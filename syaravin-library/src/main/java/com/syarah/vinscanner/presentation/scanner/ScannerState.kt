package com.syarah.vinscanner.presentation.scanner

import com.syarah.vinscanner.domain.model.BoundingBox
import com.syarah.vinscanner.domain.model.VinNumber

/**
 * Represents the ROI border state for visual feedback
 */
internal enum class RoiBorderState {
    NEUTRAL,              // White - scanning with boxes
    VALID_VIN_DETECTED,   // Green - valid VIN found
    NO_DETECTION         // Red - no boxes detected
}

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
    val roiBorderState: RoiBorderState = RoiBorderState.NO_DETECTION  // Start with RED
) {
    /**
     * Whether the scanner is actively processing
     */
    val isProcessing: Boolean
        get() = isScanning && isLoading
}