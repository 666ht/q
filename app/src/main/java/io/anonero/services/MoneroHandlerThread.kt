package io.anonero.services

import io.anonero.model.PendingTransaction
import io.anonero.model.Wallet
import io.anonero.model.WalletListener
import io.anonero.model.WalletManager
import io.anonero.services.calculateSyncProgress
import timber.log.Timber

/**
 * Handy class for starting a new thread that has a looper. The looper can then be
 * used to create handler classes. Note that start() must still be called.
 * The started Thread has a stck size of STACK_SIZE (=5MB)
 */

class MoneroHandlerThread(
    private val wallet: Wallet,
    private val walletState: WalletState,
    private val onRestoreCompleted: () -> Unit = {}
) :
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
        if (walletState.heightRefreshInProgress) {
            val status = runCatching { wallet.fullStatus.connectionStatus }.getOrNull()
            val daemonHeight = runCatching { wallet.getDaemonBlockChainHeight() }.getOrDefault(0L)
            walletState.recordOrdinaryRefreshHeight(
                daemonHeight,
                status == Wallet.ConnectionStatus.ConnectionStatus_Connected
            )
            return
        }
        if (walletState.userOperationTransitionInProgress) {
            Timber.tag(name).d("Ignoring stale block callback during latest-operation handoff")
            return
        }
        if (walletState.customRescanPreparing) {
            Timber.tag(name).d("Ignoring block callback while custom rescan is being prepared")
            return
        }
        Timber.tag(name).i("newBlock: %s", height)

        // Custom-height scans have their own hard end and progress cursor. Do not
        // let the daemon's full target or native synchronized flag finish them.
        if (walletState.customRescanInProgress) {
            walletState.requestScanDataRefresh()
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

        // For mnemonic restore, the displayed remaining-block counter is the
        // completion authority. Never switch to nativeSynchronized early.
        if (walletState.restoreProgressCompleted) {
            return
        }
        if (walletState.restoreProgressInProgress) {
            // Show discovered balance/transactions while the restore scan is
            // still running, without changing the sync/progress lifecycle.
            walletState.requestScanDataRefresh()
            updateSyncProgress(height)
            return
        }

        // Native wallet2 can report its refresh cycle as synchronized while the
        // live daemon tip is still ahead. Do not hide progress or mark the wallet
        // complete until the local scan cursor is caught up with the daemon.
        if (!wallet.isSynchronized && wallet.nativeSynchronized) {
            // A restore callback can report nativeSynchronized before the recovery
            // rescan request has actually started. Do not finish the restore window
            // or fall back to normal-sync progress in that gap.
            if (walletState.restoreProgressInProgress &&
                !walletState.restoreProgressRescanStarted) {
                return
            }
            val scanHeight = runCatching { wallet.getBlockChainHeight() }.getOrDefault(-1L)
            val daemonHeight = runCatching { wallet.getDaemonBlockChainHeight() }.getOrDefault(0L)
            if (keepNormalSyncProgress(scanHeight, daemonHeight)) {
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

    private fun updateSyncProgress(@Suppress("UNUSED_PARAMETER") height: Long) {
        // Custom rescans have a single progress publisher in WalletState's 400ms
        // cursor poller; normal callbacks must not overwrite it with a different height.
        if (walletState.customRescanPreparing ||
            walletState.customRescanInProgress ||
            walletState.customRescanFinalizing
        ) return

        val syncHeight = wallet.getBlockChainHeight()
        val daemonHeight = wallet.getDaemonBlockChainHeight()
        val restoreStart = walletState.restoreProgressStartHeight

        if (daemonHeight < 0L || syncHeight < 0L) {
            walletState.waitForSyncHeights()
            return
        }

        val restoreActive = walletState.restoreProgressInProgress && restoreStart != null

        // The daemon's current height is the only live target. Its target-height
        // estimate can be tens of thousands of blocks ahead and creates phantom
        // remaining counts, so never use getDaemonBlockChainTargetHeight() here.
        val effectiveTarget = daemonHeight

        // Keep a small number of newly mined blocks on the normal refresh indicator.
        // wallet2 still scans them; this only avoids reopening the full sync counter
        // every time an already-synced wallet is reopened.
        if (!restoreActive &&
            effectiveTarget - syncHeight > 0L &&
            !walletState.shouldShowNormalSyncProgress(syncHeight, effectiveTarget)
        ) {
            walletState.showOrdinaryCatchUpRefresh()
            Timber.tag(name).d(
                "Small normal catch-up: wallet=%d daemon=%d left=%d; keeping refresh indicator",
                syncHeight, effectiveTarget, effectiveTarget - syncHeight
            )
            return
        }

        // Restore progress has two distinct phases. Before the daemon itself has
        // reached the user-selected restore height, there is no wallet scan cursor
        // to report yet. Keep the loading indicator alive without displaying a fake
        // remaining-block count.
        if (restoreActive && (
                daemonHeight < restoreStart ||
                !walletState.restoreProgressRescanStarted ||
                !walletState.hasObservedRestoreProgressCursor(syncHeight)
            )
        ) {
            walletState.waitForRestoreHeight()
            return
        }

        val currentHeight = if (restoreActive) {
            // Restore progress cannot start before the daemon reaches restoreStart.
            // Once active, do not reuse a stale newBlock callback as the cursor.
            maxOf(syncHeight, restoreStart)
        } else {
            syncHeight
        }

        val progressStart = if (restoreActive) restoreStart else 0L

        val progress = calculateSyncProgress(progressStart, currentHeight, effectiveTarget)
        val left = progress.left
        if (restoreActive) {
            if (walletState.restoreProgressCompleted) return
            Timber.tag(name).d(
                "restore progress start=%d wallet=%d daemon=%d target=%d left=%d",
                restoreStart, syncHeight, daemonHeight, effectiveTarget, left
            )
            // Remaining blocks and percentage share exactly the same calculation.
            // A zero remainder starts the final refresh, but its terminal state stays
            // visible until finalizeRestoreSynchronization publishes the final data.
            if (left == 0L) {
                walletState.syncUpdate(progress)
                walletState.restoreProgressCompleted = true
                completeSynchronization()
                return
            }
        }
        walletState.syncUpdate(progress)
    }

    override fun updated() {
        if (walletState.userOperationTransitionInProgress) {
            Timber.tag(name).d("updated() ignored during latest-operation handoff")
            return
        }
        // During either finalization phase, wallet2 can emit an intermediate
        // callback while history/coins are temporarily incomplete. Do not
        // publish a transient snapshot or start another refresh loop.
        if (walletState.customRescanPreparing ||
            walletState.restoreProgressFinalizing ||
            walletState.customRescanFinalizing
        ) {
            Timber.tag(name).i("updated() ignored during sync/reset transition")
            return
        }
        refresh(false)
        Timber.tag(name).i("updated()")
        walletState.update()
    }

    override fun refreshed() {
        val status = runCatching { wallet.fullStatus.connectionStatus }.getOrNull()
        val daemonHeight = runCatching { wallet.getDaemonBlockChainHeight() }.getOrDefault(0L)
        if (walletState.heightRefreshInProgress) {
            walletState.recordOrdinaryRefreshHeight(
                daemonHeight,
                status == Wallet.ConnectionStatus.ConnectionStatus_Connected
            )
            Timber.tag(name).i(
                "Ordinary refresh height callback: status=%s daemonHeight=%d",
                status, daemonHeight
            )
            // Preserve connection recovery while waiting for the live daemon height.
            if (status == Wallet.ConnectionStatus.ConnectionStatus_Disconnected || status == null) {
                tryRestartConnection()
            }
            return
        }
        if (walletState.userOperationTransitionInProgress) {
            Timber.tag(name).d("refreshed() ignored during latest-operation handoff")
            return
        }
        if (walletState.customRescanPreparing) {
            Timber.tag(name).d("Ignoring refreshed callback while custom rescan is being prepared")
            return
        }
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
            // WalletState completes the custom rescan directly after the selected
            // end height. Ignore late native refresh callbacks so they cannot start
            // a second refresh or recreate the progress indicator.
            Timber.tag(name).i("refreshed() ignored during custom rescan finalization")
            return
        }
        if (walletState.customRescanFinished) {
            walletState.update()
            return
        }
        if (walletState.restoreProgressFinalizing) {
            // Final restore refresh owns the single UI publication.
            Timber.tag(name).i("refreshed() ignored during restore final refresh")
            return
        }
        if (walletState.restoreProgressCompleted) {
            walletState.update()
            return
        }
        if (walletState.restoreProgressInProgress) {
            updateSyncProgress(chainHeight)
            return
        }

        // A manual refresh requested during ordinary synchronization is safe now:
        // doRefresh() has reached its listener callback boundary, so no second JNI
        // history read races the block-processing loop. Final-sync paths below
        // already refresh the complete history and therefore need no extra read.
        if (walletState.consumeDeferredManualRefresh() && !wallet.isSynchronized) {
            runCatching {
                wallet.refreshHistory()
                walletState.update()
            }.onFailure {
                Timber.tag(name).e(it, "Deferred manual refresh failed")
            }
        }

        if (status === Wallet.ConnectionStatus.ConnectionStatus_Disconnected || status == null) {
            tryRestartConnection()
        } else if (keepNormalSyncProgress(chainHeight, daemonHeight)) {
            // The daemon is reachable but the wallet cursor is behind. Publish the
            // real remaining-block count first, then resume the existing native scan.
            // Re-running wallet.init() here resets the connection path and can leave
            // the UI stuck showing ordinary refresh instead of scan progress.
            runCatching { wallet.startRefresh() }
                .onFailure { Timber.tag(name).e(it, "Failed to resume block synchronization") }
        } else {
            if (!wallet.isSynchronized) {
                updateSyncProgress(chainHeight)
            }
            // Only mark synchronized after comparing valid live heights and confirming
            // that no meaningful daemon blocks remain.
            wallet.setSynchronized()
            wallet.store()
            refresh(true)
            walletState.finishSync()
        }
        walletState.update()
    }

    /**
     * Keep the normal-sync progress visible while the live daemon has blocks
     * the wallet has not scanned yet. A missing daemon height is not proof of
     * synchronization; keep waiting for a valid height instead of finishing early.
     */
    private fun keepNormalSyncProgress(scanHeight: Long, daemonHeight: Long): Boolean {
        if (daemonHeight <= 0L || scanHeight < 0L) {
            walletState.waitForSyncHeights()
            return true
        }
        if (daemonHeight - scanHeight >= 2L) {
            // A wallet that has already completed its initial scan should not reopen
            // the full block-sync UI just because an ordinary refresh found newer blocks.
            // Continue the native scan and keep the ordinary refresh indicator; reserve
            // numeric block progress for first sync, restore, and explicit reset scans.
            if (walletState.hasCompletedSyncBefore() &&
                (wallet.isSynchronized || wallet.nativeSynchronized) &&
                !walletState.restoreProgressInProgress &&
                !walletState.customRescanInProgress &&
                !walletState.customRescanFinalizing
            ) {
                walletState.showOrdinaryCatchUpRefresh()
                Timber.tag(name).d(
                    "Previously-synced wallet catching up silently: wallet=%d daemon=%d left=%d",
                    scanHeight, daemonHeight, daemonHeight - scanHeight
                )
            } else {
                // Record the initial backlog size before the progress callback re-reads heights.
                walletState.shouldShowNormalSyncProgress(scanHeight, daemonHeight)
                updateSyncProgress(scanHeight)
            }
            return true
        }
        return false
    }

    private fun completeSynchronization() {
        val restoreWasActive = walletState.restoreProgressInProgress ||
            walletState.restoreProgressCompleted

        if (restoreWasActive) {
            finalizeRestoreSynchronization()
            return
        }

        if (wallet.isSynchronized && !walletState.customRescanInProgress) {
            // Another native callback may have marked the wallet synchronized first.
            // Do not exit before refreshing/publishing the final balance and outputs.
            runCatching {
                wallet.refreshHistory()
                wallet.refreshCoins(force = true)
                wallet.store()
            }.onFailure {
                Timber.tag(name).e(it, "Failed to refresh already-synchronized wallet data")
            }
            walletState.finishRestoreProgress()
            walletState.finishSync()
            walletState.update()
            return
        }

        try {
            wallet.setSynchronized()
            walletState.customRescanStartHeight = null
            walletState.customRescanDayEndHeight = null
            walletState.customRescanInProgress = false

            refresh(true)
            wallet.store()
            walletState.update()
            walletState.finishRestoreProgress()
            walletState.finishSync()
            walletState.update()
        } catch (e: Exception) {
            Timber.tag(name).e(e, "Failed to finalize synchronized wallet data")
            walletState.finishSync()
        }
    }

    private fun finalizeRestoreSynchronization() {
        walletState.restoreProgressFinalizing = true
        try {
            wallet.setSynchronized()

            // Match custom-rescan completion: keep 100% and zero remaining visible
            // while refreshing the final history and outputs, then hide progress.
            walletState.syncUpdate(SyncProgress(1f, 0L))
            walletState.finishRestoreProgress()
            wallet.refreshHistory()
            wallet.refreshCoins(force = true)
            wallet.store()

            // Open the publication gate before finishSync(): its final update must
            // replace the temporary merged transaction list with the exact refreshed
            // wallet history, matching the custom-rescan completion path.
            walletState.restoreProgressFinalizing = false
            walletState.finishSync()

            // Keep the terminal guard until the final snapshot has been published.
            // Leaving it set after this point would block later ordinary refreshes.
            walletState.restoreProgressCompleted = false
            runCatching { onRestoreCompleted() }
                .onFailure { Timber.tag(name).e(it, "Failed to clear completed restore request") }
        } catch (e: Exception) {
            Timber.tag(name).e(e, "Failed to finalize restored wallet data")
            walletState.finishRestoreProgress()
            walletState.restoreProgressFinalizing = false
            walletState.finishSync()
            walletState.restoreProgressCompleted = false
        }
    }


    private fun tryRestartConnection() {
        // This callback is a reconnect/sync transition, not an active manual refresh.
        walletState.finishManualRefreshIndicator()
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