package io.anonero.services

import android.content.SharedPreferences
import io.anonero.AnonConfig
import io.anonero.model.WalletManager
import io.anonero.model.node.Node
import io.anonero.model.node.NodeFields
import org.json.JSONObject
import io.anonero.util.RESTORE_HEIGHT
import io.anonero.util.RESTORE_NEEDS_RESCAN
import io.anonero.util.WALLET_PROXY
import io.anonero.util.WALLET_PROXY_PORT
import io.anonero.util.WALLET_USE_TOR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import timber.log.Timber
import androidx.core.content.edit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger


class InvalidPin : Exception()

private const val TAG = "AnonWalletHandler"

class UnableToCloseWallet : Exception()

class AnonWalletHandler(
    private val prefs: SharedPreferences,
    private val walletState: WalletState,
    private val torService: TorService
) {

    private val _scope = CoroutineScope(Dispatchers.Default) + SupervisorJob()

    private var handler: MoneroHandlerThread? = null
    private var torSocksJob: Job? = null
    private val wipeInProgress = AtomicBoolean(false)
    private val secureWipeCleanupTasks = AtomicInteger(0)
    private val secureWipeRequestStartedInProcess = AtomicBoolean(false)
    private val secureWipeWasBackgroundSync = AtomicBoolean(false)

    /** True once a validated request exists; persisted requests also block wallet work. */
    fun isSecureWipeInProgress(): Boolean =
        wipeInProgress.get() || secureWipeCleanupTasks.get() > 0 ||
            secureWipeRequestStartedInProcess.get()

    fun isSecureWipeCleanupActive(): Boolean =
        wipeInProgress.get() || secureWipeCleanupTasks.get() > 0

    fun markSecureWipeRequestStartedInProcess() {
        secureWipeRequestStartedInProcess.set(true)
        AnonConfig.context?.markSecureWipeStartedInProcess()
    }

    fun markSecureWipeCleanupStarted() {
        secureWipeCleanupTasks.incrementAndGet()
    }

    fun markSecureWipeCleanupFinished() {
        secureWipeCleanupTasks.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    private fun secureWipePriorityActive(): Boolean =
        isSecureWipeInProgress() || walletState.isWiping() ||
            (AnonConfig.context?.let { AnonConfig.isSecureWipePending(it) } == true)

    /**
     * Raise the sticky wipe gate before any destructive operation. A valid request
     * must prevent wallet opening, refresh startup, proxy changes, and node work.
     */
    fun prepareForSecureWipeRequest() {
        secureWipeRequestStartedInProcess.set(true)
        if (walletState.backgroundSync) {
            secureWipeWasBackgroundSync.set(true)
        }
        // This is the immediate priority gate and can be called from a UI lifecycle
        // callback. Do not call wallet2/JNI here: pauseRefresh() can wait for the
        // active scan's native lock and freeze the UI when invoked on the main thread.
        // The deletion worker performs native listener detach, pause, and close in
        // order from its IO coroutine below.
        walletState.prepareForWipe()
        runCatching { torService.stop() }
            .onFailure { Timber.tag(TAG).e(it, "Unable to stop Tor for secure deletion") }
    }

    val scope get() = _scope

    init {
        torSocksJob = scope.launch {
            torService.socksFlow.collect {
                if (secureWipePriorityActive() || walletState.isWiping()) return@collect
                val wallet = WalletManager.instance?.wallet
                if (prefs.getBoolean(WALLET_USE_TOR, false) && wallet?.isInitialized == true) {
                    setProxy(it.address.toString(), it.port.value)
                }
            }
        }
    }

    fun openWallet(pin: String): Boolean {
        if (secureWipePriorityActive()) return false
        val walletFile = AnonConfig.getDefaultWalletFile(AnonConfig.context!!)
        val anonWallet = WalletManager.instance?.openWallet(
            walletFile.path,
            pin,
        )
        if (anonWallet?.status?.isOk != true) {
            Timber.tag(TAG).e("openWallet error: %s", anonWallet?.status?.errorString)
        }
        return anonWallet?.status?.isOk ?: throw InvalidPin()
    }

    suspend fun startService() {
        if (secureWipePriorityActive()) return
        val wallet = WalletManager.instance?.wallet ?: return
        // Read and arm mnemonic-restore progress before attaching the native
        // listener. Otherwise an early normal-sync callback can publish the old
        // daemon target estimate (~100k) before restore mode takes control.
        val needsRestoreRescan = prefs.getBoolean(RESTORE_NEEDS_RESCAN, false)
        // RESTORE_HEIGHT is a one-shot restore instruction, not a permanent
        // sync start height. Ignore and clean stale values once that restore has
        // finished; otherwise a temporary unsynchronized state after refresh can
        // incorrectly queue another full rescan when the app is reopened.
        val savedRestoreHeight = if (needsRestoreRescan) {
            prefs.getLong(RESTORE_HEIGHT, 0L)
        } else {
            0L
        }
        val restoreScanPending = savedRestoreHeight > 0L && !wallet.nativeSynchronized
        if (!restoreScanPending) {
            // Migrate the wallet's existing saved cursor before callbacks begin. Older
            // builds did not persist an app-side completed-sync marker.
            runCatching { wallet.getBlockChainHeight() }
                .getOrDefault(0L)
                .let(walletState::rememberKnownWalletCursor)

            // Wallet.isSynchronized is an in-memory Kotlin flag and defaults to
            // false after every process restart. Seed it from native wallet2 before
            // attaching listeners, so a previously-synced wallet isn't classified
            // as a first sync merely because a new Wallet wrapper was created.
            wallet.isSynchronized = runCatching { wallet.nativeSynchronized }
                .onFailure { Timber.tag(TAG).w(it, "Could not restore native synchronized state") }
                .getOrDefault(false)
        }
        if (restoreScanPending) {
            // Keep the chosen start height armed, but allow ordinary-refresh listener
            // callbacks through so they can obtain the current daemon tip.
            walletState.beginRestoreProgress(savedRestoreHeight)
        }

        handler = MoneroHandlerThread(
            wallet,
            walletState
        ) {
            // Consume the one-shot restore request when the restore scan has
            // actually completed, not on a later app launch.
            prefs.edit(commit = true) {
                putBoolean(RESTORE_NEEDS_RESCAN, false)
                remove(RESTORE_HEIGHT)
            }
        }

        wallet.setListener(handler)
        // A selected-height mnemonic restore must enter the shared reset scan directly.
        // Its finalization refreshes history and outputs; do not refresh history before scanning.
        if (!restoreScanPending) {
            wallet.refreshHistory()
        }
        handler?.start()
        if (!restoreScanPending) {
            walletState.setLoading(true)
        }
        walletState.update()
        try {
            val host = prefs.getString(NodeFields.RPC_HOST.value, "")
            val rpcPort = prefs.getInt(NodeFields.RPC_PORT.value, Node.defaultRpcPort)
            val rpcUsername = prefs.getString(NodeFields.RPC_USERNAME.value, "")
            val rpcPassphrase = prefs.getString(NodeFields.RPC_PASSWORD.value, "")
            val proxyHost = prefs.getString(WALLET_PROXY, "")
            val proxyPort = prefs.getInt(WALLET_PROXY_PORT, -1)
            val useTor = prefs.getBoolean(WALLET_USE_TOR, true)
            if (useTor || proxyHost.isNullOrBlank()) {
                torService.start()
                val socket = withTimeoutOrNull(120_000L) {
                    while (torService.socks == null) {
                        delay(200)
                    }
                    torService.socks
                } ?: throw IllegalStateException("Tor 代理启动超时，无法执行普通刷新获取最新高度")
                WalletManager.instance?.setProxy("${socket.value}")
            } else if (proxyHost.isNotEmpty() && proxyPort != -1) {
                WalletManager.instance?.setProxy("${proxyHost}:$proxyPort")
            } else {
                throw Exception("no proxy")
            }

            if (host?.isNotEmpty() == true) {
                val nodeObj = JSONObject().apply {
                    put(NodeFields.RPC_HOST.value, host)
                    put(NodeFields.RPC_PORT.value, rpcPort)
                    put(NodeFields.RPC_USERNAME.value, rpcUsername)
                    put(NodeFields.RPC_PASSWORD.value, rpcPassphrase)
                    put(NodeFields.RPC_NETWORK.value, AnonConfig.getNetworkType().toString())
                    put(NodeFields.NODE_NAME.value, "anon")
                }
                val node = Node.fromJson(nodeObj)
                updateDaemon(node)
                walletState.setLoading(true)
            }

            walletState.update()
            if (!restoreScanPending && wallet.isSynchronized) {
                wallet.refreshHistory()
            }

            wallet.init(0)

            // Restore mode is only a guard against normal-sync estimates until the
            // shared custom-height reset engine takes ownership of scan progress.
            if (savedRestoreHeight > 0L && savedRestoreHeight != wallet.getRestoreHeight()) {
                wallet.setRestoreHeight(savedRestoreHeight)
                wallet.store()
            }
            if (wallet.isInitialized) {
                if (!restoreScanPending || wallet.nativeSynchronized) {
                    wallet.refreshHistory()
                }
                wallet.setTrustedDaemon(true)
                val effectiveRestoreHeight = if (needsRestoreRescan) {
                    savedRestoreHeight.takeIf { it > 0L }
                        ?: wallet.getRestoreHeight().takeIf { it > 0L }
                } else {
                    null
                }
                if (effectiveRestoreHeight != null && !wallet.nativeSynchronized) {
                    // Mnemonic recovery uses the same sequence as in-wallet reset:
                    // ordinary refresh -> live daemon height -> exact remaining count
                    // -> custom-height rescan. There is no separate fake restore estimate.
                    wallet.setRestoreHeight(effectiveRestoreHeight)
                    wallet.store()

                    val result = walletState.resetSyncFromHeight(effectiveRestoreHeight) {
                        prefs.edit(commit = true) {
                            putBoolean(RESTORE_NEEDS_RESCAN, false)
                            remove(RESTORE_HEIGHT)
                        }
                    }
                    if (result.isFailure) {
                        walletState.abortRestoreProgressForRetry()
                        Timber.tag(TAG).e(
                            result.exceptionOrNull(),
                            "Selected-height restore scan not started: %d",
                            effectiveRestoreHeight
                        )
                        // Keep RESTORE_NEEDS_RESCAN so the next startup can retry.
                    }
                }
                walletState.update()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Wallet service initialization failed")
            if (restoreScanPending) {
                walletState.abortRestoreProgressForRetry()
            } else {
                walletState.setLoading(false)
            }
        }
    }

    fun updateDaemon(node: io.anonero.model.node.Node?) {
        if (secureWipePriorityActive()) return
        walletState.setLoading(true)
        val wallet = WalletManager.instance?.wallet
        val walletManager = WalletManager.instance
        wallet?.pauseRefresh()
        try {
            if (node != null) {
                walletState.updateDaemon(
                    io.anonero.model.node.DaemonInfo(
                        daemon = "${node.host}:${node.rpcPort}",
                        io.anonero.model.Wallet.ConnectionStatus.ConnectionStatus_Disconnected,
                        daemonHeight = 0L
                    )
                )
            } else {
                walletState.updateDaemon(
                    io.anonero.model.node.DaemonInfo(
                        daemon = null,
                        io.anonero.model.Wallet.ConnectionStatus.ConnectionStatus_Disconnected,
                        daemonHeight = 0L
                    )
                )
            }
            walletManager?.setDaemon(node)
            wallet?.setTrustedDaemon(true)
            walletState.setLoading(false)
        } catch (e: Exception) {
            walletState.updateDaemon(
                io.anonero.model.node.DaemonInfo(
                    daemon = null,
                    io.anonero.model.Wallet.ConnectionStatus.ConnectionStatus_Disconnected,
                    daemonHeight = 0L
                )
            )
            Timber.tag(TAG).e(e, "Failed to configure daemon")
            // Match the known-working #1070 behavior: keep the wallet refresh
            // startup path alive, while retaining the real exception in logs.
        }
    }

    fun setProxy(proxy: String?, port: Int?) {
        if (secureWipePriorityActive()) return
        Timber.tag(TAG).d("setProxy %s%s", proxy, port.toString())
        if (proxy == null && port == null) {
            prefs.edit {
                remove(WALLET_PROXY)
                remove(WALLET_PROXY_PORT)
            }
        } else {
            prefs.edit {
                putString(WALLET_PROXY, proxy)
                putInt(WALLET_PROXY_PORT, port ?: -1)
            }
            val proxyHost = prefs.getString(WALLET_PROXY, "")
            val proxyPort = prefs.getInt(WALLET_PROXY_PORT, -1)
            val proxyStr = if (proxyHost?.isNotEmpty() == true && proxyPort != -1) {
                "${proxyHost}:$proxyPort"
            } else {
                return
            }
            WalletManager.instance?.setProxy(proxyStr)
            WalletManager.instance?.wallet?.setProxy(proxyStr)
        }
    }

    fun getProxy(): Pair<String, Int>? {
        val proxyHost = prefs.getString(WALLET_PROXY, "")
        val proxyPort = prefs.getInt(WALLET_PROXY_PORT, -1)

        if (proxyHost?.isNotEmpty() == true && proxyPort != -1) {
            return Pair(proxyHost, proxyPort)
        }
        return null
    }

    fun wipe(passPhrase: String): Boolean {
        check(wipeInProgress.compareAndSet(false, true)) {
            "Secure deletion is already in progress"
        }
        try {
            // Keep the gate active and stop all ordinary wallet activity first.
            prepareForSecureWipeRequest()
            torSocksJob?.cancel()
            torSocksJob = null
            handler = null

            val walletManager = WalletManager.instance
            val wallet = walletManager?.wallet

            // Submit the native cancellation request before pause/close. This call is
            // non-blocking and lets an active scan/refresh unwind before native teardown.
            walletState.cancelNativeWorkForSecureWipe(wallet)

            if (wallet != null && wallet.isInitialized) {
                if (secureWipeWasBackgroundSync.get()) {
                    check(wallet.stopBackgroundSync(passPhrase)) {
                        "Unable to stop background sync; secure deletion will retry"
                    }
                    secureWipeWasBackgroundSync.set(false)
                    walletState.setBackGroundSync(false)
                }

                runCatching { wallet.setListener(null) }
                    .onFailure { Timber.tag(TAG).e(it, "Wallet listener detach failed; retrying close") }
                runCatching { wallet.pauseRefresh() }
                    .onFailure { Timber.tag(TAG).e(it, "Wallet pause failed; retrying close") }

                val closed = runCatching { wallet.close() }
                    .onFailure { Timber.tag(TAG).e(it, "Native wallet close failed; secure deletion will retry") }
                    .getOrDefault(false)
                check(closed || !wallet.isInitialized) {
                    "Native wallet could not be closed; secure deletion must retry"
                }
            }

            runCatching { walletManager?.setDaemon(null) }
                .onFailure { Timber.tag(TAG).e(it, "Daemon detach failed; continuing cleanup") }
            runCatching { torService.stop() }
                .onFailure { Timber.tag(TAG).e(it, "Tor stop failed; continuing cleanup") }

            // Reset the native singleton only after the wallet handle has closed.
            WalletManager.resetInstance()

            val appContext = AnonConfig.context?.applicationContext
                ?: throw IllegalStateException("Application context unavailable")
            val walletDir = File(appContext.filesDir, "wallets")
            repeat(20) {
                if (!walletDir.exists()) return@repeat
                if (!walletDir.deleteRecursively()) Thread.sleep(100)
            }
            check(!walletDir.exists()) {
                "Wallet files remain after secure-delete attempt"
            }
            return true
        } finally {
            wipeInProgress.set(false)
        }
    }
}