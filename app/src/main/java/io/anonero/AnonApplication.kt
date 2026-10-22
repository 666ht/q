package io.anonero

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.content.SharedPreferences
import io.anonero.di.appModule
import io.anonero.model.WalletManager
import io.anonero.model.node.NodeFields
import io.anonero.services.TorService
import io.anonero.store.LogRepository
import io.anonero.store.NodesRepository
import io.anonero.ui.util.AnonLogTree
import io.anonero.util.WALLET_PREFERENCES
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext.startKoin
import org.koin.core.qualifier.named
import timber.log.Timber
import timber.log.Timber.DebugTree
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean


const val FOREGROUND_CHANNEL = "anon_foreground"
const val TX_CHANNEL = "anon_transactions"
private const val TAG = "AnonApplication"

class AnonApplication : Application(), Thread.UncaughtExceptionHandler {

    private lateinit var anonLogTree: AnonLogTree
    private val defaultHandler =
        Thread.getDefaultUncaughtExceptionHandler()
    private val crashReportWriteStarted = AtomicBoolean(false)

    private fun persistCrashReport(thread: Thread, throwable: Throwable) {
        if (!crashReportWriteStarted.compareAndSet(false, true)) return
        try {
            val reportFile = File(filesDir, "crash_report.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
                .format(Date())
            val report = buildString {
                appendLine("ANONERO CRASH REPORT")
                appendLine("Time: $timestamp")
                appendLine("Thread: ${thread.name} (id=${thread.id})")
                appendLine("App ID: ${BuildConfig.APPLICATION_ID}")
                appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} / ${Build.DEVICE}")
                appendLine("Product: ${Build.PRODUCT}; Hardware: ${Build.HARDWARE}; ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
                appendLine()
                appendLine("Uncaught exception:")
                append(throwable.stackTraceToString())
            }
            FileOutputStream(reportFile, false).use { output ->
                output.write(report.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
        } catch (writeError: Throwable) {
            // Do not rely on Timber/async logging while the process is crashing.
            android.util.Log.e(TAG, "Unable to persist crash report", writeError)
        }
    }

    override fun onCreate() {
        super.onCreate()
        AnonConfig.context = this
        val secureWipePending = AnonConfig.isSecureWipePending(this)
        if (!secureWipePending) {
            AnonConfig.initWalletState()
        }
        startKoin {
            androidContext(this@AnonApplication)
            modules(appModule)
        }

        // A pending secure deletion has priority over all normal startup work.
        // In particular, do not initialize wallet2, start Tor, or create log files:
        // the recovery screen must finish deleting the existing app data first.
        if (secureWipePending) {
            Thread.setDefaultUncaughtExceptionHandler(this)
            return
        }

        initConfigs()
        plantLog()
        Thread.setDefaultUncaughtExceptionHandler(this)
        val torService: TorService = get()
        val prefs: SharedPreferences = get(named(WALLET_PREFERENCES))
        val host = prefs.getString(NodeFields.RPC_HOST.value, "")
        if (!host.isNullOrEmpty()) {
            torService.start()
        }
        AnonConfig.clearSpendCacheFiles(this)
    }

    private fun plantLog() {

        anonLogTree = AnonLogTree(AnonConfig.getLogFile(applicationContext), get<LogRepository>())

        if (BuildConfig.DEBUG) {
            Timber.plant(DebugTree())
            Timber.plant(anonLogTree)
        } else {
            Timber.plant(anonLogTree)
        }
        Timber.tag(TAG).i("BUILD_TYPE: %s", BuildConfig.BUILD_TYPE)
        Timber.tag(TAG).i("FLAVOR: %s", BuildConfig.FLAVOR)
        Timber.tag(TAG).i("APPLICATION_ID: %s", BuildConfig.APPLICATION_ID)
        Timber.tag(TAG).i("MONERO_NETWORK: %s", AnonConfig.getNetworkType())
        Timber.tag(TAG)
            .i("VERSION: ${BuildConfig.VERSION_NAME} ${BuildConfig.VERSION_CODE}\n\n")
    }

    private fun initConfigs() {
        //initialize wallet manager
        WalletManager.instance?.init()
        initNotificationChannels()
    }

    private fun initNotificationChannels() {
        val foregroundChannel = NotificationChannel(
            FOREGROUND_CHANNEL,
            getString(R.string.notification_channel_service),
            NotificationManager.IMPORTANCE_LOW,
        )
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(foregroundChannel)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                TX_CHANNEL,
                getString(R.string.notification_channel_transactions),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
    }

    override fun onTerminate() {
        val torService: TorService = get()
        torService.dispose()
        if (::anonLogTree.isInitialized) anonLogTree.cleanup()
        super.onTerminate()
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        // A wipe request has higher priority than crash reporting. Do not recreate
        // files after secure deletion has been requested or completed.
        if (!AnonConfig.isSecureWipePending(this)) {
            persistCrashReport(t, e)
        }
        Timber.tag(TAG).e(e)
        defaultHandler?.uncaughtException(t, e)
    }

}
