# ViewModel & State

Files: `presentation/scanner/ScannerViewModel.kt`, `ScannerState.kt`, `ScannerViewModelFactory.kt`, `ScannerViewModelStrings.kt`

## `ScannerState`

Immutable data class. All UI state in one place.

```kotlin
data class ScannerState(
    val isScanning: Boolean = false,         // camera analyzing frames
    val isLoading: Boolean = false,          // validation in progress
    val detectedVin: VinNumber? = null,      // non-null → LaunchedEffect fires onVinConfirmed
    val detectionBoxes: List<BoundingBox>,   // rendered by BoundingBoxOverlay
    val errorMessage: String? = null,
    val hasPermission: Boolean = false,
    val showVinResult: Boolean = false,      // legacy field; bottom sheet removed
    val scanHistory: List<VinNumber>,
    val roiBorderState: RoiBorderState,      // drives RoiOverlay border color
    val latestRoiCroppedBitmap: Bitmap?      // for manual entry button
)
val isProcessing: Boolean get() = isScanning && isLoading
```

## `RoiBorderState` (enum)

| Value | Color token | Condition |
|-------|-------------|-----------|
| `NO_DETECTION` | `RoiInvalidBorder` (red) | Default; no boxes in current frame |
| `NEUTRAL` | `RoiValidBorder` (green — reused as white on this branch) | Boxes detected |
| `VALID_VIN_DETECTED` | `RoiValidBorder` (green) | Valid VIN confirmed |

## `ScannerEvent` (sealed class)

MVI-style. All mutations go through `onEvent(event)`:

| Event | Effect |
|-------|--------|
| `StartScanning` | sets `isScanning=true` if permission granted |
| `StopScanning` | sets `isScanning=false`, recycles ROI bitmap |
| `PermissionGranted` | sets `hasPermission=true`, calls `startScanning()` |
| `PermissionDenied` | sets error message |
| `DismissError` | clears `errorMessage` |
| `DismissResult` | clears `detectedVin`, ROI bitmap, border state |
| `RetryScanning` | `dismissResult()` then `startScanning()` |
| `UpdateVin(vin)` | re-validates, updates `detectedVin.value` |
| `UpdateRoiBorderState(state)` | updates `roiBorderState` |

## `ScannerViewModel`

### `onVinDetected(vin, confidence, croppedBitmap)`

Called from `ScannerScreen.processImage()` when a valid VIN is found:
1. Validates on `Dispatchers.Default` → `VinNumber(value, isValid, confidence, croppedImage=bitmap)`
2. Updates state: `detectedVin=vinNumber`, `showVinResult=true`, appends to `scanHistory`
3. Calls `stopScanning()` — scanning stops immediately

Setting `detectedVin` triggers the `LaunchedEffect` in `ScannerScreen` to call `onVinConfirmed()`.

### `onRoiCroppedBitmapUpdated(newBitmap)`

Replaces `latestRoiCroppedBitmap` in state and recycles the old bitmap asynchronously. Called every processed frame with the latest ROI crop. Prevents memory leaks from the continuous stream of new bitmaps.

### Bitmap lifecycle

Three `recycleBitmapAsync` call sites:
1. `stopScanning()` — recycles when user/auto stops
2. `dismissResult()` — recycles when result is cleared
3. `onCleared()` — recycles on ViewModel destruction

`recycleBitmapAsync` checks `!bitmap.isRecycled` before calling `recycle()` to avoid double-free exceptions.

### `onVinUpdated(vin)` (via `UpdateVin` event)

Skips if the VIN value hasn't changed (guards against recomposition loops). Re-validates on `Dispatchers.Default`, preserves original `confidence`.

## `ScannerViewModelFactory`

`ViewModelProvider.Factory` that delegates to `VinScannerDependencies.get().createScannerViewModel()`. Required because `ScannerViewModel` has a non-empty constructor.

## `ScannerViewModelStrings`

Resolves localized strings from `Context` at ViewModel creation time. Stored as a data class so ViewModel logic has no `Context` dependency after construction. Enables unit testing with fake strings.

```kotlin
ScannerViewModelStrings(
    permissionRequired = context.getString(R.string.error_camera_permission_required),
    permissionRequiredForScanning = ...,
    errorValidatingVin = { detail -> context.getString(R.string.error_validating_vin, detail) }
)
```
