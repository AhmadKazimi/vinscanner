# Syaravin VIN Scanner Library

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=24)

Android library for real-time VIN (Vehicle Identification Number) detection and validation using machine learning.

## Features

- 📷 **Real-time Camera Detection** - Live VIN detection using CameraX
- 🤖 **ML-Powered** - TensorFlow Lite object detection + ML Kit OCR
- ✅ **ISO 3779 Validation** - Checksum verification and format validation
- 🎨 **Built-in UI** - Beautiful Material 3 interface with camera preview and result display
- 🚀 **Easy Integration** - Single API call to launch scanner
- 📊 **Confidence Scoring** - AI confidence levels for each detection
- 🚗 **Vehicle Decoding** - Extract manufacturer, model year, and country information
- 🔧 **Zero Configuration** - Auto-initialization, no Application class changes needed
- 🎯 **High Accuracy** - Optimized for various lighting conditions and VIN positions

## Requirements

- Android API 24+ (Android 7.0)
- Camera permission
- ~28MB storage (ML models)
- Device with camera hardware

## Installation

### Step 1: Add Bitbucket Maven Repository

Add the Bitbucket Packages repository to your project. In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()

        // Add Bitbucket Packages repository
        maven {
            url = uri("https://api.bitbucket.org/2.0/repositories/{workspace}/{repo_slug}/maven")
            credentials {
                username = project.findProperty("bitbucket.user") as String?
                password = project.findProperty("bitbucket.appPassword") as String?
            }
        }
    }
}
```

**Replace `{workspace}` and `{repo_slug}` with your actual Bitbucket workspace and repository name.**

### Step 2: Configure Credentials

Create `gradle.properties` in your project root:

```properties
# Bitbucket credentials
bitbucket.user=your_bitbucket_username
bitbucket.appPassword=your_app_password
```

**To get Bitbucket App Password:**
1. Go to https://bitbucket.org/account/settings/app-passwords/
2. Create app password with `Repositories: Read` permission
3. Copy the password to `gradle.properties`

**IMPORTANT:** Add `gradle.properties` to `.gitignore` to avoid committing credentials!

### Step 3: Add Dependency

In your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.kazimi:syaravin-scanner:1.0.0")
}
```

### Step 4: Sync Project

```bash
./gradlew --refresh-dependencies
```

## Quick Start

### Basic Usage

```kotlin
import com.kazimi.syaravin.VinScanner
import com.kazimi.syaravin.VinScanResult

class MainActivity : ComponentActivity() {

    // 1. Register the scanner launcher
    private val vinScannerLauncher = registerForActivityResult(
        VinScanner.Contract()
    ) { result ->
        when (result) {
            is VinScanResult.Success -> {
                val vin = result.vinNumber
                // Use the detected VIN
                Toast.makeText(this, "VIN: ${vin.value}", Toast.LENGTH_LONG).show()

                // Access additional data
                Log.d("VIN", "Confidence: ${vin.confidence}")
                Log.d("VIN", "Valid: ${vin.isValid}")

                // Get cropped VIN image
                val croppedImage: Bitmap? = vin.croppedImage
            }

            is VinScanResult.Cancelled -> {
                Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show()
            }

            is VinScanResult.Error -> {
                Toast.makeText(this, "Error: ${result.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 2. Launch the scanner when needed
    fun startVinScanning() {
        vinScannerLauncher.launch(Unit)
    }
}
```

### Compose Integration

```kotlin
@Composable
fun VinScannerButton() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = VinScanner.Contract()
    ) { result ->
        when (result) {
            is VinScanResult.Success -> {
                // Handle success
            }
            is VinScanResult.Cancelled -> {
                // Handle cancellation
            }
            is VinScanResult.Error -> {
                // Handle error
            }
        }
    }

    Button(onClick = { launcher.launch(Unit) }) {
        Text("Scan VIN")
    }
}
```

## API Reference

### VinScanner

Entry point singleton object for the library.

```kotlin
object VinScanner {
    /**
     * Returns the ActivityResultContract for VIN scanning.
     * Use with registerForActivityResult() in your Activity or Fragment.
     */
    fun Contract(): VinScannerContract

    /**
     * Library version
     */
    const val VERSION: String
}
```

### VinScanResult

Sealed class representing the result of a VIN scan operation.

```kotlin
sealed class VinScanResult {
    /**
     * VIN was successfully detected and validated.
     * @property vinNumber The detected VIN with all metadata
     */
    data class Success(val vinNumber: VinNumber) : VinScanResult()

    /**
     * User cancelled the scanning operation.
     */
    object Cancelled : VinScanResult()

    /**
     * An error occurred during scanning.
     * @property message Error description
     */
    data class Error(val message: String) : VinScanResult()
}
```

### VinNumber

Data class representing a detected Vehicle Identification Number.

```kotlin
data class VinNumber(
    val value: String,           // The 17-character VIN
    val confidence: Float,       // Detection confidence (0.0 to 1.0)
    val isValid: Boolean,        // Whether VIN passes ISO 3779 validation
    val croppedImage: Bitmap?    // Cropped bitmap of detected VIN region
) : Parcelable
```

