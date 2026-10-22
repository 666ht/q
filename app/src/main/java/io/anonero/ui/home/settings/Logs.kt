package io.anonero.ui.home.settings

import AnonNeroTheme
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.rememberCoroutineScope
import io.anonero.BuildConfig
import java.io.File
import kotlinx.coroutines.flow.first
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.anonero.AnonConfig
import io.anonero.R
import io.anonero.icons.AnonIcons
import io.anonero.store.AnonLog
import io.anonero.store.LogRepository
import io.anonero.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import timber.log.Timber
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter


private const val TAG = "Logs"

class LogViewModel(private val logRepository: LogRepository) : ViewModel() {
    val logLines: StateFlow<List<AnonLog>> = logRepository
        .logFlow
        .map {
            it.reversed()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList()
        )


    fun clearLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            logRepository.clear()
        }
    }

    /**
     * Return the persisted uncaught-exception report after a crash. If the app has
     * not crashed since the previous clear/install, return device info plus current
     * log history so the user can still copy useful diagnostics.
     */
    suspend fun prepareCrashReportForCopy(context: Context): Pair<String, Boolean> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val crashFile = File(context.filesDir, "crash_report.txt")
            if (crashFile.isFile && crashFile.length() > 0L) {
                return@withContext crashFile.readText(Charsets.UTF_8) to true
            }

            val logs = logRepository.logFlow.first()
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            val logText = logs.joinToString(separator = "\n") { entry ->
                val dateTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(entry.date),
                    ZoneId.systemDefault()
                )
                val level = when (entry.priority) {
                    Log.VERBOSE -> "VERBOSE"
                    Log.DEBUG -> "DEBUG"
                    Log.INFO -> "INFO"
                    Log.WARN -> "WARN"
                    Log.ERROR -> "ERROR"
                    Log.ASSERT -> "ASSERT"
                    else -> "UNKNOWN"
                }
                "${formatter.format(dateTime)} | $level | ${entry.tag} | ${entry.message}"
            }

            buildString {
                appendLine("ANONERO DIAGNOSTIC LOG")
                appendLine("No saved uncaught-exception report was found.")
                appendLine("App ID: ${BuildConfig.APPLICATION_ID}")
                appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine()
                appendLine(logDeviceInfo())
                appendLine()
                appendLine("CURRENT LOG HISTORY")
                append(logText.ifBlank { "No log entries available." })
            } to false
        }

    private fun logDeviceInfo(): String {
        val deviceInfo = """
        Model          : ${Build.MODEL}
        Manufacturer   : ${Build.MANUFACTURER}
        Brand          : ${Build.BRAND}
        Device         : ${Build.DEVICE}
        OS Version     : ${Build.VERSION.RELEASE}
        SDK Version    : ${Build.VERSION.SDK_INT}
        Product        : ${Build.PRODUCT}
        Hardware       : ${Build.HARDWARE}
        Board          : ${Build.BOARD}
        Host           : ${Build.HOST}
    """.trimIndent()
        return deviceInfo
    }


    fun prePareForShare(): Job {
        return viewModelScope.launch {
            val logsAsString = logLines.map { list ->
                list.joinToString(separator = "\n") {
                    val instant =
                        Instant.ofEpochMilli(it.date)
                    val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                    val formatter = DateTimeFormatter.ofPattern("MMM dd hh:mm:ssa")
                    val level = when (it.priority) {
                        Log.VERBOSE -> "VERBOSE"
                        Log.DEBUG -> "DEBUG"
                        Log.INFO -> "INFO"
                        Log.WARN -> "WARN"
                        Log.ERROR -> "ERROR"
                        Log.ASSERT -> "ASSERT"
                        else -> "UNKNOWN"
                    }
                    "${formatter.format(dateTime)} | $level | ${it.tag} | ${it.message}"
                }
            }.stateIn(viewModelScope).value
            val logFile = AnonConfig.context?.let { AnonConfig.getLogFile(it) }
            if (logFile?.exists() == true) {
                logFile.writeText("")
                logFile.writeText(
                    "${'-'.toString().repeat(100)}\n\n" +
                            "${logDeviceInfo()}\n\n" +
                            "${'-'.toString().repeat(100)}\n" +
                            logsAsString
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewer(
    onBackPress: () -> Unit = {}
) {
    val viewModel = koinViewModel<LogViewModel>()
    val logLines by viewModel.logLines.collectAsState(emptyList())
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var reportDialogText by remember { mutableStateOf<String?>(null) }
    var reportDialogIsCrash by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.logs))
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackPress
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val (report, isCrashReport) =
                                        viewModel.prepareCrashReportForCopy(context)
                                    val clipboard = context.getSystemService(
                                        Context.CLIPBOARD_SERVICE
                                    ) as ClipboardManager
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText("Anonero crash report", report)
                                    )
                                    // The crash report is stored separately from the live log list.
                                    // Display its actual contents while keeping the one-tap copy action.
                                    reportDialogText = report
                                    reportDialogIsCrash = isCrashReport
                                    Toast.makeText(
                                        context,
                                        if (isCrashReport) "闪退日志已显示并复制" else "未找到保存的闪退记录，已显示并复制当前运行日志",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } catch (e: Exception) {
                                    Timber.tag(TAG).e(e, "Copy crash report failed")
                                    Toast.makeText(
                                        context,
                                        "复制日志失败",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "复制闪退日志"
                        )
                    }
                    IconButton(
                        onClick = {
                            viewModel.prePareForShare()
                                .invokeOnCompletion {
                                    Timber.tag(TAG).i(it)
                                    if (it == null) {
                                        val fileUri: Uri = try {
                                            FileProvider.getUriForFile(
                                                context, // Context
                                                "${context.packageName}.shareProvider", // Authority
                                                AnonConfig.getLogFile(context)
                                            )
                                        } catch (e: Exception) {
                                            Timber.tag(TAG).e(e)
                                            return@invokeOnCompletion
                                        }
                                        // Create the share intent
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "*/*"
                                            putExtra(Intent.EXTRA_STREAM, fileUri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) // Grant permission to the receiving app
                                        }
                                        context.startActivity(
                                            Intent.createChooser(
                                                shareIntent,
                                                context.getString(R.string.share_encrypted_backup_file)
                                            )
                                        )
                                    }
                                }
                        }
                    ) {
                        Icon(AnonIcons.Share_log, contentDescription = stringResource(R.string.share_logs))
                    }
                    IconButton(
                        onClick = {
                            viewModel.clearLogs()
                        }
                    ) {
                        Icon(AnonIcons.Clear_all, contentDescription = stringResource(R.string.clear_logs))
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier
                .padding(it)
                .padding(
                    horizontal = 8.dp
                )
                .fillMaxSize(),
            contentPadding = PaddingValues(0.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            items(logLines.size) { index ->
                LogLine(logLines[index])
            }
        }
    }

    reportDialogText?.let { report ->
        AlertDialog(
            onDismissRequest = { reportDialogText = null },
            title = {
                Text(if (reportDialogIsCrash) "最近一次闪退日志" else "当前诊断日志（没有保存的闪退记录）")
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = report,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runCatching {
                            val clipboard = context.getSystemService(
                                Context.CLIPBOARD_SERVICE
                            ) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("Anonero crash report", report)
                            )
                        }
                        Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("复制日志")
                }
            },
            dismissButton = {
                TextButton(onClick = { reportDialogText = null }) {
                    Text("关闭")
                }
            }
        )
    }
}

