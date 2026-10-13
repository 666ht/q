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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.io.File
import timber.log.Timber
import androidx.core.content.edit


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

    val scope get() = _scope

    init {
        scope.launch {
            torService.socksFlow.collect {
                if (walletState.isWiping()) return@collect
                val wallet = WalletManager.instance?.wallet
                if (prefs.getBoolean(WALLET_USE_TOR, false) && wallet?.isInitialized == true) {
                    setProxy(it.address.toString(), it.port.value)
                }
            }
        }
    }

    fun openWallet(pin: String): Boolean {
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
        if (restoreScanPending) {
            walletState.beginRestoreProgress(savedRestoreHeight)
            // Arm the same preparation gate used by the in-wallet reset before the
            // listener is attached, so startup callbacks cannot publish the daemon's
            // full-chain estimate or switch through a separate restore progress phase.
            walletState.customRescanPreparing = true
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
                while (torService.socks == null) {
                    delay(200)
                }
                val socket = torService.socks
                WalletManager.instance?.setProxy("${socket?.value}")
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
                    // A stored restore height is the authoritative start of the
                    // restore scan. Do not reset an already-armed restore window,
                    // because that would erase the live daemon target captured
                    // immediately after wallet.init(0).
                    if (!walletState.restoreProgressInProgress ||
                        walletState.restoreProgressStartHeight != effectiveRestoreHeight) {
                        walletState.beginRestoreProgress(effectiveRestoreHeight)
                    }
                } else if (needsRestoreRescan && wallet.nativeSynchronized) {
                    // Native wallet state confirms the previous restore already
                    // finished. Consume the one-shot instruction immediately.
                    prefs.edit(commit = true) {
                        putBoolean(RESTORE_NEEDS_RESCAN, false)
                        remove(RESTORE_HEIGHT)
                    }
                    walletState.customRescanPreparing = false
                    walletState.finishRestoreProgress()
                } else if (!needsRestoreRescan && prefs.contains(RESTORE_HEIGHT)) {
                    // Backward-compatible cleanup for stale one-shot height values.
                    prefs.edit { remove(RESTORE_HEIGHT) }
                }
                if (effectiveRestoreHeight != null && !wallet.nativeSynchronized) {
                    // Mnemonic recovery and in-wallet "重置同步区块" share the same
                    // native reset, real cursor polling, remaining-block calculation,
                    // and final history/output refresh. The selected height is the exact
                    // start; the live daemon height is the scan end.
                    wallet.setRestoreHeight(effectiveRestoreHeight)
                    wallet.store()

                    // resetSyncFromHeight requires a connected daemon. Start the refresh
                    // worker only if necessary, then wait for connection state rather
                    // than falling back to a normal full-chain sync estimate.
                    if (wallet.fullStatus.connectionStatus !=
                        io.anonero.model.Wallet.ConnectionStatus.ConnectionStatus_Connected
                    ) {
                        wallet.startRefresh()
                    }
                    val connectDeadline = System.currentTimeMillis() + 120_000L
                    while (
                        wallet.isInitialized &&
                        wallet.fullStatus.connectionStatus !=
                            io.anonero.model.Wallet.ConnectionStatus.ConnectionStatus_Connected &&
                        System.currentTimeMillis() < connectDeadline
                    ) {
                        delay(200)
                    }

                    if (wallet.fullStatus.connectionStatus ==
                        io.anonero.model.Wallet.ConnectionStatus.ConnectionStatus_Connected
                    ) {
                        val result = walletState.resetSyncFromHeight(effectiveRestoreHeight) {
                            prefs.edit(commit = true) {
                                putBoolean(RESTORE_NEEDS_RESCAN, false)
                                remove(RESTORE_HEIGHT)
                            }
                        }
                        if (result.isFailure) {
                            walletState.customRescanPreparing = false
                            Timber.tag(TAG).e(
                                result.exceptionOrNull(),
                                "Shared selected-height restore scan failed: %d",
                                effectiveRestoreHeight
                            )
                            // Keep the one-shot restore request and suppress fake
                            // full-chain progress; do not mark this restore complete.
                        }
                    } else {
                        walletState.customRescanPreparing = false
                        Timber.tag(TAG).e(
                            "Selected-height restore scan not started: daemon did not connect"
                        )
                        // Retain RESTORE_NEEDS_RESCAN so startup can retry this height.
                    }
                }
                walletState.update()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            walletState.setLoading(false)
        }
    }

    fun updateDaemon(node: io.anonero.model.node.Node?) {
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
        if (walletState.isWiping()) return
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
        val walletManager = WalletManager.instance
        val wallet = walletManager?.wallet

        walletState.prepareForWipe()
        _scope.coroutineContext.cancelChildren()
        handler = null

        runCatching { wallet?.setListener(null) }
            .onFailure { Timber.tag(TAG).e(it, "Wallet listener detach failed; continuing secure wipe") }

        runCatching { wallet?.pauseRefresh() }
            .onFailure { Timber.tag(TAG).e(it, "Wallet pause failed; continuing secure wipe") }

        runCatching { walletManager?.setDaemon(null) }
            .onFailure { Timber.tag(TAG).e(it, "Daemon detach failed; continuing secure wipe") }

        var closed = true
        if (wallet != null) {
            closed = runCatching { wallet.close() }
                .onFailure { Timber.tag(TAG).e(it, "Wallet close failed") }
                .getOrDefault(false)
            if (!closed) {
                Timber.tag(TAG).e("Wallet native close returned false; continuing file deletion")
            }
        }
        runCatching { torService.stop() }
            .onFailure { Timber.tag(TAG).e(it, "Tor stop failed; continuing secure wipe") }

        WalletManager.resetInstance()

        val appContext = AnonConfig.context?.applicationContext
        var deleted = true
        if (appContext != null) {
            val walletDir = File(appContext.filesDir, "wallets")
            repeat(20) {
                if (!walletDir.exists()) return@repeat
                if (!walletDir.deleteRecursively()) {
                    Thread.sleep(100)
                }
            }
            deleted = !walletDir.exists()

            repeat(5) {
                if (AnonConfig.clearAllAppData(appContext)) return@repeat
                Thread.sleep(100)
            }
            deleted = deleted && AnonConfig.clearAllAppData(appContext)
        }

        return deleted
    }

}