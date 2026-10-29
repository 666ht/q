package io.anonero.services

import android.content.SharedPreferences
import androidx.compose.ui.util.fastDistinctBy
import androidx.compose.ui.util.fastFilter
import io.anonero.AnonConfig
import io.anonero.model.CoinsInfo
import io.anonero.model.Subaddress
import io.anonero.model.TransactionInfo
import io.anonero.model.Wallet
import io.anonero.model.WalletManager
import io.anonero.util.RESTORE_HEIGHT
import io.anonero.model.node.DaemonInfo
import io.anonero.ui.util.getAllUsedSubAddresses
import io.anonero.ui.util.getLatestSubAddress
import io.anonero.ui.home.LockScreenShortCut
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class SyncProgress(val progress: Float, val left: Long)

/**
 * Shared progress calculation for mnemonic recovery and in-wallet custom rescans.
 * Both paths use the same start/current/target arithmetic so the percentage and
 * remaining-block counter cannot drift apart.
 */
fun calculateSyncProgress(startHeight: Long, currentHeight: Long, targetHeight: Long): SyncProgress {
    val safeStart = startHeight.coerceAtLeast(0L)
    val safeTarget = targetHeight.coerceAtLeast(safeStart)
    val safeCurrent = currentHeight.coerceIn(safeStart, safeTarget)
    val total = (safeTarget - safeStart).coerceAtLeast(1L)
    val left = (safeTarget - safeCurrent).coerceAtLeast(0L)
    val progress = if (safeCurrent >= safeTarget) {
        1f
    } else {
        ((safeCurrent - safeStart).toDouble() / total.toDouble())
            .coerceIn(0.0, 1.0)
            .toFloat()
    }
    return SyncProgress(progress, left)
}

private const val TAG = "WalletState"
private const val PREF_WALLET_HAS_COMPLETED_SYNC = "walletHasCompletedSync"
private const val PREF_LAST_COMPLETED_SCAN_HEIGHT = "lastCompletedScanHeight"
private const val NORMAL_SYNC_PROGRESS_DISPLAY_THRESHOLD = 10L

class WalletState(private val prefs: SharedPreferences) {
    private var _blockUpdates = AtomicBoolean(false)
    val hideAmountsFlow = MutableStateFlow(false)
    private val _isLoading = MutableStateFlow(false)
    // Only an explicit user-triggered pull-to-refresh may show the generic refresh spinner.
    // Automatic sync/connect callbacks must not recreate that spinner.
    private val _manualRefreshInProgress = MutableStateFlow(false)
    val manualRefreshInProgress: Flow<Boolean> = _manualRefreshInProgress

    fun finishManualRefreshIndicator() {
        _manualRefreshInProgress.value = false
    }

