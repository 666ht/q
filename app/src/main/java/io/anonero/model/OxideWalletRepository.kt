package io.anonero.model

import android.content.Context
import org.monfluo.wallet.lib.Monfluo
import org.monfluo.wallet.lib.OxideNative
import org.monfluo.wallet.lib.data.Network as OxideNetwork
import org.monfluo.wallet.lib.seed.MoneroSeed
import org.monfluo.wallet.lib.seed.Polyseed
import org.monfluo.wallet.lib.wallet.Wallet as OxideWallet
import timber.log.Timber
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Transitional side-by-side wallet store. Existing wallet2 files remain authoritative
 * until AN's transaction/output model has been fully adapted to Monfluo.
 *
 * Sidecar files deliberately live outside filesDir/wallets because the Rust wallet
 * stores a directory per wallet while wallet2 uses a single wallet file there.
 */
internal object OxideWalletRepository {
    private const val TAG = "OxideWalletRepository"
    private const val ROOT_DIR = "monfluo-oxide-wallets"
    private val sessionLock = Any()

    @Volatile
    private var activeWallet: OxideWallet? = null

    @Volatile
    private var activeWalletName: String? = null

    private fun root(context: Context): File =
        File(context.filesDir, ROOT_DIR).apply { mkdirs() }

    private fun walletDirectory(context: Context, name: String): File =
        File(File(root(context), "wallets"), name)

    private fun oxideNetwork(networkType: NetworkType): OxideNetwork = when (networkType) {
        NetworkType.NetworkType_Mainnet -> OxideNetwork.Mainnet
        NetworkType.NetworkType_Stagenet -> OxideNetwork.Stagenet
        NetworkType.NetworkType_Testnet -> OxideNetwork.Testnet
    }

    /**
     * Creates a Rust sidecar from the exact mnemonic belonging to the legacy wallet.
     * Non-empty Monero seed offsets are skipped because the mirrored JNI currently
     * does not implement offset derivation; it must never create a mismatched wallet.
     */
    suspend fun mirrorFromMnemonic(
        context: Context,
        walletName: String,
        password: String,
        mnemonic: String,
        expectedAddress: String,
        offsetPassphrase: String,
        networkType: NetworkType,
        restoreHeight: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        clearMirror(context, walletName)

        if (!OxideNative.isAvailable) {
            Timber.tag(TAG).d("Rust bridge unavailable; keeping wallet2 as the active backend")
            return@withContext false
        }
        if (offsetPassphrase.isNotEmpty()) {
            Timber.tag(TAG).i("Rust sidecar skipped for wallet %s: seed offset needs a Rust implementation", walletName)
            return@withContext false
        }
        if (mnemonic.isBlank() || expectedAddress.isBlank()) {
            Timber.tag(TAG).w("Rust sidecar skipped for wallet %s: missing seed/address", walletName)
            return@withContext false
        }

        val moneroSeed = MoneroSeed.fromMnemonic(mnemonic)
        val polyseed = if (moneroSeed == null) Polyseed.fromMnemonic(mnemonic) else null
        if (moneroSeed == null && polyseed == null) {
            Timber.tag(TAG).w("Rust seed parser rejected wallet %s; keeping wallet2 as the active backend", walletName)
            return@withContext false
        }

        val rootDirectory = root(context)
        val height = restoreHeight.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        try {
            if (moneroSeed != null) {
                Monfluo.restoreWallet(rootDirectory.absolutePath, oxideNetwork(networkType), walletName, password, moneroSeed, null, height)
            } else {
                Monfluo.restoreWallet(rootDirectory.absolutePath, oxideNetwork(networkType), walletName, password, polyseed!!, null, height)
            }

            val rustWallet = Monfluo.openWallet(rootDirectory.absolutePath, walletName, password)
            val rustAddress = runCatching {
                rustWallet.getAccountView(0).addresses.firstOrNull { it.addressIndex == 0 }?.value
            }.getOrElse {
                runCatching { rustWallet.close() }
                throw it
            }

            if (rustAddress != expectedAddress) {
                runCatching { rustWallet.close() }
                throw IllegalStateException("Rust wallet primary address did not match wallet2; refusing sidecar")
            }

            synchronized(sessionLock) {
                closeActiveWalletLocked()
                activeWallet = rustWallet
                activeWalletName = walletName
            }
            Timber.tag(TAG).i("Rust sidecar created and address-verified for wallet %s", walletName)
            true
        } catch (error: Throwable) {
            clearMirror(context, walletName)
            Timber.tag(TAG).w(error, "Could not create Rust sidecar for wallet %s; keeping wallet2", walletName)
            false
        }
    }

