package io.anonero.ui.home

import AnonNeroTheme
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dokar.sonner.ToastType
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.anonero.AnonConfig
import io.anonero.R
import io.anonero.icons.AnonIcons
import io.anonero.model.TransactionInfo
import io.anonero.model.WalletManager
import io.anonero.services.WalletState
import io.anonero.ui.components.WalletProgressIndicator
import io.anonero.ui.home.graph.routes.CoinsScreenRoute
import io.anonero.ui.home.graph.routes.ReviewTransactionRoute
import io.anonero.ui.home.graph.routes.ResetSyncRoute
import io.anonero.ui.home.graph.routes.SendScreenRoute
import io.anonero.ui.home.graph.routes.SettingsNodeRoute
import io.anonero.ui.home.graph.routes.TransactionsRoute
import io.anonero.ui.home.spend.qr.ExportType
import io.anonero.ui.home.spend.qr.ImportEvents
import io.anonero.ui.home.spend.qr.QRExchangeScreen
import io.anonero.ui.home.spend.qr.SpendQRExchangeParam
import io.anonero.ui.home.spend.qr.URQRScanner
import io.anonero.util.Formats
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import io.anonero.services.TorService
import io.anonero.util.WALLET_PREFERENCES
import io.anonero.util.WALLET_USE_TOR
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds
import androidx.compose.ui.res.painterResource


class TransactionsViewModel : ViewModel() {
    private val walletState: WalletState by inject(WalletState::class.java)

    val balance = walletState.balanceInfo.map {
        it ?: 0L
    }.asLiveData()

    val transactions = walletState.transactions
        .asLiveData()

}

private const val TAG = "Transactions"

