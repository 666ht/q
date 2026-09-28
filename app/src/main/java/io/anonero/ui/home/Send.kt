package io.anonero.ui.home

import AnonOutlineButton
import android.os.Build
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.sparrowwallet.hummingbird.ResultType
import com.sparrowwallet.hummingbird.URDecoder
import io.anonero.AnonConfig
import io.anonero.R
import io.anonero.icons.AnonIcons
import io.anonero.model.AnonUrRegistryTypes
import io.anonero.model.CoinsInfo
import io.anonero.model.PendingTransaction
import io.anonero.model.Wallet
import io.anonero.model.WalletManager
import io.anonero.services.WalletState
import io.anonero.ui.components.WalletProgressIndicator
import io.anonero.ui.home.graph.routes.ReviewTransactionRoute
import io.anonero.ui.home.graph.routes.SendScreenRoute
import io.anonero.ui.home.spend.qr.ExportType
import io.anonero.ui.home.spend.qr.ImportEvents
import io.anonero.ui.home.spend.qr.QRExchangeScreen
import io.anonero.ui.home.spend.qr.SpendQRExchangeParam
import io.anonero.ui.home.spend.qr.URQRScanner
import io.anonero.util.Formats
import io.anonero.util.backup.BackupHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.io.File

private const val TAG = "Send"


enum class SpendType {
    NORMAL,
    SWEEP
}

class SendViewModel : ViewModel() {
    private val walletState: WalletState by inject(WalletState::class.java)
    private val _balance = walletState.unLockedBalance.asLiveData()
    private val _paymentUri = MutableLiveData<SendScreenRoute?>(null)
    private val _coinsSelected = MutableLiveData<Set<CoinsInfo>>(emptySet())
    private val _txComposeError = MutableLiveData<String?>(null)
    private val _spendType = MutableLiveData(SpendType.NORMAL)
    val paymentUri = _paymentUri as LiveData<SendScreenRoute?>
    val spendType = _spendType as LiveData<SpendType>
    val coins = _coinsSelected as LiveData<Set<CoinsInfo>>
    val txComposeError = _txComposeError as LiveData<String?>

    suspend fun prepareTransaction(addressField: String, amount: String): PendingTransaction? {
        val amountFromString: Long = Wallet.getAmountFromString(amount)
        val wallet = WalletManager.instance?.wallet
        return withContext(Dispatchers.IO) {
            try {
                _txComposeError.postValue(null)
                if (spendType.value == SpendType.SWEEP && _coinsSelected.value.isNullOrEmpty()) {
                    if(wallet?.fullStatus?.connectionStatus != Wallet.ConnectionStatus.ConnectionStatus_Connected) {
                        throw Exception(AnonConfig.context?.getString(R.string.wallet_not_connected_to_node_please_check_network))
                    }
                    val pendingTx = wallet.createSweepTransaction(
                        dstAddr = addressField,
                        priority = PendingTransaction.Priority.Priority_Default,
                        keyImages = arrayListOf()
                    )
                    if (!pendingTx?.getErrorString().isNullOrEmpty()) {
                        throw Exception(pendingTx.getErrorString())
                    }

                    if (pendingTx != null) {
                        val unsignedTxFile =
                            File(AnonConfig.context?.cacheDir, AnonConfig.EXPORT_UNSIGNED_TX_FILE)
                        pendingTx.commit(unsignedTxFile.path, true)
                    }
                    pendingTx
                } else {
                    if(wallet?.fullStatus?.connectionStatus != Wallet.ConnectionStatus.ConnectionStatus_Connected) {
                        throw Exception(AnonConfig.context?.getString(R.string.wallet_not_connected_to_node_please_check_network))
                    }
                    val pendingTx = wallet.createTransaction(
                        dst_addr = addressField,
                        amount = amountFromString,
                        sweepAll = spendType.value == SpendType.SWEEP,
                        selectedUtxos = _coinsSelected.value?.toList() ?: listOf()
                    )

                    if (!pendingTx?.getErrorString().isNullOrEmpty()) {
                        throw Exception(pendingTx.getErrorString())
                    }
                    if (pendingTx != null) {
                        val unsignedTxFile =
                            File(AnonConfig.context?.cacheDir, AnonConfig.EXPORT_UNSIGNED_TX_FILE)
                        pendingTx.commit(unsignedTxFile.path, true)
                    }
                    pendingTx
                }
            } catch (e: Exception) {
                _txComposeError.postValue(e.message)
                Timber.tag(TAG).e(e)
                null
            }
        }
    }

