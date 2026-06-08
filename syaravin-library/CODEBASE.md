# Syaravin Library — Full Codebase Reference

## Table of Contents

1. [Library Purpose & Deployment Model](#1-library-purpose--deployment-model)
2. [Directory Map](#2-directory-map)
3. [Public API (Entry Points)](#3-public-api-entry-points)
4. [Architecture Overview](#4-architecture-overview)
5. [Domain Layer](#5-domain-layer)
6. [Data Layer — Models](#6-data-layer--models)
7. [Data Layer — Camera Data Source](#7-data-layer--camera-data-source)
8. [Data Layer — ML: VIN Detector](#8-data-layer--ml-vin-detector)
9. [Data Layer — ML: Text Extractor](#9-data-layer--ml-text-extractor)
10. [Data Layer — Validator](#10-data-layer--validator)
11. [Data Layer — Repository](#11-data-layer--repository)
12. [Dependency Injection](#12-dependency-injection)
13. [Presentation Layer — State & ViewModel](#13-presentation-layer--state--viewmodel)
14. [Presentation Layer — ScannerScreen](#14-presentation-layer--scannerscreen)
15. [Presentation Layer — UI Components](#15-presentation-layer--ui-components)
16. [Theme](#16-theme)
17. [Utilities](#17-utilities)
18. [Localization](#18-localization)
19. [Tests](#19-tests)
20. [Build & Publishing](#20-build--publishing)
21. [Cross-Cutting Design Decisions](#21-cross-cutting-design-decisions)
22. [Full Data Flow Walk-through](#22-full-data-flow-walk-through)

---

## 1. Library Purpose & Deployment Model

`syaravin-library` is an Android AAR that provides a self-contained, turn-key VIN (Vehicle Identification Number) scanner. The host application adds a single dependency and launches the scanner with `registerForActivityResult`. No Koin, no Hilt, no Fragment knowledge is required.

The scanner:
1. Acquires camera permission by itself
2. Runs a YOLO object-detection model to find the VIN plate region
3. Runs ML Kit OCR inside that region
4. Validates and normalises the extracted text against ISO 3779 rules
5. Returns a `VinScanResult` (sealed class) back through the `ActivityResultContract`

Published coordinates: `com.syarah:vinscanner:1.3.0`  
Min SDK: 29 · Compile/Target SDK: 36 · JVM target: 21

---

## 2. Directory Map

```
syaravin-library/src/main/java/com/syarah/vinscanner/
├── VinScanner.kt                   ← public entry point (object)
├── VinScannerActivity.kt           ← internal host activity
├── VinScannerContract.kt           ← ActivityResultContract
├── VinScanResult.kt                ← sealed result type
│
├── domain/
│   ├── model/
│   │   ├── BoundingBox.kt          ← normalized detection coordinates
│   │   └── VinNumber.kt            ← domain entity (Parcelable)
│   ├── repository/
│   │   └── VinScannerRepository.kt ← repository interface
│   └── usecase/
│       ├── DetectVinUseCase.kt
│       ├── ExtractTextUseCase.kt
│       └── ValidateVinUseCase.kt
│
├── data/
│   ├── VinInfo.kt                  ← decoded manufacturer/country/year
│   ├── model/
│   │   ├── DetectionResult.kt
│   │   └── VinValidationResult.kt
│   ├── datasource/
│   │   ├── camera/
│   │   │   ├── CameraDataSource.kt
│   │   │   └── CameraDataSourceImpl.kt
│   │   ├── ml/
│   │   │   ├── VinDetector.kt
│   │   │   ├── VinDetectorImpl.kt  ← TFLite YOLO inference
│   │   │   ├── TextExtractor.kt
│   │   │   └── TextExtractorImpl.kt ← ML Kit OCR
│   │   └── validator/
│   │       ├── VinValidator.kt
│   │       └── VinValidatorImpl.kt ← full ISO 3779 pipeline
│   └── repository/
│       └── VinScannerRepositoryImpl.kt
│
├── di/
│   └── VinScannerDependencies.kt   ← manual DI (no Koin in library)
│
├── presentation/
│   ├── scanner/
│   │   ├── ScannerScreen.kt        ← main composable + frame loop
│   │   ├── ScannerState.kt
│   │   ├── ScannerViewModel.kt
│   │   ├── ScannerViewModelFactory.kt
│   │   └── ScannerViewModelStrings.kt
│   └── components/
│       ├── CameraPreview.kt
│       ├── RoiOverlay.kt
│       ├── BoundingBoxOverlay.kt
│       ├── VinResultDialog.kt
│       ├── VinEditBar.kt
│       ├── VinInputField.kt
│       └── VinTextField.kt
│
├── ui/theme/
│   ├── Color.kt
│   ├── Theme.kt
│   └── Type.kt
│
└── util/
    ├── Extensions.kt
    ├── ImagePreprocessor.kt
    ├── LogTags.kt
    ├── RoiConfig.kt
    ├── ScannerPerfConfig.kt
    ├── SLog.kt
    ├── ThermalManager.kt
    └── VinDecoder.kt
```

---

## 3. Public API (Entry Points)

### `VinScanner` (object)

The only public name the host app needs to know. Responsibilities:
- Provides `Contract()` which returns a `VinScannerContract`
- Holds an optional `typographyOverride: Typography?` — the host can call `setTypography(appTypography)` at app startup so the scanner's Compose UI uses the same typeface as the host app

```kotlin
val launcher = registerForActivityResult(VinScanner.Contract()) { result ->
    when (result) {
        is VinScanResult.Success  -> { /* result.vinNumber */ }
        is VinScanResult.Cancelled -> { }
        is VinScanResult.Error    -> { /* result.message */ }
    }
}
launcher.launch(Unit)
```

### `VinScannerContract`

`ActivityResultContract<Unit, VinScanResult>`:
- `createIntent` → `Intent` targeting `VinScannerActivity`
- `parseResult` → translates `RESULT_OK` + parcelable extra into `VinScanResult.Success`, `RESULT_CANCELED` into `Cancelled`, anything else into `Error`

### `VinScanResult`

Sealed class:
| Subtype | Fields | When |
|---------|--------|------|
| `Success` | `vinNumber: VinNumber` | Valid VIN returned |
| `Cancelled` | — | User pressed back |
| `Error` | `message: String` | Unexpected failure |

`VinNumber` is the domain entity (`VinNumber.kt`) — see §5.

### `VinScannerActivity` (internal)

Bootstraps the library when launched:
1. Calls `VinScannerDependencies.initialize(applicationContext)`
2. Calls `enableEdgeToEdge()`
3. Sets content to `SyaravinTheme { ScannerScreen(...) }`
4. `onVinConfirmed` callback puts the `VinNumber` parcelable in a result intent and calls `finish()`
5. `onCancelled` sets `RESULT_CANCELED` and calls `finish()`

Declared in the library's `AndroidManifest.xml` as `exported=false`, portrait-only, hardware-accelerated. The manifest also declares the `CAMERA` permission and the `com.google.mlkit.vision.DEPENDENCIES = ocr` meta-data so ML Kit auto-downloads its OCR model.

---

## 4. Architecture Overview

```
Host App
   │  registerForActivityResult(VinScanner.Contract())
   ▼
VinScannerActivity
   │  setContent { ScannerScreen }
   ▼
ScannerViewModel ◄──────────── ScannerState (StateFlow)
   │
   ├── VinValidator (validate + cleanVin)
   │
ScannerScreen (Compose)
   │
   ├── CameraPreview composable
   │       └── CameraX: ProcessCameraProvider, Preview, ImageAnalysis
   │
   ├── Frame analysis loop (every 500ms)
   │       1. CameraDataSource.imageToBitmap()
   │       2. Crop to ROI
   │       3. VinDetector.detect()          → TFLite YOLO
   │       4. TextExtractor.extractText()   → ML Kit OCR
   │       5. VinValidator.cleanVin/validate()
   │       6. onVinDetected → ScannerViewModel
   │
   └── UI Layers: RoiOverlay, BoundingBoxOverlay, result dialogs
```

Clean Architecture layers: **Domain** (interfaces + models) ← **Data** (implementations) ← **Presentation** (Compose UI + ViewModel). The DI object (`VinScannerDependencies`) wires everything.

---

## 5. Domain Layer

### `BoundingBox`

Internal data class. All coordinates are **normalized (0.0–1.0)** relative to the image dimensions.

| Property | Meaning |
|----------|---------|
| `left`, `top`, `right`, `bottom` | normalized edges |
| `confidence` | model confidence score |
| `width`, `height`, `centerX`, `centerY` | computed derived values |
| `toPixelCoordinates(w, h)` | scales to absolute pixels |

Rationale for normalization: the detection pipeline runs on different bitmap resolutions (ROI crop, full frame, model input). Keeping coordinates in `[0,1]` space means mapping functions only need to multiply by the current bitmap dimensions, rather than tracking which resolution each box was computed in.

### `VinNumber`

Domain entity, `Parcelable` (needed to survive `Intent` serialization through `VinScannerActivity`).

| Property | Meaning |
|----------|---------|
| `value` | 17-character VIN string (may be empty for manual entry) |
| `confidence` | detection confidence from TFLite model (0–1) |
| `isValid` | passed `VinValidatorImpl` checks |
| `croppedImage: Bitmap?` | cropped & enhanced bitmap of the detected VIN plate |

Companion constants:
- `VIN_LENGTH = 17`
- `INVALID_CHARACTERS = {I, O, Q, i, o, q}` — chars prohibited by ISO 3779
- `VALID_PATTERN = Regex("[A-HJ-NPR-Z0-9]{17}", IGNORE_CASE)`

### `VinScannerRepository` (interface)

```kotlin
suspend fun detectVinRegions(bitmap): List<BoundingBox>
suspend fun extractTextFromRegion(bitmap, boundingBox): String?
suspend fun validateVin(vin): VinNumber
fun startScanning(): Flow<VinNumber>
fun stopScanning()
```

### Use Cases

`DetectVinUseCase`, `ExtractTextUseCase`, `ValidateVinUseCase` are thin `operator fun invoke` wrappers that route calls through `Dispatchers.Default`. They exist to provide a clean domain boundary and ensure the data layer never runs on the main thread, regardless of caller context.

---

## 6. Data Layer — Models

### `DetectionResult`
Wraps `List<BoundingBox>` and `processingTimeMs`. Returned by `VinDetector.detect()`.

### `VinValidationResult`

| Field | Meaning |
|-------|---------|
| `isValid` | overall pass/fail (soft — checksum failure still passes) |
| `formatValid` | 17 chars, valid character set, ≥5 digits |
| `checksumValid` | ISO 3779 check digit correct |
| `errorMessage` | localized string describing failure |
| `wasTrimmed` | invalid leading/trailing characters were stripped |

### `VinInfo`
Result of `VinDecoder.decode()`: `manufacturer`, `country`, `modelYear`, `assemblyPlant`.

---

## 7. Data Layer — Camera Data Source

### `CameraDataSource` (interface)
- `startCamera(): Flow<ImageProxy>` — design-time interface; the actual CameraX binding lives in the Compose UI (`CameraPreview.kt`). The repository's `startScanning()` uses this interface, but `ScannerScreen` bypasses the repository entirely and calls `imageToBitmap` directly.
- `stopCamera()` — stub; lifecycle managed by CameraX.
- `imageToBitmap(imageProxy): Bitmap` — the live method.

### `CameraDataSourceImpl`

**`imageToBitmap`** supports `YUV_420_888`, `NV21`, `NV16` formats.

**Primary path — Direct YUV→RGB** (`convertYuvToBitmapDirect`):
1. Reads separate Y, U, V planes from `ImageProxy.planes[]`
2. Applies ITU-R BT.601 conversion per pixel:
   ```
   R = clamp(1.164·(Y-16) + 1.596·(V-128))
   G = clamp(1.164·(Y-16) - 0.392·(U-128) - 0.813·(V-128))
   B = clamp(1.164·(Y-16) + 2.017·(U-128))
   ```
3. Handles `pixelStride` and `rowStride` correctly for packed/planar UV formats
4. Applies rotation via `Matrix.postRotate(rotationDegrees)`

Rationale for direct conversion over JPEG: JPEG compression introduces blocking artifacts at text edges. The TFLite YOLO model and ML Kit OCR are both sensitive to this — blurry letter boundaries cause missed detections and garbled OCR. Direct conversion preserves full signal quality at the cost of a per-pixel CPU loop.

**Fallback path — JPEG** (`convertYuvToBitmapViaJpeg`): Used only if direct conversion throws. Constructs NV21 byte array → `YuvImage.compressToJpeg(85%)` → `BitmapFactory.decodeByteArray`. Still applies rotation.

Performance logging via `ThrottledDurationLogger` (every 30 frames).

---

## 8. Data Layer — ML: VIN Detector

### `VinDetector` (interface)
- `detect(bitmap, confidenceThreshold = 0.25f): DetectionResult`
- `preprocessImage(bitmap): Bitmap`

### `VinDetectorImpl`

Takes an injected `Interpreter` (TFLite). Uses model file `best_float32.tflite` (640×640 single-class YOLO-style detector). Stateless after construction.

#### Preprocessing (`preprocessImage`)
Letterbox scaling: scale the bitmap uniformly so the larger dimension fits in 640 pixels, then center-pad with black to fill 640×640. This preserves aspect ratio — without letterboxing, a portrait frame would be squashed and bounding box coordinates would be wrong.

```
scaleFactor = min(640/w, 640/h)
padLeft = (640 - scaledW) / 2
padTop  = (640 - scaledH) / 2
```

The padding offsets (`padLeft`, `padTop`) are saved as locals inside `detect()` so bounding boxes can be un-letterboxed after inference.

#### Input Buffer
Pre-allocated `ByteBuffer` of `640 × 640 × 3 × 4` bytes (RGB float32) allocated once in the constructor. `convertBitmapToByteBuffer` normalizes each pixel component to `[0, 1]` by dividing by 255.

#### Inference & Output Parsing
The model output tensor can be in two orientations:
- `[1, 8400, 6]` — candidates-first: `[batch, numCandidates, properties]`
- `[1, 6, 8400]` — properties-first: `[batch, properties, numCandidates]`

`properties` can be 5 (cx, cy, w, h, confidence) or 6+ (+ class scores). The code detects orientation at runtime by checking which dimension is in the known-properties set `{5, 6, 84, 85}`. A `getProp(candidate, prop)` lambda then provides uniform access.

Per-candidate scoring:
```
conf = objectness × max_class_score
```

Boxes passing the confidence threshold are un-letterboxed:
```
leftContent  = (leftPxModel  - padLeft) / scaledWidth   [clamped 0..1]
topContent   = (topPxModel   - padTop)  / scaledHeight
```

#### NMS
Greedy Non-Maximum Suppression sorted by descending confidence, IoU threshold 0.45. Removes duplicate detections of the same VIN plate.

#### Warmup
On first `detect()` call, runs 3 dummy inference passes (`maybeWarmupInterpreter`) to initialize GPU shader compilation and JIT paths, preventing first-frame latency spikes. Uses `AtomicBoolean` for thread-safe once-only execution.

#### Output Buffer Caching
`getOrCreateOutputBuffer` caches the allocated `Array<Array<FloatArray>>` and only reallocates if tensor dimensions change. This avoids GC pressure on every inference.

---

## 9. Data Layer — ML: Text Extractor

### `TextExtractor` (interface)
```kotlin
suspend fun extractText(bitmap, boundingBox): String?
suspend fun extractAllText(bitmap): List<String>
suspend fun extractAllTextWithBounds(bitmap): List<TextWithBounds>
```

`TextWithBounds` pairs a text string with its normalized `BoundingBox`.

### `TextExtractorImpl`

Uses `ML Kit TextRecognition` (on-device, Latin script). Implements `Closeable` to shut down the recognizer and coroutine scope.

#### Google Play Services Guard
ML Kit requires Google Play Services. At construction the class checks:
- `GoogleApiAvailability.isGooglePlayServicesAvailable()` returns `SUCCESS`
- `Class.forName("com.google.mlkit.vision.text.TextRecognition")` succeeds

If either fails, `skipOcrForSession = true` and a background `ModuleInstall` request is issued via `ModuleInstall.getClient(context).installModules(...)`. On subsequent frames the guard re-checks and re-enables OCR if Play Services became ready. This handles the case of a fresh device install where GMS modules haven't synced yet.

#### `extractText` (single region)
1. Converts `BoundingBox` → pixel `Rect` via `toPixelRect()`
2. Ensures minimum 32×32 pixels via `ensureMinimumSize()` (ML Kit rejects smaller images). Expands equally from all sides while clamping to bitmap bounds.
3. Detects rotation via `detectRotation()` heuristic: wide aspect ratio (>1.5) → 0°; tall portrait (<0.67) → 270°
4. Wraps in `InputImage.fromBitmap(cropped, rotationDegrees)`
5. Calls `recogniser.process(image).await()` (suspends on ML Kit's Task)
6. Returns `result.text` if non-blank

#### `extractAllText` / `extractAllTextWithBounds`
Same flow but on the full bitmap. `extractAllText` returns `List<String>` (one per line). `extractAllTextWithBounds` additionally normalizes the line's `Rect` into a `BoundingBox`.

---

## 10. Data Layer — Validator

### `VinValidator` (interface)
```kotlin
fun validate(vin: String): VinValidationResult
fun cleanVin(vin: String): String
```

Both are **synchronous** (no `suspend`). Validation is pure computation over strings.

### `VinValidatorImpl`

The most complex component. Implements a multi-stage pipeline designed around the reality of OCR output: text will have noise, confusable characters, embedded labels, and occasional punctuation.

#### Stage 1 — Strip Leading VIN Label (`stripLeadingVinLabel`)
Regex: `(?i)^\s*VIN(?:\s*(?:NUMBER|NO|#))?\s*[:#=–—\-]?\s*`

Removes prefixes like `"VIN:"`, `"VIN NUMBER:"`, `"vin no -"`, `"VIN#"` before applying character corrections. If corrections ran first, the `V`, `I`, `N` characters might get transformed (e.g., `I → 1`), breaking the label pattern.

#### Stage 2 — OCR Error Correction (`correctOcrErrors`)
Maps each character through `OCR_CORRECTIONS`:
```
I/i → 1   (capital I and lowercase i both look like 1)
O/o → 0   (O looks like 0)
Q/q → 0   (Q looks like 0)
l   → 1   (lowercase L looks like 1)
|   → 1   (pipe looks like 1)
!   → 1
Ø   → 0
°   → 0
lowercase a-z (except i, o, q, l) → uppercase equivalents
```

#### Stage 3 — Extraction (`extractVin`) → `Pair<String?, Boolean>`
1. `trim().uppercase()` then strip any remaining VIN label prefix
2. `dropWhile { not alphanumeric }` from the start
3. `dropLastWhile { not alphanumeric }` from the end — tracks `wasTrimmed = (normalized != trimmedBoth)`
4. If any character in the trimmed result is still not `A-Z` or `0-9` (i.e. punctuation in the middle like `"ERA:PPSNAE234439G161"`), return `null`. Middle-invalid characters cannot be part of a real VIN.
5. Apply `[A-HJ-NPR-Z0-9]{17}` regex to find the first 17-char VIN sequence (excludes I, O, Q by character class)

#### Stage 4 — Length Check
If extracted string ≠ 17 characters → fail with `validation_wrong_length`.

#### Stage 5 — Invalid Character Check
If `I`, `O`, or `Q` survived (shouldn't after Stage 2 but defensive) → fail.

#### Stage 6 — Digit Count Heuristic
VINs must have at least 5 digits. A string like `"ABCDEFGHJKLMNPRS1"` (only 1 digit) is almost certainly misread text, not a real VIN. Threshold of 5 was chosen empirically.

#### Stage 7 — ISO 3779 Checksum with Permutations
**Standard checksum:**
- `TRANSLITERATION` table maps each alphanumeric character to its numeric value (A=1, B=2, … H=8, J=1, …, 0=0, …, 9=9 — note I/O/Q are absent from the table)
- `WEIGHTS = [8,7,6,5,4,3,2,10,0,9,8,7,6,5,4,3,2]` — weight for each position (position 8, the check digit position, has weight 0 so it contributes nothing to the sum)
- `sum = Σ(value[i] × weight[i])`
- `remainder = sum % 11`; expected check digit = `'X'` if remainder=10, else `char(remainder)`

**Permutation tolerance (up to 1 swap):**
OCR commonly misreads `S↔5`, `Z↔2`, `B↔8`, `A↔4`, `G↔6`. The validator tries all single-position swaps using BFS over `AMBIGUOUS_CHARS`. The BFS is bounded to `maxChanges = 1` and uses a `seenVins` set to prevent re-visiting. This recovers ~90% of single-character OCR errors.

**Soft validation:** If even permutation search fails, the validator returns `isValid=true, checksumValid=false`. The UI can still show the result — the user may correct it manually. The rationale: a real-world VIN plate that passes format+digit checks but fails checksum is more likely an OCR transcription error than a fabricated number.

#### `cleanVin`
Runs the same pipeline (stages 1–3) but only returns the extracted VIN string or `""` on failure. Used by `ScannerScreen` to quickly normalize the OCR output before calling `validate`.

---

## 11. Data Layer — Repository

### `VinScannerRepositoryImpl`

Coordinates the four data sources. The `startScanning()` flow implementation:
```
cameraDataSource.startCamera()
  .map { imageProxy ->
      bitmap = imageToBitmap(imageProxy)
      detections = vinDetector.detect(bitmap)
      for (box in detections) {
          text = textExtractor.extractText(bitmap, box)
          vin  = vinValidator.cleanVin(text)
          if (vin.length in 15..19) emit(VinNumber(...))
      }
      bitmap.recycle()
  }.collect {}
```

**Note:** `startCamera()` in `CameraDataSourceImpl` returns an empty `callbackFlow` (stub). This means the repository's `startScanning()` flow never emits. In practice, `ScannerScreen` drives the frame loop directly via `ImageAnalysis.setAnalyzer()`, bypassing the repository. The repository API exists as a clean-architecture seam for future refactoring (e.g., moving camera logic entirely out of Compose).

---

## 12. Dependency Injection

### `VinScannerDependencies` (object)

Thread-safe singleton factory using double-checked locking:

```kotlin
@Volatile private var instance: DependencyContainer? = null

fun initialize(appContext: Context) {
    if (instance == null) {
        synchronized(this) {
            if (instance == null) { instance = DependencyContainer(appContext.applicationContext) }
        }
    }
}
```

Called from `VinScannerActivity.onCreate`. Subsequent calls are no-ops.

**Why not Koin?** The library intentionally avoids Koin (used in the sample app). A library that injects into the host app's Koin context would pollute the host's DI graph and force a Koin dependency on users who use Hilt or Dagger.

### `DependencyContainer`

**Lazy singletons** (expensive, created once):

| Name | Type | Notes |
|------|------|-------|
| `interpreter` | `Interpreter` | Loads `best_float32.tflite`, tries GPU → NNAPI → CPU delegate |
| `vinDetector` | `VinDetectorImpl` | Wraps interpreter |
| `textExtractor` | `TextExtractorImpl` | Creates ML Kit recognizer |
| `vinValidator` | `VinValidatorImpl` | Stateless validator |
| `vinDecoder` | `VinDecoder` | Loads `vin_data.json` |
| `cameraDataSource` | `CameraDataSourceImpl` | Stateless converter |

**TFLite interpreter setup:**
1. Loads model as memory-mapped `ByteBuffer` from assets
2. Reads `ScannerPerfConfig.delegateMode` (default `"gpu"`) from system property `syaravin.perf.tflite.delegate`
3. GPU path: checks `CompatibilityList.isDelegateSupportedOnThisDevice`, uses `bestOptionsForThisDevice`
4. NNAPI path: `NnApiDelegate()`
5. CPU/XNNPack path: falls through, uses `setNumThreads` + `setUseXNNPACK`
6. Calls `allocateTensors()` to finalize

**Factory methods** (new instance per call):
- `createExecutor()` → `Executors.newSingleThreadExecutor()`
- `createCameraSelector()` → `CameraSelector.DEFAULT_BACK_CAMERA`
- `createPreview()` → `Preview.Builder().build()`
- `createImageAnalysis()` → configured with resolution `540×960`, `STRATEGY_KEEP_ONLY_LATEST` backpressure, `ROTATION_0` target
- `createRepository()` → `VinScannerRepositoryImpl(...)` wiring all singletons
- `createScannerViewModel()` → `ScannerViewModel(vinValidator, strings)`

**`warmUpScannerDependencies()`:** Accesses `vinDetector`, `textExtractor`, `vinValidator`, `cameraDataSource` lazy properties to force their initialization on `Dispatchers.Default`. Called from `ScannerScreen` via `LaunchedEffect` before the first frame is analyzed, preventing first-frame GC pressure.

---

## 13. Presentation Layer — State & ViewModel

### `RoiBorderState` (enum)
| Value | Color | Meaning |
|-------|-------|---------|
| `NO_DETECTION` | Red `#F75555` | Default; no boxes found in current frame |
| `NEUTRAL` | White `#FFFFFF` | Boxes detected, scanning in progress |
| `VALID_VIN_DETECTED` | Green `#4AAF57` | Valid VIN confirmed |

### `ScannerState` (data class)

Immutable snapshot. All UI state in one place for predictable recomposition.

```kotlin
data class ScannerState(
    val isScanning: Boolean = false,        // camera analyzing frames
    val isLoading: Boolean = false,         // validation in progress
    val detectedVin: VinNumber? = null,     // when non-null → auto-confirm
    val detectionBoxes: List<BoundingBox>,  // for overlay rendering
    val errorMessage: String? = null,
    val hasPermission: Boolean = false,
    val showVinResult: Boolean = false,     // legacy (bottom sheet removed)
    val scanHistory: List<VinNumber>,
    val roiBorderState: RoiBorderState,
    val latestRoiCroppedBitmap: Bitmap?     // for manual entry fallback
)
val isProcessing: Boolean get() = isScanning && isLoading
```

### `ScannerEvent` (sealed class)

All state mutations happen through events (MVI pattern):
`StartScanning`, `StopScanning`, `PermissionGranted`, `PermissionDenied`, `DismissError`, `DismissResult`, `RetryScanning`, `UpdateVin(vin)`, `UpdateRoiBorderState(state)`

### `ScannerViewModel`

Key behaviors:

**`updatePermissionStatus(granted)`** — on granted, immediately calls `startScanning()`. This creates a smooth startup path: permission request → granted callback → scanning starts without a user action.

**`onVinDetected(vin, confidence, croppedBitmap)`** — validates on `Dispatchers.Default`, updates state with `VinNumber(croppedImage = croppedBitmap)`, sets `showVinResult = true`, stops scanning. The `detectedVin` state update is what triggers the `LaunchedEffect` in `ScannerScreen` to auto-confirm.

**`onRoiCroppedBitmapUpdated(newBitmap)`** — replaces the stored ROI bitmap, recycling the old one asynchronously on `Dispatchers.Default`. Prevents memory leaks from the continuous stream of new bitmaps.

**`recycleBitmapAsync`** — safe bitmap recycling helper. Checks `!bitmap.isRecycled` before calling `recycle()` to avoid exceptions from double-free.

**`onCleared()`** — overridden to recycle the last stored `latestRoiCroppedBitmap` when the ViewModel is destroyed (Activity finish).

### `ScannerViewModelStrings`

Decouples localized string resolution from ViewModel logic. Constructed from `Context` at ViewModel creation time, stored as a data class. Allows ViewModel unit tests that don't need an Android Context (pass fake strings).

### `ScannerViewModelFactory`

`ViewModelProvider.Factory` that calls `VinScannerDependencies.get().createScannerViewModel()`. Required because `ScannerViewModel` has a non-empty constructor; the default `ViewModelProvider` can't create it.

---

## 14. Presentation Layer — ScannerScreen

The main composable. All camera lifecycle and frame processing logic lives here.

### Initialization Sequence

```
1. viewModel = viewModel(factory = ScannerViewModelFactory())
2. dependencies = VinScannerDependencies.get()
3. Create per-screen objects: cameraSelector, preview, imageAnalysis, executor
4. Create lazy references to heavy singletons (cameraDataSourceLazy, etc.)
5. processingScope = CoroutineScope(SupervisorJob + Dispatchers.Default)
6. LaunchedEffect: warmUpScannerDependencies() on background thread
7. LaunchedEffect: check/request camera permission
8. DisposableEffect: on dispose → clearAnalyzer, cancel scope, executor.shutdownNow()
```

### Frame Analysis Loop

`DisposableEffect(state.isScanning, isWarmupComplete)` — only activates when both scanning is enabled AND warmup is complete:

```kotlin
imageAnalysis.setAnalyzer(executor) { imageProxy ->
    val now = currentTimeMillis()
    if (now - lastProcessTime >= inferenceIntervalMs (500ms)
        && isProcessingFrame.compareAndSet(false, true)) {
        lastProcessTime = now
        processingScope.launch {
            try {
                processImage(...)
            } finally {
                isProcessingFrame.set(false)
            }
        }
    } else {
        frameTiming.onFrameDropped()
        imageProxy.close()
    }
}
```

Rate limiting via `AtomicLong` timestamp + `AtomicBoolean` lock: at most one frame in-flight, at most 2 frames per second (configurable). Frames arriving while processing is in progress are dropped (not queued). This prevents backpressure from accumulating stale frames.

### `processImage` (suspend function)

Stage-by-stage with nanosecond timing:

1. **`imageToBitmap(imageProxy)`** — converts camera frame to `Bitmap`
2. **ROI crop** — computes pixel coordinates from `RoiConfig.roi` (normalized), crops `Bitmap`. Saves a `downscaleForDisplay()` copy of the ROI for the manual entry button.
3. **`vinDetector.detect(roiBitmap)`** — runs YOLO on the cropped ROI
4. **Map boxes to full-frame coordinates** — since detection ran on the ROI crop, boxes need to be re-scaled and offset back to full-frame normalized space:
   ```
   fullLeft = roi.left + box.left × (roi.right - roi.left)
   fullTop  = roi.top  + box.top  × (roi.bottom - roi.top)
   ```
5. **Update ROI border state** — `NO_DETECTION` if no boxes, else `NEUTRAL`
6. **OCR loop** (sorted by descending confidence):
   ```kotlin
   for (box in boxes.sortedByDescending { it.confidence }) {
       text = textExtractor.extractText(roiBitmap, box)
       candidate = vinValidator.cleanVin(text)
       validation = vinValidator.validate(candidate)
       if (validation.isValid) {
           bestVin = candidate
           croppedVinBitmap = ImagePreprocessor.cropAndEnhance(roiBitmap, box)
           break
       }
   }
   ```
7. **Fallback to full-image OCR** (currently disabled by comment — relies on AI detection only)
8. **`onVinDetected(bestVin, confidence, croppedBitmap)`** if found; sets border to `VALID_VIN_DETECTED`
9. Log per-stage timing via `ScannerPerfConfig.frameTiming`
10. `imageProxy.close()` in `finally`

### Auto-Confirm

```kotlin
LaunchedEffect(state.detectedVin) {
    state.detectedVin?.let { onVinConfirmed(it) }
}
```

When `ScannerViewModel.onVinDetected` sets `detectedVin` in state, this effect fires immediately (no user tap required). The bottom sheet flow was removed — the scanner auto-returns the result as soon as a valid VIN is detected.

### Manual Entry Button

A circular white button at the bottom (camera shutter aesthetic). When tapped:
- If `latestRoiCroppedBitmap` is available: passes `VinNumber(value="", croppedImage=roiBitmap)` to `onVinConfirmed`. The host app can then show a manual input UI pre-populated with the cropped image as a visual reference.
- Fallback: passes `VinNumber(value="", croppedImage=null)`

### UI Layout

```
Box(fillMaxSize, background=Black)
  ├── CameraPreview (fillMaxSize)               -- visible when hasPermission && isScanning
  ├── CircularProgressIndicator                  -- during warmup
  ├── RoiOverlay (fillMaxSize, animated border)  -- guide rectangle
  ├── BoundingBoxOverlay (fillMaxSize)           -- detected boxes
  ├── CircularProgressIndicator (center)         -- during isProcessing
  ├── Column (permission denied message)         -- when !hasPermission
  ├── TopAppBar (semi-transparent, stop/start)   -- when hasPermission
  ├── ManualEntry CircleButton (bottom-center)   -- when hasPermission && isScanning
  └── Snackbar (bottom)                          -- on errorMessage
```

---

## 15. Presentation Layer — UI Components

### `CameraPreview`

Wraps CameraX `PreviewView` in `AndroidView`. Key correctness concerns:

**Generation counter (`AtomicLong activeGeneration`):** CameraX provider futures resolve asynchronously. If the composable is disposed before the future resolves, a stale callback would incorrectly bind the camera to a dead lifecycle. The generation counter is incremented on every bind/release cycle. The async callback checks `generationRef.get() != expectedGeneration` and returns early if stale.

**Tap-to-focus:** `previewView.setOnTouchListener` creates a `FocusMeteringAction` at the touched point with 3-second auto-cancel. Calls `camera.cameraControl.startFocusAndMetering(action)`.

**Lifecycle integration:** Uses `DisposableEffectWithLifecycle` (`Extensions.kt`) to bind on `ON_START` and release on `ON_STOP`. Also has a plain `DisposableEffect` to release on `onDispose`. This handles both foreground/background transitions and Activity destruction.

### `RoiOverlay`

Canvas composable. Draws 4 scrim rectangles (top/bottom/left/right of the ROI cutout) with 55% black alpha. Then draws:
1. A thin white border around the ROI rectangle
2. Corner accent lines (L-shaped marks at each corner, 24dp length)

The scrim+border+corners pattern directs the user's eye to the scanning area without fully obscuring the camera feed.

### `BoundingBoxOverlay`

Canvas composable. For each `BoundingBox`:
1. Draws a stroked rectangle in the theme's primary color (maps normalized coords to canvas pixels)
2. If `confidence > 0.25`, draws the confidence percentage as text at the top-left of the box

Performance: logs overlay render time every 30 frames via `ScannerPerfConfig.overlayTiming`.

### `VinResultDialog` / `VinResultSheetContent`

The `VinResultSheetContent` composable is the main result UI (used inside a `Dialog` wrapper or directly in a bottom sheet). It shows:
- "VIN Detected" header with green checkmark
- VIN number text card
- Cropped VIN image card (if `vinNumber.croppedImage != null`) — downscaled for display safety
- Car information card (manufacturer, country, model year) — decoded via `VinDecoder` using `derivedStateOf`
- Editable VIN field (via `VinTextField`) — re-validates on every change using `vinValidator.validate()`
- **Confirmed** button — enabled only when `isCurrentVinValid`; passes `vinNumber.copy(value=vin, isValid=...)` to `onConfirm`
- **Scan Again** button — calls `onRetry`

`vinDecoder` and `vinValidator` are injected via default parameters from `VinScannerDependencies.get()`, making the composable testable with fakes.

### `VinInputField`

17 individual `BasicTextField` boxes (one per VIN character). Auto-advances focus to the next box via `FocusRequester` when a character is typed. Characters are forced uppercase. Visually: bordered square cells with white text on dark background.

### `VinTextField`

Single-line text field for the result dialog. Features:
- Monospace font, 28sp, letter spacing 3sp
- Paste button (reads from `ClipboardManager`) — visible when empty or editing
- Edit button — enters edit mode and requests focus
- Character counter (`n / 17 characters`) below the field
- Border color: transparent normally, primary when editing, error red when invalid

### `VinEditBar`

Surface card wrapping `VinInputField` with Clear (✕) and Done (✓) buttons. Used for inline editing contexts.

---

## 16. Theme

### `SyaravinTheme`

Material 3 theme. Supports:
- Dynamic color on Android 12+ (`Build.VERSION.SDK_INT >= S`)
- Dark/light system theme
- Host app typography injection via `VinScanner.typographyOverride ?: Typography`

The typography injection is the key integration hook: `VinScanner.setTypography(MyAppTypography)` ensures the scanner UI doesn't look visually disconnected from the host app's font choices.

### Colors

Base palette: Material purple/pink defaults.

ROI feedback colors (used in `ScannerScreen` border animation):
- `RoiNeutralBorder = Color(0xFFFFFFFF)` — white
- `RoiValidBorder = Color(0xFF4AAF57)` — green
- `RoiInvalidBorder = Color(0xFFF75555)` — red

The border color animates with `animateColorAsState(tween(250ms))` for smooth visual feedback.

---

## 17. Utilities

### `SLog`

Structured logger. Emits OpenTelemetry-shaped JSON records to Android `Logcat` under the tag `"SYARAHVIN"`.

```json
{"timestamp":"2026-06-08 10:30:00 AM","severityText":"WARN","body":"[module=VinDetectorImpl] msg","attributes":{"library":"Syaravin","module":"VinDetectorImpl","thread.name":"DefaultDispatcher-worker-1"}}
```

- Default minimum level: `WARN` — `DEBUG`/`INFO` are suppressed unless the host app sets `adb shell setprop log.tag.SYARAHVIN DEBUG`
- `detectCallerModule()` walks `Throwable().stackTrace` to find the first non-`SLog` class in the library's package, providing per-class module attribution automatically

### `ImagePreprocessor`

**`enhanceVinImage(bitmap)`:** Applies a `ColorMatrix` with contrast factor 1.5× and brightness offset +10 on all channels. Used to make VIN text more legible in the result dialog and for the cropped image shown to the user.

**`cropAndEnhance(bitmap, left, top, right, bottom, paddingPercent=0.15f)`:** Crops to the bounding box with 15% padding, then enhances. Returns `null` on failure (prevents crashes from invalid coordinates).

**`downscaleForDisplay(bitmap, maxDimension=1600, maxPixels=1_500_000)`:** Scales down maintaining aspect ratio. Used before storing bitmaps in `ScannerState` (which is observed by Compose) to avoid OOM from full-resolution camera bitmaps held in memory. Applies the stricter of: dimension limit and pixel count limit.

### `RoiConfig`

```kotlin
val roi = BoundingBox(left=0.04, top=0.44, right=0.96, bottom=0.56)
```

A horizontal strip: 4% padding on each side, covering the vertical center 12% of the frame (44% to 56%). Rationale: VINs on vehicles are typically horizontal text. The strip focuses detection on the most likely position and reduces computation on irrelevant areas (sky, interior, etc.).

Portrait aspect ratio constant `9/16` documents that the ROI coordinates were designed for the 540×960 camera resolution.

### `ScannerPerfConfig`

System-property-configurable performance parameters (useful for QA and device-specific tuning without recompiling):

| System Property | Default | Meaning |
|-----------------|---------|---------|
| `syaravin.perf.logs` | `true` | Enable perf logging |
| `syaravin.perf.inference.interval.ms` | `500` | Minimum ms between frames |
| `syaravin.perf.camera.analysis.width` | `540` | Camera resolution width |
| `syaravin.perf.camera.analysis.height` | `960` | Camera resolution height |
| `syaravin.perf.tflite.threads` | `4` | TFLite CPU threads |
| `syaravin.perf.tflite.delegate` | `"gpu"` | Delegate: gpu/nnapi/cpu/xnnpack |

`FrameTimingLogger` logs aggregate stats every 30 frames: FPS, average end-to-end latency, dropped frame count, per-stage breakdown.

### `ThermalManager`

Tracks processing rate and average processing time over 60-second windows. Returns `shouldThrottle() = true` if:
- Processing rate > 3.0 fps, or
- Average processing time > 200ms

Not currently called in the hot path (the 500ms frame interval effectively caps rate at 2fps), but available for future use as a safety valve on hot devices.

### `VinDecoder`

Loads `vin_data.json` from assets (lazy, once). JSON structure:
```json
{
  "wmi": { "1HG": { "manufacturer": "Honda", "country": "USA" }, ... },
  "model_year": { "A": 1980, "B": 1981, ..., "K": 2019, ... },
  "assembly_plant": { "Honda": { "1": "Marysville, Ohio", ... }, ... }
}
```

`decode(vin)` extracts:
- Characters 0–2 (WMI) → manufacturer + country
- Character 9 → model year
- Character 10 + manufacturer lookup → assembly plant

Returns `null` if VIN is not 17 characters.

### `Extensions.kt`

**`Context.showToast(message, duration)`** — utility.

**`DisposableEffectWithLifecycle`** — Compose effect that attaches a `LifecycleEventObserver` to a `LifecycleOwner`. Provides `onCreate/onStart/onResume/onPause/onStop/onDestroy` callbacks. Used by `CameraPreview` to bind/release camera on foreground/background transitions.

---

## 18. Localization

Strings in `res/values/strings.xml` (English) and `res/values-ar/strings.xml` (Arabic).

All UI strings are accessed via `stringResource(R.string.*)` in composables. `ScannerViewModelStrings` resolves strings from `Context` at ViewModel creation time for use in ViewModel error messages.

Validation error messages are also localized (`validation_wrong_length`, `validation_contains_invalid_chars`, `validation_insufficient_digits`, `validation_checksum_accepted`, `validation_invalid_chars_or_no_valid_vin`).

---

## 19. Tests

`VinValidatorImplTest` — 50+ unit tests covering:
- Format validation: length, invalid characters, digit count
- ISO 3779 checksum: known valid VINs, X check digit, accepted-but-invalid checksums
- OCR correction: I→1, O→0, Q→0, lowercase
- Extraction: VIN label stripping (`VIN:`, `VIN NUMBER:`, `vin no -`, `VIN#`)
- Smart trimming: trailing/leading slashes and special chars are trimmed (`wasTrimmed=true`)
- Strict middle rejection: colons, asterisks, spaces in middle → invalid
- Ambiguous characters: S/5, B/8 permutation tries
- Edge cases: empty, whitespace-only, emoji, mixed case, unusual inputs that must not throw

---

## 20. Build & Publishing

Library is an Android library module (`com.android.library`) with:
- `mlModelBinding = true` — enables ML model data binding
- Maven publication configured via `maven-publish` plugin

Published as `com.syarah:vinscanner:1.3.0` with sources and javadoc JARs.

Key dependencies:
- `ai.edge.litert` 1.4.0 (Google AI Edge LiteRT — TFLite) — `base`, `gpu`, `core`
- `mlkit.text.recognition` — ML Kit OCR
- `camera.*` — CameraX suite
- `accompanist.permissions` — Compose permission handling
- `kotlinx.serialization.json` — VIN data JSON parsing
- `kotlinx.coroutines.play.services` — `Task.await()` for ML Kit

---

## 21. Cross-Cutting Design Decisions

### No Koin in Library
The sample app uses Koin; the library does not. `VinScannerDependencies` is a manual DI factory that requires no DI framework. This keeps the library usable in apps using Hilt, Dagger, or no DI at all.

### ActivityResultContract Pattern
The entire scanner is encapsulated in an internal Activity. The host app needs no knowledge of Compose, CameraX, or ML Kit. Integration is three lines: declare launcher, launch it, handle result.

### Repository as Architecture Seam (Not Live Data Path)
`VinScannerRepository` and its use cases exist as clean-architecture declarations. The live frame processing bypasses them: `ScannerScreen` calls data sources directly. This was a deliberate trade-off — wiring CameraX `ImageAnalysis` through a Flow-based repository requires bridging Android lifecycle to coroutines in a way that is complex and fragile. The architecture seam is preserved for future migration.

### Bitmap Lifecycle Management
Three tiers of bitmap management:
1. **Processing bitmaps** (full frame, ROI crop): recycled in `finally` blocks in `processImage`
2. **Display bitmaps** (cropped VIN, ROI copy): downscaled via `ImagePreprocessor.downscaleForDisplay()` before being stored in `ScannerState`
3. **State bitmaps**: managed by `ScannerViewModel.onRoiCroppedBitmapUpdated()` which recycles the old bitmap asynchronously when a new one arrives

### Soft Validation
The validator returns `isValid=true` even when the ISO 3779 checksum fails. The `Confirmed` button in `VinResultSheetContent` is gated on `isCurrentVinValid` — which re-validates on every edit. This means a user can manually correct the VIN until it validates, rather than being blocked entirely.

### Frame Rate Gating
Three independent mechanisms limit processing rate:
1. **Interval timer** (500ms) — time since last processed frame
2. **AtomicBoolean lock** — prevents concurrent processing
3. **Warmup gate** — no processing until background warmup completes

### Configurable via System Properties
`ScannerPerfConfig` reads system properties at class initialization. QA engineers can tune frame rates, resolution, and delegate mode without rebuilding the APK:
```bash
adb shell setprop syaravin.perf.tflite.delegate cpu
adb shell setprop syaravin.perf.inference.interval.ms 250
```

---

## 22. Full Data Flow Walk-through

```
HOST APP
  └── launcher.launch(Unit)
        │
        ▼
VinScannerContract.createIntent()
  └── Intent(context, VinScannerActivity::class)
        │
        ▼
VinScannerActivity.onCreate()
  ├── VinScannerDependencies.initialize(appContext)
  │     └── DependencyContainer created (lazy singletons declared, not yet created)
  └── setContent { SyaravinTheme { ScannerScreen(...) } }
        │
        ▼
ScannerScreen (first composition)
  ├── VinScannerDependencies.get() → dependencies
  ├── remember { dependencies.createCameraSelector() } → CameraSelector
  ├── remember { dependencies.createPreview() }        → Preview
  ├── remember { dependencies.createImageAnalysis() }  → ImageAnalysis
  ├── remember { dependencies.createExecutor() }       → ExecutorService
  ├── LaunchedEffect: warmUpScannerDependencies()       → forces lazy init of TFLite + ML Kit
  └── LaunchedEffect: cameraPermissionState.launchPermissionRequest()
        │
USER GRANTS PERMISSION
        │
        ▼
cameraPermissionState.onPermissionResult(granted=true)
  └── viewModel.onEvent(PermissionGranted)
        └── startScanning() → state.isScanning = true, state.hasPermission = true
              │
              ▼
DisposableEffect(isScanning=true, isWarmupComplete=true) activates
  └── imageAnalysis.setAnalyzer(executor) { imageProxy → ... }
        │
CAMERA FRAME ARRIVES (every ~33ms at 30fps, processed every 500ms)
        │
        ▼
processImage(imageProxy, cameraDataSource, vinDetector, textExtractor, vinValidator)
  │
  ├─ [Stage 1] cameraDataSource.imageToBitmap(imageProxy)
  │     └── YUV→RGB (ITU-R BT.601 direct conversion) + rotation
  │     └── Bitmap: 540×960 portrait
  │
  ├─ [Stage 2] Crop to ROI: x=[4%,96%], y=[44%,56%]
  │     └── roiBitmap: ~529×116 px
  │     └── Save downscaled copy to state for manual entry button
  │
  ├─ [Stage 3] vinDetector.detect(roiBitmap)
  │     ├── preprocessImage: letterbox roiBitmap → 640×640
  │     ├── convertBitmapToByteBuffer: normalize RGB → float [0,1]
  │     ├── interpreter.runForMultipleInputsOutputs()
  │     └── parse [1,8400,6] output → rawBoxes → NMS → DetectionResult
  │
  ├─ [Stage 4] Map detection boxes from ROI-space → full-frame-space
  │     └── onBoxesDetected(mappedBoxes) → state.detectionBoxes (BoundingBoxOverlay updates)
  │
  ├─ [Stage 5] Update RoiBorderState (NO_DETECTION or NEUTRAL)
  │
  ├─ [Stage 6] For each box (sorted by confidence):
  │     ├── textExtractor.extractText(roiBitmap, box)
  │     │     ├── crop region from roiBitmap (ensure ≥32×32)
  │     │     ├── detectRotation heuristic (aspect ratio)
  │     │     └── MLKit TextRecognition.process(InputImage).await()
  │     ├── vinValidator.cleanVin(ocrText)
  │     │     ├── stripLeadingVinLabel
  │     │     ├── correctOcrErrors (I→1, O→0, etc.)
  │     │     └── extractVin (trim edges, reject middle punctuation, find 17-char sequence)
  │     └── vinValidator.validate(cleanedVin)
  │           ├── length=17 check
  │           ├── no I/O/Q check
  │           ├── ≥5 digits check
  │           └── ISO 3779 checksum (with up to 1 ambiguous-char permutation)
  │                 └── soft-accept even on checksum failure
  │
  ├─ [Stage 7] If bestVin found:
  │     ├── ImagePreprocessor.cropAndEnhance(roiBitmap, box) → croppedVinBitmap
  │     ├── downscaleForDisplay(croppedVinBitmap)
  │     ├── onRoiBorderStateChange(VALID_VIN_DETECTED) → border turns green
  │     └── onVinDetected(bestVin, confidence, croppedBitmap)
  │
  └─ finally: bitmap.recycle(), imageProxy.close(), log stage timings
        │
        ▼
ScannerViewModel.onVinDetected(vin, confidence, bitmap)
  ├── vinValidator.validate(vin) on Dispatchers.Default
  ├── VinNumber(value=vin, isValid=true, confidence=0.87, croppedImage=bitmap)
  ├── state.update { copy(detectedVin=vinNumber, showVinResult=true) }
  └── stopScanning() → state.isScanning = false
        │
        ▼
LaunchedEffect(state.detectedVin) in ScannerScreen fires
  └── onVinConfirmed(vinNumber)
        │
        ▼
VinScannerActivity.returnResult(vinNumber)
  └── setResult(RESULT_OK, Intent().putExtra(EXTRA_VIN_RESULT, vinNumber))
  └── finish()
        │
        ▼
VinScannerContract.parseResult(RESULT_OK, intent)
  └── VinScanResult.Success(vinNumber)
        │
        ▼
HOST APP lambda receives VinScanResult.Success
  └── result.vinNumber.value     → "1HGBH41JXMN109186"
  └── result.vinNumber.confidence → 0.87
  └── result.vinNumber.isValid   → true
  └── result.vinNumber.croppedImage → Bitmap of the VIN plate
```
