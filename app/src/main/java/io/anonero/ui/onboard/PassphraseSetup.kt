package io.anonero.ui.onboard

import AnonNeroTheme
import AnonOutlineButton
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.anonero.R
import androidx.compose.ui.res.stringResource


@Composable
fun SetupPassphrase(
    onBackPressed: () -> Unit = {},
    oNextPressed: (passphrase: String) -> Unit = {},
) {
    var passPhraseMatched by remember { mutableStateOf(false) }
    var passPhrase by remember { mutableStateOf("") }
    var passPhraseConfirm by remember { mutableStateOf("") }


    LaunchedEffect(passPhraseConfirm, passPhrase) {
        passPhraseMatched = if (passPhraseConfirm.isNotEmpty()) {
            passPhraseConfirm == passPhrase
        } else {
            false
        }
    }

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
                    modifier = Modifier.size(24.dp),
                    onClick = {
                        onBackPressed()
                    }
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = Color.White
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.drawable.ic_anon),
                    contentDescription = stringResource(R.string.anon_icon),
                    modifier = Modifier
                        .size(120.dp)
                )
                Spacer(Modifier.height(48.dp))
            }
            Column(
                modifier = Modifier
                    .weight(.8f)
                    .padding(top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.passphrase),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    },
                    supportingContent = {
                        OutlinedTextField(
                            value = passPhrase,
                            shape = MaterialTheme.shapes.medium,
                            onValueChange = {
                                passPhrase = it
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Next,
                                keyboardType = KeyboardType.Password
                            ),
                            label = { Text(text = "") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                )
                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.confirm_passphrase),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    },
                    supportingContent = {
                        OutlinedTextField(
                            value = passPhraseConfirm,
                            shape = MaterialTheme.shapes.medium,
                            label = { Text(text = "") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Done,
                                keyboardType = KeyboardType.Password
                            ),
                            isError = passPhraseConfirm != passPhrase,
                            supportingText = {
                                if (passPhraseConfirm != passPhrase) {
                                    Text(text = stringResource(R.string.passphrases_do_not_match))
                                }
                            },
                            onValueChange = {
                                passPhraseConfirm = it
                            },

                            )
                    },
                )
            }

            AnonOutlineButton(
                enabled = passPhraseMatched && passPhrase.length >= 4,
                onClick = {
                    oNextPressed(passPhrase)
                },
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

@Preview(showBackground = true, device = "id:pixel_8")
@Composable
private fun SetupNodeComposablePreview() {
    AnonNeroTheme {
        SetupPassphrase()
    }
}