package com.syarah.vinscanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.IntentCompat
import com.syarah.vinscanner.domain.model.VinNumber

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
    override fun createIntent(
        context: Context,
        input: Unit,
    ): Intent = Intent(context, VinScannerActivity::class.java)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): VinScanResult =
        when (resultCode) {
            Activity.RESULT_OK -> {
                intent
                    ?.let {
                        IntentCompat.getParcelableExtra(
                            it,
                            EXTRA_VIN_RESULT,
                            VinNumber::class.java,
                        )
                    }?.let {
                        VinScanResult.Success(it)
                    } ?: VinScanResult.Error("Invalid result data")
            }

            Activity.RESULT_CANCELED -> {
                VinScanResult.Cancelled
            }

            else -> {
                VinScanResult.Error("Unknown error")
            }
        }

    companion object {
        internal const val EXTRA_VIN_RESULT = "extra_vin_result"
    }
}
