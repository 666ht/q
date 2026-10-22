package io.anonero.ui

import AnonNeroTheme
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.asLiveData
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import io.anonero.AnonConfig
import io.anonero.services.AnonNeroService
import io.anonero.services.WalletState
import io.anonero.services.startAnonService
import io.anonero.ui.home.LockScreen
import io.anonero.ui.home.LockScreenMode
import io.anonero.ui.home.LockScreenShortCut
import io.anonero.ui.home.graph.homeGraph
import io.anonero.ui.home.graph.routes.Home
import io.anonero.ui.onboard.OnboardViewModel
import io.anonero.ui.onboard.graph.LandingScreenRoute
import io.anonero.ui.onboard.graph.onboardingGraph
import io.anonero.util.WALLET_PREFERENCES
import io.anonero.util.WALLET_USE_TOR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.compose.koinViewModel
import org.koin.core.qualifier.named
import timber.log.Timber
import java.io.File
import android.widget.Toast


private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    private val walletState: WalletState by inject()
    val anonPrefs: SharedPreferences by inject(named(WALLET_PREFERENCES))

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashscreen = installSplashScreen()
        var isAppReady by mutableStateOf(false)
        super.onCreate(savedInstanceState)

        val scrimColor = Color.Black.copy(alpha = 0.1f).toArgb()
        splashscreen.setKeepOnScreenCondition { isAppReady }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(scrimColor),
            navigationBarStyle = SystemBarStyle.dark(scrimColor),
        )
        setContent {
            val context = LocalContext.current
            var walletExist by remember(context) {
                mutableStateOf(
                    AnonConfig.isSecureWipePending(context) || AnonConfig.isWalletFileExist()
                )
            }
            var useTor by remember(context) {
                mutableStateOf(!AnonConfig.isSecureWipePending(context))
            }
            var showCrashDialog by remember(context) { mutableStateOf(false) }
            var crashReportText by remember(context) { mutableStateOf("") }

            // Show a saved crash report once on the next launch. This check runs
            // outside TorSplash so a slow Tor connection cannot hide the dialog.
            LaunchedEffect(context) {
                if (AnonConfig.isSecureWipePending(context)) return@LaunchedEffect
                val report = withContext(Dispatchers.IO) {
                    runCatching {
                        val file = File(context.filesDir, "crash_report.txt")
                        if (file.isFile && file.length() > 0L) {
                            file.readText(Charsets.UTF_8)
                        } else {
                            null
                        }
                    }.getOrNull()
                }
                if (!report.isNullOrBlank()) {
                    val reportKey = report.hashCode().toString()
                    val popupPrefs = context.getSharedPreferences(
                        "crash_report_popup_state",
                        Context.MODE_PRIVATE
                    )
                    if (popupPrefs.getString("last_shown_report_hash", null) != reportKey) {
                        popupPrefs.edit().putString("last_shown_report_hash", reportKey).apply()
                        crashReportText = report
                        showCrashDialog = true
                    }
                }
            }

            val onboardViewModel = koinViewModel<OnboardViewModel>()

            LaunchedEffect(key1 = true) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val wipePending = AnonConfig.isSecureWipePending(context)
                        // Keep the recovery route available even if the wallet file was
                        // already removed before the previous process was interrupted.
                        walletExist = wipePending || AnonConfig.getDefaultWalletFile(context).exists()
                        useTor = if (wipePending) false else anonPrefs.getBoolean(WALLET_USE_TOR, true)
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Secure-wipe-aware startup initialization failed")
                        walletExist = AnonConfig.isSecureWipePending(context)
                        useTor = false
                    } finally {
                        isAppReady = true
                    }
                }
            }
            Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                TorSplash(enableTor = useTor) {
                    AnonNeroTheme {
                    val navController = rememberNavController()
                    navController.addOnDestinationChangedListener { x, destination, d ->
                        Timber.tag(TAG).d("destination changed to ${destination.route}")
                    }
                    val showLockScreen by walletState.backgroundSyncFlow.asLiveData().observeAsState(walletState.backgroundSync)
                    val deletionWorkflowActive = AnonConfig.isSecureWipePending(context)
                    Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                        NavHost(
                            navController = navController, popExitTransition = {
                            ExitTransition.None
                        }, popEnterTransition = {
                            EnterTransition.None
                        }, startDestination = if (walletExist) Home else LandingScreenRoute
                        ) {
                            //landing screen and onboarding screens
                            onboardingGraph(navController, onboardViewModel)
                            //home bottom navigation
                            homeGraph(navController)
                        }
                        if (showLockScreen && !deletionWorkflowActive) {
                            LockScreen(
                                mode = LockScreenMode.LOCK_SCREEN,
                                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                                onUnLocked = { _, shortCut ->
                                    if (shortCut != LockScreenShortCut.HOME) {
                                        walletState.emitUnlockShortcut(shortCut)
                                    }
                                }
                            )
                        }
                    }
                }
                }
                if (showCrashDialog) {
                    AlertDialog(
                        onDismissRequest = { showCrashDialog = false },
                        title = { Text("检测到上次运行时闪退") },
                        text = {
                            Text("已保存闪退日志。点击“复制日志”后，可将日志粘贴发送以便排查。")
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    runCatching {
                                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(
                                            ClipData.newPlainText("Anonero crash report", crashReportText)
                                        )
                                    }.onSuccess {
                                        Toast.makeText(this@MainActivity, "闪退日志已复制", Toast.LENGTH_SHORT).show()
                                    }.onFailure {
                                        Toast.makeText(this@MainActivity, "复制日志失败", Toast.LENGTH_SHORT).show()
                                    }
                                    showCrashDialog = false
                                }
                            ) {
                                Text("复制日志")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showCrashDialog = false }) {
                                Text("关闭")
                            }
                        }
                    )
                }
            }
        }

    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Intent(applicationContext, AnonNeroService::class.java).also {
                it.action = "start"
                ContextCompat.startForegroundService(applicationContext, it)
            }
        }
    }

    fun startNotificationService() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startAnonService(applicationContext)
        }
    }

    fun stopNotificationService() {
        Intent(applicationContext, AnonNeroService::class.java).also {
            it.action = "stop"
            ContextCompat.startForegroundService(applicationContext, it)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        walletState.requestBackgroundSyncOnLeave()
    }

    override fun onDestroy() {
        // Activity destruction is not wallet shutdown. The native wallet can still be
        // scanning on wallet2's worker thread, so closing it here can race JNI callbacks
        // and crash the next Activity launch. TorService and the wallet manager are
        // application-scoped; keep them alive across Activity recreation/re-entry.
        // Explicit wallet wipe already performs its own ordered native teardown.
        scope.cancel()
        super.onDestroy()
    }
}
