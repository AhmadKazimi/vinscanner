package com.kazimi.syaravin.data.datasource.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import com.kazimi.syaravin.domain.model.BoundingBox
import com.kazimi.syaravin.util.LogTags
import com.kazimi.syaravin.util.SLog
import com.kazimi.syaravin.util.ScannerPerfConfig
import com.kazimi.syaravin.util.ThrottledDurationLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

private const val TAG = LogTags.LIBRARY

/**
 * Implementation of CameraDataSource for camera operations
 */
internal class CameraDataSourceImpl(
    private val context: Context,
) : CameraDataSource {
    private val imageToBitmapTiming = ThrottledDurationLogger("image_to_bitmap_total", 30, ScannerPerfConfig.perfLogsEnabled)
    private val yuvToRgbTiming = ThrottledDurationLogger("yuv_to_rgb_direct", 30, ScannerPerfConfig.perfLogsEnabled)
    private val rotateTiming = ThrottledDurationLogger("bitmap_rotate", 30, ScannerPerfConfig.perfLogsEnabled)
    private var pixelBuffer = IntArray(0)
    private var yPlaneBuffer = ByteArray(0)
    private var uPlaneBuffer = ByteArray(0)
    private var vPlaneBuffer = ByteArray(0)
    private var nv21Buffer = ByteArray(0)
    private val jpegOutputStream = ByteArrayOutputStream()

    override fun startCamera(): Flow<ImageProxy> =
        callbackFlow {
            // Camera flow will be implemented with CameraX in the presentation layer
            // This is a placeholder for the flow structure
            awaitClose {
                // Cleanup will be handled by CameraX lifecycle
            }
        }

    override fun stopCamera() {
        // Camera stop will be handled by CameraX lifecycle
    }

    @Synchronized
    override fun imageToBitmap(
        imageProxy: ImageProxy,
        cropRegion: BoundingBox?,
    ): Bitmap {
        val startNs = System.nanoTime()
        return when (imageProxy.format) {
            ImageFormat.YUV_420_888 -> {
                convertYuvToBitmap(imageProxy, cropRegion).also {
                    imageToBitmapTiming.log(System.nanoTime() - startNs)
                }
            }

            ImageFormat.NV21, ImageFormat.NV16 -> {
                convertYuvToBitmap(imageProxy, cropRegion).also {
                    imageToBitmapTiming.log(System.nanoTime() - startNs)
                }
            }

            else -> {
                throw IllegalArgumentException("Unsupported image format: ${imageProxy.format}")
            }
        }
    }

    private fun convertYuvToBitmap(
        imageProxy: ImageProxy,
        cropRegion: BoundingBox?,
    ): Bitmap =
        try {
            convertYuvToBitmapDirect(imageProxy, cropRegion)
        } catch (e: Exception) {
            SLog.e(TAG, "✗ Direct YUV→RGB conversion FAILED, falling back to JPEG method", e)
            val startTime = System.currentTimeMillis()
            val bitmap = convertYuvToBitmapViaJpeg(imageProxy, cropRegion)
            val duration = System.currentTimeMillis() - startTime
            SLog.w(TAG, "Fallback JPEG conversion completed in ${duration}ms - Bitmap: ${bitmap.width}x${bitmap.height}")
            bitmap
        }

    /**
     * Direct YUV to RGB conversion for maximum image quality.
     * Eliminates JPEG compression artifacts that degrade AI detection.
     */
    private fun convertYuvToBitmapDirect(
        imageProxy: ImageProxy,
        cropRegion: BoundingBox?,
    ): Bitmap {
        val conversionStartNs = System.nanoTime()
        check(imageProxy.planes.size >= 3) { "Expected three YUV planes" }
        val crop =
            sourceCrop(
                imageWidth = imageProxy.width,
                imageHeight = imageProxy.height,
                rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                outputCrop = cropRegion,
            )
        val cropWidth = crop.right - crop.left
        val cropHeight = crop.bottom - crop.top
        val pixelCount = cropWidth * cropHeight
        if (pixelBuffer.size < pixelCount) pixelBuffer = IntArray(pixelCount)

        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]
        yPlaneBuffer = copyPlane(yPlane.buffer, yPlaneBuffer)
        uPlaneBuffer = copyPlane(uPlane.buffer, uPlaneBuffer)
        vPlaneBuffer = copyPlane(vPlane.buffer, vPlaneBuffer)

        var outputIndex = 0
        for (sourceRow in crop.top until crop.bottom) {
            val yRowOffset = sourceRow * yPlane.rowStride
            val uRowOffset = (sourceRow / 2) * uPlane.rowStride
            val vRowOffset = (sourceRow / 2) * vPlane.rowStride
            for (sourceColumn in crop.left until crop.right) {
                val y =
                    ((yPlaneBuffer[yRowOffset + sourceColumn].toInt() and 0xFF) - 16)
                        .coerceAtLeast(0)
                val chromaColumn = sourceColumn / 2
                val u = (uPlaneBuffer[uRowOffset + chromaColumn * uPlane.pixelStride].toInt() and 0xFF) - 128
                val v = (vPlaneBuffer[vRowOffset + chromaColumn * vPlane.pixelStride].toInt() and 0xFF) - 128

                // Integer ITU-R BT.601 conversion avoids floating-point work per pixel.
                val yScaled = 1192 * y
                val red = ((yScaled + 1634 * v) shr 10).coerceIn(0, 255)
                val green = ((yScaled - 400 * u - 833 * v) shr 10).coerceIn(0, 255)
                val blue = ((yScaled + 2066 * u) shr 10).coerceIn(0, 255)
                pixelBuffer[outputIndex++] =
                    (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }

        val bitmap = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixelBuffer, 0, cropWidth, 0, 0, cropWidth, cropHeight)
        yuvToRgbTiming.log(System.nanoTime() - conversionStartNs)

        val rotateStartNs = System.nanoTime()
        val rotated = rotateBitmapAndRecycle(bitmap, imageProxy.imageInfo.rotationDegrees)
        rotateTiming.log(System.nanoTime() - rotateStartNs)
        return rotated
    }

    /**
     * Fallback JPEG-based conversion (original implementation).
     * Used if direct YUV→RGB conversion fails.
     */
    private fun convertYuvToBitmapViaJpeg(
        imageProxy: ImageProxy,
        cropRegion: BoundingBox?,
    ): Bitmap {
        check(imageProxy.planes.size >= 3) { "Expected three YUV planes" }
        val crop =
            evenSourceCrop(
                sourceCrop(
                    imageWidth = imageProxy.width,
                    imageHeight = imageProxy.height,
                    rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                    outputCrop = cropRegion,
                ),
                imageProxy.width,
                imageProxy.height,
            )
        val width = crop.right - crop.left
        val height = crop.bottom - crop.top
        val ySize = width * height
        val requiredSize = ySize + ySize / 2
        if (nv21Buffer.size < requiredSize) nv21Buffer = ByteArray(requiredSize)

        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]
        yPlaneBuffer = copyPlane(yPlane.buffer, yPlaneBuffer)
        uPlaneBuffer = copyPlane(uPlane.buffer, uPlaneBuffer)
        vPlaneBuffer = copyPlane(vPlane.buffer, vPlaneBuffer)

        var destination = 0
        for (row in crop.top until crop.bottom) {
            val sourceOffset = row * yPlane.rowStride + crop.left
            yPlaneBuffer.copyInto(
                nv21Buffer,
                destinationOffset = destination,
                startIndex = sourceOffset,
                endIndex = sourceOffset + width,
            )
            destination += width
        }
        for (row in crop.top / 2 until crop.bottom / 2) {
            val uRowOffset = row * uPlane.rowStride
            val vRowOffset = row * vPlane.rowStride
            for (column in crop.left / 2 until crop.right / 2) {
                nv21Buffer[destination++] = vPlaneBuffer[vRowOffset + column * vPlane.pixelStride]
                nv21Buffer[destination++] = uPlaneBuffer[uRowOffset + column * uPlane.pixelStride]
            }
        }

        val yuvImage =
            YuvImage(
                nv21Buffer,
                ImageFormat.NV21,
                width,
                height,
                null,
            )

        jpegOutputStream.reset()
        yuvImage.compressToJpeg(
            Rect(0, 0, width, height),
            85, // 85% quality for thermal efficiency
            jpegOutputStream,
        )

        val jpegByteArray = jpegOutputStream.toByteArray()
        val bitmap =
            BitmapFactory.decodeByteArray(jpegByteArray, 0, jpegByteArray.size)
                ?: error("Failed to decode YUV fallback JPEG")

        return rotateBitmapAndRecycle(bitmap, imageProxy.imageInfo.rotationDegrees)
    }

    private fun rotateBitmapAndRecycle(
        bitmap: Bitmap,
        rotationDegrees: Int,
    ): Bitmap {
        if (rotationDegrees == 0) return bitmap

        val matrix =
            Matrix().apply {
                postRotate(rotationDegrees.toFloat())
            }

        return Bitmap
            .createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true,
            ).also { rotated ->
                if (rotated !== bitmap) bitmap.recycle()
            }
    }

    private fun copyPlane(
        source: ByteBuffer,
        reusable: ByteArray,
    ): ByteArray {
        val duplicate = source.duplicate()
        val requiredSize = duplicate.remaining()
        val destination = if (reusable.size >= requiredSize) reusable else ByteArray(requiredSize)
        duplicate.get(destination, 0, requiredSize)
        return destination
    }
}

