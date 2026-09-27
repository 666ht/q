package io.anonero.ui.home.settings

import AnonNeroTheme
import android.app.Activity
import android.content.SharedPreferences
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.anonero.AnonConfig
import io.anonero.services.AnonWalletHandler
import io.anonero.store.LogRepository
import io.anonero.store.NodesRepository
import io.anonero.ui.MainActivity
import io.anonero.util.KeyStoreHelper
import io.anonero.util.PREFS_PASSPHRASE_HASH
import io.anonero.util.ShakeConfig
import io.anonero.util.WALLET_PREFERENCES
import io.anonero.util.rememberShakeController
import io.anonero.util.shake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import timber.log.Timber
import androidx.core.content.edit
import io.anonero.R


private const val TAG = "SecureWipe"

class SecureWipeViewModel(
    private val anonWalletHandler: AnonWalletHandler,
    private val sharedPreferences: SharedPreferences,
    private val nodesRepository: NodesRepository,
    private val logRepository: LogRepository
) : ViewModel() {

    private val _wipeProgressMessage = MutableLiveData<String?>()
    val wipeProgressMessage: LiveData<String?> = _wipeProgressMessage

    private val _wipeProgress = MutableLiveData(0.1f)
    val wipeProgress: LiveData<Float> = _wipeProgress

    fun wipe(passPhrase: String, activity: Activity): Job {
        return viewModelScope.launch(Dispatchers.IO) {
            (activity as MainActivity).stopNotificationService()
            _wipeProgress.postValue(.3f)
            _wipeProgressMessage.postValue(activity.getString(R.string.wiping_wallet))
            anonWalletHandler.wipe(passPhrase)
            delay(1000)
            _wipeProgress.postValue(.5f)
            _wipeProgressMessage.postValue(activity.getString(R.string.wallet_cleared))
            delay(1200)
            _wipeProgress.postValue(.6f)
            _wipeProgressMessage.postValue(activity.getString(R.string.clearing_preferences))
            sharedPreferences.edit { clear() }
            delay(800)
            _wipeProgress.postValue(.7f)
            _wipeProgressMessage.postValue(activity.getString(R.string.clearing_nodes))
            nodesRepository.clearAll()
            delay(1200)
            _wipeProgressMessage.postValue(activity.getString(R.string.clearing_logs))
            delay(1000)
            logRepository.clear()
            AnonConfig.disposeState()
            _wipeProgress.postValue(.8f)
            _wipeProgressMessage.postValue(activity.getString(R.string.logs_cleared))
            delay(1200)
            _wipeProgress.postValue(1f)
            _wipeProgressMessage.postValue(activity.getString(R.string.wallet_wiped_successfully))
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecureWipe(
    modifier: Modifier = Modifier,
    onBackPress: () -> Unit = {},
    requestClearScreen: (bool: Boolean) -> Unit = {},
    goToHome: () -> Unit = {},
) {

    val prefs = koinInject<SharedPreferences>(named(WALLET_PREFERENCES))
    val secureWipeViewModel = koinViewModel<SecureWipeViewModel>()
    val focusRequester = remember { FocusRequester() }
    var passPhraseDialog by remember { mutableStateOf(true) }
    var passPhrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val errorShake = rememberShakeController()
    val scope = rememberCoroutineScope()
    val progressMessage by secureWipeViewModel.wipeProgressMessage.observeAsState(null)
    val wipeProgress by secureWipeViewModel.wipeProgress.observeAsState(.1f)
    val view = LocalView.current
    val activity = LocalActivity.current

    BackHandler {
        progressMessage != null
    }


    val animatedProgress by animateFloatAsState(
        targetValue = wipeProgress,
        animationSpec = tween(
            durationMillis = 300,//animation duration
            delayMillis = 50,//delay before animation start
            easing = LinearOutSlowInEasing
        ), label = "animatedProgress"
    )

    val backgroundBlur: Float by animateFloatAsState(
        if (passPhraseDialog) 6f else 0f,
        label = "blur-radius"
    )

    LaunchedEffect(true) {
        scope.launch {
            delay(100)
            focusRequester.requestFocus()
        }
    }
    LaunchedEffect(progressMessage) {
        if (progressMessage != null)
            requestClearScreen.invoke(false)
    }

    fun clearWallet() {
        scope.launch(Dispatchers.IO) {
            requestClearScreen(true)
            val hash = prefs.getString(PREFS_PASSPHRASE_HASH, "")
            val hashedPass =
                KeyStoreHelper.getCrazyPass(AnonConfig.context, passPhrase)
            if (hash == hashedPass) {
                val seed = getWalletSeed(passPhrase)?.split(" ")
                if (seed != null) {
                    passPhraseDialog = false
                    HapticFeedbackConstants.CONTEXT_CLICK
                    activity?.let { activity ->
                        secureWipeViewModel.wipe(passPhrase, activity)
                            .invokeOnCompletion {
                                if (it == null) {
                                    view.performHapticFeedback(
                                        HapticFeedbackConstants.CONTEXT_CLICK
                                    )
                                    scope.launch(Dispatchers.Main) {
                                        goToHome()
                                    }
                                } else {
                                    Timber.tag(TAG).e(it)
                                    error = it.message
                                    requestClearScreen(false)
                                }
                            }
                    }
                }
            } else {
                errorShake.shake(
                    ShakeConfig(
                        6, translateX = 5f
                    )
                )
                repeat(6) {
                    delay(50)
                    view.performHapticFeedback(
                        HapticFeedbackConstants.CONTEXT_CLICK
                    )
                }
                delay(100)
            }

        }
    }

    if (passPhraseDialog)
        AlertDialog(
            modifier = Modifier
                .shake(errorShake)
                .border(
                    1.dp,
                    color = MaterialTheme.colorScheme.onSecondary.copy(
                        alpha = .2f
                    ),
                    shape = MaterialTheme.shapes.medium,
                ),
            containerColor = MaterialTheme.colorScheme.secondary,
            properties = DialogProperties(
                securePolicy = SecureFlagPolicy.SecureOn,
                dismissOnClickOutside = false, dismissOnBackPress = false
            ),
            title = {
                Column {
                    Text(
                        text = stringResource(id = R.string.securely_wipe_title),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = 18.sp
                        )
                    )
                    Spacer(Modifier.padding(12.dp))
                    Text(
                        text = stringResource(id = R.string.securely_wipe_warning),
                        style = MaterialTheme.typography.bodyMedium.copy(
                        )
                    )
                    Spacer(Modifier.padding(8.dp))
                    Text(
                        text = stringResource(id = R.string.securely_wipe_passphrase_prompt),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Light
                    )
                }
            },
            text = {
                OutlinedTextField(
                    value = passPhrase,
                    shape = MaterialTheme.shapes.small,
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                    ),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {

                        }
                    ),
                    onValueChange = {
                        passPhrase = it
                    },

                    )
            },
            onDismissRequest = {
                passPhraseDialog = false
            },
            confirmButton = {
                Button(
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(
                        1.dp,
                        color = MaterialTheme.colorScheme.onError
                    ),
                    colors = ButtonDefaults.buttonColors(
                        contentColor = MaterialTheme.colorScheme.onBackground,
                        containerColor = MaterialTheme.colorScheme.errorContainer,

                        ),
                    onClick = {
                        clearWallet()
                    }) { Text(stringResource(id = R.string.wipe)) }
            },
            dismissButton = {
                Button(
                    onClick = {
                        scope.launch {
                            passPhraseDialog = false
                            delay(300)
                            onBackPress.invoke()
                        }
                    },
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(
                        1.dp,
                        color = MaterialTheme.colorScheme.onSecondary
                    ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    )
                ) {
                    Text(
                        stringResource(id = R.string.cancel),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSecondary.copy(
                                alpha = 0.8f
                            )
                        )
                    )
                }
            }
        )


    Scaffold(
        Modifier.blur(
            backgroundBlur.dp
        ),
    ) {
        Column(
            Modifier
                .padding(it)
                .padding(0.dp)
                .fillMaxSize(),
            Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (progressMessage != null && error == null)
                Box(
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.size(320.dp)
                    )
                    Text(
                        progressMessage ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(
                            Alignment.Center
                        )
                    )
                }
            if (error != null) {
                Box(
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        error ?: "",
                        style = MaterialTheme.typography.labelSmall
                            .copy(
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            ),
                        textAlign = TextAlign.Justify,
                        modifier = Modifier.align(
                            Alignment.Center
                        )
                    )
                }
            }
        }
    }
}

@Preview(device = "id:pixel_5")
@Composable
private fun SecureWipePrev() {
    AnonNeroTheme {
        SecureWipe()
    }
}