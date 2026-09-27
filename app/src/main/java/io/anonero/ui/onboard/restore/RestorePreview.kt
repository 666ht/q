package io.anonero.ui.onboard.restore

import AnonOutlineButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.anonero.R
import io.anonero.model.BackupPayload
import io.anonero.model.Wallet
import io.anonero.ui.onboard.graph.OnboardLogsScreen
import io.anonero.util.backup.BackupHelper
import io.anonero.util.backup.NetworkMismatchException
import io.anonero.util.rememberShakeController
import io.anonero.util.shake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private const val TAG = "RestorePreview"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestorePreview(
    modifier: Modifier = Modifier,
    backUpPath: String,
    onBackPressed: () -> Unit = {},
    oNextPressed: () -> Unit = {},
    navigateTo: (route: Any) -> Unit = {}
) {

    var passPhrase by remember { mutableStateOf<String>("") }
    var loadingMessage by remember { mutableStateOf<String>("Loading...") }
    var errorMessage by remember { mutableStateOf<String>("") }
    var backupPayload by remember { mutableStateOf<BackupPayload?>(null) }
    var passphraseDialog by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val errorShake = rememberShakeController()
    val view = LocalView.current

    fun restoreFromBackup() {
        scope.launch(Dispatchers.IO) {
            try {
                loading = true
                passphraseDialog = false
                loadingMessage = stringResource(R.string.extracting_backup)
                backupPayload = BackupHelper.extractBackUp(backUpPath, passPhrase)
                loading = false
            } catch (e: NetworkMismatchException) {
                errorMessage = stringResource(R.string.invalid_network)
                loading = false
                scope.launch {
                    errorShake.shake(view)
                }
            } catch (e: Exception) {
                errorMessage = stringResource(R.string.unable_to_extract_backup)
                loading = false
                scope.launch {
                    errorShake.shake(view)
                }
                e.printStackTrace()
            }
        }
    }


    LaunchedEffect(passphraseDialog) {
        scope.launch {
            if (passphraseDialog) {
                delay(100)
                focusRequester.requestFocus()
            }
        }
    }

    if (passphraseDialog) AlertDialog(
        modifier = Modifier
            .shake(errorShake)
            .border(
                1.dp,
                color = MaterialTheme.colorScheme.onSecondary.copy(
                    alpha = .2f
                ),
                shape = MaterialTheme.shapes.medium,
            ), containerColor = MaterialTheme.colorScheme.secondary, properties = DialogProperties(
            securePolicy = SecureFlagPolicy.SecureOn, dismissOnBackPress = false
        ), title = {
            Text(
                text = stringResource(R.string.enter_seed_phrase), style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 18.sp
                )
            )
        }, text = {
            OutlinedTextField(
                value = passPhrase,
                shape = MaterialTheme.shapes.small,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                ),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .padding(
                        top = 8.dp
                    ),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        restoreFromBackup()
                    }),
                onValueChange = {
                    passPhrase = it
                },

                )
        }, onDismissRequest = {
            passphraseDialog = false
        }, confirmButton = {
            Button(
                shape = MaterialTheme.shapes.small, border = BorderStroke(
                    1.dp, color = MaterialTheme.colorScheme.onBackground
                ), colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onBackground,

                    ), onClick = {
                    restoreFromBackup()
                }) { Text(stringResource(R.string.restore)) }
        }, dismissButton = {
            Button(
                onClick = {
                    passphraseDialog = false
                    onBackPressed.invoke()
                }, shape = MaterialTheme.shapes.small, border = BorderStroke(
                    1.dp, color = MaterialTheme.colorScheme.onSecondary
                ), colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.background,
                )
            ) {
                Text(
                    stringResource(R.string.cancel), style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSecondary.copy(
                            alpha = 0.8f
                        )
                    )
                )
            }
        })


    if (passphraseDialog) {
        return Box {

        }
    }

    Scaffold(
        topBar = {
            TopAppBar(navigationIcon = {
                IconButton(
                    onClick = onBackPressed
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }, title = {
                Text(
                    stringResource(R.string.backup_preview),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = MaterialTheme.colorScheme.onSecondary.copy(
                            alpha = 0.8f
                        )
                    )
                )
            })
        },
    ) { paddingValues ->
        if (errorMessage.isNotEmpty()) {
            return@Scaffold Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.size(12.dp))
                Text(
                    errorMessage,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 16.sp
                    )
                )
                AnonOutlineButton(
                    onClick = {
                        navigateTo(OnboardLogsScreen)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .safeContentPadding()
                        .padding(
                            horizontal = 16.dp,
                        ),
                ) {
                    Text(stringResource(R.string.view_logs))
                }
            }
        }

        if (loading) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(140.dp), strokeWidth = 2.dp
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    loadingMessage,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSecondary.copy(
                            alpha = 0.8f
                        )
                    )
                )
            }
        }

        if (backupPayload != null && !loading) {
            val wallet = backupPayload!!.backup.wallet
            val node = backupPayload!!.backup.node
            val meta = backupPayload!!.backup.meta


            LazyColumn(
                modifier = Modifier
                    .padding(paddingValues)
                    .padding(
                        horizontal = 16.dp
                    )
            ) {
                item {
                    ListItem(
                        modifier = modifier
                            .border(
                                .5.dp,
                                color = MaterialTheme.colorScheme.primary.copy(
                                    alpha = .8f
                                ),
                                shape = MaterialTheme.shapes.medium,
                            )
                            .padding(8.dp),
                        headlineContent = {
                            Text(
                                "Wallet ",
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(
                                    top = 8.dp
                                ),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = MaterialTheme.colorScheme.onSecondary.copy(
                                        alpha = 0.8f
                                    )
                                )
                            )
                        },
                        supportingContent = {
                            Column {
                                HorizontalDivider(
                                    modifier = Modifier.padding(
                                        vertical = 8.dp
                                    )
                                )
                                ListWidget(
                                    title = stringResource(R.string.seed_label),
                                    subtitle = "${wallet.seed}",
                                    modifier = Modifier.padding(
                                        bottom = 8.dp
                                    )
                                )

                                if (wallet.primaryAddress.isNotEmpty()) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(
                                            vertical = 8.dp
                                        )
                                    )
                                    ListWidget(
                                        title = stringResource(R.string.primary_address_label),
                                        subtitle = "${wallet.primaryAddress}",
                                        modifier = Modifier.padding(
                                            bottom = 8.dp
                                        )
                                    )
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(
                                        vertical = 8.dp
                                    )
                                )
                                ListWidget(
                                    title = stringResource(R.string.balance_label),
                                    subtitle = "${Wallet.getDisplayAmount(wallet.balanceAll)}",
                                    modifier = Modifier.weight(1f)
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(
                                        vertical = 8.dp
                                    )
                                )
                                ListWidget(
                                    title = stringResource(R.string.restore_height_label),
                                    subtitle = "${wallet.restoreHeight}",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    )
                }
                item {
                    Spacer(Modifier.height(16.dp))
                }
                item {
                    ListItem(
                        modifier = modifier
                            .border(
                                .5.dp,
                                color = MaterialTheme.colorScheme.primary.copy(
                                    alpha = .8f
                                ),
                                shape = MaterialTheme.shapes.medium,
                            )
                            .padding(8.dp),
                        headlineContent = {
                            Text(
                                stringResource(R.string.node).lowercase(),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(
                                    top = 8.dp
                                ),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = MaterialTheme.colorScheme.onSecondary.copy(
                                        alpha = 0.8f
                                    )
                                )
                            )
                        },
                        supportingContent = {
                            if (node.host.isNotEmpty())
                                Column {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(
                                            vertical = 8.dp
                                        )
                                    )
                                    ListWidget(
                                        title = stringResource(R.string.host),
                                        subtitle = "${node.host}:${node.rpcPort}",
                                        modifier = Modifier.padding(
                                            bottom = 8.dp
                                        )
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(
                                            vertical = 8.dp
                                        )
                                    )
                                    if (node.password.isNotEmpty())
                                        ListWidget(
                                            title = stringResource(R.string.password),
                                            subtitle = "${node.password}",
                                        )
                                    if (node.password.isNotEmpty())
                                        HorizontalDivider(
                                            modifier = Modifier.padding(
                                                vertical = 8.dp
                                            )
                                        )
                                    if (node.username.isNotEmpty())
                                        ListWidget(
                                            title = stringResource(R.string.username),
                                            subtitle = "${node.username}",
                                        )

                                }
                        }
                    )
                }
                item {
                    if (errorMessage.isEmpty()) {
                        AnonOutlineButton(
                            enabled = !loading,
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        loading = true
                                        loadingMessage = stringResource(R.string.restoring_wallet_progress)
                                        val success =
                                            BackupHelper.restoreBackUp(backupPayload!!, passPhrase)
                                        if (success) {
                                            BackupHelper.cleanCacheDir()
                                            delay(1000)
                                            withContext(Dispatchers.Main) {
                                                oNextPressed()
                                            }
                                        }
                                        loading = false
                                    } catch (e: Exception) {
                                        Timber.tag(TAG).e(e)
                                        e.printStackTrace()
                                    } finally {
                                        loading = false
                                    }
                                }

                            },
                        ){
                            Text(stringResource(R.string.restore_wallet_action))
                        }
                    }
                }
                item {
                    Box(
                        Modifier.height(
                            16.dp
                        )
                    )
                }
                if (meta.timestamp != 0L) {
                    item {
                        Text(
                            "Created at ${
                                LocalDateTime.ofInstant(
                                    Instant.ofEpochMilli(meta.timestamp),
                                    java.time.ZoneId.systemDefault()
                                ).format(formatter)
                            }",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSecondary.copy(
                                    alpha = 0.8f
                                ),
                                fontWeight = FontWeight.W100,
                                fontSize = 11.sp,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    top = 8.dp,
                                    bottom = 8.dp
                                )
                        )
                    }
                }
                item {
                    Box(
                        Modifier.height(
                            16.dp
                        )
                    )
                }
            }
        }

    }

}


@Composable
fun ListWidget(
    subtitle: String, title: String, modifier: Modifier = Modifier
) {
    ListItem(
        headlineContent = {
            Text(
                title, style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 16.sp, color = Color.White
                )
            )
        }, supportingContent = {
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp,
                )
            )
        })
}