package com.syarah.vinscanner.presentation.components

import com.syarah.vinscanner.util.LogTags

import com.syarah.vinscanner.util.SLog
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.syarah.vinscanner.util.DisposableEffectWithLifecycle
import java.util.concurrent.atomic.AtomicLong

private const val TAG = LogTags.LIBRARY

/**
 * Composable for displaying camera preview using CameraX
 */
@Composable
internal fun CameraPreview(
    modifier: Modifier = Modifier,
    cameraSelector: CameraSelector,
    preview: Preview,
    imageAnalyzer: ImageAnalysis,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val activeGeneration = remember { AtomicLong(0L) }
    val currentLifecycleOwner = rememberUpdatedState(lifecycleOwner)

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Set implementation mode for better performance
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }

    DisposableEffectWithLifecycle(
        onStart = {
            SLog.w(TAG, "Camera preview starting")
            bindCameraUseCases(
                context = appContext,
                lifecycleOwner = currentLifecycleOwner.value,
                previewView = previewView,
                cameraSelector = cameraSelector,
                preview = preview,
                imageAnalyzer = imageAnalyzer,
                generationRef = activeGeneration,
                expectedGeneration = activeGeneration.incrementAndGet()
            )
        },
        onStop = {
            SLog.w(TAG, "Camera preview stopping")
            releaseCameraUseCases(
                context = appContext,
                previewView = previewView,
                preview = preview,
                imageAnalyzer = imageAnalyzer,
                generationRef = activeGeneration,
                expectedGeneration = activeGeneration.incrementAndGet()
            )
        }
    )

    DisposableEffect(appContext, previewView, preview, imageAnalyzer) {
        onDispose {
            SLog.w(TAG, "Camera preview disposing")
            releaseCameraUseCases(
                context = appContext,
                previewView = previewView,
                preview = preview,
                imageAnalyzer = imageAnalyzer,
                generationRef = activeGeneration,
                expectedGeneration = activeGeneration.incrementAndGet()
            )
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize()
    )
}

private fun bindCameraUseCases(
    context: android.content.Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    cameraSelector: CameraSelector,
    preview: Preview,
    imageAnalyzer: ImageAnalysis,
    generationRef: AtomicLong,
    expectedGeneration: Long
) {
    val appContext = context.applicationContext
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

    cameraProviderFuture.addListener({
        try {
            // Ignore stale async callbacks that arrive after a dispose/release cycle.
            if (generationRef.get() != expectedGeneration) return@addListener

            val cameraProvider = cameraProviderFuture.get()

            // Unbind all use cases before rebinding
            cameraProvider.unbind(preview, imageAnalyzer)

            // Set the surface provider for preview
            preview.setSurfaceProvider(previewView.surfaceProvider)

            // Bind use cases to camera
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            // Setup Tap-to-Focus
            previewView.setOnTouchListener { view, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    val factory = previewView.meteringPointFactory
                    val point = factory.createPoint(event.x, event.y)
                    val action = androidx.camera.core.FocusMeteringAction.Builder(point)
                        .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    
                    camera.cameraControl.startFocusAndMetering(action)
                    view.performClick()
                }
                true
            }

            SLog.w(TAG, "Camera use cases bound")
        } catch (e: Exception) {
            SLog.e(TAG, "Error binding camera use cases", e)
        }
    }, ContextCompat.getMainExecutor(appContext))
}

private fun releaseCameraUseCases(
    context: android.content.Context,
    previewView: PreviewView,
    preview: Preview,
    imageAnalyzer: ImageAnalysis,
    generationRef: AtomicLong,
    expectedGeneration: Long
) {
    val appContext = context.applicationContext

    try {
        preview.setSurfaceProvider(null)
        previewView.setOnTouchListener(null)
    } catch (e: Exception) {
        SLog.w(TAG, "Failed to clear preview surface/touch listener", e)
    }

    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener({
        try {
            if (generationRef.get() != expectedGeneration) return@addListener

            cameraProviderFuture.get().unbind(preview, imageAnalyzer)
            SLog.w(TAG, "Camera use cases unbound")
        } catch (e: Exception) {
            SLog.w(TAG, "Failed to unbind camera use cases", e)
        }
    }, ContextCompat.getMainExecutor(appContext))
}
