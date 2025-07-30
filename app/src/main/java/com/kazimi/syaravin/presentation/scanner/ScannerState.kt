package com.kazimi.syaravin.presentation.scanner

import com.kazimi.syaravin.domain.model.BoundingBox
import com.kazimi.syaravin.domain.model.VinNumber

/**
 * Represents the state of the scanner screen
 */
data class ScannerState(
    val isScanning: Boolean = false,
    val isLoading: Boolean = false,
    val detectedVin: VinNumber? = null,
    val detectionBoxes: List<BoundingBox> = emptyList(),
    val errorMessage: String? = null,
    val hasPermission: Boolean = false,
    val showVinResult: Boolean = false,
    val scanHistory: List<VinNumber> = emptyList()
) {
    /**
     * Whether the scanner is actively processing
     */
    val isProcessing: Boolean
        get() = isScanning && isLoading
}