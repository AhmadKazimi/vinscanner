# Architecture

## Layers

```
Host App
   └── VinScannerContract (ActivityResultContract)
         └── VinScannerActivity
               └── ScannerScreen (Compose)
                     ├── ScannerViewModel
                     └── Frame processing loop
                           ├── CameraDataSource
                           ├── VinDetector (TFLite)
                           ├── TextExtractor (ML Kit)
                           └── VinValidator
```

### Layer rules

| Layer | Allowed to depend on | Must NOT depend on |
|-------|---------------------|--------------------|
| `domain` | nothing | data, di, presentation |
| `data` | `domain` | `presentation`, `di` |
| `presentation` | `domain`, `data` (data sources directly) | nothing extra |
| `di` | all layers | nothing |

Domain contains only interfaces and models. Data contains implementations. Presentation is allowed to call data sources directly (see "Repository seam" below).

## Domain Layer

### `BoundingBox`
All coordinates **normalized 0.0–1.0** relative to the image. Never holds pixel values. Has computed `width`, `height`, `centerX`, `centerY` and a `toPixelCoordinates(w, h)` converter.

Why normalized: the detection pipeline runs at different resolutions (ROI crop, full frame, 640×640 model space). Normalized coordinates survive any resolution change — callers multiply by the current bitmap dimensions.

### `VinNumber` (Parcelable)
`value: String` · `confidence: Float` · `isValid: Boolean` · `croppedImage: Bitmap?`

Parcelable because it must survive the `Intent` round-trip through `VinScannerActivity.returnResult()`.

Constants: `VIN_LENGTH=17`, `INVALID_CHARACTERS={I,O,Q}`, `VALID_PATTERN=[A-HJ-NPR-Z0-9]{17}`.

### `VinScannerRepository` (interface)
`detectVinRegions` · `extractTextFromRegion` · `validateVin` · `startScanning():Flow<VinNumber>` · `stopScanning()`

### Use cases
`DetectVinUseCase`, `ExtractTextUseCase`, `ValidateVinUseCase` — thin `operator fun invoke` wrappers that enforce `Dispatchers.Default`. Exist as a clean-architecture seam; `ScannerScreen` currently calls data sources directly.

## Repository Seam (important)

`VinScannerRepositoryImpl.startScanning()` calls `cameraDataSource.startCamera()`, which in `CameraDataSourceImpl` returns an **empty** `callbackFlow` stub. The repository flow never emits.

The live frame path is in `ScannerScreen.kt`: `imageAnalysis.setAnalyzer()` drives `processImage()` which calls `cameraDataSource.imageToBitmap()`, `vinDetector.detect()`, `textExtractor.extractText()`, and `vinValidator.cleanVin/validate()` directly.

The repository exists as an architecture seam for future refactoring — don't delete it, but don't expect it to carry live data.

## Data Flow (summary)

```
Camera frame (YUV_420_888)
  → CameraDataSourceImpl.imageToBitmap()   [YUV→RGB, rotation]
  → Crop to RoiConfig.roi                  [normalized → pixel coords]
  → VinDetectorImpl.detect()               [letterbox → TFLite → NMS]
  → Map boxes to full-frame coords
  → TextExtractorImpl.extractText()        [ML Kit OCR per box]
  → VinValidatorImpl.cleanVin/validate()   [7-stage pipeline]
  → ScannerViewModel.onVinDetected()
  → ScannerState.detectedVin set
  → LaunchedEffect fires onVinConfirmed()
  → VinScannerActivity.returnResult()
  → VinScannerContract.parseResult()
  → Host app receives VinScanResult.Success
```

## Design decisions

**No Koin in the library.** The sample app uses Koin; the library uses `VinScannerDependencies` (manual DI). A library injecting into the host's Koin context pollutes the host's DI graph and forces Koin on Hilt/Dagger users.

**ActivityResultContract pattern.** The entire scanner is an internal Activity. Host needs no knowledge of Compose, CameraX, or ML Kit — just `registerForActivityResult`.

**Soft checksum validation.** `isValid=true` even when checksum fails (with `checksumValid=false`). False negatives from OCR errors are worse than accepting a bad checksum; the user can correct manually.