internal fun sourceCrop(
    imageWidth: Int,
    imageHeight: Int,
    rotationDegrees: Int,
    outputCrop: BoundingBox?,
): SourceCrop {
    if (outputCrop == null) return SourceCrop(0, 0, imageWidth, imageHeight)

    val left = outputCrop.left.coerceIn(0f, 1f)
    val top = outputCrop.top.coerceIn(0f, 1f)
    val right = outputCrop.right.coerceIn(left, 1f)
    val bottom = outputCrop.bottom.coerceIn(top, 1f)
    val normalizedSource =
        when (((rotationDegrees % 360) + 360) % 360) {
            0 -> floatArrayOf(left, top, right, bottom)
            90 -> floatArrayOf(top, 1f - right, bottom, 1f - left)
            180 -> floatArrayOf(1f - right, 1f - bottom, 1f - left, 1f - top)
            270 -> floatArrayOf(1f - bottom, left, 1f - top, right)
            else -> error("Unsupported image rotation: $rotationDegrees")
        }
    val sourceLeft =
        kotlin.math
            .floor(normalizedSource[0] * imageWidth)
            .toInt()
            .coerceIn(0, imageWidth - 1)
    val sourceTop =
        kotlin.math
            .floor(normalizedSource[1] * imageHeight)
            .toInt()
            .coerceIn(0, imageHeight - 1)
    val sourceRight =
        kotlin.math
            .ceil(normalizedSource[2] * imageWidth)
            .toInt()
            .coerceIn(sourceLeft + 1, imageWidth)
    val sourceBottom =
        kotlin.math
            .ceil(normalizedSource[3] * imageHeight)
            .toInt()
            .coerceIn(sourceTop + 1, imageHeight)
    return SourceCrop(sourceLeft, sourceTop, sourceRight, sourceBottom)
}

internal fun evenSourceCrop(
    crop: SourceCrop,
    imageWidth: Int,
    imageHeight: Int,
): SourceCrop {
    val left = crop.left and -2
    val top = crop.top and -2
    val right = ((crop.right + 1) and -2).coerceAtMost(imageWidth and -2)
    val bottom = ((crop.bottom + 1) and -2).coerceAtMost(imageHeight and -2)
    return SourceCrop(
        left = left.coerceAtMost(right - 2),
        top = top.coerceAtMost(bottom - 2),
        right = right,
        bottom = bottom,
    )
}

internal data class SourceCrop(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)
