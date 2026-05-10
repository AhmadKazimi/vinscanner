package com.syarah.vinscanner.presentation.scanner

import com.syarah.vinscanner.util.LogTags

import android.Manifest
import com.syarah.vinscanner.util.SLog
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
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
import com.syarah.vinscanner.presentation.components.RoiOverlay
import com.syarah.vinscanner.ui.theme.RoiInvalidBorder
import com.syarah.vinscanner.ui.theme.RoiNeutralBorder
import com.syarah.vinscanner.ui.theme.RoiValidBorder
import com.syarah.vinscanner.util.ImagePreprocessor
import com.syarah.vinscanner.util.RoiConfig
import com.syarah.vinscanner.util.ScannerPerfConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = LogTags.LIBRARY

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
    LocalContext.current

    // Get dependencies via remember to avoid recreating on recomposition
    val dependencies = remember { VinScannerDependencies.get() }
    var isWarmupComplete by remember { mutableStateOf(false) }

    // Factory-created instances (per-screen lifecycle)
    val cameraSelector = remember { dependencies.createCameraSelector() }
    val preview = remember { dependencies.createPreview() }
    val imageAnalysis = remember { dependencies.createImageAnalysis() }
    val executor = remember { dependencies.createExecutor() }

    // Defer heavy singleton creation until first frame processing on background thread.
    val cameraDataSourceLazy = remember { lazy { dependencies.cameraDataSource } }
    val vinDetectorLazy = remember { lazy { dependencies.vinDetector } }
    val textExtractorLazy = remember { lazy { dependencies.textExtractor } }
    val vinValidatorLazy = remember { lazy { dependencies.vinValidator } }

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
    val lastProcessTime = remember { AtomicLong(0L) }

    // Set up image analysis
    DisposableEffect(state.isScanning, isWarmupComplete) {
        if (state.isScanning && isWarmupComplete) {
            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                val currentTime = System.currentTimeMillis()
                val previousTime = lastProcessTime.get()

                if (currentTime - previousTime >= ScannerPerfConfig.inferenceIntervalMs &&
                    isProcessingFrame.compareAndSet(false, true)
                ) {
                    lastProcessTime.set(currentTime)
                    val frameReceivedNs = System.nanoTime()
                    processingScope.launch {
                        try {
                            processImage(
                                frameReceivedNs = frameReceivedNs,
                                imageProxy = imageProxy,
                                cameraDataSource = cameraDataSourceLazy.value,
                                vinDetector = vinDetectorLazy.value,
                                textExtractor = textExtractorLazy.value,
                                vinValidator = vinValidatorLazy.value,
                                onVinDetected = { vin, confidence, croppedBitmap ->
                                    viewModel.onVinDetected(vin, confidence, croppedBitmap)
                                },
                                onBoxesDetected = { boxes ->
                                    viewModel.onDetectionBoxesUpdated(boxes)
                                },
                                onRoiBorderStateChange = { state ->
                                    viewModel.onEvent(ScannerEvent.UpdateRoiBorderState(state))
                                },
                                onRoiBitmapCaptured = { roiBitmap ->
                                    viewModel.onRoiCroppedBitmapUpdated(roiBitmap)
                                }
                            )
                        } catch (cancelled: CancellationException) {
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
            // Invoke callback immediately when VIN is detected
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
                modifier = Modifier.fillMaxSize(),
                cameraSelector = cameraSelector,
                preview = preview,
                imageAnalyzer = imageAnalysis,
            )

            if (!isWarmupComplete) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.scanner_preparing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // ROI overlay to guide user with dynamic border color
            val roiBorderColor by animateColorAsState(
                targetValue = when (state.roiBorderState) {
                    RoiBorderState.VALID_VIN_DETECTED -> RoiValidBorder
                    RoiBorderState.NEUTRAL -> RoiValidBorder
                    RoiBorderState.NO_DETECTION -> RoiInvalidBorder
                }, animationSpec = tween(durationMillis = 250), label = "roi_border_color"
            )

            RoiOverlay(
                modifier = Modifier.fillMaxSize(),
                roiBox = RoiConfig.roi,
                borderColor = roiBorderColor
            )

            // Bounding box overlay
            BoundingBoxOverlay(
                modifier = Modifier.fillMaxSize(), boundingBoxes = state.detectionBoxes
            )

            // Scanning indicator
            if (state.isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary
                )
            }
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
                                Icons.Filled.Info
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
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable {
                        SLog.d(TAG, "Enter manually button clicked")

                        // Get latest ROI-cropped bitmap from state
                        val roiBitmap = state.latestRoiCroppedBitmap

                        if (roiBitmap != null) {
                            SLog.d(
                                TAG,
                                "Passing empty VIN with ROI bitmap (${roiBitmap.width}x${roiBitmap.height})"
                            )

                            // Create VinNumber with empty string and ROI bitmap
                            val manualEntryVin = VinNumber(
                                value = "",
                                confidence = 0f,
                                isValid = false,
                                croppedImage = roiBitmap
                            )

                            // Invoke callback with bitmap
                            onVinConfirmed(manualEntryVin)
                        } else {
                            SLog.w(TAG, "No ROI bitmap available, passing empty VIN without image")

                            // Fallback: pass empty VIN without bitmap
                            onVinConfirmed(VinNumber(value = "", confidence = 0f, isValid = false))
                        }
                    }
                    .semantics {
                        contentDescription = "vin_scanner_capture_button"
                    },
            )
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

private suspend fun processImage(
    frameReceivedNs: Long,
    imageProxy: ImageProxy,
    cameraDataSource: CameraDataSource,
    vinDetector: VinDetector,
    textExtractor: TextExtractor,
    vinValidator: VinValidator,
    onVinDetected: (String, Float, Bitmap?) -> Unit,
    onBoxesDetected: (List<com.syarah.vinscanner.domain.model.BoundingBox>) -> Unit,
    onRoiBorderStateChange: (RoiBorderState) -> Unit,
    onRoiBitmapCaptured: (Bitmap) -> Unit
) {
    var stageImageToBitmapNs = 0L
    var stageRoiCropNs = 0L
    var stageDetectionNs = 0L
    var stageTextNs = 0L
    var stagePostNs = 0L
    try {
        // Convert ImageProxy to Bitmap
        val imageToBitmapStartNs = System.nanoTime()
        val bitmap = cameraDataSource.imageToBitmap(imageProxy)
        stageImageToBitmapNs = System.nanoTime() - imageToBitmapStartNs

        try {
            // Crop to ROI first to reduce noise and improve accuracy
            val roiCropStartNs = System.nanoTime()
            val roi = RoiConfig.roi
            val leftPx = (roi.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val topPx = (roi.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            val rightPx = (roi.right * bitmap.width).toInt().coerceIn(leftPx + 1, bitmap.width)
            val bottomPx = (roi.bottom * bitmap.height).toInt().coerceIn(topPx + 1, bitmap.height)
            val roiWidth = rightPx - leftPx
            val roiHeight = bottomPx - topPx
            val shouldCrop = roiWidth > 0 && roiHeight > 0

            val processedBitmap: Bitmap = try {
                if (shouldCrop) {
                    val cropped = Bitmap.createBitmap(bitmap, leftPx, topPx, roiWidth, roiHeight)

                    // Store a copy for manual entry (create new bitmap to prevent recycling issues)
                    try {
                        val roiCopy = cropped.copy(Bitmap.Config.ARGB_8888, false)
                        val safeRoiCopy = ImagePreprocessor.downscaleForDisplay(roiCopy)
                        if (safeRoiCopy !== roiCopy && !roiCopy.isRecycled) {
                            roiCopy.recycle()
                        }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onRoiBitmapCaptured(safeRoiCopy)
                        }
                    } catch (e: Exception) {
                        SLog.e(TAG, "Failed to create ROI bitmap copy", e)
                    }

                    cropped
                } else bitmap
            } catch (e: Exception) {
                SLog.e(TAG, "Failed to crop to ROI, falling back to full image", e)
                bitmap
            }
            stageRoiCropNs = System.nanoTime() - roiCropStartNs

            var allText: List<String> = emptyList()
            var bestVin: String? = null
            var bestConfidence = 0f
            var croppedVinBitmap: Bitmap? = null

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
                for (box in boxes.sortedByDescending { it.confidence }) {
                    val textInBox = textExtractor.extractText(processedBitmap, box)
                    if (!textInBox.isNullOrBlank()) {
                        val candidate = vinValidator.cleanVin(textInBox)
                        val validation = vinValidator.validate(candidate)
                        if (validation.isValid) {
                            bestVin = candidate
                            bestConfidence = box.confidence
                            SLog.d(TAG, "VIN detected from box with confidence=${box.confidence}")

                            // Crop and enhance the bitmap using the AI detection box
                            try {
                                val enhancedVinBitmap = ImagePreprocessor.cropAndEnhance(
                                    processedBitmap,
                                    box.left,
                                    box.top,
                                    box.right,
                                    box.bottom,
                                    paddingPercent = 0.15f
                                )
                                croppedVinBitmap = enhancedVinBitmap?.let { rawBitmap ->
                                    val safeBitmap = ImagePreprocessor.downscaleForDisplay(rawBitmap)
                                    if (safeBitmap !== rawBitmap && !rawBitmap.isRecycled) {
                                        rawBitmap.recycle()
                                    }
                                    safeBitmap
                                }
                            } catch (e: Exception) {
                                SLog.e(TAG, "Failed to crop and enhance VIN bitmap", e)
                            }
                            break
                        }
                    }
                }

                // Extract all text from the ROI image
                allText = textExtractor.extractAllText(processedBitmap)
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
