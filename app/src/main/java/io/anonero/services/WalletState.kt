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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicReference

data class SyncProgress(val progress: Float, val left: Long)

private const val TAG = "WalletState"

class WalletState {
    private var _blockUpdates = AtomicBoolean(false)
    val hideAmountsFlow = MutableStateFlow(false)
    private val _isLoading = MutableStateFlow(false)
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
    private val customRescanScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var customRescanJob: Job? = null
    private val customRescanFinishStarted = AtomicBoolean(false)

    // Explicit lifecycle for a user-triggered one-shot refresh. This is kept
    // separate from normal synchronization/custom-rescan state so a manual
    // pull cannot leave the generic loading indicator running forever.
    private val manualRefreshInProgress = AtomicBoolean(false)
    private var manualRefreshJob: Job? = null

    @Volatile
    var isManualRefreshInProgress: Boolean = false
        private set

    // During mnemonic restore, expose discovered wallet data while the native
    // scan is still running. Throttle the refresh to keep JNI/native work off
    // the per-block callback path and avoid refreshing once synchronization ends.
    private val restoreDataRefreshRunning = AtomicBoolean(false)
    private var restoreDataRefreshJob: Job? = null
    @Volatile
    private var lastRestoreDataRefreshAt = 0L

    // The async native request is queued before wallet2 clears its old blockchain.
    // Do not let the first poll of the old tip falsely finish the custom rescan.
    private val customRescanScanStarted = AtomicBoolean(false)

    @Volatile
    var customRescanInProgress: Boolean = false

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

    fun beginRestoreProgress(startHeight: Long) {
        lastRestoreDataRefreshAt = 0L
        restoreDataRefreshRunning.set(false)
        restoreProgressFinalizing = false
        restoreProgressStartHeight = startHeight
        restoreProgressRescanStarted = false
        restoreProgressCompleted = false
        restoreProgressInProgress = true
        // Drop any stale normal-sync "blocks left" value immediately. Until the
        // daemon reaches the restore height, the restore scan itself has no cursor.
        // Restore progress owns the sync indicator. Do not leave the generic
        // loading indicator active, otherwise the UI can show a progress bar
        // while there is no restore SyncProgress/remaining-block value yet.
        _syncProgress.value = null
        _isSyncing.set(true)
        _isLoading.value = false
    }

    fun markRestoreProgressRescanStarted() {
        if (restoreProgressInProgress) {
            restoreProgressRescanStarted = true
        }
    }

    fun initializeRestoreProgress(daemonHeight: Long, scanHeight: Long) {
        val start = restoreProgressStartHeight ?: return
        if (!restoreProgressInProgress || daemonHeight < start) {
            return
        }
        val current = maxOf(scanHeight, start)
        val left = (daemonHeight - current).coerceAtLeast(0L)
        val total = (daemonHeight - start).coerceAtLeast(1L)
        val progress = ((current - start).toDouble() / total.toDouble())
            .coerceIn(0.0, 1.0)
            .toFloat()
        syncUpdate(SyncProgress(progress, left))
    }

    fun finishRestoreProgress() {
        restoreProgressStartHeight = null
        restoreProgressRescanStarted = false
        restoreProgressInProgress = false
        // Cancel any queued/running incremental refresh so it cannot race
        // the final restore refresh or publish a transient empty history.
        restoreDataRefreshJob?.cancel()
        restoreDataRefreshJob = null
        // Do not let a late incremental refresh publish a second sync pass.
        // A final refresh is performed by MoneroHandlerThread after remaining=0.
        restoreDataRefreshRunning.set(false)
    }

