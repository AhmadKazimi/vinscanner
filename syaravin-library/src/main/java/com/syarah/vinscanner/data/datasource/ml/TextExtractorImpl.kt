package com.syarah.vinscanner.data.datasource.ml

import com.syarah.vinscanner.util.LogTags

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.syarah.vinscanner.util.SLog
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.syarah.vinscanner.domain.model.BoundingBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = LogTags.LIBRARY
private const val ML_KIT_MIN_SIZE = 32 // ML Kit requires minimum 32x32 pixels

/**
 * Text extraction implementation powered by ML Kit's on-device text recogniser.
 * This replaces the previous placeholder that attempted to use the same TFLite
 * model for OCR and, as a result, always returned an empty list.
 */
internal class TextExtractorImpl(
    private val context: Context
) : TextExtractor, Closeable {

    // Lazily initialise recogniser – it is thread-safe and can be reused.
    private val recogniser by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warmupRequested = AtomicBoolean(false)
    @Volatile
    private var skipOcrForSession = false

    init {
        if (!isGooglePlayServicesReady() || !isMlKitRuntimePresent()) {
            skipOcrForSession = true
            requestMlKitWarmupInBackground()
        }
    }

    override suspend fun extractText(bitmap: Bitmap, boundingBox: BoundingBox): String? =
        withContext(Dispatchers.Default) {
            val recognizer = getRecognizerOrNull() ?: return@withContext null
            try {
                val cropRect = toPixelRect(bitmap, boundingBox)
                if (cropRect.width() <= 0 || cropRect.height() <= 0) return@withContext null

                // Expand bounding box if it's too small for ML Kit (requires 32x32 minimum)
                val expandedRect = ensureMinimumSize(cropRect, bitmap.width, bitmap.height)

                if (expandedRect.width() != cropRect.width() || expandedRect.height() != cropRect.height()) {
                    SLog.d(TAG, "Expanded box from ${cropRect.width()}x${cropRect.height()} to ${expandedRect.width()}x${expandedRect.height()}")
                }

                val cropped = Bitmap.createBitmap(
                    bitmap,
                    expandedRect.left,
                    expandedRect.top,
                    expandedRect.width(),
                    expandedRect.height()
                )

                // Detect rotation angle for better OCR on angled text
                val rotationDegrees = detectRotation(cropped)
                if (rotationDegrees != 0) {
                    SLog.d(TAG, "Detected text rotation: $rotationDegrees degrees")
                }

                val image = InputImage.fromBitmap(cropped, rotationDegrees)
                val result = recognizer.process(image).await()
                result.text.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                SLog.e(TAG, "Error extracting text from region", e)
                null
            }
        }

    override suspend fun extractAllText(bitmap: Bitmap): List<String> = withContext(Dispatchers.Default) {
        val recognizer = getRecognizerOrNull() ?: return@withContext emptyList()
        try {
            val rotationDegrees = detectRotation(bitmap)
            if (rotationDegrees != 0) {
                SLog.d(TAG, "Detected full image rotation: $rotationDegrees degrees")
            }

            val image = InputImage.fromBitmap(bitmap, rotationDegrees)
            val result = recognizer.process(image).await()
            result.textBlocks.flatMap { block ->
                block.lines.map { it.text }
            }
        } catch (e: Exception) {
            SLog.e(TAG, "Error extracting text from image", e)
            emptyList()
        }
    }

    override suspend fun extractAllTextWithBounds(bitmap: Bitmap): List<TextWithBounds> = withContext(Dispatchers.Default) {
        val recognizer = getRecognizerOrNull() ?: return@withContext emptyList()
        try {
            val rotationDegrees = detectRotation(bitmap)
            if (rotationDegrees != 0) {
                SLog.d(TAG, "Detected full image rotation for bounds: $rotationDegrees degrees")
            }

            val image = InputImage.fromBitmap(bitmap, rotationDegrees)
            val result = recognizer.process(image).await()
            result.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    line.boundingBox?.let { rect ->
                        // Convert pixel coordinates to normalized coordinates
                        val normalizedBox = BoundingBox(
                            left = rect.left.toFloat() / bitmap.width,
                            top = rect.top.toFloat() / bitmap.height,
                            right = rect.right.toFloat() / bitmap.width,
                            bottom = rect.bottom.toFloat() / bitmap.height,
                            confidence = 1.0f // ML Kit doesn't provide confidence per line
                        )
                        TextWithBounds(line.text, normalizedBox)
                    }
                }
            }
        } catch (e: Exception) {
            SLog.e(TAG, "Error extracting text with bounds from image", e)
            emptyList()
        }
    }

    private fun getRecognizerOrNull(): TextRecognizer? {
        if (skipOcrForSession && isGooglePlayServicesReady() && isMlKitRuntimePresent()) {
            skipOcrForSession = false
            SLog.i(TAG, "ML Kit became available during session, re-enabling OCR")
        }

        if (skipOcrForSession) return null

        if (!isGooglePlayServicesReady() || !isMlKitRuntimePresent()) {
            skipOcrForSession = true
            requestMlKitWarmupInBackground()
            return null
        }

        return try {
            recogniser
        } catch (t: Throwable) {
            SLog.e(TAG, "ML Kit recognizer unavailable for this session", t)
            skipOcrForSession = true
            requestMlKitWarmupInBackground()
            null
        }
    }

    private fun isGooglePlayServicesReady(): Boolean {
        return try {
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        } catch (t: Throwable) {
            SLog.w(TAG, "Unable to verify Google Play services availability", t)
            false
        }
    }

    private fun isMlKitRuntimePresent(): Boolean {
        return try {
            Class.forName("com.google.mlkit.vision.text.TextRecognition")
            Class.forName("com.google.mlkit.vision.text.latin.TextRecognizerOptions")
            true
        } catch (t: Throwable) {
            SLog.w(TAG, "ML Kit runtime classes are not available", t)
            false
        }
    }

    private fun requestMlKitWarmupInBackground() {
        if (!warmupRequested.compareAndSet(false, true)) return

        backgroundScope.launch {
            if (!isMlKitRuntimePresent()) return@launch

            try {
                val requestBuilder = ModuleInstallRequest.newBuilder()
                    .addApi(recogniser)

                val request = requestBuilder.build()

                val response = ModuleInstall.getClient(context)
                    .installModules(request)
                    .await()

                if (response.areModulesAlreadyInstalled()) {
                    SLog.i(TAG, "ML Kit OCR module already installed")
                } else {
                    SLog.i(TAG, "ML Kit OCR module install requested in background")
                }

                if (isGooglePlayServicesReady() && isMlKitRuntimePresent()) {
                    skipOcrForSession = false
                }
            } catch (t: Throwable) {
                SLog.w(TAG, "ML Kit OCR module install request failed", t)
                warmupRequested.set(false)
            }
        }
    }

    /**
     * Detects the rotation angle of text in the bitmap.
     * Returns rotation in degrees: 0, 90, 180, or 270.
     * Uses simple heuristics based on bitmap aspect ratio and orientation.
     */
    private fun detectRotation(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        val aspectRatio = width.toFloat() / height.toFloat()

        // VINs are typically horizontal text (wide aspect ratio)
        // If the bitmap is portrait (tall), it might be rotated
        return when {
            // Wide landscape (normal VIN orientation)
            aspectRatio > 1.5f -> 0

            // Portrait orientation - might be 90 or 270 degrees rotated
            aspectRatio < 0.67f -> {
                // Default to 270 degrees (rotate right to make horizontal)
                // This assumes camera is held vertically with VIN on side
                270
            }

            // Nearly square or slight landscape/portrait - no rotation
            else -> 0
        }
    }

    /**
     * Ensures a bounding box meets ML Kit's minimum size requirement (32x32 pixels).
     * Expands the box equally in all directions while staying within bitmap bounds.
     */
    private fun ensureMinimumSize(rect: Rect, bitmapWidth: Int, bitmapHeight: Int): Rect {
        var width = rect.width()
        var height = rect.height()

        // Check if expansion is needed
        if (width >= ML_KIT_MIN_SIZE && height >= ML_KIT_MIN_SIZE) {
            return rect // Already meets minimum size
        }

        // Calculate how much to expand
        val widthExpansion = maxOf(0, ML_KIT_MIN_SIZE - width)
        val heightExpansion = maxOf(0, ML_KIT_MIN_SIZE - height)

        // Expand equally in both directions (left/right for width, top/bottom for height)
        val expandLeft = widthExpansion / 2
        val expandRight = widthExpansion - expandLeft // Handle odd numbers
        val expandTop = heightExpansion / 2
        val expandBottom = heightExpansion - expandTop // Handle odd numbers

        // Apply expansion and clamp to bitmap bounds
        val newLeft = (rect.left - expandLeft).coerceIn(0, bitmapWidth)
        val newTop = (rect.top - expandTop).coerceIn(0, bitmapHeight)
        val newRight = (rect.right + expandRight).coerceIn(0, bitmapWidth)
        val newBottom = (rect.bottom + expandBottom).coerceIn(0, bitmapHeight)

        return Rect(newLeft, newTop, newRight, newBottom)
    }

    /**
     * Converts a normalised [BoundingBox] (values in 0..1) to a pixel [Rect]
     * relative to the supplied [bitmap]. Any out-of-bounds values are clamped.
     */
    private fun toPixelRect(bitmap: Bitmap, box: BoundingBox): Rect {
        val left = (box.left * bitmap.width).toInt().coerceIn(0, bitmap.width)
        val top = (box.top * bitmap.height).toInt().coerceIn(0, bitmap.height)
        val right = (box.right * bitmap.width).toInt().coerceIn(left, bitmap.width)
        val bottom = (box.bottom * bitmap.height).toInt().coerceIn(top, bitmap.height)
        return Rect(left, top, right, bottom)
    }

    override fun close() {
        backgroundScope.cancel()
        runCatching { recogniser.close() }
            .onFailure { SLog.w(TAG, "Failed to close ML Kit recognizer", it) }
    }
}
