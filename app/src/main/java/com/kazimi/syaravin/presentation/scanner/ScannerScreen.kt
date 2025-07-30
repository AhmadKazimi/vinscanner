package com.kazimi.syaravin.presentation.scanner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import com.kazimi.syaravin.presentation.components.VinResultDialog
import com.kazimi.syaravin.util.showToast
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import timber.log.Timber
import java.util.concurrent.ExecutorService

/**
 * Main scanner screen for VIN detection
 */
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    viewModel: ScannerViewModel = koinViewModel()
) {
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
                viewModel.onEvent(ScannerEvent.PermissionGranted)
            } else {
                viewModel.onEvent(ScannerEvent.PermissionDenied)
            }
        }
    )
    
    // Request permission on first launch
    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        } else {
            viewModel.onEvent(ScannerEvent.PermissionGranted)
        }
    }
    
    // Set up image analysis
    DisposableEffect(state.isScanning) {
        if (state.isScanning) {
            imageAnalysis.setAnalyzer(executor) { imageProxy ->
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
            }
        }
        
        onDispose {
            imageAnalysis.clearAnalyzer()
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
    
    // VIN result dialog
    if (state.showVinResult && state.detectedVin != null) {
        VinResultDialog(
            vinNumber = state.detectedVin!!,
            onDismiss = { viewModel.onEvent(ScannerEvent.DismissResult) },
            onRetry = { viewModel.onEvent(ScannerEvent.RetryScanning) },
            onCopy = { vin ->
                copyToClipboard(context, vin)
                context.showToast("VIN copied to clipboard")
                viewModel.onEvent(ScannerEvent.DismissResult)
            }
        )
    }
}

private fun processImage(
    imageProxy: ImageProxy,
    cameraDataSource: CameraDataSource,
    vinDetector: VinDetector,
    textExtractor: TextExtractor,
    vinValidator: VinValidator,
    onVinDetected: (String, Float) -> Unit,
    onBoxesDetected: (List<com.kazimi.syaravin.domain.model.BoundingBox>) -> Unit
) {
    try {
        // Convert ImageProxy to Bitmap
        val bitmap = cameraDataSource.imageToBitmap(imageProxy)
        
        // Run VIN detection
        kotlinx.coroutines.GlobalScope.launch {
            try {
                val detectionResult = vinDetector.detect(bitmap)
                onBoxesDetected(detectionResult.boundingBoxes)
                
                // Process each detection
                for (box in detectionResult.boundingBoxes) {
                    val extractedText = textExtractor.extractText(bitmap, box)
                    
                    if (!extractedText.isNullOrEmpty()) {
                        val cleanedVin = vinValidator.cleanVin(extractedText)
                        
                        // Check if it looks like a VIN
                        if (cleanedVin.length in 15..19) {
                            onVinDetected(cleanedVin, box.confidence)
                            break // Stop after first VIN
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error processing image")
            } finally {
                bitmap.recycle()
            }
        }
    } catch (e: Exception) {
        Timber.e(e, "Error converting image")
    } finally {
        imageProxy.close()
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("VIN", text)
    clipboard.setPrimaryClip(clip)
}