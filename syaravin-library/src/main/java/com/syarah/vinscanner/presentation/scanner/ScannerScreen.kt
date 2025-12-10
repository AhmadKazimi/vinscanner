package com.syarah.vinscanner.presentation.scanner

import android.Manifest
import android.content.Context
import android.util.Log
import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Logger.e
import androidx.camera.core.Preview
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.syarah.vinscanner.util.showToast
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService

private const val TAG = "ScannerScreen"

/**
 * Main scanner screen for VIN detection
 */
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ScannerScreen(
	onVinConfirmed: (VinNumber) -> Unit = {},
	onCancelled: () -> Unit = {}
) {
	Log.d(TAG, "ScannerScreen composable started.")

	// Create ViewModel with custom factory
	val viewModel: ScannerViewModel = viewModel(
		factory = ScannerViewModelFactory()
	)

	val state by viewModel.state.collectAsStateWithLifecycle()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()

	// Get dependencies via remember to avoid recreating on recomposition
	val dependencies = remember { VinScannerDependencies.get() }

	// Factory-created instances (per-screen lifecycle)
	val cameraSelector = remember { dependencies.createCameraSelector() }
	val preview = remember { dependencies.createPreview() }
	val imageAnalysis = remember { dependencies.createImageAnalysis() }
	val executor = remember { dependencies.createExecutor() }

	// Singletons - safe to access directly
	val cameraDataSource = dependencies.cameraDataSource
	val vinDetector = dependencies.vinDetector
	val textExtractor = dependencies.textExtractor
	val vinValidator = dependencies.vinValidator

	// Clean up executor on dispose
	DisposableEffect(Unit) {
		onDispose {
			Log.d(TAG, "Shutting down camera executor")
			executor.shutdown()
		}
	}

	// Camera permission
	val cameraPermissionState = rememberPermissionState(
		permission = Manifest.permission.CAMERA,
		onPermissionResult = { granted ->
			if (granted) {
				Log.d(TAG, "Camera permission granted.")
				viewModel.onEvent(ScannerEvent.PermissionGranted)
			} else {
				Log.w(TAG, "Camera permission denied.")
				viewModel.onEvent(ScannerEvent.PermissionDenied)
			}
		}
	)

	// Request permission on first launch
	LaunchedEffect(Unit) {
		Log.d(TAG, "LaunchedEffect for permission check.")
		if (!cameraPermissionState.status.isGranted) {
			Log.d(TAG, "Permission not granted, launching permission request.")
			cameraPermissionState.launchPermissionRequest()
		} else {
			Log.d(TAG, "Permission already granted.")
			viewModel.onEvent(ScannerEvent.PermissionGranted)
		}
	}

	var processing by remember { mutableStateOf(false) }
	var lastProcessTime by remember { mutableStateOf(0L) }

	// Set up image analysis
	DisposableEffect(state.isScanning) {
		if (state.isScanning) {
			Log.d(TAG, "Setting up image analyzer.")
			imageAnalysis.setAnalyzer(executor) { imageProxy ->
				val currentTime = System.currentTimeMillis()


				if (!processing && currentTime - lastProcessTime >= 500) {
					processing = true
					lastProcessTime = currentTime
					Log.d(TAG, "New image received for processing.")
					scope.launch(kotlinx.coroutines.Dispatchers.Default) {
						processImage(
							imageProxy = imageProxy,
							cameraDataSource = cameraDataSource,
							vinDetector = vinDetector,
							textExtractor = textExtractor,
							vinValidator = vinValidator,

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
						kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
							processing = false
						}
					}
				} else {
					if (processing) {
						Log.v(TAG, "Skipping image processing, already in progress.")
					} else {
						Log.v(TAG, "Skipping image processing, throttled.")
					}
					imageProxy.close()
				}
			}
		}

		onDispose {
			Log.d(TAG, "Disposing image analyzer.")
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
				imageAnalyzer = imageAnalysis
			)

			// ROI overlay to guide user with dynamic border color
			val roiBorderColor by animateColorAsState(
				targetValue = when (state.roiBorderState) {
					RoiBorderState.VALID_VIN_DETECTED -> RoiValidBorder
					RoiBorderState.NEUTRAL -> RoiValidBorder
					RoiBorderState.NO_DETECTION -> RoiInvalidBorder
				},
				animationSpec = tween(durationMillis = 250),
				label = "roi_border_color"
			)

			RoiOverlay(
				modifier = Modifier.fillMaxSize(),
				roiBox = RoiConfig.roi,
				borderColor = roiBorderColor
			)

			// Bounding box overlay
			BoundingBoxOverlay(
				modifier = Modifier.fillMaxSize(),
				boundingBoxes = state.detectionBoxes
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
					text = "Camera Permission Required",
					style = MaterialTheme.typography.headlineMedium,
					color = Color.White,
					textAlign = TextAlign.Center
				)

				Spacer(modifier = Modifier.height(16.dp))

				Text(
					text = "Please grant camera permission to scan VIN numbers",
					style = MaterialTheme.typography.bodyLarge,
					color = Color.Gray,
					textAlign = TextAlign.Center
				)

				Spacer(modifier = Modifier.height(32.dp))

				Button(
					onClick = { cameraPermissionState.launchPermissionRequest() }
				) {
					Text("Grant Permission")
				}
			}
		}

		// Top bar with controls
		if (state.hasPermission) {
			TopAppBar(
				modifier = Modifier.align(Alignment.TopCenter),
				title = { Text("VIN Scanner") },
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = Color.Black.copy(alpha = 0.5f),
					titleContentColor = Color.White
				),
				actions = {
					IconButton(
						onClick = {
							if (state.isScanning) {
								viewModel.onEvent(ScannerEvent.StopScanning)
							} else {
								viewModel.onEvent(ScannerEvent.StartScanning)
							}
						}
					) {
						Icon(
							imageVector = if (state.isScanning) {
								Icons.Filled.Info
							} else {
								Icons.Filled.PlayArrow
							},
							contentDescription = if (state.isScanning) "Stop" else "Start",
							tint = Color.White
						)
					}
				}
			)
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
						Log.d(TAG, "Enter manually button clicked")

						// Get latest ROI-cropped bitmap from state
						val roiBitmap = state.latestRoiCroppedBitmap

						if (roiBitmap != null) {
							Log.d(TAG, "Passing empty VIN with ROI bitmap (${roiBitmap.width}x${roiBitmap.height})")

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
							Log.w(TAG, "No ROI bitmap available, passing empty VIN without image")

							// Fallback: pass empty VIN without bitmap
							onVinConfirmed(VinNumber(value = "", confidence = 0f, isValid = false))
						}
					}
			)
		}

		// Error snackbar
		state.errorMessage?.let { error ->
			Snackbar(
				modifier = Modifier
					.align(Alignment.BottomCenter)
					.padding(16.dp),
				action = {
					TextButton(
						onClick = { viewModel.onEvent(ScannerEvent.DismissError) }
					) {
						Text("Dismiss")
					}
				}
			) {
				Text(error)
			}
		}
	}
}

