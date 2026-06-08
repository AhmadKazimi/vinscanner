# Scanner Screen

File: `presentation/scanner/ScannerScreen.kt`

## Initialization order

```
1. viewModel = viewModel(factory = ScannerViewModelFactory())
2. dependencies = VinScannerDependencies.get()
3. remember { createCameraSelector() / createPreview() / createImageAnalysis() / createExecutor() }
4. lazy { cameraDataSource / vinDetector / textExtractor / vinValidator }   ← not yet created
5. processingScope = CoroutineScope(SupervisorJob + Dispatchers.Default)
6. LaunchedEffect: warmUpScannerDependencies()   ← forces lazy init off main thread
7. LaunchedEffect: check/request camera permission
8. DisposableEffect(Unit): on dispose → clearAnalyzer, cancel scope, executor.shutdownNow()
```

## Frame analysis loop

`DisposableEffect(state.isScanning, isWarmupComplete)` — activates only when both are true:

```kotlin
imageAnalysis.setAnalyzer(executor) { imageProxy ->
    val now = currentTimeMillis()
    if (now - lastProcessTime >= inferenceIntervalMs        // 500ms rate limit
        && isProcessingFrame.compareAndSet(false, true)) {  // one-at-a-time lock
        lastProcessTime = now
        processingScope.launch {
            try { processImage(...) }
            finally { isProcessingFrame.set(false) }
        }
    } else {
        frameTiming.onFrameDropped()
        imageProxy.close()   // must always close
    }
}
```

Three independent rate gates:
1. **500ms interval** (`AtomicLong lastProcessTime`)
2. **One-at-a-time lock** (`AtomicBoolean isProcessingFrame`)
3. **Warmup gate** (`isWarmupComplete`)

Frames arriving while processing is in-flight are dropped (not queued). `imageProxy.close()` must always be called.

## `processImage` stages

```
[1] cameraDataSource.imageToBitmap(imageProxy)
      → Bitmap: 540×960 portrait (YUV→RGB direct conversion)

[2] Crop to RoiConfig.roi
      leftPx  = roi.left  × bitmap.width   = 0.04 × 540 ≈ 21px
      topPx   = roi.top   × bitmap.height  = 0.44 × 960 ≈ 422px
      rightPx = roi.right × bitmap.width   = 0.96 × 540 ≈ 518px
      btmPx   = roi.bottom× bitmap.height  = 0.56 × 960 ≈ 537px
      roiBitmap ≈ 497×115px
      Save downscaleForDisplay() copy → state.latestRoiCroppedBitmap

[3] vinDetector.detect(roiBitmap)
      → DetectionResult.boundingBoxes (normalized to roiBitmap)

[4] Map boxes from ROI-space → full-frame-space
      fullLeft = roi.left + box.left × (roi.right - roi.left)
      (same for top/right/bottom)
      onBoxesDetected(mappedBoxes)  → state.detectionBoxes → BoundingBoxOverlay

[5] Update RoiBorderState
      empty boxes → NO_DETECTION (red)
      boxes found → NEUTRAL (white)

[6] OCR loop (boxes sorted by descending confidence)
      for each box:
          text = textExtractor.extractText(roiBitmap, box)
          candidate = vinValidator.cleanVin(text)
          result = vinValidator.validate(candidate)
          if result.isValid:
              bestVin = candidate
              croppedBitmap = ImagePreprocessor.cropAndEnhance(roiBitmap, box, padding=0.15)
              croppedBitmap = downscaleForDisplay(croppedBitmap)
              break

[7] If bestVin != null:
      onRoiBorderStateChange(VALID_VIN_DETECTED)   ← green
      onVinDetected(bestVin, confidence, croppedBitmap)
      → ScannerViewModel.onVinDetected()

finally: bitmap.recycle(), imageProxy.close(), log stage timings
```

Note: full-image OCR fallback is present in the code but **disabled** by a comment block. Detection relies on the AI model only.

## Auto-confirm

```kotlin
LaunchedEffect(state.detectedVin) {
    state.detectedVin?.let { onVinConfirmed(it) }
}
```

When `ScannerViewModel.onVinDetected` sets `detectedVin`, this effect fires immediately — no user tap required. The bottom sheet was removed.

## Manual entry button

Circular white button at bottom center. On tap:
- If `state.latestRoiCroppedBitmap != null`: passes `VinNumber(value="", croppedImage=roiBitmap)` to `onVinConfirmed`
- Fallback: passes `VinNumber(value="", croppedImage=null)`

The host app receives `VinScanResult.Success` with an empty VIN and a bitmap reference for a manual input UI.

## UI layout

```
Box(fillMaxSize, background=Black)
  ├── CameraPreview           when hasPermission && isScanning
  ├── CircularProgressIndicator(center)   during warmup
  ├── RoiOverlay              animated border color
  ├── BoundingBoxOverlay      detection boxes
  ├── CircularProgressIndicator(center)   isProcessing
  ├── Permission-denied column  when !hasPermission
  ├── TopAppBar               stop/start toggle
  ├── ManualEntryButton       bottom-center circle
  └── Snackbar                errorMessage
```

ROI border animation: `animateColorAsState(tween(250ms))` between `RoiValidBorder`, `RoiNeutralBorder`, `RoiInvalidBorder`.
