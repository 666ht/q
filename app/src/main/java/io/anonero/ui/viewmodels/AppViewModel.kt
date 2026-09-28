package io.anonero.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.anonero.AnonConfig
import io.anonero.services.AnonWalletHandler
import io.anonero.services.InvalidPin
import io.anonero.services.TorService
import io.anonero.services.WalletState
import kotlinx.coroutines.Dispatchers
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
        }
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