private suspend fun processImage(
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
	Log.d(TAG, "Processing image...")
	try {
		// Convert ImageProxy to Bitmap
		val bitmap = cameraDataSource.imageToBitmap(imageProxy)
		Log.d(TAG, "Image converted to Bitmap with dimensions: ${bitmap.width}x${bitmap.height}")

		try {
			// Crop to ROI first to reduce noise and improve accuracy
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
					Log.d(TAG, "Cropping to ROI: [$leftPx,$topPx,$rightPx,$bottomPx] -> ${roiWidth}x${roiHeight}")
					val cropped = Bitmap.createBitmap(bitmap, leftPx, topPx, roiWidth, roiHeight)

					// Store a copy for manual entry (create new bitmap to prevent recycling issues)
					try {
						val roiCopy = cropped.copy(Bitmap.Config.ARGB_8888, false)
						kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
							onRoiBitmapCaptured(roiCopy)
						}
						Log.d(TAG, "Stored ROI bitmap copy for manual entry: ${roiCopy.width}x${roiCopy.height}")
					} catch (e: Exception) {
						Log.e(TAG, "Failed to create ROI bitmap copy", e)
					}

					cropped
				} else bitmap
			} catch (e: Exception) {
				Log.e(TAG, "Failed to crop to ROI, falling back to full image", e)
				bitmap
			}

			var allText: List<String> = emptyList()
			var bestVin: String? = null
			var bestConfidence = 0f
			var croppedVinBitmap: Bitmap? = null

			try {
				// Run object detection to get bounding boxes on ROI image
				Log.d(TAG, "Detecting VIN boxes...")
				val detectionResult = vinDetector.detect(processedBitmap)
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
				Log.d(TAG, "Detected ${boxes.size} VIN boxes (mapped: ${mappedBoxes.size}).")

				// Update ROI border state based on detection
				if (mappedBoxes.isEmpty()) {
					onRoiBorderStateChange(RoiBorderState.NO_DETECTION)
				} else {
					onRoiBorderStateChange(RoiBorderState.NEUTRAL)
				}

				// Try OCR inside each detected box first (sorted by confidence)
				for (box in boxes.sortedByDescending { it.confidence }) {
					val textInBox = textExtractor.extractText(processedBitmap, box)
					if (!textInBox.isNullOrBlank()) {
						val candidate = vinValidator.cleanVin(textInBox)
						val validation = vinValidator.validate(candidate)
						if (validation.isValid) {
							bestVin = candidate
							bestConfidence = box.confidence
							Log.d(TAG, "Valid VIN from box: $bestVin (conf=${box.confidence})")

							// Crop and enhance the bitmap using the AI detection box
							try {
								croppedVinBitmap = ImagePreprocessor.cropAndEnhance(
									processedBitmap,
									box.left,
									box.top,
									box.right,
									box.bottom,
									paddingPercent = 0.15f
								)
								Log.d(TAG, "Cropped and enhanced VIN from AI detection: ${croppedVinBitmap?.width}x${croppedVinBitmap?.height}")
							} catch (e: Exception) {
								Log.e(TAG, "Failed to crop and enhance VIN bitmap", e)
							}
							break
						}
					}
				}

				// Extract all text from the ROI image
				Log.d(TAG, "Extracting all text from ROI image...")
				allText = textExtractor.extractAllText(processedBitmap)
				Log.d(TAG, "Extracted text: $allText")

				// If none found from boxes, fall back to ROI text lines and require validation
				/* Fallback disabled by user request - rely on AI detection only
				if (bestVin == null) {
					Log.d(TAG, "Falling back to ROI text lines for VIN candidate...")
					for (text in allText) {
						val cleanedText = vinValidator.cleanVin(text)
						val validation = vinValidator.validate(cleanedText)
						if (validation.isValid) {
							bestVin = cleanedText
							bestConfidence = 1.0f
							// VIN found from text, AI model did not detect box location
							Log.d(TAG, "VIN found from text without AI detection box")
							break
						}
					}
				}
				*/
			} finally {
				if (processedBitmap !== bitmap) {
					try { processedBitmap.recycle() } catch (_: Throwable) {}
				}
			}


			// If a VIN was found, report it
			if (bestVin != null) {
				Log.i(TAG, "VIN detected: $bestVin with confidence $bestConfidence")
				onRoiBorderStateChange(RoiBorderState.VALID_VIN_DETECTED)
				onVinDetected(bestVin, bestConfidence, croppedVinBitmap)
			} else {
				Log.d(TAG, "No valid VIN found in the extracted text.")
			}

		} catch (e: Exception) {
			Log.e(TAG, "Error processing image", e)
		} finally {
			try { bitmap.recycle() } catch (_: Throwable) {}
		}
	} catch (e: Exception) {
		Log.e(TAG, "Error converting image", e)
	} finally {
		imageProxy.close()
	}
}

