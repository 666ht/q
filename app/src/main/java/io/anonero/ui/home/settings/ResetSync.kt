package io.anonero.ui.home.settings

import AnonNeroTheme
import AnonOutlineButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.anonero.model.WalletManager
import io.anonero.services.WalletState
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResetSyncPage(
    onBackPress: () -> Unit = {}
) {
    val walletState = koinInject<WalletState>()
    val wallet = WalletManager.instance?.wallet
    val dateFormat = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            isLenient = false
        }
    }

    fun dateForTimestamp(timestamp: Long): String {
        return dateFormat.format(Date(timestamp * 1000L))
    }

    fun parseDate(value: String): Date? {
        if (value.length != 10) return null
        return try {
            dateFormat.parse(value)?.takeIf { dateFormat.format(it) == value }
        } catch (_: Exception) {
            null
        }
    }

    var height by remember {
        mutableStateOf(wallet?.getRestoreHeight()?.toString() ?: "")
    }
    var date by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var querying by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val heightToDateCache = remember(wallet) { mutableMapOf<Long, String>() }
    val dateToHeightCache = remember(wallet) { mutableMapOf<String, Long>() }

    val selectedHeight = height.toLongOrNull()
    val selectedDate = parseDate(date)
    val canReset = selectedHeight != null && selectedHeight >= 0L && selectedDate != null

    // Resolve the saved height's date in the background. Initialization must not
    // drive the button spinner or disable either input field.
    LaunchedEffect(wallet) {
        val initialHeight = height.toLongOrNull()
        val activeWallet = wallet
        if (activeWallet != null && initialHeight != null && initialHeight >= 0L && date.isBlank()) {
            try {
                val resolvedDate = heightToDateCache[initialHeight]
                    ?: withContext(Dispatchers.IO) {
                        val timestamp = runCatching {
                            activeWallet.getBlockTimestamp(initialHeight)
                        }.getOrDefault(0L)
                        if (timestamp > 0L) dateForTimestamp(timestamp) else null
                    }
                if (height.toLongOrNull() == initialHeight && date.isBlank()) {
                    if (resolvedDate != null) {
                        heightToDateCache[initialHeight] = resolvedDate
                        date = resolvedDate
                    } else {
                        error = "无法查询该高度的区块时间，请检查节点连接"
                    }
                }
            } catch (e: Exception) {
                if (height.toLongOrNull() == initialHeight && date.isBlank()) {
                    error = e.message ?: "查询失败，请检查节点连接后重试"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackPress) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                title = { Text("重置同步区块") }
            )
        },
        bottomBar = {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 56.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    AnonOutlineButton(
                        onClick = {
                            if (querying || resetting) return@AnonOutlineButton

                            val heightValue = height.toLongOrNull()
                            val dateValue = parseDate(date)
                            val readyToReset = heightValue != null &&
                                heightValue >= 0L &&
                                dateValue != null

                            if (readyToReset) {
                                resetting = true
                                error = null
                                coroutineScope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        // The selected height is the scan start; WalletState uses the current
                                        // daemon height as the end of the rescan.
                                        walletState.resetSyncFromHeight(heightValue!!)
                                    }
                                    resetting = false
                                    if (result.isSuccess) {
                                        onBackPress()
                                    } else {
                                        error = result.exceptionOrNull()?.message ?: "重置失败"
                                    }
                                }
                                return@AnonOutlineButton
                            }

                            val hasHeightInput = height.isNotBlank()
                            val hasDateInput = date.isNotBlank()
                            if (!hasHeightInput && !hasDateInput) {
                                error = "请输入高度或日期后查询"
                                return@AnonOutlineButton
                            }
                            if (hasHeightInput && hasDateInput) {
                                error = "请输入有效高度和日期"
                                return@AnonOutlineButton
                            }

                            val activeWallet = wallet
                            if (activeWallet == null) {
                                error = "钱包尚未就绪，无法查询"
                                return@AnonOutlineButton
                            }

                            val requestedHeight = height
                            val requestedDate = date
                            querying = true
                            error = null
                            coroutineScope.launch {
                                try {
                                    // Keep the spinner visible for a frame even when the lookup is cached.
                                    delay(80L)
                                    if (hasHeightInput) {
                                        val queryHeight = requestedHeight.toLongOrNull()
                                        if (queryHeight == null || queryHeight < 0L) {
                                            error = "请输入有效区块高度"
                                            return@launch
                                        }

                                        val resolvedDate = heightToDateCache[queryHeight]
                                            ?: withContext(Dispatchers.IO) {
                                                val timestamp = runCatching {
                                                    activeWallet.getBlockTimestamp(queryHeight)
                                                }.getOrDefault(0L)
                                                if (timestamp > 0L) dateForTimestamp(timestamp) else null
                                            }

                                        if (height != requestedHeight || date.isNotBlank()) return@launch
                                        if (resolvedDate == null) {
                                            error = "无法查询该高度的区块时间，请检查节点连接"
                                            return@launch
                                        }

                                        heightToDateCache[queryHeight] = resolvedDate
                                        date = resolvedDate
                                        error = null
                                    } else {
                                        val parsedDate = parseDate(requestedDate)
                                        if (parsedDate == null) {
                                            error = "请输入有效日期，格式为 yyyy-MM-dd"
                                            return@launch
                                        }

                                        val resolvedHeight = dateToHeightCache[requestedDate]
                                            ?: withContext(Dispatchers.IO) {
                                                runCatching {
                                                    val calendar = java.util.Calendar.getInstance().apply {
                                                        time = parsedDate
                                                        set(java.util.Calendar.HOUR_OF_DAY, 0)
                                                        set(java.util.Calendar.MINUTE, 0)
                                                        set(java.util.Calendar.SECOND, 0)
                                                        set(java.util.Calendar.MILLISECOND, 0)
                                                    }
                                                    activeWallet.getBlockChainHeightByDate(
                                                        calendar.get(java.util.Calendar.YEAR),
                                                        calendar.get(java.util.Calendar.MONTH) + 1,
                                                        calendar.get(java.util.Calendar.DAY_OF_MONTH)
                                                    )
                                                }.getOrNull()
                                            }

                                        if (date != requestedDate || height.isNotBlank()) return@launch
                                        if (resolvedHeight == null || resolvedHeight < 0L) {
                                            error = "无法查询该日期的区块高度，请检查节点连接或日期"
                                            return@launch
                                        }

                                        dateToHeightCache[requestedDate] = resolvedHeight
                                        height = resolvedHeight.toString()
                                        error = null
                                    }
                                } catch (e: Exception) {
                                    error = e.message ?: "查询失败，请检查节点连接后重试"
                                } finally {
                                    querying = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(.9f)
                    ) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.size(width = 64.dp, height = 24.dp),
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            if (querying || resetting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(if (canReset) "重置" else "查询")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(56.dp))

            OutlinedTextField(
                value = height,
                onValueChange = {
                    error = null
                    height = it.filter(Char::isDigit)
                    // A changed height invalidates the old date until the user runs Query.
                    date = ""
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
                enabled = !resetting && !querying,
                label = { Text("高度恢复") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Spacer(modifier = Modifier.height(56.dp))

            OutlinedTextField(
                value = date,
                onValueChange = {
                    error = null
                    date = it
                    // A changed date invalidates the old height until the user runs Query.
                    height = ""
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
                enabled = !resetting && !querying,
                label = { Text("日期恢复") },
                singleLine = true
            )

            if (error != null) {
                Text(
                    text = error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}

@Preview(device = "id:pixel_7_pro")
@Composable
private fun ResetSyncPagePreview() {
    AnonNeroTheme {
        ResetSyncPage()
    }
}