@OptIn(
    ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class,
    ExperimentalSharedTransitionApi::class
)
@Composable
fun TransactionScreen(
    modifier: Modifier = Modifier,
    onItemClick: (TransactionInfo) -> Unit = {},
    navigateTo: (route: Any) -> Unit = {},
    navigateToShortCut: (shortcut: LockScreenShortCut) -> Unit = {},
    animatedContentScope: AnimatedContentScope,
    sharedTransitionScope: SharedTransitionScope,
) {

    val walletState = koinInject<WalletState>()
    val balance by walletState.balanceInfo.collectAsState(null)
    val transactions by walletState.transactions.collectAsState(listOf())
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior { true }
    var showExitDialog by remember { mutableStateOf(false) }
    var scanFailure by remember { mutableStateOf<String?>(null) }
    var spendDialog by remember { mutableStateOf<String?>(null) }
    var broadcastSignedTxPath by remember { mutableStateOf<String?>(null) }
    var broadcastProgree by remember { mutableStateOf(false) }
    var settingBSync by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var qrScannerParam by remember { mutableStateOf<SpendQRExchangeParam?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    val torService = koinInject<TorService>()
    val anonPrefs = koinInject<android.content.SharedPreferences>(named(WALLET_PREFERENCES))
    val showLockScreen by walletState.backgroundSyncFlow.asLiveData().observeAsState(walletState.backgroundSync)
    val hideAmounts by walletState.hideAmountsFlow.asLiveData().observeAsState(false)
    val scope = rememberCoroutineScope()
    val toastState = rememberToasterState()
    val scanUnsignedTxText = stringResource(R.string.scan_unsigned_tx)
    val keyImagesImportedText = stringResource(R.string.key_images_imported)
    val activity = LocalActivity.current;
    val context = LocalContext.current
    val customXmrFont = remember {
        FontFamily(Typeface.createFromAsset(context.assets, "160ee2f7b959256f6a2e09db2fa9060b.ttf"))
    }

    if (broadcastSignedTxPath != null) {
        AlertDialog(
            modifier = Modifier
                .border(
                    1.dp,
                    color = MaterialTheme.colorScheme.onSecondary.copy(
                        alpha = .2f
                    ),
                    shape = MaterialTheme.shapes.medium,
                ),
            containerColor = MaterialTheme.colorScheme.background,
            properties = DialogProperties(
                securePolicy = SecureFlagPolicy.SecureOn, dismissOnBackPress = false
            ),
            title = {
                Text(
                    text = "Anon",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 18.sp
                    )
                )
            },
            text = {
                Text(stringResource(R.string.broadcast_transaction_confirm))
            },
            onDismissRequest = {
                scanFailure = null
            },
            dismissButton = {
                Button(
                    onClick = {
                        broadcastSignedTxPath = null
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
                    Text(stringResource(R.string.cancel),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSecondary.copy(
                                alpha = 0.8f
                            )
                        )
                    )
                }
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
                        broadcastProgree = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                WalletManager.instance?.wallet?.submitTransaction(
                                    broadcastSignedTxPath ?: ""
                                )
                                WalletManager.instance?.wallet?.refreshHistory()
                                WalletManager.instance?.wallet?.store()
                            } catch (ex: Exception) {
                                Timber.tag(TAG).e(ex)
                            } finally {
                                broadcastProgree = false
                                broadcastSignedTxPath = null
                            }
                        }
                    }) { Text(stringResource(R.string.yes)) }
            },
        )
    }
    if (showExitDialog) {
        AlertDialog(
            modifier = Modifier
                .border(
                    1.dp,
                    color = MaterialTheme.colorScheme.onSecondary.copy(
                        alpha = .2f
                    ),
                    shape = MaterialTheme.shapes.medium,
                )
                .fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.background,
            properties = DialogProperties(
                securePolicy = SecureFlagPolicy.SecureOn, dismissOnBackPress = false
            ),
            title = {
                Text(
                    text = "Anon",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 18.sp
                    )
                )
            },
            text = {
                Text(stringResource(R.string.exit_app_confirm, stringResource(R.string.app_name)))
            },
            onDismissRequest = {
                showExitDialog = false
            },
            dismissButton = {
                Button(
                    onClick = {
                        showExitDialog = false
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
                    Text(stringResource(R.string.cancel),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSecondary.copy(
                                alpha = 0.8f
                            )
                        )
                    )
                }
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
                        activity?.finishAffinity()
                    }) { Text(stringResource(R.string.exit)) }
            },
        )
    }

    if (qrScannerParam != null) {
        QRExchangeScreen(
            params = qrScannerParam!!,
            onNavigateToHome = {
                navigateTo.invoke(TransactionsRoute)
            },
            onBackPressed = {
                qrScannerParam = null
            },
            onCtaCalled = {
                qrScannerParam = null
                showScanner = true
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    if (showScanner)
        URQRScanner(
            onQRCodeScanned = {
                if (it.isNotEmpty()) {
                    scope.launch(Dispatchers.IO) {
                        SendScreenRoute.parse(it)?.let { paymentUri ->
                            withContext(Dispatchers.Main) {
                                navigateTo(paymentUri)
                            }
                        }
                    }
                }
                showScanner = false
            },
            onDismiss = {
                showScanner = false
            },
            onURResult = {
                when (it.getOrNull()) {
                    ImportEvents.IMPORT_OUTPUTS -> {
                        showScanner = false
                        qrScannerParam = SpendQRExchangeParam(
                            exportType = ExportType.IMAGE,
                            title = context.getString(R.string.key_images),
                            ctaText = scanUnsignedTxText,
                        )
                    }

                    null -> {

                    }

                    ImportEvents.IMPORT_KEY_IMAGES -> {
                        toastState.show(
                            keyImagesImportedText,
                            type = ToastType.Success,
                            duration = 4.seconds
                        )
                    }

                    ImportEvents.IMPORT_UNSIGNED_TX -> {
                        if (!AnonConfig.viewOnly) {
                            navigateTo.invoke(
                                ReviewTransactionRoute(
                                    "",
                                )
                            );
                        }
                    }

                    ImportEvents.IMPORT_SIGNED_TX -> {}
                }
                showScanner = false
            },
            showScanner = showScanner
        )

    if (spendDialog != null) {
        AlertDialog(
            modifier = Modifier
                .border(
                    1.dp,
                    color = MaterialTheme.colorScheme.onSecondary.copy(
                        alpha = .2f
                    ),
                    shape = MaterialTheme.shapes.medium,
                ),
            containerColor = MaterialTheme.colorScheme.background,
            properties = DialogProperties(
                securePolicy = SecureFlagPolicy.SecureOn, dismissOnBackPress = false
            ),
            title = {
                Text(
                    text = "Anon",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 18.sp
                    )
                )
            },
            text = {
                Text("${spendDialog}")
            },
            onDismissRequest = {
                spendDialog = null
            },
            dismissButton = {
                Button(
                    onClick = {
                        spendDialog = null
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
                        "Ok",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSecondary.copy(
                                alpha = 0.8f
                            )
                        )
                    )
                }
            },
            confirmButton = {

            },
        )
    }
    val showIndefiniteLoading by walletState.isLoading.asLiveData().observeAsState(false)
    val refreshState = rememberPullToRefreshState();
    val view = LocalView.current;
    val torConnected by torService.socksFlow.asLiveData().observeAsState(torService.socks != null)
    val useTor = anonPrefs.getBoolean(WALLET_USE_TOR, true)
    val hazeState = rememberHazeState()


    BackHandler {
        showExitDialog = true
    }

    LaunchedEffect(true) {
        scope.launch {
            refreshState.snapTo(0f)
        }
    }

    Toaster(
        state = toastState,
        maxVisibleToasts = 4,
        alignment = Alignment.BottomCenter,
        darkTheme = true,
        richColors = true,
        containerPadding = PaddingValues(
            bottom = 84.dp,
        ),
    )
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors().copy(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                modifier = Modifier
                    .hazeEffect(
                        state = hazeState,
                        style = HazeDefaults.style(
                            backgroundColor = MaterialTheme.colorScheme.surface.copy(
                                alpha = 0.95f
                            ),
                            blurRadius = 26.dp
                        ),
                    )
                    .fillMaxWidth(),
                title = {
                    Text(if (AnonConfig.viewOnly) "[ИΞR0]" else "[ΛИ0И]")
                },
                actions = {
                    IconButton(
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = Color.White
                        ),
                        onClick = {
                            showScanner = true
                        }
                    ) {
                        Icon(AnonIcons.Scan, contentDescription = stringResource(R.string.scan))
                    }
                    IconButton(
                        onClick = {
                            navigateTo(SettingsNodeRoute)
                        }
                    ) {
                        val torIconColor = if (useTor && torConnected == false) Color.Red else Color.White
                        Icon(
                            painterResource(R.drawable.ic_tor),
                            contentDescription = stringResource(R.string.tor_status),
                            tint = torIconColor
                        )
                    }
                    val context = LocalContext.current
//                    LockButton(
//                        onLock = {
//                            scope.launch {
//                                try {
//                                    settingBSync = true
//                                    walletState.blockUpdates(true)
//                                    withContext(Dispatchers.IO) {
//                                        WalletManager.instance?.wallet?.let { wallet: Wallet ->
//                                            if (wallet.startBackgroundSync()) {
//                                                walletState.setBackGroundSync(true)
//                                            }
//                                        }
//                                    }
//                                } finally {
//                                    walletState.blockUpdates(false)
//                                    settingBSync = false
//                                }
//                            }
//                        }, loading = settingBSync
//                    )
                    IconButton(
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = Color.White
                        ),
                        onClick = {
                            showMenu = !showMenu
                        }
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                        DropdownMenu(
                            expanded = showMenu,
                            containerColor = MaterialTheme.colorScheme.background,
                            modifier = Modifier
                                .border(
                                    1.dp,
                                    color = MaterialTheme.colorScheme.onSecondary.copy(
                                        alpha = .2f
                                    ),
                                    shape = MaterialTheme.shapes.medium,
                                ),
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.coin_control)) },
                                onClick = {
                                    navigateTo(CoinsScreenRoute)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.refresh)) },
                                onClick = {
                                    showMenu = false
                                    scope.launch {
                                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                        walletState.refresh()
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.resync_blockchain)) },
                                onClick = {
                                    showMenu = false
                                    navigateTo(ResetSyncRoute)
                                }
                            )
                            if (AnonConfig.viewOnly) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.show_outputs)) },
                                    onClick = {
                                        qrScannerParam =
                                            SpendQRExchangeParam(
                                                exportType = ExportType.OUTPUT,
                                                title = context.getString(R.string.outputs),
                                                ctaText = context.getString(R.string.scan_key_images),
                                            )
                                    }
                                )
                            }
                            if (!AnonConfig.viewOnly) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_key_images)) },
                                    onClick = {
                                        qrScannerParam =
                                            SpendQRExchangeParam(
                                                exportType = ExportType.IMAGE,
                                                title = context.getString(R.string.key_images),
                                                ctaText = "",
                                            )
                                    }
                                )
                            }
                        }
                    }
                }
            )

        }
    ) { contentPadding ->
        PullToRefreshBox(
            isRefreshing = false,
            state = refreshState,
            modifier = Modifier
                .hazeSource(
                    state = hazeState,
                ),
            onRefresh = {
                scope.launch {
                    view.performHapticFeedback(
                        HapticFeedbackConstants.CONFIRM
                    )
                    walletState.refresh()
                    refreshState.animateToHidden()
                }
            },
            indicator = {}
        ) {
            LazyColumn(
                contentPadding = contentPadding
            ) {
                stickyHeader(key = "progress") {
                    Column {
                        WalletProgressIndicator(
                            refreshIndicatorProgress = refreshState.distanceFraction
                        )
                        AnimatedVisibility(
                            visible = refreshState.distanceFraction > .2f && !showIndefiniteLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(R.string.pull_to_start_refresh),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(
                                    top = 16.dp, bottom = 8.dp
                                ),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp
                                )
                            )
                        };
                    }
                }
                item(key = "balance") {
                    Column(
                        modifier = Modifier
                            .padding(
                                vertical = 32.dp
                            )
                            .combinedClickable(
                                onClick = { walletState.toggleHideAmounts() },
                                onLongClick = {
                                    navigateTo(CoinsScreenRoute)
                                }
                            )
                            .fillParentMaxWidth()
                    ) {
                        Text(
                            text = "XMR",
                            fontFamily = customXmrFont,
                            fontSize = 50.sp,
                            modifier = Modifier.padding(start = 2.dp).offset(y = (-6).dp)
                        )
                        Text(
                            if (hideAmounts) Formats.maskAmount(balance ?: 0)
                            else Formats.getDisplayAmount(balance ?: 0),
                            style = MaterialTheme.typography.displaySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, end = 4.dp),
                            textAlign = TextAlign.End
                        )
                    }
                }
                item(key = "transaction_offset") {
                    Spacer(modifier = Modifier.size(40.dp))
                }
                items(transactions.size, key = { transactions[it].getListKey() }) {
                    with(sharedTransitionScope) {
                        TransactionItem(
                            transactions[it],
                            hideAmounts = hideAmounts,
                            showBorder = it != 1,
                            modifier = Modifier
                                .offset(y = if (it == 1) (-62).dp else 0.dp)
                                .clickable {
                                    onItemClick(transactions[it])
                                }
                                .sharedElement(
                                    sharedTransitionScope.rememberSharedContentState(
                                        key = "${transactions[it].hash}",
                                    ),
                                    animatedVisibilityScope = animatedContentScope
                                )
                        )
                    }
                }
            }
        }
    }
}


