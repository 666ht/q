package io.anonero.ui.home.settings

import AnonNeroTheme
import AnonOutlineButton
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.FileProvider
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.anonero.AnonConfig
import io.anonero.R
import io.anonero.icons.AnonIcons
import io.anonero.util.Formats
import io.anonero.util.KeyStoreHelper
import io.anonero.util.PREFS_PASSPHRASE_HASH
import io.anonero.util.ShakeConfig
import io.anonero.util.WALLET_PREFERENCES
import io.anonero.util.backup.BackupHelper
import io.anonero.util.rememberShakeController
import io.anonero.util.shake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import timber.log.Timber
import java.io.File


private const val TAG = "ExportBackUp"

class ExportBackUpViewModel : ViewModel() {

    private val backupFile = MutableLiveData<File?>(null)

    val backupFileLive: LiveData<File?> = backupFile

    fun createBackUp(passPhrase: String): Job {
        return viewModelScope.launch(Dispatchers.IO) {
            val path = BackupHelper.createBackUp(passPhrase, AnonConfig.context!!)
            Timber.tag(TAG).i("Backup created :$path")
            backupFile.postValue(File(path))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportBackUp(onBackPress: () -> Unit = {}) {

    val prefs = koinInject<SharedPreferences>(named(WALLET_PREFERENCES))
    val exportViewModel = viewModel<ExportBackUpViewModel>()
    val focusRequester = remember { FocusRequester() }
    var passPhraseDialog by remember { mutableStateOf(!AnonConfig.viewOnly) }
    var passPhrase by remember { mutableStateOf("") }
    val errorShake = rememberShakeController()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val context = LocalContext.current
    val backupFile by exportViewModel.backupFileLive.observeAsState()

    val backgroundBlur: Float by animateFloatAsState(
        if (passPhraseDialog) 6f else 0f,
        label = "blur-radius"
    )

    fun createBackUp() {
        scope.launch(Dispatchers.IO) {
            val hash = prefs.getString(PREFS_PASSPHRASE_HASH, "")
            val hashedPass = KeyStoreHelper.getCrazyPass(AnonConfig.context, passPhrase)
            if (hash == hashedPass) {
                passPhraseDialog = false
                exportViewModel.createBackUp(passPhrase)
            } else {
                errorShake.shake(
                    ShakeConfig(
                        6, translateX = 5f
                    )
                )
                repeat(6) {
                    delay(50)
                    view.performHapticFeedback(
                        HapticFeedbackConstants.CONTEXT_CLICK
                    )
                }
                delay(100)
            }

        }
    }

    LaunchedEffect(true) {
        scope.launch {
            delay(100)
            if (AnonConfig.viewOnly) {
                createBackUp()
            } else {
                focusRequester.requestFocus()
            }
        }
    }

    fun exportToExternDir() {
        if (backupFile == null) {
            return
        }
        val fileUri: Uri = try {
            FileProvider.getUriForFile(
                context, // Context
                "${context.packageName}.shareProvider", // Authority
                backupFile!!
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e)
            return
        }
        // Create the share intent
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) // Grant permission to the receiving app
        }
        context.startActivity(shareIntent)
    }
    if (passPhraseDialog)
        AlertDialog(
            modifier = Modifier
                .shake(errorShake)
                .border(
                    1.dp,
                    color = MaterialTheme.colorScheme.onSecondary.copy(
                        alpha = .2f
                    ),
                    shape = MaterialTheme.shapes.medium,
                ),
            containerColor = MaterialTheme.colorScheme.secondary,
            properties = DialogProperties(
                securePolicy = SecureFlagPolicy.SecureOn,
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            ),
            title = {
                Text(
                    text = stringResource(R.string.enter_seed_phrase),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 18.sp
                    )
                )
            },
            text = {
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
                            createBackUp()
                        }
                    ),
                    onValueChange = {
                        passPhrase = it
                    },

                    )
            },
            onDismissRequest = {
                passPhraseDialog = false
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
                        createBackUp()
                    }) { Text(stringResource(R.string.create)) }
            },
            dismissButton = {
                Button(
                    onClick = {
                        passPhraseDialog = false
                        onBackPress.invoke()
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
                        stringResource(R.string.cancel),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSecondary.copy(
                                alpha = 0.8f
                            )
                        )
                    )
                }
            }
        )


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
                title = {
                    Text(stringResource(R.string.encrypted_backup))
                }
            )
        }
    ) {
        Box(
            modifier = Modifier
                .padding(it)
                .blur(backgroundBlur.dp)
                .padding(
                    horizontal = 8.dp
                )
        ) {
            if (!passPhraseDialog && backupFile == null) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(
                            all = 12.dp
                        ),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(300.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.generating_backup))
                }
            } else {
                if (backupFile != null) {

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                horizontal = 12.dp
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Spacer(Modifier.fillMaxWidth())
                        Column {

                            ListItem(
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        color = MaterialTheme.colorScheme.primary.copy(
                                            alpha = 1f
                                        ),
                                        shape = MaterialTheme.shapes.medium,
                                    ),
                                headlineContent = {
                                    Text(
                                        "${backupFile?.name}",
                                        style = MaterialTheme.typography
                                            .titleSmall
                                    )
                                },
                                trailingContent = {
                                    Text(Formats.formatFileSize(backupFile!!.length()))
                                },
                                leadingContent = {
                                    Icon(
                                        AnonIcons.FileEarmarkLock,
                                        contentDescription = stringResource(R.string.backup_icon),
                                        modifier = Modifier.size(24.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            )
                            Text(
                                stringResource(R.string.backup_generated_successfully), textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        top = 24.dp,
                                        bottom = 4.dp
                                    )
                            )
                            Text(
                                stringResource(R.string.save_backup_instruction),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = Color.Gray
                                )
                            )
                        }
                        AnonOutlineButton(
                            modifier = Modifier
                                .fillMaxWidth(0.9f),
                            onClick = {
                                exportToExternDir()
                            }) { Text(stringResource(R.string.export_to_file)) }
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Preview(device = "id:pixel_5")
@Composable
private fun SeedSettingsPre() {
    AnonNeroTheme {

    }
}