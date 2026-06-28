@file:Suppress("ktlint:standard:function-naming")

package com.kazimi.syaravin.presentation.scanner

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kazimi.syaravin.R

/**
 * Top banner with context-sensitive guidance. Shown only when [activeGuidance] is not
 * [ScanGuidance.NONE]; fades/slides between hints.
 */
@Composable
internal fun BoxScope.ScannerGuidanceBanner(activeGuidance: ScanGuidance) {
    AnimatedVisibility(
        visible = activeGuidance != ScanGuidance.NONE,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 }),
        modifier =
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 140.dp, start = 24.dp, end = 24.dp),
    ) {
        AnimatedContent(targetState = activeGuidance, label = "scan_guidance") { guidance ->
            val textRes = guidance.bannerTextRes()
            if (textRes != null) {
                Text(
                    text = stringResource(textRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .background(
                                color = Color.Black.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(12.dp),
                            ).padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

private fun ScanGuidance.bannerTextRes(): Int? =
    when (this) {
        ScanGuidance.PREPARING -> R.string.scanner_preparing
        ScanGuidance.AIM -> R.string.scanner_guidance
        ScanGuidance.MOVE_CLOSER -> R.string.scanner_move_closer
        ScanGuidance.CENTER_VIN -> R.string.scanner_center_vin
        ScanGuidance.TAP_TO_FOCUS -> R.string.scanner_tap_to_focus
        ScanGuidance.HOLD_STEADY -> R.string.scanner_hold_steady
        ScanGuidance.NONE -> null
    }

/** Top app bar with auto-scan, torch, and start/stop controls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoxScope.ScannerTopBar(
    isScanning: Boolean,
    autoScanEnabled: Boolean,
    hasFlashUnit: Boolean,
    torchEnabled: Boolean,
    onClose: () -> Unit,
    onToggleAutoScan: () -> Unit,
    onToggleTorch: () -> Unit,
    onToggleScanning: () -> Unit,
) {
    TopAppBar(
        modifier = Modifier.align(Alignment.TopCenter),
        title = {},
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Black.copy(alpha = 0.5f),
                titleContentColor = Color.White,
            ),
        navigationIcon = {
            // Close the scanner without returning any VIN/result.
            IconButton(
                onClick = onClose,
                modifier =
                    Modifier.semantics {
                        contentDescription = "vin_scanner_close"
                    },
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.scanner_close),
                    tint = Color.White,
                )
            }
        },
        actions = {
            // Auto-scan on/off (persisted). Off → results suppressed; analyzer stays attached.
            IconButton(
                onClick = onToggleAutoScan,
                modifier =
                    Modifier.semantics {
                        contentDescription = "vin_scanner_toggle_auto_scan"
                    },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_barcode_scan),
                    contentDescription = stringResource(R.string.scanner_auto_scan_label),
                    tint = if (autoScanEnabled) Color(0xFFFFC107) else Color.White,
                )
            }
            if (hasFlashUnit) {
                IconButton(
                    onClick = onToggleTorch,
                    modifier =
                        Modifier.semantics {
                            contentDescription = "vin_scanner_toggle_torch"
                        },
                ) {
                    Icon(
                        imageVector = if (torchEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        contentDescription =
                            stringResource(
                                if (torchEnabled) R.string.torch_off else R.string.torch_on,
                            ),
                        tint = if (torchEnabled) Color(0xFFFFC107) else Color.White,
                    )
                }
            }
            IconButton(
                onClick = onToggleScanning,
                modifier =
                    Modifier.semantics {
                        contentDescription = "vin_scanner_toggle_scanning"
                    },
            ) {
                Icon(
                    imageVector = if (isScanning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription =
                        if (isScanning) {
                            stringResource(R.string.stop)
                        } else {
                            stringResource(
                                R.string.start,
                            )
                        },
                    tint = Color.White,
                )
            }
        },
    )
}

/** Full-screen message + button shown when camera permission is denied. */
@Composable
internal fun PermissionDeniedContent(onGrantPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.camera_permission_required_title),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.camera_permission_required_message),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onGrantPermission) {
            Text(stringResource(R.string.grant_permission))
        }
    }
}

/** Live "possible VIN" feedback, shown just above the capture button. */
@Composable
internal fun BoxScope.LiveCandidate(candidate: ScannedCandidate?) {
    AnimatedVisibility(
        visible = candidate != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 120.dp),
    ) {
        if (candidate != null) {
            ScannedCandidateCard(candidate)
        }
    }
}

@Composable
private fun ScannedCandidateCard(candidate: ScannedCandidate) {
    // Light, near-opaque pill so the dark text reads clearly over the camera feed.
    val fill =
        when {
            candidate.checksumValid -> Color(0xFFC8E6C9)
            candidate.isValid -> Color(0xFFFFECB3)
            else -> Color(0xFFFFCDD2)
        }
    val textColor =
        when {
            candidate.checksumValid -> Color(0xFF1B5E20)
            candidate.isValid -> Color(0xFF8A5A00)
            else -> Color(0xFFB71C1C)
        }
    Row(
        modifier =
            Modifier
                .background(fill.copy(alpha = 0.92f), MaterialTheme.shapes.medium)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = candidate.value,
            label = "candidate_vin",
        ) { value ->
            Text(
                text = value,
                color = textColor,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Error snackbar pinned to the bottom of the scanner. */
@Composable
internal fun BoxScope.ScannerErrorSnackbar(
    message: String,
    onDismiss: () -> Unit,
) {
    Snackbar(
        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        action = {
            TextButton(
                onClick = onDismiss,
                modifier =
                    Modifier.semantics {
                        contentDescription = "vin_scanner_dismiss_error"
                    },
            ) {
                Text(stringResource(R.string.dismiss))
            }
        },
    ) {
        Text(message)
    }
}
