package io.anonero.ui.onboard

import AnonNeroTheme
import AnonOutlineButton
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.anonero.AnonConfig
import io.anonero.R


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingWelcome(
    onCreateClick: () -> Unit = {},
    onRestoreClick: (backupPath: String?) -> Unit = {},
    onRestoreFromKeys: () -> Unit = {},
    onProxySettings: () -> Unit = {},
    onLogsScreen: () -> Unit = {},
) {
    var restoreOptions by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(
        )
    ) {
        restoreOptions = false
        if(it == null) {
            return@rememberLauncherForActivityResult
        }
        onRestoreClick(it.toString())
    }

    fun chooseFile() {
        launcher.launch(arrayOf("*/*"))
    }

    if (restoreOptions)
        AlertDialog(
            {
                restoreOptions = false
            }, {
                Button(
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(
                        1.dp,
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    modifier = Modifier.width(220.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onBackground,

                        ),
                    onClick = {
                        restoreOptions = false
                        onRestoreClick(null)
                    }) { Text(stringResource(R.string.restore_from_seed)) }
            }, Modifier
                .border(
                    1.dp,
                    color = MaterialTheme.colorScheme.onSecondary.copy(
                        alpha = .2f
                    ),
                    shape = MaterialTheme.shapes.medium,
                ), {
                Button(
                    onClick = {
                        chooseFile()
                        restoreOptions = false
                    },
                    modifier = Modifier.width(220.dp),
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
                        stringResource(R.string.restore_from_backup),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSecondary.copy(
                                alpha = 0.8f
                            )
                        )
                    )
                }
            }, containerColor = MaterialTheme.colorScheme.secondary,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,

                ),
            text = {
                Text(
                    text = stringResource(R.string.restore_options),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                    )
                )
            }
        )

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            Spacer(Modifier.size(24.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.drawable.ic_anon),
                    contentDescription = stringResource(R.string.anon_icon),
                    modifier = Modifier
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                onLogsScreen()
                            }
                        )
                        .size(120.dp)
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxHeight(0.5f)
                    .weight(1.2f),
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.Bottom),
            ) {
                if (!AnonConfig.viewOnly)
                    AnonOutlineButton(
                        onClick = onCreateClick,
                        modifier = Modifier.width(220.dp)
                    ) {
                        Text(stringResource(R.string.create_wallet))
                    }
                AnonOutlineButton(
                    onClick = {
                        if (AnonConfig.viewOnly) {
                            onRestoreFromKeys()
                        } else {
                            restoreOptions = true;
                        }
                    },
                    modifier = Modifier.width(220.dp)
                ) {
                    Text(stringResource(R.string.restore_wallet), color = MaterialTheme.colorScheme.primary)
                }
                IconButton(
                    onClick = onProxySettings,
                    modifier = Modifier.width(200.dp)
                ) {
                    Row {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.proxy),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.proxy), color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

        }
    }
}


@Preview(heightDp = 640, widthDp = 380)
@Composable
private fun OnboardingWelcomePreview() {
    AnonNeroTheme {
        OnboardingWelcome()
    }
}