## Advanced Usage

### Handling Different Result Types

```kotlin
vinScannerLauncher = registerForActivityResult(VinScanner.Contract()) { result ->
    when (result) {
        is VinScanResult.Success -> {
            val vin = result.vinNumber

            // Check validation status
            if (vin.isValid) {
                // VIN passed all validation checks
                saveVinToDatabase(vin.value)
            } else {
                // VIN format is incorrect
                showValidationError(vin.value)
            }

            // Check confidence level
            when {
                vin.confidence >= 0.9f -> {
                    // High confidence - automatically proceed
                    processVin(vin.value)
                }
                vin.confidence >= 0.7f -> {
                    // Medium confidence - ask for confirmation
                    showConfirmationDialog(vin.value)
                }
                else -> {
                    // Low confidence - request manual entry
                    showManualEntryDialog(vin.value)
                }
            }

            // Display cropped image
            vin.croppedImage?.let { bitmap ->
                imageView.setImageBitmap(bitmap)
            }
        }

        is VinScanResult.Cancelled -> {
            Log.d("VIN", "User cancelled scanning")
        }

        is VinScanResult.Error -> {
            showError(result.message)
        }
    }
}
```

## Troubleshooting

### Repository Not Found (401/404)

**Problem:** Gradle can't find the Bitbucket repository.

**Solution:**
1. Verify `gradle.properties` has correct credentials
2. Check Bitbucket App Password has `Repositories: Read` permission
3. Verify workspace and repo_slug in repository URL
4. Test authentication: `curl -u username:app_password https://api.bitbucket.org/2.0/repositories/{workspace}/{repo_slug}`

### Camera Permission Denied

**Problem:** Scanner shows "Camera permission required" error.

**Solution:**
- Library handles runtime permission request automatically
- Ensure `android.permission.CAMERA` is in your app's `AndroidManifest.xml` (optional, library declares it)

### Low Detection Accuracy

**Problem:** VIN not detected or confidence is low.

**Solution:**
- Ensure good lighting conditions
- Hold device steady and align VIN within the camera frame
- Clean camera lens
- VIN should be clearly visible and not obscured
- Works best with embossed/stamped VINs on metal plates

### Build Error - Duplicate Classes

**Problem:** Build fails with "Duplicate class" errors.

**Solution:**
- Library already includes CameraX, TensorFlow Lite, ML Kit dependencies
- Don't add them separately in your app
- Use dependency resolution strategy if needed:

```kotlin
configurations.all {
    resolutionStrategy {
        force("androidx.camera:camera-core:1.3.0")
        force("com.google.ai.edge.litert:litert:1.4.0")
    }
}
```

### Large APK Size

**Problem:** App size increased by ~28MB after adding library.

**Explanation:** Library includes TensorFlow Lite models (~26MB) for ML detection.

**Solutions:**
- Use Android App Bundle (AAB) for Play Store distribution
- Enable App Bundle compression
- Models are essential for offline VIN detection

## Publishing the Library

### Local Testing

```bash
# Publish to local Maven repository
./gradlew :syaravin-library:publishToMavenLocal

# Location: ~/.m2/repository/com/kazimi/syaravin-scanner/1.0.0/
```

### Publish to Bitbucket Packages

```bash
# Ensure gradle.properties has credentials
./gradlew :syaravin-library:publish
```

### Build Sample App

```bash
# Build and install sample app
./gradlew :sample-app:assembleDebug
./gradlew :sample-app:installDebug
```

## Architecture

The library follows Clean Architecture principles:

```
Presentation Layer (UI) - Compose + Material 3
    ↓
Domain Layer (Business Logic) - Use Cases
    ↓
Data Layer (ML Models, Camera, Validation)
```

**Technologies:**
- **UI:** Jetpack Compose + Material 3
- **Camera:** CameraX
- **ML Detection:** TensorFlow Lite (Google AI Edge LiteRT) with GPU acceleration
- **OCR:** ML Kit Text Recognition
- **DI:** Koin
- **Coroutines:** Kotlin Coroutines

## Privacy & Permissions

The library requires:

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

**Privacy Notes:**
- All processing happens on-device (no internet required)
- No data transmitted to external servers
- Camera frames processed in memory and immediately discarded
- Only the final VIN result is returned to your app
- Cropped VIN images stored temporarily in memory

## Performance

- **Detection Time:** ~100-300ms per frame
- **Memory Usage:** ~40-50MB (ML models loaded)
- **Battery Impact:** Moderate (camera + ML processing)
- **Thermal Management:** Built-in throttling to prevent overheating
- **Camera Resolution:** Optimized at 540×960 (portrait)

## License

```
MIT License

Copyright (c) 2025 Kazimi

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Support

For issues and questions, please contact the development team or create an issue in the Bitbucket repository.

## Credits

Developed by Kazimi Team

**Libraries Used:**
- [TensorFlow Lite](https://www.tensorflow.org/lite)
- [ML Kit](https://developers.google.com/ml-kit)
- [CameraX](https://developer.android.com/training/camerax)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Koin](https://insert-koin.io/)
