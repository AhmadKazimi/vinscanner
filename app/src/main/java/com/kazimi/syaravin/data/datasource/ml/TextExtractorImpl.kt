package com.kazimi.syaravin.data.datasource.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kazimi.syaravin.domain.model.BoundingBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Text extraction implementation powered by ML Kit's on-device text recogniser.
 * This replaces the previous placeholder that attempted to use the same TFLite
 * model for OCR and, as a result, always returned an empty list.
 */
class TextExtractorImpl(
    private val context: Context
) : TextExtractor {

    // Lazily initialise recogniser – it is thread-safe and can be reused.
    private val recogniser by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun extractText(bitmap: Bitmap, boundingBox: BoundingBox): String? =
        withContext(Dispatchers.Default) {
            try {
                val cropRect = toPixelRect(bitmap, boundingBox)
                if (cropRect.width() <= 0 || cropRect.height() <= 0) return@withContext null

                // Crop original region
                val cropped = Bitmap.createBitmap(
                    bitmap,
                    cropRect.left,
                    cropRect.top,
                    cropRect.width(),
                    cropRect.height()
                )

                // First pass (up-scaled + gray)
                val preprocessed = preprocess(cropped)
                val image1 = InputImage.fromBitmap(preprocessed, 0)
                val text1 = runCatching { recogniser.process(image1).await().text }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }

                // Second pass (thresholded)
                val thresholded = binarize(preprocessed)
                val image2 = InputImage.fromBitmap(thresholded, 0)
                val text2 = runCatching { recogniser.process(image2).await().text }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }

                chooseBetter(text1, text2)
            } catch (e: Exception) {
                Timber.e(e, "Error extracting text from region")
                null
            }
        }

    override suspend fun extractAllText(bitmap: Bitmap): List<String> = withContext(Dispatchers.Default) {
        try {
            val preprocessed = preprocess(bitmap)
            val image = InputImage.fromBitmap(preprocessed, 0)
            val result = recogniser.process(image).await()
            result.textBlocks.flatMap { block ->
                block.lines.map { it.text }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error extracting text from image")
            emptyList()
        }
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

    /**
     * Upscales (if needed) and converts the bitmap to grayscale to improve OCR quality.
     */
    private fun preprocess(src: Bitmap): Bitmap {
        // Upscale so that the shorter side is at least 640 px
        val minDim = minOf(src.width, src.height)
        val scale = if (minDim < 640) 640f / minDim else 1f
        val scaled = if (scale > 1f) {
            Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt(),
                (src.height * scale).toInt(),
                true
            )
        } else src

        // Convert to grayscale
        val gray = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(gray)
        val paint = Paint()
        val cm = ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(scaled, 0f, 0f, paint)

        return gray
    }

    /**
     * Performs a simple global threshold (Otsu) on a grayscale bitmap.
     */
    private fun binarize(gray: Bitmap): Bitmap {
        val width = gray.width
        val height = gray.height

        // Copy so that we have a mutable bitmap
        val bin = gray.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(width * height)
        bin.getPixels(pixels, 0, width, 0, 0, width, height)

        val histogram = IntArray(256)
        for (p in pixels) {
            val l = p ushr 16 and 0xFF // R channel == luminance for grayscale
            histogram[l]++
        }

        // Otsu threshold calculation
        val total = pixels.size
        var sum = 0L
        for (i in 0..255) sum += i.toLong() * histogram[i]

        var sumB = 0L
        var wB = 0
        var varMax = 0.0
        var threshold = 0

        for (i in 0..255) {
            wB += histogram[i]
            if (wB == 0) continue

            val wF = total - wB
            if (wF == 0) break

            sumB += i.toLong() * histogram[i]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val between = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
            if (between > varMax) {
                varMax = between
                threshold = i
            }
        }

        for (i in pixels.indices) {
            val l = pixels[i] ushr 16 and 0xFF
            pixels[i] = if (l > threshold) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }

        bin.setPixels(pixels, 0, width, 0, 0, width, height)
        return bin
    }

    private fun chooseBetter(text1: String?, text2: String?): String? {
        // Prefer non-null VIN-looking string first
        val regex = com.kazimi.syaravin.domain.model.VinNumber.VALID_PATTERN

        val match1 = text1?.let { regex.matches(it.trim()) } ?: false
        val match2 = text2?.let { regex.matches(it.trim()) } ?: false

        return when {
            match1 && !match2 -> text1
            match2 && !match1 -> text2
            match1 && match2 -> if (text1!!.length >= text2!!.length) text1 else text2
            // Fallback: longer non-blank string
            !text1.isNullOrBlank() || !text2.isNullOrBlank() ->
                if ((text1?.length ?: 0) >= (text2?.length ?: 0)) text1 else text2
            else -> null
        }
    }
}