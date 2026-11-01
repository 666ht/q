package io.anonero.util

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.SecureRandom
import timber.log.Timber

/**
 * Wallet2 uses a random 256-bit password. Android Keystore wraps that password
 * with an AES-GCM key that is not exportable from this device.
 */
object WalletKey {
    private const val SECRET_BYTES = 32
    private const val TAG = "WalletKey"

    fun newSecret(): String =
        Base64.encodeToString(
            ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) },
            Base64.NO_WRAP,
        )

    fun store(context: Context, wallet: String, secret: String) =
        KeyStoreHelper.saveWalletSecret(context, wallet, secret)

    fun load(context: Context, wallet: String): String =
        KeyStoreHelper.loadWalletSecret(context, wallet)

    fun exists(context: Context, wallet: String): Boolean =
        KeyStoreHelper.walletSecretExists(context, wallet)

    fun remove(context: Context, wallet: String) =
        KeyStoreHelper.removeWalletSecret(context, wallet)

    private fun restoreSourceName(wallet: String) = "restore-source-$wallet"

    /**
     * Temporarily keeps the portable backup's wallet password wrapped by Android
     * Keystore while the restored wallet is re-keyed to its device-bound secret.
     * It is removed immediately after the new wallet password has been persisted.
     */
    fun storeRestoreSource(context: Context, wallet: String, password: String) =
        KeyStoreHelper.saveWalletSecret(context, restoreSourceName(wallet), password)

    fun loadRestoreSource(context: Context, wallet: String): String =
        KeyStoreHelper.loadWalletSecret(context, restoreSourceName(wallet))

    fun restoreSourceExists(context: Context, wallet: String): Boolean =
        KeyStoreHelper.walletSecretExists(context, restoreSourceName(wallet))

    fun removeRestoreSource(context: Context, wallet: String) =
        KeyStoreHelper.removeWalletSecret(context, restoreSourceName(wallet))

    /**
     * Recover an interrupted password migration. Both wallet2 files are password-keyed,
     * so the keys file and cache must be restored together when the wrapped key is absent
     * or cannot be opened.
     */
    fun recoverInterruptedMigration(context: Context, walletBasePath: String, wallet: String) {
        val snapshots = migrationSnapshots(walletBasePath).filter { (snapshot, _) -> snapshot.exists() }
        if (snapshots.isEmpty()) return

        val wrappedSecretUsable = exists(context, wallet) &&
            runCatching { load(context, wallet) }.isSuccess

        if (wrappedSecretUsable) {
            // Keep the PIN-encrypted rollback copy until a cold open with the wrapped
            // password succeeds. A readable Keystore blob alone does not prove that
            // wallet2 can open both password-protected wallet files with that secret.
            Timber.tag(TAG).i("Wallet-key migration snapshot retained for first cold-open verification")
            return
        }

        rollbackMigration(context, walletBasePath, wallet)
        Timber.tag(TAG).w("Interrupted wallet-key migration rolled back to PIN encryption")
    }

    /**
     * Remove the rollback copies only after the new Keystore password has opened
     * the wallet successfully in a fresh process.
     */
    fun completeMigration(walletBasePath: String) {
        migrationSnapshots(walletBasePath).forEach { (snapshot, _) ->
            if (snapshot.exists() && !snapshot.delete()) {
                Timber.tag(TAG).w("Unable to remove wallet migration snapshot: ${snapshot.name}")
            }
        }
    }

    /**
     * Restore the wallet files captured before password migration. The copy is
     * completed for every available snapshot before any snapshot is deleted.
     * Exposed internally so the file-level rollback can be regression-tested.
     */
    internal fun restoreMigrationSnapshots(walletBasePath: String): Boolean {
        val snapshots = migrationSnapshots(walletBasePath).filter { (snapshot, _) -> snapshot.exists() }
        if (snapshots.isEmpty()) return false

        snapshots.forEach { (snapshot, live) ->
            live.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    throw IllegalStateException("Unable to create wallet directory for migration rollback")
                }
            }
            snapshot.copyTo(live, overwrite = true)
        }
        snapshots.forEach { (snapshot, _) ->
            if (snapshot.exists() && !snapshot.delete()) {
                throw IllegalStateException("Unable to remove restored wallet migration snapshot")
            }
        }
        return true
    }

    fun rollbackMigration(context: Context, walletBasePath: String, wallet: String): Boolean {
        val hasSnapshots = migrationSnapshots(walletBasePath).any { (snapshot, _) -> snapshot.exists() }
        if (!hasSnapshots) return false

        remove(context, wallet)
        return restoreMigrationSnapshots(walletBasePath)
    }

    fun migrationSnapshots(walletBasePath: String): List<Pair<File, File>> = listOf(
        File("$walletBasePath.keys.premigrate") to File("$walletBasePath.keys"),
        File("$walletBasePath.premigrate") to File(walletBasePath),
    )
}
