package io.anonero.ui.util

import io.anonero.store.AnonLog
import io.anonero.store.LogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.Date


class AnonLogTree(
    private val logFile: File,
    private val logRepository: LogRepository
) : Timber.DebugTree() {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        ioScope.launch {
            try {
                appendLog(tag, message, t, priority)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e)
            }
        }
    }


    private fun appendLog(tag: String?, message: String, t: Throwable?, priority: Int) {
        var errorMessage = message
        t?.let { throwable ->
            errorMessage = "${message}\n ${throwable.stackTraceToString()}\n"
        }

        ioScope.launch {
            logRepository.addItem(
                AnonLog(
                    date = Date().time,
                    message = errorMessage,
                    priority = priority,
                    tag = tag ?: "Anon"
                )
            )
        }
    }

    fun cleanup() {
        ioScope.cancel()
    }

    companion object {
        private const val TAG = "LogTree"
    }
}