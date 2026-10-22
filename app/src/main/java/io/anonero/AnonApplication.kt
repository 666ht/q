package io.anonero

import android.app.Application
import android.app.ApplicationExitInfo
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
import androidx.core.content.edit
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
    @Volatile
    private var secureWipeStartedInThisProcess = false

    fun markSecureWipeStartedInProcess() {
        secureWipeStartedInThisProcess = true
    }

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

    /**
     * Java's uncaught-exception handler cannot see native SIGSEGV/abort exits.
     * On Android 11+, recover the system's last crash/native-crash/ANR record
     * on next startup when no matching Java crash report was already written.
     */
    private fun persistPreviousCrashReportIfAny() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (AnonConfig.isSecureWipePending(this)) return

        val activityManager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return
        val recentExits = runCatching {
            activityManager.getHistoricalProcessExitReasons(packageName, 0, 10)
        }.getOrDefault(emptyList())

        val crashExit = recentExits
            .filter {
                it.reason == ApplicationExitInfo.REASON_CRASH ||
                    it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    it.reason == ApplicationExitInfo.REASON_ANR
            }
            .maxByOrNull { it.timestamp }
            ?: return

        val preferences = getSharedPreferences("crash_report_state", Context.MODE_PRIVATE)
        val processedTimestamp = preferences.getLong("last_processed_exit_timestamp", 0L)
        if (crashExit.timestamp <= processedTimestamp) return

        val reportFile = File(filesDir, "crash_report.txt")
        // The Java handler writes its own detailed stack trace just before the
        // process exits. Preserve it if its timestamp matches this same event.
        val javaReportAlreadySaved = reportFile.isFile &&
            reportFile.length() > 0L &&
            reportFile.lastModified() >= crashExit.timestamp - 30_000L

        if (!javaReportAlreadySaved) {
            val reasonText = when (crashExit.reason) {
                ApplicationExitInfo.REASON_CRASH -> "Java/Kotlin crash"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                ApplicationExitInfo.REASON_ANR -> "Application Not Responding (ANR)"
                else -> "Unknown crash"
            }
            val trace = runCatching {
                crashExit.traceInputStream?.use { input ->
                    input.readBytes().let { bytes ->
                        val boundedBytes = if (bytes.size > 1_000_000) {
                            bytes.copyOf(1_000_000)
                        } else {
                            bytes
                        }
                        boundedBytes.toString(Charsets.UTF_8)
                    }
                }.orEmpty()
            }.getOrDefault("System trace unavailable.")

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
                .format(Date(crashExit.timestamp))
            val report = buildString {
                appendLine("ANONERO CRASH REPORT (recovered from Android)")
                appendLine("Time: $timestamp")
                appendLine("Exit reason: $reasonText")
                appendLine("System description: ${crashExit.description.orEmpty()}")
                appendLine("App ID: ${BuildConfig.APPLICATION_ID}")
                appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} / ${Build.DEVICE}")
                appendLine("Product: ${Build.PRODUCT}; Hardware: ${Build.HARDWARE}; ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
                appendLine()
                appendLine("Android system trace:")
                append(trace)
            }
            try {
                FileOutputStream(reportFile, false).use { output ->
                    output.write(report.toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
            } catch (writeError: Exception) {
                android.util.Log.e(TAG, "Unable to recover system crash report", writeError)
                return
            }
        }

        preferences.edit(commit = true) {
            putLong("last_processed_exit_timestamp", crashExit.timestamp)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Install crash persistence before wallet/native initialization can fail.
        Thread.setDefaultUncaughtExceptionHandler(this)
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
            return
        }

        persistPreviousCrashReportIfAny()
        initConfigs()
        plantLog()
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
        runCatching {
            if (!secureWipeStartedInThisProcess && !AnonConfig.isSecureWipePending(this)) {
                persistCrashReport(t, e)
            }
        }.onFailure {
            android.util.Log.e(TAG, "Unable to check crash-report policy", it)
        }
        runCatching { Timber.tag(TAG).e(e) }
        defaultHandler?.uncaughtException(t, e)
    }

}
