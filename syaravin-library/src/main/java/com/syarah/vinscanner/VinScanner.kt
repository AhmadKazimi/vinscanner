package com.syarah.vinscanner

import androidx.compose.material3.Typography

/**
 * Main entry point for VIN scanning functionality.
 *
 * Usage:
 * ```
 * class MainActivity : ComponentActivity() {
 *     private val vinScannerLauncher = registerForActivityResult(
 *         VinScanner.Contract()
 *     ) { result ->
 *         when (result) {
 *             is VinScanResult.Success -> {
 *                 val vin = result.vinNumber
 *                 println("VIN: ${vin.value}, Confidence: ${vin.confidence}")
 *             }
 *             is VinScanResult.Cancelled -> { /* User cancelled */ }
 *             is VinScanResult.Error -> { /* Handle error */ }
 *         }
 *     }
 *
 *     fun startScanning() {
 *         vinScannerLauncher.launch(Unit)
 *     }
 * }
 * ```
 */
object VinScanner {
    @Volatile
    internal var typographyOverride: Typography? = null

    /**
     * Returns the ActivityResultContract for VIN scanning.
     * Use with registerForActivityResult() in your Activity or Fragment.
     */
    fun Contract(): VinScannerContract = VinScannerContract()

    /**
     * Optional: Provide host-app typography so scanner UI uses the same fonts.
     * Call once at app startup, e.g. `VinScanner.setTypography(AppTypography)`.
     */
    fun setTypography(typography: Typography) {
        typographyOverride = typography
    }

    /**
     * Clears typography override and reverts scanner UI to library default typography.
     */
    fun clearTypographyOverride() {
        typographyOverride = null
    }

    /**
     * Library version
     */
    const val VERSION = "1.4.2"
}
