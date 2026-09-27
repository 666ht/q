package io.anonero.ui.home.settings

import AnonNeroTheme
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
                                                stringResource(R.string.share_encrypted_backup_file)
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