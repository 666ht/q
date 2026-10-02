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
import kotlinx.coroutines.Job
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

    var height by remember {
        mutableStateOf(wallet?.getRestoreHeight()?.toString() ?: "")
    }
    var date by remember { mutableStateOf("") }
    var selectedDateEndHeight by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var heightDateJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(wallet) {
        val restoreHeight = wallet?.getRestoreHeight()
        if (restoreHeight != null) {
            val timestamp = withContext(Dispatchers.IO) {
                runCatching { wallet.getBlockTimestamp(restoreHeight) }.getOrDefault(0L)
            }
            if (timestamp > 0L && height == restoreHeight.toString()) {
                date = dateForTimestamp(timestamp)
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
                    val restoreHeight = height.toLongOrNull()
                    if (restoreHeight == null) {
                        error = "请输入有效高度或日期"
                        return@AnonOutlineButton
                    }
                    val result = walletState.resetSyncFromHeight(restoreHeight, selectedDateEndHeight)
                    if (result.isSuccess) {
                        onBackPress()
                    } else {
                        error = result.exceptionOrNull()?.message ?: "重置失败"
                    }
                },
                        modifier = Modifier.fillMaxWidth(.9f)
                    ) {
                        Text("重置")
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
                    heightDateJob?.cancel()
                    height = it.filter(Char::isDigit)
                    selectedDateEndHeight = null
                    height.toLongOrNull()?.let { h ->
                        wallet?.let { activeWallet ->
                            heightDateJob = coroutineScope.launch {
                                delay(150L)
                                val timestamp = withContext(Dispatchers.IO) {
                                    runCatching {
                                        activeWallet.getBlockTimestamp(h)
                                    }.getOrDefault(0L)
                                }
                                if (timestamp > 0L && height == h.toString()) {
                                    date = dateForTimestamp(timestamp)
                                }
                            }
                        }
                    }
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("高度恢复") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Spacer(modifier = Modifier.height(56.dp))

            OutlinedTextField(
                value = date,
                onValueChange = {
                    error = null
                    heightDateJob?.cancel()
                    date = it
                    heightDateJob?.cancel()
                    selectedDateEndHeight = null
                    if (it.length == 10) {
                        wallet?.let { activeWallet ->
                            val requestedDate = it
                            heightDateJob = coroutineScope.launch {
                                delay(150L)
                                val range = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                                            isLenient = false
                                        }
                                        val selected = formatter.parse(requestedDate)
                                            ?: throw IllegalArgumentException("invalid date")
                                        val calendar = java.util.Calendar.getInstance().apply {
                                            time = selected
                                            set(java.util.Calendar.HOUR_OF_DAY, 0)
                                            set(java.util.Calendar.MINUTE, 0)
                                            set(java.util.Calendar.SECOND, 0)
                                            set(java.util.Calendar.MILLISECOND, 0)
                                        }

                                        val startHeight = activeWallet.getBlockChainHeightByDate(
                                            calendar.get(java.util.Calendar.YEAR),
                                            calendar.get(java.util.Calendar.MONTH) + 1,
                                            calendar.get(java.util.Calendar.DAY_OF_MONTH)
                                        )

                                        calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
                                        val endHeight = activeWallet.getBlockChainHeightByDate(
                                            calendar.get(java.util.Calendar.YEAR),
                                            calendar.get(java.util.Calendar.MONTH) + 1,
                                            calendar.get(java.util.Calendar.DAY_OF_MONTH)
                                        )

                                        startHeight to endHeight
                                    }.getOrNull()
                                }

                                if (range != null && date == requestedDate) {
                                    height = range.first.toString()
                                    selectedDateEndHeight = range.second
                                }
                            }
                        }
                    }
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
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
