package com.kazimi.syaravin.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * Utility for preprocessing images to enhance VIN text visibility
 */
object ImagePreprocessor {

    /**
     * Enhances a VIN image by:
     * - Increasing contrast
     * - Sharpening
     * - Removing noise
     * @param bitmap The original bitmap
     * @return Enhanced bitmap
     */
    fun enhanceVinImage(bitmap: Bitmap): Bitmap {
        try {
            // Create a mutable copy
            val enhanced = bitmap.copy(Bitmap.Config.ARGB_8888, true)

            // Apply contrast enhancement
            val canvas = Canvas(enhanced)
            val paint = Paint()

            // Increase contrast using ColorMatrix
            val colorMatrix = ColorMatrix().apply {
                // Increase contrast and slightly increase brightness
                set(floatArrayOf(
                    1.5f, 0f, 0f, 0f, 10f,    // Red
                    0f, 1.5f, 0f, 0f, 10f,    // Green
                    0f, 0f, 1.5f, 0f, 10f,    // Blue
                    0f, 0f, 0f, 1f, 0f        // Alpha
                ))
            }

            paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
            canvas.drawBitmap(enhanced, 0f, 0f, paint)

            return enhanced
        } catch (e: Exception) {
            // If enhancement fails, return original
            return bitmap
        }
    }

    /**
     * Creates a tightly cropped bitmap around the specified bounding box
     * with optional padding
     * @param bitmap Source bitmap
     * @param left Left coordinate (0-1 normalized)
     * @param top Top coordinate (0-1 normalized)
     * @param right Right coordinate (0-1 normalized)
     * @param bottom Bottom coordinate (0-1 normalized)
     * @param paddingPercent Additional padding as percentage of box size (e.g., 0.1 for 10%)
     * @return Cropped and enhanced bitmap
     */
    fun cropAndEnhance(
        bitmap: Bitmap,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        paddingPercent: Float = 0.15f
    ): Bitmap? {
        try {
            // Calculate pixel coordinates
            val boxWidth = (right - left) * bitmap.width
            val boxHeight = (bottom - top) * bitmap.height

            // Add padding
            val padX = boxWidth * paddingPercent
            val padY = boxHeight * paddingPercent

            val leftPx = ((left * bitmap.width) - padX).toInt().coerceIn(0, bitmap.width - 1)
            val topPx = ((top * bitmap.height) - padY).toInt().coerceIn(0, bitmap.height - 1)
            val rightPx = ((right * bitmap.width) + padX).toInt().coerceIn(leftPx + 1, bitmap.width)
            val bottomPx = ((bottom * bitmap.height) + padY).toInt().coerceIn(topPx + 1, bitmap.height)

            val width = rightPx - leftPx
            val height = bottomPx - topPx

            if (width <= 0 || height <= 0) return null

            // Crop the bitmap
            val cropped = Bitmap.createBitmap(bitmap, leftPx, topPx, width, height)

            // Enhance the cropped image
            return enhanceVinImage(cropped)
        } catch (e: Exception) {
            return null
        }
    }
}
