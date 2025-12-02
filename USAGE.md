# Syaravin VIN Scanner - Detailed Usage Guide

This guide provides comprehensive information on integrating and using the Syaravin VIN Scanner library in your Android application.

## Table of Contents

- [Installation](#installation)
- [Basic Integration](#basic-integration)
- [Integration Patterns](#integration-patterns)
- [Handling Results](#handling-results)
- [Testing Your Integration](#testing-your-integration)
- [Best Practices](#best-practices)
- [Common Issues](#common-issues)

## Installation

### Prerequisites

Before integrating the library, ensure your project meets these requirements:

- **Minimum SDK:** API 24 (Android 7.0)
- **Compile SDK:** API 36 or higher
- **Gradle:** 8.0+
- **Kotlin:** 1.9.0+
- **Java:** 11+

### Step-by-Step Setup

#### 1. Configure Bitbucket Access

In your project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // Bitbucket Packages repository
        maven {
            url = uri("https://api.bitbucket.org/2.0/repositories/{workspace}/{repo}/maven")
            credentials {
                username = project.findProperty("bitbucket.user") as String?
                password = project.findProperty("bitbucket.appPassword") as String?
            }
        }
    }
}
```

#### 2. Add Credentials

Create `gradle.properties` in your project root:

```properties
# Bitbucket authentication
bitbucket.user=your_username
bitbucket.appPassword=your_app_password
```

Add to `.gitignore`:

```
gradle.properties
local.properties
```

#### 3. Add Library Dependency

In `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.kazimi:syaravin-scanner:1.0.0")

    // Your other dependencies...
}
```

#### 4. Sync and Verify

```bash
./gradlew --refresh-dependencies
./gradlew :sample-app:assembleDebug
```

## Basic Integration

### Simple VIN Capture

The most basic integration - just capture the VIN and display it:

```kotlin
class VinCaptureActivity : ComponentActivity() {

    private val vinLauncher = registerForActivityResult(VinScanner.Contract()) { result ->
        if (result is VinScanResult.Success) {
            val vin = result.vinNumber.value
            Toast.makeText(this, "VIN: $vin", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Button(onClick = { vinLauncher.launch(Unit) }) {
                    Text("Scan VIN")
                }
            }
        }
    }
}
```

### With Validation Feedback

Show validation status to the user:

```kotlin
private val vinLauncher = registerForActivityResult(VinScanner.Contract()) { result ->
    when (result) {
        is VinScanResult.Success -> {
            val vin = result.vinNumber
            if (vin.isValid) {
                showSuccess("Valid VIN: ${vin.value}")
                proceedWithValidVin(vin.value)
            } else {
                showWarning("Invalid VIN format. Please verify.")
                allowManualCorrection(vin.value)
            }
        }
        is VinScanResult.Cancelled -> {
            showInfo("Scan cancelled. You can enter VIN manually.")
        }
        is VinScanResult.Error -> {
            showError("Unable to scan: ${result.message}")
        }
    }
}
```

## Integration Patterns

### Pattern 1: Confidence-Based Processing

Different actions based on detection confidence:

```kotlin
private val vinLauncher = registerForActivityResult(VinScanner.Contract()) { result ->
    if (result is VinScanResult.Success) {
        val vin = result.vinNumber

        when {
            vin.confidence >= 0.95f -> {
                // Very high confidence - auto-proceed
                Log.d("VIN", "High confidence: ${vin.confidence}")
                autoProcessVin(vin.value)
            }

            vin.confidence >= 0.8f -> {
                // High confidence - show quick confirmation
                showQuickConfirmation(
                    message = "VIN detected: ${vin.value}",
                    confidence = vin.confidence
                ) { confirmed ->
                    if (confirmed) processVin(vin.value)
                }
            }

            vin.confidence >= 0.6f -> {
                // Medium confidence - detailed review
                showDetailedReview(vin) { editedVin ->
                    if (editedVin != null) processVin(editedVin)
                }
            }

            else -> {
                // Low confidence - request manual entry
                showManualEntryDialog(initialValue = vin.value)
            }
        }
    }
}

private fun showQuickConfirmation(
    message: String,
    confidence: Float,
    onResult: (Boolean) -> Unit
) {
    AlertDialog.Builder(this)
        .setTitle("Confirm VIN")
        .setMessage("$message\n\nConfidence: ${(confidence * 100).toInt()}%")
        .setPositiveButton("Confirm") { _, _ -> onResult(true) }
        .setNegativeButton("Retry") { _, _ -> onResult(false) }
        .show()
}
```

### Pattern 2: Store Cropped VIN Image

Save the VIN image for audit trails or records:

```kotlin
private val vinLauncher = registerForActivityResult(VinScanner.Contract()) { result ->
    if (result is VinScanResult.Success) {
        val vin = result.vinNumber

        // Save VIN value to database
        saveVinToDatabase(vin.value, vin.confidence, vin.isValid)

        // Save cropped image if available
        vin.croppedImage?.let { bitmap ->
            val imageFile = saveVinImage(bitmap, vin.value)
            attachImageToRecord(vin.value, imageFile)
            Log.d("VIN", "Image saved: ${imageFile.absolutePath}")
        }
    }
}

private fun saveVinImage(bitmap: Bitmap, vinValue: String): File {
    val filename = "vin_${vinValue}_${System.currentTimeMillis()}.jpg"
    val file = File(getExternalFilesDir(null), filename)

    file.outputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
    }

    return file
}
```

### Pattern 3: Jetpack Compose with State Management

Full Compose integration with ViewModel:

```kotlin
// ViewModel
class VinViewModel : ViewModel() {
    private val _vinState = MutableStateFlow<VinState>(VinState.Idle)
    val vinState: StateFlow<VinState> = _vinState.asStateFlow()

    fun setVin(vinNumber: VinNumber) {
        _vinState.value = VinState.Success(vinNumber)
    }

    fun setScanCancelled() {
        _vinState.value = VinState.Cancelled
    }

    fun setError(message: String) {
        _vinState.value = VinState.Error(message)
    }

    fun reset() {
        _vinState.value = VinState.Idle
    }
}

sealed class VinState {
    object Idle : VinState()
    data class Success(val vinNumber: VinNumber) : VinState()
    object Cancelled : VinState()
    data class Error(val message: String) : VinState()
}

// Composable Screen
@Composable
fun VinInputScreen(viewModel: VinViewModel = viewModel()) {
    val vinState by viewModel.vinState.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        contract = VinScanner.Contract()
    ) { result ->
        when (result) {
            is VinScanResult.Success -> viewModel.setVin(result.vinNumber)
            is VinScanResult.Cancelled -> viewModel.setScanCancelled()
            is VinScanResult.Error -> viewModel.setError(result.message)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Button(
            onClick = { launcher.launch(Unit) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Scan VIN")
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (val state = vinState) {
            is VinState.Success -> {
                VinDisplay(
                    vin = state.vinNumber.value,
                    confidence = state.vinNumber.confidence,
                    isValid = state.vinNumber.isValid,
                    image = state.vinNumber.croppedImage
                )
            }
            is VinState.Cancelled -> {
                Text("Scan was cancelled")
            }
            is VinState.Error -> {
                Text("Error: ${state.message}", color = MaterialTheme.colorScheme.error)
            }
            VinState.Idle -> {
                Text("Tap button to scan VIN")
            }
        }
    }
}

@Composable
fun VinDisplay(
    vin: String,
    confidence: Float,
    isValid: Boolean,
    image: Bitmap?
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("VIN: $vin", style = MaterialTheme.typography.titleLarge)
            Text("Confidence: ${(confidence * 100).toInt()}%")
            Text(
                text = if (isValid) "Valid ✓" else "Invalid ✗",
                color = if (isValid) Color.Green else Color.Red
            )

            image?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "VIN Image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}
```

### Pattern 4: Background Service Integration

For apps that need to scan VINs as part of a larger workflow:

```kotlin
class VehicleInspectionActivity : ComponentActivity() {

    private val inspectionData = mutableStateMapOf<String, Any>()

    private val vinLauncher = registerForActivityResult(VinScanner.Contract()) { result ->
        if (result is VinScanResult.Success) {
            inspectionData["vin"] = result.vinNumber.value
            inspectionData["vin_confidence"] = result.vinNumber.confidence
            inspectionData["vin_image"] = result.vinNumber.croppedImage

            // Move to next step in inspection
            proceedToNextInspectionStep()
        }
    }

    private fun startVinScanStep() {
        vinLauncher.launch(Unit)
    }

    private fun proceedToNextInspectionStep() {
        // Continue with inspection workflow
        // e.g., take photos, record mileage, etc.
    }
}
```

## Handling Results

### Extracting VIN Components

Decode VIN information manually:

```kotlin
fun decodeVin(vin: String): VinInfo {
    require(vin.length == 17) { "VIN must be 17 characters" }

    return VinInfo(
        wmi = vin.substring(0, 3),              // World Manufacturer Identifier
        vds = vin.substring(3, 9),              // Vehicle Descriptor Section
        checkDigit = vin[8],                     // Check digit
        modelYear = decodeModelYear(vin[9]),    // Model year
        plantCode = vin[10],                     // Assembly plant
        serialNumber = vin.substring(11)         // Serial number
    )
}

fun decodeModelYear(char: Char): Int? {
    return when (char) {
        'A' -> 1980; 'B' -> 1981; 'C' -> 1982; 'D' -> 1983
        'E' -> 1984; 'F' -> 1985; 'G' -> 1986; 'H' -> 1987
        'J' -> 1988; 'K' -> 1989; 'L' -> 1990; 'M' -> 1991
        'N' -> 1992; 'P' -> 1993; 'R' -> 1994; 'S' -> 1995
        'T' -> 1996; 'V' -> 1997; 'W' -> 1998; 'X' -> 1999
        'Y' -> 2000; '1' -> 2001; '2' -> 2002; '3' -> 2003
        '4' -> 2004; '5' -> 2005; '6' -> 2006; '7' -> 2007
        '8' -> 2008; '9' -> 2009; 'A' -> 2010; 'B' -> 2011
        // Pattern repeats every 30 years
        else -> null
    }
}

data class VinInfo(
    val wmi: String,
    val vds: String,
    val checkDigit: Char,
    val modelYear: Int?,
    val plantCode: Char,
    val serialNumber: String
)
```

## Testing Your Integration

### Unit Tests

```kotlin
@Test
fun `test VIN processing with high confidence`() {
    val vinNumber = VinNumber(
        value = "1HGBH41JXMN109186",
        confidence = 0.95f,
        isValid = true,
        croppedImage = null
    )

    val result = VinScanResult.Success(vinNumber)

    // Test your processing logic
    val shouldAutoProceed = result.vinNumber.confidence >= 0.9f
    assertTrue(shouldAutoProceed)
    assertEquals("1HGBH41JXMN109186", result.vinNumber.value)
}

@Test
fun `test VIN validation`() {
    val validVin = "1HGBH41JXMN109186"
    val invalidVin = "INVALID123VIN4567"

    // Assuming you have access to validation logic
    assertTrue(isValidVin(validVin))
    assertFalse(isValidVin(invalidVin))
}
```

### Integration Tests

```kotlin
@Test
fun testVinScannerLaunch() {
    val scenario = ActivityScenario.launch(MainActivity::class.java)

    // Click scan button
    onView(withId(R.id.btnScanVin)).perform(click())

    // Verify scanner activity launched
    // (Requires additional instrumentation test setup)

    scenario.close()
}
```

## Best Practices

### 1. Handle All Result Types

Always handle all three result types:

```kotlin
✅ GOOD:
vinLauncher = registerForActivityResult(VinScanner.Contract()) { result ->
    when (result) {
        is VinScanResult.Success -> { /* handle */ }
        is VinScanResult.Cancelled -> { /* handle */ }
        is VinScanResult.Error -> { /* handle */ }
    }
}

❌ BAD:
vinLauncher = registerForActivityResult(VinScanner.Contract()) { result ->
    if (result is VinScanResult.Success) {
        // Only handling success, ignoring other cases
    }
}
```

### 2. Validate Before Processing

Even though the library validates, add your own checks:

```kotlin
✅ GOOD:
if (result is VinScanResult.Success) {
    val vin = result.vinNumber
    if (vin.isValid && vin.value.length == 17) {
        processVin(vin.value)
    } else {
        requestManualEntry()
    }
}
```

### 3. Provide User Feedback

Always inform users about the scan result:

```kotlin
✅ GOOD:
when (result) {
    is VinScanResult.Success -> {
        Toast.makeText(this, "VIN captured successfully", Toast.LENGTH_SHORT).show()
    }
    is VinScanResult.Cancelled -> {
        Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show()
    }
    is VinScanResult.Error -> {
        Toast.makeText(this, "Scan failed: ${result.message}", Toast.LENGTH_LONG).show()
    }
}
```

### 4. Store Confidence Scores

Save confidence for audit purposes:

```kotlin
✅ GOOD:
data class VinRecord(
    val vin: String,
    val confidence: Float,
    val capturedAt: Long,
    val isValid: Boolean,
    val imagePath: String?
)
```

## Common Issues

### Issue: Result Not Received

**Symptoms:** Launcher callback never called.

**Solutions:**
- Ensure you're using `registerForActivityResult()` before `onCreate()` completes
- Check that the activity isn't being recreated
- Verify you're calling `launch(Unit)` correctly

```kotlin
// ✅ Correct
class MainActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(VinScanner.Contract()) { ... }

    fun scanVin() {
        launcher.launch(Unit)
    }
}

// ❌ Wrong
class MainActivity : ComponentActivity() {
    fun scanVin() {
        val launcher = registerForActivityResult(VinScanner.Contract()) { ... }
        launcher.launch(Unit)  // Will crash!
    }
}
```

### Issue: Memory Leaks

**Symptoms:** App memory increases over time.

**Solutions:**
- Let garbage collector handle bitmaps
- Don't hold references to VinNumber objects longer than needed

```kotlin
✅ GOOD:
vinLauncher = registerForActivityResult(VinScanner.Contract()) { result ->
    if (result is VinScanResult.Success) {
        processVin(result.vinNumber.value)
        // Don't store the bitmap unless necessary
    }
}

❌ BAD:
var lastScannedVin: VinNumber? = null  // Holds bitmap in memory

vinLauncher = registerForActivityResult(VinScanner.Contract()) { result ->
    if (result is VinScanResult.Success) {
        lastScannedVin = result.vinNumber  // Memory leak!
    }
}
```

### Issue: Crashes on Some Devices

**Symptoms:** App crashes on specific devices.

**Solutions:**
- Ensure minimum SDK is 24+
- Check that devices have camera hardware
- Handle permission denial gracefully

```kotlin
✅ GOOD:
vinLauncher = registerForActivityResult(VinScanner.Contract()) { result ->
    try {
        when (result) {
            is VinScanResult.Success -> processVin(result.vinNumber.value)
            is VinScanResult.Error -> showError(result.message)
            is VinScanResult.Cancelled -> { /* handled */ }
        }
    } catch (e: Exception) {
        Log.e("VIN", "Error processing result", e)
        showError("Failed to process VIN: ${e.message}")
    }
}
```

## Support

For additional help:
- Check the main [README.md](README.md)
- Review the sample app in `/sample-app`
- Contact the development team
- Open an issue in Bitbucket repository
