package io.anonero.ui.onboard.viewonly

import AnonNeroTheme
import AnonOutlineButton
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.anonero.AnonConfig
import io.anonero.R
import io.anonero.icons.AnonIcons
import io.anonero.model.NeroKeyPayload
import io.anonero.ui.components.scanner.QRScannerDialog
import kotlinx.serialization.json.Json


@Composable
fun RestoreFromKeys(
    onBackPressed: () -> Unit = {},
    oNextPressed: (NeroKeyPayload) -> Unit = {},
) {
    var viewKey by remember { mutableStateOf("") }
    var primaryAddress by remember { mutableStateOf("") }
    var restoreHeight by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    val context = LocalContext.current
    QRScannerDialog(
        show = showScanner,
        onQRCodeScanned = {
            error = ""
            try {
                val payload = Json.decodeFromString<NeroKeyPayload>(it)
                primaryAddress = payload.primaryAddress
                viewKey = payload.privateViewKey
                restoreHeight = payload.restoreHeight.toString()
            } catch (e: Exception) {
                error = context.getString(R.string.invalid_qr_code)
            }
            showScanner = false
        },
        onDismiss = {
            showScanner = false
        }
    )

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    modifier = Modifier
                        .size(24.dp),
                    onClick = {
                        onBackPressed()
                    }
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.drawable.ic_anon),
                    contentDescription = "Anon nero icon",
                    modifier = Modifier
                        .size(120.dp)
                )
                Spacer(Modifier.height(48.dp))
            }
            Column(
                modifier = Modifier
                    .weight(.8f)
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.primary_address),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    },
                    supportingContent = {
                        OutlinedTextField(
                            value = primaryAddress,
                            shape = MaterialTheme.shapes.medium,
                            onValueChange = {
                                primaryAddress = it
                            },
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Next,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusable()
                        )
                    },
                )

                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.private_view_key),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    },
                    supportingContent = {
                        OutlinedTextField(
                            value = viewKey,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Next,
                            ),
                            onValueChange = {
                                viewKey = it
                            },

                            )
                    },
                )
                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.restore_height),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    },
                    supportingContent = {
                        OutlinedTextField(
                            value = restoreHeight,
                            shape = MaterialTheme.shapes.medium,
                            onValueChange = {
                                restoreHeight = it
                            },
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Done,
                                keyboardType = KeyboardType.Number
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                )
                IconButton(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    onClick = {
                        showScanner = true
                    }
                ) {
                    Icon(AnonIcons.Scan, contentDescription = "")
                }
                if (error.isNotEmpty()) {
                    Text(
                        text = error,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                    )
                }
            }
            val enabled =
                primaryAddress.isNotEmpty() && viewKey.isNotEmpty() && restoreHeight.isNotEmpty()
            AnonOutlineButton (
                onClick = {
                    if (enabled) {
                        val payload = NeroKeyPayload(
                            primaryAddress = primaryAddress,
                            privateViewKey = viewKey,
                            restoreHeight = restoreHeight.toLong(),
                            version = AnonConfig.NERO_KEY_PAYLOAD_VERSION
                        )
                        oNextPressed(payload)
                    }
                },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 12.dp
                    ),
            ) {
                Text(stringResource(R.string.next))
            }
        }
    }
}

@Preview(showBackground = true, device = "id:pixel_5")
@Composable
private fun SetupNodeComposablePreview() {
    AnonNeroTheme {
        RestoreFromKeys()
    }
}