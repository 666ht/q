package io.anonero.ui.home

import AnonNeroTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.anonero.services.WalletState
import io.anonero.util.Formats
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoinDetailScreen(
    coinKey: String,
    onBackPress: () -> Unit = {},
) {
    val walletState = koinInject<WalletState>()
    val coins by walletState.coins.collectAsState(arrayListOf())
    val coin = coins.find { it.key == coinKey }

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
                    "输出",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    coin?.let { Formats.getDisplayAmount(it.amount) } ?: "____",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    coin?.pub_key ?: "____",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Button(
                onClick = { },
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
