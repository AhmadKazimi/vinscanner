# Syarahvin - VIN Number Scanner Android Application

## Project Overview

Syarahvin is an Android application that performs real-time VIN (Vehicle Identification Number) detection and extraction using a custom TensorFlow Lite model combined with ML Kit for text recognition.

## Key Features

1. **Live Object Detection**: Real-time VIN number detection using custom TFLite model
2. **Visual Feedback**: Draw bounding box around detected VIN numbers
3. **Text Extraction**: Use ML Kit OCR to extract text from detected regions
4. **VIN Validation**: Validate extracted VIN numbers according to VIN standards
5. **Landscape Mode**: Optimized for landscape camera orientation
6. **Performance Optimized**: Efficient processing with minimal latency
7. **Clean Architecture**: Following MVVM and Clean Architecture principles

## Why Koin over Hilt

- **Simpler Setup**: No annotation processing, faster build times
- **Pure Kotlin**: Written in Kotlin, more idiomatic for Kotlin projects
- **Lightweight**: Smaller runtime overhead compared to Hilt
- **Easy Testing**: Simple module overrides for testing
- **Flexibility**: More control over injection timing and scope management

## Technology Stack

### Core Technologies
- **Language**: Kotlin
- **Minimum SDK**: API 21 (Android 5.0)
- **Target SDK**: API 34 (Android 14)
- **Build System**: Gradle with Kotlin DSL

### Camera & Vision
- **CameraX**: For camera preview and image capture
- **TensorFlow Lite**: For running the custom VIN detection model
- **ML Kit Text Recognition**: For OCR text extraction
- **OpenCV** (Optional): For image preprocessing if needed

### Architecture Components
- **Jetpack Compose**: Modern UI toolkit
- **ViewModel**: For UI state management
- **StateFlow/Flow**: For reactive data streams
- **Koin**: Lightweight dependency injection framework (v3.5+)
- **Coroutines**: For asynchronous operations

### Additional Libraries
- **Timber**: For logging
- **LeakCanary**: For memory leak detection (debug builds)

## Architecture Design

### Clean Architecture Layers

```
┌─────────────────────────────────────────────────────────────┐
│                    Presentation Layer                        │
│  (UI Components, ViewModels, State Management)              │
├─────────────────────────────────────────────────────────────┤
│                      Domain Layer                           │
│  (Use Cases, Business Logic, Domain Models)                │
├─────────────────────────────────────────────────────────────┤
│                       Data Layer                            │
│  (Repositories, Data Sources, ML Models)                   │
└─────────────────────────────────────────────────────────────┘
```

### Module Structure

```
app/
├── src/main/java/com/kazimi/syaravin/
│   ├── di/                          # Dependency Injection
│   │   ├── AppModule.kt
│   │   ├── CameraModule.kt
│   │   ├── MLModule.kt
│   │   ├── RepositoryModule.kt
│   │   └── ViewModelModule.kt
│   │
│   ├── data/                        # Data Layer
│   │   ├── repository/
│   │   │   └── VinScannerRepositoryImpl.kt
│   │   ├── datasource/
│   │   │   ├── camera/
│   │   │   │   └── CameraDataSource.kt
│   │   │   ├── ml/
│   │   │   │   ├── VinDetector.kt
│   │   │   │   └── TextExtractor.kt
│   │   │   └── validator/
│   │   │       └── VinValidator.kt
│   │   └── model/
│   │       ├── DetectionResult.kt
│   │       └── VinValidationResult.kt
│   │
│   ├── domain/                      # Domain Layer
│   │   ├── model/
│   │   │   ├── VinNumber.kt
│   │   │   └── BoundingBox.kt
│   │   ├── repository/
│   │   │   └── VinScannerRepository.kt
│   │   └── usecase/
│   │       ├── DetectVinUseCase.kt
│   │       ├── ExtractTextUseCase.kt
│   │       └── ValidateVinUseCase.kt
│   │
│   ├── presentation/                # Presentation Layer
│   │   ├── scanner/
│   │   │   ├── ScannerScreen.kt
│   │   │   ├── ScannerViewModel.kt
│   │   │   └── ScannerState.kt
│   │   ├── components/
│   │   │   ├── CameraPreview.kt
│   │   │   ├── BoundingBoxOverlay.kt
│   │   │   └── VinResultDialog.kt
│   │   └── theme/
│   │       └── (existing theme files)
│   │
│   ├── util/                        # Utilities
│   │   ├── ImageUtils.kt
│   │   ├── PermissionUtils.kt
│   │   └── Extensions.kt
│   │
│   ├── SyarahvinApplication.kt     # Application class with Koin setup
│   └── MainActivity.kt
│
├── assets/
│   └── models/
│       └── vin_detector.tflite      # Your TFLite model
│
└── res/
    └── (existing resources)
```

## Implementation Plan

### Phase 1: Project Setup and Dependencies

1. **Update Gradle Configuration**
   - Add necessary dependencies in `build.gradle.kts`
   - Configure ML Kit and TensorFlow Lite
   - Set up Koin for dependency injection

2. **Permissions Setup**
   - Camera permission handling
   - Update AndroidManifest.xml

### Phase 2: Core Infrastructure

1. **Set up Clean Architecture Structure**
   - Create package structure
   - Implement base classes and interfaces