    /**
     * Refresh balance/history during mnemonic restore without waiting for the
     * scan to finish. Calls are throttled to about 400ms and run off the native
     * block callback thread.
     */
    fun requestRestoreDataRefresh() {
        if (!restoreProgressInProgress ||
            restoreProgressCompleted ||
            restoreProgressFinalizing
        ) return

        val now = System.currentTimeMillis()
        if (now - lastRestoreDataRefreshAt < 400L) return
        if (!restoreDataRefreshRunning.compareAndSet(false, true)) return

        lastRestoreDataRefreshAt = now
        restoreDataRefreshJob = customRescanScope.launch {
            try {
                val wallet = getWallet ?: return@launch
                if (!wallet.isInitialized ||
                    !restoreProgressInProgress ||
                    restoreProgressCompleted ||
                    restoreProgressFinalizing
                ) {
                    return@launch
                }

                // The native restore scan is already discovering new wallet
                // transactions/balance. Refresh only the visible wallet data;
                // do not start another synchronization.
                wallet.refreshHistory()

                // A final restore refresh may have started while this native call
                // was running. Never publish its stale/intermediate snapshot.
                if (!restoreProgressFinalizing &&
                    restoreProgressInProgress &&
                    !restoreProgressCompleted
                ) {
                    update()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Incremental restore data refresh failed")
            } finally {
                restoreDataRefreshRunning.set(false)
                restoreDataRefreshJob = null
            }
        }
    }

    val transactions: Flow<List<TransactionInfo>> = _transactions

    val balanceInfo: Flow<Long?> = _balanceInfo
    val unLockedBalance: Flow<Long?> = _unLockedBalance
    val isLoading: Flow<Boolean> = _isLoading
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

            // During the final restore refresh, do NOT replace the already visible
            // transaction list. Keep every transaction the user has already seen
            // and append only records that were not discovered during the scan.
            // This turns the final pass into a pure "fill missing" operation and
            // prevents the visible list from disappearing/reappearing.
            val updatedTxs = if (restoreProgressFinalizing) {
                val existingKeys = oldTransactions.asSequence()
                    .map { it.getListKey() }
                    .toHashSet()
                oldTransactions + discoveredTxs.filter {
                    it.getListKey() !in existingKeys
                }
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

        // Do not carry restore/sync UI state into a newly restored wallet in
        // the same process. A secure wipe must also terminate the old progress
        // window held by this singleton state object.
        restoreProgressStartHeight = null
        restoreProgressRescanStarted = false
        restoreProgressInProgress = false
        restoreProgressCompleted = false
        restoreProgressFinalizing = false
        lastRestoreDataRefreshAt = 0L
        restoreDataRefreshRunning.set(false)
        _syncProgress.value = null
        _isSyncing.set(false)
    }

    fun isWiping(): Boolean = _isWiping.get()

    fun setLoading(b: Boolean) {
        // Once mnemonic restore owns progress, the generic loading flag must not
        // replace the real restore SyncProgress with an indeterminate bar.
        if (b && restoreProgressInProgress) return
        this._isLoading.update { b }
    }

    fun emitUnlockShortcut(shortcut: LockScreenShortCut) {
        _unlockShortcut.trySend(shortcut)
    }
    
    suspend fun enterBackgroundSync(): Boolean = bgSyncMutex.withLock {
        val wallet = getWallet ?: return false
        if (!wallet.isInitialized || backgroundSync) return false
        emitUnlockShortcut(LockScreenShortCut.HOME)
        _backgroundSync.value = true
        if (AnonConfig.viewOnly) return true
        _blockUpdates.set(true)
        try {
            if (wallet.startBackgroundSync()) {
                Timber.tag(TAG).i("Entered background sync")
                return true
            }
            _backgroundSync.value = false
            Timber.tag(TAG).e("startBackgroundSync returned false")
            return false
        } catch (e: Exception) {
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
        _blockUpdates.set(true)
        try {
            if (wallet.stopBackgroundSync(pin)) {
                _backgroundSync.value = false
                update()
                wallet.startRefresh()
                Timber.tag(TAG).i("Exited background sync")
                return true
            }
            Timber.tag(TAG).e("stopBackgroundSync returned false")
            return false
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "stopBackgroundSync error")
            return false
        } finally {
            _blockUpdates.set(false)
        }
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
        // For mnemonic restore, reaching 100% only means the wallet scan cursor
        // reached the captured daemon tip. Native wallet synchronization can still
        // be finishing its final callbacks, so keep the progress visible until
        // MoneroHandlerThread calls finishSync().
        val done = syncProgress.progress >= 1f
        val restoreActive = restoreProgressInProgress
        _syncProgress.update {
            if (done && !restoreActive) null else syncProgress
        }
        _isSyncing.set(!(done && !restoreActive))
        if (done && !restoreActive) {
            _connectionStatus.update { Wallet.ConnectionStatus.ConnectionStatus_Connected }
        }
    }

    fun finishSync() {
        _syncProgress.value = null
        _isSyncing.set(false)
        _isLoading.value = false
        _connectionStatus.update { Wallet.ConnectionStatus.ConnectionStatus_Connected }
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

    fun beginManualRefresh() {
        val wallet = getWallet ?: return
        if (!wallet.isInitialized ||
            wallet.fullStatus.connectionStatus != Wallet.ConnectionStatus.ConnectionStatus_Connected) {
            return
        }

        manualRefreshJob?.cancel()
        manualRefreshInProgress.set(true)
        isManualRefreshInProgress = true
        setLoading(true)

        // refreshAsync() normally completes through WalletListener.refreshed().
        // Keep a safety timeout so a lost native callback can never leave the
        // loading indicator spinning forever.
        manualRefreshJob = customRescanScope.launch {
            delay(15_000L)
            if (manualRefreshInProgress.compareAndSet(true, false)) {
                isManualRefreshInProgress = false
                Timber.tag(TAG).w("Manual refresh callback timed out; finishing refresh state")
                _syncProgress.value = null
                _isSyncing.set(false)
                _isLoading.value = false
                _connectionStatus.update { Wallet.ConnectionStatus.ConnectionStatus_Connected }
                manualRefreshJob = null
            }
        }
        try {
            wallet.refreshAsync()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Manual refresh failed to start")
            completeManualRefresh()
        }
    }

    fun completeManualRefresh() {
        if (!manualRefreshInProgress.compareAndSet(true, false)) return
        isManualRefreshInProgress = false
        manualRefreshJob?.cancel()
        manualRefreshJob = null
        _syncProgress.value = null
        _isSyncing.set(false)
        _isLoading.value = false
        _connectionStatus.update { Wallet.ConnectionStatus.ConnectionStatus_Connected }
    }

    fun refresh() {
        if(getWallet?.isInitialized != true) {
            return;
        }
        customRescanFinished = false
        beginManualRefresh()
        getWallet?.refreshHistory()
    }

    fun resetSyncFromHeight(height: Long, dayEndHeight: Long? = null): Result<Boolean> = try {
        val wallet = getWallet ?: return Result.failure(Exception("Wallet not initialized"))
        if (wallet.fullStatus.connectionStatus != Wallet.ConnectionStatus.ConnectionStatus_Connected) {
            return Result.failure(Exception("Please connect to daemon for resync"))
        }
        if (height < 0L) {
            return Result.failure(IllegalArgumentException("Invalid restore height"))
        }

        // This is an in-wallet rescan, not wallet creation/recovery. The height
        // entered here belongs only to this rescan operation. The wallet's
        // original recovery height is never read or overwritten by this path.
        wallet.pauseRefresh()
        try {
            customRescanFinishStarted.set(false)
            customRescanScanStarted.set(false)
            customRescanFinished = false
            customRescanFinalizing = false

            // A custom scan always has a hard end: use the requested end height
            // when provided, otherwise stop at the daemon's current height.
            val effectiveEnd = (dayEndHeight ?: wallet.getDaemonBlockChainHeight())
                .takeIf { it >= height }
                ?: throw IllegalArgumentException("Invalid custom end height")

            setLoading(true)
            customRescanJob?.cancel()
            customRescanStartHeight = height
            customRescanDayEndHeight = effectiveEnd
            customRescanInProgress = true

            if (!wallet.rescanBlockchainAsyncFromHeight(height)) {
                customRescanStartHeight = null
                customRescanDayEndHeight = null
                customRescanInProgress = false
                setLoading(false)
                wallet.startRefresh()
                return Result.failure(
                    IllegalStateException("Blockchain rescan was not started")
                )
            }

            startCustomRescanProgressPolling(wallet, height, effectiveEnd)

            Timber.tag(TAG).i(
                "Reset wallet scan from custom height: requested=%d end=%d",
                height, effectiveEnd
            )
            Result.success(true)
        } catch (e: Exception) {
            customRescanJob?.cancel()
            customRescanScanStarted.set(false)
            customRescanStartHeight = null
            customRescanDayEndHeight = null
            customRescanInProgress = false
            setLoading(false)
            wallet.startRefresh()
            throw e
        }
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Failed to reset wallet scan from custom height: %d", height)
        Result.failure(e)
    }

    /**
     * Poll the native wallet's real scan cursor every 400ms for the custom-height
     * rescan. The normal sync callback is intentionally not used for this range,
     * because the daemon's full target height is not the custom operation's end.
     */
    private fun startCustomRescanProgressPolling(
        wallet: Wallet,
        startHeight: Long,
        endHeight: Long
    ) {
        customRescanJob?.cancel()
        customRescanJob = customRescanScope.launch {
            while (isActive && customRescanInProgress) {
                val rawHeight = runCatching {
                    wallet.getBlockChainHeight()
                }.getOrDefault(startHeight)

                // rescanBlockchainAsyncFromHeight() only queues the native job.
                // Until wallet2 clears its old blockchain, getBlockChainHeight()
                // still reports the pre-rescan tip. That old tip may already be
                // >= endHeight, which must never be treated as completion.
                if (!customRescanScanStarted.get()) {
                    if (rawHeight <= startHeight) {
                        customRescanScanStarted.set(true)
                    } else {
                        syncUpdate(SyncProgress(0f, (endHeight - startHeight).coerceAtLeast(0L)))
                        delay(400)
                        continue
                    }
                }

                val currentHeight = rawHeight.coerceAtLeast(startHeight)
                if (currentHeight >= endHeight) {
                    requestCustomRescanCompletion(wallet, endHeight)
                    break
                }

                val total = (endHeight - startHeight).coerceAtLeast(1L)
                val left = (endHeight - currentHeight).coerceAtLeast(0L)
                val progress = ((currentHeight - startHeight).toDouble() / total.toDouble())
                    .coerceIn(0.0, 0.999999)
                    .toFloat()

                syncUpdate(SyncProgress(progress, left))
                delay(400)
            }
        }
    }

    /**
     * Called from the native newBlock callback. This gives us a faster stop at the
     * custom end height; the 400ms poller remains as a fallback if callbacks lag.
     */
    fun onCustomRescanBlock(height: Long) {
        if (!customRescanInProgress) return
        val wallet = getWallet ?: return
        val start = customRescanStartHeight ?: return
        val end = customRescanDayEndHeight ?: return

        // Ignore callbacks from the old pre-rescan chain tip. A real custom
        // rescan is considered started only after callbacks enter its requested
        // range, or the polling path observes the native chain reset.
        if (!customRescanScanStarted.get()) {
            if (height <= start) {
                customRescanScanStarted.set(true)
            } else if (height < end) {
                customRescanScanStarted.set(true)
            } else {
                return
            }
        }

        if (height >= end) {
            requestCustomRescanCompletion(wallet, end)
            return
        }

        val total = (end - start).coerceAtLeast(1L)
        val current = height.coerceAtLeast(start)
        val left = (end - current).coerceAtLeast(0L)
        val progress = ((current - start).toDouble() / total.toDouble())
            .coerceIn(0.0, 0.999999)
            .toFloat()
        syncUpdate(SyncProgress(progress, left))
    }

    private fun requestCustomRescanCompletion(wallet: Wallet, endHeight: Long) {
        if (!customRescanInProgress) return
        if (!customRescanFinishStarted.compareAndSet(false, true)) return

        // Stop the native refresh immediately when the custom end height is reached.
        runCatching { wallet.pauseRefresh() }
            .onFailure { Timber.tag(TAG).e(it, "Failed to stop custom rescan at %d", endHeight) }

        // Native callbacks run under the JNI listener lock. Do the potentially
        // callback-producing wallet refreshes off that thread.
        customRescanScope.launch {
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
            // Re-enter the normal refresh lifecycle. The first refreshed() callback
            // is the synchronization barrier for the post-rescan wallet data.
            setLoading(true)
            wallet.startRefresh()
            Timber.tag(TAG).i(
                "Custom rescan reached end height=%d; entering final refresh",
                endHeight
            )
        } catch (e: Exception) {
            customRescanFinalizing = false
            customRescanFinished = true
            Timber.tag(TAG).e(e, "Failed to restart refresh after custom rescan")
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
