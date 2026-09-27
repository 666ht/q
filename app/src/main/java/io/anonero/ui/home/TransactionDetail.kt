package io.anonero.ui.home

import AnonNeroTheme
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import io.anonero.R
import io.anonero.model.TransactionInfo
import io.anonero.model.WalletManager
import io.anonero.services.WalletState
import io.anonero.util.Formats
import kotlinx.coroutines.launch
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject


@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun TransactionDetailScreen(
    modifier: Modifier = Modifier,
    transactionId: String,
    onBackPress: () -> Unit = {},
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope
) {
//    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var transactionInfo: TransactionInfo? by remember { mutableStateOf(null) }
    val walletState = koinInject<WalletState>()
    val transactions by walletState.transactions.collectAsState(listOf())
    val scope = rememberCoroutineScope()
    var notesDialog by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf(TextFieldValue(text = "")) }
    val focusRequester = remember { FocusRequester() }
    var destinations by remember { mutableStateOf("") }
    var transactionKey by remember { mutableStateOf("") }
    val view = LocalView.current

    fun saveNote() {
        if (transactionInfo == null) {
            return
        }
        scope.launch {
            notesDialog = false
            view.performHapticFeedback(
                HapticFeedbackConstants.CONTEXT_CLICK
            )
            walletState.setTransactionNote(notes.text, transactionInfo!!)
        }
    }

    LaunchedEffect(true) {
        scope.launch {
            transactionInfo = transactions.find { it.hash == transactionId }
            val wallet = WalletManager.instance?.wallet ?: return@launch
            if (transactionInfo != null) {
                var destination = ""
                transactionInfo?.transfers?.forEach {
                    destination = "${it.address}\n${destination}"
                }
                destinations = destination.trim()

                if (transactionInfo?.direction == TransactionInfo.Direction.Direction_In) {
                    destinations = wallet.getSubaddress(
                        0,
                        transactionInfo!!.addressIndex
                    )
                }
                transactionKey =
                    wallet.getTxKey(transactionInfo!!.hash) ?: "____"

                transactionInfo?.notes?.let {
                    notes = notes.copy(
                        text = it,
                        selection = TextRange(it.length)
                    )
                }
            }

        }
    }

    LaunchedEffect(notesDialog) {
        if (notesDialog) {
            focusRequester.requestFocus()
        }
    }

    if (notesDialog)
        AlertDialog(
            modifier = Modifier
                .border(
                    1.dp,
                    color = MaterialTheme.colorScheme.onSecondary.copy(
                        alpha = .2f
                    ),
                    shape = MaterialTheme.shapes.medium,
                ),
            containerColor = MaterialTheme.colorScheme.secondary,
            properties = DialogProperties(
                dismissOnBackPress = true
            ),
            title = {
                Text(
                    text = stringResource(R.string.set_transaction_description),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 16.sp
                    )
                )
            },
            text = {
                OutlinedTextField(
                    value = notes,
                    shape = MaterialTheme.shapes.small,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                    ),
                    minLines = 3,
                    maxLines = 5,
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
                            saveNote()
                        }
                    ),
                    onValueChange = {
                        if (it.text.length <= 24) {
                            notes = it
                        }
                    },
                )
            },
            onDismissRequest = {
                notesDialog = false
            },
            confirmButton = {
                Button(
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(
                        1.dp,
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onBackground,

                        ),
                    onClick = {
                        saveNote()
                    }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                Button(
                    onClick = {
                        notesDialog = false
                    },
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(
                        1.dp,
                        color = MaterialTheme.colorScheme.onSecondary
                    ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    )
                ) {
                    Text(
                        stringResource(R.string.dismiss),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSecondary.copy(
                                alpha = 0.8f
                            )
                        )
                    )
                }
            }
        )


    Scaffold(topBar = {
        TopAppBar(navigationIcon = {
            IconButton(
                onClick = onBackPress
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
        }, title = {
            Text("")
        })
    }) { paddingValues ->
        if (transactionInfo != null) {
            LazyColumn(
                contentPadding = paddingValues,
            ) {
                item {
                    with(sharedTransitionScope) {
                        TransactionItem(
                            tx = transactionInfo!!,
                            rightAlignAmount = false,
                            showTime = true,
                            modifier = Modifier
                                .clickable {

                                }
                                .sharedElement(
                                    sharedTransitionScope.rememberSharedContentState(
                                        key = "${transactionInfo!!.hash}",
                                    ),
                                    animatedVisibilityScope = animatedContentScope
                                )
                        )
                    }
                }
                item {
                    DetailItem(
                        title = stringResource(R.string.destination),
                        subtitle = destinations,
                        copyable = true
                    )
                }
                item {
                    DetailItem(
                        title = stringResource(R.string.description),
                        subtitle = notes.text,
                        trailing = {
                            IconButton(onClick = {
                                notesDialog = true
                                view.performHapticFeedback(
                                    HapticFeedbackConstants.CONTEXT_CLICK
                                )
                            }) {
                                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit))
                            }
                        }
                    )
                }
                item {
                    DetailItem(
                        title = stringResource(R.string.transaction_id),
                        subtitle = transactionInfo?.hash ?: "____",
                        copyable = true
                    )
                }
                item {
                    DetailItem(
                        title = stringResource(R.string.transaction_fee),
                        subtitle = if (transactionInfo?.fee != null) {
                            Formats.getDisplayAmount(transactionInfo?.fee!!)
                        } else {
                            "____"
                        }
                    )
                }
                item {
                    DetailItem(
                        title = stringResource(R.string.transaction_key),
                        subtitle = transactionKey,
                        copyable = true
                    )
                }
                item {
                    DetailItem(
                        title = stringResource(R.string.confirmation_block),
                        subtitle = "${transactionInfo?.blockheight ?: "____"}"
                    )
                }
                item {
                    DetailItem(
                        modifier = Modifier.padding(bottom = 24.dp),
                        title = stringResource(R.string.time),
                        subtitle = Formats.formatTransactionTime(
                            transactionInfo!!.timestamp,
                            "dd/MM/yyy HH:mm:ss"
                        )
                    )
                }
                item { Spacer(Modifier.height(44.dp)) }
            }
        }
    }
}

@Composable
fun DetailItem(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    copyable: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val clipboardManager = LocalClipboardManager.current
    val view = LocalView.current
    ListItem(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .then(
                if (copyable && subtitle.isNotEmpty() && subtitle != "____") {
                    Modifier.clickable {
                        clipboardManager.setText(AnnotatedString(subtitle))
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                } else Modifier
            ),
        trailingContent = trailing,
        headlineContent = {
            Text(
                title, style = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.W500
                )
            )
        }, supportingContent = {
            Text(
                subtitle,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.W400
                )
            )
        })
}

@Preview(device = "id:pixel_7_pro")
@Composable
private fun TransactionDetailsScreenPreview() = KoinApplication(application = {}) {
    AnonNeroTheme {

    }
}