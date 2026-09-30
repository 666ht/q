package io.anonero.ui.onboard

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import io.anonero.AnonConfig
import io.anonero.R
import io.anonero.model.NeroKeyPayload
import io.anonero.model.Wallet
import io.anonero.model.WalletManager
import io.anonero.util.CrazyPassEncoder
import io.anonero.util.KeyStoreHelper
import io.anonero.util.PREFS_PASSPHRASE_HASH
import io.anonero.util.PREFS_PIN_HASH
import io.anonero.util.RESTORE_HEIGHT
import io.anonero.util.RESTORE_NEEDS_RESCAN
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber


enum class Mode {
    RESTORE,
    CREATE
}

private const val TAG = "OnboardViewModel"

class OnboardViewModel(private val prefs: SharedPreferences) : ViewModel() {

    private var mode = Mode.CREATE
    private var passPhrase = ""
    private var walllet: Wallet? = null
    private var neroKeyPayload: NeroKeyPayload? = null
    private var restorePayload: RestorePayload? = null

    fun setMode(mode: Mode) = run { this.mode = mode }

    fun getMode() = mode

    fun getSeed() = walllet?.getSeed(passPhrase) ?: ""


    suspend fun create(pin: String) {
        if (AnonConfig.context == null) return
        withContext(Dispatchers.IO) {
            val context = AnonConfig.context!!.applicationContext
            val walletFile = AnonConfig.getDefaultWalletFile(context)
            AnonConfig.getDefaultWalletDir(context).deleteRecursively()
            AnonConfig.getDefaultWalletDir(context).mkdirs()
            val anonWallet = WalletManager.instance?.createWallet(
                walletFile,
                pin,
                passPhrase,
                "English",
                1,
            )
            anonWallet?.store()
            delay(100)
            if (anonWallet?.status?.isOk != true) {
                walletFile.delete()
                throw CancellationException(context.getString(R.string.unable_to_create_wallet))
            }
            val crazyPass: String = KeyStoreHelper.getCrazyPass(AnonConfig.context, passPhrase)
            prefs.edit {
                putString(PREFS_PASSPHRASE_HASH, crazyPass)
            }

            //adds padding to the pin if it's less than 32 bytes
            val pinHash = CrazyPassEncoder.encode(
                pin.toByteArray().let { bytes ->
                    if (bytes.size < 32) {
                        bytes + ByteArray(32 - bytes.size)
                    } else bytes
                }
            )
            prefs.edit {
                putString(PREFS_PIN_HASH, pinHash)
            }
            walllet = anonWallet
            delay(500)
        }
    }


    suspend fun createViewOnly(pin: String) {
        if (neroKeyPayload == null) return
        if (AnonConfig.context == null) return
        withContext(Dispatchers.IO) {
            val context = AnonConfig.context!!.applicationContext
            val walletFile = AnonConfig.getDefaultWalletFile(context)
            AnonConfig.getDefaultWalletDir(context).deleteRecursively()
            AnonConfig.getDefaultWalletDir(context).mkdirs()
            val anonWallet = WalletManager.instance?.createWalletWithKeys(
                walletFile,
                pin,
                language = "English",
                restoreHeight = neroKeyPayload!!.restoreHeight,
                viewKeyString = neroKeyPayload!!.privateViewKey,
                addressString = neroKeyPayload!!.primaryAddress,
                spendKeyString = ""
            )
            anonWallet?.setRestoreHeight(3460000);
            anonWallet?.store()
            delay(100)
            if (anonWallet?.status?.isOk != true) {
                walletFile.delete()
                throw CancellationException(context.getString(R.string.unable_to_create_wallet))
            }
            val crazyPass: String = KeyStoreHelper.getCrazyPass(AnonConfig.context, passPhrase)
            prefs.edit {
                putString("passPhraseHash", crazyPass)
            }
            walllet = anonWallet
            delay(500)
        }
    }

    fun setPassPhrase(it: String) {
        this.passPhrase = it
    }

    fun setNeroPayload(it: NeroKeyPayload) {
        neroKeyPayload = it
    }

    fun setRestorePayload(it: RestorePayload) {
        restorePayload = it
    }

    fun getRestorePayload(): RestorePayload? {
        return restorePayload
    }

    suspend fun restoreFromSeed(pin: String) {
        if (restorePayload == null) return

        val context = AnonConfig.context!!.applicationContext
        val walletFile = AnonConfig.getDefaultWalletFile(context)
        AnonConfig.getDefaultWalletDir(context).deleteRecursively()
        AnonConfig.getDefaultWalletDir(context).mkdirs()
        delay(100)
        val anonWallet = if (restorePayload!!.seed.size == 25) {
            WalletManager.instance?.recoveryWallet(
                walletFile,
                pin,
                restorePayload!!.seed.joinToString(" "),
                passPhrase,
                restorePayload!!.restoreHeight ?: 0
            )
        } else if (restorePayload!!.seed.size == 16) {
            WalletManager.instance?.recoveryWalletPolyseed(
                walletFile,
                pin,
                restorePayload!!.seed.joinToString(" "),
                passPhrase,
            )
        } else {
            null
        }
        if (anonWallet == null) {
            throw Exception("unable to create wallet from seed, invalid seed length ${restorePayload!!.seed.size}")
        }
        delay(1000)
        restorePayload?.restoreHeight?.let {
            anonWallet?.setRestoreHeight(it)
        }
        anonWallet?.store()
        anonWallet?.store(walletFile.path)
        delay(100)
        if (anonWallet?.status?.isOk != true) {
            walletFile.delete()
            throw CancellationException("unable to create wallet ${anonWallet?.status?.errorString}")
        }
        val crazyPass: String = KeyStoreHelper.getCrazyPass(AnonConfig.context, passPhrase)
        prefs.edit(commit = true) {
            putString(PREFS_PASSPHRASE_HASH, crazyPass)
            restorePayload?.restoreHeight?.let {
                putLong(RESTORE_HEIGHT, it)
            }
            putBoolean(RESTORE_NEEDS_RESCAN, true)
        }
        try {
            anonWallet.close()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unable to close wallet")
        }

        delay(500)
    }

}