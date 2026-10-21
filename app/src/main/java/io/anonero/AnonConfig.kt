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
import java.io.FileOutputStream
import kotlin.math.pow

val jsonDecoder = Json { ignoreUnknownKeys = true }

object AnonConfig {

    const val NERO_KEY_PAYLOAD_VERSION = 1
    const val XMR_DECIMALS = 12
    val ONE_XMR = Math.round(10.0.pow(XMR_DECIMALS.toDouble()))
    const val MAX_LOG_SIZE = 250
    const val PREFS = "anonPref"
    const val APP_PREFERENCES = "anonero_prefs"
    const val PREF_WALLET_WIPE_PENDING = "wallet_wipe_pending"
    const val PREF_WALLET_WIPE_REQUESTED = "wallet_wipe_requested"
    private const val SECURE_WIPE_MARKER_FILE = ".secure_wipe_pending"
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
        val walletDir = getDefaultWalletDir(context)
        val anonWallet = File(walletDir, "anon")
        return anonWallet
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

    /**
     * A delete request is persisted in preferences and in a dedicated file at the
     * app-data root. The marker file is intentionally preserved until the final
     * full-data deletion pass has removed every other app-owned file.
     */
    fun markSecureWipePending(context: Context): Boolean {
        val app = context.applicationContext
        val prefsSaved = app.getSharedPreferences(
            APP_PREFERENCES,
            Context.MODE_PRIVATE
        ).edit()
            .putBoolean(PREF_WALLET_WIPE_REQUESTED, true)
            .putBoolean(PREF_WALLET_WIPE_PENDING, true)
            .commit()

        val marker = File(File(app.applicationInfo.dataDir), SECURE_WIPE_MARKER_FILE)
        val markerReady = runCatching {
            if (marker.exists() && !marker.isFile) {
                check(marker.deleteRecursively()) {
                    "Unable to replace malformed secure-delete marker"
                }
            }
            if (!marker.isFile || marker.length() == 0L) {
                FileOutputStream(marker, false).use { output ->
                    output.write("secure-wipe-pending".toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
            }
            marker.isFile && marker.length() > 0L
        }.getOrDefault(false)
        return prefsSaved && markerReady
    }

    fun isSecureWipePending(context: Context): Boolean {
        val app = context.applicationContext
        val marker = File(File(app.applicationInfo.dataDir), SECURE_WIPE_MARKER_FILE)
        if (marker.isFile) return true
        return runCatching {
            val preferences = app.getSharedPreferences(APP_PREFERENCES, Context.MODE_PRIVATE)
            preferences.getBoolean(PREF_WALLET_WIPE_PENDING, false) ||
                preferences.getBoolean(PREF_WALLET_WIPE_REQUESTED, false)
        }.getOrDefault(false)
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
     * Clears the complete application data set. When secure deletion is active,
     * the root-level recovery marker is kept while every other private and
     * app-specific external file is deleted. It is removed only as the final
     * deletion operation, so an interrupted cleanup can still resume.
     */
    fun clearAllAppData(context: Context): Boolean {
        val app = context.applicationContext
        val dataRoot = File(app.applicationInfo.dataDir)
        val marker = File(dataRoot, SECURE_WIPE_MARKER_FILE)
        val preserveMarker = marker.isFile
        var success = true

        val children = dataRoot.listFiles()
        if (children == null) {
            success = false
        } else {
            children.forEach { child ->
                if (preserveMarker && child.absoluteFile == marker.absoluteFile) {
                    return@forEach
                }
                if (!child.deleteRecursively()) success = false
            }
        }

        val externalDirs = (
            app.getExternalFilesDirs(null).filterNotNull() +
                app.externalCacheDirs.filterNotNull() +
                app.externalMediaDirs.filterNotNull() +
                app.obbDirs.filterNotNull()
            ).distinctBy { it.absolutePath }

        externalDirs.forEach { directory ->
            if (!deleteContents(directory)) success = false
        }

        val otherPrivateFilesRemain = dataRoot.listFiles()
            ?.any { !(preserveMarker && it.absoluteFile == marker.absoluteFile) }
            ?: true
        val externalDataRemains = externalDirs.any { directory ->
            directory.exists() && directory.listFiles()?.isNotEmpty() != false
        }
        if (!success || otherPrivateFilesRemain || externalDataRemains) {
            return false
        }

        // No wallet, preference, cache, database, log, keystore metadata, or
        // external app file remains. Remove the recovery marker last.
        if (preserveMarker && marker.exists() && !marker.delete()) {
            return false
        }

        walletFound = false
        return dataRoot.listFiles()?.isEmpty() == true &&
            externalDirs.all { !it.exists() || it.listFiles()?.isEmpty() == true }
    }

    private fun deleteContents(directory: File): Boolean {
        if (!directory.exists()) return true
        val children = directory.listFiles() ?: return false
        var success = true
        children.forEach { child ->
            if (!child.deleteRecursively()) success = false
        }
        return success && directory.listFiles()?.isEmpty() == true
    }

    fun disposeState() {
        walletFound = false
    }
}