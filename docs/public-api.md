# Public API

## `VinScanner` (object) — `VinScanner.kt`

The only public symbol the host app needs.

```kotlin
// Typical usage
val launcher = registerForActivityResult(VinScanner.Contract()) { result ->
    when (result) {
        is VinScanResult.Success   -> result.vinNumber
        is VinScanResult.Cancelled -> { }
        is VinScanResult.Error     -> result.message
    }
}
launcher.launch(Unit)

// Optional: match the host app's fonts
VinScanner.setTypography(AppTypography)   // call once at app startup
VinScanner.clearTypographyOverride()       // revert to library default
```

`typographyOverride: Typography?` is `@Volatile` and read by `SyaravinTheme` on every recomposition.

`VERSION = "1.3.0"` — update this when bumping `build.gradle.kts` version.

## `VinScannerContract` — `VinScannerContract.kt`

`ActivityResultContract<Unit, VinScanResult>`

- `createIntent` → `Intent(context, VinScannerActivity::class.java)`
- `parseResult(RESULT_OK, intent)` → reads `EXTRA_VIN_RESULT` parcelable → `VinScanResult.Success`
- `parseResult(RESULT_CANCELED, _)` → `VinScanResult.Cancelled`
- anything else → `VinScanResult.Error("Unknown error")`

Uses `IntentCompat.getParcelableExtra` (backward-compat API for `VinNumber` parcelable).

## `VinScanResult` — `VinScanResult.kt`

```kotlin
sealed class VinScanResult {
    data class Success(val vinNumber: VinNumber) : VinScanResult()
    object Cancelled : VinScanResult()
    data class Error(val message: String) : VinScanResult()
}
```

`vinNumber.croppedImageUri` is a cache-backed `content://` URI for the enhanced VIN plate region. It may be non-null even when `vinNumber.value` is empty (manual entry button tap). Consume or copy it promptly because cache files may be evicted.

## `VinScannerActivity` (internal) — `VinScannerActivity.kt`

Not part of the public API but the pivot of the flow:

1. `onCreate`: calls `VinScannerDependencies.initialize(applicationContext)` then `setContent { SyaravinTheme { ScannerScreen(...) } }`
2. `onVinConfirmed(vinNumber)`: puts `VinNumber` parcelable into `RESULT_OK` intent, calls `finish()`
3. `onCancelled`: `setResult(RESULT_CANCELED)`, `finish()`
4. `onDestroy`: logs only (cleanup handled by Compose disposables and ViewModel `onCleared`)

Manifest: `exported=false`, `screenOrientation=portrait`, `hardwareAccelerated=true`, `Theme.Syaravin` (NoActionBar).

## `VinNumber` — `domain/model/VinNumber.kt`

The primary result payload. Parcelable.

| Field | Meaning |
|-------|---------|
| `value` | 17-char VIN; empty string for manual-entry taps |
| `confidence` | TFLite detection confidence (0–1) |
| `isValid` | passed `VinValidatorImpl` checks |
| `croppedImage` | in-process scanner bitmap; omitted from Activity result parcels |
| `croppedImageUri` | bounded cache-backed `content://` result image URI |

Constants: `VIN_LENGTH=17`, `INVALID_CHARACTERS={I,O,Q,i,o,q}`, `VALID_PATTERN=Regex("[A-HJ-NPR-Z0-9]{17}", IGNORE_CASE)`.
