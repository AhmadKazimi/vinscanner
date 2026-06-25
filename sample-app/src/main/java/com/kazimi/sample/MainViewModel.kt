package com.kazimi.sample

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import com.kazimi.syaravin.domain.model.VinNumber

data class ScanHistoryEntry(
    val vin: VinNumber? = null,
    val message: String? = null,
)

class MainViewModel : ViewModel() {
    private val _history = mutableStateListOf<ScanHistoryEntry>()
    val history: List<ScanHistoryEntry> get() = _history

    private fun push(entry: ScanHistoryEntry) {
        _history.add(0, entry)
        if (_history.size > 10) _history.removeAt(_history.lastIndex)
    }

    fun onScanSuccess(vinNumber: VinNumber) = push(ScanHistoryEntry(vin = vinNumber))
    fun onScanCancelled() = push(ScanHistoryEntry(message = "Scan cancelled"))
    fun onScanError(message: String) = push(ScanHistoryEntry(message = "Error: $message"))
}
