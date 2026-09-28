package io.anonero.ui.home

import AnonNeroTheme
import android.content.SharedPreferences
import android.util.Log
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import io.anonero.R
import io.anonero.icons.AnonIcons
import io.anonero.services.WalletState
import io.anonero.ui.viewmodels.AppViewModel
import io.anonero.util.CrazyPassEncoder
import io.anonero.util.PREFS_PIN_HASH
import io.anonero.util.ShakeConfig
import io.anonero.util.WALLET_PREFERENCES
import io.anonero.util.rememberShakeController
import io.anonero.util.shake
import io.anonero.util.shuffleExcept
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import timber.log.Timber

const val BackSpaceKey = -2
const val ConfirmKey = -3
val keys = listOf(
    1, 2, 3, 4, 5, 6, 7, 8, 9, BackSpaceKey, 0, ConfirmKey
)

@Serializable
enum class LockScreenMode {
    OPEN_WALLET, LOCK_SCREEN, VERYFY_PIN
}

@Serializable
enum class LockScreenShortCut {
    HOME, RECEIVE, SEND
}

private const val TAG = "LockScreen"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LockScreen(
    modifier: Modifier = Modifier,
    mode: LockScreenMode = LockScreenMode.OPEN_WALLET,
    onUnLocked: (String, LockScreenShortCut) -> Unit = { _, _ -> }
) {
    val view = LocalView.current
    val prefs = koinInject<SharedPreferences>(named(WALLET_PREFERENCES))
    val currentPin = remember { mutableStateListOf<Int>() }
    var pinError by remember { mutableStateOf(false) }
    var pinKeys by remember { mutableStateOf(keys) }
    val walletState = koinInject<WalletState>()
    val errorColor: Color by animateColorAsState(
        if (pinError) MaterialTheme.colorScheme.error else Color.White, label = "error_anim"
    )
    val errorShake = rememberShakeController()
    val scope = rememberCoroutineScope()
    val appViewModel: AppViewModel = koinViewModel()

    LaunchedEffect(true) {
        pinKeys = keys.shuffleExcept(keys.indexOf(BackSpaceKey), keys.indexOf(ConfirmKey))
    }

    fun showError() {
        errorShake.shake(
            ShakeConfig(
                6, translateX = 5f
            )
        )
        scope.launch {
            pinError = true
            repeat(6) {
                delay(50)
                view.performHapticFeedback(
                    HapticFeedbackConstants.CONTEXT_CLICK
                )
            }
            delay(100)
            pinError = false
        }
    }

    fun checkPin(shortCut: LockScreenShortCut) {
        val pin = currentPin.joinToString(separator = "")
        if (pin.length >= 4) {
            scope.launch(Dispatchers.IO) {
                try {
                    if (mode == LockScreenMode.VERYFY_PIN) {
                        val prefsHash = prefs.getString(PREFS_PIN_HASH, "")
                        val pinHash = CrazyPassEncoder.encode(
                            pin.toByteArray().let { bytes ->
                                if (bytes.size < 32) {
                                    bytes + ByteArray(32 - bytes.size)
                                } else bytes
                            }
                        )
                        if (prefsHash == pinHash) {
                            withContext(Dispatchers.Main) {
                                onUnLocked(pin, shortCut)
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                showError()
                            }
                        }
                    } else if (mode == LockScreenMode.OPEN_WALLET) {
                        val result = appViewModel.openWallet(pin)
                        prefs.edit(commit = true) {
                            putString(
                                PREFS_PIN_HASH,
                                CrazyPassEncoder.encode(
                                    pin.toByteArray().let { bytes ->
                                        if (bytes.size < 32) {
                                            bytes + ByteArray(32 - bytes.size)
                                        } else bytes
                                    }
                                )
                            )
                        }
                        withContext(Dispatchers.Main) {
                            if (result) {
                                appViewModel.startService()
                                withContext(Dispatchers.Main) {
                                    onUnLocked(pin, shortCut)
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    showError()
                                }
                            }
                        }
                    } else {
                        //if wallet is already open, stop background sync from lock screen

                        // Verify PIN locally against stored hash — instant, no network needed.
                        val prefsHash = prefs.getString(PREFS_PIN_HASH, "")
                        val pinHash = CrazyPassEncoder.encode(
                            pin.toByteArray().let { bytes ->
                                if (bytes.size < 32) bytes + ByteArray(32 - bytes.size)
                                else bytes
                            }
                        )
                        if (prefsHash != pinHash) {
                            withContext(Dispatchers.Main) { showError() }
                        } else {
                            withContext(Dispatchers.Main) {
                                onUnLocked(pin, shortCut)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e)
                    Timber.tag(TAG).i("checkPin: %s", e.message)
                    showError()
                }
            }
        } else {
            showError()
        }

    }

    Scaffold(modifier = modifier) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceAround,
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
                Text(
                    if (mode == LockScreenMode.VERYFY_PIN)
                        stringResource(R.string.enter_current_pin)
                    else
                        stringResource(R.string.unlock_wallet)
                )
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
            Column(
                modifier = Modifier
                    .weight(.8f)
                    .wrapContentHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LazyVerticalGrid(
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 0.dp),
                    columns = GridCells.Fixed(3),
                    verticalArrangement = Arrangement.Top,
                    horizontalArrangement = Arrangement.Center,
                    contentPadding = PaddingValues(
                        vertical = 8.dp,
                    )
                ) {
                    items(pinKeys.size) { index ->
                        val key = pinKeys[index]
                        if (currentPin.size == 0 && key == BackSpaceKey) {
                            return@items
                        }
                        if (currentPin.size <= 4 && key == ConfirmKey) {
                            return@items
                        }
                        Column(
                            modifier = Modifier
                                .size(100.dp)
                                .clip(shape = CircleShape)
                                .combinedClickable(
                                    onClick = {
                                        if (key == BackSpaceKey && currentPin.isNotEmpty()) {
                                            currentPin.removeAt(currentPin.size - 1)
                                        } else if (key == ConfirmKey) {
                                            checkPin(LockScreenShortCut.HOME)
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

                if (mode != LockScreenMode.VERYFY_PIN) {
                    Row(
                        modifier = Modifier
                            .alpha(if (currentPin.size > 4) 1f else 0f)
                            .wrapContentHeight(),
                    ) {
                        IconButton(onClick = {
                            if (currentPin.size > 4)
                                checkPin(LockScreenShortCut.RECEIVE)
                        }) {
                            Icon(
                                AnonIcons.ArrowDownLeft,
                                contentDescription = stringResource(R.string.receive),
                                modifier = Modifier.size(64.dp)
                            )
                        }
                        Spacer(Modifier.width(24.dp))
                        IconButton(onClick = {
                            if (currentPin.size > 4)
                                checkPin(LockScreenShortCut.SEND)
                        }) {
                            Icon(
                                AnonIcons.ArrowUpRight,
                                tint = MaterialTheme.colorScheme.primary,
                                contentDescription = stringResource(R.string.send),
                                modifier = Modifier.size(64.dp)
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
        LockScreen()
    }
}
