package io.anonero.services

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.provider.Settings
import androidx.core.app.NotificationCompat
import io.anonero.FOREGROUND_CHANNEL
import io.anonero.TX_CHANNEL
import io.anonero.R
import io.anonero.model.Wallet
import io.anonero.model.WalletManager
import io.anonero.model.node.NodeFields
import io.anonero.store.NodesRepository
import io.anonero.ui.MainActivity
import io.anonero.util.Formats
import io.anonero.util.WALLET_PREFERENCES
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.util.Locale


const val NOTIFICATION_ID = 2

fun startAnonService(context: Context) {
    val intent = Intent(context, AnonNeroService::class.java).apply {
        action = "start"
    }
    context.startForegroundService(intent)
}

class AnonNeroService : Service() {

    private val TAG: String = AnonNeroService::class.java.simpleName
    private val job = SupervisorJob()
    private var updateJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    private val walletState: WalletState by inject(WalletState::class.java)
    private val torService: TorService by inject(TorService::class.java)
    private val prefs: SharedPreferences by inject(named(WALLET_PREFERENCES))

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "start") {
            runCatching { start() }
                .onFailure {
                    Timber.tag(TAG).e(it, "Failed to start foreground notification service")
                    stopSelfResult(startId)
                }
        }
        if (intent?.action == "stop") {
            stopForeground(STOP_FOREGROUND_REMOVE) // Properly removes the notification
            stopSelf() // Stops the service completely
            return START_NOT_STICKY
        }
        return START_STICKY
    }


    private fun start() {
        val notification = foregroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        scope.launch {
            walletState.walletConnectionStatus.collect {
                updateNotificationState()
            }
            walletState.walletStatus.collect {
                updateNotificationState()
            }
        }
        // Update notification state every 2 seconds
        updateJob = scope.launch {
            while (scope.isActive) {
                updateNotificationState()
                delay(1000)
            }
        }
        scope.launch {
            walletState.incomingTx.collect {
                postIncomingTxNotification()
            }
        }
        scope.launch {
            walletState.syncProgress.collect {
                val torSate = if (torService.socks != null) {
                    " | Tor 守护进程：${torService.socks?.port.toString()}"
                } else {
                    ""
                }
                if (it != null) {
                    withContext(Dispatchers.Main) {
                        showProgress(it, torSate)
                    }
                }
            }
        }
    }

    private suspend fun updateNotificationState() {
        val mNotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val wallet = WalletManager.instance?.wallet
        val daemon = prefs.getString(NodeFields.RPC_HOST.value, "") ?: ""
        var torSate = if (torService.socks != null) {
            " | Tor 守护进程：${torService.socks?.port.toString()}"
        } else {
            ""
        }
        if (wallet != null) {
            val isSyncing = walletState.isSyncing
            if (!isSyncing) {
                val notificationMessage = if (walletState.backgroundSync) {
                    getString(R.string.notification_wallet_locked_synced, wallet.getBlockChainHeight())
                } else if (!wallet.isInitialized) {
                    getString(R.string.notification_loading_wallet)
                } else if (!isNetworkAvailable()) {
                    getString(R.string.notification_disconnected)
                } else {
                    // Do not probe the daemon here. Node connection is controlled
                    // only by the manual Connect action in Node Settings.
                    when (wallet.fullStatus.connectionStatus) {
                        Wallet.ConnectionStatus.ConnectionStatus_Disconnected,
                        null -> getString(R.string.notification_daemon_disconnected)
                        Wallet.ConnectionStatus.ConnectionStatus_WrongVersion ->
                            getString(R.string.notification_wrong_version)
                        Wallet.ConnectionStatus.ConnectionStatus_Connected -> {
                            if (wallet.getBlockChainHeight() > 1) {
                                getString(R.string.notification_synced, wallet.getBlockChainHeight())
                            } else {
                                getString(R.string.notification_syncing)
                            }
                        }
                    }
                }
                if (daemon.isEmpty()) {
                    torSate = ""
                }
                withContext(Dispatchers.Main) {
                    mNotificationManager.notify(
                        NOTIFICATION_ID,
                        foregroundNotification("${notificationMessage}${torSate}")
                    )
                }
            }
        }
    }

    private fun showProgress(it: SyncProgress, torSate: String) {
        val content = if (it.left != 0L) {
            getString(
                R.string.notification_syncing_blocks_left,
                Formats.convertNumber(it.left, Locale.getDefault()),
                torSate
            )
        } else {
            getString(R.string.notification_sync_completed, torSate)
        }
        val notification = foregroundNotification(content = content, progress = it)
        val mNotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mNotificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun foregroundNotification(
        content: String = getString(R.string.notification_loading_wallet),
        title: String = "[ΛИ0ИΞR0]",
        progress: SyncProgress? = null
    ): Notification {

        val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this.applicationContext, FOREGROUND_CHANNEL)
            .setSmallIcon(R.drawable.anon_notification)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .setContentTitle(title)
            .setContentText(content)
            .apply {
                if (progress != null && progress.progress < 1) {
                    this.setProgress(100, (progress.progress * 100).toInt(), false)
                }
            }
            .setGroup("BackgroundService")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun postIncomingTxNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 1, mainActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, TX_CHANNEL)
            .setSmallIcon(R.drawable.anon_notification)
            .setContentTitle("[ΛИ0ИΞR0]")
            .setContentText(getString(R.string.notification_transaction_received))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(NOTIFICATION_ID + 1, notification)
    }

    override fun onDestroy() {
        updateJob?.cancel()
        Timber.tag(TAG).i("onDestroy: ")
        super.onDestroy()
        job.cancel()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.tag(TAG).i("onTaskRemoved: ")
        torService.dispose()
        updateJob?.cancel()
        job.cancel()
        super.onTaskRemoved(rootIntent)
    }
}