    // Hide the connection-wait spinner while a selected-height scan is being prepared.
    // The real remaining-block counter becomes visible as soon as the native cursor is valid.
    private val _suppressConnectionWaitIndicator = MutableStateFlow(false)
    private var _isSyncing = AtomicBoolean(false)
    private val _isWiping = AtomicBoolean(false)
    private val _backgroundSync = MutableStateFlow(false)
    private val _incomingTx = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val _transactions = MutableStateFlow<List<TransactionInfo>>(listOf())
    private val _subAddresses = MutableStateFlow<List<Subaddress>>(listOf())
    private val _balanceInfo = MutableStateFlow<Long?>(null)
    private val _unLockedBalance = MutableStateFlow<Long?>(null)
    private val _walletStatus = MutableStateFlow<Wallet.Status?>(null)
    private val _nextAddress = MutableStateFlow<Subaddress?>(null)
    private val _coins = MutableStateFlow<List<CoinsInfo>>(arrayListOf())
    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)
    // Visibility policy for ordinary catch-up scans. A wallet may be fully synced
    // from the previous session but have only a handful of new blocks on re-entry.
    // Those blocks must still be scanned, but should not reopen the full block-count UI.
    @Volatile
    private var normalSyncProgressVisible: Boolean? = null
    // A previously-synced wallet may be catching up without a numeric block counter.
    // Protect its native scan from direct history/output queries until finishSync().
    @Volatile
    private var ordinaryCatchUpInProgress: Boolean = false
    // First callbacks after reopening may briefly report a zero/old scan height
    // before wallet2 finishes restoring its persisted cursor.
    @Volatile
    private var invalidStartupHeightSince: Long = 0L
    private val _connectedDaemon = MutableStateFlow<DaemonInfo?>(null)
    // Keep valid node heights in the singleton wallet state, not a screen ViewModel.
    // This cache survives leaving/re-entering NodeSettings while wallet sync continues.
    private val knownDaemonHeights = ConcurrentHashMap<String, Long>()
    private val _connectionStatus = MutableStateFlow<Wallet.ConnectionStatus?>(null)
    private val _previousConnectionStatus = AtomicReference<Wallet.ConnectionStatus?>(null)
    private val _unlockShortcut = Channel<LockScreenShortCut>(capacity = 1)
    val unlockShortcut = _unlockShortcut.receiveAsFlow()
    private val bgSyncMutex = Mutex()
    // Activity teardown must not cancel a wallet background-transition operation
    // while native wallet2 is switching refresh modes.
    private val backgroundLifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var backgroundLifecycleJob: Job? = null
    // Track deferred native background-sync teardown so a custom rescan cannot start
    // until wallet2 has actually left background mode and its post-exit refresh is done.
    private val backgroundSyncExitLock = Any()
    @Volatile
    private var backgroundSyncExitJob: Job? = null
    // Distinguish the UI PIN lock from native wallet2 background-sync mode.
    // Switching the native wallet while a foreground scan is active can race its refresh worker.
    private val nativeBackgroundSyncActive = AtomicBoolean(false)
    // Serialize lock intent and UI-state writes so stale lifecycle requests cannot
    // overwrite a newer successful PIN unlock.
    private val backgroundLockStateLock = Any()
    private val backgroundLockRequestGeneration = AtomicLong(0L)
    private val customRescanScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Serialize user-requested refresh/reset/query operations. The generation changes
    // as soon as a new action is requested, so older queued work can never take over later.
    private val userOperationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val userOperationMutex = Mutex()
    private val userOperationGeneration = AtomicLong(0L)
    @Volatile
    private var secureWipeCancellationId: Long = 0L
    private val userOperationTransitionLock = Any()
    private var userOperationTransitionOwner = 0L

    @Volatile
    var userOperationTransitionInProgress: Boolean = false
        private set

    private val _userOperationTransition = MutableStateFlow(false)
    val userOperationTransition: Flow<Boolean> = _userOperationTransition

    /**
     * Give a newly requested user action immediate ownership of the progress UI.
     * Old native callbacks are ignored until the new operation either publishes
     * its own scan progress or finishes starting its refresh/query.
     */
    private fun beginUserOperationTransition(): Long = synchronized(userOperationTransitionLock) {
        val operationId = userOperationGeneration.incrementAndGet()
        userOperationTransitionOwner = operationId
        userOperationTransitionInProgress = true
        _userOperationTransition.value = true
        _syncProgress.value = null
        _isSyncing.set(false)
        normalSyncProgressVisible = null
        ordinaryCatchUpInProgress = false
        _isLoading.value = true
        updateScanPreparationIndicator()
        operationId
    }

    private fun finishUserOperationTransition(operationId: Long) {
        synchronized(userOperationTransitionLock) {
            if (userOperationTransitionOwner == operationId &&
                userOperationGeneration.get() == operationId
            ) {
                userOperationTransitionOwner = 0L
                userOperationTransitionInProgress = false
                _userOperationTransition.value = false
                updateScanPreparationIndicator()
            }
        }
    }

    // A native block callback wakes the progress reader immediately; the timeout is a fallback.
    private val customRescanProgressSignals = Channel<Unit>(capacity = Channel.CONFLATED)
    private var customRescanJob: Job? = null
    // Track finalization so a new user-requested reset can wait for the prior
    // custom rescan to release the native wallet before taking over.
    private var customRescanFinishJob: Job? = null
    private val customRescanFinishStarted = AtomicBoolean(false)
    private val customRescanRequesting = AtomicBoolean(false)
    // Mnemonic recovery reuses the same custom-height scan engine as in-wallet reset.
    // Consume the one-shot recovery request only after the shared final refresh succeeds.
    @Volatile
    private var customRescanCompletionCallback: (() -> Unit)? = null
    // A manual refresh pressed during sync is replayed at the next safe native
    // refresh callback, or coalesced into the scan's final history refresh.
    private val deferredManualRefresh = AtomicBoolean(false)


    // During either restore or a custom-height rescan, expose discovered wallet
    // data while the native scan is running. Throttle refreshes off the callback
    // path and stop publishing once either scan starts finalization.
    private val scanDataRefreshRunning = AtomicBoolean(false)
    private var scanDataRefreshJob: Job? = null
    @Volatile
    private var lastScanDataRefreshAt = 0L

    // The async native request is queued before wallet2 clears its old blockchain.
    // Do not let the first poll of the old tip falsely finish the custom rescan.
    private val customRescanScanStarted = AtomicBoolean(false)

    // Native rescan can wait for an in-flight wallet refresh to release its lock.
    // While preparing, ignore callbacks from the old sync so they cannot finish
    // or publish progress for the new scan before the native reset is accepted.
    @Volatile
    var customRescanPreparing: Boolean = false
        set(value) {
            field = value
            updateScanPreparationIndicator()
        }

    @Volatile
    var customRescanInProgress: Boolean = false
        set(value) {
            field = value
            updateScanPreparationIndicator()
        }

    @Volatile
    var customRescanFinished: Boolean = false

    // True between the custom scan reaching its end and the first normal
    // refresh callback that confirms the wallet data is fully refreshed.
    @Volatile
    var customRescanFinalizing: Boolean = false

    @Volatile
    var customRescanStartHeight: Long? = null

    @Volatile
    var customRescanDayEndHeight: Long? = null

    // Restore-from-seed progress uses the user-selected restore height as its exact start.
    @Volatile
    var restoreProgressStartHeight: Long? = null

    @Volatile
    var restoreProgressInProgress: Boolean = false

    // True only after the native recovery rescan request has actually been queued.
    // Until then, an early native callback must not terminate restore progress.
    @Volatile
    var restoreProgressRescanStarted: Boolean = false

    // Capture the cursor before rescan enqueue; the old tip is not valid progress.
    @Volatile
    private var restoreProgressInitialWalletHeight: Long? = null

    @Volatile
    private var restoreProgressCursorObserved: Boolean = false

    // Once the restore remaining-block counter reaches zero, this locks the
    // completion path so late native callbacks cannot recreate sync progress.
    @Volatile
    var restoreProgressCompleted: Boolean = false

    // Final restore refresh is a transactional UI update: native callbacks may
    // temporarily expose an empty/partial history while the wallet refreshes.
    // Suppress those intermediate publications until the final data snapshot
    // is ready and MoneroHandlerThread explicitly calls update().
    @Volatile
    var restoreProgressFinalizing: Boolean = false

    // Both mnemonic recovery and in-wallet reset first run the ordinary refresh.
    // A fresh listener callback supplies the current daemon height before countdown math.
    @Volatile
    var heightRefreshInProgress: Boolean = false

    @Volatile
    private var heightRefreshStartHeight: Long? = null

    @Volatile
    private var ordinaryRefreshRequested: Boolean = false

    @Volatile
    private var ordinaryRefreshHeightWaiter: CompletableDeferred<Long>? = null

    /**
     * Publish the initial height-based progress as soon as a restore/rescan request
     * has a usable end height. This is intentionally called before waiting for the
     * native refresh mutex or for the first rescan cursor callback.
     */
    fun publishInitialHeightSyncProgress(startHeight: Long, endHeight: Long): Boolean {
        if (startHeight < 0L || endHeight < startHeight) return false

        // Gate callbacks from the previous scan before replacing its progress.
        // The native rescan is not marked active until its request is accepted.
        customRescanPreparing = true
        customRescanInProgress = false
        customRescanFinalizing = false
        customRescanFinished = false
        customRescanStartHeight = startHeight
        customRescanDayEndHeight = endHeight

        val remaining = (endHeight - startHeight).coerceAtLeast(0L)
        syncUpdate(SyncProgress(0f, remaining))
        finishOrdinaryHeightRefresh()
        Timber.tag(TAG).i(
            "Published initial height progress: start=%d end=%d left=%d",
            startHeight, endHeight, remaining
        )
        return true
    }

    fun beginOrdinaryHeightRefresh(startHeight: Long) {
        ordinaryRefreshHeightWaiter?.cancel()
        heightRefreshStartHeight = startHeight
        ordinaryRefreshRequested = false
        ordinaryRefreshHeightWaiter = CompletableDeferred()
        heightRefreshInProgress = true
        _syncProgress.value = null
        _isSyncing.set(false)
        // Keep the existing home progress area alive while we wait for the live
        // daemon tip; plain flags alone do not trigger Compose recomposition.
        _isLoading.value = true
        updateScanPreparationIndicator()
        Timber.tag(TAG).i("Starting ordinary refresh before height scan: start=%d", startHeight)
    }

    fun armOrdinaryHeightRefresh() {
        if (heightRefreshInProgress) ordinaryRefreshRequested = true
    }

    /** Avoid extra JNI height reads from callbacks while the request is only draining old work. */
    fun isAwaitingOrdinaryHeightCallback(): Boolean =
        heightRefreshInProgress &&
            ordinaryRefreshRequested &&
            ordinaryRefreshHeightWaiter?.isActive == true

    /** Accept only a fresh, connected ordinary-refresh callback with a usable live tip. */
    fun recordOrdinaryRefreshHeight(daemonHeight: Long, connected: Boolean) {
        if (!heightRefreshInProgress || !ordinaryRefreshRequested || !connected) return
        val startHeight = heightRefreshStartHeight ?: return
        if (daemonHeight <= 0L || daemonHeight < startHeight) return
        val waiter = ordinaryRefreshHeightWaiter ?: return
        if (waiter.complete(daemonHeight)) {
            Timber.tag(TAG).i(
                "Ordinary refresh obtained live daemon height: start=%d latest=%d left=%d",
                startHeight, daemonHeight, daemonHeight - startHeight
            )
        }
    }

    suspend fun awaitOrdinaryRefreshHeight(timeoutMs: Long = 120_000L): Long {
        val waiter = ordinaryRefreshHeightWaiter
            ?: throw IllegalStateException("普通刷新尚未启动")
        return withTimeoutOrNull(timeoutMs) {
            waiter.await()
        } ?: throw IllegalStateException("普通刷新未能获取节点最新高度，请检查节点连接后重试")
    }

    fun finishOrdinaryHeightRefresh() {
        heightRefreshInProgress = false
        ordinaryRefreshRequested = false
        heightRefreshStartHeight = null
        ordinaryRefreshHeightWaiter?.cancel()
        ordinaryRefreshHeightWaiter = null
        updateScanPreparationIndicator()
    }

    /**
     * Keep the node page's wallet-height value aligned with the selected scan range
     * while wallet2 is still returning the previous scan cursor. Once the native
     * rescan cursor enters the selected range, show its live height, never below
     * the selected start used by the scan-progress calculation.
     */
    fun walletHeightForDisplay(nativeHeight: Long?): Long? {
        val restoreStart = restoreProgressStartHeight
        if (restoreProgressInProgress && restoreStart != null) {
            return restoreStart
        }

        val selectedStart = customRescanStartHeight ?: heightRefreshStartHeight
        if (customRescanPreparing && selectedStart != null) {
            return selectedStart
        }
        if (customRescanInProgress && selectedStart != null) {
            if (!customRescanScanStarted.get()) return selectedStart
            return nativeHeight?.coerceAtLeast(selectedStart) ?: selectedStart
        }
        if (customRescanFinalizing && selectedStart != null) {
            return nativeHeight?.coerceAtLeast(selectedStart) ?: selectedStart
        }

        return nativeHeight
    }

    fun beginRestoreProgress(startHeight: Long) {
        lastScanDataRefreshAt = 0L
        scanDataRefreshRunning.set(false)
        restoreProgressFinalizing = false
        restoreProgressStartHeight = startHeight
        restoreProgressRescanStarted = false
        restoreProgressInitialWalletHeight = null
        restoreProgressCursorObserved = false
        restoreProgressCompleted = false
        restoreProgressInProgress = true
        // Never carry a stale count from the previous wallet. Until a reliable
        // daemon height is available, the UI shows an indeterminate sync bar;
        // publish the real remaining count immediately when that height is known.
        _syncProgress.value = null
        _isSyncing.set(false)
        _isLoading.value = false
    }

    fun markRestoreProgressRescanStarted(initialWalletHeight: Long? = null) {
        if (restoreProgressInProgress) {
            restoreProgressInitialWalletHeight = initialWalletHeight?.takeIf { it >= 0L }
                ?: restoreProgressInitialWalletHeight
            restoreProgressCursorObserved = false
            restoreProgressRescanStarted = true
        }
    }

    /**
     * True only after wallet2 has moved the cursor into the selected restore range.
     * Enqueuing an async rescan alone is not proof that the old tip has been cleared.
     */
    fun hasObservedRestoreProgressCursor(currentHeight: Long): Boolean {
        if (!restoreProgressInProgress || !restoreProgressRescanStarted || restoreProgressCompleted) {
            return false
        }
        if (restoreProgressCursorObserved) return true
        if (currentHeight < 0L) return false

        val start = restoreProgressStartHeight ?: return false
        val initial = restoreProgressInitialWalletHeight
        val observed = when {
            // If the pre-rescan wallet tip was above the selected restore height,
            // the native cursor may jump from that old tip to start+N between
            // callbacks. Waiting for an exact read at start can therefore block
            // progress forever; any drop below the captured old tip confirms reset.
            initial != null && initial > start -> currentHeight < initial
            // Without a reliable baseline, only accept an observed cursor at/below
            // the selected start. Never mistake the old high tip for live progress.
            initial == null -> currentHeight <= start
            // For a fresh wallet whose initial cursor is at/below the chosen start,
            // wait until the scan actually enters that range.
            else -> currentHeight != initial && currentHeight >= start
        }
        if (observed) restoreProgressCursorObserved = true
        return restoreProgressCursorObserved
    }

    /**
     * The selected restore cursor is not valid yet. Clear stale counts and show
     * indeterminate loading until the native cursor can be trusted.
     */
    fun waitForRestoreHeight() {
        if (!restoreProgressInProgress || restoreProgressCompleted) return
        _syncProgress.value = null
        _isSyncing.set(false)
        _isLoading.value = true
    }

    /** Clear stale numeric progress whenever native height reads are unavailable. */
    fun waitForSyncHeights() {
        if (restoreProgressInProgress && !restoreProgressCompleted) {
            waitForRestoreHeight()
            return
        }
        _syncProgress.value = null
        _isSyncing.set(false)
        _isLoading.value = true
    }

    fun initializeRestoreProgress(scanHeight: Long) {
        if (!restoreProgressInProgress) return
        restoreProgressInitialWalletHeight = scanHeight.takeIf { it >= 0L }
        restoreProgressCursorObserved = false
        // This is called before the native rescan is queued; do not publish the old tip.
        waitForRestoreHeight()
    }

    fun finishRestoreProgress() {
        restoreProgressStartHeight = null
        restoreProgressRescanStarted = false
        restoreProgressInitialWalletHeight = null
        restoreProgressCursorObserved = false
        restoreProgressInProgress = false
        // Keep the completion guard through finalization, then clear it for any
        // later ordinary refresh. Outside finalization there is no active restore
        // callback chain left to protect.
        if (!restoreProgressFinalizing) {
            restoreProgressCompleted = false
        }
        // Cancel any queued/running incremental scan refresh so it cannot race
        // the final refresh or publish a transient/partial history.
        scanDataRefreshJob?.cancel()
        scanDataRefreshJob = null
        // Do not let a late incremental refresh publish a second sync pass.
        // A final refresh is performed by MoneroHandlerThread after remaining=0.
        scanDataRefreshRunning.set(false)
    }

    /**
     * Clear only the in-memory progress window when a mnemonic restore cannot
     * reach the daemon. Keep the one-shot restore preference so startup can retry.
     */
    fun abortRestoreProgressForRetry() {
        customRescanPreparing = false
        customRescanInProgress = false
        customRescanFinalizing = false
        customRescanFinished = false
        customRescanStartHeight = null
        customRescanDayEndHeight = null
        customRescanRequesting.set(false)
        _syncProgress.value = null
        _isSyncing.set(false)
        _isLoading.value = false
        finishRestoreProgress()
        updateScanPreparationIndicator()
        update()
    }

    /**
     * Refresh visible history while mnemonic recovery or a custom-height rescan
     * is running. Calls are throttled to about 400ms and run off the native
     * block callback thread, without starting a second synchronization.
     */
    fun requestScanDataRefresh() {
        fun scanIsActive(): Boolean {
            val restoreActive = restoreProgressInProgress &&
                !restoreProgressCompleted &&
                !restoreProgressFinalizing
            val customActive = customRescanInProgress &&
                !customRescanPreparing &&
                !customRescanFinalizing
            return restoreActive || customActive
        }

        if (!scanIsActive()) return

        val now = System.currentTimeMillis()
        if (now - lastScanDataRefreshAt < 400L) return
        if (!scanDataRefreshRunning.compareAndSet(false, true)) return

        lastScanDataRefreshAt = now
        scanDataRefreshJob = customRescanScope.launch {
            try {
                val wallet = getWallet ?: return@launch
                if (!wallet.isInitialized || !scanIsActive()) {
                    return@launch
                }

                // Both scan types discover transactions as wallet2 processes blocks.
                // Refresh history only; leave the active scan's cursor and target alone.
                wallet.refreshHistory()

                // Do not publish a stale/partial snapshot if finalization began
                // while the native history read was in progress.
                if (scanIsActive()) {
                    update()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Incremental scan data refresh failed")
            } finally {
                scanDataRefreshRunning.set(false)
                scanDataRefreshJob = null
            }
        }
    }

    val transactions: Flow<List<TransactionInfo>> = _transactions

    val balanceInfo: Flow<Long?> = _balanceInfo
    val unLockedBalance: Flow<Long?> = _unLockedBalance
    val isLoading: Flow<Boolean> = _isLoading
    val suppressConnectionWaitIndicator: Flow<Boolean> = _suppressConnectionWaitIndicator

    private fun updateScanPreparationIndicator() {
        _suppressConnectionWaitIndicator.value =
            (heightRefreshInProgress || customRescanRequesting.get() ||
                customRescanPreparing || customRescanInProgress) &&
                _syncProgress.value == null
    }
    val isSyncing get():Boolean = _isSyncing.get()
    val backgroundSync get():Boolean = _backgroundSync.value
    val backgroundSyncFlow: Flow<Boolean> = _backgroundSync

    val walletStatus: Flow<Wallet.Status?> = _walletStatus
    val syncProgress: Flow<SyncProgress?> = _syncProgress

    val nextAddress: Flow<Subaddress?> = _nextAddress
    val coins: Flow<List<CoinsInfo>> = _coins
    val subAddresses: Flow<List<Subaddress>> = _subAddresses

    val walletConnectionStatus: Flow<Wallet.ConnectionStatus?> = _walletStatus.map {
        it?.connectionStatus
    }

    val daemonInfo: Flow<DaemonInfo?> = _connectedDaemon
    val connectionStatus: Flow<Wallet.ConnectionStatus?> = _connectionStatus
    val incomingTx = _incomingTx.asSharedFlow()

    fun update() {
        if (_isWiping.get() || _blockUpdates.get()) return
        getWallet?.let { wallet ->
            if (wallet.isInitialized) {
                // wallet2 may temporarily report 0 while its scan is rebuilding
                // outputs/history. Do not let that transient value erase a known
                // balance while synchronization is active.
                val nativeBalance = runCatching { wallet.balance }.getOrNull()
                val outputBalance = runCatching {
                    wallet.coins?.all
                        ?.asSequence()
                        ?.filter {
                            !it.spent && it.accountIndex == wallet.getAccountIndex()
                        }
                        ?.sumOf { it.amount }
                        ?: 0L
                }.getOrDefault(0L)
                val balanceCandidate = nativeBalance?.takeIf { it > 0L }
                    ?: outputBalance.takeIf { it > 0L }
                    ?: (nativeBalance ?: 0L)
                val balanceTransitionInProgress = _isLoading.value ||
                    _isSyncing.get() ||
                    restoreProgressInProgress ||
                    restoreProgressFinalizing ||
                    customRescanPreparing ||
                    customRescanInProgress ||
                    customRescanFinalizing
                _balanceInfo.update { previous ->
                    if (balanceCandidate <= 0L &&
                        balanceTransitionInProgress &&
                        (previous ?: 0L) > 0L
                    ) {
                        previous
                    } else {
                        balanceCandidate
                    }
                }
                _unLockedBalance.update {
                    if (AnonConfig.viewOnly) {
                        wallet.viewOnlyBalance()
                    }
                    wallet.unlockedBalance
                }
                _walletStatus.update { wallet.fullStatus }
                if (wallet.status.errorString.isNotEmpty()) {
                    Timber.tag(TAG).i("StatusError %s", wallet.status.errorString)
                }
                val address = try {
                    WalletManager.instance?.getDaemonAddress()
                } catch (_: Exception) {
                    null
                }
                address?.let { daemonAddress ->
                    val observedHeight = runCatching {
                        WalletManager.instance?.getBlockchainHeight() ?: -1L
                    }.getOrDefault(-1L)
                    val retainedHeight = if (observedHeight > 0L) {
                        rememberDaemonHeight(daemonAddress, observedHeight)
                    } else {
                        cachedDaemonHeight(daemonAddress)
                    }
                    _connectedDaemon.update {
                        DaemonInfo(
                            daemonAddress,
                            connectionStatus = wallet.fullStatus.connectionStatus
                                ?: Wallet.ConnectionStatus.ConnectionStatus_Disconnected,
                            retainedHeight
                        )
                    }
                }
            }
            val oldTransactions = _transactions.value
            val discoveredTxs = (wallet.history?.all?.sortedWith(comparator = { o1, o2 ->
                o2.timestamp.compareTo(o1.timestamp)
            }) ?: listOf()).fastDistinctBy {
                it.getListKey()
            }

            // A native refresh can publish a transient empty/partial history while
            // wallet2 is rebuilding its in-memory transaction list. Never expose that
            // transient snapshot as the UI list when a refresh/sync is still active.
            // Keep already visible records, replace matching records with their fresh
            // native versions, and append newly discovered records. After the refresh
            // completes, the next normal update() restores the exact native ordering.
            val keepVisibleTransactions = restoreProgressFinalizing ||
                _isLoading.value ||
                _isSyncing.get()

            val updatedTxs = if (keepVisibleTransactions) {
                val discoveredByKey = discoveredTxs.associateBy { it.getListKey() }
                val merged = oldTransactions.map { oldTx ->
                    discoveredByKey[oldTx.getListKey()] ?: oldTx
                }
                val existingKeys = oldTransactions.asSequence()
                    .map { it.getListKey() }
                    .toHashSet()

                (merged + discoveredTxs.filter {
                    it.getListKey() !in existingKeys
                }).sortedWith(compareByDescending<TransactionInfo> { it.timestamp })
            } else {
                discoveredTxs
            }

            if (updatedTxs.size != oldTransactions.size ||
                updatedTxs.zip(oldTransactions).any { (fresh, old) -> fresh !== old }
            ) {
                _transactions.update { updatedTxs }
            }

            if (updatedTxs.size > oldTransactions.size) {
                val oldKeySet = oldTransactions.asSequence()
                    .map { it.getListKey() }
                    .toHashSet()
                val hasNewIncoming = updatedTxs.any {
                    it.getListKey() !in oldKeySet &&
                        it.direction == TransactionInfo.Direction.Direction_In
                }
                if (hasNewIncoming) {
                    _incomingTx.tryEmit(Unit)
                }
            }

            if (!backgroundSync) {
                _nextAddress.update { wallet.getLatestSubAddress() }
                _subAddresses.update { wallet.getAllUsedSubAddresses().reversed() }
                _coins.update { (wallet.coins?.all ?: listOf()).fastFilter { !it.spent } }
            }
        }
    }

    fun prepareForWipe() {
        // The gate is set before touching any other wallet state.
        _isWiping.set(true)
        secureWipeCancellationId = userOperationGeneration.incrementAndGet()
        // Wake/cancel any reset waiting for an ordinary-refresh height callback.
        finishOrdinaryHeightRefresh()
        prefs.edit()
            .remove(PREF_WALLET_HAS_COMPLETED_SYNC)
            .remove(PREF_LAST_COMPLETED_SCAN_HEIGHT)
            .apply()
        invalidStartupHeightSince = 0L
        ordinaryCatchUpInProgress = false
        _blockUpdates.set(true)
        synchronized(backgroundLockStateLock) {
            backgroundLockRequestGeneration.incrementAndGet()
            _backgroundSync.value = false
        }
        nativeBackgroundSyncActive.set(false)

        // Cancel active jobs, not the long-lived scopes. Cancelling these singleton
        // scopes permanently disables later refresh/reset jobs in the same process.
        // The wipe gate and generation change prevent new wallet work during deletion.
        backgroundLifecycleJob?.cancel()
        backgroundLifecycleJob = null
        customRescanJob?.cancel()
        customRescanFinishJob?.cancel()
        scanDataRefreshJob?.cancel()
        scanDataRefreshRunning.set(false)
        customRescanRequesting.set(false)
        customRescanScanStarted.set(false)
        customRescanFinishStarted.set(false)

        restoreProgressStartHeight = null
        restoreProgressRescanStarted = false
        restoreProgressInitialWalletHeight = null
        restoreProgressCursorObserved = false
        restoreProgressInProgress = false
        restoreProgressCompleted = false
        restoreProgressFinalizing = false
        customRescanPreparing = false
        customRescanInProgress = false
        customRescanFinalizing = false
        customRescanFinished = false
        customRescanCompletionCallback = null
        lastScanDataRefreshAt = 0L
        _syncProgress.value = null
        _isSyncing.set(false)
        _manualRefreshInProgress.value = false
        _isLoading.value = false
        userOperationTransitionInProgress = false
        _userOperationTransition.value = false
    }

    fun cancelNativeWorkForSecureWipe(wallet: Wallet?) {
        if (!_isWiping.get() || wallet == null || !wallet.isInitialized) return
        val operationId = secureWipeCancellationId
        if (operationId <= 0L) return
        runCatching { wallet.cancelCurrentOperation(operationId) }
            .onFailure { Timber.tag(TAG).e(it, "Native operation cancellation request failed") }
    }

    fun isWiping(): Boolean = _isWiping.get()

    fun setLoading(b: Boolean) {
        // Generic loading is reserved for explicit refreshes and non-scan operations.
        // Restore/custom-rescan progress owns the indicator from preparation through
        // final data refresh; never replace its real cursor progress with a spinner.
        if (b && (
                restoreProgressInProgress ||
                restoreProgressFinalizing ||
                customRescanPreparing ||
                customRescanInProgress ||
                customRescanFinalizing
            )
        ) return
        this._isLoading.update { b }
    }

    fun emitUnlockShortcut(shortcut: LockScreenShortCut) {
        _unlockShortcut.trySend(shortcut)
    }

    /**
     * Run the leave-app transition independently of Activity teardown. The native
     * wallet operation must not be cancelled merely because Android destroys the UI.
     */
    /**
     * True while foreground refresh, synchronization, or a user-initiated wallet
     * operation may be using wallet2. Native background-sync transitions take the
     * same native refresh lock, so do not start/finish those transitions or perform
     * extra JNI snapshots while any of these states are active.
     */
    private fun foregroundWalletOperationActive(wallet: Wallet): Boolean =
        isSyncing ||
            _isLoading.value ||
            _manualRefreshInProgress.value ||
            _syncProgress.value != null ||
            ordinaryCatchUpInProgress ||
            heightRefreshInProgress ||
            userOperationTransitionInProgress ||
            customRescanRequesting.get() ||
            restoreProgressInProgress ||
            restoreProgressFinalizing ||
            customRescanPreparing ||
            customRescanInProgress ||
            customRescanFinalizing ||
            !wallet.isSynchronized ||
            !runCatching { wallet.nativeSynchronized }.getOrDefault(false)

    fun requestBackgroundSyncOnLeave() {
        if (_isWiping.get()) return

        val wallet = getWallet ?: return
        if (!runCatching { wallet.isInitialized }.getOrDefault(false)) return

        // Publish lock intent and its generation as one state transition. Do not cancel
        // native work that may be waiting for wallet2's refresh lock.
        val requestId = synchronized(backgroundLockStateLock) {
            if (_isWiping.get()) return
            val id = backgroundLockRequestGeneration.incrementAndGet()
            val wasAlreadyLocked = _backgroundSync.value
            if (!wasAlreadyLocked) emitUnlockShortcut(LockScreenShortCut.HOME)
            _backgroundSync.value = true
            id
        }

        // Keep this request's identity so a fast valid PIN can invalidate it before
        // it re-enters native background-sync mode.
        val previousJob = backgroundLifecycleJob
        backgroundLifecycleJob = backgroundLifecycleScope.launch {
            previousJob?.join()
            if (_isWiping.get() || requestId != backgroundLockRequestGeneration.get()) {
                return@launch
            }
            runCatching { enterBackgroundSync(requestId) }
                .onFailure { Timber.tag(TAG).e(it, "Background wallet transition failed") }
        }
    }

    suspend fun enterBackgroundSync(): Boolean {
        // This public entry point represents an explicit request to lock.
        val requestId = synchronized(backgroundLockStateLock) {
            if (_isWiping.get()) return false
            val id = backgroundLockRequestGeneration.incrementAndGet()
            val wasAlreadyLocked = _backgroundSync.value
            _backgroundSync.value = true
            if (!wasAlreadyLocked) emitUnlockShortcut(LockScreenShortCut.HOME)
            id
        }
        return enterBackgroundSync(requestId)
    }

    private suspend fun enterBackgroundSync(requestId: Long): Boolean = bgSyncMutex.withLock {
        if (_isWiping.get() || requestId != backgroundLockRequestGeneration.get()) return false
        val wallet = getWallet ?: return false
        if (!wallet.isInitialized) return false

        // The UI lock is set by the request itself. Do not set it here: a valid PIN
        // may arrive while this coroutine is reading wallet state, and this stale
        // native-transition job must never re-lock the UI after that PIN is accepted.

        // If native mode was already entered, there is no second transition to start.
        if (nativeBackgroundSyncActive.get()) return true
        if (AnonConfig.viewOnly) return true

        // Never switch wallet2 into native background-sync mode while its normal
        // refresh/rescan worker is active. That transition can race the native scan
        // when the user leaves the app mid-sync and crash on the next activity launch.
        // Consider every foreground wallet operation before entering native
        // background-sync mode. In particular, an already-synchronized wallet may
        // still be doing ordinary catch-up; its normal indicator does not necessarily
        // set isSyncing/_isLoading, and wallet2 may still report synchronized until
        // the next callback. startBackgroundSync() takes the native refresh lock, so
        // missing these states can hold bgSyncMutex and make the next PIN unlock wait
        // indefinitely for stopBackgroundSync().
        val scanActive = foregroundWalletOperationActive(wallet)
        if (scanActive) {
            nativeBackgroundSyncActive.set(false)
            Timber.tag(TAG).i(
                "Keeping foreground wallet operation active; PIN lock only (syncing=%s loading=%s manualRefresh=%s catchUp=%s heightRefresh=%s transition=%s customRescan=%s restore=%s)",
                isSyncing,
                _isLoading.value,
                _manualRefreshInProgress.value,
                ordinaryCatchUpInProgress,
                heightRefreshInProgress,
                userOperationTransitionInProgress,
                customRescanPreparing || customRescanInProgress || customRescanFinalizing,
                restoreProgressInProgress || restoreProgressFinalizing
            )
            return true
        }

        // A valid PIN may have arrived while the foreground-operation checks ran.
        if (_isWiping.get() || requestId != backgroundLockRequestGeneration.get()) {
            return false
        }

        _blockUpdates.set(true)
        try {
            if (wallet.startBackgroundSync()) {
                nativeBackgroundSyncActive.set(true)
                Timber.tag(TAG).i("Entered native background sync")
                return true
            }
            nativeBackgroundSyncActive.set(false)
            synchronized(backgroundLockStateLock) {
                if (requestId == backgroundLockRequestGeneration.get()) {
                    _backgroundSync.value = false
                }
            }
            Timber.tag(TAG).e("startBackgroundSync returned false")
            return false
        } catch (e: Exception) {
            nativeBackgroundSyncActive.set(false)
            synchronized(backgroundLockStateLock) {
                if (requestId == backgroundLockRequestGeneration.get()) {
                    _backgroundSync.value = false
                }
            }
            Timber.tag(TAG).e(e, "startBackgroundSync error")
            return false
        } finally {
            _blockUpdates.set(false)
        }
    }

    fun requestExitBackgroundSync(pin: String): Boolean =
        exitBackgroundSyncInternal(pin)

    suspend fun exitBackgroundSync(pin: String): Boolean =
        exitBackgroundSyncInternal(pin)

    private fun exitBackgroundSyncInternal(pin: String): Boolean {
        if (_isWiping.get()) return false

        // Atomically invalidate queued lock-entry work and release the PIN overlay.
        synchronized(backgroundLockStateLock) {
            backgroundLockRequestGeneration.incrementAndGet()
            _backgroundSync.value = false
        }

        val wallet = getWallet
        if (wallet == null) {
            Timber.tag(TAG).w(
                "PIN accepted and lock released; wallet instance unavailable for native teardown"
            )
            return true
        }

        // Native wallet2 may still be holding its refresh lock. Never make the PIN UI
        // wait for bgSyncMutex or a JNI call; teardown is serialized separately.
        val nativeTransitionPending =
            nativeBackgroundSyncActive.get() || backgroundLifecycleJob?.isActive == true
        if (AnonConfig.viewOnly || !nativeTransitionPending) {
            Timber.tag(TAG).i(
                "Released PIN lock immediately; no native background-sync teardown required"
            )
            return true
        }

        synchronized(backgroundSyncExitLock) {
            if (backgroundSyncExitJob?.isActive == true) {
                Timber.tag(TAG).d("Native background-sync teardown is already queued")
                return true
            }
            backgroundSyncExitJob = backgroundLifecycleScope.launch {
                bgSyncMutex.withLock {
                if (_isWiping.get()) return@withLock
                val currentWallet = getWallet ?: return@withLock
                if (!nativeBackgroundSyncActive.get()) {
                    Timber.tag(TAG).d(
                        "Skipping deferred background-sync teardown; native mode was not entered"
                    )
                    return@withLock
                }

                _blockUpdates.set(true)
                val exited = try {
                    currentWallet.stopBackgroundSync(pin)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Deferred native background-sync exit failed")
                    false
                } finally {
                    _blockUpdates.set(false)
                }

                if (!exited) {
                    Timber.tag(TAG).e(
                        "Deferred stopBackgroundSync returned false; PIN screen was already released"
                    )
                    return@withLock
                }

                nativeBackgroundSyncActive.set(false)
                runCatching { currentWallet.refreshHistory() }
                    .onFailure { Timber.tag(TAG).e(it, "History refresh after background sync failed") }
                runCatching { currentWallet.refreshCoins(force = true) }
                    .onFailure { Timber.tag(TAG).e(it, "Output refresh after background sync failed") }
                runCatching { update() }
                    .onFailure { Timber.tag(TAG).e(it, "Wallet snapshot after background sync failed") }
                runCatching { currentWallet.startRefresh() }
                    .onFailure { Timber.tag(TAG).e(it, "Restart refresh after background sync failed") }
                Timber.tag(TAG).i("Deferred native background-sync teardown completed")
                }
            }
        }

        Timber.tag(TAG).i(
            "PIN unlock accepted immediately; native background-sync teardown queued in IO"
        )
        return true
    }

    private suspend fun awaitBackgroundSyncExitBeforeReset(): Result<Unit> {
        val exitJob = synchronized(backgroundSyncExitLock) { backgroundSyncExitJob }
        if (exitJob?.isActive == true) {
            Timber.tag(TAG).i("Waiting for native background-sync teardown before wallet reset")
            try {
                withTimeoutOrNull(30_000L) {
                    exitJob.join()
                    true
                } ?: return Result.failure(
                    IllegalStateException("Timed out waiting for background sync to stop; please retry reset")
                )
            } catch (e: CancellationException) {
                throw e
            }
        }
        if (nativeBackgroundSyncActive.get()) {
            return Result.failure(
                IllegalStateException("Native background sync is still active; unlock the wallet and retry reset")
            )
        }
        return Result.success(Unit)
    }

    fun setConnectionStatus(status: Wallet.ConnectionStatus) {
        if (_isWiping.get()) return
        val previous = _previousConnectionStatus.getAndSet(status)
        _connectionStatus.update { status }
        if (previous == Wallet.ConnectionStatus.ConnectionStatus_Disconnected &&
            status == Wallet.ConnectionStatus.ConnectionStatus_Connected) {
            val wallet = getWallet
            if (wallet?.isSynchronized == true || (wallet?.isInitialized == true && wallet.nativeSynchronized)) {
                // The wallet is already synced. Do not restart the refresh/loading
                // indicator just because the daemon connection was re-established.
                finishSync()
            } else {
                setLoading(true)
                wallet?.startRefresh()
            }
        }
    }

    fun getAddressForCoin(txHash: String?): String? {
        if (txHash.isNullOrEmpty()) return null
        val tx = _transactions.value.firstOrNull { it.hash == txHash } ?: return null
        return try { getWallet?.getSubaddress(tx.accountIndex, tx.addressIndex) } catch (_: Exception) { null }
    }

    fun freezeCoin(publicKey: String): Result<Boolean> = try {
        val wallet = getWallet ?: return Result.failure(Exception("Wallet not initialized"))
        val coins = wallet.coins ?: return Result.failure(Exception("Coins not initialized"))
        coins.setFrozen(publicKey)
        wallet.store()
        coins.refresh()
        update()
        Result.success(true)
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun thawCoin(publicKey: String): Result<Boolean> = try {
        val wallet = getWallet ?: return Result.failure(Exception("Wallet not initialized"))
        val coins = wallet.coins ?: return Result.failure(Exception("Coins not initialized"))
        coins.thaw(publicKey)
        wallet.store()
        coins.refresh()
        update()
        Result.success(true)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Return the last valid height for a node even if a later native read
     * temporarily reports 0/-1 during refresh, reconnect, or screen navigation.
     */
    fun cachedDaemonHeight(daemonAddress: String?): Long {
        if (daemonAddress.isNullOrBlank()) return 0L
        return knownDaemonHeights[daemonAddress]?.takeIf { it > 0L }
            ?: _connectedDaemon.value
                ?.takeIf { it.daemon == daemonAddress }
                ?.daemonHeight?.takeIf { it > 0L }
            ?: 0L
    }

    /** Store valid heights centrally so a recreated screen ViewModel can reuse them. */
    fun rememberDaemonHeight(daemonAddress: String?, height: Long): Long {
        if (daemonAddress.isNullOrBlank()) return 0L
        if (height > 0L) knownDaemonHeights[daemonAddress] = height
        return cachedDaemonHeight(daemonAddress)
    }

    fun updateDaemon(daemonInfo: DaemonInfo) {
        val height = if (daemonInfo.daemonHeight > 0L) {
            rememberDaemonHeight(daemonInfo.daemon, daemonInfo.daemonHeight)
        } else {
            cachedDaemonHeight(daemonInfo.daemon)
        }
        this._connectedDaemon.update {
            daemonInfo.copy(daemonHeight = height)
        }
    }

    /**
     * Decide whether ordinary catch-up deserves the visible block-count progress.
     * First scans and larger backlogs get full progress. If the wallet was already
     * near the daemon tip, continue scanning under the normal refresh indicator.
     */
    fun hasCompletedSyncBefore(): Boolean =
        prefs.getBoolean(PREF_WALLET_HAS_COMPLETED_SYNC, false)

    /**
     * Migrate an existing wallet's persisted cursor when upgrading from builds that
     * did not store a completed-sync marker. This is called before listener callbacks.
     */
    fun rememberKnownWalletCursor(height: Long) {
        if (height <= 1L) return
        val currentHeight = prefs.getLong(PREF_LAST_COMPLETED_SCAN_HEIGHT, 0L)
        if (!hasCompletedSyncBefore() || height > currentHeight) {
            prefs.edit()
                .putBoolean(PREF_WALLET_HAS_COMPLETED_SYNC, true)
                .putLong(PREF_LAST_COMPLETED_SCAN_HEIGHT, maxOf(height, currentHeight))
                .apply()
        }
    }

    /**
     * A wallet that already completed a scan persists a high local cursor. On app
     * startup, wallet2 can briefly return 0 or an older cursor while its database is
     * being restored. Do not misclassify that startup read as a brand-new full scan.
     * If the cursor stays invalid for 15 seconds, allow normal sync progress so a
     * genuine unexpectedly-low cursor is not hidden forever.
     */
    fun shouldShowNormalSyncProgress(scanHeight: Long, daemonHeight: Long): Boolean {
        val previouslySynced = hasCompletedSyncBefore()
        val lastCompletedHeight = prefs.getLong(PREF_LAST_COMPLETED_SCAN_HEIGHT, 0L)
        val startupCursorInvalid = previouslySynced &&
            lastCompletedHeight > 1L &&
            scanHeight < lastCompletedHeight

        if (startupCursorInvalid) {
            val now = System.currentTimeMillis()
            if (invalidStartupHeightSince == 0L) invalidStartupHeightSince = now
            if (now - invalidStartupHeightSince < 15_000L) return false
        } else {
            invalidStartupHeightSince = 0L
        }

        if (normalSyncProgressVisible == true) return true

        val remaining = daemonHeight - scanHeight
        // Do not use scanHeight <= 1 alone as proof this is the first scan: on
        // startup that value can be a transient JNI read before the saved cursor loads.
        val shouldShow = remaining > NORMAL_SYNC_PROGRESS_DISPLAY_THRESHOLD
        normalSyncProgressVisible = shouldShow
        return shouldShow
    }

    /** Keep the native catch-up scan active without showing a full sync counter. */
    fun showOrdinaryCatchUpRefresh() {
        ordinaryCatchUpInProgress = true
        _syncProgress.value = null
        _isSyncing.set(false)
        _isLoading.value = true
        updateScanPreparationIndicator()
    }

    fun syncUpdate(syncProgress: SyncProgress) {
        // A real synchronization cursor owns the progress UI from this point.
        _manualRefreshInProgress.value = false
        // Keep the terminal 100% / zero-remaining state visible until the final
        // history/output snapshot is ready for both recovery and custom rescans.
        val done = syncProgress.progress >= 1f
        val scanFinalizing = restoreProgressInProgress ||
            restoreProgressFinalizing ||
            customRescanInProgress ||
            customRescanFinalizing
        val keepProgressVisible = !done || scanFinalizing
        _syncProgress.update {
            if (keepProgressVisible) syncProgress else null
        }
        _isSyncing.set(keepProgressVisible)
        updateScanPreparationIndicator()
        // A real cursor update replaces the indeterminate waiting state.
        _isLoading.value = false
        if (done && !scanFinalizing) {
            _connectionStatus.update { Wallet.ConnectionStatus.ConnectionStatus_Connected }
        }
    }

    fun finishSync() {
        ordinaryCatchUpInProgress = false
        // Persist evidence of a completed wallet scan for the next app process.
        // The Kotlin Wallet.isSynchronized flag itself is not persistent.
        runCatching {
            val wallet = getWallet
            val height = wallet?.takeIf { it.isInitialized }?.getBlockChainHeight() ?: 0L
            if (height > 1L) {
                prefs.edit()
                    .putBoolean(PREF_WALLET_HAS_COMPLETED_SYNC, true)
                    .putLong(PREF_LAST_COMPLETED_SCAN_HEIGHT, height)
                    .apply()
            }
        }.onFailure {
            Timber.tag(TAG).w(it, "Could not persist last completed wallet scan height")
        }
        _manualRefreshInProgress.value = false
        // Normal synchronization/custom-rescan completion has already refreshed
        // history and outputs. A deferred request is satisfied by that snapshot.
        deferredManualRefresh.set(false)
        _syncProgress.value = null
        _isSyncing.set(false)
        _isLoading.value = false
        normalSyncProgressVisible = null
        _connectionStatus.update { Wallet.ConnectionStatus.ConnectionStatus_Connected }

        // Publish the final wallet snapshot after clearing sync/loading flags.
        // This guarantees the balance is refreshed even when the last native
        // callback updated the progress state before the final data was ready.
        update()
    }


    fun toggleHideAmounts() {
        hideAmountsFlow.update { !it }
    }

    fun setBackGroundSync(startBackgroundSync: Boolean) {
        synchronized(backgroundLockStateLock) {
            if (!startBackgroundSync) {
                backgroundLockRequestGeneration.incrementAndGet()
            }
            _backgroundSync.value = startBackgroundSync
        }
    }

    fun getNewAddress() {
        getWallet?.let {
            it.addSubaddress(it.getAccountIndex(), "Subaddress #${it.numSubAddresses}")
            it.store()
            it.getLatestSubAddress().let { subAddresses ->
                _nextAddress.update { subAddresses }
            }
            it.getAllUsedSubAddresses().let { allItems ->
                _subAddresses.update { allItems.reversed() }
            }
            update()
        }
    }

    fun setTransactionNote(note: String, transactionInfo: TransactionInfo) {
        getWallet?.let {
            it.setUserNote(transactionInfo.hash, note)
            it.store()
            it.refreshHistory()
            update()
        }
    }

    fun updateAddressLabel(label: String, addressIndex: Int) {
        getWallet?.let {
            it.setSubaddressLabel(addressIndex, label)
            it.store()
            it.refreshHistory()
            it.getAllUsedSubAddresses().let { allItems ->
                _subAddresses.update { allItems.reversed() }
            }
            it.getLatestSubAddress().let { subAddresses ->
                _nextAddress.update { subAddresses }
            }
        }
    }

    /**
     * Consume a manual refresh queued while a native scan owned mutable wallet
     * state. Custom rescans and mnemonic restores perform their own final refresh,
     * so those paths keep the request until finishSync() clears it.
     */
    fun consumeDeferredManualRefresh(): Boolean {
        if (customRescanPreparing ||
            customRescanInProgress ||
            customRescanFinalizing ||
            customRescanFinished ||
            restoreProgressInProgress ||
            restoreProgressFinalizing ||
            restoreProgressCompleted
        ) return false
        return deferredManualRefresh.compareAndSet(true, false)
    }

    /**
     * Date/height lookups call the native wallet directly. Do not start these
     * lookups while another refresh or scan owns wallet2's mutable state.
     * Cached values can still be returned by ResetSync without calling JNI.
     */
    fun canQueryBlockDate(): Boolean {
        val wallet = getWallet ?: return false
        return wallet.isInitialized &&
            !_isLoading.value &&
            !_isSyncing.get() &&
            _syncProgress.value == null &&
            !_backgroundSync.value &&
            !nativeBackgroundSyncActive.get() &&
            !customRescanPreparing &&
            !customRescanInProgress &&
            !customRescanFinalizing &&
            !restoreProgressInProgress &&
            !restoreProgressFinalizing
    }

    /**
     * Backward-compatible trigger for non-screen callers. The UI uses the suspending
     * overload so the pull gesture remains active until the explicit refresh work ends.
     */
    fun refresh() {
        if (_isWiping.get()) return
        userOperationScope.launch {
            refresh(manual = true)
        }
    }

    suspend fun refresh(manual: Boolean) {
        if (_isWiping.get()) {
            if (manual) _manualRefreshInProgress.value = false
            return
        }
        val wallet = getWallet
        if (wallet?.isInitialized != true || _isWiping.get()) {
            if (manual) _manualRefreshInProgress.value = false
            return
        }

        userOperationMutex.lock()
        var manualWorkStarted = false
        var awaitManualRefreshFinish = false
        try {
            if (manual) _manualRefreshInProgress.value = false
            if (!wallet.isInitialized || _isWiping.get()) return

            // Completed custom rescans keep a terminal guard to ignore late callbacks
            // from that scan. A new user refresh is a newer operation: release the old
            // guard first, or MoneroHandlerThread.refreshed() will keep discarding every
            // callback and later pulls won't perform a real daemon refresh.
            if (manual && customRescanFinished &&
                !customRescanPreparing &&
                !customRescanInProgress &&
                !customRescanFinalizing
            ) {
                customRescanFinished = false
                Timber.tag(TAG).i("Manual refresh released completed custom-rescan callback guard")
            }

            // Only genuinely active operations may defer a new manual refresh.
            // Terminal callback guards are not active wallet operations.
            val priorityOperationOwnsWallet =
                userOperationTransitionInProgress ||
                    heightRefreshInProgress ||
                    customRescanPreparing ||
                    customRescanInProgress ||
                    customRescanFinalizing ||
                    restoreProgressInProgress ||
                    restoreProgressFinalizing

            if (priorityOperationOwnsWallet) {
                if (manual) deferredManualRefresh.set(true)
                Timber.tag(TAG).i("Manual refresh queued behind the current wallet operation")
                return
            }

            if (manual) {
                // A completed scan keeps these guards set to ignore late native callbacks.
                // A new manual refresh starts a fresh operation, so release the terminal
                // guards after acquiring the operation mutex; otherwise later refresh
                // callbacks keep returning early even though the scan has finished.
                if (customRescanFinished || restoreProgressCompleted) {
                    Timber.tag(TAG).i(
                        "Releasing completed-scan callback guards for manual refresh"
                    )
                    customRescanFinished = false
                    restoreProgressCompleted = false
                }
            }

            // Do not query transaction/output state while wallet2 is processing blocks.
            // Still restart the native refresh worker: that both wakes a stalled first
            // sync and guarantees a deferred pull has a future safe listener callback.
            val scanOwnsWallet =
                !wallet.isSynchronized ||
                    _isLoading.value ||
                    _isSyncing.get() ||
                    _syncProgress.value != null ||
                    ordinaryCatchUpInProgress

            if (scanOwnsWallet) {
                if (manual) {
                    deferredManualRefresh.set(true)
                    _manualRefreshInProgress.value = true
                    setLoading(true)
                    awaitManualRefreshFinish = true
                }
                withContext(Dispatchers.IO) {
                    if (wallet.isInitialized && !_isWiping.get()) {
                        wallet.startRefresh()
                    } else if (manual) {
                        awaitManualRefreshFinish = false
                        _manualRefreshInProgress.value = false
                        setLoading(false)
                    }
                }
                Timber.tag(TAG).i(
                    "Manual refresh handed to active scan: synchronized=%s loading=%s syncing=%s",
                    wallet.isSynchronized, _isLoading.value, _isSyncing.get()
                )
                return
            }

            if (manual) {
                _manualRefreshInProgress.value = true
                setLoading(true)
                manualWorkStarted = true
                awaitManualRefreshFinish = true
            }

            withContext(Dispatchers.IO) {
                if (!wallet.isInitialized || _isWiping.get()) {
                    awaitManualRefreshFinish = false
                    if (manual) _manualRefreshInProgress.value = false
                    return@withContext
                }

                val connected = runCatching {
                    wallet.fullStatus.connectionStatus == Wallet.ConnectionStatus.ConnectionStatus_Connected
                }.getOrDefault(false)

                if (!connected) {
                    // The old path simply skipped the request when disconnected. Wake
                    // the native refresh/reconnect lifecycle instead of making pull-to-
                    // refresh a no-op; MoneroHandlerThread handles connection recovery.
                    Timber.tag(TAG).i("Manual refresh requested while disconnected; restarting native refresh")
                    wallet.startRefresh()
                    return@withContext
                }

                // Resume block catch-up and refresh the cached transaction/output view.
                // This matches the upstream manual-refresh path while keeping all direct
                // native reads off the Compose/main thread.
                wallet.startRefresh()
                wallet.refreshHistory()
                wallet.refreshCoins(force = true)
                // The finally block publishes one consolidated state snapshot off
                // the UI thread. Avoid doing the same JNI-heavy update twice at the
                // end of a manual pull, which delays hiding the refresh indicator.
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            awaitManualRefreshFinish = false
            if (manual) _manualRefreshInProgress.value = false
            throw e
        } catch (e: Exception) {
            awaitManualRefreshFinish = false
            if (manual) _manualRefreshInProgress.value = false
            Timber.tag(TAG).e(e, "Manual wallet refresh failed")
        } finally {
            try {
                if (manualWorkStarted && !_isWiping.get()) {
                    val scanStillOwnsWallet =
                        ordinaryCatchUpInProgress ||
                            _isSyncing.get() ||
                            _syncProgress.value != null ||
                            customRescanPreparing ||
                            customRescanInProgress ||
                            customRescanFinalizing ||
                            restoreProgressInProgress ||
                            restoreProgressFinalizing ||
                            (!wallet.isSynchronized && wallet.isInitialized)

                    setLoading(
                        scanStillOwnsWallet ||
                            (awaitManualRefreshFinish && _manualRefreshInProgress.value)
                    )
                    if (!scanStillOwnsWallet && !awaitManualRefreshFinish) {
                        _manualRefreshInProgress.value = false
                    }
                    // update() contains JNI reads; never run it on the Compose thread,
                    // and always release the operation mutex even if a read fails.
                    withContext(Dispatchers.IO) { update() }
                }
            } finally {
                userOperationMutex.unlock()
            }

            // startRefresh() schedules native work; returning from this method only
            // means the cache snapshot was requested, not that the native refresh has
            // reached its callback boundary. Keep the pull gesture alive off the main
            // thread until syncUpdate(), finishSync(), or reconnect handling releases it.
            if (awaitManualRefreshFinish && manual && !_isWiping.get()) {
                try {
                    _manualRefreshInProgress.first { !it }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    _manualRefreshInProgress.value = false
                    if (manualWorkStarted) setLoading(false)
                    throw e
                }

                if (manualWorkStarted && !_isWiping.get()) {
                    val scanStillOwnsWallet =
                        ordinaryCatchUpInProgress ||
                            _isSyncing.get() ||
                            _syncProgress.value != null ||
                            customRescanPreparing ||
                            customRescanInProgress ||
                            customRescanFinalizing ||
                            restoreProgressInProgress ||
                            restoreProgressFinalizing ||
                            (!wallet.isSynchronized && wallet.isInitialized)
                    setLoading(scanStillOwnsWallet)
                }
            }
        }
    }

    /**
     * Run a cache-miss date/height query as the latest explicit wallet operation.
     * pauseRefresh() invalidates any in-flight native block-date binary search; the
     * generation check prevents an older query from publishing after a newer action.
     */
    suspend fun <T> executeLatestWalletQuery(query: (Wallet) -> T): Result<T> {
        _manualRefreshInProgress.value = false
        if (_isWiping.get()) return Result.failure(IllegalStateException("Secure deletion in progress"))
        val wallet = getWallet
            ?: return Result.failure(IllegalStateException("Wallet not initialized"))
        val operationId = beginUserOperationTransition()
        finishOrdinaryHeightRefresh()

        return try {
            // Non-blocking native signal immediately cancels an active refresh or
            // date/height lookup. Waiting for the old operation happens below.
            wallet.cancelCurrentOperation(operationId)
            if (operationId != userOperationGeneration.get()) {
                return Result.failure(IllegalStateException("查询已被更新操作取代"))
            }

            userOperationMutex.lock()
            try {
                if (operationId != userOperationGeneration.get()) {
                    return Result.failure(IllegalStateException("查询已被更新操作取代"))
                }

                // Wait until native refresh callbacks and their wallet mutations exit.
                withContext(Dispatchers.IO) { wallet.pauseRefresh() }
                if (operationId != userOperationGeneration.get()) {
                    return Result.failure(IllegalStateException("查询已被更新操作取代"))
                }

                cancelScanUiStateForLatestOperation()
                val value = withContext(Dispatchers.IO) { query(wallet) }
                if (operationId != userOperationGeneration.get()) {
                    return Result.failure(IllegalStateException("查询已被更新操作取代"))
                }

                if (wallet.fullStatus.connectionStatus == Wallet.ConnectionStatus.ConnectionStatus_Connected) {
                    setLoading(true)
                    wallet.startRefresh()
                }
                Result.success(value)
            } finally {
                userOperationMutex.unlock()
            }
        } catch (e: Exception) {
            if (operationId == userOperationGeneration.get() &&
                wallet.fullStatus.connectionStatus == Wallet.ConnectionStatus.ConnectionStatus_Connected
            ) {
                runCatching {
                    setLoading(true)
                    wallet.startRefresh()
                }.onFailure { Timber.tag(TAG).w(it, "Could not resume refresh after query failure") }
            }
            Result.failure(e)
        } finally {
            finishUserOperationTransition(operationId)
        }
    }

    private suspend fun cancelScanUiStateForLatestOperation() {
        finishOrdinaryHeightRefresh()
        customRescanPreparing = true
        customRescanJob?.cancelAndJoin()
        customRescanJob = null
        customRescanFinishJob?.cancelAndJoin()
        customRescanFinishJob = null
        scanDataRefreshJob?.cancelAndJoin()
        scanDataRefreshJob = null
        finishRestoreProgress()
        scanDataRefreshRunning.set(false)
        customRescanRequesting.set(false)
        customRescanScanStarted.set(false)
        customRescanFinishStarted.set(false)
        customRescanInProgress = false
        customRescanFinalizing = false
        customRescanFinished = false
        customRescanStartHeight = null
        customRescanDayEndHeight = null
        restoreProgressStartHeight = null
        restoreProgressRescanStarted = false
        restoreProgressInitialWalletHeight = null
        restoreProgressCursorObserved = false
        restoreProgressInProgress = false
        restoreProgressCompleted = false
        restoreProgressFinalizing = false
        _syncProgress.value = null
        _isSyncing.set(false)
        setLoading(false)
        customRescanPreparing = false
    }

    suspend fun resetSyncFromHeight(
        height: Long,
        dayEndHeight: Long? = null,
        onCompleted: (() -> Unit)? = null,
        onPrepared: (suspend () -> Unit)? = null
    ): Result<Boolean> {
        _manualRefreshInProgress.value = false
        if (_isWiping.get()) return Result.failure(IllegalStateException("Secure deletion in progress"))
        val wallet = getWallet
            ?: return Result.failure(IllegalStateException("Wallet not initialized"))
        if (!wallet.isInitialized) {
            return Result.failure(IllegalStateException("Wallet not initialized"))
        }
        if (height < 0L) {
            return Result.failure(IllegalArgumentException("Invalid restore height"))
        }

        val backgroundExit = awaitBackgroundSyncExitBeforeReset()
        if (backgroundExit.isFailure) return Result.failure(backgroundExit.exceptionOrNull()!!)

        val operationId = beginUserOperationTransition()
        // Turn on the shared home progress area and gate stale callbacks before
        // signalling native cancellation. The reset button may still be spinning,
        // but leaving this screen must not hide the operation state.
        beginOrdinaryHeightRefresh(height)
        return try {
            wallet.cancelCurrentOperation(operationId)
            if (operationId != userOperationGeneration.get()) {
                return Result.failure(IllegalStateException("重置已被更新操作取代"))
            }

            userOperationMutex.lock()
            try {
                if (operationId != userOperationGeneration.get()) {
                    return Result.failure(IllegalStateException("重置已被更新操作取代"))
                }

                // Drain the previous native refresh/rescan before reading the tip.
                // pauseRefresh() is a lock barrier; the native wrapper also discards any
                // rescan request re-queued while that old operation was unwinding.
                withContext(Dispatchers.IO) { wallet.pauseRefresh() }
                if (operationId != userOperationGeneration.get()) {
                    return Result.failure(IllegalStateException("重置已被更新操作取代"))
                }

                // A connected wallet can usually provide its actual current daemon
                // height immediately. Do not run another full refresh merely to read it.
                // Only fall back to the fresh listener callback when the native value is 0.
                val liveDaemonHeight = withContext(Dispatchers.IO) {
                    runCatching { wallet.getDaemonBlockChainHeight() }.getOrDefault(0L)
                }
                val refreshedDaemonHeight = if (liveDaemonHeight > 0L) {
                    liveDaemonHeight
                } else {
                    armOrdinaryHeightRefresh()
                    wallet.startRefresh()
                    awaitOrdinaryRefreshHeight()
                }
                if (operationId != userOperationGeneration.get()) {
                    return Result.failure(IllegalStateException("重置已被更新操作取代"))
                }

                val effectiveEndHeight = dayEndHeight ?: refreshedDaemonHeight
                if (effectiveEndHeight < height) {
                    throw IllegalArgumentException(
                        "节点最新高度 $effectiveEndHeight 低于所选起始高度 $height"
                    )
                }
                if (!publishInitialHeightSyncProgress(height, effectiveEndHeight)) {
                    throw IllegalStateException("无法发布所选高度的同步进度")
                }
                finishUserOperationTransition(operationId)

                // Keep the reset page active until the native custom-height rescan has
                // been accepted. The inner method invokes onPrepared only after it has
                // queued the rescan and started its progress poller, so failures remain
                // visible here instead of clearing progress after navigation.
                val result = resetSyncFromHeightInternal(
                    height,
                    effectiveEndHeight,
                    operationId,
                    onCompleted,
                    onPrepared
                )
                if (result.isFailure && operationId == userOperationGeneration.get()) {
                    cancelScanUiStateForLatestOperation()
                    if (operationId == userOperationGeneration.get() &&
                        wallet.fullStatus.connectionStatus == Wallet.ConnectionStatus.ConnectionStatus_Connected
                    ) {
                        runCatching {
                            setLoading(true)
                            wallet.startRefresh()
                        }.onFailure {
                            Timber.tag(TAG).w(it, "Could not resume refresh after reset failure")
                        }
                    }
                }
                result
            } finally {
                if (operationId == userOperationGeneration.get() && heightRefreshInProgress) {
                    finishOrdinaryHeightRefresh()
                }
                userOperationMutex.unlock()
            }
        } catch (e: Exception) {
            if (operationId == userOperationGeneration.get()) {
                runCatching {
                    cancelScanUiStateForLatestOperation()
                    if (wallet.fullStatus.connectionStatus == Wallet.ConnectionStatus.ConnectionStatus_Connected) {
                        setLoading(true)
                        wallet.startRefresh()
                    }
                }.onFailure {
                    Timber.tag(TAG).w(it, "Could not recover wallet refresh after height lookup failure")
                }
            }
            Timber.tag(TAG).e(e, "Could not obtain a live daemon height before rescan")
            Result.failure(e)
        } finally {
            finishUserOperationTransition(operationId)
        }
    }

    /**
     * Start a reset from a service-owned scope so leaving ResetSyncPage does not
     * cancel the native wallet operation. onPrepared fires after the initial
     * remaining-block snapshot is published, before waiting on native refresh locks.
     */
    fun requestResetSyncFromHeight(
        height: Long,
        onPrepared: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        userOperationScope.launch {
            var prepared = false
            val result = resetSyncFromHeight(
                height = height,
                onPrepared = {
                    prepared = true
                    withContext(Dispatchers.Main.immediate) { onPrepared() }
                }
            )
            if (result.isFailure) {
                val message = result.exceptionOrNull()?.message ?: "重置失败"
                withContext(Dispatchers.Main.immediate) {
                    // Once the screen has returned home, its local error label is no
                    // longer visible; the reset cleanup still clears failed progress.
                    if (!prepared) onFailure(message)
                }
            }
        }
    }

    private suspend fun resetSyncFromHeightInternal(
        height: Long,
        dayEndHeight: Long?,
        operationId: Long,
        onCompleted: (() -> Unit)?,
        onPrepared: (suspend () -> Unit)?
    ): Result<Boolean> {
        if (operationId != userOperationGeneration.get()) {
            return Result.failure(IllegalStateException("重置已被更新操作取代"))
        }

        val wallet = getWallet
            ?: return Result.failure(IllegalStateException("Wallet not initialized"))
        customRescanRequesting.set(true)
        updateScanPreparationIndicator()
        var preparationStarted = false
        var refreshPaused = false
        try {
            if (wallet.fullStatus.connectionStatus != Wallet.ConnectionStatus.ConnectionStatus_Connected) {
                throw IllegalStateException("Please connect to daemon for resync")
            }
            if (height < 0L) {
                throw IllegalArgumentException("Invalid restore height")
            }

            // The daemon's reported current height can be temporarily 0/stale while
            // the target height already has a valid value. Use both reports to avoid
            // rejecting a cached height/date selection before the reset even starts.
            val endHeightCandidate = if (dayEndHeight != null) {
                dayEndHeight
            } else {
                val daemonHeight = runCatching {
                    wallet.getDaemonBlockChainHeight()
                }.getOrDefault(0L)
                val daemonTargetHeight = runCatching {
                    wallet.getDaemonBlockChainTargetHeight()
                }.getOrDefault(0L)
                // Use the current chain tip for remaining-block arithmetic whenever
                // it is a valid end. The target estimate is only a fallback when the
                // current height has not yet reached the selected start.
                if (daemonHeight >= height) daemonHeight else maxOf(daemonHeight, daemonTargetHeight)
            }
            val effectiveEnd = endHeightCandidate.takeIf { it >= height }
                ?: throw IllegalArgumentException(
                    "Invalid custom end height: node height is below selected start height $height"
                )

            // This is an in-wallet rescan, not wallet creation/recovery. The height
            // entered here belongs only to this rescan operation. The wallet's
            // original recovery height is never read or overwritten by this path.
            //
            // Set the preparation gate before pausing/waiting for native wallet locks:
            // callbacks from the old sync must not be mistaken for this new scan.
            // A new explicit reset supersedes an older scan. Set the gate first
            // so callbacks from the old operation cannot publish progress or finish it.
            customRescanPreparing = true
            preparationStarted = true
            customRescanCompletionCallback = null
            customRescanInProgress = false
            customRescanJob?.cancelAndJoin()
            if (operationId != userOperationGeneration.get()) {
                return Result.failure(IllegalStateException("重置已被更新操作取代"))
            }
            customRescanJob = null

            // If the old scan had already entered its final refresh, wait for that
            // synchronous native work to leave before starting another rescan.
            customRescanFinishJob?.cancelAndJoin()
            if (operationId != userOperationGeneration.get()) {
                return Result.failure(IllegalStateException("重置已被更新操作取代"))
            }
            customRescanFinishJob = null

            customRescanScanStarted.set(false)
            customRescanFinishStarted.set(false)
            customRescanFinished = false
            customRescanFinalizing = false
            customRescanStartHeight = null
            customRescanDayEndHeight = null

            // A manual reset supersedes mnemonic-restore progress but does not
            // change the wallet's stored restore height. Capture the incremental
            // refresh so the reset can publish its intent before waiting for it.
            val pendingRestoreRefresh = scanDataRefreshJob
            restoreProgressInProgress = false
            restoreProgressFinalizing = false
            restoreProgressCompleted = false
            finishRestoreProgress()

            // Publish the chosen range before any potentially slow native pause/JNI
            // call. Home can show the initial remaining-block count while the service
            // waits for old native work to leave the wallet safely.
            customRescanStartHeight = height
            customRescanDayEndHeight = effectiveEnd
            customRescanInProgress = true
            // The shared caller already published this exact initial snapshot.
            // Preserve it while native refresh work exits and the rescan is queued.
            setLoading(true)

            pendingRestoreRefresh?.cancelAndJoin()
            if (operationId != userOperationGeneration.get()) {
                return Result.failure(IllegalStateException("重置已被更新操作取代"))
            }

            wallet.pauseRefresh()
            refreshPaused = true
            if (operationId != userOperationGeneration.get()) {
                return Result.failure(IllegalStateException("重置已被更新操作取代"))
            }

            // The native implementation serializes the clear/rescan request behind
            // any currently executing refresh. This method therefore runs on IO,
            // never on the UI thread (see ResetSyncPage).
            val initialWalletHeight = runCatching {
                wallet.getBlockChainHeight()
            }.getOrNull()?.takeIf { it >= 0L }

            if (!wallet.rescanBlockchainAsyncFromHeight(height)) {
                throw IllegalStateException("Blockchain rescan was not started")
            }
            if (operationId != userOperationGeneration.get()) {
                runCatching { wallet.pauseRefresh() }
                return Result.failure(IllegalStateException("重置已被更新操作取代"))
            }
            // pauseRefresh() disables the native refresh loop. Start it only after
            // the custom-height request is queued and still owns the newest generation.
            wallet.startRefresh()
            if (operationId != userOperationGeneration.get()) {
                runCatching { wallet.pauseRefresh() }
                return Result.failure(IllegalStateException("重置已被更新操作取代"))
            }

            // The native request has been accepted and the old scan cursor cleared.
            customRescanStartHeight = height
            customRescanDayEndHeight = effectiveEnd
            customRescanCompletionCallback = onCompleted
            customRescanInProgress = true

            // Publish the exact selected scan range as soon as the native rescan
            // request is accepted. The cursor poller below still validates the new
            // cursor before replacing this initial 0% / full-range snapshot.
            syncUpdate(SyncProgress(0f, (effectiveEnd - height).coerceAtLeast(0L)))

            startCustomRescanProgressPolling(wallet, height, effectiveEnd, initialWalletHeight, operationId)
            customRescanPreparing = false

            Timber.tag(TAG).i(
                "Reset wallet scan from custom height: requested=%d end=%d",
                height, effectiveEnd
            )

            // Leave the reset page only after the native request is accepted,
            // refresh has resumed, and the custom progress poller owns this scan.
            try {
                onPrepared?.invoke()
            } catch (callbackError: Exception) {
                // A navigation callback failure must not cancel a rescan already queued.
                Timber.tag(TAG).e(callbackError, "Failed to hand off UI after custom rescan started")
            }
            return Result.success(true)
        } catch (e: Exception) {
            if (preparationStarted && operationId == userOperationGeneration.get()) {
                // Keep callback suppression enabled while state is reset and the
                // existing refresh loop is resumed after a failed request.
                customRescanPreparing = true
                customRescanJob?.cancel()
                customRescanJob = null
                customRescanScanStarted.set(false)
                customRescanStartHeight = null
                customRescanDayEndHeight = null
                customRescanInProgress = false
                customRescanFinalizing = false
                customRescanFinished = false
                customRescanCompletionCallback = null
                _syncProgress.value = null
                _isSyncing.set(false)
                setLoading(false)
                if (refreshPaused) {
                    runCatching { wallet.startRefresh() }
                        .onFailure { Timber.tag(TAG).e(it, "Failed to resume refresh after reset error") }
                }
            }
            Timber.tag(TAG).e(e, "Failed to reset wallet scan from custom height: %d", height)
            return Result.failure(e)
        } finally {
            if (operationId == userOperationGeneration.get()) {
                customRescanPreparing = false
                customRescanRequesting.set(false)
                updateScanPreparationIndicator()
            }
        }
    }

    /**
     * Poll the native wallet's real scan cursor every 400ms for the custom-height
     * rescan. The normal sync callback is intentionally not used for this range,
     * because the daemon's full target height is not the custom operation's end.
     */
    private fun startCustomRescanProgressPolling(
        wallet: Wallet,
        startHeight: Long,
        endHeight: Long,
        initialWalletHeight: Long?,
        operationId: Long
    ) {
        customRescanJob?.cancel()
        customRescanJob = customRescanScope.launch {
            while (
                isActive &&
                customRescanInProgress &&
                operationId == userOperationGeneration.get()
            ) {
                val rawHeight = runCatching {
                    wallet.getBlockChainHeight()
                }.getOrNull()
                // A superseding user operation owns progress immediately. Do not let
                // a stale JNI height read overwrite that operation's initial snapshot.
                if (operationId != userOperationGeneration.get() || !customRescanInProgress) {
                    break
                }
                if (rawHeight == null || rawHeight < 0L) {
                    // Preserve the accurate initial (0%, end-start) snapshot while
                    // wallet2's cursor is temporarily unavailable. Never erase the
                    // progress bar during the native rescan hand-off.
                    _isSyncing.set(_syncProgress.value != null)
                    _isLoading.value = false
                    withTimeoutOrNull(400L) { customRescanProgressSignals.receive() }
                    continue
                }

                // rescanBlockchainAsyncFromHeight() only queues the native job.
                // Until wallet2 clears its old blockchain, getBlockChainHeight()
                // still reports the pre-rescan tip. That old tip may already be
                // >= endHeight, which must never be treated as completion.
                if (!customRescanScanStarted.get()) {
                    val cursorReachedSelectedRange = when {
                        initialWalletHeight == null || initialWalletHeight > startHeight ->
                            rawHeight <= startHeight
                        else -> rawHeight != initialWalletHeight && rawHeight >= startHeight
                    }
                    if (cursorReachedSelectedRange) {
                        customRescanScanStarted.set(true)
                    } else {
                        // The cursor still belongs to the pre-reset wallet. Keep the
                        // already-published selected-height total visible at 0%; it is
                        // derived from end-start, not from this stale cursor.
                        _isSyncing.set(_syncProgress.value != null)
                        _isLoading.value = false
                        withTimeoutOrNull(400L) { customRescanProgressSignals.receive() }
                        continue
                    }
                }

                val currentHeight = rawHeight.coerceAtLeast(startHeight)
                if (currentHeight >= endHeight) {
                    requestCustomRescanCompletion(wallet, endHeight, operationId)
                    break
                }

                val progress = calculateSyncProgress(startHeight, currentHeight, endHeight)
                // Keep 100% reserved for the shared completion/final-refresh path.
                syncUpdate(progress.copy(progress = progress.progress.coerceAtMost(0.999999f)))
                // Update on the next native block callback, or at least every 400ms.
                withTimeoutOrNull(400L) { customRescanProgressSignals.receive() }
            }
        }
    }

    /**
     * Called from the native newBlock callback. This gives us a faster stop at the
     * custom end height; the 400ms poller remains as a fallback if callbacks lag.
     */
    fun onCustomRescanBlock(height: Long) {
        if (customRescanPreparing || !customRescanInProgress) return
        val start = customRescanStartHeight ?: return
        val end = customRescanDayEndHeight ?: return

        // A callback is only a wake-up signal, never proof that the new scan started:
        // it may belong to the old chain tip while wallet2 is still clearing it.
        // The poller alone marks the scan started after reading the native cursor.
        if (height >= 0L && end >= start) {
            customRescanProgressSignals.trySend(Unit)
        }
    }

    private fun requestCustomRescanCompletion(
        wallet: Wallet,
        endHeight: Long,
        operationId: Long
    ) {
        if (operationId != userOperationGeneration.get() || !customRescanInProgress) return
        if (!customRescanFinishStarted.compareAndSet(false, true)) return

        // Stop the native refresh from the IO coroutine, never on a JNI callback
        // thread. Native callbacks are invoked while the listener mutex is held.
        customRescanFinishJob = customRescanScope.launch {
            runCatching { wallet.pauseRefresh() }
                .onFailure { Timber.tag(TAG).e(it, "Failed to stop custom rescan at %d", endHeight) }

            // pauseRefresh() is a blocking native barrier. A new user action may
            // supersede this coroutine while it waits, so check ownership again
            // before the old completion path touches history or publishes progress.
            if (operationId != userOperationGeneration.get() || !customRescanInProgress) {
                return@launch
            }
            completeCustomRescan(wallet, endHeight, operationId)
        }
    }

    private fun completeCustomRescan(wallet: Wallet, endHeight: Long, operationId: Long) {
        if (operationId != userOperationGeneration.get() || !customRescanInProgress) return

        customRescanInProgress = false
        customRescanFinalizing = true
        customRescanFinished = false
        customRescanScanStarted.set(false)
        customRescanJob?.cancel()
        customRescanStartHeight = null
        customRescanDayEndHeight = null

        try {
            // The custom rescan is complete at the user-selected end height.
            // Do not start a second normal synchronization and do not wait for a
            // future refreshed() callback to hide the progress indicator.
            // Refresh wallet data directly, then finish this custom operation.
            setLoading(true)
            wallet.refreshHistory()
            if (operationId != userOperationGeneration.get()) return
            wallet.refreshCoins(force = true)
            if (operationId != userOperationGeneration.get()) return
            wallet.store()
            if (operationId != userOperationGeneration.get()) return
            update()

            // Only the operation that still owns the wallet may publish completion.
            if (operationId != userOperationGeneration.get()) return
            syncUpdate(SyncProgress(1f, 0L))
            customRescanFinalizing = false
            customRescanFinished = true
            finishSync()

            Timber.tag(TAG).i(
                "Custom rescan reached end height=%d; final wallet refresh complete",
                endHeight
            )
            val onCompleted = customRescanCompletionCallback
            customRescanCompletionCallback = null
            runCatching { onCompleted?.invoke() }
                .onFailure { Timber.tag(TAG).e(it, "Post-rescan completion callback failed") }
        } catch (e: Exception) {
            if (operationId != userOperationGeneration.get()) return
            customRescanFinalizing = false
            customRescanFinished = true
            customRescanCompletionCallback = null
            Timber.tag(TAG).e(e, "Failed to finalize custom rescan wallet data")
            finishSync()
        }
    }

    fun resyncBlockchain(): Result<Boolean> {
        setLoading(true);
        try {
            if (getWallet?.fullStatus?.connectionStatus != Wallet.ConnectionStatus.ConnectionStatus_Connected) {
                return Result.failure(Exception("Please connect to daemon for resync"))
            }
            getWallet?.rescanBlockchainAsync()
            return Result.success(true)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e)
            return Result.failure(e)
        } finally {
            setLoading(false);
        }
    }

    private val getWallet get() = if (_isWiping.get()) null else WalletManager.instance?.wallet
}
