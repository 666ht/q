package io.anonero.ui.home

import AnonNeroTheme
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import AnonOutlineButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.anonero.services.WalletState
import io.anonero.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private fun abbreviatedValue(value: String): String {
    if (value.length <= 24) return value
    return value.take(10) + "..." + value.takeLast(10)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoinDetailScreen(
    coinKey: String,
    onBackPress: () -> Unit = {},
) {
    val walletState = koinInject<WalletState>()
    val coins by walletState.coins.collectAsState(arrayListOf())
    val hideAmounts by walletState.hideAmountsFlow.collectAsState(false)
    val coinIndex = coins.indexOfFirst { it.key == coinKey }
    val coin = coins.getOrNull(coinIndex)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pubKeyState by remember { mutableStateOf(0) }
    var hashState by remember { mutableStateOf(0) }
    var addressState by remember { mutableStateOf(0) }
    val outputAddress = coin?.let { walletState.getAddressForCoin(it.hash) }
    val context = LocalContext.current
    val outputColor = if (coin?.frozen == true) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.primary
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("冻结") },
                navigationIcon = {
                    IconButton(onClick = onBackPress) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "全局索引  " + (coin?.globalOutputIndex ?: "____"),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    color = outputColor,
                    style = MaterialTheme.typography.titleMedium
                )
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        coin?.let {
                            if (hideAmounts) Formats.maskAmount(it.amount)
                            else Formats.getDisplayAmount(it.amount)
                        } ?: "____",
                        modifier = Modifier.fillMaxWidth(),
                        color = outputColor,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Start
                    )
                    Text(
                        "输出 " + if (coinIndex >= 0) coinIndex + 1 else "",
                        modifier = Modifier.align(Alignment.CenterStart),
                        color = outputColor,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .size(28.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        androidx.compose.foundation.layout.Spacer(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .size(28.dp)
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "公钥",
                        modifier = Modifier.size(width = 52.dp, height = 24.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = coin?.let {
                            if (pubKeyState == 1) it.pub_key else abbreviatedValue(it.pub_key)
                        } ?: "____",
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = coin != null) {
                                when (pubKeyState) {
                                    0 -> pubKeyState = 1
                                    1 -> {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("公钥", coin?.pub_key.orEmpty()))
                                        pubKeyState = 2
                                    }
                                    else -> pubKeyState = 0
                                }
                            },
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "哈希",
                        modifier = Modifier.size(width = 52.dp, height = 24.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = coin?.let {
                            if (hashState == 1) it.hash else abbreviatedValue(it.hash)
                        } ?: "____",
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = coin != null) {
                                when (hashState) {
                                    0 -> hashState = 1
                                    1 -> {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("哈希", coin?.hash.orEmpty()))
                                        hashState = 2
                                    }
                                    else -> hashState = 0
                                }
                            },
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "地址",
                        modifier = Modifier.size(width = 52.dp, height = 24.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = outputAddress?.let {
                            if (addressState == 1) it else abbreviatedValue(it)
                        } ?: "____",
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = outputAddress != null) {
                                when (addressState) {
                                    0 -> addressState = 1
                                    1 -> {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("地址", outputAddress.orEmpty()))
                                        addressState = 2
                                    }
                                    else -> addressState = 0
                                }
                            },
                        maxLines = 1,
                        textAlign = TextAlign.Start,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            AnonOutlineButton(
                onClick = {
                    val selectedCoin = coin ?: return@AnonOutlineButton
                    if (busy) return@AnonOutlineButton
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        try {
                            if (selectedCoin.frozen) {
                                walletState.thawCoin(selectedCoin.pub_key)
                            } else {
                                walletState.freezeCoin(selectedCoin.pub_key)
                            }
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text(if (coin?.frozen == true) "解冻" else "冻结")
            }
        }
    }
}

@Preview(device = "id:pixel")
@Composable
private fun CoinDetailScreenPreview() {
    AnonNeroTheme {
        CoinDetailScreen(coinKey = "")
    }
}
