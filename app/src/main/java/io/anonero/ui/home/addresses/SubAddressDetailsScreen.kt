package io.anonero.ui.home.addresses

import AnonNeroTheme
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewmodel.compose.viewModel
import io.anonero.icons.AnonIcons
import io.anonero.model.Subaddress
import io.anonero.model.TransactionInfo
import io.anonero.services.WalletState
import io.anonero.ui.components.SubAddressLabelDialog
import io.anonero.ui.components.qr.QrCodeView
import io.anonero.ui.home.TransactionItem
import io.anonero.util.Formats
import kotlinx.coroutines.flow.map
import org.koin.java.KoinJavaComponent.inject

class SubAddressDetail(subAddress: Subaddress) : ViewModel() {
    private val walletState: WalletState by inject(WalletState::class.java)

    val transactions = walletState.transactions
        .map {
            it.filter { txInfo ->
                return@filter txInfo.addressIndex == subAddress.addressIndex
                        && txInfo.direction == TransactionInfo.Direction.Direction_In
            }
        }.asLiveData()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SubAddressDetailScreen(
    modifier: Modifier = Modifier,
    onBackPress: () -> Unit = {},
    onTransactionClick: (TransactionInfo) -> Unit = {},
    subAddress: Subaddress,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope
) {
    val walletState: WalletState by inject(WalletState::class.java)
    var showQR by remember { mutableStateOf(false) }
    val subAddressDetailVm = viewModel {
        SubAddressDetail(subAddress)
    }
    val transactions by subAddressDetailVm.transactions.observeAsState(listOf())
    var addressLabel by remember { mutableStateOf(subAddress.label) }
    val displayAddressLabel = remember(addressLabel, subAddress.addressIndex) {
        if (addressLabel.isEmpty() || Subaddress.DEFAULT_LABEL_FORMATTER.matcher(addressLabel).matches()) {
            if (subAddress.addressIndex == 0) "主地址 0" else "子地址 ${subAddress.addressIndex}"
        } else {
            addressLabel.replace(Regex("""\s*[#＃]?\s*\d+$"""), "")
        }
    }
    BackHandler {
        onBackPress()
    }

    var labelDialog by remember { mutableStateOf(false) }

    if (labelDialog)
        SubAddressLabelDialog(
            label = addressLabel,
            onSave = { label ->
                walletState.updateAddressLabel(label, subAddress.addressIndex)
                addressLabel = label
                labelDialog = false
            }, onCancel = {
                labelDialog = false
            })

    if (showQR) {
        ModalBottomSheet(
            scrimColor = MaterialTheme.colorScheme.background.copy(
                alpha = 0.5f
            ),
            containerColor = MaterialTheme.colorScheme.background,
            onDismissRequest = {
                showQR = false
            }
        ) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                QrCodeView(
                    data = subAddress.address,
                    modifier = Modifier
                        .size(300.dp)
                        .padding(20.dp)
                )
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBackPress
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                title = {},
                actions = {
                    IconButton(
                        onClick = {
                            showQR = true
                        }
                    ) {
                        Icon(AnonIcons.QrCode, contentDescription = null)
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(modifier = Modifier.padding(paddingValues)) {
            item {
                with(sharedTransitionScope) {
                    ListItem(
                        modifier = Modifier
                            .sharedElement(
                                sharedTransitionScope.rememberSharedContentState(key = "${subAddress.address}:${subAddress.addressIndex}"),
                                animatedVisibilityScope = animatedContentScope
                            )
                            .padding(
                                horizontal = 4.dp,
                                vertical = 6.dp
                            ),
                        headlineContent = {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    displayAddressLabel,
                                    modifier = Modifier.clickable {
                                        labelDialog = true
                                    },
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    Formats.getDisplayAmount(subAddress.totalAmount),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        },
                        supportingContent = {
                            SelectionContainer {
                                Text(
                                    subAddress.address,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Justify
                                )
                            }
                        },
                    )
                }
            }
            items(transactions.size) {
                with(sharedTransitionScope) {
                    TransactionItem(
                        transactions[it], modifier = Modifier
                            .clickable {
                                onTransactionClick(transactions[it])
                            }
                            .sharedElement(
                                sharedTransitionScope.rememberSharedContentState(key = "${transactions[it].hash}"),
                                animatedVisibilityScope = animatedContentScope
                            )
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Preview(device = "id:pixel_7_pro")
@Composable
private fun SubAddressScreenPrev() {
    AnonNeroTheme {

    }
}