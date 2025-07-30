package com.kazimi.syaravin.di


import com.kazimi.syaravin.data.datasource.ml.TextExtractor
import com.kazimi.syaravin.data.datasource.ml.TextExtractorImpl
import com.kazimi.syaravin.data.datasource.ml.VinDetector
import com.kazimi.syaravin.data.datasource.ml.VinDetectorImpl
import com.kazimi.syaravin.data.datasource.validator.VinValidator
import com.kazimi.syaravin.data.datasource.validator.VinValidatorImpl
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import timber.log.Timber
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

val mlModule = module {
    // LiteRT Interpreter
    single {
        Timber.d("Creating LiteRT Interpreter")
        val context = androidContext()
        val modelPath = "best_float32.tflite"
        
        // Load model from assets
        val assetFileDescriptor = context.assets.openFd(modelPath)
        val modelBuffer = FileInputStream(assetFileDescriptor.fileDescriptor).use { inputStream ->
            val fileChannel = inputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        }
        Timber.d("Model loaded from assets: $modelPath")
        
        // Configure interpreter options
        val options = Interpreter.Options().apply {
            setNumThreads(4)
            
            // Use GPU delegate if available
            val compatibilityList = CompatibilityList()
            if (compatibilityList.isDelegateSupportedOnThisDevice) {
                Timber.d("GPU delegate is supported on this device.")
                val delegateOptions = compatibilityList.bestOptionsForThisDevice
                addDelegate(GpuDelegate(delegateOptions))
                Timber.d("GPU delegate added.")
            } else {
                Timber.d("GPU delegate is not supported on this device.")
            }
        }
        
        val interpreter = Interpreter(modelBuffer, options)
        Timber.d("LiteRT Interpreter created successfully.")
        interpreter
    }
    
    // VIN Detector
    single<VinDetector> { VinDetectorImpl(get()) }
    
    // Text Extractor
    single<TextExtractor> { TextExtractorImpl(get()) }
    
    // VIN Validator
    single<VinValidator> { VinValidatorImpl() }
}