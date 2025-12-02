package com.kazimi.syaravin.data.datasource.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

private const val TAG = "CameraDataSourceImpl"

/**
 * Implementation of CameraDataSource for camera operations
 */
class CameraDataSourceImpl(
    private val context: Context
) : CameraDataSource {

    override fun startCamera(): Flow<ImageProxy> = callbackFlow {
        // Camera flow will be implemented with CameraX in the presentation layer
        // This is a placeholder for the flow structure
        awaitClose {
            // Cleanup will be handled by CameraX lifecycle
        }
    }

    override fun stopCamera() {
        // Camera stop will be handled by CameraX lifecycle
        Log.d(TAG, "Camera stopped")
    }

    override fun imageToBitmap(imageProxy: ImageProxy): Bitmap {
        return when (imageProxy.format) {
            ImageFormat.YUV_420_888 -> convertYuvToBitmap(imageProxy)
            ImageFormat.NV21, ImageFormat.NV16 -> convertYuvToBitmap(imageProxy)
            else -> throw IllegalArgumentException("Unsupported image format: ${imageProxy.format}")
        }
    }

    private fun convertYuvToBitmap(imageProxy: ImageProxy): Bitmap {
        Log.d(TAG, "=== YUV Conversion START ===")
        Log.d(TAG, "ImageProxy format=${imageProxy.format}, width=${imageProxy.width}, height=${imageProxy.height}, rotation=${imageProxy.imageInfo.rotationDegrees}")

        return try {
            Log.d(TAG, "Attempting direct YUV→RGB conversion (no JPEG compression)")
            val startTime = System.currentTimeMillis()
            val bitmap = convertYuvToBitmapDirect(imageProxy)
            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "✓ Direct YUV→RGB conversion SUCCESS in ${duration}ms - Bitmap: ${bitmap.width}x${bitmap.height}, config=${bitmap.config}")
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "✗ Direct YUV→RGB conversion FAILED, falling back to JPEG method", e)
            val startTime = System.currentTimeMillis()
            val bitmap = convertYuvToBitmapViaJpeg(imageProxy)
            val duration = System.currentTimeMillis() - startTime
            Log.w(TAG, "Fallback JPEG conversion completed in ${duration}ms - Bitmap: ${bitmap.width}x${bitmap.height}")
            bitmap
        }
    }

    /**
     * Direct YUV to RGB conversion for maximum image quality.
     * Eliminates JPEG compression artifacts that degrade AI detection.
     */
    private fun convertYuvToBitmapDirect(imageProxy: ImageProxy): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        // Create RGB bitmap directly without JPEG compression
        val width = imageProxy.width
        val height = imageProxy.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Direct YUV to RGB conversion
        val pixels = IntArray(width * height)
        val yData = ByteArray(ySize)
        val uData = ByteArray(uSize)
        val vData = ByteArray(vSize)

        yBuffer.get(yData)
        uBuffer.get(uData)
        vBuffer.get(vData)

        val uvPixelStride = imageProxy.planes[1].pixelStride
        val uvRowStride = imageProxy.planes[1].rowStride

        for (row in 0 until height) {
            for (col in 0 until width) {
                val yIndex = row * imageProxy.planes[0].rowStride + col
                val uvRow = row / 2
                val uvCol = col / 2
                val uvIndex = uvRow * uvRowStride + uvCol * uvPixelStride

                val y = (yData[yIndex].toInt() and 0xFF) - 16
                val u = (uData[uvIndex].toInt() and 0xFF) - 128
                val v = (vData[uvIndex].toInt() and 0xFF) - 128

                // YUV to RGB conversion (ITU-R BT.601)
                val r = (1.164f * y + 1.596f * v).toInt().coerceIn(0, 255)
                val g = (1.164f * y - 0.392f * u - 0.813f * v).toInt().coerceIn(0, 255)
                val b = (1.164f * y + 2.017f * u).toInt().coerceIn(0, 255)

                pixels[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        // Rotate bitmap if needed (preserve existing rotation handling)
        return rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
    }

    /**
     * Fallback JPEG-based conversion (original implementation).
     * Used if direct YUV→RGB conversion fails.
     */
    private fun convertYuvToBitmapViaJpeg(imageProxy: ImageProxy): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // Copy Y plane
        yBuffer.get(nv21, 0, ySize)

        // Interleave U and V planes
        val uvPixelStride = imageProxy.planes[1].pixelStride
        if (uvPixelStride == 1) {
            // Packed format
            uBuffer.get(nv21, ySize, uSize)
            vBuffer.get(nv21, ySize + uSize, vSize)
        } else {
            // Planar format - need to interleave
            var pos = ySize
            for (i in 0 until uSize step uvPixelStride) {
                nv21[pos] = vBuffer.get(i)
                nv21[pos + 1] = uBuffer.get(i)
                pos += 2
            }
        }

        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null
        )

        val outputStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            Rect(0, 0, imageProxy.width, imageProxy.height),
            85, // 85% quality for thermal efficiency
            outputStream
        )

        val jpegByteArray = outputStream.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(jpegByteArray, 0, jpegByteArray.size)

        // Rotate bitmap if needed based on imageProxy rotation
        return rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap

        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }

        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }
}
