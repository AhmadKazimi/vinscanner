package com.syarah.vinscanner

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.syarah.vinscanner.domain.model.VinNumber
import com.syarah.vinscanner.presentation.scanner.ScannerScreen
import com.syarah.vinscanner.ui.theme.SyaravinTheme

/**
 * Internal activity that hosts the VIN scanner UI.
 * Launched via VinScannerContract.
 */
internal class VinScannerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SyaravinTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
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
                        }
                    )
                }
            }
        }
    }

    private fun returnResult(vinNumber: VinNumber) {
        val resultIntent = Intent().apply {
            putExtra(EXTRA_VIN_RESULT, vinNumber)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    companion object {
        const val EXTRA_VIN_RESULT = "extra_vin_result"
    }
}
