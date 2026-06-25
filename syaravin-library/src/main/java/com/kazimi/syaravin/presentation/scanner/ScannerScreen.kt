@file:Suppress("ktlint:standard:function-naming")

package com.kazimi.syaravin.presentation.scanner

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.kazimi.syaravin.R
import com.kazimi.syaravin.data.datasource.camera.CameraDataSource
import com.kazimi.syaravin.data.datasource.ml.TextExtractor
import com.kazimi.syaravin.data.datasource.ml.VinDetector
import com.kazimi.syaravin.data.datasource.validator.VinValidator
import com.kazimi.syaravin.di.VinScannerDependencies
import com.kazimi.syaravin.domain.model.VinNumber
import com.kazimi.syaravin.presentation.components.BoundingBoxOverlay
import com.kazimi.syaravin.presentation.components.CameraPreview
import com.kazimi.syaravin.presentation.components.CaptureButton
import com.kazimi.syaravin.presentation.components.RoiOverlay
import com.kazimi.syaravin.ui.theme.RoiDetectedBorder
import com.kazimi.syaravin.ui.theme.RoiInvalidBorder
import com.kazimi.syaravin.ui.theme.RoiValidBorder
import com.kazimi.syaravin.util.FocusState
import com.kazimi.syaravin.util.ImagePreprocessor
import com.kazimi.syaravin.util.LogTags
import com.kazimi.syaravin.util.RoiConfig
import com.kazimi.syaravin.util.SLog
import com.kazimi.syaravin.util.ScanFeedback
import com.kazimi.syaravin.util.ScannerPerfConfig
import com.kazimi.syaravin.util.Sharpness
import com.kazimi.syaravin.util.ThermalManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private const val TAG = LogTags.LIBRARY

private const val PREF_AUTO_SCAN_ENABLED = "auto_scan_enabled"

// Auto-accept geometry gates for the detected VIN box (normalized 0..1 within the analyzed frame).
// The analyzed frame IS the tight ROI band, so a well-placed VIN fills its width (left~0,
// right~1) — we therefore only gate vertically (not clipped top/bottom, roughly vertically
// centered) and require the box to fill a decent fraction of the width (camera close enough).
private const val VIN_BOX_VERTICAL_MARGIN = 0.01f // box top/bottom must sit this far inside
private const val VIN_BOX_MAX_CENTER_OFFSET_Y = 0.45f // box vertical center within this of mid
private const val VIN_BOX_MIN_WIDTH = 0.20f // box at least this wide => camera close enough

private fun isVinBoxWellPositioned(box: com.kazimi.syaravin.domain.model.BoundingBox): Boolean {
    // Not clipped vertically (the VIN band spans the ROI width by design, so don't gate L/R).
    if (box.top < VIN_BOX_VERTICAL_MARGIN || box.bottom > 1f - VIN_BOX_VERTICAL_MARGIN) {
        return false
    }
    // Roughly vertically centered.
    val centerY = (box.top + box.bottom) / 2f
    if (abs(centerY - 0.5f) > VIN_BOX_MAX_CENTER_OFFSET_Y) {
        return false
    }
    // Close enough (box fills a decent fraction of the frame width).
    return (box.right - box.left) >= VIN_BOX_MIN_WIDTH
}

