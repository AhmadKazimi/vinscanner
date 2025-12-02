package com.kazimi.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                // Display VIN result
                val vin = result.vinNumber
                vinResult = "VIN: ${vin.value}\nConfidence: ${(vin.confidence * 100).toInt()}%\nValid: ${vin.isValid}"
            }
            is VinScanResult.Cancelled -> {
                vinResult = "Scan cancelled by user"
            }
            is VinScanResult.Error -> {
                vinResult = "Error: ${result.message}"
            }
        }
    }

    private var vinResult by mutableStateOf("No scan result yet")

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
                        vinResult = vinResult,
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
    vinResult: String,
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
                Text(
                    text = vinResult,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