    fun handleScan(data: String) {
        viewModelScope.launch(Dispatchers.Default) {
            SendScreenRoute.parse(data)?.let {
                _paymentUri.postValue(it)
            }
        }
    }

    fun processUrCode(
        urResult: URDecoder.Result,
        onSuccess: (path: String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (urResult.type == ResultType.SUCCESS) {
                var destinationFile: File? = null
                val ur = urResult.ur
                when (ur.type) {
                    AnonUrRegistryTypes.XMR_TX_UNSIGNED.type -> {
                        destinationFile =
                            File(AnonConfig.context?.cacheDir, AnonConfig.IMPORT_UNSIGNED_TX_FILE)
                    }

                    AnonUrRegistryTypes.XMR_TX_SIGNED.type -> {
                        destinationFile =
                            File(AnonConfig.context?.cacheDir, AnonConfig.IMPORT_SIGNED_TX_FILE)
                    }

                    AnonUrRegistryTypes.XMR_OUTPUT.type -> {
                        destinationFile =
                            File(AnonConfig.context?.cacheDir, AnonConfig.IMPORT_OUTPUT_FILE)
                    }

                    AnonUrRegistryTypes.XMR_KEY_IMAGE.type -> {
                        destinationFile =
                            File(AnonConfig.context?.cacheDir, AnonConfig.IMPORT_KEY_IMAGE_FILE)
                    }

                    else -> {
                        Timber.tag(TAG).e("Unknown UR type")
                    }
                }
                destinationFile?.writeBytes(ur.toBytes())
                if (destinationFile != null) {
                    onSuccess(destinationFile.path)
                }
            }
        }
    }

    fun setSpendType(type: SpendType) {
        _spendType.postValue(type)
    }

    // Computed balance based on selected coins or unlocked balance
    val balance = MediatorLiveData<Long>().apply {
        addSource(walletState.unLockedBalance.asLiveData()) { updateComputedBalance() }
        addSource(_coinsSelected) { updateComputedBalance() }
    }

    private fun MediatorLiveData<Long>.updateComputedBalance() {
        val selectedCoins = _coinsSelected.value
        val unlockedBal = _balance.value ?: 0L
        value = if (selectedCoins.isNullOrEmpty()) {
            unlockedBal
        } else {
            selectedCoins.sumOf { it.amount }
        }
    }

