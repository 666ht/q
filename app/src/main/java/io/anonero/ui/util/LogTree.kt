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
    private val fileLock = Any()

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val timestamp = Date().time
        val errorMessage = if (t == null) message else "$message\n${t.stackTraceToString()}"
        val safeTag = tag ?: "Anon"

        // Write to a small rolling file immediately. DataStore writes are asynchronous,
        // so without this file a fast native crash can lose the most recent log context.
        try {
            appendToFile(timestamp, priority, safeTag, errorMessage)
        } catch (_: Exception) {
            // Logging must never crash the app or recursively invoke Timber.
        }

        ioScope.launch {
            try {
                logRepository.addItem(
                    AnonLog(
                        date = timestamp,
                        message = errorMessage,
                        priority = priority,
                        tag = safeTag
                    )
                )
            } catch (_: Exception) {
                // Keep logging failures isolated from app startup and wallet operations.
            }
        }
    }

    private fun appendToFile(timestamp: Long, priority: Int, tag: String, message: String) {
        synchronized(fileLock) {
            logFile.parentFile?.mkdirs()
            val time = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                java.util.Locale.US
            ).format(Date(timestamp))
            logFile.appendText("$time | $priority | $tag | $message\n")

            // Keep enough context for crash diagnosis without allowing logs to grow forever.
            val maxBytes = 512 * 1024
            val keepBytes = 384 * 1024
            if (logFile.length() > maxBytes) {
                val bytes = logFile.readBytes()
                var first = (bytes.size - keepBytes).coerceAtLeast(0)
                while (first < bytes.size && bytes[first] != '\n'.code.toByte()) {
                    first++
                }
                if (first < bytes.size) first++
                logFile.writeBytes(bytes.copyOfRange(first, bytes.size))
            }
        }
    }

    fun cleanup() {
        ioScope.cancel()
    }

    companion object {
        private const val TAG = "LogTree"
    }
}