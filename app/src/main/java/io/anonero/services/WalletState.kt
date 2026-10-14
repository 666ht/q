package io.anonero.services

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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
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

class WalletState {
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
    private val _connectedDaemon = MutableStateFlow<DaemonInfo?>(null)
    private val _connectionStatus = MutableStateFlow<Wallet.ConnectionStatus?>(null)
    private val _previousConnectionStatus = AtomicReference<Wallet.ConnectionStatus?>(null)
    private val _unlockShortcut = Channel<LockScreenShortCut>(capacity = 1)
    val unlockShortcut = _unlockShortcut.receiveAsFlow()
    private val bgSyncMutex = Mutex()
    // Activity teardown must not cancel a wallet background-transition operation
    // while native wallet2 is switching refresh modes.
    private val backgroundLifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Distinguish the UI PIN lock from native wallet2 background-sync mode.
    // Switching the native wallet while a foreground scan is active can race its refresh worker.
    private val nativeBackgroundSyncActive = AtomicBoolean(false)
    private val customRescanScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Serialize user-requested refresh/reset/query operations. The generation changes
    // as soon as a new action is requested, so older queued work can never take over later.
    private val userOperationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val userOperationMutex = Mutex()
    private val userOperationGeneration = AtomicLong(0L)
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
        _isLoading.value = false
        updateScanPreparationIndicator()
        Timber.tag(TAG).i("Starting ordinary refresh before height scan: start=%d", startHeight)
    }

    fun armOrdinaryHeightRefresh() {
        if (heightRefreshInProgress) ordinaryRefreshRequested = true
    }

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
        if (_blockUpdates.get()) return
        getWallet?.let { wallet ->
            if (wallet.isInitialized) {
                _balanceInfo.update { wallet.balance }
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
                address?.let {
                    _connectedDaemon.update {
                        DaemonInfo(
                            address,
                            connectionStatus = wallet.fullStatus.connectionStatus
                                ?: Wallet.ConnectionStatus.ConnectionStatus_Disconnected,
                            WalletManager.instance?.getBlockchainHeight() ?: -1L
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
        _isWiping.set(true)
        _blockUpdates.set(true)
        _backgroundSync.value = false
        nativeBackgroundSyncActive.set(false)

        // Do not carry restore/sync UI state into a newly restored wallet in
        // the same process. A secure wipe must also terminate the old progress
        // window held by this singleton state object.
        restoreProgressStartHeight = null
        restoreProgressRescanStarted = false
        restoreProgressInitialWalletHeight = null
        restoreProgressCursorObserved = false
        restoreProgressInProgress = false
        restoreProgressCompleted = false
        restoreProgressFinalizing = false
        lastScanDataRefreshAt = 0L
        scanDataRefreshRunning.set(false)
        _syncProgress.value = null
        _isSyncing.set(false)
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
    fun requestBackgroundSyncOnLeave() {
        backgroundLifecycleScope.launch {
            runCatching { enterBackgroundSync() }
                .onFailure { Timber.tag(TAG).e(it, "Background wallet transition failed") }
        }
    }
    
    suspend fun enterBackgroundSync(): Boolean = bgSyncMutex.withLock {
        val wallet = getWallet ?: return false
        if (!wallet.isInitialized || backgroundSync) return false
        emitUnlockShortcut(LockScreenShortCut.HOME)
        _backgroundSync.value = true
        if (AnonConfig.viewOnly) return true

        // Never switch wallet2 into native background-sync mode while its normal
        // refresh/rescan worker is active. That transition can race the native scan
        // when the user leaves the app mid-sync and crash on the next activity launch.
        val scanActive = isSyncing ||
            _isLoading.value ||
            restoreProgressInProgress ||
            restoreProgressFinalizing ||
            customRescanInProgress ||
            customRescanFinalizing ||
            !wallet.isSynchronized ||
            !wallet.nativeSynchronized
        if (scanActive) {
            nativeBackgroundSyncActive.set(false)
            Timber.tag(TAG).i("Keeping foreground scan active; PIN lock only")
            return true
        }

        _blockUpdates.set(true)
        try {
            if (wallet.startBackgroundSync()) {
                nativeBackgroundSyncActive.set(true)
                Timber.tag(TAG).i("Entered native background sync")
                return true
            }
            nativeBackgroundSyncActive.set(false)
            _backgroundSync.value = false
            Timber.tag(TAG).e("startBackgroundSync returned false")
            return false
        } catch (e: Exception) {
            nativeBackgroundSyncActive.set(false)
            _backgroundSync.value = false
            Timber.tag(TAG).e(e, "startBackgroundSync error")
            return false
        } finally {
            _blockUpdates.set(false)
        }
    }

    suspend fun exitBackgroundSync(pin: String): Boolean = bgSyncMutex.withLock {
        val wallet = getWallet ?: return false
        if (AnonConfig.viewOnly) {
            _backgroundSync.value = false
            update()
            return true
        }

        // If this was only a UI lock during an active foreground scan, do not call
        // native stopBackgroundSync(): that native mode was never started.
        if (!nativeBackgroundSyncActive.get()) {
            _backgroundSync.value = false
            update()
            Timber.tag(TAG).i("Released PIN lock; foreground scan remained active")
            return true
        }

        // Keep the UI locked and suppress state publications while the native
        // wallet leaves background sync.
        _blockUpdates.set(true)
        val exited = try {
            if (wallet.stopBackgroundSync(pin)) {
                nativeBackgroundSyncActive.set(false)
                Timber.tag(TAG).i("Native background sync stopped")
                true
            } else {
                Timber.tag(TAG).e("stopBackgroundSync returned false")
                false
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "stopBackgroundSync error")
            false
        } finally {
            _blockUpdates.set(false)
        }

        if (!exited) return false

        // Re-read native wallet data after the background worker has stopped,
        // then publish the fresh output list while still holding the overlay.
        runCatching { wallet.refreshHistory() }
            .onFailure { Timber.tag(TAG).e(it, "History refresh after background sync failed") }
        runCatching { wallet.refreshCoins(force = true) }
            .onFailure { Timber.tag(TAG).e(it, "Output refresh after background sync failed") }

        _backgroundSync.value = false
        update()
        wallet.startRefresh()
        Timber.tag(TAG).i("Exited background sync and refreshed wallet data")
        true
    }

    fun setConnectionStatus(status: Wallet.ConnectionStatus) {
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

    fun updateDaemon(daemonInfo: DaemonInfo) {
        this._connectedDaemon.update { daemonInfo }
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
        _manualRefreshInProgress.value = false
        // Normal synchronization/custom-rescan completion has already refreshed
        // history and outputs. A deferred request is satisfied by that snapshot.
        deferredManualRefresh.set(false)
        _syncProgress.value = null
        _isSyncing.set(false)
        _isLoading.value = false
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
        _backgroundSync.update { startBackgroundSync }
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

    fun refresh() {
        val wallet = getWallet
        if (wallet?.isInitialized != true) return

        // Reset the indicator as soon as a new pull request supersedes the old one.
        // Mark it active only after prior native work has been paused and this manual
        // refresh is actually about to start, so stale callbacks cannot clear it.
        _manualRefreshInProgress.value = false

        // Signal cancellation synchronously without waiting for a native lock.
        // A newer manual refresh must also release a reset that is waiting for the
        // ordinary-refresh height callback, rather than inherit its timeout.
        val operationId = userOperationGeneration.incrementAndGet()
        finishOrdinaryHeightRefresh()
        runCatching { wallet.cancelCurrentOperation(operationId) }
            .onFailure { Timber.tag(TAG).w(it, "Could not signal cancellation before manual refresh") }

        userOperationScope.launch {
            userOperationMutex.lock()
            try {
                if (operationId != userOperationGeneration.get()) return@launch
                withContext(Dispatchers.IO) { wallet.pauseRefresh() }
                if (operationId != userOperationGeneration.get()) return@launch

                cancelScanUiStateForLatestOperation()
                deferredManualRefresh.set(false)
                customRescanFinished = false
                restoreProgressCompleted = false

                if (wallet.fullStatus.connectionStatus == Wallet.ConnectionStatus.ConnectionStatus_Connected) {
                    _manualRefreshInProgress.value = true
                    setLoading(true)
                    wallet.startRefresh()
                } else {
                    // No native refresh will be started while disconnected.
                    _manualRefreshInProgress.value = false
                }
                if (operationId == userOperationGeneration.get()) {
                    runCatching { wallet.refreshHistory() }
                        .onFailure {
                            _manualRefreshInProgress.value = false
                            Timber.tag(TAG).e(it, "Manual refresh history update failed")
                        }
                }
            } catch (e: Exception) {
                _manualRefreshInProgress.value = false
                Timber.tag(TAG).e(e, "Manual refresh operation failed")
            } finally {
                userOperationMutex.unlock()
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
        val wallet = getWallet
            ?: return Result.failure(IllegalStateException("Wallet not initialized"))
        val operationId = userOperationGeneration.incrementAndGet()
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
        val wallet = getWallet
            ?: return Result.failure(IllegalStateException("Wallet not initialized"))
        if (!wallet.isInitialized) {
            return Result.failure(IllegalStateException("Wallet not initialized"))
        }
        if (height < 0L) {
            return Result.failure(IllegalArgumentException("Invalid restore height"))
        }

        val operationId = userOperationGeneration.incrementAndGet()
        return try {
            // The newest request cancels older native work first. Keep a preparation
            // indicator visible while the ordinary refresh retrieves the live daemon tip.
            wallet.cancelCurrentOperation(operationId)
            if (operationId != userOperationGeneration.get()) {
                return Result.failure(IllegalStateException("重置已被更新操作取代"))
            }
            beginOrdinaryHeightRefresh(height)

            userOperationMutex.lock()
            try {
                if (operationId != userOperationGeneration.get()) {
                    return Result.failure(IllegalStateException("重置已被更新操作取代"))
                }

                // First run the normal wallet refresh loop. Only a connected callback
                // from this request may satisfy the wait; target-height estimates are
                // deliberately excluded from the remaining-block calculation.
                armOrdinaryHeightRefresh()
                wallet.startRefresh()
                val refreshedDaemonHeight = awaitOrdinaryRefreshHeight()
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

                // The counter is now grounded in the selected start and a live tip.
                // Return home after publishing it, then hand off to the native rescan.
                onPrepared?.invoke()
                val result = resetSyncFromHeightInternal(
                    height,
                    effectiveEndHeight,
                    operationId,
                    onCompleted,
                    null
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
            syncUpdate(SyncProgress(0f, initialRemaining))

            startCustomRescanProgressPolling(wallet, height, effectiveEnd, initialWalletHeight, operationId)
            customRescanPreparing = false

            Timber.tag(TAG).i(
                "Reset wallet scan from custom height: requested=%d end=%d",
                height, effectiveEnd
            )
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
                    requestCustomRescanCompletion(wallet, endHeight)
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

    private fun requestCustomRescanCompletion(wallet: Wallet, endHeight: Long) {
        if (!customRescanInProgress) return
        if (!customRescanFinishStarted.compareAndSet(false, true)) return

        // Stop the native refresh from the IO coroutine, never on a JNI callback
        // thread. Native callbacks are invoked while the listener mutex is held.
        customRescanFinishJob = customRescanScope.launch {
            runCatching { wallet.pauseRefresh() }
                .onFailure { Timber.tag(TAG).e(it, "Failed to stop custom rescan at %d", endHeight) }
            completeCustomRescan(wallet, endHeight)
        }
    }

    private fun completeCustomRescan(wallet: Wallet, endHeight: Long) {
        if (!customRescanInProgress) return

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
            wallet.refreshCoins(force = true)
            wallet.store()
            update()

            // Publish the terminal state before clearing the progress indicator.
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

    private val getWallet get() = WalletManager.instance?.wallet
}
