package com.kazimi.syaravin.util

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max

/**
 * Focus / motion-blur estimator.
 *
 * Uses the variance of the Laplacian: a sharp image has strong high-frequency edge energy, so its
 * Laplacian response spreads out (high variance); a blurred image has weak edges (low variance).
 * Higher return value = sharper. The absolute scale depends on content and the downsample size, so
 * the accept threshold must be tuned per-device against logged values — see [ScannerPerfConfig].
 */
internal object Sharpness {
    /**
     * @param maxEdge downsample the long edge to at most this many pixels before measuring, to keep
     *   the per-frame cost low. The analysis ROI band (~994×230) drops to ~320×74.
     * @return variance of the Laplacian over interior pixels, or 0.0 for unusable input.
     */
    fun varianceOfLaplacian(
        bitmap: Bitmap,
        maxEdge: Int = 320,
    ): Double {
        if (bitmap.isRecycled || bitmap.width < 3 || bitmap.height < 3) return 0.0

        val longEdge = max(bitmap.width, bitmap.height)
        val scale = if (longEdge > maxEdge) maxEdge.toFloat() / longEdge.toFloat() else 1f
        val sampleWidth = max(3, (bitmap.width * scale).toInt())
        val sampleHeight = max(3, (bitmap.height * scale).toInt())

        val sample =
            if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true)
            } else {
                bitmap
            }

        return try {
            val width = sample.width
            val height = sample.height
            val pixels = IntArray(width * height)
            sample.getPixels(pixels, 0, width, 0, 0, width, height)

            // Grayscale luminance (ITU-R BT.601 integer weights).
            val gray = IntArray(width * height)
            for (i in pixels.indices) {
                val p = pixels[i]
                gray[i] = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            }

            // 4-neighbour Laplacian kernel [0 1 0; 1 -4 1; 0 1 0] over interior pixels.
            var sum = 0.0
            var sumSq = 0.0
            var count = 0L
            for (y in 1 until height - 1) {
                for (x in 1 until width - 1) {
                    val idx = y * width + x
                    val lap =
                        (
                            gray[idx - 1] + gray[idx + 1] +
                                gray[idx - width] + gray[idx + width] -
                                4 * gray[idx]
                        ).toDouble()
                    sum += lap
                    sumSq += lap * lap
                    count++
                }
            }
            if (count == 0L) return 0.0
            val mean = sum / count
            (sumSq / count) - (mean * mean)
        } catch (_: Throwable) {
            0.0
        } finally {
            if (sample !== bitmap && !sample.isRecycled) sample.recycle()
        }
    }
}
