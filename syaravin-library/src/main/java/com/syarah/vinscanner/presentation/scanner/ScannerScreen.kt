package com.syarah.vinscanner.presentation.scanner

import com.syarah.vinscanner.util.LogTags

import android.Manifest
import com.syarah.vinscanner.util.SLog
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.syarah.vinscanner.R
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.syarah.vinscanner.data.datasource.camera.CameraDataSource
import com.syarah.vinscanner.data.datasource.ml.TextExtractor
import com.syarah.vinscanner.data.datasource.ml.VinDetector
import com.syarah.vinscanner.data.datasource.validator.VinValidator
import com.syarah.vinscanner.di.VinScannerDependencies
import com.syarah.vinscanner.domain.model.VinNumber
import com.syarah.vinscanner.presentation.components.BoundingBoxOverlay
import com.syarah.vinscanner.presentation.components.CameraPreview
import com.syarah.vinscanner.presentation.components.CaptureButton
import com.syarah.vinscanner.presentation.components.RoiOverlay
import com.syarah.vinscanner.ui.theme.RoiDetectedBorder
import com.syarah.vinscanner.ui.theme.RoiInvalidBorder
import com.syarah.vinscanner.ui.theme.RoiValidBorder
import com.syarah.vinscanner.util.ImagePreprocessor
import com.syarah.vinscanner.util.FocusState
import com.syarah.vinscanner.util.RoiConfig
import com.syarah.vinscanner.util.ScanFeedback
import com.syarah.vinscanner.util.ScannerPerfConfig
import com.syarah.vinscanner.util.ThermalManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

private const val TAG = LogTags.LIBRARY

// Auto-accept geometry gates for the detected VIN box (normalized 0..1 within the analyzed frame).
// The analyzed frame IS the tight ROI band, so a well-placed VIN fills its width (left~0,
// right~1) — we therefore only gate vertically (not clipped top/bottom, roughly vertically
// centered) and require the box to fill a decent fraction of the width (camera close enough).
private const val VIN_BOX_VERTICAL_MARGIN = 0.01f      // box top/bottom must sit this far inside
private const val VIN_BOX_MAX_CENTER_OFFSET_Y = 0.35f  // box vertical center within this of mid
private const val VIN_BOX_MIN_WIDTH = 0.55f            // box at least this wide => camera close enough

