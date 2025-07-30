package com.kazimi.syaravin.di

import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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

val mlModule = module {
    // TensorFlow Lite Interpreter
    single {
        val context = androidContext()
        val modelPath = "best_float32.tflite"
        
        // Load model from assets
        val modelBuffer = context.assets.open(modelPath).use { inputStream ->
            val byteArray = ByteArray(inputStream.available())
            inputStream.read(byteArray)
            java.nio.ByteBuffer.allocateDirect(byteArray.size).apply {
                order(java.nio.ByteOrder.nativeOrder())
                put(byteArray)
            }
        }
        
        // Configure interpreter options
        val options = Interpreter.Options().apply {
            setNumThreads(4)
            
            // Use GPU delegate if available
            val compatibilityList = CompatibilityList()
            if (compatibilityList.isDelegateSupportedOnThisDevice) {
                val delegateOptions = compatibilityList.bestOptionsForThisDevice
                addDelegate(GpuDelegate(delegateOptions))
            }
        }
        
        Interpreter(modelBuffer, options)
    }
    
    // ML Kit Text Recognizer
    single<TextRecognizer> {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    
    // VIN Detector
    single<VinDetector> { VinDetectorImpl(get()) }
    
    // Text Extractor
    single<TextExtractor> { TextExtractorImpl(get()) }
    
    // VIN Validator
    single<VinValidator> { VinValidatorImpl() }
}