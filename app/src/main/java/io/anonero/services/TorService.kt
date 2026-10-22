package io.anonero.services

import android.content.SharedPreferences
import io.anonero.AnonConfig
import io.matthewnelson.kmp.tor.runtime.Action.Companion.restartDaemonAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.stopDaemonAsync
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.OnEvent
import io.matthewnelson.kmp.tor.runtime.core.TorEvent
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.net.IPSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber


private const val TAG = "TorService"

class TorService(prefs: SharedPreferences) {

    val scope = CoroutineScope(SupervisorJob())

    private val _socksSharedFlow =
        MutableSharedFlow<IPSocketAddress>(replay = 1)

    private val _torLogs =
        MutableSharedFlow<String>(
            replay = 20, onBufferOverflow = BufferOverflow.DROP_OLDEST,
            extraBufferCapacity = 50
        )
    private var _socks: IPSocketAddress? = null
    val socks get() = _socks
    val socksFlow get() = _socksSharedFlow
    val torLogs: Flow<String> get() = _torLogs.asSharedFlow()

    val runtime = TorRuntime.Builder(environment = AnonConfig.getTorConfig(scope)) {

        TorEvent.entries().forEach { event ->
            if (event == TorEvent.ERR)
                observerStatic(event, OnEvent.Executor.Immediate) { data ->
                    _torLogs.tryEmit(data)
                }
        }
        RuntimeEvent.entries().forEach { event ->
            observerStatic(event, OnEvent.Executor.Immediate) { data ->
                _torLogs.tryEmit(data.toString())
            }
        }
        observerStatic(RuntimeEvent.LISTENERS) {
            val socketAddr = it.socks.firstOrNull()
            if (socketAddr != null) {
                _socks = socketAddr
                scope.launch {
                    _socksSharedFlow.emit(socketAddr) // Ensure the value is emitted
                }
            }
        }
        config { _ ->
            TorOption.SocksPort.configure { auto() }
        }
        required(TorEvent.ERR)
        required(TorEvent.WARN)
        required(TorEvent.INFO)
    }

    fun start() {
        scope.launch {
            runtime.startDaemonAsync()
        }
    }

    fun stop() {
        _socks = null
        scope.launch {
            runtime.stopDaemonAsync()
        }
    }

    /**
     * Secure deletion must not remove Tor's work/cache directories while the
     * daemon is still stopping. The runtime action is suspending: completion means
     * StopDaemon has finished and any active StartDaemon action was interrupted.
     */
    suspend fun stopAndWait() {
        _socks = null
        runtime.stopDaemonAsync()
        Timber.tag(TAG).i("Tor daemon stopped before secure-delete filesystem cleanup")
    }


    fun restart() {
        scope.launch {
            runtime.restartDaemonAsync()
        }
    }

    fun dispose() {
        scope.launch {
            runtime.stopDaemonAsync()
        }.invokeOnCompletion {
            it?.let {
                Timber.tag(TAG).i("Tor dispose exception: ${it.message}")
            }
            scope.cancel()
        }
    }
}
