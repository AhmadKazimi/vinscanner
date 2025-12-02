# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Syaravin is an Android VIN (Vehicle Identification Number) scanner application that performs real-time detection and validation using:
- **CameraX** for live camera preview and image analysis
- **TensorFlow Lite (Google AI Edge LiteRT)** for VIN region detection with GPU acceleration
- **ML Kit Text Recognition** for OCR text extraction with confidence analysis
- **Koin** for dependency injection
- **Jetpack Compose** with Material 3 for UI
- **Thermal management** for device performance optimization

## Essential Commands

### Build & Install
```bash
# Build debug APK
./gradlew assembleDebug

# Install debug build on connected device
./gradlew :sample-app:installDebug

# Build and install in one command
./gradlew :sample-app:installDebug
```

### Testing
```bash
# Run unit tests
./gradlew testDebugUnitTest

# Run instrumented tests on device
./gradlew connectedDebugAndroidTest

# Run specific test class
./gradlew testDebugUnitTest --tests "com.kazimi.syaravin.ClassNameTest"
```

### Development
```bash
# Clean build
./gradlew clean

# Check for dependency updates
./gradlew dependencyUpdates
```

## Architecture Overview

### Clean Architecture Layers
- **Presentation**: `presentation/` - Compose UI, ViewModels, state management
  - `presentation/scanner/` - Main scanner screen and ViewModel
  - `presentation/components/` - Reusable Compose components (overlays, dialogs, inputs)
- **Domain**: `domain/` - Use cases, business logic, domain models
  - `domain/model/` - Domain entities (VinNumber, BoundingBox)
  - `domain/repository/` - Repository interfaces
  - `domain/usecase/` - Business logic use cases (DetectVin, ExtractText, ValidateVin)
- **Data**: `data/` - Repositories, data sources (camera, ML, validation)
  - `data/datasource/camera/` - Camera input handling
  - `data/datasource/ml/` - ML model inference and text extraction
  - `data/datasource/validator/` - VIN validation logic
  - `data/repository/` - Repository implementations
  - `data/model/` - Data layer models (DetectionResult, VinValidationResult)
- **DI**: `di/` - Koin modules for dependency injection
- **UI**: `ui/theme/` - Material 3 theme, colors, typography
- **Util**: `util/` - Utilities (RoiConfig, ThermalManager, VinDecoder, Extensions)

### Data Flow
1. Camera captures frames via `CameraDataSource`
2. `VinDetector` runs TFLite inference to find VIN regions
3. `TextExtractor` uses ML Kit OCR on detected regions
4. `VinValidator` cleans and validates extracted text
5. Results flow through repository → use cases → ViewModel → UI

### Key Components

**ML Pipeline**:
- `VinDetectorImpl.kt` - TFLite model inference with letterbox preprocessing (640×640 RGB input)
- `TextExtractorImpl.kt` - ML Kit OCR with confidence analysis and preprocessing (1.5× upscaling)
- `VinValidatorImpl.kt` - VIN format validation and ISO 3779 checksum verification

**Camera Pipeline**:
- `CameraDataSourceImpl.kt` - ImageProxy to Bitmap conversion with rotation handling
- `ScannerScreen.kt` - ImageAnalysis analyzer with thermal-aware frame processing
- `BoundingBoxOverlay.kt` - Real-time detection visualization
- `RoiOverlay.kt` - Region of Interest visualization for focused scanning

**Dependency Injection** (registered in `SyarahvinApplication.kt`):
- `appModule` - Application-level dependencies and utilities
- `cameraModule` - Camera data source and executor services
- `mlModule` - TFLite interpreter, text extractor, validator (all as `single`)
- `repositoryModule` - Repository implementations
- `viewModelModule` - ViewModels with `viewModelOf` scope

**Performance & Optimization**:
- `ThermalManager.kt` - Frame rate and processing time monitoring to prevent overheating
- Early termination strategies in ML pipeline (top 3 boxes for text extraction)
- Bitmap recycling and memory management throughout pipeline

## Development Guidelines

### Adding New Dependencies
1. Update `gradle/libs.versions.toml` with version
2. Add library reference in `libs.versions.toml` `[libraries]` section  
3. Add implementation in `app/build.gradle.kts`
4. Create DI bindings if needed in appropriate `di/` module

### Working with ML Models
- Replace `best_float32.tflite` or `best_float16.tflite` in `app/src/main/assets/`
- Ensure input size matches `VinDetectorImpl.MODEL_INPUT_SIZE` constant (currently 640×640)
- Update tensor parsing logic in `VinDetectorImpl` if output format changes (supports both [1,8400,6] and [1,6,8400] formats)
- GPU delegate used when available with thermal optimizations, CPU fallback with 2 threads
- Model uses letterbox preprocessing to maintain aspect ratio

### UI Development
- Use existing Compose components in `presentation/components/`
- Follow Material 3 design system from `ui/theme/`
- Maintain normalized coordinates in `BoundingBox` domain model
- UI components handle pixel coordinate mapping

### Camera Integration
- App runs in portrait mode (`android:screenOrientation="portrait"` in AndroidManifest)
- Camera resolution optimized for thermal efficiency (540×960 portrait)
- ImageAnalysis uses rotation-corrected bitmap conversion with 85% JPEG quality
- Frame processing happens in `ScannerScreen` with 500ms intervals
- ROI (Region of Interest) configured in `RoiConfig.kt` for focused scanning (9:16 aspect ratio)
- Permission handling via Accompanist Permissions
- Thermal throttling via `ThermalManager` to prevent device overheating

## Testing Strategy

### Unit Testing
- Focus on validation logic in `VinValidatorImpl`
- Test use cases and domain model behavior
- Mock repository implementations for ViewModel tests

