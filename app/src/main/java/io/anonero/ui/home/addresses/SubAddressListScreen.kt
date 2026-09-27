package io.anonero.ui.home.addresses

import AnonNeroTheme
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.anonero.model.Subaddress
import io.anonero.services.WalletState
import io.anonero.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject


class SubAddressListViewModel : ViewModel() {

    private val walletState: WalletState by inject(WalletState::class.java)

    val subAddresses = walletState.subAddresses.asLiveData()


    fun getNextAddress() {
        viewModelScope.launch(Dispatchers.IO) {
            walletState.getNewAddress()
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SubAddressesScreen(
    modifier: Modifier = Modifier,
    onBackPress: () -> Unit = {},
    navigateToDetails: (Subaddress) -> Unit = {},
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    BackHandler {
        onBackPress()
    }
    val vm = viewModel<SubAddressListViewModel>()
    val addresses by vm.subAddresses.observeAsState(listOf())
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
                            vm.getNextAddress()
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(modifier = Modifier.padding(paddingValues)) {
            items(addresses.size) {
                val address = addresses[it]
                with(sharedTransitionScope) {
                    ListItem(
                        modifier = Modifier
                            .sharedElement(
                                sharedTransitionScope.rememberSharedContentState(key = "${address.address}:${address.addressIndex}"),
                                animatedVisibilityScope = animatedContentScope
                            )
                            .padding(
                                horizontal = 4.dp,
                                vertical = 6.dp
                            )

                            .clickable {
                                navigateToDetails(address)
                            },
                        headlineContent = {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (address.addressIndex == 0) "主地址 0" else "子地址 " + address.addressIndex,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    Formats.getDisplayAmount(address.totalAmount),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        },
                        supportingContent = {
                            SelectionContainer {
                                Text(
                                    address.squashedAddress,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Justify
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Preview(device = "id:pixel_7_pro")
@Composable
private fun SubAddressScreenPrev() {
    AnonNeroTheme {

    }
}