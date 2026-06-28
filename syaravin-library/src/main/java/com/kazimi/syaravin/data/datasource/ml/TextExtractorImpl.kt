package com.kazimi.syaravin.data.datasource.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kazimi.syaravin.domain.model.BoundingBox
import com.kazimi.syaravin.util.LogTags
import com.kazimi.syaravin.util.SLog
import kotlinx.coroutines.CancellationException
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

// Detection boxes hug the VIN tightly and jitter frame-to-frame, so a tight crop clips the
// first/last glyph (16-char reads). Pad the crop before OCR so edge characters are included.
private const val OCR_CROP_PAD_X = 0.20f // fraction of box width added to each horizontal side
private const val OCR_CROP_PAD_Y = 0.25f // fraction of box height added to each vertical side

/**
 * Text extraction implementation powered by ML Kit's on-device text recogniser.
 * This replaces the previous placeholder that attempted to use the same TFLite
 * model for OCR and, as a result, always returned an empty list.
 */
internal class TextExtractorImpl(
    private val context: Context,
) : TextExtractor,
    Closeable {
    // Lazily initialise recogniser – it is thread-safe and can be reused.
    private val recogniser by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warmupRequested = AtomicBoolean(false)

    @Volatile
    private var skipOcrForSession = false
    private val mlKitPresent: Boolean by lazy {
        try {
            Class.forName("com.google.mlkit.vision.text.TextRecognition")
            Class.forName("com.google.mlkit.vision.text.latin.TextRecognizerOptions")
            true
        } catch (t: Throwable) {
            SLog.w(TAG, "ML Kit runtime classes are not available", t)
            false
        }
    }

    init {
        if (!isGooglePlayServicesReady() || !mlKitPresent) {
            skipOcrForSession = true
            requestMlKitWarmupInBackground()
        }
    }

    override suspend fun extractText(
        bitmap: Bitmap,
        boundingBox: BoundingBox,
    ): String? =
        withContext(Dispatchers.Default) {
            val recognizer = getRecognizerOrNull() ?: return@withContext null
            var cropped: Bitmap? = null
            try {
                val cropRect = toPixelRect(bitmap, boundingBox)
                if (cropRect.width() <= 0 || cropRect.height() <= 0) return@withContext null

                // Pad the crop so tight/jittery detection boxes don't clip edge glyphs.
                val paddedRect = padForOcr(cropRect, bitmap.width, bitmap.height)

                // Expand bounding box if it's too small for ML Kit (requires 32x32 minimum)
                val expandedRect = ensureMinimumSize(paddedRect, bitmap.width, bitmap.height)

                if (expandedRect.width() != cropRect.width() || expandedRect.height() != cropRect.height()) {
                    SLog.d(
                        TAG,
                        "Expanded box from ${cropRect.width()}x${cropRect.height()} to ${expandedRect.width()}x${expandedRect.height()}",
                    )
                }

                cropped =
                    Bitmap.createBitmap(
                        bitmap,
                        expandedRect.left,
                        expandedRect.top,
                        expandedRect.width(),
                        expandedRect.height(),
                    )

                // Upscale if the bitmap itself was too small to reach 32px via re-anchoring
                if (cropped.width < ML_KIT_MIN_SIZE || cropped.height < ML_KIT_MIN_SIZE) {
                    val scale =
                        maxOf(
                            ML_KIT_MIN_SIZE.toFloat() / cropped.width,
                            ML_KIT_MIN_SIZE.toFloat() / cropped.height,
                        )
                    val scaled =
                        Bitmap.createScaledBitmap(
                            cropped,
                            (cropped.width * scale).toInt().coerceAtLeast(ML_KIT_MIN_SIZE),
                            (cropped.height * scale).toInt().coerceAtLeast(ML_KIT_MIN_SIZE),
                            true,
                        )
                    cropped.recycle()
                    cropped = scaled
                }

                val image = InputImage.fromBitmap(cropped, 0)
                val result = recognizer.process(image).await()
                result.text.takeIf { it.isNotBlank() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                SLog.e(TAG, "Error extracting text from region", e)
                null
            } finally {
                cropped?.takeUnless(Bitmap::isRecycled)?.recycle()
            }
        }

    override suspend fun extractAllText(bitmap: Bitmap): List<String> =
        withContext(Dispatchers.Default) {
            val recognizer = getRecognizerOrNull() ?: return@withContext emptyList()
            try {
                val rotationDegrees = detectRotation(bitmap)
                if (rotationDegrees != 0) {
                    SLog.d(TAG, "Detected full image rotation: $rotationDegrees degrees")
                }

                val image = InputImage.fromBitmap(bitmap, rotationDegrees)
                val result = recognizer.process(image).await()
                result.textBlocks.flatMap { block ->
                    block.lines.map { it.text }.filterNot(::isNoiseLine)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                SLog.e(TAG, "Error extracting text from image", e)
                emptyList()
            }
        }

    override suspend fun extractAllTextWithBounds(bitmap: Bitmap): List<TextWithBounds> =
        withContext(Dispatchers.Default) {
            val recognizer = getRecognizerOrNull() ?: return@withContext emptyList()
            try {
                val rotationDegrees = detectRotation(bitmap)
                if (rotationDegrees != 0) {
                    SLog.d(TAG, "Detected full image rotation for bounds: $rotationDegrees degrees")
                }

                val image = InputImage.fromBitmap(bitmap, rotationDegrees)
                val result = recognizer.process(image).await()
                result.textBlocks.flatMap { block ->
                    block.lines.filterNot { isNoiseLine(it.text) }.mapNotNull { line ->
                        line.boundingBox?.let { rect ->
                            // Convert pixel coordinates to normalized coordinates
                            val normalizedBox =
                                BoundingBox(
                                    left = rect.left.toFloat() / bitmap.width,
                                    top = rect.top.toFloat() / bitmap.height,
                                    right = rect.right.toFloat() / bitmap.width,
                                    bottom = rect.bottom.toFloat() / bitmap.height,
                                    confidence = 1.0f, // ML Kit doesn't provide confidence per line
                                )
                            TextWithBounds(line.text, normalizedBox)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                SLog.e(TAG, "Error extracting text with bounds from image", e)
                emptyList()
            }
        }

    private fun getRecognizerOrNull(): TextRecognizer? {
        if (skipOcrForSession && isGooglePlayServicesReady() && mlKitPresent) {
            skipOcrForSession = false
            SLog.i(TAG, "ML Kit became available during session, re-enabling OCR")
        }

        if (skipOcrForSession) return null

        if (!isGooglePlayServicesReady() || !mlKitPresent) {
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

    private fun isGooglePlayServicesReady(): Boolean =
        try {
            GoogleApiAvailability
                .getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        } catch (t: Throwable) {
            SLog.w(TAG, "Unable to verify Google Play services availability", t)
            false
        }

    private fun requestMlKitWarmupInBackground() {
        if (!warmupRequested.compareAndSet(false, true)) return

        backgroundScope.launch {
            if (!mlKitPresent) return@launch

            try {
                val requestBuilder =
                    ModuleInstallRequest
                        .newBuilder()
                        .addApi(recogniser)

                val request = requestBuilder.build()

                val response =
                    ModuleInstall
                        .getClient(context)
                        .installModules(request)
                        .await()

                if (response.areModulesAlreadyInstalled()) {
                    SLog.i(TAG, "ML Kit OCR module already installed")
                } else {
                    SLog.i(TAG, "ML Kit OCR module install requested in background")
                }

                if (isGooglePlayServicesReady() && mlKitPresent) {
                    skipOcrForSession = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
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
            aspectRatio > 1.5f -> {
                0
            }

            // Portrait orientation - might be 90 or 270 degrees rotated
            aspectRatio < 0.67f -> {
                // Default to 270 degrees (rotate right to make horizontal)
                // This assumes camera is held vertically with VIN on side
                270
            }

            // Nearly square or slight landscape/portrait - no rotation
            else -> {
                0
            }
        }
    }

    /**
     * Ensures a bounding box meets ML Kit's minimum size requirement (32x32 pixels).
     * Expands symmetrically first, then re-anchors against the opposite edge if a boundary
     * clamp would leave the dimension still below the minimum.
     */
    private fun ensureMinimumSize(
        rect: Rect,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): Rect {
        if (rect.width() >= ML_KIT_MIN_SIZE && rect.height() >= ML_KIT_MIN_SIZE) return rect

        var left = rect.left
        var top = rect.top
        var right = rect.right
        var bottom = rect.bottom

        if (right - left < ML_KIT_MIN_SIZE) {
            val shortage = ML_KIT_MIN_SIZE - (right - left)
            left -= shortage / 2
            right += shortage - shortage / 2
            if (left < 0) {
                right -= left
                left = 0
            }
            if (right > bitmapWidth) {
                left -= (right - bitmapWidth)
                right = bitmapWidth
            }
            left = left.coerceAtLeast(0)
        }

        if (bottom - top < ML_KIT_MIN_SIZE) {
            val shortage = ML_KIT_MIN_SIZE - (bottom - top)
            top -= shortage / 2
            bottom += shortage - shortage / 2
            if (top < 0) {
                bottom -= top
                top = 0
            }
            if (bottom > bitmapHeight) {
                top -= (bottom - bitmapHeight)
                bottom = bitmapHeight
            }
            top = top.coerceAtLeast(0)
        }

        return Rect(left, top, right, bottom)
    }

    /**
     * Converts a normalised [BoundingBox] (values in 0..1) to a pixel [Rect]
     * relative to the supplied [bitmap]. Any out-of-bounds values are clamped.
     */
    /**
     * Expands [rect] by [OCR_CROP_PAD_X]/[OCR_CROP_PAD_Y] on each side, clamped to the bitmap, so
     * tight or slightly-misaligned detection boxes don't clip the first/last VIN character.
     */
    private fun padForOcr(
        rect: Rect,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): Rect {
        val padX = (rect.width() * OCR_CROP_PAD_X).toInt()
        val padY = (rect.height() * OCR_CROP_PAD_Y).toInt()
        return Rect(
            (rect.left - padX).coerceAtLeast(0),
            (rect.top - padY).coerceAtLeast(0),
            (rect.right + padX).coerceAtMost(bitmapWidth),
            (rect.bottom + padY).coerceAtMost(bitmapHeight),
        )
    }

    private fun toPixelRect(
        bitmap: Bitmap,
        box: BoundingBox,
    ): Rect {
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

// Patterns that identify non-VIN label text on compliance stickers.
// Matched case-insensitively against each OCR line before it reaches the VIN pipeline.
private val NOISE_PATTERNS = listOf(
    Regex("""(?i)type\s*:"""),           // TYPE: PASSENGER CAR, TYPE: MPV, etc.
    Regex("""(?i)passenger\s+car"""),
    Regex("""(?i)made\s+in\s+\w+"""),    // MADE IN CHINA, MADE IN INDONESIA, etc.
)

private fun isNoiseLine(text: String): Boolean = NOISE_PATTERNS.any { it.containsMatchIn(text) }
