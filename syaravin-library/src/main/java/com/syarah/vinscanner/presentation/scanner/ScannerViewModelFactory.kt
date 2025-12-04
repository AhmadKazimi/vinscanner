package com.syarah.vinscanner.presentation.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.syarah.vinscanner.di.VinScannerDependencies

/**
 * Factory for creating ScannerViewModel with manual dependency injection.
 * Uses VinScannerDependencies to provide all required use cases.
 */
internal class ScannerViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ScannerViewModel::class.java)) {
            return VinScannerDependencies.get().createScannerViewModel() as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
