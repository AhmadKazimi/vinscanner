package com.kazimi.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.kazimi.syaravin.domain.model.VinNumber

class MainViewModel : ViewModel() {
    var scannedVin by mutableStateOf<VinNumber?>(null)
        private set
    var resultMessage by mutableStateOf<String?>(null)
        private set

    fun onScanSuccess(vinNumber: VinNumber) {
        scannedVin = vinNumber
        resultMessage = null
    }

    fun onScanCancelled() {
        scannedVin = null
        resultMessage = "Scan cancelled by user"
    }

    fun onScanError(message: String) {
        scannedVin = null
        resultMessage = "Error: $message"
    }
}
