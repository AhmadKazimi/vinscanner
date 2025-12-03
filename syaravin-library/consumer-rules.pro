# Consumer ProGuard rules for VIN Scanner Library
# These rules are automatically applied to apps that consume this library

# Keep public API classes
-keep public class com.syarah.vinscanner.VinScanner { *; }
-keep public class com.syarah.vinscanner.VinScannerContract { *; }
-keep public class com.syarah.vinscanner.VinScanResult { *; }
-keep public class com.syarah.vinscanner.VinScanResult$** { *; }
-keep public class com.syarah.vinscanner.domain.model.VinNumber { *; }

# Keep Parcelable implementation
-keepclassmembers class com.syarah.vinscanner.domain.model.VinNumber {
    public static final ** CREATOR;
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