    fun setCoins(coins: List<String>) {
        val walletCoins = WalletManager.instance?.wallet?.coins?.all ?: listOf();
        val selectedCoins = walletCoins.filter { coins.contains(it.pub_key) }.toSet()
        _coinsSelected.postValue(
            selectedCoins
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    modifier: Modifier = Modifier,
    onBackPress: () -> Unit = {},
    navigateToReview: (route: ReviewTransactionRoute) -> Unit = {},
    navigateToHome: () -> Unit = {},
    paymentUri: SendScreenRoute? = null,
) {

    val snackbarHostState = remember { SnackbarHostState() }
    var addressField by rememberSaveable { mutableStateOf("") }
    var amountField by rememberSaveable { mutableStateOf("") }
    var validSpend by rememberSaveable { mutableStateOf(false) }
    var preparingTx by remember { mutableStateOf(false) }
    var showCoinSelection by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var qrScannerParam by remember { mutableStateOf<SpendQRExchangeParam?>(null) }
    var inValidAddress by remember { mutableStateOf<Boolean?>(null) }
    val labelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
    val scope = rememberCoroutineScope()
    val sendViewModel = koinViewModel<SendViewModel>()
    val walletState: WalletState by inject(WalletState::class.java)
    val unlockedBalance by sendViewModel.balance.observeAsState(0L)
    val hideAmounts by walletState.hideAmountsFlow.collectAsState(false)
    val paymentUriFromScanner by sendViewModel.paymentUri.observeAsState(null)
    val spendType by sendViewModel.spendType.observeAsState(SpendType.NORMAL)
    val coins by sendViewModel.coins.observeAsState(emptySet())
    val txComposeError by sendViewModel.txComposeError.observeAsState()
    val view = LocalView.current
    val showIndefiniteLoading by walletState.isLoading.asLiveData().observeAsState(false)


    val unLockedAmount = if (hideAmounts) Formats.maskAmount(unlockedBalance ?: 0L) else Formats.getDisplayAmount(unlockedBalance ?: 0L)

    LaunchedEffect(paymentUri) {
        if (paymentUri != null && paymentUri.coins.isNotEmpty()) {
            sendViewModel.setCoins(paymentUri.coins)
        }
    }

    LaunchedEffect(paymentUriFromScanner) {
        paymentUriFromScanner?.let {
            if (it.address.isNotEmpty())
                addressField = it.address
            if (it.amount != 0.0)
                amountField =
                    Formats.getDisplayAmount(Wallet.getAmountFromString(it.amount.toString()))
        }
    }

    LaunchedEffect(paymentUri) {
        paymentUri?.let {
            if (it.address.isNotEmpty())
                addressField = it.address
            if (it.amount != 0.0)
                amountField =
                    Formats.getDisplayAmount(Wallet.getAmountFromString(it.amount.toString()))
        }
    }
    val sweep = spendType == SpendType.SWEEP

    LaunchedEffect(addressField, amountField) {
        scope.launch {
            unlockedBalance?.let { maxSpendableBalance ->
                val amountFromString = Wallet.getAmountFromString(amountField)

                validSpend = when {
                    inValidAddress == true -> false
                    spendType == SpendType.SWEEP -> true
                    amountFromString == 0L || maxSpendableBalance == 0L -> false
                    amountFromString >= maxSpendableBalance -> false
                    else -> true
                }
            }
        }.invokeOnCompletion {
            if (it != null) {
                Timber.tag(TAG).e(it)
            }
        }
    }

    fun prepare() {
        scope.launch {
            try {
                preparingTx = true
                val pendingTx =
                    sendViewModel.prepareTransaction(addressField, amountField)
                if (pendingTx != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        view.performHapticFeedback(
                            HapticFeedbackConstants.CONFIRM
                        )
                    }
                    if (!pendingTx.getErrorString().isNullOrEmpty()) {
                        return@launch
                    }
                    navigateToReview.invoke(ReviewTransactionRoute(addressField))
                } else {
                    Timber.tag(TAG).i("prepare: Pending tx is null")
                    //show error
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e)
            } finally {
                preparingTx = false
            }
        }
    }


    val context = LocalContext.current


    if (showCoinSelection)
        ModalBottomSheet(
            scrimColor = MaterialTheme.colorScheme.background,
            contentColor = Color.Transparent,
            sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true
            ),
            contentWindowInsets = {
                WindowInsets(
                    left = 0.dp,
                    top = 0.dp,
                    right = 0.dp,
                    bottom = 0.dp
                )
            },
            shape = MaterialTheme.shapes.large,
            dragHandle = {
                Box(modifier = Modifier.height(0.dp))
            },
            onDismissRequest = {
                showCoinSelection = false
            }
        ) {
            CoinsScreen(
                selected = coins.map { it.key }.toSet(),
                onBackPress = {
                    showCoinSelection = false
                },
                navigateToSpend = {
                    showCoinSelection = false
                    sendViewModel.setCoins(it.coins)
                }
            )
        }


    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text("")
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackPress
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {

                }
            )
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(
                    bottom = 12.dp
                )
            )
        },
    ) { padding ->
        if (preparingTx) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = CenterHorizontally
            ) {
                Box(
                    contentAlignment = Alignment.Center
                ){
                    Image(
                        painter = painterResource(R.drawable.ic_anon),
                        contentDescription = stringResource(R.string.anon_icon),
                        modifier = Modifier
                            .size(110.dp)
                    )
                    CircularProgressIndicator(
                        modifier = Modifier.size(300.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text(
                    stringResource(R.string.constructing_transaction),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(bottom = 0.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.Start
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    WalletProgressIndicator()
                    ListItem(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.address),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        },
                        supportingContent = {
                            OutlinedTextField(
                                value = addressField,

                                shape = MaterialTheme.shapes.medium,
                                minLines = 4,
                                isError = inValidAddress == true,
                                supportingText = {
                                    if (inValidAddress == true) {
                                        Text(stringResource(R.string.invalid_address))
                                    }
                                },
                                enabled = !showIndefiniteLoading,
                                onValueChange = {
                                    addressField = it.trim()
                                },
                                keyboardOptions = KeyboardOptions(
                                    imeAction = ImeAction.Next,
                                ),
                                placeholder = {
                                    Text(
                                        text = "",
                                        color = labelColor
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged {
                                        if (!it.hasFocus && addressField.isNotEmpty()) {
                                            scope.launch(Dispatchers.Default) {
                                                inValidAddress =
                                                    WalletManager.instance?.wallet?.validateAddress(
                                                        addressField
                                                    ) != true
                                            }
                                        } else {
                                            if (addressField.isEmpty())
                                                inValidAddress = null
                                        }

                                    }
                                    .focusable()
                            )
                        },
                    )

                    ListItem(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.amount),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        },
                        supportingContent = {
                            OutlinedTextField(
                                value = amountField,
                                shape = MaterialTheme.shapes.medium,
                                placeholder = {
                                    Text(
                                        text = "",
                                        color = labelColor
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(
                                    imeAction = ImeAction.Next,
                                    keyboardType = KeyboardType.Number
                                ),
                                enabled = !showIndefiniteLoading,
                                onValueChange = {
                                    amountField = it.trim()
                                },

                                )
                        },
                    )
                    Text(
                        if (sweep) "${stringResource(R.string.sweeping_balance)} $unLockedAmount ${
                            stringResource(
                                R.string.minus_fees
                            )}" else "${stringResource(R.string.available_balance)} $unLockedAmount ",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                amountField = if (!sweep) {
                                    sendViewModel.setSpendType(SpendType.SWEEP)
                                    unLockedAmount
                                } else {
                                    sendViewModel.setSpendType(SpendType.NORMAL)
                                    ""
                                }
                            },
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium
                    )
                    IconButton(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        onClick = {
                            showScanner = true
                            qrScannerParam = null
                        }
                    ) {
                        Icon(AnonIcons.Scan, contentDescription = "")
                    }

                    if (!txComposeError.isNullOrEmpty())
                        Text(
                            text = "${txComposeError}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )

                    if (coins.isNotEmpty())
                        Text(
                            text = "${if (coins.size == 1) stringResource(R.string._1_coin_selected) else "${coins.size} ${
                                stringResource(
                                    R.string.coins_selected
                                )}"}",
                            modifier = Modifier
                                .align(CenterHorizontally)
                                .padding(
                                    horizontal = 16.dp,
                                    vertical = 8.dp
                                )
                                .clickable {
                                    showCoinSelection = true;
                                }
                                .padding(horizontal = 16.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xfffb8500)
                        )
                }
                AnonOutlineButton(
                    enabled = validSpend,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 16.dp,
                        ),
                    onClick = {
                        val wallet = WalletManager.instance?.wallet;
                        val spendAmount = Wallet.getAmountFromString(amountField)
                        if (wallet == null) {
                            return@AnonOutlineButton
                        }
                        if (AnonConfig.viewOnly) {
                            var needsKeyImages: Boolean
                            if (spendType == SpendType.SWEEP) {
                                needsKeyImages = wallet.hasUnknownKeyImages()
                            } else {
                                needsKeyImages =
                                    (spendAmount + 1_000_000_000L) > wallet.viewOnlyBalance()
                                Timber.tag(TAG).i("SendScreen: needsKeyImages: %s", needsKeyImages)
                                Timber.tag(TAG)
                                    .i("SendScreen: viewOnlyBalance: %s", wallet.viewOnlyBalance())
                                Timber.tag(TAG).i("SendScreen: spendAmount: %s", spendAmount)
                            }

                            if (needsKeyImages) {
                                qrScannerParam = SpendQRExchangeParam(
                                    exportType = ExportType.OUTPUT,
                                    title = context.getString(R.string.outputs),
                                    ctaText = context.getString(R.string.scan_key_images),
                                )
                            } else {
                                prepare()
                            }

                            return@AnonOutlineButton
                        } else {
                            prepare()
                        }
                    },
                ){
                    Text(stringResource(R.string.next))
                }
            }

        }
    }

    if (showScanner)
        URQRScanner(
            onQRCodeScanned = {
                showScanner = false
                sendViewModel.handleScan(it)
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
                            ctaText = context.getString(R.string.scan_unsigned_tx),
                        )
                    }

                    ImportEvents.IMPORT_KEY_IMAGES -> {
                        prepare()
                    }

                    ImportEvents.IMPORT_UNSIGNED_TX -> {
                        if (!AnonConfig.viewOnly) {
                            navigateToReview.invoke(
                                ReviewTransactionRoute(
                                    addressField,
                                )
                            )
                        }
                    }

                    ImportEvents.IMPORT_SIGNED_TX -> {
                        if (AnonConfig.viewOnly) {
                            navigateToReview.invoke(
                                ReviewTransactionRoute(
                                    addressField,
                                )
                            )
                        }
                    }

                    null -> {

                    }
                }
                showScanner = false
            },
            showScanner = showScanner
        )

    if (qrScannerParam != null) {
        QRExchangeScreen(
            params = qrScannerParam!!,
            onBackPressed = {
                qrScannerParam = null
            },
            onNavigateToHome = {
                qrScannerParam = null
                showScanner = false
                navigateToHome.invoke()
            },
            onCtaCalled = {
                qrScannerParam = null
                showScanner = true
            },
            modifier = Modifier.fillMaxSize()
        )
    }


}

@Preview(device = "id:pixel_5")
@Composable
private fun SendScreenPrev() {
    SendScreen(modifier = Modifier)
}
