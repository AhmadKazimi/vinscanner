package com.syarah.vinscanner.presentation.scanner

import android.content.Context
import com.syarah.vinscanner.R

internal data class ScannerViewModelStrings(
    val permissionRequired: String,
    val permissionRequiredForScanning: String,
    val errorValidatingVin: (String) -> String
) {
    companion object {
        fun from(context: Context): ScannerViewModelStrings {
            return ScannerViewModelStrings(
                permissionRequired = context.getString(R.string.error_camera_permission_required),
                permissionRequiredForScanning = context.getString(R.string.error_camera_permission_required_for_scanning),
                errorValidatingVin = { detail ->
                    context.getString(
                        R.string.error_validating_vin,
                        detail.ifBlank { "unknown" }
                    )
                }
            )
        }
    }
}

