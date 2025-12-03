# Syarah VIN Scanner

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=24)
[![](https://jitpack.io/v/com.github.AhmadKazimi/vinscanner.svg)](https://jitpack.io/#com.github.AhmadKazimi/vinscanner)

Android library for real-time VIN (Vehicle Identification Number) detection and validation using machine learning.

## ✨ Features

- 📷 **Real-time Camera Detection** - Live VIN detection using CameraX
- 🤖 **ML-Powered** - TensorFlow Lite object detection + ML Kit OCR
- ✅ **ISO 3779 Validation** - Checksum verification and format validation
- 🎨 **Modern UI** - Beautiful Material 3 bottom sheet interface
- 🚀 **Easy Integration** - Single API call to launch scanner
- 📊 **Confidence Scoring** - AI confidence levels for each detection
- 🚗 **Vehicle Decoding** - Extract manufacturer, model year, and country information
- 🔧 **Zero Configuration** - Auto-initialization, no Application class changes needed
- 🎯 **High Accuracy** - Optimized for various lighting conditions and VIN positions
- 🖼️ **VIN Image Capture** - Returns cropped image of detected VIN
- 🎨 **Visual Feedback** - Dynamic ROI border colors indicating detection status
- ⚡ **Thermal Management** - Built-in throttling to prevent device overheating

## 📋 Requirements

- Android API 24+ (Android 7.0)
- Camera permission
- ~28MB storage (ML models)
- Device with camera hardware
- Java 21

## 📦 Installation

### Step 1: Add JitPack Repository

Add JitPack to your project. In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### Step 2: Add Dependency

In your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.AhmadKazimi:vinscanner:v1.0.2")
}
```

### Step 3: Sync Project

Click "Sync Now" in Android Studio or run:

```bash
./gradlew build
```

That's it! No credentials, no complex setup. 🎉

## 🚀 Quick Start

### Basic Usage

```kotlin
import com.syarah.vinscanner.VinScanner
import com.syarah.vinscanner.VinScanResult
import com.syarah.vinscanner.domain.model.VinNumber

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

### Jetpack Compose Integration

```kotlin
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import com.syarah.vinscanner.VinScanner
import com.syarah.vinscanner.VinScanResult

@Composable
fun VinScannerScreen() {
    var vinResult by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = VinScanner.Contract()
    ) { result ->
        when (result) {
            is VinScanResult.Success -> {
                vinResult = "VIN: ${result.vinNumber.value}"
            }
            is VinScanResult.Cancelled -> {
                vinResult = "Scan cancelled"
            }
            is VinScanResult.Error -> {
                vinResult = "Error: ${result.message}"
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Button(onClick = { launcher.launch(Unit) }) {
            Text("Start VIN Scan")
        }

        vinResult?.let {
            Text(it, modifier = Modifier.padding(top = 16.dp))
        }
    }
}
```

## 📖 API Reference

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
sealed class VinScanResult : Parcelable {
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

## 🎯 Advanced Usage

### Handling Different Confidence Levels

```kotlin
vinScannerLauncher = registerForActivityResult(VinScanner.Contract()) { result ->
    when (result) {
        is VinScanResult.Success -> {
            val vin = result.vinNumber

            // Check validation status
            if (vin.isValid) {
                // VIN passed all validation checks including ISO 3779 checksum
                saveVinToDatabase(vin.value)
            } else {
                // VIN format is incorrect
                showValidationError(vin.value)
            }

            // Confidence-based handling
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
                    // Low confidence - request manual verification
                    showManualEntryDialog(vin.value)
                }
            }

            // Save or display cropped image
            vin.croppedImage?.let { bitmap ->
                // Save to file, display in ImageView, etc.
                saveVinImage(bitmap, vin.value)
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

### Using VIN Decoder

```kotlin
import com.syarah.vinscanner.util.VinDecoder
import org.koin.android.ext.android.inject

class VinDetailsActivity : AppCompatActivity() {
    private val vinDecoder: VinDecoder by inject()

    fun decodeVin(vin: String) {
        val vinInfo = vinDecoder.decode(vin)

        vinInfo?.let {
            println("Manufacturer: ${it.manufacturer}")
            println("Country: ${it.country}")
            println("Model Year: ${it.modelYear}")
        } ?: run {
            println("VIN could not be decoded")
        }
    }
}
```

## ⚙️ Configuration

The library is designed to work out of the box with sensible defaults. However, you can customize behavior:

### ROI Configuration

The Region of Interest can be adjusted in your fork by modifying `RoiConfig.kt`:

```kotlin
// Default ROI covers 40-60% vertical area, 5% horizontal padding
val roi = BoundingBox(
    left = 0.05f,
    top = 0.4f,
    right = 0.95f,
    bottom = 0.6f
)
```

### Thermal Management

Built-in thermal throttling prevents device overheating:
- Max processing rate: 3 FPS
- Max average processing time: 200ms
- Automatic frame skipping under high load

## 🔧 Troubleshooting

### Camera Permission Denied

**Problem:** Scanner shows "Camera permission required" error.

**Solution:**
- Library handles runtime permission request automatically
- Ensure your app's `targetSdk` is set correctly
- Camera permission is automatically merged from library manifest

### Low Detection Accuracy

**Problem:** VIN not detected or confidence is low.

**Solution:**
- Ensure good lighting conditions
- Hold device steady and align VIN within the ROI guide
- Clean camera lens
- VIN should be clearly visible and not obscured
- Works best with embossed/stamped VINs on metal plates
- Try adjusting distance from VIN (15-30cm optimal)

### Build Errors

**Problem:** Build fails with dependency conflicts.

**Solution:**
- Library uses Java 21 - ensure your project uses Java 21+
- Check `jitpack.yml` configuration
- Clean and rebuild: `./gradlew clean build`

## 📊 Performance

- **Detection Time:** ~100-300ms per frame
- **Memory Usage:** ~40-50MB (ML models loaded)
- **Battery Impact:** Moderate (camera + ML processing)
- **Camera Resolution:** Optimized at 540×960 (portrait)
- **Frame Processing:** Up to 3 FPS (thermal throttling)
- **Model Size:** ~26MB (float16 TFLite model)

## 🏗️ Architecture

The library follows Clean Architecture principles:

```
┌─────────────────────────────────────┐
│  Presentation Layer                 │
│  - Compose UI                       │
│  - ViewModels                       │
│  - Material 3 Components            │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│  Domain Layer                       │
│  - Use Cases                        │
│  - Domain Models                    │
│  - Repository Interfaces            │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│  Data Layer                         │
│  - ML Detection (TFLite + GPU)      │
│  - OCR (ML Kit)                     │
│  - Camera (CameraX)                 │
│  - Validation (ISO 3779)            │
└─────────────────────────────────────┘
```

**Technologies:**
- **UI:** Jetpack Compose + Material 3
- **Camera:** CameraX with portrait optimization
- **ML Detection:** TensorFlow Lite (Google AI Edge LiteRT) with GPU acceleration
- **OCR:** ML Kit Text Recognition v2
- **DI:** Koin
- **Coroutines:** Kotlin Coroutines + Flow
- **Threading:** Dispatchers.Default for ML, dedicated executor for camera

## 🔒 Privacy & Permissions

The library requires:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" />
```

**Privacy Notes:**
- ✅ All processing happens **on-device** (no internet required)
- ✅ No data transmitted to external servers
- ✅ Camera frames processed in memory and immediately discarded
- ✅ Only the final VIN result is returned to your app
- ✅ Cropped VIN images stored temporarily in memory
- ✅ No analytics or tracking
- ✅ GDPR compliant (no personal data collected)

## 🚀 Publishing Your Own Version


### Build Sample App

```bash
# Build and install sample app
./gradlew :sample-app:assembleDebug
./gradlew :sample-app:installDebug

# Or combined
./gradlew :sample-app:installDebug
```

## 📝 License

```
MIT License

Copyright (c) 2025 Syarah

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

## 🆘 Support

- **Repository:** https://github.com/AhmadKazimi/vinscanner
- **Issues:** Report issues in the GitHub repository
- **JitPack:** https://jitpack.io/#com.github.AhmadKazimi/vinscanner
- **Developer Email:** Ahmad.kazimi@syarah.com

## 👏 Credits

Developed by Ahmad Kazimi

**Libraries Used:**
- [TensorFlow Lite](https://www.tensorflow.org/lite) - ML model inference
- [ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition/v2) - OCR
- [CameraX](https://developer.android.com/training/camerax) - Camera API
- [Jetpack Compose](https://developer.android.com/jetpack/compose) - Modern UI toolkit
- [Koin](https://insert-koin.io/) - Dependency injection
- [Material 3](https://m3.material.io/) - Design system

---

**Made with ❤️ by Kazimi**