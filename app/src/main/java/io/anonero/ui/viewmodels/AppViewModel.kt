package io.anonero.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.anonero.AnonConfig
import io.anonero.services.AnonWalletHandler
import io.anonero.services.InvalidPin
import io.anonero.services.TorService
import io.anonero.services.WalletState
import io.anonero.model.WalletManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber

private const val TAG = "AppViewModel"

class AppViewModel(private val walletState: WalletState, private val torService: TorService) :
    ViewModel() {

    private var splashInit = false
    private var walletExist: MutableSharedFlow<Boolean> = MutableSharedFlow(replay = 2)

    val isReady get() = splashInit
    val existWallet get() = walletExist as SharedFlow<Boolean>

    private val walletHandler: AnonWalletHandler by inject(AnonWalletHandler::class.java)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            walletExist.emit(
                AnonConfig.getDefaultWalletFile(AnonConfig.context!!).exists()
            )
            splashInit = true
        }
    }

    @Synchronized
    fun openWallet(pin: String): Boolean {
        return try {
            walletHandler.openWallet(pin)
        } catch (e: InvalidPin) {
            false
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "openWallet failed")
            false
        }
    }

    /**
     * PIN verification may change navigation/background-lock state and remove the
     * LockScreen composable before the operation has returned. Run that workflow in
     * the ViewModel scope rather than rememberCoroutineScope(), which is cancelled
     * when the composable leaves the composition.
     */
    fun launchPinOperation(block: suspend () -> Unit): Job =
        viewModelScope.launch(Dispatchers.IO) { block() }

    /**
     * Compatibility migration for wallets created by versions that failed to save
     * PREFS_PIN_HASH during seed restore or view-only setup. Normal unlocks use the
     * local hash only; this disk-wallet check is called solely when that hash mismatches.
     */
    fun verifyLegacyWalletPin(pin: String): Boolean {
        val context = AnonConfig.context?.applicationContext ?: return false
        val walletFile = AnonConfig.getDefaultWalletFile(context)
        val manager = WalletManager.instance ?: return false

        // Older builds and native wrappers have used both the wallet base path and
        // the .keys path for password verification. Try both without changing the
        // PIN UI contract; only a successful native credential check is accepted.
        val candidatePaths = listOf(
            "${walletFile.path}.keys",
            walletFile.path
        ).distinct()

        for (path in candidatePaths) {
            val verified = runCatching {
                manager.verifyWalletPassword(path, pin, AnonConfig.viewOnly)
            }.onFailure {
                Timber.tag(TAG).d(it, "Wallet password verification failed for candidate path")
            }.getOrDefault(false)
            if (verified) {
                Timber.tag(TAG).i("Legacy PIN verified against wallet credential file")
                return true
            }

            // Compatibility fallback for native builds exposing the wallet-device
            // password verifier separately from verifyWalletPassword().
            val verifiedByWalletDevice = runCatching {
                manager.verifyWalletPasswordOnly(path, pin)
            }.onFailure {
                Timber.tag(TAG).d(it, "Wallet-device PIN verification failed for candidate path")
            }.getOrDefault(false)
            if (verifiedByWalletDevice) {
                Timber.tag(TAG).i("Legacy PIN verified by wallet-device password check")
                return true
            }
        }

        Timber.tag(TAG).w("PIN hash mismatch and wallet credential verification rejected the PIN")
        return false
    }

    @Synchronized
    fun startService() {
        walletHandler.scope.launch {
            walletHandler.startService()
        }.invokeOnCompletion {
            it?.printStackTrace()
            if (it != null)
                Timber.tag(TAG).e(it)
        }
    }

    /** Starts the wallet handler and does not return until node setup and refresh have been started. */
    suspend fun startServiceAndWait() {
        walletHandler.startService()
    }


}