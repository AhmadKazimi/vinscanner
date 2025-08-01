
package com.kazimi.syaravin.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kazimi.syaravin.domain.model.VinNumber
import com.kazimi.syaravin.util.VinDecoder
import org.koin.androidx.compose.get

/**
 * Dialog to display VIN detection results
 */
@Composable
fun VinResultDialog(
    vinNumber: VinNumber,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
    onVinChanged: (String) -> Unit
) {

    Dialog(onDismissRequest = onDismiss) {
        VinResultSheetContent(
            vinNumber = vinNumber,
            onRetry = onRetry,
            onCopy = onCopy,
            onVinChanged = onVinChanged
        )
    }
}


@Composable
fun VinResultSheetContent(
    vinNumber: VinNumber,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
    onVinChanged: (String) -> Unit
) {
    var vin by remember(vinNumber) { mutableStateOf(vinNumber.value) }
    val vinDecoder: VinDecoder = get()

    val vinInfo by remember(vin) {
        derivedStateOf {
            vinDecoder.decode(vin)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title
            Text(
                text = "VIN Detected",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // VIN Value
            VinInputField(
                vin = vin,
                onVinChanged = {
                    vin = it
                    onVinChanged(it)
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Validation Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (vinNumber.isValid) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Filled.Warning
                    },
                    contentDescription = null,
                    tint = if (vinNumber.isValid) Color.Green else Color.Red,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = if (vinNumber.isValid) "Valid VIN" else "Invalid VIN",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (vinNumber.isValid) Color.Green else Color.Red,
                    fontWeight = FontWeight.Medium
                )
            }

            // Confidence Score
            if (vinNumber.confidence > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Confidence: ${(vinNumber.confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            vinInfo?.let { info ->
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text("Manufacturer: ${info.manufacturer}")
                    Text("Country: ${info.country}")
                    Text("Model Year: ${info.modelYear}")
                    Text("Assembly Plant: ${info.assemblyPlant}")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Copy Button
                OutlinedButton(
                    onClick = { onCopy(vin) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Face,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy")
                }

                // Retry Button
                Button(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Scan Again")
                }
            }
        }
    }
}