@Composable
private fun LogLine(text: AnonLog) {

    val color = when (text.priority) {
        Log.VERBOSE -> Color(0xFF4FC3F7)
        Log.DEBUG -> Color(0xFF81C784)
        Log.INFO -> Color(0xFFB0BEC5)
        Log.WARN -> Color(0xFFFFD54F)
        Log.ERROR -> Color(0xFFE57373)
        Log.ASSERT -> Color(0xFFA1887F)
        else -> Color(0xFF757575)
    }


    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                vertical = 0.dp,
                horizontal = 0.dp
            ),
        Arrangement.Start,

        verticalAlignment = Alignment.Top
    ) {
        Text(
            "${Formats.formatLogTime(text.date)}:  ",
            color = Color.Gray,
            fontSize = 8.sp,
            letterSpacing = 0.sp,
            modifier = Modifier
                .width(70.dp)
                .padding(0.dp)
        )
        Text(
            text.message,
            fontSize = 8.sp,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Light,
            maxLines = 30,
            lineHeight = 12.sp,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(0.dp)
                .background(Color.Transparent),
        )
    }


}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(device = "id:pixel_5")
@Composable
private fun LogViewerPrev() {
    AnonNeroTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(stringResource(R.string.logs))
                    },
                )
            }
        ) {
            LazyColumn(
                reverseLayout = true,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(it)
                    .padding(
                        horizontal = 8.dp, vertical = 0.dp
                    )
            ) {
                item {
                    LogLine(
                        AnonLog(
                            1734877537,
                            "Test",
                            "Compat change id reported: 171228096; UID 10452; state: ENABLED\n                                                                                                    Local Branch                     : \n                                                                                                    Remote Branch                    : \n                                                                                                    Remote Branch                    : ",
                            4
                        )
                    )
                }
                item {
                    LogLine(
                        AnonLog(
                            1734877537,
                            "Test",
                            " setDaemonAddressJ(): end",
                            4
                        )
                    )
                }
                item {
                    LogLine(
                        AnonLog(
                            1734877537,
                            "Test",
                            "Installing profile for io.anonero.anon",
                            2
                        )
                    )
                }
            }
        }

    }
}