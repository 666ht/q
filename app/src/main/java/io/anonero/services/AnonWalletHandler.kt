package io.anonero.services

import android.content.SharedPreferences
import io.anonero.AnonConfig
import io.anonero.model.WalletManager
import io.anonero.model.node.Node
import io.anonero.model.node.NodeFields
import org.json.JSONObject
import io.anonero.util.PREFS_PIN_HASH
import io.anonero.util.PREFS_PIN_SETUP_PENDING
import io.anonero.util.PinHash
import io.anonero.util.RESTORE_HEIGHT
import io.anonero.util.WalletKey
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
        // Capture native background-sync mode before the priority gate clears UI state.
        if (walletState.backgroundSync) {
            secureWipeWasBackgroundSync.set(true)
        }
        // Publish the priority gate synchronously, before any IO job is queued.
        secureWipeRequestStartedInProcess.set(true)
        AnonConfig.context?.markSecureWipeStartedInProcess()
        walletState.prepareForWipe()
        // Cancel the native refresh asynchronously right after the gate rises.
        // The request is non-blocking; native teardown remains serialized below.
        scope.launch(Dispatchers.IO) {
            walletState.cancelNativeWorkForSecureWipe(WalletManager.instance?.wallet)
        }
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

    /** Await Tor teardown before secure-delete recovery removes app data. */
    suspend fun stopTorForSecureWipe() {
        torService.stopAndWait()
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
        val context = AnonConfig.context!!.applicationContext
        val walletFile = AnonConfig.getDefaultWalletFile(context)
        val walletName = walletFile.name

        WalletKey.recoverInterruptedMigration(context, walletFile.path, walletName)
        val hasWalletSecret = WalletKey.exists(context, walletName)
        val hasRestoreSource = WalletKey.restoreSourceExists(context, walletName)

        val anonWallet = if (hasWalletSecret || hasRestoreSource) {
            // A restore-source wrapper marks a portable-backup rekey that may have
            // been interrupted by process death. It also acts as the recovery source
            // if the wallet was not yet re-keyed to its device secret.
            val pinSetupPending = prefs.getBoolean(PREFS_PIN_SETUP_PENDING, false) ||
                hasRestoreSource
            val storedPinHash = prefs.getString(PREFS_PIN_HASH, "")
            if (!pinSetupPending && !PinHash.matches(pin, storedPinHash)) throw InvalidPin()
            if (!pinSetupPending && PinHash.isLegacy(storedPinHash)) {
                if (!prefs.edit(commit = true) {
                        putString(PREFS_PIN_HASH, PinHash.hash(pin))
                    }) {
                    throw IllegalStateException("Unable to upgrade stored PIN verifier")
                }
            }

            val manager = WalletManager.instance
                ?: throw IllegalStateException("Wallet manager is unavailable")
            var opened: io.anonero.model.Wallet? = null

            if (hasWalletSecret) {
                val walletPassword = runCatching {
                    WalletKey.load(context, walletName)
                }.getOrNull()
                if (walletPassword != null) {
                    val candidate = runCatching {
                        manager.openWallet(walletFile.path, walletPassword)
                    }.getOrNull()
                    if (candidate?.status?.isOk == true) {
                        opened = candidate
                        if (hasRestoreSource) {
                            WalletKey.removeRestoreSource(context, walletName)
                        }
                    } else {
                        candidate?.let { runCatching { manager.close(it) } }
                        if (!hasRestoreSource) throw InvalidPin()
                        WalletKey.remove(context, walletName)
                    }
                } else if (hasRestoreSource) {
                    WalletKey.remove(context, walletName)
                } else {
                    throw InvalidPin()
                }
            }

            if (opened == null && WalletKey.restoreSourceExists(context, walletName)) {
                val sourcePassword = try {
                    WalletKey.loadRestoreSource(context, walletName)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Portable backup recovery password is unavailable")
                    throw InvalidPin()
                }
                val sourceWallet = manager.openWallet(walletFile.path, sourcePassword)
                try {
                    if (!sourceWallet.status.isOk) {
                        throw InvalidPin()
                    }
                    val walletPassword = if (WalletKey.exists(context, walletName)) {
                        WalletKey.load(context, walletName)
                    } else {
                        WalletKey.newSecret().also {
                            WalletKey.store(context, walletName, it)
                        }
                    }
                    if (!sourceWallet.setPassword(walletPassword) || !sourceWallet.store()) {
                        throw IllegalStateException("Unable to finish portable backup wallet re-key")
                    }
                    WalletKey.removeRestoreSource(context, walletName)
                    opened = sourceWallet
                } catch (e: Exception) {
                    runCatching { manager.close(sourceWallet) }
                    throw e
                }
            }

            val readyWallet = opened ?: throw InvalidPin()
            if (readyWallet.status.isOk && pinSetupPending) {
                // A portable v3 backup is already bound to this device's Keystore.
                // The existing first wallet-open PIN becomes the local PIN; no extra
                // onboarding screen or change to the current PIN UI is required.
                val saved = prefs.edit()
                    .putString(PREFS_PIN_HASH, PinHash.hash(pin))
                    .remove(PREFS_PIN_SETUP_PENDING)
                    .commit()
                if (!saved) {
                    manager.close(readyWallet)
                    throw IllegalStateException("Unable to save the new wallet PIN")
                }
            }
            readyWallet
        } else {
            // Existing installs and older PIN-keyed backups keep opening with their
            // old PIN until the first successful unlock migrates both wallet files.
            val opened = WalletManager.instance?.openWallet(walletFile.path, pin)
            if (opened?.status?.isOk == true) {
                migrateToKeystore(walletFile, walletName, opened, pin)
            }
            opened
        }

        if (anonWallet?.status?.isOk != true) {
            Timber.tag(TAG).e("openWallet error: %s", anonWallet?.status?.errorString)
        }
        return anonWallet?.status?.isOk ?: throw InvalidPin()
    }

    /**
     * Upgrade a live legacy wallet after its PIN was verified by the app lock screen.
     * This is deferred while wallet2 is scanning; cold wallet opens migrate before
     * the service starts and do not need this idle check.
     */
    suspend fun migrateActiveWalletToKeystore(pin: String): Boolean {
        if (secureWipePriorityActive()) return false
        val context = AnonConfig.context?.applicationContext ?: return false
        val walletFile = AnonConfig.getDefaultWalletFile(context)
        val walletName = walletFile.name
        WalletKey.recoverInterruptedMigration(context, walletFile.path, walletName)
        if (WalletKey.exists(context, walletName)) return true
        if (!walletState.waitUntilWalletIdleForKeyMigration()) {
            Timber.tag(TAG).i("Wallet-key migration deferred until wallet2 is idle")
            return false
        }
        if (secureWipePriorityActive()) return false
        val wallet = WalletManager.instance?.wallet ?: return false
        return migrateToKeystore(walletFile, walletName, wallet, pin)
    }

    private fun migrateToKeystore(
        walletFile: File,
        walletName: String,
        wallet: io.anonero.model.Wallet,
        pin: String
    ): Boolean {
        val context = AnonConfig.context!!.applicationContext
        val snapshots = WalletKey.migrationSnapshots(walletFile.path)
            .filter { (_, live) -> live.exists() }
        if (snapshots.none { (_, live) -> live.name.endsWith(".keys") }) {
            Timber.tag(TAG).e("Cannot migrate wallet: wallet keys file is missing")
            return false
        }

        var rekeyed = false
        try {
            snapshots.forEach { (snapshot, live) ->
                val partial = File(snapshot.path + ".tmp")
                live.copyTo(partial, overwrite = true)
                if (!partial.renameTo(snapshot)) throw Exception("Wallet snapshot failed: ${snapshot.name}")
            }

            // Persist the PIN verifier before changing the wallet password. If the
            // process dies after the Keystore wrapper is written, the same PIN remains usable.
            val pinVerifier = PinHash.hash(pin)
            if (!prefs.edit()
                    .putString(PREFS_PIN_HASH, pinVerifier)
                    .remove(PREFS_PIN_SETUP_PENDING)
                    .commit()) {
                throw Exception("PIN verifier was not committed")
            }

            val walletPassword = WalletKey.newSecret()
            rekeyed = true
            if (!wallet.setPassword(walletPassword)) {
                throw Exception("setPassword failed: ${wallet.status.errorString}")
            }
            if (!wallet.store()) {
                throw Exception("Wallet cache could not be persisted after password migration")
            }
            WalletKey.store(context, walletName, walletPassword)
            snapshots.forEach { (snapshot, _) -> snapshot.delete() }
            Timber.tag(TAG).i("Legacy wallet migrated to a Keystore-bound password")
            return true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Keystore wallet migration failed; rolling back")
            WalletKey.remove(context, walletName)

            val restoredInMemory = if (!rekeyed) {
                true
            } else {
                runCatching {
                    wallet.setPassword(pin) && wallet.store()
                }.getOrDefault(false)
            }

            if (restoredInMemory) {
                snapshots.forEach { (snapshot, _) -> snapshot.delete() }
            } else {
                // The live wallet may still hold the random password. Restore both disk
                // files and retain any failed snapshot so startup recovery can retry.
                runCatching {
                    snapshots.forEach { (snapshot, live) ->
                        if (snapshot.exists()) snapshot.copyTo(live, overwrite = true)
                    }
                    snapshots.forEach { (snapshot, _) -> snapshot.delete() }
                }.onFailure { Timber.tag(TAG).e(it, "Unable to restore legacy wallet snapshot") }
            }
            return false
        }
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
        if (secureWipePriorityActive()) return
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

        if (secureWipePriorityActive()) return
        wallet.setListener(handler)
        // A selected-height mnemonic restore must enter the shared reset scan directly.
        // Its finalization refreshes history and outputs; do not refresh history before scanning.
        if (!restoreScanPending) {
            if (secureWipePriorityActive()) return
            wallet.refreshHistory()
        }
        if (secureWipePriorityActive()) return
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
                if (secureWipePriorityActive()) return
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

            if (secureWipePriorityActive()) return
            walletState.update()
            if (secureWipePriorityActive()) return
            wallet.init(0)

            // Restore mode is only a guard against normal-sync estimates until the
            // shared custom-height reset engine takes ownership of scan progress.
            if (savedRestoreHeight > 0L && savedRestoreHeight != wallet.getRestoreHeight()) {
                wallet.setRestoreHeight(savedRestoreHeight)
                wallet.store()
            }
            if (wallet.isInitialized && !secureWipePriorityActive()) {
                if (!restoreScanPending || wallet.nativeSynchronized) {
                    if (secureWipePriorityActive()) return
                    wallet.refreshHistory()
                }
                if (secureWipePriorityActive()) return
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
        if (secureWipePriorityActive()) return
        wallet?.takeIf { it.isInitialized }?.pauseRefresh()
        if (secureWipePriorityActive()) return
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
            if (secureWipePriorityActive()) return
            walletManager?.setDaemon(node)
            if (secureWipePriorityActive()) return
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
            // Keep the wallet refresh startup path alive; retain the exception in logs.
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
            if (secureWipePriorityActive()) return
            WalletManager.instance?.setProxy(proxyStr)
            if (secureWipePriorityActive()) return
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

    suspend fun wipe(passPhrase: String): Boolean {
        check(wipeInProgress.compareAndSet(false, true)) {
            "Secure deletion is already in progress"
        }
        try {
            // Keep the gate active and stop all ordinary wallet activity first.
            prepareForSecureWipeRequest()
            torSocksJob?.cancel()
            torSocksJob = null
            handler = null

            // Do not close/delete wallet state or app directories until Tor has
            // actually stopped. stop() alone merely queues an asynchronous action.
            torService.stopAndWait()

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