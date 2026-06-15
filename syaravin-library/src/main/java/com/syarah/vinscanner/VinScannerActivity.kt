package com.syarah.vinscanner

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.syarah.vinscanner.di.VinScannerDependencies
import com.syarah.vinscanner.domain.model.VinNumber
import com.syarah.vinscanner.presentation.scanner.ScannerScreen
import com.syarah.vinscanner.ui.theme.SyaravinTheme
import com.syarah.vinscanner.util.LogTags
import com.syarah.vinscanner.util.SLog

private const val TAG = LogTags.LIBRARY

/**
 * Internal activity that hosts the VIN scanner UI.
 * Launched via VinScannerContract.
 */
internal class VinScannerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val startMs = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        SLog.w(TAG, "VIN scanner activity created")

        // Initialize dependency factory with Application context
        val initStartMs = SystemClock.elapsedRealtime()
        VinScannerDependencies.initialize(applicationContext)
        SLog.w(
            TAG,
            "Dependencies.initialize() took ${SystemClock.elapsedRealtime() - initStartMs}ms",
        )

        enableEdgeToEdge()
        val setContentStartMs = SystemClock.elapsedRealtime()
        setContent {
            SyaravinTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ScannerScreen(
                        onVinConfirmed = { vinNumber ->
                            // User confirmed VIN in bottom sheet
                            returnResult(vinNumber)
                        },
                        onCancelled = {
                            // User cancelled scanning
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        },
                    )
                }
            }
        }
        SLog.w(
            TAG,
            "setContent() returned in ${SystemClock.elapsedRealtime() - setContentStartMs}ms; total onCreate=${SystemClock.elapsedRealtime() - startMs}ms",
        )
    }

    override fun onDestroy() {
        SLog.w(TAG, "VIN scanner activity destroying")
        super.onDestroy()
    }

    private fun returnResult(vinNumber: VinNumber) {
        val resultIntent =
            Intent().apply {
                putExtra(EXTRA_VIN_RESULT, vinNumber)
            }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    companion object {
        const val EXTRA_VIN_RESULT = "extra_vin_result"
    }
}
