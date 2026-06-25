@file:Suppress("ktlint:standard:function-naming")

package com.kazimi.syaravin.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.kazimi.syaravin.R
import com.kazimi.syaravin.data.datasource.validator.VinValidator
import com.kazimi.syaravin.di.VinScannerDependencies
import com.kazimi.syaravin.domain.model.VinNumber
import com.kazimi.syaravin.util.ImagePreprocessor
import com.kazimi.syaravin.util.LogTags
import com.kazimi.syaravin.util.SLog
import com.kazimi.syaravin.util.VinDecoder

private const val TAG = LogTags.LIBRARY

/**
 * Dialog to display VIN detection results
 */
@Composable
internal fun VinResultDialog(
    vinNumber: VinNumber,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
    onVinChanged: (String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        VinResultSheetContent(
            vinNumber = vinNumber,
            onRetry = onRetry,
            onCopy = onCopy,
            onVinChanged = onVinChanged,
        )
    }
}

@Composable
internal fun VinResultSheetContent(
    vinNumber: VinNumber,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
    onVinChanged: (String) -> Unit,
    onConfirm: (VinNumber) -> Unit = {},
    vinDecoder: VinDecoder = VinScannerDependencies.get().vinDecoder,
    vinValidator: VinValidator = VinScannerDependencies.get().vinValidator,
) {
    var vin by remember(vinNumber) { mutableStateOf(vinNumber.value) }

    val vinInfo by remember(vin) {
        derivedStateOf {
            vinDecoder.decode(vin)
        }
    }

    // Reflect true validity using checksum rules when editable VIN changes
    val isCurrentVinValid by remember(vin) {
        mutableStateOf(vinValidator.validate(vin).isValid)
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFFAFAFA)),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header with title and checkmark
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.vin_detected),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF212121),
                )

                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4AAF57),
                    modifier = Modifier.size(26.dp),
                )
            }

            // VIN Number Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = Color.White,
                    ),
                shape = RoundedCornerShape(15.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.vin_number),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0D0DB5),
                    )

                    Text(
                        text = vin,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF212121),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // VIN Image Card (if available)
            vinNumber.croppedImage?.let { bitmap ->
                val displayBitmap =
                    remember(bitmap) {
                        ImagePreprocessor.downscaleForDisplay(bitmap)
                    }
                SLog.d(TAG, "Displaying cropped image: ${displayBitmap.width}x${displayBitmap.height}")

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = Color.White,
                        ),
                    shape = RoundedCornerShape(15.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.vin_image),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0D0DB5),
                        )

                        Image(
                            bitmap = displayBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.vin_image_content_description),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 80.dp, max = 150.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            } ?: run {
                SLog.d(TAG, "No cropped image available")
            }

            // Car Information Card
            vinInfo?.let { info ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = Color.White,
                        ),
                    shape = RoundedCornerShape(15.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.car_information),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0D0DB5),
                        )

                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.manufacturer_label, info.manufacturer),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF212121),
                            )

                            Text(
                                text = stringResource(R.string.country_label, info.country),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF212121),
                            )

                            Text(
                                text = stringResource(R.string.model_year_label, info.modelYear),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF212121),
                            )
                        }
                    }
                }
            }
        }

        // Bottom Action Buttons Container
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        color = Color.White,
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    ).border(
                        width = 1.dp,
                        color = Color(0xFFF5F5F5),
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    ).padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Confirmed Button
            Button(
                onClick = {
                    onConfirm(vinNumber.copy(value = vin, isValid = isCurrentVinValid))
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(100.dp),
                contentPadding = PaddingValues(vertical = 18.dp, horizontal = 16.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0D0DB5),
                        contentColor = Color.White,
                    ),
                enabled = isCurrentVinValid,
                elevation =
                    ButtonDefaults.buttonElevation(
                        defaultElevation = 4.dp,
                        pressedElevation = 8.dp,
                    ),
            ) {
                Text(
                    text = stringResource(R.string.confirmed),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Scan Again Button
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(100.dp),
                contentPadding = PaddingValues(vertical = 18.dp, horizontal = 16.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEC6234),
                        contentColor = Color.White,
                    ),
            ) {
                Text(
                    text = stringResource(R.string.scan_again),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
