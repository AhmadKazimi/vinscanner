package com.kazimi.syaravin.data.datasource.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.Log
import com.kazimi.syaravin.data.model.DetectionResult
import com.kazimi.syaravin.domain.model.BoundingBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

private const val TAG = "VinDetectorImpl"

/**
 * Implementation of VinDetector using TensorFlow Lite
 */
class VinDetectorImpl(
    private val interpreter: Interpreter
) : VinDetector {

    companion object {
        // Model constants - adjust these based on your model's requirements
        private const val MODEL_INPUT_SIZE = 640 // Typical size for object detection models
        private const val PIXEL_SIZE = 3 // RGB
        private const val IMAGE_MEAN = 127.5f
        private const val IMAGE_STD = 127.5f

        // Output array sizes - adjust based on your model
        private const val MAX_DETECTIONS = 10
        private const val NUM_COORDINATES = 4 // [ymin, xmin, ymax, xmax]

        // New constants for YOLO model
        private const val OUTPUT_TENSOR_WIDTH = 8400
        private const val OUTPUT_TENSOR_PROPERTIES = 5 // [x, y, w, h, conf]
    }

    // Pre-allocated buffers for better performance
    private val imgData: ByteBuffer = ByteBuffer.allocateDirect(
        MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * PIXEL_SIZE * 4 // 4 bytes per float
    ).apply {
        order(ByteOrder.nativeOrder())
    }

    private val outputBuffer = Array(1) { Array(OUTPUT_TENSOR_PROPERTIES) { FloatArray(OUTPUT_TENSOR_WIDTH) } }

    override suspend fun detect(bitmap: Bitmap, confidenceThreshold: Float): DetectionResult =
        withContext(Dispatchers.Default) {
            val startTime = System.currentTimeMillis()

            try {
                // Preprocessing is likely still correct
                val preprocessedBitmap = preprocessImage(bitmap)
                convertBitmapToByteBuffer(preprocessedBitmap)

                // Prepare the new output map for a single output
                val outputMap = mapOf(0 to outputBuffer)

                // Run inference
                interpreter.runForMultipleInputsOutputs(arrayOf(imgData), outputMap)

                // --- REWRITE THE POST-PROCESSING LOGIC ---
                val boundingBoxes = mutableListOf<BoundingBox>()

                // Get the results from the buffer: shape is [5, 8400]
                val detections = outputBuffer[0]

                for (i in 0 until OUTPUT_TENSOR_WIDTH) {
                    val confidence = detections[4][i] // 5th element is confidence

                    if (confidence >= confidenceThreshold) {
                        val xCenter = detections[0][i]
                        val yCenter = detections[1][i]
                        val width = detections[2][i]
                        val height = detections[3][i]

                        // Convert from [x_center, y_center, width, height] to [left, top, right, bottom]
                        // The coordinates are likely relative to the 640x640 input size,
                        // so we normalize them to [0, 1] to match your BoundingBox model.
                        val left = (xCenter - width / 2) / MODEL_INPUT_SIZE
                        val top = (yCenter - height / 2) / MODEL_INPUT_SIZE
                        val right = (xCenter + width / 2) / MODEL_INPUT_SIZE
                        val bottom = (yCenter + height / 2) / MODEL_INPUT_SIZE

                        boundingBoxes.add(
                            BoundingBox(
                                left = left,
                                top = top,
                                right = right,
                                bottom = bottom,
                                confidence = confidence
                            )
                        )
                    }
                }

                // Note: YOLO models often produce many overlapping boxes.
                // You may need to add a Non-Max Suppression (NMS) step here for best results.

                val processingTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "Detection completed in ${processingTime}ms, found ${boundingBoxes.size} potential VIN regions")

                DetectionResult(
                    boundingBoxes = boundingBoxes, // You might want to apply NMS to this list
                    processingTimeMs = processingTime
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error during VIN detection", e)
                DetectionResult(
                    boundingBoxes = emptyList(),
                    processingTimeMs = System.currentTimeMillis() - startTime
                )
            }
        }

    override fun preprocessImage(bitmap: Bitmap): Bitmap {
        Log.d(TAG, "Original bitmap dimensions: ${bitmap.width}x${bitmap.height}")
        // Create a square bitmap with MODEL_INPUT_SIZE dimensions
        val scaleFactor = min(
            MODEL_INPUT_SIZE.toFloat() / bitmap.width,
            MODEL_INPUT_SIZE.toFloat() / bitmap.height
        )

        val scaledWidth = (bitmap.width * scaleFactor).toInt()
        val scaledHeight = (bitmap.height * scaleFactor).toInt()

        // Create scaled bitmap
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

        // Create square bitmap with padding if needed
        val outputBitmap = Bitmap.createBitmap(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)

        // Calculate padding to center the image
        val left = (MODEL_INPUT_SIZE - scaledWidth) / 2f
        val top = (MODEL_INPUT_SIZE - scaledHeight) / 2f

        // Draw scaled bitmap centered on canvas
        canvas.drawBitmap(scaledBitmap, left, top, null)

        // Clean up
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        return outputBitmap
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap) {
        imgData.rewind()

        val intValues = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        // Convert the image pixels to floating point values
        for (pixel in intValues) {
            val r = (pixel shr 16 and 0xFF)
            val g = (pixel shr 8 and 0xFF)
            val b = (pixel and 0xFF)

            // Normalize pixel values
            imgData.putFloat((r - IMAGE_MEAN) / IMAGE_STD)
            imgData.putFloat((g - IMAGE_MEAN) / IMAGE_STD)
            imgData.putFloat((b - IMAGE_MEAN) / IMAGE_STD)
        }
    }
}
