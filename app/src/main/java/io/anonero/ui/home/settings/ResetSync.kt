package io.anonero.ui.home.settings

import AnonNeroTheme
import AnonOutlineButton
import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.anonero.AnonConfig
import io.anonero.model.WalletManager
import io.anonero.services.WalletState
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Constant-size spinner for the reset/query button.
 * Only its angle changes: the center, radius, stroke and arc length remain fixed,
 * avoiding the growing/shrinking arc of the default indeterminate indicator.
 */
@Composable
private fun FixedSpinIndicator(
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.dp
) {
    val transition = rememberInfiniteTransition(label = "reset_query_spinner")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "reset_query_spinner_rotation"
    )
    val color = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier) {
        val stroke = strokeWidth.toPx()
        val diameter = (size.minDimension - stroke).coerceAtLeast(0f)
        val arcSize = Size(diameter, diameter)
        val topLeft = Offset(
            x = (size.width - diameter) / 2f,
            y = (size.height - diameter) / 2f
        )
        drawArc(
            color = color,
            startAngle = rotation - 90f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResetSyncPage(
    onBackPress: () -> Unit = {}
) {
    val walletState = koinInject<WalletState>()
    val wallet = WalletManager.instance?.wallet
    // Observe refresh/sync state so the saved-height date lookup waits until
    // the native wallet is available instead of racing a refresh callback.
    val isLoading by walletState.isLoading.collectAsState(false)
    val syncProgress by walletState.syncProgress.collectAsState(null)
    val canQueryBlockDate = walletState.canQueryBlockDate() && !isLoading && syncProgress == null
    val dateFormat = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            isLenient = false
        }
    }

    fun dateForTimestamp(timestamp: Long): String {
        return dateFormat.format(Date(timestamp * 1000L))
    }

    fun parseDate(value: String): Date? {
        if (value.length != 10) return null
        return try {
            dateFormat.parse(value)?.takeIf { dateFormat.format(it) == value }
        } catch (_: Exception) {
            null
        }
    }

    val appContext = LocalContext.current.applicationContext
    val preferences = remember(appContext) {
        appContext.getSharedPreferences(AnonConfig.PREFS, Context.MODE_PRIVATE)
    }
    // Date cache is separated by network because the same block height can map
    // to different timestamps on mainnet, testnet, and stagenet.
    val dateCacheNamespace =
        "${wallet?.networkType?.value ?: -1}_${java.util.TimeZone.getDefault().id}"
    fun dateCacheKey(heightValue: Long): String =
        "reset_sync_height_date_${dateCacheNamespace}_${heightValue}"
    fun heightCacheKey(dateValue: String): String =
        "reset_sync_date_height_${dateCacheNamespace}_${dateValue}"

    val savedRestoreHeight = preferences.getLong("MNEMONIC_SELECTED_RESTORE_HEIGHT", Long.MIN_VALUE)
        .takeIf { it != Long.MIN_VALUE }
        ?: wallet?.getRestoreHeight()
    var height by remember(wallet) {
        mutableStateOf(savedRestoreHeight?.toString() ?: "")
    }
    // Load a previously resolved date synchronously so it appears in the first
    // frame when the page is reopened, instead of waiting for a daemon lookup.
    var date by remember(wallet, preferences, savedRestoreHeight) {
        mutableStateOf(
            savedRestoreHeight?.let { preferences.getString(dateCacheKey(it), null) }.orEmpty()
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    var querying by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var actionGeneration by remember { mutableStateOf(0L) }
    val coroutineScope = rememberCoroutineScope()
    val heightToDateCache = remember(wallet) { mutableMapOf<Long, String>() }
    val dateToHeightCache = remember(wallet) { mutableMapOf<String, Long>() }

    fun cachedDateForHeight(heightValue: Long): String? {
        val cached = heightToDateCache[heightValue]
            ?: preferences.getString(dateCacheKey(heightValue), null)
        return cached?.takeIf { parseDate(it) != null }
    }

    fun cachedHeightForDate(dateValue: String): Long? {
        val cached = dateToHeightCache[dateValue]
            ?: preferences.getLong(heightCacheKey(dateValue), Long.MIN_VALUE)
                .takeIf { it != Long.MIN_VALUE }
        return cached?.takeIf { it >= 0L }
    }

    // Store a resolved height/date relationship in both directions together,
    // so either lookup can display the counterpart without another wallet query.
    fun cacheHeightDatePair(heightValue: Long, dateValue: String) {
        if (heightValue < 0L || parseDate(dateValue) == null) return
        heightToDateCache[heightValue] = dateValue
        dateToHeightCache[dateValue] = heightValue
        preferences.edit()
            .putString(dateCacheKey(heightValue), dateValue)
            .putLong(heightCacheKey(dateValue), heightValue)
            .apply()
    }

    val selectedHeight = height.toLongOrNull()
    val selectedDate = parseDate(date)
    val canReset = selectedHeight != null &&
        selectedHeight >= 0L &&
        selectedDate != null &&
        (cachedDateForHeight(selectedHeight) == date ||
            cachedHeightForDate(date) == selectedHeight)
    var queryByHeight by remember(wallet) { mutableStateOf(true) }

    // Resolve the saved height's date in the background. Initialization must not
    // drive the button spinner or disable either input field.
    LaunchedEffect(wallet, canQueryBlockDate) {
        val initialHeight = height.toLongOrNull()
        val activeWallet = wallet
        if (canQueryBlockDate && !querying &&
            activeWallet != null && initialHeight != null &&
            initialHeight >= 0L && date.isBlank()
        ) {
            try {
                val resolvedDate = heightToDateCache[initialHeight]
                    ?: preferences.getString(dateCacheKey(initialHeight), null)
                    ?: withContext(Dispatchers.IO) {
                        val timestamp = runCatching {
                            activeWallet.getBlockTimestamp(initialHeight)
                        }.getOrDefault(0L)
                        if (timestamp > 0L) dateForTimestamp(timestamp) else null
                    }
                if (height.toLongOrNull() == initialHeight && date.isBlank()) {
                    if (resolvedDate != null) {
                        cacheHeightDatePair(initialHeight, resolvedDate)
                        date = resolvedDate
                    } else {
                        error = "无法查询该高度的区块时间，请检查节点连接"
                    }
                }
            } catch (e: Exception) {
                if (height.toLongOrNull() == initialHeight && date.isBlank()) {
                    error = e.message ?: "查询失败，请检查节点连接后重试"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackPress) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                title = { Text("重置同步区块") }
            )
        },
        bottomBar = {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 56.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    AnonOutlineButton(
                        onClick = {
                            // When both a valid height and date are displayed, this button resets.
                            if (querying || resetting) return@AnonOutlineButton

                            val heightValue = height.toLongOrNull()
                            val dateValue = parseDate(date)
                            val readyToReset = heightValue != null &&
                                heightValue >= 0L &&
                                dateValue != null

                            if (canReset && readyToReset) {
                                val requestId = actionGeneration + 1L
                                actionGeneration = requestId
                                querying = false
                                resetting = true
                                error = null
                                coroutineScope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        walletState.resetSyncFromHeight(heightValue!!)
                                    }
                                    if (requestId != actionGeneration) return@launch
                                    resetting = false
                                    if (result.isSuccess) {
                                        onBackPress()
                                    } else {
                                        error = result.exceptionOrNull()?.message ?: "重置失败"
                                    }
                                }
                                return@AnonOutlineButton
                            }

                            val hasHeightInput = height.isNotBlank()
                            val hasDateInput = date.isNotBlank()
                            if (!hasHeightInput && !hasDateInput) {
                                error = "请输入高度或日期后查询"
                                return@AnonOutlineButton
                            }
                            val activeWallet = wallet
                            if (activeWallet == null) {
                                error = "钱包尚未就绪，无法查询"
                                return@AnonOutlineButton
                            }

                            val requestedHeight = height
                            val requestedDate = date
                            val queryHeightMode = when {
                                hasHeightInput && hasDateInput -> queryByHeight
                                hasHeightInput -> true
                                else -> false
                            }

                            // A cache hit is a display-only lookup. Resolve it synchronously
                            // before showing the spinner or entering executeLatestWalletQuery(),
                            // which intentionally pauses refresh and clears active scan UI state.
                            // This lets cached queries return instantly without interrupting a scan.
                            if (queryHeightMode) {
                                val queryHeight = requestedHeight.toLongOrNull()
                                if (queryHeight != null && queryHeight >= 0L) {
                                    val cachedDate = heightToDateCache[queryHeight]
                                        ?: preferences.getString(dateCacheKey(queryHeight), null)
                                    if (!cachedDate.isNullOrBlank() && parseDate(cachedDate) != null) {
                                        cacheHeightDatePair(queryHeight, cachedDate)
                                        date = cachedDate
                                        error = null
                                        queryByHeight = true
                                        return@AnonOutlineButton
                                    }
                                }
                            } else {
                                val parsedDate = parseDate(requestedDate)
                                if (parsedDate != null) {
                                    val cachedHeight = dateToHeightCache[requestedDate]
                                        ?: preferences.getLong(
                                            heightCacheKey(requestedDate),
                                            Long.MIN_VALUE
                                        ).takeIf { it != Long.MIN_VALUE }
                                    if (cachedHeight != null && cachedHeight >= 0L) {
                                        cacheHeightDatePair(cachedHeight, requestedDate)
                                        height = cachedHeight.toString()
                                        error = null
                                        queryByHeight = false
                                        return@AnonOutlineButton
                                    }
                                }
                            }

                            val requestId = actionGeneration + 1L
                            actionGeneration = requestId
                            resetting = false
                            querying = true
                            error = null
                            coroutineScope.launch {
                                try {
                                    if (queryHeightMode) {
                                        val queryHeight = requestedHeight.toLongOrNull()
                                        if (queryHeight == null || queryHeight < 0L) {
                                            error = "请输入有效区块高度"
                                            return@launch
                                        }

                                        val cachedDate = heightToDateCache[queryHeight]
                                            ?: preferences.getString(dateCacheKey(queryHeight), null)
                                        val queryResult = walletState.executeLatestWalletQuery { queryWallet ->
                                            cachedDate ?: run {
                                                val timestamp = queryWallet.getBlockTimestamp(queryHeight)
                                                if (timestamp > 0L) dateForTimestamp(timestamp) else null
                                            }
                                        }
                                        if (requestId != actionGeneration) return@launch
                                        if (queryResult.isFailure) {
                                            error = queryResult.exceptionOrNull()?.message
                                                ?: "查询失败，请检查节点连接后重试"
                                            return@launch
                                        }
                                        val resolvedDate = queryResult.getOrThrow()

                                        if (height != requestedHeight || !queryByHeight) return@launch
                                        if (resolvedDate == null) {
                                            error = "无法查询该高度的区块时间，请检查节点连接"
                                            return@launch
                                        }

                                        cacheHeightDatePair(queryHeight, resolvedDate)
                                        date = resolvedDate
                                        error = null
                                    } else {
                                        val parsedDate = parseDate(requestedDate)
                                        if (parsedDate == null) {
                                            error = "请输入有效日期，格式为 yyyy-MM-dd"
                                            return@launch
                                        }

                                        val cachedHeight = dateToHeightCache[requestedDate]
                                            ?: preferences.getLong(
                                                heightCacheKey(requestedDate),
                                                Long.MIN_VALUE
                                            ).takeIf { it != Long.MIN_VALUE }
                                        val queryResult = walletState.executeLatestWalletQuery { queryWallet ->
                                            if (cachedHeight != null) {
                                                cachedHeight
                                            } else {
                                                val calendar = java.util.Calendar.getInstance().apply {
                                                    time = parsedDate
                                                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                                                    set(java.util.Calendar.MINUTE, 0)
                                                    set(java.util.Calendar.SECOND, 0)
                                                    set(java.util.Calendar.MILLISECOND, 0)
                                                }
                                                queryWallet.getBlockChainHeightByDate(
                                                    calendar.get(java.util.Calendar.YEAR),
                                                    calendar.get(java.util.Calendar.MONTH) + 1,
                                                    calendar.get(java.util.Calendar.DAY_OF_MONTH)
                                                )
                                            }
                                        }
                                        if (requestId != actionGeneration) return@launch
                                        if (queryResult.isFailure) {
                                            error = queryResult.exceptionOrNull()?.message
                                                ?: "查询失败，请检查节点连接后重试"
                                            return@launch
                                        }
                                        val resolvedHeight = queryResult.getOrThrow()

                                        if (date != requestedDate || queryByHeight) return@launch
                                        if (resolvedHeight == null || resolvedHeight < 0L) {
                                            error = "无法查询该日期的区块高度，请检查节点连接或日期"
                                            return@launch
                                        }

                                        cacheHeightDatePair(resolvedHeight, requestedDate)
                                        height = resolvedHeight.toString()
                                        error = null
                                    }
                                } catch (e: Exception) {
                                    if (requestId == actionGeneration) {
                                        error = e.message ?: "查询失败，请检查节点连接后重试"
                                    }
                                } finally {
                                    if (requestId == actionGeneration) querying = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(.9f)
                    ) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.size(width = 64.dp, height = 24.dp),
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            if (querying || resetting) {
                                FixedSpinIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(if (canReset) "重置" else "查询")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(56.dp))

            OutlinedTextField(
                value = height,
                onValueChange = {
                    error = null
                    height = it.filter(Char::isDigit)
                    queryByHeight = true
                    val enteredHeight = height.toLongOrNull()
                    if (enteredHeight != null && enteredHeight >= 0L) {
                        cachedDateForHeight(enteredHeight)?.let { cachedDate ->
                            cacheHeightDatePair(enteredHeight, cachedDate)
                            date = cachedDate
                            error = null
                        }
                    }
                    // Clearing height leaves date untouched; a cached height shows its date immediately.
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
                enabled = !resetting && !querying,
                label = { Text("高度恢复") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Spacer(modifier = Modifier.height(56.dp))

            OutlinedTextField(
                value = date,
                onValueChange = {
                    error = null
                    date = it
                    queryByHeight = false
                    val enteredDate = parseDate(date)
                    if (enteredDate != null) {
                        cachedHeightForDate(date)?.let { cachedHeight ->
                            cacheHeightDatePair(cachedHeight, date)
                            height = cachedHeight.toString()
                            error = null
                        }
                    }
                    // Clearing date leaves height untouched; a cached date shows its height immediately.
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
                enabled = !resetting && !querying,
                label = { Text("日期恢复") },
                singleLine = true
            )

            if (error != null) {
                Text(
                    text = error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}

@Preview(device = "id:pixel_7_pro")
@Composable
private fun ResetSyncPagePreview() {
    AnonNeroTheme {
        ResetSyncPage()
    }
}
