package com.kazimi.syaravin.presentation.components

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
import com.kazimi.syaravin.util.DisposableEffectWithLifecycle
import timber.log.Timber
import java.util.concurrent.ExecutorService

/**
 * Composable for displaying camera preview using CameraX
 */
@Composable
fun CameraPreview(
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
            Timber.d("Starting camera preview")
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
            Timber.d("Stopping camera preview")
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
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            
            Timber.d("Camera use cases bound successfully")
        } catch (e: Exception) {
            Timber.e(e, "Error binding camera use cases")
        }
    }, ContextCompat.getMainExecutor(context))
}