/**
 * Main scanner screen for VIN detection
 */
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ScannerScreen(
    onVinConfirmed: (VinNumber) -> Unit = {},
    onCancelled: () -> Unit = {},
) {
    val screenStartMs = remember { SystemClock.elapsedRealtime() }
    LaunchedEffect(Unit) {
        SLog.w(TAG, "ScannerScreen first composition reached after ${SystemClock.elapsedRealtime() - screenStartMs}ms")
    }

    // Create ViewModel with custom factory
    val viewModel: ScannerViewModel =
        viewModel(
            factory = ScannerViewModelFactory(),
        )

    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Get dependencies via remember to avoid recreating on recomposition
    val dependencies = remember { VinScannerDependencies.get() }
    var isWarmupComplete by remember { mutableStateOf(false) }
    val thermalManager = remember(context) { ThermalManager(context) }
    var thermalStatus by remember { mutableIntStateOf(thermalManager.currentStatus) }

    // Torch / flashlight — bound camera is hoisted up from CameraPreview so we can drive it.
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchEnabled by remember { mutableStateOf(false) }
    val hasFlashUnit = camera?.cameraInfo?.hasFlashUnit() == true

    // Apply torch state whenever it changes or the camera rebinds. Reset to off on unbind.
    LaunchedEffect(camera, torchEnabled) {
        val activeCamera = camera
        if (activeCamera == null) {
            torchEnabled = false
        } else if (activeCamera.cameraInfo.hasFlashUnit()) {
            activeCamera.cameraControl.enableTorch(torchEnabled)
        }
    }

    DisposableEffect(thermalManager) {
        thermalManager.start { status -> thermalStatus = status }
        onDispose(thermalManager::stop)
    }

    // Factory-created instances (per-screen lifecycle)
    val cameraSelector = remember { dependencies.createCameraSelector() }
    val preview = remember { dependencies.createPreview() }
    val imageAnalysis = remember { dependencies.createImageAnalysis() }
    val imageCapture = remember { dependencies.createImageCapture() }
    val executor = remember { dependencies.createExecutor() }

    // Defer heavy singleton creation until first frame processing on background thread.
    val cameraDataSourceLazy = remember { lazy { dependencies.cameraDataSource } }
    val vinDetectorLazy = remember { lazy { dependencies.vinDetector } }
    val textExtractorLazy = remember { lazy { dependencies.textExtractor } }
    val vinValidatorLazy = remember { lazy { dependencies.vinValidator } }

    // Success chime + slight vibration on auto-detect.
    val scanFeedback = remember(context) { ScanFeedback(context) }
    DisposableEffect(scanFeedback) {
        onDispose { scanFeedback.release() }
    }

    // Sharpness accept gate (#2): hold soft frames briefly and accept the sharpest, with a
    // time-bounded fallback so a steady-but-soft scene still completes. Survives recomposition.
    val acceptGate =
        remember {
            VinAcceptState(
                VinAcceptDecider(
                    enabled = ScannerPerfConfig.sharpnessGateEnabled,
                    sharpThreshold = ScannerPerfConfig.sharpnessThreshold,
                    acceptTimeoutMs = ScannerPerfConfig.sharpnessAcceptTimeoutMs,
                    resetGapMs = ScannerPerfConfig.sharpnessResetGapMs,
                ),
            )
        }

    // Clean up executor on dispose
    val processingScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    DisposableEffect(Unit) {
        onDispose {
            SLog.d(TAG, "Shutting down camera executor")
            imageAnalysis.clearAnalyzer()
            acceptGate.reset()
            processingScope.cancel()
            executor.shutdownNow()
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                SLog.w(TAG, "Camera executor did not terminate within timeout")
            }
        }
    }

    // Camera permission
    val cameraPermissionState =
        rememberPermissionState(permission = Manifest.permission.CAMERA, onPermissionResult = { granted ->
            if (granted) {
                SLog.d(TAG, "Camera permission granted.")
                viewModel.onEvent(ScannerEvent.PermissionGranted)
            } else {
                SLog.w(TAG, "Camera permission denied.")
                viewModel.onEvent(ScannerEvent.PermissionDenied)
            }
        })

    // Warm up heavy dependencies in background to reduce first-run frame drops.
    LaunchedEffect(Unit) {
        SLog.d(TAG, "Starting scanner dependency warmup")
        val warmupStart = System.currentTimeMillis()
        withContext(Dispatchers.Default) {
            dependencies.warmUpScannerDependencies()
        }
        isWarmupComplete = true
        SLog.d(TAG, "Scanner dependency warmup completed in ${System.currentTimeMillis() - warmupStart}ms")
    }

    // Request permission on first launch
    LaunchedEffect(Unit) {
        val granted = cameraPermissionState.status.isGranted
        SLog.w(TAG, "Permission status check result: granted=$granted")
        if (!granted) {
            cameraPermissionState.launchPermissionRequest()
        } else {
            viewModel.onEvent(ScannerEvent.PermissionGranted)
        }
    }

    val isProcessingFrame = remember { AtomicBoolean(false) }
    val isManualCaptureRequested = remember { AtomicBoolean(false) }
    val analysisMutex = remember { Mutex() }
    var isManualCaptureBusy by remember { mutableStateOf(false) }
    val lastProcessTime = remember { AtomicLong(0L) }
    val roiFrameCounter = remember { AtomicLong(0L) }

    // Auto-scan toggle, persisted across sessions. When off, frames are not analyzed continuously;
    // scanning happens only on manual capture.
    val scannerPrefs =
        remember(context) {
            context.getSharedPreferences("syaravin_scanner_prefs", Context.MODE_PRIVATE)
        }
    var autoScanEnabled by remember {
        mutableStateOf(scannerPrefs.getBoolean(PREF_AUTO_SCAN_ENABLED, true))
    }

    // Set up image analysis — always attach the analyzer so it's initialized every session.
    // Frames only produce results while auto-scan is enabled (checked live below).
    DisposableEffect(state.isScanning, isWarmupComplete, thermalStatus) {
        if (state.isScanning && isWarmupComplete) {
            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                val currentTime = System.currentTimeMillis()
                val previousTime = lastProcessTime.get()

                if (autoScanEnabled &&
                    !isManualCaptureRequested.get() &&
                    currentTime - previousTime >=
                    ThermalManager.inferenceIntervalMs(
                        ScannerPerfConfig.inferenceIntervalMs,
                        thermalStatus,
                    ) &&
                    isProcessingFrame.compareAndSet(false, true)
                ) {
                    lastProcessTime.set(currentTime)
                    val frameReceivedNs = System.nanoTime()
                    processingScope.launch {
                        try {
                            analysisMutex.withLock {
                                processImage(
                                    frameReceivedNs = frameReceivedNs,
                                    imageProxy = imageProxy,
                                    cameraDataSource = cameraDataSourceLazy.value,
                                    vinDetector = vinDetectorLazy.value,
                                    textExtractor = textExtractorLazy.value,
                                    vinValidator = vinValidatorLazy.value,
                                    roiFrameCounter = roiFrameCounter,
                                    acceptGate = acceptGate,
                                    onVinDetected = { vin, confidence, croppedBitmap ->
                                        if (isManualCaptureRequested.get()) {
                                            croppedBitmap?.recycle()
                                        } else {
                                            viewModel.onVinDetected(vin, confidence, croppedBitmap)
                                        }
                                    },
                                    onBoxesDetected = viewModel::onDetectionBoxesUpdated,
                                    onRoiBorderStateChange = { roiState ->
                                        viewModel.onEvent(ScannerEvent.UpdateRoiBorderState(roiState))
                                    },
                                    onRoiBitmapCaptured = viewModel::onRoiCroppedBitmapUpdated,
                                    onCandidateScanned = viewModel::onCandidateScanned,
                                )
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } finally {
                            isProcessingFrame.set(false)
                        }
                    }
                } else {
                    ScannerPerfConfig.frameTiming.onFrameDropped()
                    imageProxy.close()
                }
            }
        }

        onDispose {
            imageAnalysis.clearAnalyzer()
        }
    }

    // Auto-confirm VIN immediately without showing bottom sheet
    LaunchedEffect(state.detectedVin) {
        state.detectedVin?.let { vinNumber ->
            // Success chime + slight vibration, then invoke callback.
            scanFeedback.success()
            onVinConfirmed(vinNumber)
        }
    }

    // Bottom sheet removed - auto-confirm is now enabled

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        if (state.hasPermission && state.isScanning) {
            // Camera preview
            CameraPreview(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(20.dp)),
                cameraSelector = cameraSelector,
                preview = preview,
                imageAnalyzer = imageAnalysis,
                imageCapture = imageCapture,
                onCameraBound = { camera = it },
            )

            // ROI overlay border target color. RoiOverlay animates this internally and reads it
            // only in its draw phase, so the transition does not recompose this screen.
            val roiBorderTarget =
                when (state.roiBorderState) {
                    RoiBorderState.VALID_VIN_DETECTED -> RoiValidBorder
                    RoiBorderState.NEUTRAL -> RoiDetectedBorder
                    RoiBorderState.NO_DETECTION -> RoiInvalidBorder
                }

            RoiOverlay(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(9f / 16f)
                        .align(Alignment.Center),
                roiBox = RoiConfig.roi,
                borderColor = roiBorderTarget,
            )

            // Top banner: while warming up show the "preparing" notice; afterwards show the
            // guidance (keep VIN inside the box, centered, and close).
            Text(
                text =
                    stringResource(
                        if (isWarmupComplete) R.string.scanner_guidance else R.string.scanner_preparing,
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 140.dp, start = 24.dp, end = 24.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(12.dp),
                        ).padding(horizontal = 16.dp, vertical = 10.dp),
            )

            // Bounding box overlay
            BoundingBoxOverlay(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(9f / 16f)
                        .align(Alignment.Center),
                boundingBoxes = state.detectionBoxes,
            )
        } else if (!state.hasPermission) {
            // Permission denied message
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.camera_permission_required_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.camera_permission_required_message),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = { cameraPermissionState.launchPermissionRequest() },
                ) {
                    Text(stringResource(R.string.grant_permission))
                }
            }
        }

        // Top bar with controls
        if (state.hasPermission) {
            TopAppBar(
                modifier = Modifier.align(Alignment.TopCenter),
                title = {},
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.5f),
                        titleContentColor = Color.White,
                    ),
                actions = {
                    // Auto-scan on/off (persisted). Off → results suppressed; analyzer stays attached.
                    IconButton(
                        onClick = {
                            val enabled = !autoScanEnabled
                            autoScanEnabled = enabled
                            scannerPrefs.edit().putBoolean(PREF_AUTO_SCAN_ENABLED, enabled).apply()
                        },
                        modifier =
                            Modifier.semantics {
                                contentDescription = "vin_scanner_toggle_auto_scan"
                            },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_barcode_scan),
                            contentDescription = stringResource(R.string.scanner_auto_scan_label),
                            tint = if (autoScanEnabled) Color(0xFFFFC107) else Color.White,
                        )
                    }
                    if (hasFlashUnit) {
                        IconButton(
                            onClick = { torchEnabled = !torchEnabled },
                            modifier =
                                Modifier.semantics {
                                    contentDescription = "vin_scanner_toggle_torch"
                                },
                        ) {
                            Icon(
                                imageVector = if (torchEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                                contentDescription =
                                    stringResource(
                                        if (torchEnabled) R.string.torch_off else R.string.torch_on,
                                    ),
                                tint = if (torchEnabled) Color(0xFFFFC107) else Color.White,
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            if (state.isScanning) {
                                viewModel.onEvent(ScannerEvent.StopScanning)
                            } else {
                                viewModel.onEvent(ScannerEvent.StartScanning)
                            }
                        },
                        modifier =
                            Modifier.semantics {
                                contentDescription = "vin_scanner_toggle_scanning"
                            },
                    ) {
                        Icon(
                            imageVector =
                                if (state.isScanning) {
                                    Icons.Filled.Stop
                                } else {
                                    Icons.Filled.PlayArrow
                                },
                            contentDescription =
                                if (state.isScanning) {
                                    stringResource(
                                        R.string.stop,
                                    )
                                } else {
                                    stringResource(R.string.start)
                                },
                            tint = Color.White,
                        )
                    }
                },
            )
        }

        // Enter manually button at bottom (camera shutter style)
        if (state.hasPermission && state.isScanning) {
            // Lock the capture button once a manual capture is running or a VIN was auto-detected,
            // so the user can't double-trigger while the result is being prepared (~0.5s).
            val captureLocked = isManualCaptureBusy || state.detectedVin != null
            CaptureButton(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp),
                enabled = !captureLocked,
                capturing = captureLocked,
                onTap = {
                    if (!isManualCaptureRequested.compareAndSet(false, true)) return@CaptureButton
                    isManualCaptureBusy = true
                    val fallbackRoiBitmap =
                        state.latestRoiCroppedBitmap
                            ?.takeUnless(Bitmap::isRecycled)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    SLog.d(TAG, "Enter manually button clicked")
                    processingScope.launch {
                        try {
                            val result =
                                analysisMutex.withLock {
                                    // Drop any pending soft auto-accept candidate; manual capture wins.
                                    acceptGate.reset()
                                    analyzeManualCapture(
                                        imageCapture = imageCapture,
                                        captureExecutor = executor,
                                        fallbackRoiBitmap = fallbackRoiBitmap,
                                        vinDetector = vinDetectorLazy.value,
                                        textExtractor = textExtractorLazy.value,
                                        vinValidator = vinValidatorLazy.value,
                                    )
                                }
                            withContext(Dispatchers.Main) { onVinConfirmed(result) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            SLog.e(TAG, "Manual capture analysis failed", e)
                            withContext(Dispatchers.Main) {
                                onVinConfirmed(VinNumber(value = "", confidence = 0f, isValid = false))
                            }
                        } finally {
                            isManualCaptureRequested.set(false)
                            withContext(Dispatchers.Main) { isManualCaptureBusy = false }
                        }
                    }
                },
            )

            // Live "possible VIN" feedback, shown just above the capture button.
            AnimatedVisibility(
                visible = state.scannedCandidate != null,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 120.dp),
            ) {
                val candidate = state.scannedCandidate
                if (candidate != null) {
                    ScannedCandidateCard(candidate)
                }
            }
        }

        // Error snackbar
        state.errorMessage?.let { error ->
            Snackbar(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                action = {
                    TextButton(
                        onClick = { viewModel.onEvent(ScannerEvent.DismissError) },
                        modifier =
                            Modifier.semantics {
                                contentDescription = "vin_scanner_dismiss_error"
                            },
                    ) {
                        Text(stringResource(R.string.dismiss))
                    }
                },
            ) {
                Text(error)
            }
        }
    }
}

