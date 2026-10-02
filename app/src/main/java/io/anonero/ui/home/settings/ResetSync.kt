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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

private const val BLOCK_TIME_SECONDS = 120L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResetSyncPage(
    onBackPress: () -> Unit = {}
) {
    val walletState = koinInject<WalletState>()
    val wallet = WalletManager.instance?.wallet
    val currentHeight = remember(wallet) {
        wallet?.getBlockChainHeight()?.takeIf { it > 0L } ?: 0L
    }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

    fun dateForHeight(value: Long): String {
        val secondsAgo = (currentHeight - value).coerceAtLeast(0L) * BLOCK_TIME_SECONDS
        return dateFormat.format(Date(System.currentTimeMillis() - secondsAgo * 1000L))
    }

    fun heightRangeForDate(value: String): Pair<Long, Long>? {
        return try {
            val parsed = dateFormat.parse(value) ?: return null
            val calendar = java.util.Calendar.getInstance().apply {
                time = parsed
            }
            val year = calendar.get(java.util.Calendar.YEAR)
            val month = calendar.get(java.util.Calendar.MONTH) + 1
            val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)

            // Use Monero's real block timestamps. The selected height is the
            // first block of that calendar day and the end is the last block
            // before the next calendar day.
            val dayStartHeight = wallet?.getBlockChainHeightByDate(year, month, day) ?: return null
            val nextDay = (calendar.clone() as java.util.Calendar).apply {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            val nextDayHeight = wallet.getBlockChainHeightByDate(
                nextDay.get(java.util.Calendar.YEAR),
                nextDay.get(java.util.Calendar.MONTH) + 1,
                nextDay.get(java.util.Calendar.DAY_OF_MONTH)
            )
            val dayEndHeight = (nextDayHeight - 1L).coerceAtLeast(dayStartHeight)

            dayStartHeight.coerceAtLeast(0L) to dayEndHeight
        } catch (_: Exception) {
            null
        }
    }

    fun heightForDate(value: String): Long? = heightRangeForDate(value)?.first

    var height by remember {
        mutableStateOf(wallet?.getRestoreHeight()?.toString() ?: "")
    }
    var date by remember {
        mutableStateOf(wallet?.getRestoreHeight()?.let(::dateForHeight) ?: "")
    }
    var selectedDateEndHeight by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

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
                    height = it.filter(Char::isDigit)
                    selectedDateEndHeight = null
                    height.toLongOrNull()?.let { h -> date = dateForHeight(h) }
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
                    date = it
                    heightRangeForDate(it)?.let { range ->
                        height = range.first.toString()
                        selectedDateEndHeight = range.second
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
