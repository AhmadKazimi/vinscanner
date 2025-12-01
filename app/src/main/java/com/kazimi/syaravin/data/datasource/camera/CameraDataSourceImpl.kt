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
