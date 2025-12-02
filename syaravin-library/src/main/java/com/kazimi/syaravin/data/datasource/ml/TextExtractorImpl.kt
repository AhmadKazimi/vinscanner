package com.kazimi.syaravin.data.datasource.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kazimi.syaravin.domain.model.BoundingBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private const val TAG = "TextExtractorImpl"

/**
 * Text extraction implementation powered by ML Kit's on-device text recogniser.
 * This replaces the previous placeholder that attempted to use the same TFLite
 * model for OCR and, as a result, always returned an empty list.
 */
internal class TextExtractorImpl(
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

                val cropped = Bitmap.createBitmap(
                    bitmap,
                    cropRect.left,
                    cropRect.top,
                    cropRect.width(),
                    cropRect.height()
                )

                val image = InputImage.fromBitmap(cropped, 0)
                val result = recogniser.process(image).await()
                result.text.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting text from region", e)
                null
            }
        }

    override suspend fun extractAllText(bitmap: Bitmap): List<String> = withContext(Dispatchers.Default) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recogniser.process(image).await()
            result.textBlocks.flatMap { block ->
                block.lines.map { it.text }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text from image", e)
            emptyList()
        }
    }

    override suspend fun extractAllTextWithBounds(bitmap: Bitmap): List<TextWithBounds> = withContext(Dispatchers.Default) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recogniser.process(image).await()
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
            Log.e(TAG, "Error extracting text with bounds from image", e)
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
}
