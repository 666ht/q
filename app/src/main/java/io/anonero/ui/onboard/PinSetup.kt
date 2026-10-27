package io.anonero.ui.onboard

import AnonNeroTheme
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.anonero.R
import io.anonero.icons.AnonIcons
import io.anonero.util.ShakeConfig
import io.anonero.util.rememberShakeController
import io.anonero.util.shake
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


const val BackSpaceKey = -2
const val ConfirmKey = -3
val keys = listOf(
    1, 2, 3, 4, 5, 6, 7, 8, 9, BackSpaceKey, 0, ConfirmKey
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PinSetup(
    changePin: Boolean = false,
    onNext: (pin: String) -> Unit = {},
) {
    val view = LocalView.current
    val pinEntered = remember { mutableStateListOf<Int>() }
    val currentPin = remember { mutableStateListOf<Int>() }
    var confirming by remember { mutableStateOf(false) }
    var pinNotMatch by remember { mutableStateOf(false) }
    val errorColor: Color by animateColorAsState(
        if (pinNotMatch) MaterialTheme.colorScheme.error else Color.White, label = "error_anim"
    )
    val errorShake = rememberShakeController()
    val context = rememberCoroutineScope()

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
                    .padding(top = 8.dp)
                    .weight(.2f),
                horizontalArrangement = Arrangement.Center
            ) {

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_anon),
                        contentDescription = stringResource(R.string.anon_icon),
                        modifier = Modifier
                            .size(200.dp)
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                }
                Spacer(modifier = Modifier)
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.padding(top = 24.dp))
                Text(if (confirming) stringResource(R.string.confirm_pin) else if (changePin) stringResource(R.string.enter_new_pin) else stringResource(
                    R.string.enter_pin
                ))
                Spacer(modifier = Modifier.padding(top = 12.dp))
                Row(
                    Modifier
                        .height(24.dp)
                        .shake(errorShake),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    currentPin.map {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .animateContentSize()
                                .clip(RoundedCornerShape(50))
                                .background(errorColor)
                                .padding(horizontal = 24.dp)
                        ) { }
                    }
                }

            }
            Box(
                modifier = Modifier
                    .weight(.9f)
                    .wrapContentHeight()
            ) {

                LazyVerticalGrid(
                    modifier = Modifier
                        .padding(top = 34.dp)
                        .padding(horizontal = 24.dp),
                    columns = GridCells.Fixed(3),
                    verticalArrangement = Arrangement.Top,
                    horizontalArrangement = Arrangement.Center,
                    contentPadding = PaddingValues(8.dp)
                ) {

                    items(keys.size) { index ->
                        val key = keys[index]
                        if (currentPin.size == 0 && key == BackSpaceKey) {
                            return@items
                        }
                        if (currentPin.size <= 4 && key == ConfirmKey) {
                            return@items
                        }
                        Column(
                            modifier = Modifier
                                .size(120.dp)
                                .clip(shape = CircleShape)
                                .align(Alignment.Center)
                                .combinedClickable(
                                    onClick = {
                                        if (key == BackSpaceKey && currentPin.isNotEmpty()) {
                                            currentPin.removeAt(currentPin.size - 1)
                                        } else if (key == ConfirmKey) {
                                            if (confirming) {
                                                if (currentPin.joinToString() == pinEntered.joinToString()) {
                                                    onNext.invoke(currentPin.joinToString(separator = ""))
                                                } else {
                                                    errorShake.shake(
                                                        ShakeConfig(
                                                            6, translateX = 5f
                                                        )
                                                    )
                                                    context.launch {
                                                        pinNotMatch = true
                                                        repeat(6) {
                                                            delay(50)
                                                            view.performHapticFeedback(
                                                                HapticFeedbackConstants.CONTEXT_CLICK
                                                            )
                                                        }
                                                        delay(100)
                                                        pinNotMatch = false
                                                    }
                                                }
                                            } else {
                                                pinEntered.clear()
                                                pinEntered.addAll(currentPin)
                                                currentPin.clear()
                                                confirming = true
                                            }
                                        } else {
                                            if (currentPin.size < 12) {
                                                currentPin.add(key)
                                            }
                                        }
                                        //update state

                                        view.performHapticFeedback(
                                            HapticFeedbackConstants.KEYBOARD_TAP
                                        )
                                    },
                                    onLongClick = {
                                        if (key == BackSpaceKey) {
                                            currentPin.clear()
                                            view.performHapticFeedback(
                                                HapticFeedbackConstants.LONG_PRESS
                                            )
                                        }

                                    },
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (key == -2 || key == -3) {

                                return@items Icon(
                                    imageVector =
                                        if (key == -2) AnonIcons.Backspace else Icons.TwoTone.Check,
                                    tint = Color.White,
                                    contentDescription = stringResource(R.string.clear),
                                    modifier = Modifier
                                        .size(28.dp)
                                )
                            }
                            Text(
                                text = "$key",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center,
                            )
                        }

                    }
                }

            }
        }
    }
}

@Preview(showBackground = true, device = "id:pixel_8")
@Composable
private fun SetupNodeComposablePreview() {
    AnonNeroTheme {
        PinSetup(
        )
    }
}
