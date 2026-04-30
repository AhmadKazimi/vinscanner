package com.syarah.vinscanner.presentation.scanner

import android.graphics.Bitmap
import com.syarah.vinscanner.util.SLog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syarah.vinscanner.domain.model.VinNumber
import com.syarah.vinscanner.domain.usecase.DetectVinUseCase
import com.syarah.vinscanner.domain.usecase.ExtractTextUseCase
import com.syarah.vinscanner.domain.usecase.ValidateVinUseCase
import com.syarah.vinscanner.util.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = LogTags.LIBRARY

/**
 * ViewModel for the scanner screen
 */
internal class ScannerViewModel(
    private val detectVinUseCase: DetectVinUseCase,
    private val extractTextUseCase: ExtractTextUseCase,
    private val validateVinUseCase: ValidateVinUseCase,
    private val strings: ScannerViewModelStrings
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
            is ScannerEvent.UpdateVin -> onVinUpdated(event.vin)
            is ScannerEvent.UpdateRoiBorderState -> updateRoiBorderState(event.state)
        }
    }

    private fun startScanning() {
        if (!_state.value.hasPermission) {
            _state.update { it.copy(errorMessage = strings.permissionRequired) }
            return
        }

        _state.update { it.copy(isScanning = true, errorMessage = null) }
        SLog.d(TAG, "Starting VIN scanning")

        // Scanning logic will be implemented in the UI layer with CameraX
        // The ViewModel will receive detected VINs through events
    }

    private fun stopScanning() {
        recycleBitmapAsync(_state.value.latestRoiCroppedBitmap, "stop")

        _state.update {
            it.copy(
                isScanning = false,
                isLoading = false,
                latestRoiCroppedBitmap = null  // Clear reference
            )
        }
        SLog.d(TAG, "Stopped VIN scanning")
    }

    private fun updatePermissionStatus(granted: Boolean) {
        _state.update { it.copy(hasPermission = granted) }
        if (granted) {
            startScanning()
        } else {
            _state.update { it.copy(errorMessage = strings.permissionRequiredForScanning) }
        }
    }

    private fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun dismissResult() {
        recycleBitmapAsync(_state.value.latestRoiCroppedBitmap, "dismiss")

        _state.update {
            it.copy(
                showVinResult = false,
                detectedVin = null,
                roiBorderState = RoiBorderState.NO_DETECTION,
                latestRoiCroppedBitmap = null  // Clear reference
            )
        }
    }

    private fun retryScanning() {
        dismissResult()
        startScanning()
    }

    private fun onVinUpdated(vin: String) {
        viewModelScope.launch {
            if (_state.value.detectedVin?.value == vin) return@launch

            val validatedVin = withContext(Dispatchers.Default) {
                validateVinUseCase(vin)
            }
            _state.update {
                it.copy(
                    detectedVin = validatedVin.copy(confidence = it.detectedVin?.confidence ?: 0f)
                )
            }
        }
    }


    fun onVinDetected(vin: String, confidence: Float, croppedBitmap: Bitmap?) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            try {
                // Validate the VIN
                val validatedVin = withContext(Dispatchers.Default) {
                    validateVinUseCase(vin)
                }

                // Update state with the result, including the cropped bitmap
                _state.update { currentState ->
                    currentState.copy(
                        isLoading = false,
                        detectedVin = validatedVin.copy(
                            confidence = confidence,
                            croppedImage = croppedBitmap
                        ),
                        showVinResult = true,
                        scanHistory = currentState.scanHistory + validatedVin
                    )
                }

                // Stop scanning after successful detection
                stopScanning()

                SLog.d(TAG, "VIN detected and validated: ${validatedVin.value} (valid: ${validatedVin.isValid}), cropped bitmap: ${croppedBitmap != null}")
            } catch (e: Exception) {
                SLog.e(TAG, "Error validating VIN", e)
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = strings.errorValidatingVin(e.message.orEmpty())
                    )
                }
            }
        }
    }

    fun onDetectionBoxesUpdated(boxes: List<com.syarah.vinscanner.domain.model.BoundingBox>) {
        _state.update { it.copy(detectionBoxes = boxes) }
    }

    /**
     * Updates the latest ROI-cropped bitmap for manual entry
     * Recycles the old bitmap to prevent memory leaks
     */
    fun onRoiCroppedBitmapUpdated(newBitmap: Bitmap?) {
        val oldBitmap = _state.value.latestRoiCroppedBitmap

        _state.update { it.copy(latestRoiCroppedBitmap = newBitmap) }

        if (oldBitmap !== newBitmap) {
            recycleBitmapAsync(oldBitmap, "replace")
        }
    }

    private fun updateRoiBorderState(state: RoiBorderState) {
        _state.update { it.copy(roiBorderState = state) }
    }

    /**
     * Clean up bitmap when ViewModel is destroyed
     */
    override fun onCleared() {
        super.onCleared()
        recycleBitmapAsync(_state.value.latestRoiCroppedBitmap, "cleared")
    }

    private fun recycleBitmapAsync(bitmap: Bitmap?, reason: String) {
        if (bitmap == null) return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                    SLog.d(TAG, "Recycled ROI bitmap on $reason")
                }
            } catch (e: Throwable) {
                SLog.w(TAG, "Failed to recycle bitmap on $reason", e)
            }
        }
    }
}

/**
 * Events that can be triggered from the UI
 */
internal sealed class ScannerEvent {
    object StartScanning : ScannerEvent()
    object StopScanning : ScannerEvent()
    object PermissionGranted : ScannerEvent()
    object PermissionDenied : ScannerEvent()
    object DismissError : ScannerEvent()
    object DismissResult : ScannerEvent()
    object RetryScanning : ScannerEvent()
    data class UpdateVin(val vin: String) : ScannerEvent()
    data class UpdateRoiBorderState(val state: RoiBorderState) : ScannerEvent()
}
