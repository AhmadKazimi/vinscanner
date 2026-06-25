package com.kazimi.syaravin.data.datasource.ml

import android.content.Context
import com.kazimi.syaravin.util.LogTags
import com.kazimi.syaravin.util.SLog
import com.kazimi.syaravin.util.ScannerPerfConfig
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.Closeable
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TAG = LogTags.LIBRARY
private const val MODEL_PATH = "best_float32.tflite"

/** Owns LiteRT resources and pins their complete lifecycle to one thread. */
internal class LiteRtRuntime(
    private val appContext: Context,
) : Closeable {
    private val runtimeThread = AtomicReference<Thread?>()
    private val closed = AtomicBoolean(false)
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "syaravin-litert").also(runtimeThread::set)
        }
    private val dispatcher = executor.asCoroutineDispatcher()

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null

    suspend fun <T> run(block: (Interpreter) -> T): T {
        check(!closed.get()) { "LiteRT runtime is closed" }
        return withContext(dispatcher) {
            check(Thread.currentThread() === runtimeThread.get())
            block(interpreter ?: createInterpreter().also { interpreter = it })
        }
    }

    private fun createInterpreter(): Interpreter {
        SLog.d(TAG, "Creating LiteRT interpreter on ${Thread.currentThread().name}")
        val modelBuffer =
            appContext.assets.openFd(MODEL_PATH).use { assetFileDescriptor ->
                FileInputStream(assetFileDescriptor.fileDescriptor).use { inputStream ->
                    inputStream.channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        assetFileDescriptor.startOffset,
                        assetFileDescriptor.declaredLength,
                    )
                }
            }
        val options =
            Interpreter.Options().apply {
                setNumThreads(ScannerPerfConfig.interpreterThreads)
                setUseXNNPACK(ScannerPerfConfig.useXnnpack)
                configureDelegate(this)
            }

        return try {
            Interpreter(modelBuffer, options).also {
                it.allocateTensors()
                SLog.d(TAG, "LiteRT interpreter created successfully")
            }
        } catch (error: Throwable) {
            closeDelegates()
            throw error
        }
    }

    private fun configureDelegate(options: Interpreter.Options) {
        when (ScannerPerfConfig.delegateMode) {
            "cpu", "xnnpack" -> {
                SLog.w(
                    TAG,
                    "LiteRT delegate mode=${ScannerPerfConfig.delegateMode}, xnnpack=${ScannerPerfConfig.useXnnpack}, threads=${ScannerPerfConfig.interpreterThreads}",
                )
            }

            "nnapi" -> {
                nnApiDelegate = NnApiDelegate().also(options::addDelegate)
                SLog.w(TAG, "LiteRT delegate mode=nnapi")
            }

            "gpu" -> {
                val compatibilityList = CompatibilityList()
                if (compatibilityList.isDelegateSupportedOnThisDevice) {
                    gpuDelegate =
                        GpuDelegate(compatibilityList.bestOptionsForThisDevice)
                            .also(options::addDelegate)
                    SLog.w(TAG, "LiteRT delegate mode=gpu")
                } else {
                    SLog.w(TAG, "GPU delegate unsupported; falling back to CPU/XNNPACK")
                }
            }

            else -> {
                SLog.w(
                    TAG,
                    "Unknown LiteRT delegate mode=${ScannerPerfConfig.delegateMode}; using CPU/XNNPACK",
                )
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        if (Thread.currentThread() === runtimeThread.get()) {
            closeResources()
        } else {
            executor.submit(::closeResources).get()
        }
        dispatcher.close()
    }

    private fun closeResources() {
        try {
            interpreter?.close()
        } finally {
            interpreter = null
            closeDelegates()
        }
    }

    private fun closeDelegates() {
        gpuDelegate?.close()
        gpuDelegate = null
        nnApiDelegate?.close()
        nnApiDelegate = null
    }
}
