package com.kazimi.syaravin

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.kazimi.syaravin.domain.model.VinNumber

/**
 * ActivityResultContract for launching VIN scanner and receiving results.
 * This contract handles all the plumbing for starting the scanner activity
 * and parsing the result.
 *
 * Usage:
 * ```
 * val launcher = registerForActivityResult(VinScannerContract()) { result ->
 *     when (result) {
 *         is VinScanResult.Success -> { /* Handle VIN */ }
 *         is VinScanResult.Cancelled -> { /* Handle cancel */ }
 *         is VinScanResult.Error -> { /* Handle error */ }
 *     }
 * }
 *
 * launcher.launch(Unit)
 * ```
 */
class VinScannerContract : ActivityResultContract<Unit, VinScanResult>() {

    override fun createIntent(context: Context, input: Unit): Intent {
        return Intent(context, VinScannerActivity::class.java)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): VinScanResult {
        return when (resultCode) {
            Activity.RESULT_OK -> {
                intent?.getParcelableExtra<VinNumber>(EXTRA_VIN_RESULT)?.let {
                    VinScanResult.Success(it)
                } ?: VinScanResult.Error("Invalid result data")
            }
            Activity.RESULT_CANCELED -> VinScanResult.Cancelled
            else -> VinScanResult.Error("Unknown error")
        }
    }

    companion object {
        internal const val EXTRA_VIN_RESULT = "extra_vin_result"
    }
}
