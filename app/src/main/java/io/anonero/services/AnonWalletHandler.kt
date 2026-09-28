package io.anonero.services

import android.content.SharedPreferences
import android.util.Log
import io.anonero.AnonConfig
import io.anonero.model.Wallet
import io.anonero.model.WalletManager
import io.anonero.model.node.DaemonInfo
import io.anonero.model.node.Node
import io.anonero.model.node.NodeFields
import io.anonero.util.RESTORE_HEIGHT
import io.anonero.util.WALLET_PROXY
import io.anonero.util.WALLET_PROXY_PORT
import io.anonero.util.WALLET_USE_TOR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.json.JSONObject
import timber.log.Timber
import androidx.core.content.edit


class InvalidPin : Exception("invalid pin")

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
                torService.start();
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
                val nodeObj = JSONObject()
                    .apply {
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
            if(wallet.isSynchronized) {
                wallet.refreshHistory()
            }
            wallet.init(0)
            if(prefs.getLong(RESTORE_HEIGHT, 0L)!=0L) {
                wallet.setRestoreHeight(prefs.getLong(RESTORE_HEIGHT, 0L));
            }
            if (wallet.isInitialized) {
                wallet.refreshHistory()
                wallet.setTrustedDaemon(true)
                wallet.startRefresh()
                walletState.update()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            walletState.setLoading(false)
        }

    }

    fun updateDaemon(node: Node?) {
        walletState.setLoading(true)
        val wallet = WalletManager.instance?.wallet
        val walletManager = WalletManager.instance
        wallet?.pauseRefresh()
        try {
            if (node != null) {
                walletState.updateDaemon(
                    DaemonInfo(
                        daemon = "${node.host}:${node.rpcPort}",
                        Wallet.ConnectionStatus.ConnectionStatus_Disconnected,
                        daemonHeight = 0L
                    )
                )
            } else {
                walletState.updateDaemon(
                    DaemonInfo(
                        daemon = null,
                        Wallet.ConnectionStatus.ConnectionStatus_Disconnected,
                        daemonHeight = 0L
                    )
                )
            }
            walletManager?.setDaemon(node)
            wallet?.setTrustedDaemon(true)
            walletState.setLoading(false)
        } catch (e: Exception) {
            walletState.updateDaemon(
                DaemonInfo(
                    daemon = null,
                    Wallet.ConnectionStatus.ConnectionStatus_Disconnected,
                    daemonHeight = 0L
                )
            )
            e.printStackTrace()
        }

    }

    fun setProxy(proxy: String?, port: Int?) {
        Timber.tag(TAG).d("setProxy %s%s", proxy, port.toString())
        //disable proxy
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

        runCatching { wallet?.stopBackgroundSync(passPhrase) }
            .onFailure { Timber.tag(TAG).e(it, "Wallet background sync stop failed; continuing secure wipe") }

        // Stop all WalletState refresh jobs before the native wallet is closed.
        walletState.prepareForWipe()

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
        handler = null

        // Drop the Kotlin manager reference before touching the wallet files on disk.
        WalletManager.resetInstance()

        val walletDir = AnonConfig.context?.let { AnonConfig.getDefaultWalletDir(it) }
        var deleted = walletDir?.let { !it.exists() } ?: true
        if (walletDir != null) {
            repeat(5) {
                if (deleted) return@repeat
                deleted = walletDir.deleteRecursively()
                if (!deleted) {
                    Thread.sleep(100)
                }
            }
            if (!deleted && walletDir.exists()) {
                Timber.tag(TAG).e("Wallet directory still exists after secure wipe: %s", walletDir)
            }
        }

        // The animation flow must continue even if native close reports failure.
        // A successful wipe is determined by the wallet files actually being gone.
        return deleted
    }

}