@Composable
private fun ScannedCandidateCard(candidate: ScannedCandidate) {
    // Light, near-opaque pill so the dark text reads clearly over the camera feed.
    // Green when valid, red when not.
    val fill = if (candidate.isValid) Color(0xFFC8E6C9) else Color(0xFFFFCDD2)
    val textColor = if (candidate.isValid) Color(0xFF1B5E20) else Color(0xFFB71C1C)
    Row(
        modifier =
            Modifier
                .background(fill.copy(alpha = 0.92f), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(targetState = candidate.value, label = "candidate_vin") { value ->
            Text(
                text = value,
                color = textColor,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "${(candidate.confidence * 100).toInt()}%",
            color = textColor.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private val scanFrameCounter = AtomicLong(0)

private suspend fun analyzeManualCapture(
    imageCapture: ImageCapture,
    captureExecutor: ExecutorService,
    fallbackRoiBitmap: Bitmap?,
    vinDetector: VinDetector,
    textExtractor: TextExtractor,
    vinValidator: VinValidator,
): VinNumber {
    SLog.w(TAG, "MANUAL_CAPTURE analysis started")
    val capturedBitmap =
        try {
            captureStillBitmap(imageCapture, captureExecutor)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            SLog.w(TAG, "Still capture failed; analyzing latest ROI frame", e)
            null
        }

    val analysisBitmap =
        if (capturedBitmap != null) {
            try {
                cropCapturedBitmapToOverlayRoi(capturedBitmap)
            } finally {
                capturedBitmap.recycle()
                fallbackRoiBitmap?.recycle()
            }
        } else {
            fallbackRoiBitmap
        } ?: return VinNumber(value = "", confidence = 0f, isValid = false)

    try {
        val boxes =
            vinDetector
                .detect(analysisBitmap)
                .boundingBoxes
                .sortedByDescending { it.confidence }
                .take(3)
        SLog.w(TAG, "MANUAL_CAPTURE detected_boxes=${boxes.size} bitmap=${analysisBitmap.width}x${analysisBitmap.height}")
        val extractedCandidates =
            coroutineScope {
                boxes
                    .map { box ->
                        async { box to textExtractor.extractText(analysisBitmap, box) }
                    }.awaitAll()
            }

        for ((box, rawText) in extractedCandidates) {
            if (rawText.isNullOrBlank()) continue
            val candidate = vinValidator.cleanVin(rawText)
            val validation = vinValidator.validate(candidate)
            if (!validation.isValid) continue

            // Keep the full captured frame as the result image (no VIN-box crop). Manual capture
            // is a high-res still → contrast only, no sharpen (avoids halos on already-sharp text).
            return VinNumber(
                value = validation.correctedVin ?: candidate,
                confidence = box.confidence,
                isValid = true,
                croppedImage = ImagePreprocessor.enhanceForDisplay(analysisBitmap, sharpen = false),
            )
        }

        val fullImageText = textExtractor.extractAllText(analysisBitmap)
        SLog.w(TAG, "MANUAL_CAPTURE fallback_ocr_lines=${fullImageText.size}")
        for (rawText in fullImageText) {
            if (rawText.isBlank()) continue
            val candidate = vinValidator.cleanVin(rawText)
            val validation = vinValidator.validate(candidate)
            SLog.w(
                TAG,
                "MANUAL_CAPTURE fallback_ocr=\"${rawText.take(40)}\" clean=\"$candidate\" valid=${validation.isValid}",
            )
            if (!validation.isValid) continue

            return VinNumber(
                value = validation.correctedVin ?: candidate,
                confidence = 1f,
                isValid = true,
                croppedImage = ImagePreprocessor.enhanceForDisplay(analysisBitmap, sharpen = false),
            )
        }

        return VinNumber(
            value = "",
            confidence = 0f,
            isValid = false,
            croppedImage = ImagePreprocessor.enhanceForDisplay(analysisBitmap, sharpen = false),
        )
    } finally {
        if (!analysisBitmap.isRecycled) analysisBitmap.recycle()
    }
}

private fun cropCapturedBitmapToOverlayRoi(capturedBitmap: Bitmap): Bitmap {
    val portraitBitmap = rotateLandscapeCaptureToPortrait(capturedBitmap)
    val overlayFrame = centerCropToAspectRatio(portraitBitmap, RoiConfig.analyzedImageAspectRatio)
    return try {
        val roi = RoiConfig.roi
        val left = floor(roi.left * overlayFrame.width).toInt().coerceIn(0, overlayFrame.width - 1)
        val top = floor(roi.top * overlayFrame.height).toInt().coerceIn(0, overlayFrame.height - 1)
        val right = ceil(roi.right * overlayFrame.width).toInt().coerceIn(left + 1, overlayFrame.width)
        val bottom = ceil(roi.bottom * overlayFrame.height).toInt().coerceIn(top + 1, overlayFrame.height)

        Bitmap.createBitmap(
            overlayFrame,
            left,
            top,
            right - left,
            bottom - top,
        ).also { roiBitmap ->
            SLog.w(
                TAG,
                "MANUAL_CAPTURE overlay_roi source=${capturedBitmap.width}x${capturedBitmap.height} " +
                    "portrait=${portraitBitmap.width}x${portraitBitmap.height} " +
                    "frame=${overlayFrame.width}x${overlayFrame.height} roi=${roiBitmap.width}x${roiBitmap.height}",
            )
        }
    } finally {
        if (overlayFrame !== capturedBitmap && !overlayFrame.isRecycled) {
            overlayFrame.recycle()
        }
        if (portraitBitmap !== capturedBitmap && portraitBitmap !== overlayFrame && !portraitBitmap.isRecycled) {
            portraitBitmap.recycle()
        }
    }
}

private fun rotateLandscapeCaptureToPortrait(bitmap: Bitmap): Bitmap {
    if (bitmap.height >= bitmap.width) return bitmap
    val matrix = Matrix().apply { postRotate(90f) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun centerCropToAspectRatio(
    bitmap: Bitmap,
    targetAspectRatio: Float,
): Bitmap {
    val currentAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
    if (abs(currentAspectRatio - targetAspectRatio) < 0.01f) return bitmap

    return if (currentAspectRatio > targetAspectRatio) {
        val targetWidth = (bitmap.height * targetAspectRatio).toInt().coerceIn(1, bitmap.width)
        val left = ((bitmap.width - targetWidth) / 2).coerceAtLeast(0)
        Bitmap.createBitmap(bitmap, left, 0, targetWidth, bitmap.height)
    } else {
        val targetHeight = (bitmap.width / targetAspectRatio).toInt().coerceIn(1, bitmap.height)
        val top = ((bitmap.height - targetHeight) / 2).coerceAtLeast(0)
        Bitmap.createBitmap(bitmap, 0, top, bitmap.width, targetHeight)
    }
}

private suspend fun captureStillBitmap(
    imageCapture: ImageCapture,
    executor: ExecutorService,
): Bitmap =
    suspendCancellableCoroutine { cont ->
        imageCapture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bmp = imageProxyJpegToBitmap(image)
                        if (cont.isActive) cont.resumeWith(Result.success(bmp))
                    } catch (t: Throwable) {
                        if (cont.isActive) cont.resumeWith(Result.failure(t))
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    if (cont.isActive) cont.resumeWith(Result.failure(exception))
                }
            },
        )
    }

private fun imageProxyJpegToBitmap(image: ImageProxy): Bitmap {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val decodeOptions =
        BitmapFactory.Options().apply {
            inSampleSize = captureDecodeSampleSize(bounds.outWidth, bounds.outHeight)
        }
    val decodedSource =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            ?: throw IllegalStateException("Failed to decode captured JPEG")
    val decoded = boundCapturedBitmap(decodedSource)
    val rotation = image.imageInfo.rotationDegrees
    if (rotation == 0) return decoded
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (rotated !== decoded && !decoded.isRecycled) decoded.recycle()
    return rotated
}

internal fun captureDecodeSampleSize(
    width: Int,
    height: Int,
): Int {
    if (width <= 0 || height <= 0) return 1
    var sampleSize = 1
    while (
        maxOf(width / (sampleSize * 2), height / (sampleSize * 2)) > 1920 ||
        minOf(width / (sampleSize * 2), height / (sampleSize * 2)) > 1080
    ) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun boundCapturedBitmap(bitmap: Bitmap): Bitmap {
    val scale =
        minOf(
            1f,
            1920f / maxOf(bitmap.width, bitmap.height),
            1080f / minOf(bitmap.width, bitmap.height),
        )
    if (scale >= 1f) return bitmap
    return Bitmap
        .createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        ).also { scaled ->
            if (scaled !== bitmap) bitmap.recycle()
        }
}

private suspend fun processImage(
    frameReceivedNs: Long,
    imageProxy: ImageProxy,
    cameraDataSource: CameraDataSource,
    vinDetector: VinDetector,
    textExtractor: TextExtractor,
    vinValidator: VinValidator,
    roiFrameCounter: AtomicLong,
    acceptGate: VinAcceptState,
    onVinDetected: (String, Float, Bitmap?) -> Unit,
    onBoxesDetected: (List<com.kazimi.syaravin.domain.model.BoundingBox>) -> Unit,
    onRoiBorderStateChange: (RoiBorderState) -> Unit,
    onRoiBitmapCaptured: (Bitmap) -> Unit,
    onCandidateScanned: (String?, Float, Boolean) -> Unit,
) {
    var stageImageToBitmapNs = 0L
    var stageRoiCropNs = 0L
    var stageDetectionNs = 0L
    var stageTextNs = 0L
    var stagePostNs = 0L
    try {
        // Convert only the rotation-aware ROI from YUV to RGB.
        val imageToBitmapStartNs = System.nanoTime()
        val roi = RoiConfig.roi
        val bitmap = cameraDataSource.imageToBitmap(imageProxy, roi)
        stageImageToBitmapNs = System.nanoTime() - imageToBitmapStartNs

        try {
            // The camera data source already returned the ROI; retain full-frame mapping metadata.
            val roiCropStartNs = System.nanoTime()
            val shouldCrop = true
            val processedBitmap = bitmap
            // Store a copy for manual-entry fallback; throttled to reduce allocation pressure.
            if (roiFrameCounter.incrementAndGet() % 5L == 1L) {
                try {
                    val roiCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    val safeRoiCopy = ImagePreprocessor.downscaleForDisplay(roiCopy)
                    if (safeRoiCopy !== roiCopy && !roiCopy.isRecycled) roiCopy.recycle()
                    withContext(Dispatchers.Main) { onRoiBitmapCaptured(safeRoiCopy) }
                } catch (e: Exception) {
                    SLog.e(TAG, "Failed to create ROI bitmap copy", e)
                }
            }
            stageRoiCropNs = System.nanoTime() - roiCropStartNs

            var bestVin: String? = null
            var bestConfidence = 0f
            var croppedVinBitmap: Bitmap? = null
            // First plausible read this frame, surfaced as the live "possible VIN".
            var frameCandidate: Triple<String, Float, Boolean>? = null

            try {
                // Run object detection to get bounding boxes on ROI image
                val detectionStartNs = System.nanoTime()
                val detectionResult = vinDetector.detect(processedBitmap)
                stageDetectionNs = System.nanoTime() - detectionStartNs
                val boxes = detectionResult.boundingBoxes
                val mappedBoxes =
                    if (shouldCrop) {
                        val roiWidthNorm = roi.right - roi.left
                        val roiHeightNorm = roi.bottom - roi.top
                        boxes.map { box ->
                            com.kazimi.syaravin.domain.model.BoundingBox(
                                left = roi.left + box.left * roiWidthNorm,
                                top = roi.top + box.top * roiHeightNorm,
                                right = roi.left + box.right * roiWidthNorm,
                                bottom = roi.top + box.bottom * roiHeightNorm,
                                confidence = box.confidence,
                            )
                        }
                    } else {
                        boxes
                    }
                onBoxesDetected(mappedBoxes)

                // Update ROI border state based on detection
                if (mappedBoxes.isEmpty()) {
                    onRoiBorderStateChange(RoiBorderState.NO_DETECTION)
                } else {
                    onRoiBorderStateChange(RoiBorderState.NEUTRAL)
                }

                // Try OCR inside each detected box first (sorted by confidence)
                val textStartNs = System.nanoTime()
                val frame = scanFrameCounter.incrementAndGet()
                val sortedBoxes = boxes.sortedByDescending { it.confidence }
                if (sortedBoxes.isNotEmpty()) {
                    SLog.w(
                        TAG,
                        "VIN_SCAN frame=$frame boxes=${sortedBoxes.size} analysisFrame=${imageProxy.width}x${imageProxy.height} cropBitmap=${processedBitmap.width}x${processedBitmap.height}",
                    )
                }
                val frameNowMs = System.currentTimeMillis()
                // Commit a held soft candidate once its hold window elapses — even with no fresh
                // valid read this frame (later frames may be misreads that never re-enter accept).
                // Checked before the stale-drop so timeout-accept wins over reset-gap.
                if (acceptGate.isAcceptTimedOut(frameNowMs)) {
                    bestVin = acceptGate.stashedVin
                    bestConfidence = acceptGate.stashedConfidence
                    croppedVinBitmap = acceptGate.takeStashedBitmap()
                    acceptGate.reset()
                    SLog.w(TAG, "VIN_SHARPNESS frame=$frame timeout -> ACCEPT_STASHED result=${bestVin ?: "none"}")
                } else if (acceptGate.isStale(frameNowMs)) {
                    SLog.w(TAG, "VIN_SHARPNESS frame=$frame stale pending dropped")
                    acceptGate.reset()
                }
                for ((boxIdx, box) in sortedBoxes.withIndex()) {
                    if (bestVin != null) break // already committed (timeout) — skip further OCR
                    val boxTag = "${boxIdx + 1}/${sortedBoxes.size}"
                    val boxCoords = "L=${"%.3f".format(
                        box.left,
                    )} T=${"%.3f".format(box.top)} R=${"%.3f".format(box.right)} B=${"%.3f".format(box.bottom)}"
                    val textInBox = textExtractor.extractText(processedBitmap, box)
                    if (textInBox.isNullOrBlank()) {
                        SLog.w(TAG, "VIN_CANDIDATE frame=$frame box=$boxTag conf=${"%.3f".format(box.confidence)} $boxCoords ocr=null")
                        continue
                    }
                    val candidate = vinValidator.cleanVin(textInBox)
                    val validation = vinValidator.validate(candidate)
                    val outcome = if (validation.isValid) "ACCEPTED" else "REJECTED"
                    val reason = validation.errorMessage ?: if (validation.checksumValid) "checksum_ok" else "soft_accept"
                    SLog.w(
                        TAG,
                        "VIN_CANDIDATE frame=$frame box=$boxTag conf=${"%.3f".format(
                            box.confidence,
                        )} $boxCoords ocr=\"${textInBox.take(40)}\" clean=\"$candidate\" $outcome reason=\"$reason\"",
                    )
                    if (frameCandidate == null && candidate.length >= 11) {
                        frameCandidate = Triple(validation.correctedVin ?: candidate, box.confidence, validation.isValid)
                    }
                    // Auto-accept ONLY checksum-valid reads. Soft-accept (format-valid but
                    // checksum-failed) is shown as a live candidate but never auto-confirmed —
                    // otherwise a misread VIN gets delivered with false confidence.
                    if (validation.isValid && validation.checksumValid) {
                        if (!isVinBoxWellPositioned(box)) {
                            val cx = (box.left + box.right) / 2f
                            val cy = (box.top + box.bottom) / 2f
                            SLog.w(
                                TAG,
                                "VIN_REJECT_POSITION frame=$frame box=$boxTag $boxCoords " +
                                    "center=(${"%.2f".format(cx)},${"%.2f".format(cy)}) w=${"%.2f".format(box.right - box.left)}",
                            )
                            continue
                        }
                        // Wait for focus to settle before accepting.
                        if (!FocusState.isStable) {
                            SLog.w(TAG, "VIN_REJECT_FOCUS frame=$frame box=$boxTag (focus not stable)")
                            continue
                        }

                        // #2 sharpness gate: prefer a sharp frame; hold soft ones briefly and accept
                        // the sharpest, with a time-bounded fallback so we never stall forever. The
                        // result image is the full scanned frame, contrast-boosted for clarity.
                        val vinValue = validation.correctedVin ?: candidate
                        val sharpness =
                            Sharpness.varianceOfLaplacian(
                                processedBitmap,
                                ScannerPerfConfig.sharpnessSampleMaxEdge,
                            )
                        val thr = ScannerPerfConfig.sharpnessThreshold

                        // Auto-scan source is the soft analysis stream → sharpen the result.
                        fun enhanced(): Bitmap? =
                            try {
                                ImagePreprocessor.enhanceForDisplay(processedBitmap, sharpen = true)
                            } catch (e: Exception) {
                                SLog.e(TAG, "Failed to prepare VIN frame bitmap", e)
                                null
                            }
                        when (acceptGate.onValidRead(sharpness, frameNowMs)) {
                            VinAcceptDecider.Action.ACCEPT_CURRENT -> {
                                SLog.w(
                                    TAG,
                                    "VIN_SHARPNESS frame=$frame box=$boxTag value=${"%.1f".format(sharpness)} thr=$thr -> ACCEPT_CURRENT",
                                )
                                acceptGate.reset()
                                bestVin = vinValue
                                bestConfidence = box.confidence
                                croppedVinBitmap = enhanced()
                                break
                            }

                            VinAcceptDecider.Action.ACCEPT_STASHED -> {
                                SLog.w(
                                    TAG,
                                    "VIN_SHARPNESS frame=$frame box=$boxTag value=${"%.1f".format(sharpness)} thr=$thr -> ACCEPT_STASHED",
                                )
                                bestVin = acceptGate.stashedVin ?: vinValue
                                bestConfidence = acceptGate.stashedConfidence
                                croppedVinBitmap = acceptGate.takeStashedBitmap() ?: enhanced()
                                acceptGate.reset()
                                break
                            }

                            VinAcceptDecider.Action.STASH_CURRENT -> {
                                SLog.w(
                                    TAG,
                                    "VIN_SHARPNESS frame=$frame box=$boxTag value=${"%.1f".format(
                                        sharpness,
                                    )} thr=$thr -> STASH (waiting for sharper)",
                                )
                                acceptGate.stash(vinValue, box.confidence, enhanced())
                                // No accept this frame; keep scanning subsequent frames.
                                break
                            }

                            VinAcceptDecider.Action.DISCARD_CURRENT -> {
                                SLog.w(
                                    TAG,
                                    "VIN_SHARPNESS frame=$frame box=$boxTag value=${"%.1f".format(
                                        sharpness,
                                    )} thr=$thr -> DISCARD (waiting for sharper)",
                                )
                                break
                            }
                        }
                    }
                }
                onCandidateScanned(
                    frameCandidate?.first,
                    frameCandidate?.second ?: 0f,
                    frameCandidate?.third ?: false,
                )
                if (sortedBoxes.isNotEmpty()) {
                    SLog.w(TAG, "VIN_RESULT frame=$frame result=${bestVin ?: "none"}")
                }

                stageTextNs = System.nanoTime() - textStartNs

                // If none found from boxes, fall back to ROI text lines and require validation

                /* Fallback disabled by user request - rely on AI detection only
                if (bestVin == null) {
                    SLog.d(TAG, "Falling back to ROI text lines for VIN candidate...")
                    for (text in allText) {
                        val cleanedText = vinValidator.cleanVin(text)
                        val validation = vinValidator.validate(cleanedText)
                        if (validation.isValid) {
                            bestVin = cleanedText
                            bestConfidence = 1.0f
                            // VIN found from text, AI model did not detect box location
                            SLog.d(TAG, "VIN found from text without AI detection box")
                            break
                        }
                    }
                }
                 */
            } finally {
                if (processedBitmap !== bitmap) {
                    try {
                        processedBitmap.recycle()
                    } catch (_: Throwable) {
                    }
                }
            }

            // If a VIN was found, report it
            val postStartNs = System.nanoTime()
            if (bestVin != null) {
                SLog.d(TAG, "VIN detected with confidence=$bestConfidence")
                onRoiBorderStateChange(RoiBorderState.VALID_VIN_DETECTED)
                onVinDetected(bestVin, bestConfidence, croppedVinBitmap)
            }
            stagePostNs = System.nanoTime() - postStartNs
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            SLog.e(TAG, "Error processing image", e)
        } finally {
            try {
                bitmap.recycle()
            } catch (_: Throwable) {
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        SLog.e(TAG, "Error converting image", e)
    } finally {
        val totalNs = System.nanoTime() - frameReceivedNs
        val stageSummary =
            "camera_to_bitmap_ms=${"%.2f".format(stageImageToBitmapNs / 1_000_000.0)} " +
                "roi_crop_ms=${"%.2f".format(stageRoiCropNs / 1_000_000.0)} " +
                "detect_ms=${"%.2f".format(stageDetectionNs / 1_000_000.0)} " +
                "ocr_ms=${"%.2f".format(stageTextNs / 1_000_000.0)} " +
                "post_ms=${"%.2f".format(stagePostNs / 1_000_000.0)}"
        ScannerPerfConfig.frameTiming.onFrameFinished(totalNs, stageSummary)
        imageProxy.close()
    }
}
