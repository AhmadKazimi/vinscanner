@file:Suppress("ktlint:standard:function-naming")

package com.kazimi.syaravin.presentation.components

import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kazimi.syaravin.util.LogTags
import com.kazimi.syaravin.util.SLog
import kotlinx.coroutines.delay

private const val TAG = LogTags.LIBRARY

// Re-run center autofocus on this cadence so the preview keeps refocusing as the phone is moved
// toward the VIN (a single focus action only locks once). Suppressed briefly after a manual tap.
private const val AUTO_CENTER_FOCUS_INTERVAL_MS = 2000L
private const val MANUAL_FOCUS_GRACE_MS = 3000L

/**
 * Composable for displaying camera preview using CameraX
 */
@Composable
internal fun CameraPreview(
    modifier: Modifier = Modifier,
    cameraSelector: CameraSelector,
    preview: Preview,
    imageAnalyzer: ImageAnalysis,
    imageCapture: ImageCapture? = null,
    onCameraBound: (Camera?) -> Unit = {},
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }

    // Bound camera + last manual-tap timestamp drive the periodic center-autofocus loop below.
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var lastManualFocusMs by remember { mutableLongStateOf(0L) }

    val previewView =
        remember {
            PreviewView(context).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                scaleType = PreviewView.ScaleType.FIT_CENTER
            }
        }

    // Bind once and let CameraX (lifecycle-aware) open the camera on START and close it on
    // STOP. On dispose we unbind every use case so the camera is fully released — leaving stale
    // bindings around causes "Conflicts with: Device 0" when the scanner is reopened.
    DisposableEffect(
        lifecycleOwner,
        appContext,
        previewView,
        cameraSelector,
        preview,
        imageAnalyzer,
        imageCapture,
    ) {
        SLog.w(TAG, "Camera preview binding")
        bindCameraUseCases(
            context = appContext,
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            cameraSelector = cameraSelector,
            preview = preview,
            imageAnalyzer = imageAnalyzer,
            imageCapture = imageCapture,
            onCameraBound = { camera ->
                boundCamera = camera
                onCameraBound(camera)
            },
            onManualFocus = { lastManualFocusMs = System.currentTimeMillis() },
        )

        onDispose {
            SLog.w(TAG, "Camera preview disposing")
            boundCamera = null
            onCameraBound(null)
            releaseCameraUseCases(
                context = appContext,
                previewView = previewView,
                preview = preview,
            )
        }
    }

    // Keep refocusing on the frame center automatically, so the user never has to tap. A single
    // FocusMeteringAction only locks once; re-issuing it tracks focus as the phone is repositioned.
    // Skipped while a manual tap-to-focus is still within its grace window.
    LaunchedEffect(boundCamera) {
        val activeCamera = boundCamera ?: return@LaunchedEffect
        val centerPoint = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
        val centerAction =
            FocusMeteringAction
                .Builder(centerPoint, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .build()
        while (true) {
            if (System.currentTimeMillis() - lastManualFocusMs >= MANUAL_FOCUS_GRACE_MS) {
                try {
                    activeCamera.cameraControl.startFocusAndMetering(centerAction)
                } catch (e: Exception) {
                    SLog.w(TAG, "Periodic center autofocus failed", e)
                }
            }
            delay(AUTO_CENTER_FOCUS_INTERVAL_MS)
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize(),
    )
}

private fun bindCameraUseCases(
    context: android.content.Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    cameraSelector: CameraSelector,
    preview: Preview,
    imageAnalyzer: ImageAnalysis,
    imageCapture: ImageCapture?,
    onCameraBound: (Camera?) -> Unit,
    onManualFocus: () -> Unit,
) {
    val appContext = context.applicationContext
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

    cameraProviderFuture.addListener({
        try {
            val cameraProvider = cameraProviderFuture.get()

            // Release any prior bindings (e.g. a previous scanner session in this process)
            // before rebinding, so the camera device is never double-claimed.
            cameraProvider.unbindAll()

            // Set the surface provider for preview
            preview.setSurfaceProvider(previewView.surfaceProvider)

            // Bind use cases to camera. Try Preview + Analysis + Capture; some low-end
            // devices only support 2 concurrent streams, so fall back to Preview + Analysis
            // (the high-res crop path then degrades gracefully to the analysis-frame crop).
            val camera =
                try {
                    if (imageCapture != null) {
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalyzer,
                            imageCapture,
                        )
                    } else {
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalyzer,
                        )
                    }
                } catch (e: Exception) {
                    if (imageCapture != null) {
                        SLog.w(TAG, "3-stream bind failed; rebinding without ImageCapture", e)
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalyzer,
                        )
                    } else {
                        throw e
                    }
                }

            // Center autofocus is driven by the periodic loop in the composable (so it keeps
            // refocusing as the phone is repositioned, not just once at bind time).

            // Setup Tap-to-Focus (overrides center AF at the tapped point for a grace window).
            previewView.setOnTouchListener { view, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    val factory = previewView.meteringPointFactory
                    val point = factory.createPoint(event.x, event.y)
                    val action =
                        FocusMeteringAction
                            .Builder(point)
                            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                            .build()

                    onManualFocus()
                    camera.cameraControl.startFocusAndMetering(action)
                    view.performClick()
                }
                true
            }

            onCameraBound(camera)
            SLog.w(TAG, "Camera use cases bound")
        } catch (e: Exception) {
            onCameraBound(null)
            SLog.e(TAG, "Error binding camera use cases", e)
        }
    }, ContextCompat.getMainExecutor(appContext))
}

private fun releaseCameraUseCases(
    context: android.content.Context,
    previewView: PreviewView,
    preview: Preview,
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
            cameraProviderFuture.get().unbindAll()
            SLog.w(TAG, "Camera use cases unbound")
        } catch (e: Exception) {
            SLog.w(TAG, "Failed to unbind camera use cases", e)
        }
    }, ContextCompat.getMainExecutor(appContext))
}
