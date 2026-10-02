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

        // A completed custom scan stays paused until the user explicitly starts
        // a normal refresh again.
        if (walletState.customRescanFinished) {
            return
        }

        // Native wallet2 is authoritative for normal synchronization.
        if (!wallet.isSynchronized && wallet.nativeSynchronized) {
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
        val targetHeight = wallet.getDaemonBlockChainTargetHeight()
        val customStart = walletState.customRescanStartHeight
        val customDayEnd = walletState.customRescanDayEndHeight
        val restoreStart = walletState.restoreProgressStartHeight

        if (syncHeight < 0 || daemonHeight < 0) return

        val effectiveTarget = when {
            walletState.customRescanInProgress && customDayEnd != null -> {
                minOf(targetHeight.takeIf { it > 0 } ?: daemonHeight, customDayEnd)
            }
            walletState.restoreProgressInProgress && restoreStart != null -> {
                // Restore remaining blocks are the remote daemon height minus the
                // wallet's local scan cursor. daemonBlockChainHeight() is the
                // documented current daemon height; newBlock(height) is a processed
                // block callback and is not the network endpoint.
                daemonHeight
            }
            else -> targetHeight.takeIf { it > 0 } ?: daemonHeight
        }

        // Restore progress has two distinct phases. Before the daemon itself has
        // reached the user-selected restore height, there is no wallet scan cursor
        // to report yet. Do not present that node catch-up distance as restore blocks.
        if (walletState.restoreProgressInProgress && restoreStart != null &&
            daemonHeight < restoreStart) {
            walletState.syncUpdate(SyncProgress(0f, 0L))
            return
        }

        val currentHeight = when {
            walletState.customRescanInProgress && customStart != null -> {
                // Native rescans can lag behind the newBlock callback. The callback
                // is the freshest actual scan cursor available to the UI.
                maxOf(height, customStart)
            }
            walletState.restoreProgressInProgress && restoreStart != null -> {
                // For mnemonic restore, use the wallet's actual scanned height. The
                // newBlock callback reports daemon blocks and can be ahead of the
                // wallet scan cursor.
                maxOf(syncHeight, restoreStart)
            }
            else -> syncHeight
        }

        val progressStart = when {
            walletState.customRescanInProgress && customStart != null -> customStart
            walletState.restoreProgressInProgress && restoreStart != null -> restoreStart
            else -> 0L
        }

        val left = (effectiveTarget - currentHeight).coerceAtLeast(0L)
        if (walletState.restoreProgressInProgress && restoreStart != null) {
            Timber.tag(name).d(
                "restore progress start=%d wallet=%d daemon=%d target=%d left=%d",
                restoreStart, syncHeight, daemonHeight, effectiveTarget, left
            )
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
        if (walletState.customRescanFinished) {
            walletState.update()
            return
        }
        if (status === Wallet.ConnectionStatus.ConnectionStatus_Disconnected || status == null) {
            tryRestartConnection()
        } else {
            if (wallet.nativeSynchronized) {
                completeSynchronization()
            } else if (!walletState.restoreProgressInProgress) {
                // During mnemonic restore, progress is driven by newBlock(height).
                // chainHeight is the wallet scan cursor, not the live network tip.
                updateSyncProgress(chainHeight)
            }

        }
        walletState.update()
    }

    private fun completeSynchronization() {
        if (wallet.isSynchronized && !walletState.customRescanInProgress) {
            walletState.finishSync()
            return
        }

        try {
            wallet.setSynchronized()
            walletState.customRescanStartHeight = null
            walletState.customRescanDayEndHeight = null
            walletState.customRescanInProgress = false
            walletState.finishRestoreProgress()

            // End the sync indicator first so completion is visible immediately.
            // Then load the final balance and transaction history.
            walletState.update()
            walletState.finishSync()

            refresh(true)
            wallet.store()
            walletState.update()
        } catch (e: Exception) {
            Timber.tag(name).e(e, "Failed to finalize synchronized wallet data")
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