package io.anonero.ui.home.settings

import AnonNeroTheme
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import io.anonero.AnonConfig
import io.anonero.R
import io.anonero.icons.AnonIcons
import io.anonero.services.AnonWalletHandler
import io.anonero.services.TorService
import io.anonero.util.WALLET_PROXY
import io.anonero.util.WALLET_PROXY_PORT
import io.anonero.util.WALLET_USE_TOR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import timber.log.Timber
import java.util.regex.Pattern
import androidx.core.content.edit
import androidx.core.net.toUri

private const val TAG = "ProxySettings"


fun isNumericAddress(address: String): Boolean {
    val ipv4Pattern = Pattern.compile(
        "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$"
    )
    val ipv6Pattern = Pattern.compile(
        "^(?:[A-Fa-f0-9]{1,4}:){7}[A-Fa-f0-9]{1,4}$"
    )
    return ipv4Pattern.matcher(address).matches() || ipv6Pattern.matcher(address).matches()
}

class ProxySettingsViewModel(
    private val anonWalletHandler: AnonWalletHandler,
    private val torService: TorService,
    private val anonPrefs: SharedPreferences,
) : ViewModel() {

    private val addressValidationError = MutableLiveData<String?>()
    private val _proxyLoading = MutableLiveData(false)
    private val _proxyAddress = MutableLiveData<Pair<String, Int>?>()
    private val _useManualProxy = MutableLiveData(false)
    val validationError: LiveData<String?> get() = addressValidationError
    val proxy: LiveData<Pair<String, Int>?> get() = _proxyAddress
    val loading: LiveData<Boolean> get() = _proxyLoading
    val useManualProxy: LiveData<Boolean> get() = _useManualProxy
    val logs: LiveData<List<String>> = torService.torLogs
        .runningFold<String, List<String>>(emptyList()) { accumulator, value ->
            accumulator + value
        }
        .asLiveData()


    init {
        updateProxyState()
        viewModelScope.launch {
            torService.socksFlow.collect {
                if (anonPrefs.getBoolean(WALLET_USE_TOR, true)
                    && torService.socks != null
                ) {
                    val socks = torService.socks!!
                    setProxy(socks.address.value, socks.port.value)
                }
            }
        }
    }

    fun setProxy(proxy: String, port: Int): Job {
        _proxyLoading.postValue(true)
        return viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!isNumericAddress(proxy)) {
                    addressValidationError.postValue(AnonConfig.context?.getString(R.string.invalid_proxy_address) ?: "")
                    return@launch
                }
                if (port > 65535) {
                    addressValidationError.postValue(AnonConfig.context?.getString(R.string.invalid_port) ?: "")
                    return@launch
                }
                anonWalletHandler.setProxy(proxy = proxy, port = port)
                updateProxyState()
                if (!anonPrefs.getBoolean(WALLET_USE_TOR, true)) {
                    torService.stop()
                }
                _proxyLoading.postValue(false)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e)
                addressValidationError.postValue(e.message)
            } finally {
                _proxyLoading.postValue(false)
            }
        }
    }

    private fun updateProxyState() {
        _proxyAddress.postValue(anonWalletHandler.getProxy())
        _useManualProxy.postValue(!anonPrefs.getBoolean(WALLET_USE_TOR, true))
        val socks = torService.socks
        if (anonPrefs.getBoolean(WALLET_USE_TOR, true) && socks != null) {
            anonPrefs.edit {
                putString(WALLET_PROXY, socks.address.value)
                    .putInt(WALLET_PROXY_PORT, socks.port.value)
            }
            _proxyAddress.postValue(anonWalletHandler.getProxy())
        }
    }

    fun enableTor(enable: Boolean) {
        if (enable) {
            anonPrefs.edit { putBoolean(WALLET_USE_TOR, true) }
            _useManualProxy.postValue(false)
            torService.start()
        } else {
            anonPrefs.edit { putBoolean(WALLET_USE_TOR, false) }
            _useManualProxy.postValue(true)
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxySettings(onBackPress: () -> Unit = {}) {
    var proxyAddress by remember { mutableStateOf("") }
    var showLogs by remember { mutableStateOf(false) }
    val proxyViewModel = koinViewModel<ProxySettingsViewModel>()
    val validationError by proxyViewModel.validationError.observeAsState(null)
    val proxy by proxyViewModel.proxy.observeAsState(null)
    val loading by proxyViewModel.loading.observeAsState(false)
    val useManualProxy by proxyViewModel.useManualProxy.observeAsState(false)
    var proxyPort by remember { mutableStateOf<Int?>(null) }
    val labelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()

    val view = LocalView.current
    LaunchedEffect(proxy) {
        if (proxy != null) {
            proxyAddress = proxy?.first.toString()
            proxyPort = proxy?.second
        }
    }

    if (showLogs) {
        ModalBottomSheet(
            onDismissRequest = {
                showLogs = false
            },
            containerColor = Color.Black,
            sheetState = sheetState,
            contentColor = Color.White,
            contentWindowInsets = {
                WindowInsets(
                    left = 0.dp,
                    top = 0.dp,
                    right = 0.dp,
                    bottom = 0.dp
                )
            },
            shape = MaterialTheme.shapes.large,
            properties = ModalBottomSheetProperties(
                shouldDismissOnBackPress = true,
            ),
        ) {
            TorLogs()
        }

    }

    Scaffold(
        modifier = Modifier.animateContentSize(),
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(
                            onClick = onBackPress
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                showLogs = !showLogs
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null)
                        }
                    },
                    title = {
                        Text(stringResource(id = R.string.proxy))
                    },
                )
                Box(modifier = Modifier.height(4.dp)) {
                    this@Column.AnimatedVisibility(visible = loading) {
                        LinearProgressIndicator(
                            trackColor = MaterialTheme.colorScheme.primary.copy(
                                alpha = 0.2f
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    ) {
        Column(
            modifier = Modifier.padding(it),
            verticalArrangement = Arrangement.Top
        ) {
            Card(
                modifier = Modifier
                    .padding(
                        horizontal = 12.dp,
                        vertical = 6.dp
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Transparent,
                ),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.onSecondary.copy(
                        alpha = .2f
                    ),
                )
            ) {
                ListItem(
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    ),
                    headlineContent = {
                        Text(stringResource(id = R.string.manual_proxy))
                    },
                    trailingContent = {
                        Switch(
                            checked = useManualProxy,
                            thumbContent = {
                                Text(if (useManualProxy) stringResource(id = R.string.on) else stringResource(id = R.string.off))
                            },
                            onCheckedChange = { onState ->
                                proxyViewModel.enableTor(!onState)
                                view.performHapticFeedback(
                                    HapticFeedbackConstants.KEYBOARD_TAP
                                )
                            })
                    }
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Row(
                Modifier
                    .padding(
                        horizontal = 8.dp,
                    ),
            ) {
                OutlinedTextField(
                    value = proxyAddress,
                    enabled = useManualProxy,
                    isError = validationError != null,
                    supportingText = {
                        if (validationError != null)
                            Text(validationError.toString())
                    },
                    shape = MaterialTheme.shapes.medium,
                    onValueChange = { text ->
                        proxyAddress = text
                        try {
                            text.toUri()
                        } catch (_: Exception) {

                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Next,
                    ),
                    placeholder = {
                        Text(
                            text = stringResource(id = R.string.proxy_address),
                            color = labelColor
                        )
                    },
                    modifier = Modifier
                        .weight(2.5f)
                        .padding(
                            horizontal = 8.dp
                        )
                )
                OutlinedTextField(
                    enabled = useManualProxy,
                    value = proxyPort?.toString() ?: "",
                    shape = MaterialTheme.shapes.medium,
                    isError = proxyPort == null,
                    onValueChange = { port ->
                        proxyPort = port.toIntOrNull()
                    },
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                        keyboardType = KeyboardType.Number
                    ),
                    placeholder = {
                        Text(
                            text = stringResource(id = R.string.proxy_port),
                            color = labelColor
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                )
            }
            if (useManualProxy)
                OutlinedButton(
                    enabled = proxyAddress.isNotEmpty() && proxyPort != null,
                    onClick = {
                        if (loading) {
                            return@OutlinedButton
                        }
                        proxyViewModel.setProxy(
                            proxyAddress, proxyPort!!
                        ).invokeOnCompletion { error ->
                            if (error == null) {
                                scope.launch(Dispatchers.Main) {
                                    Toast.makeText(
                                        AnonConfig.context,
                                        AnonConfig.context?.getString(R.string.proxy_updated) ?: "Proxy updated",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 16.dp
                        ),

                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(12.dp)
                ) {
                    Text(stringResource(id = R.string.update_external_proxy))
                }

            if (!useManualProxy) {
                Column(
                    modifier = Modifier
                        .padding(top = 48.dp)
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.Top,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        AnonIcons.ShieldCheck, contentDescription = "tor",
                        tint = Color.White.copy(
                            alpha = 0.8f
                        ), modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(id = R.string.using_built_in_tor),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onBackground.copy(
                                alpha = 0.8f
                            )
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(
                            horizontal = 16.dp
                        )
                    )
                }
            }
        }

    }
}

@Composable
fun TorLogs() {
    val listState = rememberLazyListState()
    val proxyViewModel = koinViewModel<ProxySettingsViewModel>()
    val logs by proxyViewModel.logs.observeAsState(initial = emptyList())

    LazyColumn(
        state = listState,
        modifier = Modifier
            .padding(
                horizontal = 8.dp
            )
            .navigationBarsPadding()
            .fillMaxSize(),
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        item {
            Text(
                stringResource(id = R.string.tor_logs), textAlign = TextAlign.Center, modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        vertical = 12.dp,
                    )
            )
        }
        items(logs.size) { index ->
            Text(
                logs[index],
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Light,
                maxLines = 30,
                lineHeight = 12.sp,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        vertical = 4.dp
                    )
                    .background(Color.Transparent),
            )
        }
    }

}

@Preview(device = "id:pixel_5")
@Composable
private fun ProxySettingsPreview() {
    AnonNeroTheme {
        ProxySettings()
    }
}