private fun isVinBoxWellPositioned(box: com.syarah.vinscanner.domain.model.BoundingBox): Boolean {
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
    onVinConfirmed: (VinNumber) -> Unit = {}, onCancelled: () -> Unit = {}
) {
    val screenStartMs = remember { SystemClock.elapsedRealtime() }
    LaunchedEffect(Unit) {
        SLog.w(TAG, "ScannerScreen first composition reached after ${SystemClock.elapsedRealtime() - screenStartMs}ms")
    }

    // Create ViewModel with custom factory
    val viewModel: ScannerViewModel = viewModel(
        factory = ScannerViewModelFactory()
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

    // Clean up executor on dispose
    val processingScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    DisposableEffect(Unit) {
        onDispose {
            SLog.d(TAG, "Shutting down camera executor")
            imageAnalysis.clearAnalyzer()
            processingScope.cancel()
            executor.shutdownNow()
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                SLog.w(TAG, "Camera executor did not terminate within timeout")
            }
        }
    }

    // Camera permission
    val cameraPermissionState = rememberPermissionState(
        permission = Manifest.permission.CAMERA, onPermissionResult = { granted ->
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

    // Set up image analysis
    DisposableEffect(state.isScanning, isWarmupComplete, thermalStatus) {
        if (state.isScanning && isWarmupComplete) {
            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                val currentTime = System.currentTimeMillis()
                val previousTime = lastProcessTime.get()

                if (!isManualCaptureRequested.get() &&
                    currentTime - previousTime >= ThermalManager.inferenceIntervalMs(
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
                                    onCandidateScanned = viewModel::onCandidateScanned
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
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (state.hasPermission && state.isScanning) {
            // Camera preview
            CameraPreview(
                modifier = Modifier
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
            val roiBorderTarget = when (state.roiBorderState) {
                RoiBorderState.VALID_VIN_DETECTED -> RoiValidBorder
                RoiBorderState.NEUTRAL -> RoiDetectedBorder
                RoiBorderState.NO_DETECTION -> RoiInvalidBorder
            }

            RoiOverlay(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(9f / 16f)
                    .align(Alignment.Center),
                roiBox = RoiConfig.roi,
                borderColor = roiBorderTarget
            )

            // Top banner: while warming up show the "preparing" notice; afterwards show the
            // guidance (keep VIN inside the box, centered, and close).
            Text(
                text = stringResource(
                    if (isWarmupComplete) R.string.scanner_guidance else R.string.scanner_preparing
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 140.dp, start = 24.dp, end = 24.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )

            // Bounding box overlay
            BoundingBoxOverlay(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(9f / 16f)
                    .align(Alignment.Center),
                boundingBoxes = state.detectionBoxes
            )

        } else if (!state.hasPermission) {
            // Permission denied message
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.camera_permission_required_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.camera_permission_required_message),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = { cameraPermissionState.launchPermissionRequest() }

                ) {
                    Text(stringResource(R.string.grant_permission))
                }
            }
        }

        // Top bar with controls
        if (state.hasPermission) {
            TopAppBar(
                modifier = Modifier.align(Alignment.TopCenter),
                title = { Text(stringResource(R.string.vin_scanner_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.5f), titleContentColor = Color.White
                ),
                actions = {
                    if (hasFlashUnit) {
                        IconButton(
                            onClick = { torchEnabled = !torchEnabled },
                            modifier = Modifier.semantics {
                                contentDescription = "vin_scanner_toggle_torch"
                            },
                        ) {
                            Icon(
                                imageVector = if (torchEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                                contentDescription = stringResource(
                                    if (torchEnabled) R.string.torch_off else R.string.torch_on
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
                        modifier = Modifier.semantics {
                            contentDescription = "vin_scanner_toggle_scanning"
                        },
                    ) {
                        Icon(
                            imageVector = if (state.isScanning) {
                                Icons.Filled.Stop
                            } else {
                                Icons.Filled.PlayArrow
                            },
                            contentDescription = if (state.isScanning) stringResource(R.string.stop) else stringResource(R.string.start),
                            tint = Color.White
                        )
                    }
                })
        }

        // Enter manually button at bottom (camera shutter style)
        if (state.hasPermission && state.isScanning) {
            // Lock the capture button once a manual capture is running or a VIN was auto-detected,
            // so the user can't double-trigger while the result is being prepared (~0.5s).
            val captureLocked = isManualCaptureBusy || state.detectedVin != null
            CaptureButton(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
                enabled = !captureLocked,
                capturing = captureLocked,
                onTap = {
                    if (!isManualCaptureRequested.compareAndSet(false, true)) return@CaptureButton
                    isManualCaptureBusy = true
                    val fallbackRoiBitmap = state.latestRoiCroppedBitmap
                        ?.takeUnless(Bitmap::isRecycled)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    SLog.d(TAG, "Enter manually button clicked")
                    processingScope.launch {
                        try {
                            val result = analysisMutex.withLock {
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
                modifier = Modifier
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
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp), action = {
                    TextButton(
                        onClick = { viewModel.onEvent(ScannerEvent.DismissError) },
                        modifier = Modifier.semantics {
                            contentDescription = "vin_scanner_dismiss_error"
                        },
                    ) {
                        Text(stringResource(R.string.dismiss))
                    }
                }) {
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
        modifier = Modifier
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
    val capturedBitmap = try {
        captureStillBitmap(imageCapture, captureExecutor)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        SLog.w(TAG, "Still capture failed; analyzing latest ROI frame", e)
        null
    }

    val analysisBitmap = if (capturedBitmap != null) {
        val roi = RoiConfig.roi
        try {
            Bitmap.createBitmap(
                capturedBitmap,
                (roi.left * capturedBitmap.width).toInt().coerceIn(0, capturedBitmap.width - 1),
                (roi.top * capturedBitmap.height).toInt().coerceIn(0, capturedBitmap.height - 1),
                ((roi.right - roi.left) * capturedBitmap.width).toInt().coerceAtLeast(1),
                ((roi.bottom - roi.top) * capturedBitmap.height).toInt().coerceAtLeast(1),
            )
        } finally {
            capturedBitmap.recycle()
            fallbackRoiBitmap?.recycle()
        }
    } else {
        fallbackRoiBitmap
    } ?: return VinNumber(value = "", confidence = 0f, isValid = false)

    try {
        val boxes = vinDetector.detect(analysisBitmap).boundingBoxes
            .sortedByDescending { it.confidence }
            .take(3)
        val extractedCandidates = coroutineScope {
            boxes.map { box ->
                async { box to textExtractor.extractText(analysisBitmap, box) }
            }.awaitAll()
        }

        for ((box, rawText) in extractedCandidates) {
            if (rawText.isNullOrBlank()) continue
            val candidate = vinValidator.cleanVin(rawText)
            val validation = vinValidator.validate(candidate)
            if (!validation.isValid) continue

            // Keep the full captured frame as the result image (no VIN-box crop),
            // sharpened with boosted contrast for clarity.
            return VinNumber(
                value = validation.correctedVin ?: candidate,
                confidence = box.confidence,
                isValid = true,
                croppedImage = ImagePreprocessor.enhanceForDisplay(analysisBitmap),
            )
        }

        return VinNumber(
            value = "",
            confidence = 0f,
            isValid = false,
            croppedImage = ImagePreprocessor.enhanceForDisplay(analysisBitmap),
        )
    } finally {
        if (!analysisBitmap.isRecycled) analysisBitmap.recycle()
    }
}

private suspend fun captureStillBitmap(
    imageCapture: ImageCapture,
    executor: ExecutorService,
): Bitmap = suspendCancellableCoroutine { cont ->
    imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
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
    })
}

private fun imageProxyJpegToBitmap(image: ImageProxy): Bitmap {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = captureDecodeSampleSize(bounds.outWidth, bounds.outHeight)
    }
    val decodedSource = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        ?: throw IllegalStateException("Failed to decode captured JPEG")
    val decoded = boundCapturedBitmap(decodedSource)
    val rotation = image.imageInfo.rotationDegrees
    if (rotation == 0) return decoded
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (rotated !== decoded && !decoded.isRecycled) decoded.recycle()
    return rotated
}

internal fun captureDecodeSampleSize(width: Int, height: Int): Int {
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
    val scale = minOf(
        1f,
        1920f / maxOf(bitmap.width, bitmap.height),
        1080f / minOf(bitmap.width, bitmap.height),
    )
    if (scale >= 1f) return bitmap
    return Bitmap.createScaledBitmap(
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
    onVinDetected: (String, Float, Bitmap?) -> Unit,
    onBoxesDetected: (List<com.syarah.vinscanner.domain.model.BoundingBox>) -> Unit,
    onRoiBorderStateChange: (RoiBorderState) -> Unit,
    onRoiBitmapCaptured: (Bitmap) -> Unit,
    onCandidateScanned: (String?, Float, Boolean) -> Unit
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
                val mappedBoxes = if (shouldCrop) {
                    val roiWidthNorm = roi.right - roi.left
                    val roiHeightNorm = roi.bottom - roi.top
                    boxes.map { box ->
                        com.syarah.vinscanner.domain.model.BoundingBox(
                            left = roi.left + box.left * roiWidthNorm,
                            top = roi.top + box.top * roiHeightNorm,
                            right = roi.left + box.right * roiWidthNorm,
                            bottom = roi.top + box.bottom * roiHeightNorm,
                            confidence = box.confidence
                        )
                    }
                } else boxes
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
                    SLog.w(TAG, "VIN_SCAN frame=$frame boxes=${sortedBoxes.size} bitmap=${processedBitmap.width}x${processedBitmap.height}")
                }
                for ((boxIdx, box) in sortedBoxes.withIndex()) {
                    val boxTag = "${boxIdx + 1}/${sortedBoxes.size}"
                    val boxCoords = "L=${"%.3f".format(box.left)} T=${"%.3f".format(box.top)} R=${"%.3f".format(box.right)} B=${"%.3f".format(box.bottom)}"
                    val textInBox = textExtractor.extractText(processedBitmap, box)
                    if (textInBox.isNullOrBlank()) {
                        SLog.w(TAG, "VIN_CANDIDATE frame=$frame box=$boxTag conf=${"%.3f".format(box.confidence)} $boxCoords ocr=null")
                        continue
                    }
                    val candidate = vinValidator.cleanVin(textInBox)
                    val validation = vinValidator.validate(candidate)
                    val outcome = if (validation.isValid) "ACCEPTED" else "REJECTED"
                    val reason = validation.errorMessage ?: if (validation.checksumValid) "checksum_ok" else "soft_accept"
                    SLog.w(TAG, "VIN_CANDIDATE frame=$frame box=$boxTag conf=${"%.3f".format(box.confidence)} $boxCoords ocr=\"${textInBox.take(40)}\" clean=\"$candidate\" $outcome reason=\"$reason\"")
                    if (frameCandidate == null && candidate.length >= 11) {
                        frameCandidate = Triple(validation.correctedVin ?: candidate, box.confidence, validation.isValid)
                    }
                    if (validation.isValid) {
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
                        bestVin = validation.correctedVin ?: candidate
                        bestConfidence = box.confidence

                        // Keep the full scanned analysis frame as the result image (no VIN-box
                        // crop), sharpened with boosted contrast for clarity.
                        croppedVinBitmap = try {
                            ImagePreprocessor.enhanceForDisplay(processedBitmap)
                        } catch (e: Exception) {
                            SLog.e(TAG, "Failed to prepare VIN frame bitmap", e)
                            null
                        }
                        break
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
