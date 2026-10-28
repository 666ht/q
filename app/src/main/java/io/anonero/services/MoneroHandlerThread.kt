package io.anonero.services

import io.anonero.model.PendingTransaction
import io.anonero.model.Wallet
import io.anonero.model.WalletListener
import io.anonero.model.WalletManager
import io.anonero.services.calculateSyncProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

    // Native WalletListener callbacks must return before JNI/history work is performed.
    // Coalesce block callbacks so a fast scan cannot flood the IO dispatcher.
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingBlockHeight = AtomicLong(-1L)
    private val blockCallbackWorkerActive = AtomicBoolean(false)
    private val walletUpdateCallbackPending = AtomicBoolean(false)
    private val walletUpdateCallbackWorkerActive = AtomicBoolean(false)
    private val refreshedCallbackPending = AtomicBoolean(false)
    private val refreshedCallbackWorkerActive = AtomicBoolean(false)



    @Synchronized
    override fun start() {
        super.start()
    }

    override fun run() {

    }

    override fun moneySpent(txId: String?, amount: Long) {

    }

    override fun moneyReceived(txId: String?, amount: Long) {
        if (walletState.isWiping()) return
        Timber.tag(name).i("moneyReceived: %s", amount)
        WalletManager.instance?.wallet?.store()
    }

    override fun unconfirmedMoneyReceived(txId: String?, amount: Long) {}

    override fun newBlock(height: Long) {
        if (walletState.isWiping()) return
        pendingBlockHeight.accumulateAndGet(height) { pending, next -> maxOf(pending, next) }
        scheduleBlockCallbackProcessing()
    }

    private fun scheduleBlockCallbackProcessing() {
        if (!blockCallbackWorkerActive.compareAndSet(false, true)) return
        callbackScope.launch {
            try {
                while (true) {
                    val height = pendingBlockHeight.getAndSet(-1L)
                    if (height < 0L) break
                    if (WalletManager.instance?.wallet !== wallet || !wallet.isInitialized) break
                    processNewBlock(height)
                }
            } catch (e: Exception) {
                Timber.tag(name).w(e, "Deferred new-block processing failed")
            } finally {
                blockCallbackWorkerActive.set(false)
                if (pendingBlockHeight.get() >= 0L &&
                    WalletManager.instance?.wallet === wallet &&
                    wallet.isInitialized &&
                    !walletState.isWiping()
                ) scheduleBlockCallbackProcessing()
            }
        }
    }

    private fun processNewBlock(height: Long) {
        if (walletState.isWiping()) return
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
        // Custom-height scans have their own hard end and progress cursor. Do not
        // let the daemon's full target or native synchronized flag finish them.
        if (walletState.customRescanInProgress) {
            val selectedStartHeight = walletState.customRescanStartHeight

            // wallet2::fast_refresh emits newBlock callbacks while rebuilding the
            // blockchain hash prefix before it reaches the selected transaction
            // scan height. Those callbacks are NOT scanned transactions. Refreshing
            // history for them adds needless wallet work during the hand-off and
            // makes logs look as if the recovery were scanning from an older height.
            // The native cursor poller continues independently until the selected
            // height is reached.
            if (selectedStartHeight != null && height < selectedStartHeight) {
                return
            }

            Timber.tag(name).i(
                "Custom-height transaction scan callback: height=%d selectedStart=%d",
                height,
                selectedStartHeight ?: height
            )
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

        Timber.tag(name).i("newBlock: %s", height)

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
            val daemonTargetHeight = runCatching {
                wallet.getDaemonBlockChainTargetHeight()
            }.getOrDefault(0L).coerceAtLeast(0L)
            if (keepNormalSyncProgress(scanHeight, daemonHeight, daemonTargetHeight)) {
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

    private fun updateSyncProgress(
        @Suppress("UNUSED_PARAMETER") height: Long,
        daemonTargetHeight: Long = 0L
    ) {
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

        // A newly created Polyseed wallet may have a native birthday-derived
        // refresh-from height. Use the actual scan origin so the percentage and
        // remaining-block count describe the same range (instead of measuring from
        // genesis while the wallet starts scanning later).
        val progressStart = if (restoreActive) {
            restoreStart
        } else {
            runCatching { wallet.getRestoreHeight() }
                .getOrDefault(0L)
                .coerceAtLeast(0L)
        }
        // A restore/birthday height can exceed the connected node's current tip
        // (for example, with a stale or wrong-network node). Do not clamp the
        // target up to the start and report a false 100% / zero remaining.
        if (effectiveTarget < progressStart) {
            Timber.tag(name).w(
                "Waiting for daemon height to reach scan start: start=%d daemon=%d",
                progressStart, effectiveTarget
            )
            walletState.waitForSyncHeights()
            return
        }

        // Use the daemon target for the progress fraction only. Remaining blocks
        // still count to the live daemon tip, avoiding a misleading huge countdown.
        val progressTarget = if (restoreActive) effectiveTarget else maxOf(effectiveTarget, daemonTargetHeight)
        val calculatedProgress = calculateSyncProgress(progressStart, currentHeight, progressTarget)
        val progress = if (!restoreActive && progressTarget > effectiveTarget) {
            SyncProgress(
                progress = calculatedProgress.progress.coerceAtMost(0.999f),
                left = (effectiveTarget - syncHeight).coerceAtLeast(0L),
                isComplete = false
            )
        } else {
            calculatedProgress
        }
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
        if (walletState.isWiping()) return
        if (walletState.userOperationTransitionInProgress ||
            walletState.customRescanPreparing ||
            walletState.restoreProgressFinalizing ||
            walletState.customRescanFinalizing
        ) return
        walletUpdateCallbackPending.set(true)
        scheduleWalletUpdateOutsideNativeCallback()
    }

    private fun scheduleWalletUpdateOutsideNativeCallback() {
        if (!walletUpdateCallbackWorkerActive.compareAndSet(false, true)) return
        callbackScope.launch {
            try {
                while (walletUpdateCallbackPending.getAndSet(false)) {
                    if (WalletManager.instance?.wallet !== wallet || !wallet.isInitialized) break
                    if (walletState.isWiping() ||
                        walletState.userOperationTransitionInProgress ||
                        walletState.customRescanPreparing ||
                        walletState.restoreProgressFinalizing ||
                        walletState.customRescanFinalizing
                    ) continue
                    refresh(false)
                    Timber.tag(name).i("updated() processed outside native wallet callback")
                }
            } catch (e: Exception) {
                Timber.tag(name).w(e, "Deferred wallet update failed")
            } finally {
                walletUpdateCallbackWorkerActive.set(false)
                if (walletUpdateCallbackPending.get() &&
                    WalletManager.instance?.wallet === wallet &&
                    wallet.isInitialized &&
                    !walletState.isWiping()
                ) scheduleWalletUpdateOutsideNativeCallback()
            }
        }
    }

    override fun refreshed() {
        if (walletState.isWiping()) return
        refreshedCallbackPending.set(true)
        scheduleRefreshedProcessingOutsideNativeCallback()
    }

    private fun scheduleRefreshedProcessingOutsideNativeCallback() {
        if (!refreshedCallbackWorkerActive.compareAndSet(false, true)) return
        callbackScope.launch {
            try {
                while (refreshedCallbackPending.getAndSet(false)) {
                    if (WalletManager.instance?.wallet !== wallet || !wallet.isInitialized) break
                    processRefreshedCallback()
                }
            } catch (e: Exception) {
                Timber.tag(name).w(e, "Deferred refreshed callback processing failed")
            } finally {
                refreshedCallbackWorkerActive.set(false)
                if (refreshedCallbackPending.get() &&
                    WalletManager.instance?.wallet === wallet &&
                    wallet.isInitialized
                ) scheduleRefreshedProcessingOutsideNativeCallback()
            }
        }
    }

    private fun processRefreshedCallback() {
        if (walletState.isWiping()) return
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

        val daemonTargetHeight = if (
            status == Wallet.ConnectionStatus.ConnectionStatus_Connected
        ) {
            runCatching { wallet.getDaemonBlockChainTargetHeight() }
                .getOrDefault(0L).coerceAtLeast(0L)
        } else {
            0L
        }
        if (status === Wallet.ConnectionStatus.ConnectionStatus_Disconnected || status == null) {
            tryRestartConnection()
        } else if (keepNormalSyncProgress(chainHeight, daemonHeight, daemonTargetHeight)) {
            // The existing native refresh worker owns the scan. Do not start another
            // refresh cycle from its completion callback.
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
    private fun keepNormalSyncProgress(
        scanHeight: Long,
        daemonHeight: Long,
        daemonTargetHeight: Long
    ): Boolean {
        if (daemonHeight <= 0L || scanHeight < 0L) {
            walletState.waitForSyncHeights()
            return true
        }
        if (scanHeight < daemonHeight || daemonTargetHeight > daemonHeight) {
            // Keep the wallet unsynchronized until both its scan cursor and the daemon
            // target are caught up. The counter tracks remaining blocks to the live tip;
            // the progress fraction may follow the node target while the node is syncing.
            wallet.isSynchronized = false
            updateSyncProgress(scanHeight, daemonTargetHeight)
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