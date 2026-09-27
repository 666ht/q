package io.anonero.ui.home

import AnonNeroTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.asLiveData
import io.anonero.R
import io.anonero.model.CoinsInfo
import io.anonero.model.Wallet
import io.anonero.services.WalletState
import io.anonero.ui.components.WalletProgressIndicator
import io.anonero.ui.home.graph.routes.SendScreenRoute
import io.anonero.util.Formats
import org.koin.java.KoinJavaComponent.inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoinsScreen(
    modifier: Modifier = Modifier,
    navigateToSpend: (route: SendScreenRoute) -> Unit = {},
    onBackPress: () -> Unit = {},
    selected: Set<String> = setOf(),
) {
    val walletState: WalletState by inject(WalletState::class.java)
    val coins by walletState.coins.collectAsState(arrayListOf())
    var selectedCoins by remember { mutableStateOf(selected) }
    val connectionStatus by walletState.walletConnectionStatus.asLiveData().observeAsState(
        Wallet.ConnectionStatus.ConnectionStatus_Disconnected
    )
    val loading by walletState.isLoading.asLiveData().observeAsState(false)
    val nodeConnected = connectionStatus == Wallet.ConnectionStatus.ConnectionStatus_Connected


    var message: String? = null;
    if (loading) {
        message = stringResource(R.string.wallet_loading)
    } else if (!nodeConnected) {
        message = stringResource(R.string.node_disconnected)
    } else if(coins.isEmpty()) {
        message = stringResource(R.string.no_coins_available)
    }

    fun selectCoin(coin: CoinsInfo) {
        selectedCoins = if (selectedCoins.contains(coin.pub_key)) {
            selectedCoins - coin.pub_key
        } else {
            selectedCoins + coin.pub_key
        }
    }

    Scaffold(
        modifier = modifier,
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            AnimatedVisibility(
                modifier = Modifier
                    .padding(
                        horizontal = 8.dp,
                        vertical = 8.dp
                    ),
                visible = selectedCoins.isNotEmpty(),
                enter = slideInVertically(
                    initialOffsetY = { it }, // Slide in from the bottom
                    animationSpec = tween(durationMillis = 220)
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it }, // Slide out to the bottom
                    animationSpec = tween(durationMillis = 220)
                )
            ) {
                OutlinedButton(
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground
                    ),
                    onClick = {
                        navigateToSpend(
                            SendScreenRoute(
                                coins = selectedCoins.toList(),
                                address = ""
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 12.dp
                        ),

                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(12.dp)
                ) {
                    Text(if (selected.size != 0) stringResource(R.string.confirm) else stringResource(R.string.spend))
                }
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.coins))
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackPress
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        }) {
        LazyColumn(modifier = Modifier.padding(it).padding(
            bottom = 44.dp
        )) {
            item {
                WalletProgressIndicator()
            }
            items(coins.size) { index ->
                val coin = coins[index]
                ListItem(
                    modifier = Modifier
                        .padding(
                            horizontal = 4.dp,
                            vertical = 6.dp
                        )
                        .clickable {
                            selectCoin(coin)
                        },
                    headlineContent = {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(R.string.output_number, index + 1), color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                Formats.getDisplayAmount(coin.amount),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    },
                    supportingContent = {
                        SelectionContainer {
                            Text(
                                coin.pub_key,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Justify
                            )
                        }
                    },
                    trailingContent = {
                        Checkbox(
                            checked = selectedCoins.contains(coin.pub_key),
                            onCheckedChange = { checked ->
                                selectCoin(coin)
                            }
                        )
                    }
                )
            }
            if (message != null) {
                item {
                    Box(
                        modifier = Modifier
                            .height(280.dp)
                            .fillMaxWidth()
                    ) {
                        Text(
                            message,
                            modifier = Modifier
                                .padding(
                                    horizontal = 16.dp,
                                    vertical = 8.dp
                                )
                                .align(
                                    Alignment.Center
                                ),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Preview(device = "id:pixel")
@Composable
private fun ReceiveScreenPreview() {
    AnonNeroTheme {
        CoinsScreen()
    }
}
