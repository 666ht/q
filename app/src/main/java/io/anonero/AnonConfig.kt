package io.anonero

import android.content.Context
import io.anonero.model.NetworkType
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import kotlin.math.pow

val jsonDecoder = Json { ignoreUnknownKeys = true }

object AnonConfig {

    const val NERO_KEY_PAYLOAD_VERSION = 1
    const val XMR_DECIMALS = 12
    val ONE_XMR = Math.round(10.0.pow(XMR_DECIMALS.toDouble()))
    const val MAX_LOG_SIZE = 250
    const val PREFS = "anonPref"
    var context: AnonApplication? = null
    private var walletFound: Boolean = false

    const val EXPORT_OUTPUT_FILE = "export_wallet_outputs.out"
    const val IMPORT_OUTPUT_FILE = "import_wallet_outputs"

    const val EXPORT_KEY_IMAGE_FILE = "export_key_images.imgs"
    const val IMPORT_KEY_IMAGE_FILE = "import_key_images"

    const val EXPORT_UNSIGNED_TX_FILE = "export_unsigned_tx.utx"
    const val IMPORT_UNSIGNED_TX_FILE = "import_unsigned_tx"

    const val EXPORT_SIGNED_TX_FILE = "export_signed_tx.stx"
    const val IMPORT_SIGNED_TX_FILE = "import_signed_tx"

    fun getNetworkType(): NetworkType {
        if (BuildConfig.APPLICATION_ID.lowercase().contains("stagenet")) {
            return NetworkType.NetworkType_Stagenet
        }
        return NetworkType.NetworkType_Mainnet
    }

    val viewOnly: Boolean get() = BuildConfig.VIEW_ONLY

    fun getDefaultWalletFile(context: Context): File {
        return File(getDefaultWalletDir(context), "anon")
    }

    /**
     * Returns the wallet file assigned to a PIN.
     *
     * The legacy wallet remains at "anon" for backward compatibility. New
     * PIN-routed wallets use a deterministic, non-readable filename derived
     * from the PIN rather than storing the PIN itself on disk.
     */
    fun getWalletFileForPin(context: Context, pin: String): File {
        val normalized = pin.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized)
        val id = digest.joinToString("") { "%02x".format(it) }.take(32)
        return File(getDefaultWalletDir(context), "wallet_$id")
    }

    fun getTorConfig(scope: CoroutineScope): TorRuntime.Environment {
        val torDir = File(context?.filesDir, "tor")
        torDir.mkdirs()
        val cacheDir = File(context?.cacheDir, "tor")
        torDir.mkdirs()
        return TorRuntime.Environment.Builder(
            workDirectory = torDir,
            cacheDirectory = cacheDir,
            loader = ResourceLoaderTorExec::getOrCreate,
        )
    }

    fun isWalletFileExist(): Boolean {
        return walletFound
    }

    fun getLogFile(context: Context): File {
        val logDir = File(context.applicationContext.cacheDir, "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        val logFile = File(logDir, "anon_log")
        if (!logFile.exists()) {
            logFile.createNewFile()
        }
        return logFile
    }

    fun getDefaultWalletDir(context: Context): File {
        val walletDir = File(context.applicationContext.filesDir, "wallets")
        if (!walletDir.exists()) {
            walletDir.mkdirs()
        }
        return walletDir
    }

    fun initWalletState() {
        MainScope().launch(Dispatchers.IO) {
            walletFound = getDefaultWalletFile(context!!).exists()
        }
    }

    fun clearSpendCacheFiles(context: Context) {
        val files = arrayListOf(
            EXPORT_OUTPUT_FILE,
            IMPORT_OUTPUT_FILE,

            EXPORT_KEY_IMAGE_FILE,
            IMPORT_KEY_IMAGE_FILE,

            EXPORT_UNSIGNED_TX_FILE,
            IMPORT_UNSIGNED_TX_FILE,

            EXPORT_SIGNED_TX_FILE,
            IMPORT_SIGNED_TX_FILE
        )
        context.cacheDir.listFiles()?.forEach { file ->
            if (files.any { file.name.contains(it) }) {
                file.delete()
            }
        }
    }

    /**
     * Removes all app-owned persistent data that can contain wallet material,
     * preferences, logs, caches, databases, or exported wallet artifacts.
     *
     * The app data root itself is not removed; only its data-bearing children
     * are deleted so the running process can finish the wipe flow safely.
     */
    fun clearAllAppData(context: Context): Boolean {
        val app = context.applicationContext

        // Wipe every child of the app data root, including files/directories
        // that are not part of the known wallet paths.
        val dataRoot = File(app.applicationInfo.dataDir)
        var success = deleteContents(dataRoot)

        app.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
            if (!deleteContents(dir)) success = false
        }
        app.externalCacheDirs.filterNotNull().forEach { dir ->
            if (!deleteContents(dir)) success = false
        }

        walletFound = false
        return success
    }

    private fun deleteContents(directory: File): Boolean {
        if (!directory.exists()) return true
        val children = directory.listFiles() ?: return true
        var success = true
        children.forEach { child ->
            if (!child.deleteRecursively()) {
                success = false
            }
        }
        return success && directory.listFiles().isNullOrEmpty()
    }

    fun disposeState() {
        walletFound = false
    }
}