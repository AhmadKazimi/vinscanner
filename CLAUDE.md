# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Syaravin is an Android VIN (Vehicle Identification Number) scanner application that performs real-time detection and validation using:
- **CameraX** for live camera preview and image analysis
- **TensorFlow Lite (LiteRT)** for VIN region detection with GPU acceleration
- **ML Kit Text Recognition** for OCR text extraction
- **Koin** for dependency injection
- **Jetpack Compose** with Material 3 for UI

## Essential Commands

### Build & Install
```bash
# Build debug APK
./gradlew assembleDebug

# Install debug build on connected device
./gradlew :app:installDebug

# Build and install in one command
./gradlew :app:installDebug
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
- **Domain**: `domain/` - Use cases, business logic, domain models  
- **Data**: `data/` - Repositories, data sources (camera, ML, validation)
- **DI**: `di/` - Koin modules for dependency injection

### Data Flow
1. Camera captures frames via `CameraDataSource`
2. `VinDetector` runs TFLite inference to find VIN regions
3. `TextExtractor` uses ML Kit OCR on detected regions
4. `VinValidator` cleans and validates extracted text
5. Results flow through repository → use cases → ViewModel → UI

### Key Components

**ML Pipeline**:
- `VinDetectorImpl.kt` - TFLite model inference (640×640 RGB input)
- `TextExtractorImpl.kt` - ML Kit OCR with preprocessing
- `VinValidatorImpl.kt` - VIN format validation and checksum verification

**Camera Pipeline**:
- `CameraDataSourceImpl.kt` - ImageProxy to Bitmap conversion
- `ScannerScreen.kt` - ImageAnalysis analyzer with frame processing
- `BoundingBoxOverlay.kt` - Real-time detection visualization

**Dependency Injection**:
- All modules registered in `SyarahvinApplication.kt`
- Camera, ML, Repository, ViewModel, and App modules
- Use Koin's `single` for singletons, `factory` for new instances

## Development Guidelines

### Adding New Dependencies
1. Update `gradle/libs.versions.toml` with version
2. Add library reference in `libs.versions.toml` `[libraries]` section  
3. Add implementation in `app/build.gradle.kts`
4. Create DI bindings if needed in appropriate `di/` module

### Working with ML Models
- Replace `best_float32.tflite` in `app/src/main/assets/`
- Ensure input size matches `VinDetectorImpl` constants (currently 640×640)
- Update tensor parsing logic if output format changes
- GPU delegate used when available, CPU fallback with 4 threads

### UI Development
- Use existing Compose components in `presentation/components/`
- Follow Material 3 design system from `ui/theme/`
- Maintain normalized coordinates in `BoundingBox` domain model
- UI components handle pixel coordinate mapping

### Camera Integration
- App runs in landscape mode by default
- ImageAnalysis uses rotation-corrected bitmap conversion
- Frame processing happens in `ScannerScreen.processImage`
- Permission handling via Accompanist Permissions

## Testing Strategy

### Unit Testing
- Focus on validation logic in `VinValidatorImpl`
- Test use cases and domain model behavior
- Mock repository implementations for ViewModel tests

### Integration Testing  
- Test ML pipeline with sample images
- Verify camera data source bitmap conversion
- Repository integration with multiple data sources

## Code Conventions

### Naming
- Use descriptive, non-abbreviated names
- Follow Kotlin naming conventions
- Package structure mirrors architecture layers

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
1. Replace model file in `assets/`
2. Update constants in `VinDetectorImpl` if input/output changed
3. Test with sample images
4. Update model metadata if needed

### Adding New Camera Features
1. Extend `CameraDataSource` interface in domain
2. Implement in `CameraDataSourceImpl`  
3. Add DI binding in `CameraModule`
4. Wire through repository and use case

### Modifying VIN Validation
1. Update logic in `VinValidatorImpl`
2. Add corresponding unit tests
3. Update `VinNumber` domain model if format changes

## Performance Notes

- Frame processing optimized to avoid main thread blocking
- Bitmap recycling handled in camera data source
- TFLite inference runs on background threads
- GPU acceleration when supported, CPU fallback
- Normalized coordinates used to minimize UI recalculation

## Troubleshooting

### Build Issues
- Ensure Android SDK 36 is installed
- Check Java 11 compatibility
- Verify TensorFlow Lite dependencies match in `build.gradle.kts`

### Runtime Issues
- Check camera permissions granted
- Verify TFLite model loaded correctly (check logs)
- Ensure device supports required ML Kit features
- GPU delegate may fail on some devices - CPU fallback should work

## Project Files to Reference

- `project.md` - Comprehensive technical documentation
- `.cursorrules` - Coding conventions and project context
- `syarahvin.MD` - Additional project details and implementation plan