2. **Dependency Injection Setup**
   - Configure Koin modules
   - Set up providers for camera, ML components
   - Initialize Koin in Application class

### Phase 3: Camera Implementation

1. **CameraX Integration**
   - Implement camera preview in landscape mode
   - Set up image analysis pipeline
   - Configure optimal preview and analysis resolutions

2. **Image Processing Pipeline**
   - Convert camera frames to appropriate format
   - Implement frame skipping for performance

### Phase 4: ML Integration

1. **TFLite Model Integration**
   - Load and initialize the VIN detection model
   - Implement inference logic
   - Process detection results

2. **ML Kit Text Recognition**
   - Set up ML Kit text recognizer
   - Extract text from detected regions
   - Filter and process extracted text

### Phase 5: Business Logic

1. **VIN Validation**
   - Implement VIN checksum validation
   - Validate VIN format and length
   - Handle edge cases

2. **Use Cases Implementation**
   - Implement detection, extraction, and validation use cases
   - Coordinate between different components

### Phase 6: UI Implementation

1. **Scanner Screen**
   - Implement Compose UI for camera preview
   - Add overlay for bounding boxes
   - Create result display components

2. **User Feedback**
   - Visual indicators for detection status
   - Result presentation
   - Error handling UI

### Phase 7: Optimization

1. **Performance Optimization**
   - Implement frame throttling
   - Optimize model inference
   - Memory management

2. **Testing**
   - Unit tests for business logic
   - Integration tests
   - UI tests

## Technical Implementation Details

### 1. Camera Configuration

```kotlin
// Optimal configuration for VIN scanning
- Preview Resolution: 1920x1080 (landscape)
- Analysis Resolution: 1280x720 (for performance)
- Target Rotation: ROTATION_90 (landscape)
- Image Format: YUV_420_888
```

### 2. ML Model Integration

```kotlin
// TFLite Interpreter configuration
- Number of threads: 4
- Use GPU delegate if available
- Input size: As per your model (e.g., 640x640)
- Output: Bounding boxes with confidence scores
```

### 3. Text Extraction Strategy

```kotlin
// ML Kit configuration
- Use on-device text recognition
- Configure for VIN-specific patterns
- Language: Latin script
```

### 4. VIN Validation Rules

- Length: 17 characters
- Excluded characters: I, O, Q
- Check digit validation (9th position)
- Format: [A-HJ-NPR-Z0-9]{17}

### 5. Performance Optimization Strategies

1. **Frame Processing**
   - Process every 3rd frame to reduce load
   - Use coroutines for async processing
   - Implement backpressure handling

2. **Memory Management**
   - Reuse bitmap allocations
   - Clear resources properly
   - Monitor memory usage

3. **UI Optimization**
   - Use hardware acceleration
   - Efficient bounding box drawing
   - Smooth animations

### 6. Error Handling

1. **Camera Errors**
   - Handle permission denial
   - Camera initialization failures
   - Device compatibility issues

2. **ML Errors**
   - Model loading failures
   - Inference errors
   - Invalid detection results

3. **Network Errors** (if applicable)
   - Offline capability
   - Retry mechanisms

### 7. Koin Dependency Injection Setup

```kotlin
// Application class setup
class SyarahvinApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@SyarahvinApplication)
            modules(
                appModule,
                cameraModule,
                mlModule,
                repositoryModule,
                viewModelModule
            )
        }
    }
}

// Module examples
val appModule = module {
    single { androidContext() }
}

val viewModelModule = module {
    viewModel { ScannerViewModel(get(), get(), get()) }
}

val repositoryModule = module {
    single<VinScannerRepository> { VinScannerRepositoryImpl(get(), get(), get()) }
}
```

## Testing Strategy

### 1. Unit Tests
- VIN validation logic
- Business logic in use cases
- Data transformations

### 2. Integration Tests
- Repository implementations
- ML model integration
- Camera integration

### 3. UI Tests
- Permission flows
- Camera preview
- Result display

### 4. Performance Tests
- Frame processing speed
- Memory usage
- Battery consumption

## Security Considerations

1. **Data Privacy**
   - No VIN data storage without user consent
   - Secure any cached data
   - Clear sensitive data from memory

2. **Model Security**
   - Obfuscate model file if needed
   - Validate model integrity

## Deployment Considerations

1. **App Size**
   - Use App Bundle for optimized APK size
   - Consider model quantization
   - Remove unused resources

2. **Device Compatibility**
   - Test on various devices
   - Handle different camera capabilities
   - Graceful degradation for older devices

## Future Enhancements

1. **Additional Features**
   - Barcode scanning support
   - History of scanned VINs
   - Export functionality
   - Cloud backup

2. **Model Improvements**
   - Model updates mechanism
   - A/B testing for models
   - Performance metrics collection

## Development Timeline

### Week 1-2: Foundation
- Project setup and dependencies
- Basic camera implementation
- Clean architecture structure

### Week 3-4: ML Integration
- TFLite model integration
- ML Kit text recognition
- Basic detection pipeline

### Week 5-6: Business Logic & UI
- VIN validation
- Complete UI implementation
- Error handling

### Week 7-8: Optimization & Testing
- Performance optimization
- Comprehensive testing
- Bug fixes and polish

## Conclusion

This plan provides a comprehensive roadmap for implementing the Syarahvin VIN scanner application. The architecture is designed to be maintainable, testable, and performant while following Android best practices and clean architecture principles.