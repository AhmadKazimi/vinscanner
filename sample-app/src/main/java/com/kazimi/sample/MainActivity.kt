package com.kazimi.sample

import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.syarah.vinscanner.VinScanResult
import com.syarah.vinscanner.VinScanner
import com.syarah.vinscanner.domain.model.VinNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val vinScannerLauncher =
        registerForActivityResult(
            VinScanner.Contract(),
        ) { result ->
            when (result) {
                is VinScanResult.Success -> viewModel.onScanSuccess(result.vinNumber)
                is VinScanResult.Cancelled -> viewModel.onScanCancelled()
                is VinScanResult.Error -> viewModel.onScanError(result.message)
            }
            // 1FMSKBBB0MGC21557
            // 1FMSK8BB0MGC21557
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SampleAppScreen(
                        scannedVin = viewModel.scannedVin,
                        resultMessage = viewModel.resultMessage,
                        onScanClick = {
                            vinScannerLauncher.launch(Unit)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FullScreenImageDialog(
    image: ImageBitmap,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image,
                contentDescription = "Full screen VIN Image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
fun SampleAppScreen(
    scannedVin: VinNumber?,
    resultMessage: String?,
    onScanClick: () -> Unit,
) {
    val context = LocalContext.current
    val resultBitmap by produceState<Bitmap?>(
        initialValue = scannedVin?.croppedImage,
        key1 = scannedVin?.croppedImageUri,
    ) {
        // Re-resolve on every new result; produceState retains the previous value
        // across key changes, so do not guard on `value == null`.
        value = scannedVin?.croppedImage ?: scannedVin?.croppedImageUri?.let { uri ->
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            }
        }
    }
    var fullScreenImage by remember { mutableStateOf<ImageBitmap?>(null) }

    fullScreenImage?.let { image ->
        FullScreenImageDialog(image = image, onDismiss = { fullScreenImage = null })
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "VIN Scanner Library Demo",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onScanClick,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(56.dp),
        ) {
            Text(
                text = "Start VIN Scan",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
            ) {
                Text(
                    text = "Result:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (scannedVin != null) {
                    resultBitmap?.let { bitmap ->
                        val imageBitmap = bitmap.asImageBitmap()
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = "Captured VIN Image",
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                                    .clickable { fullScreenImage = imageBitmap },
                            contentScale = ContentScale.Fit,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    val vinText =
                        buildString {
                            if (scannedVin.value.isEmpty()) {
                                append("Manual Entry Mode\n")
                                append("(No VIN detected - image captured for manual entry)")
                            } else {
                                append("VIN: ${scannedVin.value}\n")
                                append("Confidence: ${(scannedVin.confidence * 100).toInt()}%\n")
                                append("Valid: ${scannedVin.isValid}")
                            }
                        }
                    Text(
                        text = vinText,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else if (resultMessage != null) {
                    Text(
                        text = resultMessage,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    Text(
                        text = "No scan result yet",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
