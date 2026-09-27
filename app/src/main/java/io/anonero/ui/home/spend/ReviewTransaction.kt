package io.anonero.ui.home.spend

import AnonNeroTheme
import AnonOutlineButton
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.anonero.AnonConfig
import io.anonero.R
import io.anonero.model.PendingTransaction
import io.anonero.model.UnsignedTransaction
import io.anonero.model.WalletManager
import io.anonero.ui.home.graph.routes.ReviewTransactionRoute
import io.anonero.ui.home.spend.qr.ExportType
import io.anonero.ui.home.spend.qr.ImportEvents
import io.anonero.ui.home.spend.qr.QRExchangeScreen
import io.anonero.ui.home.spend.qr.SpendQRExchangeParam
import io.anonero.ui.home.spend.qr.URQRScanner
import io.anonero.ui.theme.DangerColor
import io.anonero.ui.theme.SuccessColor
import io.anonero.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

enum class BroadcastState {
    STAGING,
    IN_PROGRESS,
    SUCCESS,
    ERROR
}

data class ReviewModel(
    val address: String?,
    val amount: Long,
    val fee: Long,
    val total: Long,
    val txId: String
)

private const val TAG = "ReviewTransaction"

class ReviewTransactionViewModel : ViewModel() {
    private val reviewModel = MutableLiveData<ReviewModel?>(null)
    private val broadcastingTx = MutableLiveData(BroadcastState.STAGING)
    private var _broadcastError: Exception? = null

    val broadCastError get() = _broadcastError
    val getBroadcastingTxState get() = broadcastingTx
    val reviewModelLive: LiveData<ReviewModel?> get() = reviewModel

    var pendingTransaction: PendingTransaction? = null;
    var unsignedTransaction: UnsignedTransaction? = null;

    public fun isUnsignedTransaction(): Boolean {
        return unsignedTransaction != null
    }

    init {
        val wallet = WalletManager.instance?.wallet;
        val pendingTransaction = wallet?.getPendingTx()
        val unsignedTransaction = wallet?.getUnsginedTx()

        if (unsignedTransaction != null) {
            this.unsignedTransaction = unsignedTransaction
            reviewModel.postValue(
                ReviewModel(
                    address = unsignedTransaction.address ?: "",
                    amount = unsignedTransaction.amount,
                    fee = unsignedTransaction.fee,
                    total = unsignedTransaction.fee + unsignedTransaction.amount,
                    txId = unsignedTransaction.firstTxId
                )
            )
        } else if (pendingTransaction != null) {
            if(pendingTransaction.getFirstTxIdJ() != null){
                this.pendingTransaction = pendingTransaction
                reviewModel.postValue(
                    ReviewModel(
                        address = null,
                        amount = pendingTransaction.getAmount(),
                        fee = pendingTransaction.getFee(),
                        total = pendingTransaction.getFee() + pendingTransaction.getAmount(),
                        txId = pendingTransaction.getFirstTxIdJ()!!
                    )
                )
            }else{
                Timber.tag(TAG).e("Pending transaction has no txId")
            }
        }
    }

