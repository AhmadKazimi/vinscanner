package com.syarah.vinscanner

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
    /**
     * Returns the ActivityResultContract for VIN scanning.
     * Use with registerForActivityResult() in your Activity or Fragment.
     */
    fun Contract(): VinScannerContract = VinScannerContract()

    /**
     * Library version
     */
    const val VERSION = "1.2.2"
}
