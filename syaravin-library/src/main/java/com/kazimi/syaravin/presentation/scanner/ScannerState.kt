package com.kazimi.syaravin.presentation.scanner

import android.graphics.Bitmap
import com.kazimi.syaravin.domain.model.BoundingBox
import com.kazimi.syaravin.domain.model.VinNumber

/**
 * Represents the ROI border state for visual feedback
 */
internal enum class RoiBorderState {
    NEUTRAL, // White - scanning with boxes
    VALID_VIN_DETECTED, // Green - valid VIN found
    NO_DETECTION, // Red - no boxes detected
}

/**
 * Context-sensitive guidance shown to the user in the scanner banner. Highest-need hint wins;
 * [NONE] hides the banner (everything looks good / actively scanning a well-placed sharp VIN).
 */
internal enum class ScanGuidance {
    NONE, // Hide the banner.
    PREPARING, // First load — model still warming up.
    AIM, // No VIN detected yet — generic "keep VIN in the box" guidance.
    MOVE_CLOSER, // VIN box too small — camera too far.
    CENTER_VIN, // VIN clipped by / off-center from the box.
    HOLD_STEADY, // VIN positioned but the frame is soft (motion blur) — gate waiting for sharper.
    TAP_TO_FOCUS, // VIN positioned but focus is unstable — prompt a tap-to-focus.
}

/**
 * A possible VIN currently being read live (before auto-confirm), shown for user feedback.
 */
internal data class ScannedCandidate(
    val value: String,
    val confidence: Float,
    val isValid: Boolean,
    val checksumValid: Boolean,
)

/**
 * Represents the state of the scanner screen
 */
internal data class ScannerState(
    val isScanning: Boolean = false,
    val isLoading: Boolean = false,
    val detectedVin: VinNumber? = null,
    val detectionBoxes: List<BoundingBox> = emptyList(),
    val errorMessage: String? = null,
    val hasPermission: Boolean = false,
    val showVinResult: Boolean = false,
    val scanHistory: List<VinNumber> = emptyList(),
    val roiBorderState: RoiBorderState = RoiBorderState.NO_DETECTION, // Start with RED
    val latestRoiCroppedBitmap: Bitmap? = null, // ROI-cropped bitmap for manual entry
    val scannedCandidate: ScannedCandidate? = null, // Live "possible VIN" for feedback
    val scanGuidance: ScanGuidance = ScanGuidance.NONE, // Context-sensitive banner hint
) {
    /**
     * Whether the scanner is actively processing
     */
    val isProcessing: Boolean
        get() = isScanning && isLoading
}
