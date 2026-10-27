package io.anonero.ui.home.settings

import AnonNeroTheme
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import timber.log.Timber
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter


private const val TAG = "Logs"

class LogViewModel(private val logRepository: LogRepository) : ViewModel() {
    private val fallbackFileLogs = MutableStateFlow<List<AnonLog>>(emptyList())
    private val logsCleared = MutableStateFlow(false)

    init {
        // The rolling file is written synchronously by AnonLogTree. Use it as a
        // fallback when DataStore is empty/corrupt so the diagnostics screen remains
        // useful even if persisted log updates failed.
        viewModelScope.launch(Dispatchers.IO) {
            if (!logsCleared.value) {
                fallbackFileLogs.value = readFallbackFileLogs()
            }
        }
    }

    val logLines: StateFlow<List<AnonLog>> = combine(
        logRepository.logFlow,
        fallbackFileLogs,
        logsCleared
    ) { stored, fallback, cleared ->
        (when {
            stored.isNotEmpty() -> stored
            cleared -> emptyList()
            else -> fallback
        }).reversed()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = emptyList()
    )

    private suspend fun readFallbackFileLogs(): List<AnonLog> = withContext(Dispatchers.IO) {
        val context = AnonConfig.context ?: return@withContext emptyList()
        val file = runCatching { AnonConfig.getLogFile(context) }.getOrNull()
            ?: return@withContext emptyList()
        if (!file.isFile) return@withContext emptyList()

        val recordPattern = Regex(
            """^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) \| (-?\d+) \| ([^|]+) \| ?(.*)$"""
        )
        val parsed = mutableListOf<AnonLog>()
        var recordDate = 0L
        var recordPriority = Log.INFO
        var recordTag = ""
        var recordMessage = StringBuilder()

        fun flushRecord() {
            if (recordDate != 0L) {
                parsed += AnonLog(
                    date = recordDate,
                    tag = recordTag,
                    message = recordMessage.toString(),
                    priority = recordPriority
                )
            }
        }

        runCatching {
            file.forEachLine(Charsets.UTF_8) { line ->
                val match = recordPattern.matchEntire(line)
                if (match != null) {
                    flushRecord()
                    recordDate = runCatching {
                        java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss.SSS",
                            java.util.Locale.US
                        ).parse(match.groupValues[1])?.time ?: 0L
                    }.getOrDefault(0L)
                    recordPriority = match.groupValues[2].toIntOrNull() ?: Log.INFO
                    recordTag = match.groupValues[3].trim()
                    recordMessage = StringBuilder(match.groupValues[4])
                } else if (recordDate != 0L) {
                    recordMessage.append('\n').append(line)
                }
            }
            flushRecord()
        }
        parsed
    }

    fun getLogsText(): String {
        val logs = logLines.value
        val deviceInfo = logDeviceInfo()
        val lines = logs.joinToString(separator = "\n") {
            val instant = Instant.ofEpochMilli(it.date)
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
        return "${'-'.toString().repeat(100)}\n\n$deviceInfo\n\n${'-'.toString().repeat(100)}\n$lines"
    }
    fun clearLogs() {
        logsCleared.value = true
        fallbackFileLogs.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            logRepository.clear()
        }
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

private fun PointerEvent.averageTouchRadius(useCurrent: Boolean): Float {
    val points = changes
        .filter { if (useCurrent) it.pressed else it.previousPressed }
        .map { if (useCurrent) it.position else it.previousPosition }
    if (points.size < 2) return 0f

    val centerX = (points.sumOf { it.x.toDouble() } / points.size).toFloat()
    val centerY = (points.sumOf { it.y.toDouble() } / points.size).toFloat()
    val radiusTotal = points.sumOf { point ->
        val dx = (point.x - centerX).toDouble()
        val dy = (point.y - centerY).toDouble()
        kotlin.math.sqrt(dx * dx + dy * dy)
    }
    return (radiusTotal / points.size).toFloat()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewer(
    onBackPress: () -> Unit = {}
) {
    val viewModel = koinViewModel<LogViewModel>()
    val logLines by viewModel.logLines.collectAsState(emptyList())
    val listState = rememberLazyListState()
    val horizontalScrollState = rememberScrollState()
    var textScale by remember { mutableStateOf(1f) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    val context = LocalContext.current
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
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText("ANONERO Logs", viewModel.getLogsText())
                            )
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Logs")
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
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial
                        )
                        var previousCentroidSize = 0f
                        var multiTouchInProgress = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressedCount = event.changes.count { it.pressed }

                            if (pressedCount >= 2) {
                                val currentCentroidSize =
                                    event.averageTouchRadius(useCurrent = true)
                                if (
                                    multiTouchInProgress &&
                                    previousCentroidSize > 0f &&
                                    currentCentroidSize > 0f
                                ) {
                                    val zoomChange = currentCentroidSize / previousCentroidSize
                                    if (zoomChange.isFinite() && kotlin.math.abs(zoomChange - 1f) > 0.002f) {
                                        textScale = (textScale * zoomChange).coerceIn(0.75f, 2.5f)
                                    }
                                }
                                multiTouchInProgress = true
                                previousCentroidSize = currentCentroidSize
                                // Take ownership of pinch events before text-selection gestures
                                // see them, so zoom remains reliable even when starting on text.
                                event.changes.forEach { change ->
                                    if (change.pressed || change.previousPressed) change.consume()
                                }
                            } else if (multiTouchInProgress) {
                                // Don't let the remaining finger turn the end of a pinch into
                                // a long-press selection or horizontal scroll.
                                event.changes.forEach { change ->
                                    if (change.pressed || change.previousPressed) change.consume()
                                }
                                previousCentroidSize = 0f
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            val viewportWidth = maxWidth
            // Measure the longest actual line instead of guessing its width from
            // character count. This keeps log rows on one line while zooming and avoids
            // accidental wrapping that pushes part of a row onto the next line.
            val longestMessageLine = remember(logLines) {
                logLines.asSequence()
                    .flatMap { entry -> entry.message.lineSequence() }
                    .maxByOrNull { it.length }
                    .orEmpty()
            }
            val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
            val longestMessageWidth = textMeasurer.measure(
                text = longestMessageLine,
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = (8f * textScale).sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light
                ),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            ).size.width
            val density = LocalDensity.current
            val logContentWidth = with(density) {
                maxOf(
                    viewportWidth,
                    viewportWidth * textScale,
                    (longestMessageWidth + (70f * textScale).dp.toPx() + 16.dp.toPx()).toDp()
                )
            }
            if (logLines.isEmpty()) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopStart
                ) {
                    Text(
                        text = "暂无日志记录",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                SelectionContainer(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalScroll(horizontalScrollState)
                    ) {
                        LazyColumn(
                            state = listState,
                            reverseLayout = true,
                            modifier = Modifier
                                .width(logContentWidth)
                                .fillMaxHeight()
                                .padding(horizontal = 8.dp),
                            contentPadding = PaddingValues(0.dp),
                            verticalArrangement = Arrangement.Top
                        ) {
                            items(logLines.size) { index ->
                                LogLine(logLines[index], textScale)
                            }
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun LogLine(text: AnonLog, textScale: Float = 1f) {
    // The parent SelectionContainer covers all rows so users can select across log lines.
    LogLineContent(text, textScale)
}

@Composable
private fun LogLineContent(text: AnonLog, textScale: Float = 1f) {

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
            fontSize = (8f * textScale).sp,
            letterSpacing = 0.sp,
            modifier = Modifier
                .width((70f * textScale).dp)
                .padding(0.dp)
        )
        Text(
            text.message,
            fontSize = (8f * textScale).sp,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Light,
            maxLines = 30,
            softWrap = false,
            lineHeight = (12f * textScale).sp,
            overflow = TextOverflow.Clip,
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
                        Text("Logs")
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