### Integration Testing  
- Test ML pipeline with sample images
- Verify camera data source bitmap conversion
- Repository integration with multiple data sources

## Important Constants & Configuration

### ML Detection Constants (in `VinDetectorImpl`)
- `MODEL_INPUT_SIZE = 640` - Model expects 640×640 RGB input
- `DEFAULT_CONF_THRESHOLD = 0.25f` - Minimum confidence for detections
- `NMS_IOU_THRESHOLD = 0.45f` - Non-maximum suppression threshold

### Camera Constants
- Camera resolution: 540×960 portrait (optimized for thermal efficiency)
- JPEG quality: 85% compression
- Processing interval: 500ms between frames
- Target rotation: ROTATION_0 (portrait mode)

### Thermal Management (in `ThermalManager`)
- `MAX_AVG_PROCESSING_TIME = 200L` ms - Average processing time threshold
- `MAX_PROCESSING_RATE = 3.0` fps - Maximum frame processing rate
- Statistics reset every 60 seconds

### ROI Configuration (in `RoiConfig`)
- Analyzed image aspect ratio: 9:16 (native portrait mode)
- Default ROI: horizontal band from 40% to 60% vertically, 5% horizontal padding
- Coordinates are normalized (0.0 to 1.0)
- Configured for portrait orientation (540×960)

## Code Conventions

### Naming
- Use descriptive, non-abbreviated names
- Follow Kotlin naming conventions
- Package structure mirrors architecture layers
- Constants use UPPER_SNAKE_CASE

### Architecture
- Keep UI logic in Compose and ViewModels
- Business logic only in domain layer use cases
- External APIs wrapped by data sources
- Repository coordinates between data sources
- Use coroutines with appropriate dispatchers, never block main thread

### DI Management
- Add new modules to `SyarahvinApplication.startKoin { modules(...) }`
- Use `single` for expensive-to-create objects (ML models, cameras)
- Use `factory` for lightweight objects or when new instances needed
- Interfaces in domain, implementations in data layer

## Common Tasks

### Updating ML Model
1. Replace model files in `app/src/main/assets/` (both `best_float16.tflite` and `best_float32.tflite`)
2. Update constants in `VinDetectorImpl` if input/output dimensions change
3. Verify output tensor shape compatibility (currently supports [1,8400,6] and [1,6,8400] formats)
4. Test with sample images to ensure letterbox preprocessing works correctly
5. Update `metadata.yaml` if model metadata changes
6. Additional model files include `saved_model.pb`, `fingerprint.pb`, and variables

### Adding New Camera Features
1. Extend `CameraDataSource` interface in domain
2. Implement in `CameraDataSourceImpl`  
3. Add DI binding in `CameraModule`
4. Wire through repository and use case

### Modifying VIN Validation
1. Update logic in `VinValidatorImpl`
2. Add corresponding unit tests
3. Update `VinNumber` domain model if format changes

### Working with Confidence Analysis
- Text extraction includes confidence metrics for each character
- High confidence valid VINs auto-continue scanning without interruption
- Medium/low confidence VINs show enhanced dialog for user review
- Confidence system reduces false positives and improves UX
- See `INTEGRATION_SUMMARY.md` for detailed confidence workflow

## Performance & Thermal Management

### Optimization Strategy
- Frame processing interval: 500ms to reduce thermal load
- Camera resolution: 540×960 portrait (optimized from 720×1280)
- ML inference: Maximum 100ms timeout with early termination
- Text extraction: Limited to top 3 highest-confidence boxes
- GPU delegate with thermal optimizations and precision loss allowance

### Memory Management
- Bitmap recycling enforced in all code paths
- Pre-allocated ByteBuffer for TFLite input (640×640×3×4 bytes)
- JPEG compression at 85% quality to reduce memory overhead
- Early validation skips boxes < 0.01f normalized size

### Threading Model
- All ML operations on `Dispatchers.Default` coroutine scope
- Camera operations use dedicated `ExecutorService`
- UI updates on main thread via state flows
- Never block main thread during frame processing

### Thermal Throttling
- `ThermalManager` monitors processing rate (max 3 fps) and average time (max 200ms)
- Automatic throttling when limits exceeded
- Statistics reset every 60 seconds for fresh baselines

See `PERFORMANCE_OPTIMIZATIONS.md` for detailed optimization history.

## Troubleshooting

### Build Issues
- Ensure Android SDK 36 is installed
- Check Java 11 compatibility (`sourceCompatibility` and `targetCompatibility` in `build.gradle.kts`)
- Google AI Edge LiteRT dependencies hardcoded to version 1.4.0 in `app/build.gradle.kts`
- ML model binding must be enabled (`mlModelBinding = true` in buildFeatures)

### Runtime Issues
- Check camera permission granted (`android.permission.CAMERA`)
- Verify TFLite model files exist in `app/src/main/assets/` (both float16 and float32 variants)
- Check logcat for `VinDetectorImpl` errors if model loading fails
- GPU delegate may fail on some devices - automatic CPU fallback with 2 threads
- Thermal throttling may reduce frame rate - check `ThermalManager` logs
- Device must support hardware camera and autofocus (optional in manifest)

### Common Issues
- LeakCanary is included as debug dependency for memory leak detection
- Portrait orientation is enforced - landscape mode not supported
- VIN data loaded from `vin_data.json` for decoding manufacturer/model information

## Project Files to Reference

- `PERFORMANCE_OPTIMIZATIONS.md` - Detailed thermal and performance optimization history
- `INTEGRATION_SUMMARY.md` - Enhanced confidence system integration details
- `gradle/libs.versions.toml` - Centralized dependency version management