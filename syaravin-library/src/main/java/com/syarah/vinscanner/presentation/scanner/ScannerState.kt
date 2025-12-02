package com.syarah.vinscanner.presentation.scanner

import com.syarah.vinscanner.domain.model.BoundingBox
import com.syarah.vinscanner.domain.model.VinNumber

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
    val scanHistory: List<VinNumber> = emptyList()
) {
    /**
     * Whether the scanner is actively processing
     */
    val isProcessing: Boolean
        get() = isScanning && isLoading
}