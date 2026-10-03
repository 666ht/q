package io.anonero.services

import io.anonero.model.PendingTransaction
import io.anonero.model.Wallet
import io.anonero.model.WalletListener
import io.anonero.model.WalletManager
import timber.log.Timber

/**
 * Handy class for starting a new thread that has a looper. The looper can then be
 * used to create handler classes. Note that start() must still be called.
 * The started Thread has a stck size of STACK_SIZE (=5MB)
 */

class MoneroHandlerThread(private val wallet: Wallet, private val walletState: WalletState) :
    Thread(null, null, "MoneroHandler", THREAD_STACK_SIZE), WalletListener {



    @Synchronized
    override fun start() {
        super.start()
    }

    override fun run() {

    }

    override fun moneySpent(txId: String?, amount: Long) {

    }

    override fun moneyReceived(txId: String?, amount: Long) {
        Timber.tag(name).i("moneyReceived: %s", amount)
        WalletManager.instance?.wallet?.store()
    }

    override fun unconfirmedMoneyReceived(txId: String?, amount: Long) {}

    override fun newBlock(height: Long) {
        Timber.tag(name).i("newBlock: %s", height)

        // Custom-height scans have their own hard end and progress cursor. Do not
        // let the daemon's full target or native synchronized flag finish them.
        if (walletState.customRescanInProgress) {
            walletState.onCustomRescanBlock(height)
            return
        }

        // Between custom-scan end and the final refreshed() callback, the wallet
        // is being moved through the normal refresh lifecycle. Do not let a new
        // block callback prematurely terminate that finalization phase.
        if (walletState.customRescanFinalizing) {
            return
        }

        if (walletState.customRescanFinished) {
            return
        }

        // Native wallet2 is authoritative for normal synchronization.
        if (!wallet.isSynchronized && wallet.nativeSynchronized) {
            // A restore callback can report nativeSynchronized before the recovery
            // rescan request has actually started. Do not finish the restore window
            // or fall back to normal-sync progress in that gap.
            if (walletState.restoreProgressInProgress &&
                !walletState.restoreProgressRescanStarted) {
                return
            }
            completeSynchronization()
            return
        }

        // Do not recreate sync progress after synchronization has finished.
        if (!wallet.isSynchronized) {
            updateSyncProgress(height)
        }
    }

    private fun updateSyncProgress(height: Long) {
        val syncHeight = wallet.getBlockChainHeight()
        val daemonHeight = wallet.getDaemonBlockChainHeight()
        val customStart = walletState.customRescanStartHeight
        val customDayEnd = walletState.customRescanDayEndHeight
        val restoreStart = walletState.restoreProgressStartHeight

        if (daemonHeight < 0) return

        val restoreActive = walletState.restoreProgressInProgress && restoreStart != null

        // Mnemonic restore has exactly one progress target: the live daemon tip.
        // Do not use getDaemonBlockChainTargetHeight(), whose estimate is the
        // source of the old ~100k phantom remaining-block range.
        val effectiveTarget = when {
            walletState.customRescanInProgress && customDayEnd != null -> {
                val customTarget = wallet.getDaemonBlockChainTargetHeight()
                minOf(customTarget.takeIf { it > 0 } ?: daemonHeight, customDayEnd)
            }
            restoreActive -> daemonHeight
            else -> wallet.getDaemonBlockChainTargetHeight().takeIf { it > 0 } ?: daemonHeight
        }

        // Restore progress has two distinct phases. Before the daemon itself has
        // reached the user-selected restore height, there is no wallet scan cursor
        // to report yet. Keep the loading indicator alive without displaying a fake
        // remaining-block count.
        if (restoreActive && daemonHeight < restoreStart) {
            walletState.syncUpdate(SyncProgress(0f, 0L))
            return
        }

        val currentHeight = when {
            walletState.customRescanInProgress && customStart != null -> {
                // Native rescans can lag behind the newBlock callback. The callback
                // is the freshest actual scan cursor available to the UI.
                maxOf(height, customStart)
            }
            restoreActive -> {
                // During seed recovery, use the greatest observed native scan cursor.
                maxOf(height, syncHeight, restoreStart)
            }
            else -> syncHeight
        }

        val progressStart = when {
            walletState.customRescanInProgress && customStart != null -> customStart
            restoreActive -> restoreStart
            else -> 0L
        }

        val left = (effectiveTarget - currentHeight).coerceAtLeast(0L)
        if (restoreActive) {
            Timber.tag(name).d(
                "restore progress start=%d wallet=%d daemon=%d target=%d left=%d",
                restoreStart, syncHeight, daemonHeight, effectiveTarget, left
            )
            // Once the real wallet scan cursor reaches the live daemon tip,
            // close restore synchronization immediately. Do not start another
            // refresh/sync cycle after the progress reaches 100%.
            if (currentHeight >= daemonHeight) {
                walletState.syncUpdate(SyncProgress(1f, 0L))
                completeSynchronization()
                return
            }
        }
        val progress = if (effectiveTarget <= progressStart) {
            1f
        } else {
            ((currentHeight - progressStart).toDouble() /
                (effectiveTarget - progressStart).toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()
        }
        walletState.syncUpdate(SyncProgress(progress, left))
    }

    override fun updated() {
        refresh(false)
        Timber.tag(name).i("updated()")
        walletState.update()
    }

    override fun refreshed() {
        val status = wallet.fullStatus.connectionStatus
        val daemonHeight = wallet.getDaemonBlockChainHeight()
        val chainHeight = wallet.getBlockChainHeight()
        /// height
        Timber.tag(name)
            .i("refreshed() status:${status} daemonHeight:$daemonHeight chainHeight:$chainHeight ")
        if (walletState.customRescanInProgress) {
            // The 400ms custom-height poller reads the actual native scan height.
            // A refresh callback must not promote the custom end into a full sync.
            walletState.onCustomRescanBlock(chainHeight)
            walletState.update()
            return
        }
        if (walletState.customRescanFinalizing) {
            // The custom scan itself has ended. This callback is the normal refresh
            // barrier: only now reload the complete transaction/coin state and end
            // the progress indicator. This avoids exposing a partially refreshed
            // transaction list immediately after the scan cursor hits the end.
            try {
                wallet.refreshHistory()
                wallet.refreshCoins(force = true)
                wallet.store()
                walletState.update()
                walletState.customRescanFinalizing = false
                walletState.customRescanFinished = true
                walletState.finishSync()
                Timber.tag(name).i(
                    "Custom rescan final refresh complete: daemonHeight=%d chainHeight=%d",
                    daemonHeight, chainHeight
                )
            } catch (e: Exception) {
                Timber.tag(name).e(e, "Failed to finalize custom rescan refresh")
                walletState.customRescanFinalizing = false
                walletState.customRescanFinished = true
                walletState.finishSync()
            }
            return
        }
        if (walletState.customRescanFinished) {
            walletState.update()
            return
        }
        if (status === Wallet.ConnectionStatus.ConnectionStatus_Disconnected || status == null) {
            tryRestartConnection()
        } else {
            if (wallet.nativeSynchronized) {
                if (!walletState.restoreProgressInProgress ||
                    walletState.restoreProgressRescanStarted) {
                    completeSynchronization()
                }
            } else if (!walletState.restoreProgressInProgress) {
                // During mnemonic restore, progress is driven by newBlock(height).
                // chainHeight is the wallet scan cursor, not the live network tip.
                updateSyncProgress(chainHeight)
            }

        }
        walletState.update()
    }

    private fun completeSynchronization() {
        val restoreWasActive = walletState.restoreProgressInProgress

        if (wallet.isSynchronized && !walletState.customRescanInProgress) {
            if (restoreWasActive) {
                finalizeRestoreSynchronization()
            } else {
                walletState.finishRestoreProgress()
                walletState.finishSync()
            }
            return
        }

        try {
            wallet.setSynchronized()
            walletState.customRescanStartHeight = null
            walletState.customRescanDayEndHeight = null
            walletState.customRescanInProgress = false

            // Publish the completed scan while restore mode is still active so
            // the existing progress bar can make a brief 100% completion transition.
            walletState.syncUpdate(SyncProgress(1f, 0L))
            walletState.finishRestoreProgress()

            // Refresh the already-complete wallet data. No wallet.startRefresh() is
            // called here, so completion cannot turn into a second sync pass.
            refresh(true)
            wallet.store()
            walletState.update()
            walletState.finishSync()
            walletState.update()
        } catch (e: Exception) {
            Timber.tag(name).e(e, "Failed to finalize synchronized wallet data")
            if (restoreWasActive) {
                walletState.finishRestoreProgress()
            }
            walletState.finishSync()
        }
    }

    private fun finalizeRestoreSynchronization() {
        try {
            // Native synchronization is already complete here. Keep the existing
            // bar at 100% during the final data refresh, then remove it.
            walletState.syncUpdate(SyncProgress(1f, 0L))
            // Only refresh the final wallet data; never restart the daemon worker.
            wallet.refreshHistory()
            wallet.refreshCoins(force = true)
            wallet.store()
            walletState.finishRestoreProgress()
            walletState.update()
            walletState.finishSync()
            walletState.update()
        } catch (e: Exception) {
            Timber.tag(name).e(e, "Failed to finalize restored wallet data")
            walletState.finishRestoreProgress()
            walletState.finishSync()
        }
    }


    private fun tryRestartConnection() {
        wallet.init(0)
        wallet.startRefresh()
        walletState.update()
    }

    private fun refresh(walletSynced: Boolean) {
        wallet.refreshHistory()
        if (walletSynced) {
            wallet.refreshCoins()
        }
        walletState.update()
    }

    fun sendTx(pendingTx: PendingTransaction): Boolean {
        return pendingTx.commit("", true)
    }

    interface Listener {
        fun onRefresh(walletSynced: Boolean)
        fun onConnectionFail()
        fun onNewBlockFound(block: Long)
    }

    companion object {
        // from src/cryptonote_config.h
        const val THREAD_STACK_SIZE = (5 * 1024 * 1024).toLong()
    }
}