package com.kazimi.syaravin.presentation.scanner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Logger.e
import androidx.camera.core.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.kazimi.syaravin.data.datasource.camera.CameraDataSource
import com.kazimi.syaravin.data.datasource.ml.TextExtractor
import com.kazimi.syaravin.data.datasource.ml.VinDetector
import com.kazimi.syaravin.data.datasource.validator.VinValidator
import com.kazimi.syaravin.presentation.components.BoundingBoxOverlay
import com.kazimi.syaravin.presentation.components.CameraPreview
import com.kazimi.syaravin.presentation.components.VinResultSheetContent
import com.kazimi.syaravin.util.showToast
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.util.concurrent.ExecutorService

private const val TAG = "ScannerScreen"

/**
 * Main scanner screen for VIN detection
 */
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
	viewModel: ScannerViewModel = koinViewModel()
) {
	Log.d(TAG, "ScannerScreen composable started.")
	val state by viewModel.state.collectAsStateWithLifecycle()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()

	// Inject dependencies
	val cameraSelector: CameraSelector = koinInject()
	val preview: Preview = koinInject()
	val imageAnalysis: ImageAnalysis = koinInject()
	val executor: ExecutorService = koinInject()
	val cameraDataSource: CameraDataSource = koinInject()
	val vinDetector: VinDetector = koinInject()
	val textExtractor: TextExtractor = koinInject()
	val vinValidator: VinValidator = koinInject()

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
							onVinDetected = { vin, confidence ->
								viewModel.onVinDetected(vin, confidence)
							},
							onBoxesDetected = { boxes ->
								viewModel.onDetectionBoxesUpdated(boxes)
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

	val sheetState = rememberModalBottomSheetState(
		skipPartiallyExpanded = true
	)

	if (state.showVinResult && state.detectedVin != null) {
		ModalBottomSheet(
			onDismissRequest = { viewModel.onEvent(ScannerEvent.DismissResult) },
			sheetState = sheetState,
			containerColor = Color.Transparent,
			contentColor = MaterialTheme.colorScheme.onSurface,
			dragHandle = null,
		) {
			state.detectedVin?.let {
				VinResultSheetContent(
					vinNumber = it,
					onCopy = { vin ->
						copyToClipboard(context, vin)
						context.showToast("VIN copied to clipboard")
					},
					onRetry = {
						viewModel.onEvent(ScannerEvent.DismissResult)
						viewModel.onEvent(ScannerEvent.StartScanning)
					},
					onVinChanged = { newVin ->
						viewModel.onEvent(ScannerEvent.UpdateVin(newVin))
					}
				)
			}
		}
	}

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
	onVinDetected: (String, Float) -> Unit,
	onBoxesDetected: (List<com.kazimi.syaravin.domain.model.BoundingBox>) -> Unit
) {
	Log.d(TAG, "Processing image...")
	try {
		// Convert ImageProxy to Bitmap
		val bitmap = cameraDataSource.imageToBitmap(imageProxy)
		Log.d(TAG, "Image converted to Bitmap with dimensions: ${bitmap.width}x${bitmap.height}")

		try {
			// Run object detection to get bounding boxes
			Log.d(TAG, "Detecting VIN boxes...")
			val detectionResult = vinDetector.detect(bitmap)
			onBoxesDetected(detectionResult.boundingBoxes)
			Log.d(TAG, "Detected ${detectionResult.boundingBoxes.size} VIN boxes.")

			// Extract all text from the image
			Log.d(TAG, "Extracting all text from the image...")
			val allText = textExtractor.extractAllText(bitmap)
			Log.d(TAG, "Extracted text: $allText")

			// Find the best VIN candidate
			var bestVin: String? = null
			var bestConfidence = 0f

			Log.d(TAG, "Finding best VIN candidate...")
			for (text in allText) {
				Log.d(TAG, "Validating text: $text")
				val cleanedText = vinValidator.cleanVin(text)
				Log.d(TAG, "Cleaned text: $cleanedText")
				if (cleanedText.length in 15..19) { // Basic VIN length check
					bestVin = cleanedText
					bestConfidence = 1.0f // We don't have per-box confidence anymore
					Log.d(TAG, "Found a valid VIN candidate: $bestVin")
					break
				}
			}

			// If a VIN was found, report it
			if (bestVin != null) {
				Log.i(TAG, "VIN detected: $bestVin with confidence $bestConfidence")
				onVinDetected(bestVin, bestConfidence)
			} else {
				Log.d(TAG, "No valid VIN found in the extracted text.")
			}

		} catch (e: Exception) {
			Log.e(TAG, "Error processing image", e)
		} finally {
			bitmap.recycle()
		}
	} catch (e: Exception) {
		e(TAG, "Error converting image", e)
	} finally {
		imageProxy.close()
	}
}

private fun copyToClipboard(context: Context, text: String) {
	val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
	val clip = ClipData.newPlainText("VIN", text)
	clipboard.setPrimaryClip(clip)
}
