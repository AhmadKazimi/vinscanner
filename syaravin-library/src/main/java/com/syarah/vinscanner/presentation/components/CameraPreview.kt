package com.syarah.vinscanner.presentation.components

import android.util.Log
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.syarah.vinscanner.util.DisposableEffectWithLifecycle
import java.util.concurrent.ExecutorService

private const val TAG = "CameraPreview"

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
            Log.d(TAG, "Starting camera preview")
            bindCameraUseCases(
                context = context,
                lifecycleOwner = lifecycleOwner,
                previewView = previewView,
                cameraSelector = cameraSelector,
                preview = preview,
                imageAnalyzer = imageAnalyzer
            )
        },
        onStop = {
            Log.d(TAG, "Stopping camera preview")
        }
    )

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
    imageAnalyzer: ImageAnalysis
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

    cameraProviderFuture.addListener({
        try {
            val cameraProvider = cameraProviderFuture.get()

            // Unbind all use cases before rebinding
            cameraProvider.unbindAll()

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

            Log.d(TAG, "Camera use cases bound successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error binding camera use cases", e)
        }
    }, ContextCompat.getMainExecutor(context))
}
