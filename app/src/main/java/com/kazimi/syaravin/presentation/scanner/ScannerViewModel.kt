package com.kazimi.syaravin.presentation.scanner

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kazimi.syaravin.domain.model.VinNumber
import com.kazimi.syaravin.domain.usecase.DetectVinUseCase
import com.kazimi.syaravin.domain.usecase.ExtractTextUseCase
import com.kazimi.syaravin.domain.usecase.ValidateVinUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the scanner screen
 */
class ScannerViewModel(
    private val detectVinUseCase: DetectVinUseCase,
    private val extractTextUseCase: ExtractTextUseCase,
    private val validateVinUseCase: ValidateVinUseCase
) : ViewModel() {
    
    private val _state = MutableStateFlow(ScannerState())
    val state: StateFlow<ScannerState> = _state.asStateFlow()
    
    fun onEvent(event: ScannerEvent) {
        when (event) {
            is ScannerEvent.StartScanning -> startScanning()
            is ScannerEvent.StopScanning -> stopScanning()
            is ScannerEvent.PermissionGranted -> updatePermissionStatus(true)
            is ScannerEvent.PermissionDenied -> updatePermissionStatus(false)
            is ScannerEvent.DismissError -> dismissError()
            is ScannerEvent.DismissResult -> dismissResult()
            is ScannerEvent.RetryScanning -> retryScanning()
        }
    }
    
    private fun startScanning() {
        if (!_state.value.hasPermission) {
            _state.update { it.copy(errorMessage = "Camera permission is required") }
            return
        }
        
        _state.update { it.copy(isScanning = true, errorMessage = null) }
        Log.d("","Starting VIN scanning")
        
        // Scanning logic will be implemented in the UI layer with CameraX
        // The ViewModel will receive detected VINs through events
    }
    
    private fun stopScanning() {
        _state.update { it.copy(isScanning = false, isLoading = false) }
        Log.d("","Stopped VIN scanning")
    }
    
    private fun updatePermissionStatus(granted: Boolean) {
        _state.update { it.copy(hasPermission = granted) }
        if (granted) {
            startScanning()
        } else {
            _state.update { it.copy(errorMessage = "Camera permission is required to scan VINs") }
        }
    }
    
    private fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }
    
    private fun dismissResult() {
        _state.update { it.copy(showVinResult = false, detectedVin = null) }
    }
    
    private fun retryScanning() {
        dismissResult()
        startScanning()
    }
    
    fun onVinDetected(vin: String, confidence: Float) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            
            try {
                // Validate the VIN
                val validatedVin = validateVinUseCase(vin)
                
                // Update state with the result
                _state.update { currentState ->
                    currentState.copy(
                        isLoading = false,
                        detectedVin = validatedVin.copy(confidence = confidence),
                        showVinResult = true,
                        scanHistory = currentState.scanHistory + validatedVin
                    )
                }
                
                // Stop scanning after successful detection
                stopScanning()

                Log.d("","VIN detected and validated: ${validatedVin.value} (valid: ${validatedVin.isValid})")
            } catch (e: Exception) {
                Log.e(e.message, "Error validating VIN")
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Error validating VIN: ${e.message}"
                    )
                }
            }
        }
    }
    
    fun onDetectionBoxesUpdated(boxes: List<com.kazimi.syaravin.domain.model.BoundingBox>) {
        _state.update { it.copy(detectionBoxes = boxes) }
    }
}

/**
 * Events that can be triggered from the UI
 */
sealed class ScannerEvent {
    object StartScanning : ScannerEvent()
    object StopScanning : ScannerEvent()
    object PermissionGranted : ScannerEvent()
    object PermissionDenied : ScannerEvent()
    object DismissError : ScannerEvent()
    object DismissResult : ScannerEvent()
    object RetryScanning : ScannerEvent()
}