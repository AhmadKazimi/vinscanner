## Syaravin Android VIN Scanner – Project Overview

This app scans vehicle VINs in real time using CameraX, detects probable VIN regions with a TensorFlow Lite model, extracts text with ML Kit, and validates it as a VIN before presenting the result in the UI.

### Tech stack
- Kotlin, Jetpack Compose (Material 3)
- CameraX (Preview, ImageAnalysis)
- TensorFlow Lite (LiteRT with optional GPU delegate)
- Google ML Kit Text Recognition (Latin)
- Koin for Dependency Injection
- Coroutines/Flows, Timber, Accompanist Permissions

Android config:
- minSdk 24, target/compile 36, JVM target 11
- Landscape-only main `Activity`

### High-level architecture
- presentation: Compose UI and `ScannerViewModel`
- domain: `usecase` layer and domain `model`
- data: camera, ML, and validation data sources; repository impl
- di: Koin modules wiring all layers
- assets: TFLite model and metadata

Data flow at runtime
1) Permission gating and scan toggle in `ScannerViewModel`/`ScannerScreen`.
2) `ScannerScreen` starts an `ImageAnalysis` analyzer when scanning.
3) Each frame: `CameraDataSource.imageToBitmap(ImageProxy)` → `VinDetector.detect(bitmap)` → for each box `TextExtractor.extractText(bitmap, box)` → `VinValidator.clean/validate`.
4) First valid VIN updates `ScannerViewModel` state; overlay shows coloured boxes (green valid / red invalid) and confidence.

### Key modules and files
- Application/DI
  - `app/src/main/java/com/kazimi/syaravin/SyarahvinApplication.kt`: boots Timber and Koin, loads modules.
  - `di/AppModule.kt`: baseline app-level Koin module (currently no explicit bindings).
  - `di/CameraModule.kt`: CameraX bindings — `CameraSelector`, `Preview`, `ImageAnalysis`, executor, `CameraDataSource`.
  - `di/MLModule.kt`: TFLite `Interpreter` with optional GPU delegate, `VinDetector`, `TextExtractor` (ML Kit), `VinValidator`.
  - `di/RepositoryModule.kt`: `VinScannerRepository` binding.
  - `di/ViewModelModule.kt`: `DetectVinUseCase`, `ExtractTextUseCase`, `ValidateVinUseCase`, `ScannerViewModel`.

- Presentation (Compose)
  - `presentation/scanner/ScannerScreen.kt`: UI, permission handling, ImageAnalysis analyzer with frame processing and overlays.
  - `presentation/scanner/ScannerViewModel.kt`: scanning state, event handling, VIN validation and result handling.
  - `presentation/components/CameraPreview.kt`: binds CameraX `Preview` + `ImageAnalysis` to lifecycle.
  - `presentation/components/BoundingBoxOverlay.kt`: renders normalized boxes + confidence + extracted text.
  - `presentation/components/VinResultDialog.kt`: result dialog (invoked from `ScannerScreen`).

- Domain
  - `domain/usecase/DetectVinUseCase.kt`: detect VIN regions via repository.
  - `domain/usecase/ExtractTextUseCase.kt`: OCR a region via repository.
  - `domain/usecase/ValidateVinUseCase.kt`: validate/normalize VIN via repository.
  - `domain/model/BoundingBox.kt`: normalized coordinates, confidence, validity, optional text.
  - `domain/model/VinNumber.kt`: value + validity, pattern and helpers.

- Data
  - `data/datasource/camera/CameraDataSourceImpl.kt`: `ImageProxy` → `Bitmap` (YUV → NV21 → JPEG → `Bitmap`, rotation-corrected).
  - `data/datasource/ml/VinDetectorImpl.kt`: TFLite inference; preprocess to square 640×640, normalized float RGB; parses boxes from outputs.
  - `data/datasource/ml/TextExtractorImpl.kt`: ML Kit recognizer; crops per box, grayscale + upscale; second pass thresholding; picks best text.
  - `data/datasource/validator/VinValidatorImpl.kt`: VIN cleaning and validation (length/pattern/checks).
  - `data/repository/VinScannerRepositoryImpl.kt`: orchestration across data sources.

- Assets
  - `app/src/main/assets/best_float32.tflite`: default model used by `MLModule`.
  - Other model files and metadata for experimentation.

### Build and run
- Build debug: `./gradlew assembleDebug`
- Install on device: `./gradlew :app:installDebug`
- Unit tests: `./gradlew testDebugUnitTest`
- Instrumented tests: `./gradlew connectedDebugAndroidTest`

Open/run from Android Studio for best DX, or use the Gradle commands above. App launches in landscape mode with the scanner screen.

### Extending the app
- Update ML model: replace `best_float32.tflite` in assets; ensure input size and output tensors match what `VinDetectorImpl` expects, or adjust constants/parsing accordingly.
- Tuning detection: tweak confidence threshold passed in `ScannerScreen.processImage` and/or model constants.
- Alternative OCR: adjust `TextExtractorImpl` to try more preprocessing strategies or use a custom ML Kit recognizer.
- UI: extend `BoundingBox` to carry additional metadata and render it in `BoundingBoxOverlay`.
- Add features: e.g., gallery import; create a new `UseCase`, extend the repository, wire it in a module, and expose via `ScannerViewModel`.

### Notable implementation details
- GPU delegate is used when supported; otherwise CPU with 4 threads.
- Normalized coordinates assumed for boxes; overlay maps to view size.
- Permission flow uses Accompanist; the first launch prompts camera permission.

### Known limitations and considerations
- Model IO/shape must align with `VinDetectorImpl` constants; mismatches will fail at runtime.
- OCR accuracy depends on crop quality; aggressive thresholding may harm some cases.
- Performance depends on device; consider dynamic thread count, throttling, or frame skipping if needed.

### Dependency map (selected)
- CameraX: `camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`
- LiteRT: `com.google.ai.edge.litert:litert(+gpu,+support):1.4.0`
- ML Kit: `com.google.mlkit:text-recognition`
- Koin: `koin-android`, `koin-androidx-compose`
- Compose BOM and Material3


