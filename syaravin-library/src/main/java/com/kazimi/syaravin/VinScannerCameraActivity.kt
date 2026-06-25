package com.kazimi.syaravin

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class VinScannerCameraActivity : ComponentActivity() {
    private val vinScannerLauncher = registerForActivityResult(
        VinScanner.Contract(),
    ) { result ->
        when (result) {
            is VinScanResult.Success -> {
                val vin = result.vinNumber
                setResult(
                    Activity.RESULT_OK,
                    Intent()
                        .putExtra(EXTRA_VIN_VALUE, vin.value)
                        .putExtra(EXTRA_VIN_CONFIDENCE, vin.confidence)
                        .putExtra(EXTRA_VIN_IS_VALID, vin.isValid),
                )
                finish()
            }

            is VinScanResult.Cancelled -> {
                setEmptyResult()
            }

            is VinScanResult.Error -> {
                setResult(
                    Activity.RESULT_OK,
                    Intent()
                        .putExtra(EXTRA_VIN_VALUE, "")
                        .putExtra(EXTRA_VIN_CONFIDENCE, 0f)
                        .putExtra(EXTRA_VIN_IS_VALID, false)
                        .putExtra(EXTRA_ERROR_MESSAGE, result.message),
                )
                finish()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchVinScanner()
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (allPermissionsGranted()) {
            launchVinScanner()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchVinScanner() {
        vinScannerLauncher.launch(Unit)
    }

    private fun allPermissionsGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            baseContext,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

    private fun setEmptyResult() {
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_VIN_VALUE, "")
                .putExtra(EXTRA_VIN_CONFIDENCE, 0f)
                .putExtra(EXTRA_VIN_IS_VALID, false),
        )
        finish()
    }

    companion object {
        const val EXTRA_VIN_VALUE = "extra_vin_value"
        const val EXTRA_VIN_CONFIDENCE = "extra_vin_confidence"
        const val EXTRA_VIN_IS_VALID = "extra_vin_is_valid"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"
    }
}
