package com.kazimi.syaravin.data.datasource.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.kazimi.syaravin.data.model.DetectionResult
import com.kazimi.syaravin.domain.model.BoundingBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

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
    }
    
    // Pre-allocated buffers for better performance
    private val imgData: ByteBuffer = ByteBuffer.allocateDirect(
        MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * PIXEL_SIZE * 4 // 4 bytes per float
    ).apply {
        order(ByteOrder.nativeOrder())
    }
    
    // Output arrays - adjust sizes based on your model's output
    private val outputLocations = Array(1) { Array(MAX_DETECTIONS) { FloatArray(NUM_COORDINATES) } }
    private val outputClasses = Array(1) { FloatArray(MAX_DETECTIONS) }
    private val outputScores = Array(1) { FloatArray(MAX_DETECTIONS) }
    private val numDetections = FloatArray(1)
    
    override suspend fun detect(bitmap: Bitmap, confidenceThreshold: Float): DetectionResult = 
        withContext(Dispatchers.Default) {
            val startTime = System.currentTimeMillis()
            
            try {
                // Preprocess the image
                val preprocessedBitmap = preprocessImage(bitmap)
                
                // Convert bitmap to ByteBuffer
                convertBitmapToByteBuffer(preprocessedBitmap)
                
                // Prepare output map
                val outputMap = mapOf(
                    0 to outputLocations,
                    1 to outputClasses,
                    2 to outputScores,
                    3 to numDetections
                )
                
                // Run inference
                interpreter.runForMultipleInputsOutputs(arrayOf(imgData), outputMap)
                
                // Process results
                val boundingBoxes = mutableListOf<BoundingBox>()
                val detectionCount = numDetections[0].toInt().coerceAtMost(MAX_DETECTIONS)
                
                for (i in 0 until detectionCount) {
                    val score = outputScores[0][i]
                    if (score >= confidenceThreshold) {
                        // TFLite typically outputs [ymin, xmin, ymax, xmax] in normalized coordinates
                        val ymin = outputLocations[0][i][0]
                        val xmin = outputLocations[0][i][1]
                        val ymax = outputLocations[0][i][2]
                        val xmax = outputLocations[0][i][3]
                        
                        boundingBoxes.add(
                            BoundingBox(
                                left = xmin,
                                top = ymin,
                                right = xmax,
                                bottom = ymax,
                                confidence = score
                            )
                        )
                    }
                }
                
                val processingTime = System.currentTimeMillis() - startTime
                Timber.d("Detection completed in ${processingTime}ms, found ${boundingBoxes.size} VIN regions")
                
                DetectionResult(
                    boundingBoxes = boundingBoxes,
                    processingTimeMs = processingTime
                )
            } catch (e: Exception) {
                Timber.e(e, "Error during VIN detection")
                DetectionResult(
                    boundingBoxes = emptyList(),
                    processingTimeMs = System.currentTimeMillis() - startTime
                )
            }
        }
    
    override fun preprocessImage(bitmap: Bitmap): Bitmap {
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