package com.kazimi.syaravin.util

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
    /**
     * Contrast-boost the VIN image, optionally sharpening first.
     *
     * @param sharpen run the unsharp kernel before contrast. Set true for soft sources (the
     *   analysis-stream auto-scan crop); false for genuinely sharp sources (the high-res still
     *   capture), where sharpening only adds halos and per-pixel cost. Quality is decided by the
     *   source, not the pixel width — both paths can land at similar dimensions.
     */
    fun enhanceVinImage(
        bitmap: Bitmap,
        sharpen: Boolean,
    ): Bitmap =
        try {
            if (!sharpen) {
                applyContrast(bitmap)
            } else {
                val sharpened = sharpenBitmap(bitmap)
                applyContrast(sharpened).also {
                    if (it !== sharpened) sharpened.recycle()
                }
            }
        } catch (_: Exception) {
            bitmap
        }

    // Unsharp strength. Cross kernel center = 1 + 4*amount, cardinal neighbors = -amount.
    // 1.0 == old aggressive kernel (center 5 / neighbor -1); keep this low for a subtle boost.
    private const val SHARPEN_AMOUNT = 0.25f

    /**
     * Cross kernel (center = 1 + 4*amount, 4 cardinal neighbors = -amount, corners=0; sum=1).
     * Subtle edge boost scaled by [SHARPEN_AMOUNT].
     */
    private fun sharpenBitmap(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val a = SHARPEN_AMOUNT

        fun mix(
            c: Int,
            t: Int,
            b: Int,
            l: Int,
            r: Int,
        ): Int = (c + a * (4 * c - t - b - l - r)).roundToInt().coerceIn(0, 255)

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
                        mix(Color.red(c), Color.red(t), Color.red(b2), Color.red(l), Color.red(r2)),
                        mix(
                            Color.green(c),
                            Color.green(t),
                            Color.green(b2),
                            Color.green(l),
                            Color.green(r2),
                        ),
                        mix(
                            Color.blue(c),
                            Color.blue(t),
                            Color.blue(b2),
                            Color.blue(l),
                            Color.blue(r2),
                        ),
                    )
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * Upscale + unsharp a crop before OCR so thin/touching glyphs separate (e.g. "LJ" merging into
     * a single "U"). Returns a NEW bitmap distinct from [bitmap]; the caller owns and must recycle
     * it. The input is left untouched.
     */
    fun enhanceForOcr(
        bitmap: Bitmap,
        scale: Float = 2f,
    ): Bitmap {
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val upscaled =
            try {
                Bitmap.createScaledBitmap(bitmap, w, h, true)
            } catch (_: Throwable) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
        return try {
            sharpenBitmap(upscaled).also { if (it !== upscaled) upscaled.recycle() }
        } catch (_: Throwable) {
            upscaled
        }
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
                                1.4f,
                                0f,
                                0f,
                                0f,
                                -25f,
                                0f,
                                1.4f,
                                0f,
                                0f,
                                -25f,
                                0f,
                                0f,
                                1.4f,
                                0f,
                                -25f,
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

    // Upscale the result so the small analysis-stream crop (~994px wide) doesn't render stretched
    // and jagged in the host UI. Pure presentation polish — no detail is recovered.
    private const val DEFAULT_MIN_DISPLAY_WIDTH = 1400

    /**
     * Downscale for display, then sharpen + boost contrast. Used for the result image so the
     * full scanned frame reads clearly. The input is not recycled (caller owns it).
     */
    fun enhanceForDisplay(
        bitmap: Bitmap,
        sharpen: Boolean,
        maxDimension: Int = 2200,
        maxPixels: Int = 2_500_000,
        minDisplayWidth: Int = DEFAULT_MIN_DISPLAY_WIDTH,
    ): Bitmap {
        val scaled = downscaleForDisplay(bitmap, maxDimension, maxPixels)
        val enhanced = enhanceVinImage(scaled, sharpen)
        if (enhanced !== scaled && scaled !== bitmap) scaled.recycle()
        // Upscale last, so sharpen/contrast operate on real pixels first.
        val upscaled = upscaleForDisplay(enhanced, minDisplayWidth)
        if (upscaled !== enhanced && enhanced !== bitmap) enhanced.recycle()
        return upscaled
    }

    private fun upscaleForDisplay(
        bitmap: Bitmap,
        minWidth: Int,
    ): Bitmap {
        if (minWidth <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return bitmap
        if (bitmap.width >= minWidth) return bitmap
        return try {
            val scale = minWidth.toFloat() / bitmap.width.toFloat()
            Bitmap.createScaledBitmap(
                bitmap,
                minWidth,
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        } catch (_: Throwable) {
            bitmap
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