@Composable
fun TransactionItem(tx: TransactionInfo, hideAmounts: Boolean = false, modifier: Modifier = Modifier, rightAlignAmount: Boolean = true, showTime: Boolean = false, timePattern: String = "dd/MM\nHH:mm", timeZone: ZoneId = ZoneId.systemDefault(), upstreamLayout: Boolean = false, showBorder: Boolean = true) {
    val isIncoming = tx.direction == TransactionInfo.Direction.Direction_In
    val amount = if (isIncoming) tx.amount else tx.amount
    val confirmations = tx.confirmations
    Row(
        modifier = modifier
            .fillMaxWidth()
            .offset(x = if (upstreamLayout) 0.dp else (-4).dp)
            .padding(
                start = if (upstreamLayout) 8.dp else 25.dp,
                end = 0.dp,
                top = 20.dp,
                bottom = 20.dp
            )
            .then(Modifier)
            .padding(
                start = 12.dp,
                end = 4.dp,
                top = 12.dp,
                bottom = 12.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (rightAlignAmount) {
            Spacer(modifier = Modifier.weight(1f))
        }
        Box(modifier = Modifier.offset(x = 6.dp).padding(top = 2.dp)) {
                if (confirmations >= 10)
                    Icon(
                        if (isIncoming) AnonIcons.ArrowDownLeft else AnonIcons.ArrowUpRight,
                        modifier = Modifier.size(if (upstreamLayout) 32.dp else 25.dp),
                        tint = if (isIncoming) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        contentDescription = ""
                    )
                else
                    Box(modifier = Modifier.size(28.dp)) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.dp,
                            progress = { ((confirmations.toFloat()) / 10f) }
                        )
                        Text(
                            text = "$confirmations",
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp)
                        )
                    }
            }
        Spacer(modifier = Modifier.size(10.dp))
        Text(
            if (hideAmounts) Formats.maskAmount(amount)
            else Formats.getDisplayAmount(amount),
            modifier = if (!rightAlignAmount && upstreamLayout) Modifier.weight(1f) else Modifier,
            textAlign = if (rightAlignAmount) TextAlign.End else TextAlign.Center,
            style = MaterialTheme.typography.titleLarge
        )
        if (!rightAlignAmount && !upstreamLayout) {
            Spacer(modifier = Modifier.size(12.dp))
        }
        if (showTime) {
            Text(
                Formats.formatTransactionTime(tx.timestamp, timePattern, timeZone),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
    }

@Composable
fun LockButton(onLock: () -> Unit, loading: Boolean = false) {
    val walletState = koinInject<WalletState>()
    val walletLoading by walletState.isLoading.asLiveData().observeAsState(false)
    IconButton(
        modifier = Modifier.alpha(if (walletLoading) 0.2f else 1.0f),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = Color.White
        ),
        onClick = {
            if (walletLoading || loading) {
                return@IconButton
            }
            onLock()
        }
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.lock))
        }
    }
}

@Preview(device = "id:pixel_7_pro")
@Composable
private fun TransactionScreenReview() {
    AnonNeroTheme {
//        TransactionScreen(
//            animatedContentScope = this@composable,
//            sharedTransitionScope = this@SharedTransitionLayout
//        )
    }
}