    /**
     * Opens an existing sidecar only if its primary address matches the verified
     * wallet2 wallet and network. Existing or unsupported wallets remain on wallet2.
     */
    fun openIfCompatible(
        context: Context,
        walletName: String,
        password: String,
        expectedAddress: String,
        networkType: NetworkType,
    ): Boolean {
        if (!OxideNative.isAvailable || !walletDirectory(context, walletName).isDirectory) {
            return false
        }

        synchronized(sessionLock) {
            val existing = activeWallet
            if (existing != null && activeWalletName == walletName) {
                val address = runCatching {
                    existing.getAccountView(0).addresses.firstOrNull { it.addressIndex == 0 }?.value
                }.getOrNull()
                if (address == expectedAddress) return true
                closeActiveWalletLocked()
            }
        }

        val rootDirectory = root(context)
        return try {
            val rustWallet = Monfluo.openWallet(rootDirectory.absolutePath, walletName, password)
            val rustAddress = runCatching {
                rustWallet.getAccountView(0).addresses.firstOrNull { it.addressIndex == 0 }?.value
            }.getOrElse {
                runCatching { rustWallet.close() }
                throw it
            }
            if (rustAddress != expectedAddress) {
                runCatching { rustWallet.close() }
                Timber.tag(TAG).w("Rust sidecar address mismatch for %s; wallet2 remains authoritative", walletName)
                return false
            }

            synchronized(sessionLock) {
                closeActiveWalletLocked()
                activeWallet = rustWallet
                activeWalletName = walletName
            }
            Timber.tag(TAG).i("Opened Rust wallet sidecar for %s on %s", walletName, networkType)
            true
        } catch (error: Throwable) {
            Timber.tag(TAG).w(error, "Rust sidecar unavailable for %s; wallet2 remains authoritative", walletName)
            false
        }
    }

    fun getActiveWallet(walletName: String? = null): OxideWallet? =
        if (walletName == null || walletName == activeWalletName) activeWallet else null

    fun closeActiveWallet(walletName: String? = null) {
        synchronized(sessionLock) {
            if (walletName != null && walletName != activeWalletName) return
            closeActiveWalletLocked()
        }
    }

    private fun closeActiveWalletLocked() {
        val wallet = activeWallet
        activeWallet = null
        activeWalletName = null
        if (wallet != null) {
            runCatching { wallet.close() }
                .onFailure { Timber.tag(TAG).w(it, "Rust sidecar close failed") }
        }
    }

    fun clearMirror(context: Context, walletName: String) {
        closeActiveWallet(walletName)
        val rootDirectory = root(context)
        if (OxideNative.isAvailable) {
            runCatching { Monfluo.deleteWallet(rootDirectory.absolutePath, walletName) }
        }
        val directory = walletDirectory(context, walletName)
        if (directory.exists() && !directory.deleteRecursively()) {
            Timber.tag(TAG).w("Could not remove Rust sidecar directory for %s", walletName)
        }
    }

    fun deleteAllMirrors(context: Context) {
        closeActiveWallet()
        val directory = File(context.filesDir, ROOT_DIR)
        if (directory.exists() && !directory.deleteRecursively()) {
            Timber.tag(TAG).w("Could not remove Rust sidecar directory during wallet wipe")
        }
    }
}
