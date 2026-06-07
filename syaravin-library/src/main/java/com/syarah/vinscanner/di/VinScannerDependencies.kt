package com.syarah.vinscanner.di

import com.syarah.vinscanner.util.LogTags

import android.content.Context
import android.os.SystemClock
import com.syarah.vinscanner.util.SLog
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.Preview
import com.syarah.vinscanner.data.datasource.camera.CameraDataSource
import com.syarah.vinscanner.data.datasource.camera.CameraDataSourceImpl
import com.syarah.vinscanner.data.datasource.ml.TextExtractor
import com.syarah.vinscanner.data.datasource.ml.TextExtractorImpl
import com.syarah.vinscanner.data.datasource.ml.VinDetector
import com.syarah.vinscanner.data.datasource.ml.VinDetectorImpl
import com.syarah.vinscanner.data.datasource.validator.VinValidator
import com.syarah.vinscanner.data.datasource.validator.VinValidatorImpl
import com.syarah.vinscanner.data.repository.VinScannerRepositoryImpl
import com.syarah.vinscanner.domain.repository.VinScannerRepository
import com.syarah.vinscanner.domain.usecase.DetectVinUseCase
import com.syarah.vinscanner.domain.usecase.ExtractTextUseCase
import com.syarah.vinscanner.domain.usecase.ValidateVinUseCase
import com.syarah.vinscanner.presentation.scanner.ScannerViewModel
import com.syarah.vinscanner.presentation.scanner.ScannerViewModelStrings
import com.syarah.vinscanner.util.VinDecoder
import com.syarah.vinscanner.util.ScannerPerfConfig
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thread-safe dependency injection factory for the VIN Scanner library.
 * Provides both singleton instances for expensive resources (TFLite model, ML components)
 * and factory methods for per-screen lifecycle objects (camera components, executors).
 */
internal object VinScannerDependencies {
    private const val TAG = LogTags.LIBRARY

    @Volatile
    private var instance: DependencyContainer? = null

    /**
     * Initialize the dependency container with an application context.
     * Thread-safe and idempotent - safe to call multiple times.
     *
     * @param appContext Application or Activity context (will extract applicationContext)
     */
    fun initialize(appContext: Context) {
        if (instance == null) {
            synchronized(this) {
                if (instance == null) {
                    val startMs = SystemClock.elapsedRealtime()
                    instance = DependencyContainer(appContext.applicationContext)
                    SLog.w(TAG, "DependencyContainer created in ${SystemClock.elapsedRealtime() - startMs}ms")
                }
            }
        }
    }

    /**
     * Get the dependency container instance.
     * Must call initialize() first.
     *
     * @return DependencyContainer with all dependencies
     * @throws IllegalStateException if not initialized
     */
    fun get(): DependencyContainer {
        return instance ?: synchronized(this) {
            instance ?: throw IllegalStateException(
                "VinScannerDependencies not initialized. Call initialize(context) first."
            )
        }
    }

    /**
     * Release all singleton resources created by this library.
     * Safe to call multiple times.
     */
    fun release() {
        synchronized(this) {
            val current = instance ?: return
            runCatching { current.release() }
                .onFailure { SLog.w(TAG, "Failed to release VIN Scanner dependencies", it) }
            instance = null
            SLog.d(TAG, "VIN Scanner dependencies released")
        }
    }

