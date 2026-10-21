package io.anonero.ui.home.settings

import AnonNeroTheme
import android.app.Activity
import android.content.SharedPreferences
import android.content.Intent
import android.view.HapticFeedbackConstants
import android.os.Process
import android.os.Handler
import android.os.Looper
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
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import timber.log.Timber
import androidx.core.content.edit
import io.anonero.R
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException


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
    private val wipeTaskStarted = AtomicBoolean(false)

    fun showWipeProgress(message: String) {
        _wipeProgressMessage.postValue(message)
    }

    /**
     * Resume after process death without asking for either the deletion password
     * or wallet PIN again. This path only removes files: the old native handle died
     * with the previous process and AnonApplication skips wallet initialization.
     */
    fun resumeInterruptedWipe(activity: Activity): Job? {
        if (anonWalletHandler.isSecureWipeCleanupActive() ||
            !wipeTaskStarted.compareAndSet(false, true)
        ) return null

        anonWalletHandler.markSecureWipeRequestStartedInProcess()
        anonWalletHandler.markSecureWipeCleanupStarted()
        anonWalletHandler.prepareForSecureWipeRequest()

        return anonWalletHandler.scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    try {
                        check(AnonConfig.markSecureWipePending(activity)) {
                            "Unable to persist secure-delete recovery state"
                        }
                        (activity as? MainActivity)?.stopNotificationService()
                        _wipeProgress.postValue(.18f)
                        _wipeProgressMessage.postValue("正在恢复安全删除钱包")

                        val walletDir = File(activity.filesDir, "wallets")
                        check(!walletDir.exists() || walletDir.deleteRecursively()) {
                            "Wallet files could not be deleted"
                        }
                        check(!walletDir.exists()) { "Wallet files remain after deletion" }

                        _wipeProgress.postValue(.42f)
                        _wipeProgressMessage.postValue("正在清除钱包设置")
                        check(sharedPreferences.edit().clear().commit()) {
                            "Unable to clear wallet preferences"
                        }

                        _wipeProgress.postValue(.58f)
                        _wipeProgressMessage.postValue("正在清除节点配置")
                        nodesRepository.clearAll()

                        _wipeProgress.postValue(.70f)
                        _wipeProgressMessage.postValue("正在清除日志和密钥")
                        logRepository.clear()
                        KeyStoreHelper.clearWalletCredentials(activity.applicationContext, "anon")
                        AnonConfig.clearSpendCacheFiles(activity.applicationContext)
                        AnonConfig.disposeState()

                        _wipeProgress.postValue(.86f)
                        _wipeProgressMessage.postValue("正在清除应用全部数据")
                        // clearAllAppData keeps the root-level recovery marker until
                        // every other private/external app-data file has been removed.
                        check(AnonConfig.clearAllAppData(activity.applicationContext)) {
                            "Final application-data cleanup is incomplete"
                        }

                        _wipeProgress.postValue(1f)
                        _wipeProgressMessage.postValue("钱包已成功安全删除")
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Secure-delete recovery failed; retrying")
                        runCatching { AnonConfig.markSecureWipePending(activity) }
                        _wipeProgress.postValue(.28f)
                        _wipeProgressMessage.postValue("正在继续安全删除钱包")
                        delay(1_000)
                    }
                }
            } finally {
                anonWalletHandler.markSecureWipeCleanupFinished()
                wipeTaskStarted.set(false)
            }
        }
    }

    fun wipe(passPhrase: String, activity: Activity): Job {
        check(wipeTaskStarted.compareAndSet(false, true)) {
            "Secure-delete task is already running"
        }
        anonWalletHandler.markSecureWipeRequestStartedInProcess()
        anonWalletHandler.markSecureWipeCleanupStarted()

        return anonWalletHandler.scope.launch(Dispatchers.IO) {
            var nativeWalletDeletionCompleted = false
            try {
                // This loop is intentionally retry-based: after the deletion password
                // is accepted, no error dialog or password prompt can stop the flow.
                while (true) {
                    try {
                        check(AnonConfig.markSecureWipePending(activity)) {
                            "Unable to persist secure-delete request"
                        }
                        (activity as? MainActivity)?.stopNotificationService()

                        if (!nativeWalletDeletionCompleted) {
                            _wipeProgress.postValue(.28f)
                            _wipeProgressMessage.postValue("正在安全删除钱包")
                            check(anonWalletHandler.wipe(passPhrase)) {
                                "Wallet deletion did not finish"
                            }
                            nativeWalletDeletionCompleted = true
                            _wipeProgress.postValue(.48f)
                            _wipeProgressMessage.postValue("钱包数据已清除")
                        }

                        _wipeProgress.postValue(.58f)
                        _wipeProgressMessage.postValue("正在清除钱包设置")
                        check(sharedPreferences.edit().clear().commit()) {
                            "Unable to clear wallet preferences"
                        }

                        _wipeProgress.postValue(.68f)
                        _wipeProgressMessage.postValue("正在清除节点配置")
                        nodesRepository.clearAll()

                        _wipeProgress.postValue(.76f)
                        _wipeProgressMessage.postValue("正在清除日志和密钥")
                        logRepository.clear()
                        KeyStoreHelper.clearWalletCredentials(activity.applicationContext, "anon")
                        AnonConfig.clearSpendCacheFiles(activity.applicationContext)
                        AnonConfig.disposeState()

                        _wipeProgress.postValue(.86f)
                        _wipeProgressMessage.postValue("正在清除应用全部数据")
                        // This is the final destructive phase. The marker is the last
                        // item clearAllAppData removes, so an interrupted cleanup can resume.
                        check(AnonConfig.clearAllAppData(activity.applicationContext)) {
                            "Final application-data cleanup is incomplete"
                        }

                        _wipeProgress.postValue(1f)
                        _wipeProgressMessage.postValue("钱包已成功安全删除")
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Secure deletion failed; automatically retrying")
                        runCatching { AnonConfig.markSecureWipePending(activity) }
                        _wipeProgress.postValue(if (nativeWalletDeletionCompleted) .66f else .28f)
                        _wipeProgressMessage.postValue(
                            if (nativeWalletDeletionCompleted) {
                                "正在继续清除钱包设置"
                            } else {
                                "正在继续安全删除钱包"
                            }
                        )
                        delay(1_000)
                    }
                }
            } finally {
                anonWalletHandler.markSecureWipeCleanupFinished()
                wipeTaskStarted.set(false)
            }
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
    val anonWalletHandler = koinInject<AnonWalletHandler>()
    val secureWipeViewModel = koinViewModel<SecureWipeViewModel>()
    val focusRequester = remember { FocusRequester() }
    val view = LocalView.current
    val activity = LocalActivity.current
    val context = LocalContext.current
    val wipePending = AnonConfig.isSecureWipePending(context)

    var passPhraseDialog by remember {
        mutableStateOf(!anonWalletHandler.isSecureWipeInProgress() && !wipePending)
    }
    var passPhrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var wipeSubmitting by remember {
        mutableStateOf(wipePending || anonWalletHandler.isSecureWipeInProgress())
    }
    val errorShake = rememberShakeController()
    val scope = rememberCoroutineScope()
    val progressMessage by secureWipeViewModel.wipeProgressMessage.observeAsState(null)
    val wipeProgress by secureWipeViewModel.wipeProgress.observeAsState(.1f)

    BackHandler(
        enabled = wipeSubmitting || wipePending || anonWalletHandler.isSecureWipeInProgress()
    ) {
        // Once the deletion password is verified, the persisted request cannot be cancelled.
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

    LaunchedEffect(passPhraseDialog) {
        if (passPhraseDialog) {
            delay(100)
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(progressMessage) {
        if (progressMessage != null) requestClearScreen.invoke(false)

        if (progressMessage == "钱包已成功安全删除") {
            val completedActivity = activity
            if (completedActivity != null &&
                !completedActivity.isFinishing &&
                !completedActivity.isDestroyed
            ) {
                completedActivity.startActivity(
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                completedActivity.finishAndRemoveTask()
                Handler(Looper.getMainLooper()).postDelayed({
                    Process.killProcess(Process.myPid())
                }, 350)
            }
        }
    }

    LaunchedEffect(wipePending) {
        if (wipePending && activity != null &&
            !anonWalletHandler.isSecureWipeCleanupActive()
        ) {
            wipeSubmitting = true
            passPhraseDialog = false
            requestClearScreen(false)
            secureWipeViewModel.showWipeProgress("正在恢复安全删除钱包")
            secureWipeViewModel.resumeInterruptedWipe(activity)
        }
    }

    fun clearWallet() {
        if (wipeSubmitting || wipePending || anonWalletHandler.isSecureWipeInProgress()) return

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val hash = prefs.getString(PREFS_PASSPHRASE_HASH, "")
                    val hashedPass = KeyStoreHelper.getCrazyPass(AnonConfig.context, passPhrase)
                    hash == hashedPass
                }.getOrDefault(false)
            }

            if (result) {
                val currentActivity = activity
                if (currentActivity == null) {
                    errorShake.shake(ShakeConfig(6, translateX = 5f))
                    return@launch
                }

                passPhraseDialog = false
                wipeSubmitting = true
                error = null
                requestClearScreen(false)
                anonWalletHandler.markSecureWipeRequestStartedInProcess()
                secureWipeViewModel.showWipeProgress("正在启动安全删除")

                while (true) {
                    try {
                        withContext(Dispatchers.IO) {
                            check(AnonConfig.markSecureWipePending(currentActivity)) {
                                "Unable to persist secure-delete request"
                            }
                            anonWalletHandler.prepareForSecureWipeRequest()
                            (currentActivity as? MainActivity)?.stopNotificationService()
                        }
                        secureWipeViewModel.wipe(passPhrase, currentActivity)
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Unable to start secure deletion; retrying")
                        secureWipeViewModel.showWipeProgress("正在继续安全删除钱包")
                        delay(1_000)
                    }
                }
            } else {
                errorShake.shake(ShakeConfig(6, translateX = 5f))
                repeat(6) {
                    delay(50)
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                }
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
                if (!wipeSubmitting && !wipePending) passPhraseDialog = false
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
                    enabled = !wipeSubmitting && !wipePending,
                    onClick = {
                        clearWallet()
                    }) { Text(stringResource(id = R.string.wipe)) }
            },
            dismissButton = {
                if (!wipeSubmitting && !wipePending &&
                    !anonWalletHandler.isSecureWipeInProgress()
                ) {
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