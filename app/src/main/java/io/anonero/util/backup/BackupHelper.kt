package io.anonero.util.backup

import android.content.Context
import android.content.SharedPreferences
import android.icu.text.SimpleDateFormat
import androidx.core.content.edit
import androidx.core.net.toUri
import io.anonero.AnonConfig
import io.anonero.R
import io.anonero.di.provideWalletSharedPrefs
import io.anonero.model.Backup
import io.anonero.model.BackupMeta
import io.anonero.model.BackupPayload
import io.anonero.model.NeroKeyPayload
import io.anonero.model.NodeBackup
import io.anonero.model.WalletBackup
import io.anonero.model.WalletManager
import io.anonero.model.node.Node
import io.anonero.model.node.NodeFields
import io.anonero.store.NodesRepository
import io.anonero.util.KeyStoreHelper
import io.anonero.util.PREFS_PASSPHRASE_HASH
import io.anonero.util.PREFS_PIN_HASH
import io.anonero.util.PREFS_PIN_SETUP_PENDING
import io.anonero.util.WalletKey
import io.anonero.util.RESTORE_HEIGHT
import io.anonero.util.WALLET_PREFERENCES
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.qualifier.named
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class NetworkMismatchException : Exception()
object BackupHelper {
    private const val BACKUP_VERSION = 3
    private const val BACKUP_VERSION_PIN_KEYED = 2
    const val BUFFER = 2048
    private const val TAG = "BackUpHelper"
    val walletPrefs: SharedPreferences by inject(
        SharedPreferences::class.java, named(WALLET_PREFERENCES)
    )
    val nodeRepository: NodesRepository by inject(NodesRepository::class.java)

    suspend fun createBackUp(seedPassphrase: String, context: Context): String {
        val wallet = WalletManager.instance?.wallet ?: throw Exception("Wallet not found")
        val prefs = provideWalletSharedPrefs(context)


        val walletPayload = WalletBackup(
            address = wallet.address,
            seed = wallet.getSeed(seedPassphrase),
            restoreHeight = wallet.getRestoreHeight(),
            balanceAll = wallet.getBalanceAll(),
            numSubaddresses = wallet.numSubAddresses,
            numAccounts = wallet.getNumAccounts(),
            primaryAddress = wallet.address,
            isWatchOnly = wallet.isWatchOnly(),
            isSynchronized = wallet.isSynchronized,
            neroPayload = NeroKeyPayload.fromWallet(wallet),
        );


        val nodeBackup = NodeBackup(
            host = prefs.getString(NodeFields.RPC_HOST.value, "") ?: "",
            password = prefs.getString(NodeFields.RPC_PASSWORD.value, "") ?: "",
            username = prefs.getString(NodeFields.RPC_USERNAME.value, "") ?: "",
            rpcPort = prefs.getInt(NodeFields.RPC_PORT.value, Node.defaultRpcPort),
            networkType = AnonConfig.getNetworkType().toString(),
            isOnion = false
        )
        val backup = Backup(
            meta = BackupMeta(
                timestamp = System.currentTimeMillis(),
                network = AnonConfig.getNetworkType().toStringForBackUp(),
            ), node = nodeBackup, wallet = walletPayload,
            nodes = nodeRepository.getAll()
        )

        val walletName = AnonConfig.getDefaultWalletFile(context).name
        val deviceWalletSecret = if (WalletKey.exists(context, walletName)) {
            WalletKey.load(context, walletName)
        } else {
            null
        }
        val backUpPayload = BackupPayload(
            version = if (deviceWalletSecret != null) BACKUP_VERSION else BACKUP_VERSION_PIN_KEYED,
            backup = backup
        )
        val json = Json.encodeToString(backUpPayload)

        context.cacheDir.deleteRecursively()

        val tmpBackupDir = File(context.cacheDir, "tmp_backup")
        if (tmpBackupDir.exists()) {
            tmpBackupDir.deleteRecursively()
        }
        val date = Date()
        val sdf = SimpleDateFormat("dd_MM_yyyy' 'HH_mm_a", Locale.getDefault())
        val timeStamp: String = sdf.format(date)
        val backupFile = File(context.cacheDir, "${R.string.app_name}_backup_$timeStamp.zip")
        tmpBackupDir.mkdirs()
        val tmpBackupFile = File(tmpBackupDir, "anon.json")
        tmpBackupFile.writeText(json)
        val walletDir = File(context.filesDir, "wallets")
        val backupFileEncrypted: File
        try {
            // Keep the live wallet stable while its portable copy is prepared.
            wallet.pauseRefresh()
            try {
                if (!wallet.store()) {
                    throw IllegalStateException("Live wallet could not be stored before backup")
                }
                walletDir.copyRecursively(tmpBackupDir, true)
                if (deviceWalletSecret != null) {
                    val manager = WalletManager.instance
                        ?: throw IllegalStateException("Wallet manager is unavailable")
                    val copy = manager.openWalletUnmanaged(
                        File(tmpBackupDir, walletName).path,
                        deviceWalletSecret
                    )
                    try {
                        if (!copy.status.isOk) {
                            throw IllegalStateException(
                                "Backup copy did not open: ${copy.status.errorString}"
                            )
                        }
                        if (!copy.setPassword(seedPassphrase)) {
                            throw IllegalStateException(
                                "Backup copy could not be re-keyed: ${copy.status.errorString}"
                            )
                        }
                        if (!copy.store()) {
                            throw IllegalStateException("Portable backup wallet copy could not be stored")
                        }
                    } finally {
                        manager.closeUnmanaged(copy)
                    }
                }
            } finally {
                if (wallet.isInitialized) wallet.startRefresh()
            }

            val list = tmpBackupDir.listFiles()
            val files = list?.map { it.absolutePath }?.toTypedArray()
            val backupCacheDir = File(context.cacheDir, "backup")
            if (!backupCacheDir.exists()) {
                backupCacheDir.mkdirs()
            }
            backupFileEncrypted =
                File(backupCacheDir, "${R.string.app_name}_backup_$timeStamp.anon")

            if (files != null) {
                zip(files, backupFile.absolutePath)
                EncryptUtil.encryptFile(seedPassphrase, backupFile, backupFileEncrypted)
            }
            return backupFileEncrypted.absolutePath
        } finally {
            tmpBackupDir.deleteRecursively()
            backupFile.delete()
        }
    }

