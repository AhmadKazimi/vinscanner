# Consumer ProGuard rules for VIN Scanner Library
# These rules are automatically applied to apps that consume this library

# ==========================================
# PUBLIC API - Keep all public classes and prevent obfuscation
# ==========================================

# Main entry point - VinScanner object
-keep,allowshrinking class com.syarah.vinscanner.VinScanner {
    public *;
}

# ActivityResultContract for launching scanner
-keep,allowshrinking class com.syarah.vinscanner.VinScannerContract {
    public *;
}

# Sealed class - VinScanResult and ALL nested classes
-keep,allowshrinking class com.syarah.vinscanner.VinScanResult {
    *;
}

# IMPORTANT: Explicitly keep nested classes (they're obfuscated separately!)
-keep,allowshrinking class com.syarah.vinscanner.VinScanResult$Success {
    *;
}

-keep,allowshrinking class com.syarah.vinscanner.VinScanResult$Error {
    *;
}

-keep,allowshrinking class com.syarah.vinscanner.VinScanResult$Cancelled {
    *;
}

# VinNumber data class with ALL properties
-keep,allowshrinking class com.syarah.vinscanner.domain.model.VinNumber {
    *;
}

# Keep VinNumber companion object constants (VIN_LENGTH, INVALID_CHARACTERS, VALID_PATTERN)
-keep,allowshrinking class com.syarah.vinscanner.domain.model.VinNumber$Companion {
    *;
}

# ==========================================
# PARCELABLE - Required for Android parceling
# ==========================================

# Keep Parcelable CREATOR fields
-keepclassmembers class com.syarah.vinscanner.domain.model.VinNumber {
    public static final ** CREATOR;
}

# Keep data class methods (component1, component2, copy, etc.)
-keepclassmembers class com.syarah.vinscanner.domain.model.VinNumber {
    *** component1();
    *** component2();
    *** component3();
    *** component4();
    *** copy(...);
}

# Keep sealed class subclasses
-keepclassmembers class com.syarah.vinscanner.VinScanResult {
    public ** Companion;
}

# ==========================================
# ATTRIBUTES - Preserve metadata for debugging
# ==========================================

# Preserve annotations
-keepattributes *Annotation*

# Preserve source file names and line numbers
-keepattributes SourceFile,LineNumberTable

# Keep signatures for generics
-keepattributes Signature

# Keep exceptions for debugging
-keepattributes Exceptions

# ==========================================
# ADDITIONAL SAFETY - Catch any missed public APIs
# ==========================================

# Keep all public methods in public API package
-keepclassmembers class com.syarah.vinscanner.** {
    public <methods>;
    public <fields>;
}

# Keep all Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# TensorFlow Lite rules
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# ML Kit rules
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# Koin DI rules
-keep class org.koin.** { *; }
-keep class kotlin.Metadata { *; }

# CameraX rules
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Jetpack Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep model classes and data classes
-keep class com.syarah.vinscanner.data.model.** { *; }
-keepclassmembers class com.syarah.vinscanner.data.model.** {
    <fields>;
    <methods>;
}

# Prevent obfuscation of internal library classes that use reflection
-keep class com.syarah.vinscanner.util.VinDecoder { *; }
-keep class com.syarah.vinscanner.data.datasource.validator.VinValidatorImpl { *; }

# Asset files
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Suppress warnings
-dontwarn org.tensorflow.**
-dontwarn com.google.ai.edge.litert.**
-dontwarn kotlin.reflect.jvm.internal.**
