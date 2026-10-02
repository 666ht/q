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
        val context = AnonConfig.context ?: throw InvalidPin()
        val routedFile = AnonConfig.getWalletFileForPin(context, pin)
        val legacyFile = AnonConfig.getDefaultWalletFile(context)

        // Prefer the PIN-routed wallet. Keep the existing "anon" wallet as a
        // backward-compatible fallback so current installations continue to open.
        val walletFile = when {
            routedFile.exists() -> routedFile
            legacyFile.exists() -> legacyFile
            else -> routedFile
        }

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
        handler = MoneroHandlerThread(
            wallet,
            walletState
        )

        wallet.setListener(handler)
        wallet.refreshHistory()
        handler?.start()
        walletState.setLoading(true)
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
            if (wallet.isSynchronized) {
                wallet.refreshHistory()
            }
            wallet.init(0)
            val savedRestoreHeight = prefs.getLong(RESTORE_HEIGHT, 0L)
            val needsRestoreRescan = prefs.getBoolean(RESTORE_NEEDS_RESCAN, false)
            if (savedRestoreHeight > 0L && savedRestoreHeight != wallet.getRestoreHeight()) {
                wallet.setRestoreHeight(savedRestoreHeight)
                wallet.store()
            }
            if (wallet.isInitialized) {
                wallet.refreshHistory()
                wallet.setTrustedDaemon(true)
                val effectiveRestoreHeight = savedRestoreHeight.takeIf { it > 0L }
                    ?: wallet.getRestoreHeight().takeIf { it > 0L }
                if (effectiveRestoreHeight != null && !wallet.nativeSynchronized) {
                    // A stored restore height is the authoritative start of the
                    // restore scan. Do not depend on the one-shot preference flag,
                    // because older wallets/builds can have inconsistent flag state.
                    walletState.beginRestoreProgress(effectiveRestoreHeight)
                } else if (needsRestoreRescan && wallet.nativeSynchronized) {
                    // The restore scan is already finished; consume the one-shot flag
                    // so it cannot re-arm a restore progress cycle on future opens.
                    prefs.edit { putBoolean(RESTORE_NEEDS_RESCAN, false) }
                }
                wallet.startRefresh()
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
            e.printStackTrace()
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
        val appContext = AnonConfig.context?.applicationContext ?: return false
        val walletManager = WalletManager.instance
        val wallet = walletManager?.wallet

        val openPath = wallet?.getPath()
        val currentWalletFile = openPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: AnonConfig.getDefaultWalletFile(appContext)

        val pinWalletFile = AnonConfig.getWalletFileForPin(appContext, passPhrase)

        // The PIN is the native wallet password. Prefer direct credential
        // verification so legacy "anon" wallets also work, not only wallets
        // whose filename was routed from the PIN.
        val pinCandidates = buildList {
            add(currentWalletFile.absolutePath)
            add(currentWalletFile.absolutePath + ".keys")
            add(pinWalletFile.absolutePath)
            add(pinWalletFile.absolutePath + ".keys")
        }.distinct()

        val isPin = pinCandidates.any { candidate ->
            runCatching {
                WalletManager.instance?.verifyWalletPassword(candidate, passPhrase, AnonConfig.viewOnly)
            }.getOrDefault(false)
        } || AnonConfig.isWalletPin(currentWalletFile, passPhrase)

        val isPassphrase = !isPin &&
            AnonConfig.isWalletPassphrase(appContext, currentWalletFile, passPhrase)

        // PIN: delete only the currently opened wallet's files.
        // Password phrase: authenticated full wipe, including all app data.
        if (!isPin && !isPassphrase) {
            Timber.tag(TAG).w("Safe delete rejected: invalid credential")
            return false
        }

        walletState.prepareForWipe()
        _scope.coroutineContext.cancelChildren()
        handler = null

        runCatching { wallet?.setListener(null) }
            .onFailure { Timber.tag(TAG).e(it, "Wallet listener detach failed") }
        runCatching { wallet?.pauseRefresh() }
            .onFailure { Timber.tag(TAG).e(it, "Wallet pause failed") }
        runCatching { walletManager?.setDaemon(null) }
            .onFailure { Timber.tag(TAG).e(it, "Daemon detach failed") }
        runCatching { wallet?.close() }
            .onFailure { Timber.tag(TAG).e(it, "Wallet close failed; continuing delete") }
        runCatching { torService.stop() }
            .onFailure { Timber.tag(TAG).e(it, "Tor stop failed; continuing delete") }

        WalletManager.resetInstance()

        return if (isPassphrase) {
            AnonConfig.clearAllAppData(appContext)
        } else {
            var deleted = true
            val walletPath = currentWalletFile.absolutePath
            val walletDir = currentWalletFile.parentFile
            val walletName = currentWalletFile.name

            if (walletDir != null) {
                // Monero stores the wallet across several files sharing the
                // same base name (keys, address data, sidecars, etc.).
                walletDir.listFiles()
                    ?.filter { file ->
                        file.name == walletName || file.name.startsWith("$walletName.")
                    }
                    ?.forEach { file ->
                        var removed = false
                        repeat(20) {
                            if (!file.exists()) {
                                removed = true
                                return@repeat
                            }
                            if (file.deleteRecursively()) {
                                removed = true
                                return@repeat
                            }
                            Thread.sleep(100)
                        }
                        if (!removed && file.exists()) {
                            deleted = false
                            Timber.tag(TAG).e("Failed to delete wallet file: %s", file.absolutePath)
                        }
                    }
            }

            AnonConfig.disposeState()
            deleted
        }
    }


}