    fun broadCast(): Job? {
        if (broadcastingTx.value == BroadcastState.IN_PROGRESS) {
            return null
        }
        val signedTxFile = File(
            AnonConfig.context?.cacheDir,
            AnonConfig.IMPORT_SIGNED_TX_FILE
        );
        _broadcastError = null
        broadcastingTx.postValue(BroadcastState.IN_PROGRESS)

        return viewModelScope.launch(Dispatchers.IO) {
            try {
                val wallet = WalletManager.instance?.wallet
                if (wallet == null) {
                    throw Exception(AnonConfig.context?.getString(R.string.wallet_available))
                }
                if (!AnonConfig.viewOnly) {
                    pendingTransaction?.let {
                        val result = wallet.send(it)
                        if (!result) {
                            throw Exception(AnonConfig.context?.getString(R.string.failed_to_send_transaction))
                        } else {
                            broadcastingTx.postValue(BroadcastState.SUCCESS)
                        }
                    }
                } else
                    if (signedTxFile.exists()) {
                        val error =
                            wallet.submitTransaction(signedTxFile.absolutePath)
                        val success = error == "Transaction submitted!";
                        if (success) {
                            broadcastingTx.postValue(BroadcastState.SUCCESS)
                            signedTxFile.delete()
                            Timber.tag(TAG).d("Transaction broadcasted...")
                        } else {
                            broadcastingTx.postValue(BroadcastState.ERROR)
                            Timber.tag(TAG).e("broadcasting failed  ${error}")
                            throw Exception(error)
                        }
                    } else {
                        throw Exception(AnonConfig.context?.getString(R.string.signed_transaction_file_not_found))
                    }
                wallet.startRefresh()
                wallet.refreshHistory()
                wallet.store()
                broadcastingTx.postValue(BroadcastState.SUCCESS)
            } catch (ex: Exception) {
                _broadcastError = ex
                broadcastingTx.postValue(BroadcastState.ERROR)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewTransactionScreen(
    reviewParams: ReviewTransactionRoute,
    modifier: Modifier = Modifier,
    onFinished: () -> Unit = {},
    navigateToHome: () -> Unit = {},
    onBackPressed: () -> Unit = {},
) {

    var qrScannerParam by remember { mutableStateOf<SpendQRExchangeParam?>(null) }
    val viewModel = viewModel<ReviewTransactionViewModel>()
    val reviewModel by viewModel.reviewModelLive.observeAsState()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val broadcastState by viewModel.getBroadcastingTxState.observeAsState(BroadcastState.STAGING)
    var showScanner by remember { mutableStateOf(false) }
    var signing by remember { mutableStateOf(false) }
    var readyToBroadcast by remember { mutableStateOf(!AnonConfig.viewOnly) }

    val titleStyle = MaterialTheme.typography.bodyLarge.copy(
        color = MaterialTheme.colorScheme.primary,        fontSize = 20.sp
    )
    val subTitleStyle = MaterialTheme.typography.bodyMedium.copy(
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 14.sp
    )

    BackHandler {
        onBackPressed()
    }

    if (qrScannerParam != null) {
        QRExchangeScreen(
            params = qrScannerParam!!,
            onBackPressed = {
                qrScannerParam = null
            },
            onNavigateToHome = {
                navigateToHome.invoke()
            },
            onCtaCalled = {
                qrScannerParam = null
                showScanner = true
                if (qrScannerParam?.exportType == ExportType.SIGNED_TX) {
                    navigateToHome.invoke()
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    if (showScanner)
        URQRScanner(
            onQRCodeScanned = {

            },
            onDismiss = {
                showScanner = false
            },
            onURResult = {
                when (it.getOrNull()) {
                    ImportEvents.IMPORT_OUTPUTS -> {

                    }

                    ImportEvents.IMPORT_KEY_IMAGES -> {

                    }

                    ImportEvents.IMPORT_UNSIGNED_TX -> {

                    }

                    ImportEvents.IMPORT_SIGNED_TX -> {
                        qrScannerParam = null;
                        readyToBroadcast = true
                    }

                    null -> {

                    }
                }
                showScanner = false
            },
            showScanner = showScanner
        )

    val context = LocalContext.current;
    val signedTxText = stringResource(R.string.signed_tx)
    val unsignedTxText = stringResource(R.string.unsigned_tx)
    val scanSignedTxText = stringResource(R.string.scan_signed_tx)
    val ctaText = if (AnonConfig.viewOnly) {
        if (!viewModel.isUnsignedTransaction() && !readyToBroadcast) {
            stringResource(R.string.confirm).capitalize()
        } else {
            stringResource(R.string.broadcast_tx)
        }
    } else {
        if (viewModel.isUnsignedTransaction()) {
           stringResource(R.string.confirm).capitalize()
        } else {
            stringResource(R.string.broadcast_tx)
        }
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
                        onClick = {
                            if (broadcastState == BroadcastState.SUCCESS) {
                                onFinished.invoke()
                            } else {
                                onBackPressed.invoke()
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {

                }
            )
        }
    ) {
        Box(
            modifier = Modifier
                .padding(it)
                .padding(bottom = 0.dp)
        ) {
            if (reviewModel != null) {
                AnimatedVisibility(
                    visible = broadcastState == BroadcastState.ERROR,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            stringResource(
                                R.string.unable_to_broadcast_transaction,
                                viewModel.broadCastError?.message ?: stringResource(R.string.unknown_error)
                            ),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = DangerColor
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                }
                AnimatedVisibility(
                    visible = broadcastState == BroadcastState.SUCCESS,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.check),
                            tint = SuccessColor,
                            modifier = Modifier.size(44.dp)
                        )
                        Text(stringResource(R.string.success))
                    }
                }
                AnimatedVisibility(
                    visible = broadcastState == BroadcastState.IN_PROGRESS,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {

                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {

                        Box(
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_anon),
                                contentDescription = "Anon nero icon",
                                modifier = Modifier
                                    .size(110.dp)
                            )
                            CircularProgressIndicator(
                                modifier = Modifier.size(300.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        Text(
                            stringResource(R.string.broadcast_in_progress),
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }

                }
                AnimatedVisibility(
                    visible = broadcastState == BroadcastState.STAGING,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column(
                        verticalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp)
                    ) {
                        LazyColumn {
                            item {
                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.address), style = titleStyle) },
                                    supportingContent = {
                                        Text(
                                            reviewModel!!.address ?: reviewParams.toAddress,
                                            style = subTitleStyle
                                        )
                                    }
                                )
                            }
                            item {
                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.amount), style = titleStyle) },
                                    supportingContent = {
                                        Text(
                                            Formats.getDisplayAmount(
                                                reviewModel!!.amount                                            ),
                                            style = subTitleStyle
                                        )
                                    }
                                )
                            }
                            item {

                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.fee), style = titleStyle) },
                                    supportingContent = {
                                        Text(
                                            Formats.getDisplayAmount(
                                                reviewModel!!.fee
                                            ),
                                            style = subTitleStyle
                                        )
                                    }
                                )
                            }
                            item {
                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.total), style = titleStyle) },
                                    supportingContent = {
                                        Text(
                                            Formats.getDisplayAmount(
                                                reviewModel!!.total
                                            ),
                                            textAlign = TextAlign.Center,
                                            style = MaterialTheme.typography.titleLarge,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(
                                                    vertical = 24.dp
                                                )
                                        )
                                    }
                                )
                            }
                        }
                        AnonOutlineButton(
                            onClick = {

                                if (viewModel.isUnsignedTransaction()) {
                                    scope.launch {
                                        val unsignedTransaction = File(
                                            AnonConfig.context?.cacheDir,
                                            AnonConfig.IMPORT_UNSIGNED_TX_FILE
                                        );
                                        val signedTransaction = File(
                                            AnonConfig.context?.cacheDir,
                                            AnonConfig.EXPORT_SIGNED_TX_FILE
                                        );
                                        Timber.tag(TAG)
                                            .i("Signing Unsigned Transaction: ${unsignedTransaction.path} to ${signedTransaction.path}")
                                        signing = true
                                        val success =
                                            WalletManager.instance?.wallet?.signAndExportJ(
                                                unsignedTransaction.absolutePath,
                                                signedTransaction.absolutePath,
                                            )
                                        if (success == "Signed") {
                                            readyToBroadcast = true
                                            Timber.tag(TAG)
                                                .i("Signing result: $success")
                                            Timber.tag(TAG)
                                                .i(
                                                    "Signing Signed Transaction: exists ? ${signedTransaction.exists()} | " +
                                                            " size: ${signedTransaction.length()}"
                                                )
                                            signing = false
                                            qrScannerParam = SpendQRExchangeParam(
                                                exportType = ExportType.SIGNED_TX,
                                                title = signedTxText,
                                                ctaText = context.getString(R.string.finish)    ,
                                            )
                                        } else {
                                            readyToBroadcast = false
                                        }
                                    }.invokeOnCompletion { error ->
                                        if (error != null) {
                                            Timber.tag(TAG)
                                                .i("Signing Unsigned Transaction failed: $error")
                                        }
                                    }
                                } else {
                                    if (AnonConfig.viewOnly && !readyToBroadcast) {
                                        qrScannerParam = SpendQRExchangeParam(
                                            exportType = ExportType.UN_SIGNED_TX,
                                            title = unsignedTxText,
                                            ctaText = scanSignedTxText,
                                        )
                                    } else {
                                        viewModel.broadCast()?.invokeOnCompletion { error ->
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                                if (error == null) {
                                                    view.performHapticFeedback(
                                                        HapticFeedbackConstants.CONFIRM
                                                    )
                                                } else {
                                                    view.performHapticFeedback(
                                                        HapticFeedbackConstants.REJECT
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                        ) {
                            if (signing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(ctaText)
                            }
                        }
                    }
                }
            }
        }
    }
}


@Preview
@Composable
private fun ReviewTransactionScreenPreview() {
    AnonNeroTheme {
        ReviewTransactionScreen(
            ReviewTransactionRoute("address"),
        )
    }
}