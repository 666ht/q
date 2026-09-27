package io.anonero.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import io.anonero.R
import io.anonero.util.ShakeConfig
import io.anonero.util.rememberShakeController
import io.anonero.util.shake
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


@Composable
fun SubAddressLabelDialog(label: String, onSave: (String) -> Unit, onCancel: () -> Unit = {}) {

    var labelString by remember {
        mutableStateOf(
            TextFieldValue(
                text = label,
                selection = TextRange(label.length)
            )
        )
    }
    val focusRequester = remember { FocusRequester() }
    val errorShake = rememberShakeController()
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    fun saveLabel() {
        if (labelString.text.isEmpty()) {
            scope.launch {
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
        } else {
            onSave(labelString.text)
        }
    }

    LaunchedEffect(true) {
        focusRequester.requestFocus()
    }

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
            dismissOnBackPress = true
        ),
        title = {
            Text(
                text = stringResource(R.string.set_subaddress_label),
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 16.sp
                )
            )
        },
        text = {
            OutlinedTextField(
                value = labelString,
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                ),
                isError = labelString.text.isEmpty(),
                supportingText = {
                    if (labelString.text.isEmpty()) {
                        Text(stringResource(R.string.label_cannot_be_empty))
                    }
                },
                minLines = 1,
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
                        saveLabel()
                    }
                ),
                onValueChange = {
                    if (it.text.length <= 24) { // Limit to 10 characters
                        labelString = it
                    }
                },
            )
        },
        onDismissRequest = {
            onCancel()
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
                    if (labelString.text.isEmpty()) {
                        saveLabel()
                    } else {
                        onSave(labelString.text)
                    }
                }) { Text(stringResource(R.string.update)) }
        },
        dismissButton = {
            Button(
                onClick = {
                    onCancel()
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

}
