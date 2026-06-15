package com.syarah.vinscanner.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object ImagePreprocessor {
    fun enhanceVinImage(bitmap: Bitmap): Bitmap =
        try {
            val sharpened = sharpenBitmap(bitmap)
            applyContrast(sharpened).also {
                if (it !== sharpened) sharpened.recycle()
            }
        } catch (_: Exception) {
            bitmap
        }

    /**
     * Cross kernel (center=5, 4 cardinal neighbors=-1, corners=0).
     * Subtle edge boost; less aggressive than the full 8-neighbor kernel.
     */
    private fun sharpenBitmap(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // Cross kernel: center=5, 4 cardinal neighbors=-1, corners ignored (sum=1, subtle edge boost)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = pixels[y * w + x]
                val t = pixels[(y - 1).coerceAtLeast(0) * w + x]
                val b2 = pixels[(y + 1).coerceAtMost(h - 1) * w + x]
                val l = pixels[y * w + (x - 1).coerceAtLeast(0)]
                val r2 = pixels[y * w + (x + 1).coerceAtMost(w - 1)]
                out[y * w + x] =
                    Color.argb(
                        c ushr 24,
                        (5 * Color.red(c) - Color.red(t) - Color.red(b2) - Color.red(l) - Color.red(r2)).coerceIn(
                            0,
                            255,
                        ),
                        (
                            5 * Color.green(c) - Color.green(t) - Color.green(b2) - Color.green(l) -
                                Color.green(
                                    r2,
                                )
                        ).coerceIn(0, 255),
                        (
                            5 * Color.blue(c) - Color.blue(t) - Color.blue(b2) - Color.blue(l) -
                                Color.blue(
                                    r2,
                                )
                        ).coerceIn(0, 255),
                    )
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun applyContrast(bitmap: Bitmap): Bitmap {
        val enhanced = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val matrix =
            ColorMatrix().apply {
                setSaturation(0.2f)
                postConcat(
                    ColorMatrix().apply {
                        set(
                            floatArrayOf(
                                1.2f,
                                0f,
                                0f,
                                0f,
                                5f,
                                0f,
                                1.2f,
                                0f,
                                0f,
                                5f,
                                0f,
                                0f,
                                1.2f,
                                0f,
                                5f,
                                0f,
                                0f,
                                0f,
                                1f,
                                0f,
                            ),
                        )
                    },
                )
            }
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
        Canvas(enhanced).drawBitmap(enhanced, 0f, 0f, paint)
        return enhanced
    }

    fun cropAndEnhance(
        bitmap: Bitmap,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        paddingPercent: Float = 0.15f,
    ): Bitmap? {
        return try {
            val boxWidth = (right - left) * bitmap.width
            val boxHeight = (bottom - top) * bitmap.height
            val padX = boxWidth * paddingPercent
            val padY = boxHeight * paddingPercent

            val leftPx = ((left * bitmap.width) - padX).toInt().coerceIn(0, bitmap.width - 1)
            val topPx = ((top * bitmap.height) - padY).toInt().coerceIn(0, bitmap.height - 1)
            val rightPx = ((right * bitmap.width) + padX).toInt().coerceIn(leftPx + 1, bitmap.width)
            val bottomPx =
                ((bottom * bitmap.height) + padY).toInt().coerceIn(topPx + 1, bitmap.height)

            val width = rightPx - leftPx
            val height = bottomPx - topPx
            if (width <= 0 || height <= 0) return null

            val cropped = Bitmap.createBitmap(bitmap, leftPx, topPx, width, height)
            enhanceVinImage(cropped).also {
                if (it !== cropped) cropped.recycle()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun downscaleForDisplay(
        bitmap: Bitmap,
        maxDimension: Int = 1600,
        maxPixels: Int = 1_500_000,
    ): Bitmap {
        if (bitmap.width <= 0 || bitmap.height <= 0) return bitmap
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val currentPixels = width.toLong() * height.toLong()

            val dimensionScale =
                if (width > maxDimension || height > maxDimension) {
                    maxDimension.toFloat() / maxOf(width, height).toFloat()
                } else {
                    1f
                }

            val pixelScale =
                if (currentPixels > maxPixels) {
                    sqrt(maxPixels.toDouble() / currentPixels.toDouble()).toFloat()
                } else {
                    1f
                }

            val scale = min(dimensionScale, pixelScale)
            if (scale >= 1f) return bitmap

            Bitmap.createScaledBitmap(
                bitmap,
                (width * scale).roundToInt().coerceAtLeast(1),
                (height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        } catch (_: Throwable) {
            bitmap
        }
    }
}
