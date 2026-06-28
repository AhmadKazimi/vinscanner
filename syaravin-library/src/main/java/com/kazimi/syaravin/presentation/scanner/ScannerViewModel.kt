package com.kazimi.syaravin.presentation.scanner

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kazimi.syaravin.data.datasource.validator.VinValidator
import com.kazimi.syaravin.domain.model.VinNumber
import com.kazimi.syaravin.util.LogTags
import com.kazimi.syaravin.util.SLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = LogTags.LIBRARY

// Boxes must be absent this long before the ROI border flips to the red "no detection" state.
private const val NO_DETECTION_DEBOUNCE_MS = 450L

/**
 * ViewModel for the scanner screen
 */
internal class ScannerViewModel(
    private val vinValidator: VinValidator,
    private val strings: ScannerViewModelStrings,
) : ViewModel() {
    private val _state = MutableStateFlow(ScannerState())
    val state: StateFlow<ScannerState> = _state.asStateFlow()

    // Set once a VIN is auto-detected, so repeat detections from later frames are ignored.
    private val vinCaptured = AtomicBoolean(false)

    // Last time detection boxes were present, for ROI-border hysteresis (anti-thrash).
    private var lastBoxesSeenMs = 0L

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

        SLog.w(TAG, "startScanning invoked")
        _state.update { it.copy(isScanning = true, errorMessage = null) }

        // Scanning logic will be implemented in the UI layer with CameraX
        // The ViewModel will receive detected VINs through events
    }

    private fun stopScanning() {
        recycleBitmapAsync(_state.value.latestRoiCroppedBitmap, "stop")

        _state.update {
            it.copy(
                isScanning = false,
                isLoading = false,
                scanGuidance = ScanGuidance.NONE,
                latestRoiCroppedBitmap = null, // Clear reference
            )
        }
        SLog.d(TAG, "Stopped VIN scanning")
    }

    private fun updatePermissionStatus(granted: Boolean) {
        if (granted) {
            SLog.w(TAG, "PermissionGranted event handled")
        }
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

    /** Surface a transient error in the scanner snackbar (e.g. a manual capture that read no VIN). */
    fun showError(message: String) {
        _state.update { it.copy(errorMessage = message) }
    }

    private fun dismissResult() {
        recycleBitmapAsync(_state.value.latestRoiCroppedBitmap, "dismiss")

        _state.update {
            it.copy(
                showVinResult = false,
                detectedVin = null,
                roiBorderState = RoiBorderState.NO_DETECTION,
                scanGuidance = ScanGuidance.NONE,
                latestRoiCroppedBitmap = null, // Clear reference
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

            val validatedVin =
                withContext(Dispatchers.Default) {
                    val validationResult = vinValidator.validate(vin)
                    VinNumber(value = vin, isValid = validationResult.isValid)
                }
            _state.update {
                it.copy(
                    detectedVin = validatedVin.copy(confidence = it.detectedVin?.confidence ?: 0f),
                )
            }
        }
    }

    fun onVinDetected(
        vin: String,
        confidence: Float,
        croppedBitmap: Bitmap?,
    ) {
        // One-shot: ignore further detections once a VIN is captured, otherwise later analysis
        // frames re-trigger the result (and the success chime) before the screen closes.
        // Synchronous (atomic) guard — detection updates state asynchronously, so checking the
        // state here would race between two rapid frames.
        if (!vinCaptured.compareAndSet(false, true)) {
            croppedBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            try {
                // Validate the VIN
                val validatedVin =
                    withContext(Dispatchers.Default) {
                        val validationResult = vinValidator.validate(vin)
                        VinNumber(value = vin, isValid = validationResult.isValid)
                    }

                // Update state with the result, including the cropped bitmap
                _state.update { currentState ->
                    currentState.copy(
                        isLoading = false,
                        detectedVin =
                            validatedVin.copy(
                                confidence = confidence,
                                croppedImage = croppedBitmap,
                            ),
                        showVinResult = true,
                        scanHistory = currentState.scanHistory + validatedVin,
                    )
                }

                // Stop scanning after successful detection
                stopScanning()

                SLog.d(
                    TAG,
                    "VIN detected and validated: ${validatedVin.value} (valid: ${validatedVin.isValid}), cropped bitmap: ${croppedBitmap != null}",
                )
            } catch (e: Exception) {
                SLog.e(TAG, "Error validating VIN", e)
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = strings.errorValidatingVin(e.message.orEmpty()),
                    )
                }
            }
        }
    }

    fun onDetectionBoxesUpdated(boxes: List<com.kazimi.syaravin.domain.model.BoundingBox>) {
        _state.update { it.copy(detectionBoxes = boxes) }
    }

    /**
     * Updates the live "possible VIN" candidate shown for feedback. Pass null to clear.
     */
    fun onCandidateScanned(
        value: String?,
        confidence: Float,
        isValid: Boolean,
    ) {
        _state.update {
            it.copy(
                scannedCandidate =
                    value
                        ?.takeIf { v -> v.isNotBlank() }
                        ?.let { v -> ScannedCandidate(v, confidence, isValid) },
            )
        }
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
        // Hysteresis: detection flickers frame-to-frame, so don't flash the red "no detection"
        // border on momentary gaps. Boxes-present (NEUTRAL/VALID) applies immediately and refreshes
        // the timer; NO_DETECTION only applies after boxes have been absent for the debounce window.
        when (state) {
            RoiBorderState.NEUTRAL, RoiBorderState.VALID_VIN_DETECTED -> {
                lastBoxesSeenMs = System.currentTimeMillis()
                _state.update { it.copy(roiBorderState = state) }
            }
            RoiBorderState.NO_DETECTION -> {
                if (System.currentTimeMillis() - lastBoxesSeenMs >= NO_DETECTION_DEBOUNCE_MS) {
                    _state.update { it.copy(roiBorderState = state) }
                }
            }
        }
    }

    fun onScanGuidanceChanged(guidance: ScanGuidance) {
        if (_state.value.scanGuidance == guidance) return
        _state.update { it.copy(scanGuidance = guidance) }
    }

    /**
     * Clean up bitmap when ViewModel is destroyed
     */
    override fun onCleared() {
        super.onCleared()
        recycleBitmapAsync(_state.value.latestRoiCroppedBitmap, "cleared")
    }

    private fun recycleBitmapAsync(
        bitmap: Bitmap?,
        reason: String,
    ) {
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

    data class UpdateVin(
        val vin: String,
    ) : ScannerEvent()

    data class UpdateRoiBorderState(
        val state: RoiBorderState,
    ) : ScannerEvent()
}
