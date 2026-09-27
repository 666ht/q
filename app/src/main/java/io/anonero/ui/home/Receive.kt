package io.anonero.ui.home

import AnonNeroTheme
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.anonero.R
import io.anonero.icons.AnonIcons
import io.anonero.services.WalletState
import io.anonero.ui.components.SubAddressLabelDialog
import io.anonero.ui.components.qr.QrCodeColors
import io.anonero.ui.components.qr.QrCodeView
import org.koin.java.KoinJavaComponent.inject


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(
    modifier: Modifier = Modifier,
    onBackPress: () -> Unit = {},
    navigateToSubAddresses: () -> Unit = {}
) {
    val walletState: WalletState by inject(WalletState::class.java)
    val nextAddress by walletState.nextAddress.collectAsState(null)
    var labelDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    if (labelDialog)
        SubAddressLabelDialog(
            label = nextAddress!!.displayLabel,
            onSave = { label ->
                walletState.updateAddressLabel(label, nextAddress!!.addressIndex)
                labelDialog = false
            }, onCancel = {
                labelDialog = false
            })

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {

                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackPress
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = navigateToSubAddresses
                    ) {
                        Icon(AnonIcons.History, contentDescription = null)
                    }
                }
            )
        }) {
        if (nextAddress != null) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = it,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                item {
                    Spacer(Modifier.height(36.dp))
                }
                item {
                    Spacer(Modifier.height(24.dp))
                }
                item {
                    QrCodeView(
                        data = nextAddress!!.address,
                        colors = QrCodeColors(
                            background = MaterialTheme.colorScheme.background,
                            foreground = MaterialTheme.colorScheme.onBackground,
                        ),
                        modifier = Modifier
                            .size(300.dp)
                            .padding(18.dp),
                    )
                }
                item {
                    SelectionContainer(
                        modifier = Modifier.clickable {
                            nextAddress?.let {
                                clipboardManager.setText(AnnotatedString(text = it.address))
                                Toast.makeText(context,
                                    context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT)
                                    .show()
                            }
                        }
                    ) {
                        Text(
                            nextAddress!!.address,
                            modifier = Modifier.width(300.dp)
                        )
                    }
                }
                item {
                    Spacer(Modifier.height(18.dp))
                }

            }
        }
    }
}

@Preview(device = "id:pixel")
@Composable
private fun ReceiveScreenPreview() {
    AnonNeroTheme {
        ReceiveScreen()
    }
}
