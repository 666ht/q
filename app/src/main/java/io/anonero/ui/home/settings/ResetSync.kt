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
import androidx.compose.foundation.layout.weight
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

    fun heightForDate(value: String): Long? {
        return try {
            val parsed = dateFormat.parse(value)?.time ?: return null
            val secondsAgo = ((System.currentTimeMillis() - parsed) / 1000L).coerceAtLeast(0L)
            (currentHeight - secondsAgo / BLOCK_TIME_SECONDS).coerceAtLeast(0L)
        } catch (_: Exception) {
            null
        }
    }

    var height by remember {
        mutableStateOf(wallet?.getRestoreHeight()?.toString() ?: "")
    }
    var date by remember {
        mutableStateOf(wallet?.getRestoreHeight()?.let(::dateForHeight) ?: "")
    }
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
                    heightForDate(it)?.let { h -> height = h.toString() }
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

            Spacer(modifier = Modifier.weight(1f))

            AnonOutlineButton(
                onClick = {
                    val restoreHeight = height.toLongOrNull()
                    if (restoreHeight == null) {
                        error = "请输入有效高度或日期"
                        return@AnonOutlineButton
                    }
                    val result = walletState.resetSyncFromHeight(restoreHeight)
                    if (result.isSuccess) {
                        onBackPress()
                    } else {
                        error = result.exceptionOrNull()?.message ?: "重置失败"
                    }
                },
                modifier = Modifier
                    .fillMaxWidth(.9f)
                    .padding(bottom = 16.dp)
            ) {
                Text("重置")
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
