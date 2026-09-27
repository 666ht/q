import AnonNeroTheme
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.edit
import com.dokar.sonner.ToastType
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import io.anonero.AnonConfig
import io.anonero.BuildConfig
import io.anonero.R
import io.anonero.model.WalletManager
import io.anonero.ui.home.LockScreen
import io.anonero.ui.home.LockScreenMode
import io.anonero.ui.home.graph.routes.ProxySettingsRoute
import io.anonero.ui.home.graph.routes.SecureWipeRoute
import io.anonero.ui.home.graph.routes.SettingsExportBackUp
import io.anonero.ui.home.graph.routes.SettingsLogs
import io.anonero.ui.home.graph.routes.SettingsNodeRoute
import io.anonero.ui.home.graph.routes.SettingsViewSeedRoute
import io.anonero.ui.onboard.PinSetup
import io.anonero.util.CrazyPassEncoder
import io.anonero.util.PREFS_PIN_HASH
import timber.log.Timber

typealias NavigateTo<T> = (to: T) -> Unit

private const val TAG = "SettingsPage"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    modifier: Modifier = Modifier,
    onBackPress: () -> Unit = {},
    navigateTo: (param: Any) -> Unit = {},
) {
    // Read preferences directly from the application context. This screen is also
    // used by the Preview and must not depend on a Koin qualifier being available.
    val prefs: SharedPreferences = AnonConfig.context
        ?.getSharedPreferences(AnonConfig.PREFS, android.content.Context.MODE_PRIVATE)
        ?: throw IllegalStateException("Application context is not initialized")
    val toastState = rememberToasterState()
    var showLockScreen by remember { mutableStateOf(false) }
    var newPinDialog by remember { mutableStateOf(false) }

    if (showLockScreen) {
        Dialog(
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = true,
                dismissOnBackPress = true,
                decorFitsSystemWindows = false
            ),
            onDismissRequest = { showLockScreen = false }) {
            LockScreen(
                mode = LockScreenMode.VERYFY_PIN,
                modifier = Modifier.fillMaxHeight(0.95f),
                onUnLocked = { _, _ ->
                    showLockScreen = false
                    newPinDialog = true
                }
            )
        }
    }
    if (newPinDialog) {
        Dialog(
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = true,
                dismissOnBackPress = true,
                decorFitsSystemWindows = false
            ),
            onDismissRequest = { newPinDialog = false }) {
            PinSetup(
                changePin = true,
                onNext = {
                    try {
                        WalletManager.instance?.wallet?.setPassword(it)
                        toastState.show("PIN changed successfully", type = ToastType.Success)
                        prefs.edit(commit = true) {
                            putString(
                                PREFS_PIN_HASH,
                                CrazyPassEncoder.encode(
                                    it.toByteArray().let { bytes ->
                                        if (bytes.size < 32) bytes + ByteArray(32 - bytes.size) else bytes
                                    }
                                )
                            )
                        }
                    } catch (e: Exception) {
                        toastState.show(
                            "Error changing PIN. Check logs for more details",
                            type = ToastType.Error
                        )
                        Timber.tag(TAG).e(e)
                    } finally {
                        newPinDialog = false
                    }
                }
            )
        }
    }

    Toaster(
        state = toastState,
        maxVisibleToasts = 2,
        alignment = Alignment.TopCenter,
        darkTheme = true
    )
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackPress) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                title = { Text("") }
            )
        }
    ) { paddingValues ->
        val settingsMenuHeaderStyle = MaterialTheme.typography.titleLarge.copy(
            color = MaterialTheme.colorScheme.primary
        )
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
                item {
                    Text(
                        stringResource(R.string.settings_connection),
                        style = settingsMenuHeaderStyle,
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)
                    )
                    HorizontalDivider(thickness = 2.5.dp)
                    SettingsMenuItem(
                        title = stringResource(R.string.settings_node_settings),
                        onClick = { navigateTo(SettingsNodeRoute) }
                    )
                    HorizontalDivider(thickness = 2.5.dp)
                }
                item {
                    SettingsMenuItem(
                        title = stringResource(R.string.settings_proxy_settings),
                        onClick = { navigateTo(ProxySettingsRoute) }
                    )
                    HorizontalDivider(thickness = 2.5.dp)
                }
                item {
                    Text(
                        stringResource(R.string.settings_security),
                        style = settingsMenuHeaderStyle,
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)
                    )
                }
                if (!AnonConfig.viewOnly) {
                    item {
                        SettingsMenuItem(
                            title = stringResource(R.string.settings_seed),
                            onClick = { navigateTo(SettingsViewSeedRoute) }
                        )
                        HorizontalDivider(thickness = 2.5.dp)
                    }
                }
                item {
                    SettingsMenuItem(
                        title = stringResource(R.string.settings_change_pin),
                        onClick = { showLockScreen = true }
                    )
                    HorizontalDivider(thickness = 2.5.dp)
                }
                item {
                    SettingsMenuItem(
                        title = stringResource(R.string.settings_export_backup),
                        onClick = { navigateTo(SettingsExportBackUp) }
                    )
                    HorizontalDivider(thickness = 2.5.dp)
                }
                item {
                    SettingsMenuItem(
                        title = stringResource(R.string.settings_secure_wipe),
                        onClick = { navigateTo(SecureWipeRoute) }
                    )
                    HorizontalDivider(thickness = 2.5.dp)
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .padding(bottom = 64.dp)
            ) {
                Text(
                    stringResource(
                        R.string.settings_app_version,
                        stringResource(R.string.app_name),
                        BuildConfig.VERSION_NAME
                    ),
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clickable { navigateTo(SettingsLogs) }
                )
            }
        }
    }
}

@Composable
fun SettingsMenuItem(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    title: String = "",
) {
    val settingsMenuStyle = MaterialTheme.typography.titleMedium
    ListItem(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .clickable(
                onClick = onClick,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple()
            ),
        headlineContent = { Text(title, style = settingsMenuStyle) }
    )
}

@Preview(device = "id:pixel_5")
@Composable
private fun SettingsPagePrev() {
    AnonNeroTheme { SettingsPage() }
}