    fun testBackUp(destinationDir: File): Boolean {
        val items = destinationDir.listFiles()?.toList()?.filter {
            (it.name.endsWith(".keys") || it.name.endsWith(".json"))
        }
        return items?.size == 2
    }

    private fun zip(_files: Array<String>, zipFileName: String?) {
        try {
            var origin: BufferedInputStream?
            val dest = FileOutputStream(zipFileName)
            val out = ZipOutputStream(
                BufferedOutputStream(
                    dest
                )
            )
            val data = ByteArray(BUFFER)
            for (i in _files.indices) {
                val fi = FileInputStream(_files[i])
                origin = BufferedInputStream(fi, BUFFER)
                val entry = ZipEntry(_files[i].substring(_files[i].lastIndexOf("/") + 1))
                out.putNextEntry(entry)
                var count: Int
                while (origin.read(data, 0, BUFFER).also { count = it } != -1) {
                    out.write(data, 0, count)
                }
                origin.close()
            }
            out.close()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e)
            Timber.tag(TAG).e(e)
        }
    }

    fun unZip(toUnzip: File, destinationDir: File) {
        destinationDir.apply {
            if (!exists()) {
                mkdirs()
            }
        }
        ZipFile(toUnzip).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                zip.getInputStream(entry).use { input ->
                    val filePath = "${destinationDir}${File.separator}${entry.name}"
                    if (!entry.isDirectory) {
                        input.copyTo(File(filePath).apply { createNewFile() }.outputStream())
                    } else {
                        val dir = File(filePath)
                        dir.mkdir()
                    }
                }
            }
        }
    }

    private fun bindRestoredWallet(walletFile: File, passPhrase: String): Boolean {
        val context = AnonConfig.context ?: return false
        val walletName = walletFile.name
        val manager = WalletManager.instance ?: return false
        var opened: io.anonero.model.Wallet? = null
        var rekeyed = false
        try {
            val restoredWallet = manager.openWallet(walletFile.path, passPhrase)
            opened = restoredWallet
            if (!restoredWallet.status.isOk) {
                throw IllegalStateException(
                    "Portable backup wallet did not open: ${restoredWallet.status.errorString}"
                )
            }

            val deviceSecret = WalletKey.newSecret()
            if (!restoredWallet.setPassword(deviceSecret)) {
                throw IllegalStateException(
                    "Restored wallet could not be bound to this device: ${restoredWallet.status.errorString}"
                )
            }
            rekeyed = true
            if (!restoredWallet.store()) {
                throw IllegalStateException("Restored wallet cache could not be persisted")
            }
            WalletKey.store(context, walletName, deviceSecret)

            val bound = walletPrefs.edit()
                .putBoolean(PREFS_PIN_SETUP_PENDING, true)
                .remove(PREFS_PIN_HASH)
                .commit()
            if (!bound) throw IllegalStateException("Unable to save restored wallet PIN state")
            return true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "restore: failed to bind wallet to device")
            WalletKey.remove(context, walletName)
            val walletToRollback = opened
            if (rekeyed && walletToRollback != null) {
                runCatching {
                    if (!walletToRollback.setPassword(passPhrase) || !walletToRollback.store()) {
                        throw IllegalStateException("Restored wallet password rollback did not persist")
                    }
                }.onFailure {
                    Timber.tag(TAG).e(it, "Unable to roll back portable backup wallet password")
                }
            }
            return false
        } finally {
            opened?.let {
                runCatching { manager.close(it) }
                    .onFailure { error -> Timber.tag(TAG).e(error, "Unable to close restored wallet") }
            }
        }
    }

    fun extractBackUp(backupPath: String, passPhrase: String): BackupPayload? {
        val context = AnonConfig.context
            ?: throw IllegalStateException("Application context is unavailable")
        val destFile = File(context.cacheDir, "backup.anon")
        val decryptedDestFile = File(context.cacheDir, "backup.zip")
        val extractDestination = File(context.cacheDir, "tmp_extract")

        try {
            val input = context.contentResolver.openInputStream(backupPath.toUri())
                ?: throw IllegalStateException("Unable to open backup file")
            input.use { source ->
                destFile.outputStream().use { target -> source.copyTo(target) }
            }

            EncryptUtil.decryptFile(passPhrase, destFile, decryptedDestFile)
            unZip(decryptedDestFile, extractDestination)

            val anonJson = File(extractDestination, "anon.json")
            val json = anonJson.readText()
            val backup = Json.decodeFromString<BackupPayload>(json)
            if (backup.backup.meta.network != AnonConfig.getNetworkType().toStringForBackUp()) {
                throw NetworkMismatchException()
            }
            return backup
        } catch (e: Exception) {
            Timber.tag(TAG).e(e)
            throw e
        } finally {
            // The decrypted archive and the encrypted staging copy are no longer needed.
            destFile.delete()
            decryptedDestFile.delete()
        }
    }

    suspend fun restoreBackUp(backupPayload: BackupPayload, passPhrase: String): Boolean {
        try {

            val extractDestination = File(AnonConfig.context?.cacheDir, "tmp_extract")
            if (!extractDestination.exists() && !testBackUp(extractDestination)) {
                return false
            }

            val anonDir = AnonConfig.getDefaultWalletDir(AnonConfig.context!!)

            val v1Node = backupPayload.backup.node
            walletPrefs.edit(commit = true) {
                putString(NodeFields.RPC_HOST.value, v1Node.host)
                putInt(NodeFields.RPC_PORT.value, v1Node.rpcPort)
                putString(NodeFields.RPC_USERNAME.value, v1Node.username)
                putString(NodeFields.RPC_PASSWORD.value, v1Node.password)
                putString(NodeFields.RPC_NETWORK.value, v1Node.networkType)
            }

            val wallet = backupPayload.backup.wallet
            val nodes = backupPayload.backup.nodes;

            nodes.forEach {
                nodeRepository.addItem(it)
            }
            if (nodes.isEmpty() && v1Node.host.isNotEmpty()) {
                val node = Node().apply {
                    host = v1Node.host
                    rpcPort = v1Node.rpcPort
                    username = v1Node.username
                    password = v1Node.password
                    networkType = AnonConfig.getNetworkType()
                }
                nodes.forEach {
                    nodeRepository.addItem(node)
                }
            }
            walletPrefs.edit(commit = true) {
                putString(
                    PREFS_PASSPHRASE_HASH,
                    KeyStoreHelper.getCrazyPass(AnonConfig.context, passPhrase)
                )
                putLong(RESTORE_HEIGHT, wallet.restoreHeight ?: 0L)
            }
            extractDestination.listFiles()?.forEach { entry ->
                if (!entry.isDirectory && !entry.name.contains("anon.json")) {
                    entry.copyTo(File(anonDir, entry.name), true)
                }
            }

            walletPrefs.edit(commit = true) {
                remove(PREFS_PIN_HASH)
                remove(PREFS_PIN_SETUP_PENDING)
            }

            if (backupPayload.version >= BACKUP_VERSION) {
                val walletFile = AnonConfig.getDefaultWalletFile(AnonConfig.context!!)
                if (bindRestoredWallet(walletFile, passPhrase)) return true
                anonDir.deleteRecursively()
                return false
            }
            // Version 2 and older backups are still encrypted with the original wallet
            // PIN. The first successful old-PIN unlock will migrate them to Keystore.
            return true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e)
            return false
        }
    }

    fun cleanCacheDir() {
        AnonConfig.context?.cacheDir?.deleteRecursively()
    }
}