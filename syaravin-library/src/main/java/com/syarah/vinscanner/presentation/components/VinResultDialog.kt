
package com.syarah.vinscanner.presentation.components

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.syarah.vinscanner.domain.model.VinNumber
import com.syarah.vinscanner.util.VinDecoder
import org.koin.compose.koinInject

/**
 * Dialog to display VIN detection results
 */
@Composable
internal fun VinResultDialog(
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
internal fun VinResultSheetContent(
    vinNumber: VinNumber,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
    onVinChanged: (String) -> Unit,
    onConfirm: (VinNumber) -> Unit = {}
) {
    var vin by remember(vinNumber) { mutableStateOf(vinNumber.value) }
    val vinDecoder: VinDecoder = koinInject()
    var showSuccessCopied by remember { mutableStateOf(false) }

    val vinInfo by remember(vin) {
        derivedStateOf {
            vinDecoder.decode(vin)
        }
    }
    
    // Reflect true validity using checksum rules when editable VIN changes
    val isCurrentVinValid by remember(vin) {
        mutableStateOf(com.syarah.vinscanner.data.datasource.validator.VinValidatorImpl().validate(vin).isValid)
    }

    // Animation for status icon
    val iconScale by animateFloatAsState(
        targetValue = if (isCurrentVinValid) 1f else 0.9f,
        label = "icon_scale"
    )
    
    val statusColor by animateColorAsState(
        targetValue = if (isCurrentVinValid) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
        label = "status_color"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Handle bar
        Box(
            modifier = Modifier
                .padding(top = 12.dp)
                .size(width = 48.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                .align(Alignment.CenterHorizontally)
        )
        
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status Icon with animation
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .scale(iconScale)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isCurrentVinValid) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Filled.Warning
                    },
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Title
            Text(
                text = "VIN Detected",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            if (vinNumber.confidence > 0) {
                Text(
                    text = "${(vinNumber.confidence * 100).toInt()}% confidence",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // VIN Input Field
            VinTextField(
                vin = vin,
                onVinChanged = {
                    vin = it
                    onVinChanged(it)
                },
                isValid = isCurrentVinValid,
                modifier = Modifier.fillMaxWidth()
            )

            // Image Preview (if available)
            vinNumber.croppedImage?.let { bitmap ->
                Log.d("VinResultDialog", "Displaying cropped image: ${bitmap.width}x${bitmap.height}")
                Spacer(modifier = Modifier.height(20.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Detected Image",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(8.dp)
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Cropped VIN image",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 80.dp, max = 200.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }
            } ?: run {
                Log.d("VinResultDialog", "No cropped image available")
            }

            // VIN Info Card
            vinInfo?.let { info ->
                Spacer(modifier = Modifier.height(20.dp))
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Vehicle Information",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        
                        VinInfoRow(
                            icon = Icons.Filled.Build,
                            label = "Manufacturer",
                            value = info.manufacturer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        VinInfoRow(
                            icon = Icons.Filled.LocationOn,
                            label = "Country",
                            value = info.country
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        VinInfoRow(
                            icon = Icons.Filled.DateRange,
                            label = "Model Year",
                            value = info.modelYear.toString()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        VinInfoRow(
                            icon = Icons.Filled.Build,
                            label = "Assembly Plant",
                            value = info.assemblyPlant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Copy Button
                OutlinedButton(
                    onClick = {
                        onCopy(vin)
                        showSuccessCopied = true
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Icon(
                        imageVector = if (showSuccessCopied) Icons.Filled.Check else Icons.Filled.Clear,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (showSuccessCopied) "Copied!" else "Copy",
                        fontWeight = FontWeight.Medium
                    )
                }

                // Confirm Button
                Button(
                    onClick = {
                        // Return the VinNumber with the current (possibly edited) vin value
                        onConfirm(vinNumber.copy(value = vin, isValid = isCurrentVinValid))
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    enabled = isCurrentVinValid
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Confirm",
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
    
    // Reset copy feedback after delay
    LaunchedEffect(showSuccessCopied) {
        if (showSuccessCopied) {
            kotlinx.coroutines.delay(2000)
            showSuccessCopied = false
        }
    }
}

@Composable
private fun VinInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
