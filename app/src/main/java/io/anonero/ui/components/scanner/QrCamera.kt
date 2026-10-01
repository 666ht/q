package io.anonero.ui.components.scanner

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.sparrowwallet.hummingbird.URDecoder
import io.anonero.icons.AnonIcons
import timber.log.Timber
import java.util.concurrent.Executors

private const val TAG = "QrCamera"


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QRScannerDialog(
    modifier: Modifier = Modifier,
    show: Boolean,
    onDismiss: () -> Unit,
    onUrRusult: (URDecoder.Result) -> Unit = {},
    onQRCodeScanned: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    if (show) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = Color.Transparent,
            sheetState = sheetState,
            scrimColor = MaterialTheme.colorScheme.background,
            contentColor = Color.Transparent,
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
            properties = ModalBottomSheetProperties(
                shouldDismissOnBackPress = true,
            )
        ) {

            Box(Modifier.padding(0.dp)) {
                QRScanner(
                    onDismiss = onDismiss,
                    onQRCodeScanned = {
                        onDismiss.invoke()
                        onQRCodeScanned.invoke(it)
                    },
                    onUrRusult = {

                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

    }
}


@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun QRScanner(
    onQRCodeScanned: (String) -> Unit, modifier: Modifier = Modifier,
    onUrRusult: (URDecoder.Result) -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    val decoder = remember { URDecoder() }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermissionState = rememberPermissionState(permission = Manifest.permission.CAMERA)
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var isScanned by remember { mutableStateOf(false) }
    var progrees by remember { mutableFloatStateOf(0.0f) }
    val progressAnimDuration = 800
    val progressAnimation by animateFloatAsState(
        targetValue = progrees,
        animationSpec = tween(durationMillis = progressAnimDuration, easing = FastOutSlowInEasing),
    )
    val widthInPx: Float
    val heightInPx: Float
    val view = LocalView.current

    with(LocalDensity.current) {
        widthInPx = 240.dp.toPx()
        heightInPx = 240.dp.toPx()
    }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(true) {
        onDispose {
            cameraProvider?.unbindAll()
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        when (val status = cameraPermissionState.status) {
            is PermissionStatus.Denied -> {
                if (!status.shouldShowRationale) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            AnonIcons.ScanQrCode,
                            contentDescription = "",
                            tint = Color.White,
                            modifier = Modifier
                                .size(84.dp)
                                .padding(
                                    bottom = 8.dp
                                )
                        )
                        Text(

                            stringResource(R.string.camera_access_qr_code),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = Color.White
                            ),
                            modifier = Modifier.padding(
                                horizontal = 12.dp,
                                vertical = 16.dp
                            )
                        )
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
                                cameraPermissionState.launchPermissionRequest()
                            }) {
                            Text(stringResource(R.string.allow_button))
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            AnonIcons.ScanQrCode,
                            contentDescription = "",
                            tint = Color.White,
                            modifier = Modifier
                                .size(84.dp)
                                .padding(
                                    bottom = 8.dp
                                )
                        )
                        Text(
                            stringResource(R.string.camera_permission_required),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = Color.White
                            ),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(
                                horizontal = 14.dp,
                                vertical = 16.dp
                            )
                        )
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
                                // Open app settings to manually enable the permission
                                val intent =
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                context.startActivity(intent)
                            }) {
                            Text(stringResource(R.string.open_app_settings))
                        }
                    }
                }
            }

            PermissionStatus.Granted -> {
                Box {
                    AndroidView(
                        modifier = modifier.fillMaxSize(),
                        factory = { ctx ->
                            val previewView = PreviewView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }
                            // Set up CameraX
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                cameraProvider = cameraProviderFuture.get()

                                // Preview
                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = previewView.surfaceProvider
                                }

                                // ImageAnalysis
                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                    .also { analysis ->
                                        analysis.setAnalyzer(
                                            cameraExecutor,
                                            QrCodeAnalyzerBoofcv(onQrCodesDetected = {
                                                val result = it.text
                                                if (result.lowercase().startsWith("ur:")) {
                                                    decoder.receivePart(result)
                                                    val progreesPercentage =
                                                        decoder.estimatedPercentComplete;
                                                    if (progreesPercentage.toFloat() != progrees) {
                                                        progrees = progreesPercentage.toFloat()
                                                        view.performHapticFeedback(
                                                            HapticFeedbackConstants.KEYBOARD_TAP
                                                        )
                                                    }
                                                    if (decoder.result != null) {
                                                        if (!isScanned) {
                                                            onUrRusult.invoke(decoder.result)
                                                        }
                                                        isScanned = true
                                                    }
                                                } else {
                                                    if (!isScanned)
                                                        onQRCodeScanned.invoke(it.text)
                                                    isScanned = true
                                                }
                                            })
                                        )
                                    }

                                try {
                                    // Unbind previous use cases before rebinding
                                    cameraProvider?.unbindAll()
                                    cameraProvider?.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        imageAnalysis
                                    )
                                } catch (exc: Exception) {
                                    Timber.tag(TAG).e(exc)
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        update = { })
                    Canvas(
                        modifier = Modifier
                            .size(240.dp)
                            .align(Alignment.Center)
                            .onGloballyPositioned { layoutCoordinates ->
                                canvasSize = layoutCoordinates.size
                            }
                    ) {
                        if (canvasSize != IntSize.Zero) {
                            val offset = Offset(
                                x = (canvasSize.width - widthInPx) / 2,
                                y = (canvasSize.height - heightInPx) / 2
                            )
                            // Draw L-shaped corner markers
                            val cornerLength = widthInPx * 0.32f
                            val strokeWidth = 3.dp.toPx()
                            val cornerPadding = 4.dp.toPx()
                            val cornerRadius = 8.dp.toPx()

                            // Top corner left
                            drawLine(
                                color = Color.White,
                                start = Offset(
                                    offset.x - cornerPadding,
                                    offset.y - cornerPadding + cornerLength
                                ),
                                end = Offset(
                                    offset.x - cornerPadding,
                                    offset.y - cornerPadding + cornerRadius
                                ),
                                strokeWidth = strokeWidth,
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                color = Color.White,
                                start = Offset(
                                    offset.x - cornerPadding + cornerRadius,
                                    offset.y - cornerPadding
                                ),
                                end = Offset(
                                    offset.x - cornerPadding + cornerLength,
                                    offset.y - cornerPadding
                                ),
                                strokeWidth = strokeWidth,
                                cap = StrokeCap.Round
                            )
                            drawArc(
                                color = Color.White,
                                startAngle = 180f,
                                sweepAngle = 90f,
                                useCenter = false,
                                topLeft = Offset(
                                    offset.x - cornerPadding,
                                    offset.y - cornerPadding
                                ) + Offset(0f, 0f),
                                size = Size(cornerRadius * 2, cornerRadius * 2),
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )

                            // Top corner, right
                            drawLine(
                                color = Color.White,
                                start = Offset(
                                    offset.x + widthInPx + cornerPadding - cornerLength,
                                    offset.y - cornerPadding
                                ),
                                end = Offset(
                                    offset.x + widthInPx + cornerPadding - cornerRadius,
                                    offset.y - cornerPadding
                                ),
                                strokeWidth = strokeWidth,
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                color = Color.White,
                                start = Offset(
                                    offset.x + widthInPx + cornerPadding,
                                    offset.y - cornerPadding + cornerRadius
                                ),
                                end = Offset(
                                    offset.x + widthInPx + cornerPadding,
                                    offset.y - cornerPadding + cornerLength
                                ),
                                strokeWidth = strokeWidth,
                                cap = StrokeCap.Round
                            )
                            drawArc(
                                color = Color.White,
                                startAngle = 270f,
                                sweepAngle = 90f,
                                useCenter = false,
                                topLeft = Offset(
                                    offset.x + widthInPx + cornerPadding - cornerRadius * 2,
                                    offset.y - cornerPadding
                                ),
                                size = Size(cornerRadius * 2, cornerRadius * 2),
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )

                            // Bottom corner left
                            drawLine(
                                color = Color.White,
                                start = Offset(
                                    offset.x - cornerPadding,
                                    offset.y + heightInPx + cornerPadding - cornerLength
                                ),
                                end = Offset(
                                    offset.x - cornerPadding,
                                    offset.y + heightInPx + cornerPadding - cornerRadius
                                ),
                                strokeWidth = strokeWidth,
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                color = Color.White,
                                start = Offset(
                                    offset.x - cornerPadding + cornerRadius,
                                    offset.y + heightInPx + cornerPadding
                                ),
                                end = Offset(
                                    offset.x - cornerPadding + cornerLength,
                                    offset.y + heightInPx + cornerPadding
                                ),
                                strokeWidth = strokeWidth,
                                cap = StrokeCap.Round
                            )
                            drawArc(
                                color = Color.White,
                                startAngle = 90f,
                                sweepAngle = 90f,
                                useCenter = false,
                                topLeft = Offset(
                                    offset.x - cornerPadding,
                                    offset.y + heightInPx + cornerPadding - cornerRadius * 2
                                ),
                                size = Size(cornerRadius * 2, cornerRadius * 2),
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )

                            // Bottom corner right
                            drawLine(
                                color = Color.White,
                                start = Offset(
                                    offset.x + widthInPx + cornerPadding,
                                    offset.y + heightInPx + cornerPadding - cornerLength
                                ),
                                end = Offset(
                                    offset.x + widthInPx + cornerPadding,
                                    offset.y + heightInPx + cornerPadding - cornerRadius
                                ),
                                strokeWidth = strokeWidth,
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                color = Color.White,
                                start = Offset(
                                    offset.x + widthInPx + cornerPadding - cornerLength,
                                    offset.y + heightInPx + cornerPadding
                                ),
                                end = Offset(
                                    offset.x + widthInPx + cornerPadding - cornerRadius,
                                    offset.y + heightInPx + cornerPadding
                                ),
                                strokeWidth = strokeWidth,
                                cap = StrokeCap.Round
                            )
                            drawArc(
                                color = Color.White,
                                startAngle = 0f,
                                sweepAngle = 90f,
                                useCenter = false,
                                topLeft = Offset(
                                    offset.x + widthInPx + cornerPadding - cornerRadius * 2,
                                    offset.y + heightInPx + cornerPadding - cornerRadius * 2
                                ),
                                size = Size(cornerRadius * 2, cornerRadius * 2),
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )
                        }
                    }
                    if (progrees != 0.0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(172.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .align(Alignment.Center)

                            ) {
                                Text(
                                    "${(progrees * 100).toInt()}%", textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .align(Alignment.Center),
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                            CircularProgressIndicator(
                                strokeWidth = 6.dp,
                                modifier = Modifier
                                    .padding(24.dp)
                                    .align(Alignment.Center)
                                    .size(180.dp),
                                progress = {
                                    progressAnimation
                                }
                            )
                        }
                        Button(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 24.dp),
                            shape = MaterialTheme.shapes.extraSmall.copy(
                                all = CornerSize(12.dp)
                            ),
                            border = BorderStroke(
                                1.dp,
                                color = MaterialTheme.colorScheme.onSecondary
                            ),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            onClick = {
                                onDismiss.invoke()
                            }) {
                            Row {
                                Icon(Icons.Default.Close, contentDescription = "")
                                Spacer(Modifier.padding(8.dp))
                                Text(stringResource(R.string.close_button))
                                Spacer(Modifier.padding(12.dp))
                            }
                        }
                    }
                }
            }
        }

    }
}
