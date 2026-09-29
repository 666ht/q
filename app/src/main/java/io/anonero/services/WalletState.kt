package io.anonero.services

import androidx.compose.ui.util.fastDistinctBy
import androidx.compose.ui.util.fastFilter
import io.anonero.AnonConfig
import io.anonero.R
import io.anonero.model.CoinsInfo
import io.anonero.model.Subaddress
import io.anonero.model.TransactionInfo
import io.anonero.model.Wallet
import io.anonero.model.WalletManager
import io.anonero.model.node.DaemonInfo
import io.anonero.ui.util.getAllUsedSubAddresses
import io.anonero.ui.util.getLatestSubAddress
import io.anonero.ui.home.LockScreenShortCut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
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
    private var _resetSyncInProgress = AtomicBoolean(false)
    private val _backgroundSync = MutableStateFlow(false)
    private val _isWiping = AtomicBoolean(false)
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
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
    val walletConnectionStatus: Flow<Wallet.ConnectionStatus?> = _walletStatus.map { it?.connectionStatus }
    val daemonInfo: Flow<DaemonInfo?> = _connectedDaemon
    val connectionStatus: Flow<Wallet.ConnectionStatus?> = _connectionStatus
    val incomingTx = _incomingTx.asSharedFlow()

    fun update() {
        if (_blockUpdates.get()) return
        getWallet?.let { wallet ->
            if (wallet.isInitialized) {
                _balanceInfo.update { wallet.balance }
                _unLockedBalance.update {
                    if (AnonConfig.viewOnly) wallet.viewOnlyBalance()
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
            val oldTxCount = _transactions.value.size
            val updatedTxs = (wallet.history?.all
                ?.sortedWith(
                    compareByDescending<TransactionInfo> { it.timestamp }
                        .thenByDescending { it.hash ?: "" }
                )
                ?: listOf())
                .fastDistinctBy { it.getListKey() }
            _transactions.update { updatedTxs }
            if (oldTxCount > 0 && updatedTxs.size > oldTxCount) {
                val hasNewIncoming = updatedTxs.take(updatedTxs.size - oldTxCount).any {
                    it.direction == TransactionInfo.Direction.Direction_In
                }
                if (hasNewIncoming) _incomingTx.tryEmit(Unit)
            }
            if (!backgroundSync) {
                wallet.coins?.refresh()
                _nextAddress.update { wallet.getLatestSubAddress() }
                _subAddresses.update { wallet.getAllUsedSubAddresses().reversed() }
                _coins.update { (wallet.coins?.all ?: listOf()).fastFilter { !it.spent } }
            }
        }
    }

    fun prepareForWipe() {
        _isWiping.set(true)
        _blockUpdates.set(true)
        refreshScope.coroutineContext.cancelChildren()
        _backgroundSync.value = false
    }

    fun setLoading(b: Boolean) {
        if (_isWiping.get()) return
        _isLoading.update { b }
    }

    fun isWiping(): Boolean = _isWiping.get()

    fun isResetSyncInProgress(): Boolean = _resetSyncInProgress.get()

    fun finishResetSync() {
        _resetSyncInProgress.set(false)
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
            if (wallet.startBackgroundSync()) return true
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
        if (!_resetSyncInProgress.get() &&
            previous == Wallet.ConnectionStatus.ConnectionStatus_Disconnected &&
            status == Wallet.ConnectionStatus.ConnectionStatus_Connected) {
            setLoading(true)
            refreshScope.launch {
                try {
                    getWallet?.startRefresh()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "startRefresh error")
                    setLoading(false)
                }
            }
        }
    }

    fun updateDaemon(daemonInfo: DaemonInfo) {
        _connectedDaemon.update { daemonInfo }
    }

    fun syncUpdate(syncProgress: SyncProgress) {
        val done = syncProgress.progress >= 1f || syncProgress.left <= 0L
        _syncProgress.update { if (done) null else syncProgress }
        _isSyncing.set(!done)
        if (done) {
            // The native refresh has reached the daemon height. Mark the
            // connection as connected as upstream does, otherwise the home
            // progress indicator remains visible forever because it sees a
            // null/disconnected connection state.
            _connectionStatus.update {
                Wallet.ConnectionStatus.ConnectionStatus_Connected
            }
        }
    }

    /**
     * Publish the native wallet data at the exact end of a completed refresh.
     * This deliberately bypasses the normal update() gate because background-sync
     * state must not prevent the final balance/history from reaching the UI.
     */
    fun publishAfterSync(): Boolean {
        if (_isWiping.get()) return false
        val wallet = getWallet ?: return false
        if (!wallet.isInitialized) {
            Timber.tag(TAG).w("publishAfterSync: wallet is not initialized")
            return false
        }
        return try {
            // The native refresh has completed, but TransactionHistory is a Java
            // cache. Refresh it explicitly before reading it into StateFlow.
            wallet.refreshHistory()
            val balance = wallet.balance
            val unlocked = if (AnonConfig.viewOnly) wallet.viewOnlyBalance() else wallet.unlockedBalance
            val status = wallet.fullStatus
            val updatedTxs = (wallet.history?.all?.sortedByDescending { it.timestamp }
                ?: emptyList()).fastDistinctBy { it.getListKey() }

            _balanceInfo.value = balance
            _unLockedBalance.value = unlocked
            _walletStatus.value = status
            _transactions.value = updatedTxs

            // Keep the other wallet collections consistent with the final sync
            // snapshot without going through update(), which may be gated.
            if (!backgroundSync) {
                // Coins is a lazy Java cache; refresh it before reading all,
                // otherwise the first publishAfterSync after wallet open writes
                // an empty list and the UTXO screen stays blank.
                wallet.coins?.refresh()
                _nextAddress.value = wallet.getLatestSubAddress()
                _subAddresses.value = wallet.getAllUsedSubAddresses().reversed()
                _coins.value = (wallet.coins?.all ?: emptyList()).fastFilter { !it.spent }
            }

            val address = try {
                WalletManager.instance?.getDaemonAddress()
            } catch (_: Exception) {
                null
            }
            address?.let {
                _connectedDaemon.value = DaemonInfo(
                    it,
                    connectionStatus = status.connectionStatus
                        ?: Wallet.ConnectionStatus.ConnectionStatus_Disconnected,
                    WalletManager.instance?.getBlockchainHeight() ?: -1L
                )
            }
            Timber.tag(TAG).i(
                "publishAfterSync: balance=%s unlocked=%s transactions=%s",
                balance, unlocked, updatedTxs.size
            )
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "publishAfterSync failed")
            false
        }
    }

    fun getAddressForCoin(txHash: String?): String? {
        if (txHash.isNullOrEmpty()) return null
        val tx = _transactions.value.firstOrNull { it.hash == txHash } ?: return null
        return try {
            getWallet?.getSubaddress(tx.accountIndex, tx.addressIndex)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "getAddressForCoin failed")
            null
        }
    }

    fun freezeCoin(publicKey: String): Result<Boolean> {
        return try {
            val wallet = getWallet ?: return Result.failure(Exception("Wallet not initialized"))
            val coins = wallet.coins ?: return Result.failure(Exception("Coins not initialized"))
            coins.setFrozen(publicKey)
            wallet.store()
            coins.refresh()
            update()
            Result.success(true)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "freeze coin error")
            Result.failure(e)
        }
    }

    fun thawCoin(publicKey: String): Result<Boolean> {
        return try {
            val wallet = getWallet ?: return Result.failure(Exception("Wallet not initialized"))
            val coins = wallet.coins ?: return Result.failure(Exception("Coins not initialized"))
            coins.thaw(publicKey)
            wallet.store()
            coins.refresh()
            update()
            Result.success(true)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "thaw coin error")
            Result.failure(e)
        }
    }

    fun toggleHideAmounts() {
        hideAmountsFlow.update { !it }
    }

    fun setBackGroundSync(startBackgroundSync: Boolean) {
        _backgroundSync.update { startBackgroundSync }
    }

    fun getNewAddress() {
        getWallet?.let {
            it.addSubaddress(it.getAccountIndex(), "")
            it.store()
            it.getLatestSubAddress().let { subAddresses -> _nextAddress.update { subAddresses } }
            it.getAllUsedSubAddresses().let { allItems -> _subAddresses.update { allItems.reversed() } }
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
                val refreshed = allItems.reversed()
                _subAddresses.update { current ->
                    refreshed.map { address ->
                        current.firstOrNull { it.addressIndex == address.addressIndex }?.let { existing ->
                            if (existing.label != address.label) address.withLabel(address.label) else address
                        } ?: address
                    }
                }
            }
            it.getLatestSubAddress().let { latest -> _nextAddress.update { latest } }
        }
    }

    fun refresh() {
        val wallet = getWallet ?: return
        if (!wallet.isInitialized) return

        // Native wallet refresh is not re-entrant. During blockchain sync the
        // existing refresh must be allowed to finish; starting another refresh
        // from the UI can block the native wallet and make the screen appear
        // frozen. The active sync callback will publish the final data.
        if (_isSyncing.get() || wallet.isSynchronized.not()) {
            Timber.tag(TAG).d("refresh ignored while wallet sync is active")
            return
        }

        if (wallet.fullStatus.connectionStatus == Wallet.ConnectionStatus.ConnectionStatus_Connected) {
            setLoading(true)
            refreshScope.launch {
                try {
                    wallet.startRefresh()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "refresh error")
                    setLoading(false)
                }
            }
        }

        refreshScope.launch {
            try {
                wallet.refreshHistory()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "refreshHistory error")
            }
        }
    }

    fun resetSyncFromHeight(height: Long): Result<Boolean> {
        return try {
            val wallet = getWallet ?: return Result.failure(Exception("Wallet not initialized"))
            if (wallet.fullStatus.connectionStatus != Wallet.ConnectionStatus.ConnectionStatus_Connected) {
                return Result.failure(Exception(AnonConfig.context?.getString(R.string.resync_daemon_required) ?: "Please connect to daemon for resync"))
            }
            if (height < 0L) return Result.failure(IllegalArgumentException("Invalid restore height"))
            // This rescan owns the refresh lifecycle until the native wallet
            // reports completion. Do not let connection callbacks start a
            // second refresh while the requested restore height is active.
            _resetSyncInProgress.set(true)
            wallet.pauseRefresh()
            wallet.setRestoreHeight(height)
            wallet.store()
            setLoading(true)
            wallet.rescanBlockchainAsync()
            Result.success(true)
        } catch (e: Exception) {
            _resetSyncInProgress.set(false)
            Timber.tag(TAG).e(e, "reset sync error")
            Result.failure(e)
        }
    }

    fun resyncBlockchain(): Result<Boolean> {
        setLoading(true)
        try {
            if (getWallet?.fullStatus?.connectionStatus != Wallet.ConnectionStatus.ConnectionStatus_Connected) {
                return Result.failure(Exception(AnonConfig.context?.getString(R.string.resync_daemon_required) ?: "Please connect to daemon for resync"))
            }
            getWallet?.rescanBlockchainAsync()
            return Result.success(true)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e)
            return Result.failure(e)
        } finally {
            setLoading(false)
        }
    }

    private val getWallet get() = WalletManager.instance?.wallet
}