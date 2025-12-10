package com.kazimi.sample

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.syarah.vinscanner.VinScanner
import com.syarah.vinscanner.VinScanResult
import com.syarah.vinscanner.domain.model.VinNumber

class MainActivity : ComponentActivity() {

    private val vinScannerLauncher = registerForActivityResult(
        VinScanner.Contract()
    ) { result ->
        when (result) {
            is VinScanResult.Success -> {
                // Store VinNumber to access bitmap
                scannedVin = result.vinNumber
                resultMessage = null
            }
            is VinScanResult.Cancelled -> {
                scannedVin = null
                resultMessage = "Scan cancelled by user"
            }
            is VinScanResult.Error -> {
                scannedVin = null
                resultMessage = "Error: ${result.message}"
            }
        }
    }

    private var scannedVin by mutableStateOf<VinNumber?>(null)
    private var resultMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SampleAppScreen(
                        scannedVin = scannedVin,
                        resultMessage = resultMessage,
                        onScanClick = {
                            vinScannerLauncher.launch(Unit)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SampleAppScreen(
    scannedVin: VinNumber?,
    resultMessage: String?,
    onScanClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "VIN Scanner Library Demo",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onScanClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                text = "Start VIN Scan",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Result:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Show VIN info if available
                if (scannedVin != null) {
                    // Show captured image if available
                    scannedVin.croppedImage?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Captured VIN Image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Show VIN details
                    val vinText = buildString {
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
                        style = MaterialTheme.typography.bodyLarge
                    )
                } else if (resultMessage != null) {
                    // Show error/cancelled message
                    Text(
                        text = resultMessage,
                        style = MaterialTheme.typography.bodyLarge
                    )
                } else {
                    // No result yet
                    Text(
                        text = "No scan result yet",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}
