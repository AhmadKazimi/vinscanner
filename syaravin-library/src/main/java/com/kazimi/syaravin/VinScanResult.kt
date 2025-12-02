package com.kazimi.syaravin

import com.kazimi.syaravin.domain.model.VinNumber

/**
 * Result of a VIN scanning operation.
 */
sealed class VinScanResult {
    /**
     * VIN was successfully detected and validated.
     * @property vinNumber The detected VIN with confidence, validation status, and cropped image
     */
    data class Success(val vinNumber: VinNumber) : VinScanResult()

    /**
     * User cancelled the scanning operation.
     */
    object Cancelled : VinScanResult()

    /**
     * An error occurred during scanning.
     * @property message Error description
     */
    data class Error(val message: String) : VinScanResult()
}