    /**
     * Internal container that holds all dependencies.
     * Singletons are lazily initialized on first access.
     * Factory methods create new instances for per-screen lifecycles.
     */
    internal class DependencyContainer(
        private val appContext: Context
    ) {
        // ==================== Singletons (Lazy-Initialized) ====================

        /**
         * TensorFlow Lite Interpreter for VIN detection.
         * Expensive to create, so we cache it for the app lifetime.
         * Uses GPU delegate if available for better performance.
         */
        val interpreter: Interpreter by lazy {
            SLog.d(TAG, "Creating TensorFlow Lite Interpreter...")
            val modelPath = "best_float32.tflite"

            // Load model from assets
            val assetFileDescriptor = appContext.assets.openFd(modelPath)
            val modelBuffer = FileInputStream(assetFileDescriptor.fileDescriptor).use { inputStream ->
                val fileChannel = inputStream.channel
                val startOffset = assetFileDescriptor.startOffset
                val declaredLength = assetFileDescriptor.declaredLength
                fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            }
            SLog.d(TAG, "TFLite model loaded from assets: $modelPath")

            // Configure interpreter options
            val options = Interpreter.Options().apply {
                setNumThreads(ScannerPerfConfig.interpreterThreads)
                setUseXNNPACK(ScannerPerfConfig.useXnnpack)

                when (ScannerPerfConfig.delegateMode) {
                    "cpu", "xnnpack" -> {
                        SLog.w(
                            TAG,
                            "TFLite delegate mode=${ScannerPerfConfig.delegateMode}, xnnpack=${ScannerPerfConfig.useXnnpack}, threads=${ScannerPerfConfig.interpreterThreads}"
                        )
                    }
                    "nnapi" -> {
                        addDelegate(NnApiDelegate())
                        SLog.w(TAG, "TFLite delegate mode=nnapi, threads=${ScannerPerfConfig.interpreterThreads}")
                    }
                    "gpu" -> {
                        val compatibilityList = CompatibilityList()
                        if (compatibilityList.isDelegateSupportedOnThisDevice) {
                            val delegateOptions = compatibilityList.bestOptionsForThisDevice
                            addDelegate(GpuDelegate(delegateOptions))
                            SLog.w(TAG, "TFLite delegate mode=gpu, threads=${ScannerPerfConfig.interpreterThreads}")
                        } else {
                            SLog.w(TAG, "GPU delegate not supported, falling back to CPU/XNNPACK")
                        }
                    }
                    else -> {
                        SLog.w(TAG, "Unknown delegate mode=${ScannerPerfConfig.delegateMode}, using CPU/XNNPACK")
                    }
                }
            }

            val interpreter = Interpreter(modelBuffer, options)
            interpreter.allocateTensors()
            SLog.d(TAG, "TensorFlow Lite Interpreter created successfully")
            interpreter
        }

        /**
         * VIN Detector that uses TFLite model for real-time detection.
         * Singleton because it's stateless and expensive to create.
         */
        val vinDetector: VinDetector by lazy {
            SLog.d(TAG, "Creating VinDetector...")
            VinDetectorImpl(interpreter)
        }

        /**
         * Text Extractor using ML Kit for OCR.
         * Singleton because ML Kit recognizer is expensive to create.
         */
        val textExtractor: TextExtractor by lazy {
            SLog.d(TAG, "Creating TextExtractor...")
            TextExtractorImpl(appContext)
        }

        /**
         * VIN Validator for format checking and ISO 3779 checksum verification.
         * Singleton because it's stateless.
         */
        val vinValidator: VinValidator by lazy {
            SLog.d(TAG, "Creating VinValidator...")
            VinValidatorImpl(appContext)
        }

        /**
         * VIN Decoder for decoding manufacturer/model information.
         * Singleton because it loads data from JSON file.
         */
        val vinDecoder: VinDecoder by lazy {
            SLog.d(TAG, "Creating VinDecoder...")
            VinDecoder(appContext)
        }

        /**
         * Camera Data Source for converting camera frames to bitmaps.
         * Singleton because it's stateless.
         */
        val cameraDataSource: CameraDataSource by lazy {
            SLog.d(TAG, "Creating CameraDataSource...")
            CameraDataSourceImpl(appContext)
        }

        // ==================== Factory Methods (New Instance Per Call) ====================

        /**
         * Create a new ExecutorService for camera operations.
         * Should be created per-screen and shut down when screen is disposed.
         */
        fun createExecutor(): ExecutorService {
            return Executors.newSingleThreadExecutor()
        }

        /**
         * Create a CameraSelector for back camera.
         * Lightweight, can be created per-screen.
         */
        fun createCameraSelector(): CameraSelector {
            return CameraSelector.DEFAULT_BACK_CAMERA
        }

        /**
         * Create a Preview instance for camera preview.
         * Should be created per-screen lifecycle.
         */
        fun createPreview(): Preview {
            return Preview.Builder().build()
        }

        /**
         * Create an ImageAnalysis instance for frame processing.
         * Configured for portrait mode (540x960) with latest frame strategy.
         */
        fun createImageAnalysis(): ImageAnalysis {
            return ImageAnalysis.Builder()
                .setTargetRotation(Surface.ROTATION_0)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(
                                    ScannerPerfConfig.imageAnalysisWidth,
                                    ScannerPerfConfig.imageAnalysisHeight
                                ),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
        }

        /**
         * Create a VinScannerRepository that coordinates all data sources.
         * Created per-ViewModel to allow independent lifecycles.
         */
        fun createRepository(): VinScannerRepository {
            return VinScannerRepositoryImpl(
                cameraDataSource = cameraDataSource,
                vinDetector = vinDetector,
                textExtractor = textExtractor,
                vinValidator = vinValidator
            )
        }

        /**
         * Create DetectVinUseCase with repository.
         */
        fun createDetectVinUseCase(): DetectVinUseCase {
            return DetectVinUseCase(createRepository())
        }

        /**
         * Create ExtractTextUseCase with repository.
         */
        fun createExtractTextUseCase(): ExtractTextUseCase {
            return ExtractTextUseCase(createRepository())
        }

        /**
         * Create ValidateVinUseCase with repository.
         */
        fun createValidateVinUseCase(): ValidateVinUseCase {
            return ValidateVinUseCase(createRepository())
        }

        /**
         * Create ScannerViewModel with all required use cases.
         * Should be created via ViewModelProvider to respect Activity/Fragment lifecycle.
         */
        fun createScannerViewModel(): ScannerViewModel {
            val startMs = SystemClock.elapsedRealtime()
            return ScannerViewModel(
                vinValidator = vinValidator,
                strings = ScannerViewModelStrings.from(appContext)
            ).also {
                SLog.w(TAG, "createScannerViewModel() took ${SystemClock.elapsedRealtime() - startMs}ms")
            }
        }

        /**
         * Warm up expensive scanner dependencies in the background before first frame processing.
         * This avoids first-run jank when lazy singletons are created on demand.
         */
        suspend fun warmUpScannerDependencies() = withContext(Dispatchers.Default) {
            vinDetector
            textExtractor
            vinValidator
            cameraDataSource
        }

        /**
         * Release heavyweight singleton resources.
         */
        fun release() {
            runCatching {
                (textExtractor as? java.io.Closeable)?.close()
            }.onFailure { SLog.w(TAG, "Failed to close text extractor", it) }
        }
    }
}
