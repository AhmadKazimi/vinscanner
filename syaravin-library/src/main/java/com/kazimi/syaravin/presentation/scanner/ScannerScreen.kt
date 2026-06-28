@file:Suppress("ktlint:standard:function-naming")

package com.kazimi.syaravin.presentation.scanner

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.Camera
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.kazimi.syaravin.di.VinScannerDependencies
import com.kazimi.syaravin.domain.model.VinNumber
import com.kazimi.syaravin.presentation.components.BoundingBoxOverlay
import com.kazimi.syaravin.presentation.components.CameraPreview
import com.kazimi.syaravin.presentation.components.CaptureButton
import com.kazimi.syaravin.presentation.components.RoiOverlay
import com.kazimi.syaravin.ui.theme.RoiDetectedBorder
import com.kazimi.syaravin.ui.theme.RoiInvalidBorder
import com.kazimi.syaravin.ui.theme.RoiValidBorder
import com.kazimi.syaravin.util.LogTags
import com.kazimi.syaravin.util.RoiConfig
import com.kazimi.syaravin.util.SLog
import com.kazimi.syaravin.util.ScanFeedback
import com.kazimi.syaravin.util.ScannerPerfConfig
import com.kazimi.syaravin.util.ThermalManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = LogTags.LIBRARY

private const val PREF_AUTO_SCAN_ENABLED = "auto_scan_enabled"

// Delay before the manual capture button appears while auto-scan is enabled.
private const val CAPTURE_BUTTON_AUTO_SCAN_DELAY_MS = 800L

/**
 * Main scanner screen for VIN detection. Hosts camera/analysis lifecycle and state; the visual
 * pieces live in [ScannerComponents], frame analysis in [processImage], and the manual-capture
 * pipeline in [analyzeManualCapture].
 */
@OptIn(ExperimentalPermissionsApi::class)
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

    // Manual-capture ambiguous-VIN choices awaiting user selection. While set, the selection dialog
    // is shown and auto-scan results are suppressed (choicesOpen) so they can't close it.
    var manualChoices by remember { mutableStateOf<ManualCaptureResult?>(null) }
    val choicesOpen = remember { AtomicBoolean(false) }

    // Auto-scan toggle, persisted across sessions. When off, frames are not analyzed continuously;
    // scanning happens only on manual capture.
    val scannerPrefs =
        remember(context) {
            context.getSharedPreferences("syaravin_scanner_prefs", Context.MODE_PRIVATE)
        }
    var autoScanEnabled by remember {
        mutableStateOf(scannerPrefs.getBoolean(PREF_AUTO_SCAN_ENABLED, true))
    }

    // When auto-scan is on, hold the manual capture button back briefly so auto-detection gets a
    // chance first; show it immediately when auto-scan is off.
    var showCaptureButton by remember { mutableStateOf(!autoScanEnabled) }
    LaunchedEffect(autoScanEnabled) {
        if (autoScanEnabled) {
            showCaptureButton = false
            delay(CAPTURE_BUTTON_AUTO_SCAN_DELAY_MS)
            showCaptureButton = true
        } else {
            showCaptureButton = true
        }
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
                                        if (isManualCaptureRequested.get() || choicesOpen.get()) {
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
                                    onScanGuidance = viewModel::onScanGuidanceChanged,
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
                        .aspectRatio(RoiConfig.analyzedImageAspectRatio)
                        .align(Alignment.Center),
                roiBox = RoiConfig.roi,
                borderColor = roiBorderTarget,
            )

            // Top banner: warming up takes precedence over the per-frame guidance.
            val activeGuidance =
                if (!isWarmupComplete) ScanGuidance.PREPARING else state.scanGuidance
            ScannerGuidanceBanner(activeGuidance)

            // Bounding box overlay — only meaningful for the custom detector; hidden in Google-OCR
            // mode (where boxes are per-line OCR bounds, not VIN regions).
            if (!ScannerPerfConfig.USE_GOOGLE_OCR_ONLY) {
                BoundingBoxOverlay(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(RoiConfig.analyzedImageAspectRatio)
                            .align(Alignment.Center),
                    boundingBoxes = state.detectionBoxes,
                )
            }
        } else if (!state.hasPermission) {
            PermissionDeniedContent(onGrantPermission = { cameraPermissionState.launchPermissionRequest() })
        }

        // Top bar with controls
        if (state.hasPermission) {
            ScannerTopBar(
                isScanning = state.isScanning,
                autoScanEnabled = autoScanEnabled,
                hasFlashUnit = hasFlashUnit,
                torchEnabled = torchEnabled,
                onClose = onCancelled,
                onToggleAutoScan = {
                    val enabled = !autoScanEnabled
                    autoScanEnabled = enabled
                    scannerPrefs.edit().putBoolean(PREF_AUTO_SCAN_ENABLED, enabled).apply()
                },
                onToggleTorch = { torchEnabled = !torchEnabled },
                onToggleScanning = {
                    if (state.isScanning) {
                        viewModel.onEvent(ScannerEvent.StopScanning)
                    } else {
                        viewModel.onEvent(ScannerEvent.StartScanning)
                    }
                },
            )
        }

        // Enter manually button at bottom (camera shutter style)
        if (state.hasPermission && state.isScanning) {
            // Lock the capture button once a manual capture is running or a VIN was auto-detected,
            // so the user can't double-trigger while the result is being prepared (~0.5s).
            val captureLocked = isManualCaptureBusy || state.detectedVin != null || manualChoices != null
            if (showCaptureButton) {
                CaptureButton(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
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
                                withContext(Dispatchers.Main) {
                                    val vins = result.candidates
                                    when {
                                        // Several checksum-valid VINs from ambiguous chars → let the user pick.
                                        vins.size > 1 -> {
                                            choicesOpen.set(true)
                                            manualChoices = result
                                        }

                                        // One candidate (exact, single valid, or best-effort) → confirm + close.
                                        else -> {
                                            onVinConfirmed(
                                                VinNumber(
                                                    value = vins.firstOrNull() ?: "",
                                                    confidence = result.confidence,
                                                    isValid = result.areChecksumValid,
                                                    croppedImage = result.image,
                                                ),
                                            )
                                        }
                                    }
                                }
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
            }

            // Live "possible VIN" feedback, shown just above the capture button.
            LiveCandidate(state.scannedCandidate)
        }

        // Error snackbar
        state.errorMessage?.let { error ->
            ScannerErrorSnackbar(message = error, onDismiss = { viewModel.onEvent(ScannerEvent.DismissError) })
        }

        // Ambiguous-VIN selection (manual capture). Common characters render normal; the characters
        // that differ between candidates are highlighted so the user can spot what to verify.
        manualChoices?.let { choices ->
            VinSelectionDialog(
                candidates = choices.candidates,
                onSelect = { selected ->
                    choicesOpen.set(false)
                    manualChoices = null
                    onVinConfirmed(
                        VinNumber(
                            value = selected,
                            confidence = choices.confidence,
                            isValid = true,
                            croppedImage = choices.image,
                        ),
                    )
                },
                onDismiss = {
                    choices.image?.takeUnless(Bitmap::isRecycled)?.recycle()
                    choicesOpen.set(false)
                    manualChoices = null
                },
            )
        }
    }
}
