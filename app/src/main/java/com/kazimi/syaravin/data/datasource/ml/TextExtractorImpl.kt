package com.kazimi.syaravin.data.datasource.ml

import android.graphics.Bitmap
import com.kazimi.syaravin.domain.model.BoundingBox
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TextExtractorImpl(
    private val interpreter: Interpreter
) : TextExtractor {
    
    override suspend fun extractText(bitmap: Bitmap, boundingBox: BoundingBox): String? {
        // This function is not used with the new LiteRT model,
        // as the model returns all the text at once.
        return null
    }
    
    override suspend fun extractAllText(bitmap: Bitmap): List<String> {
        Timber.d("Starting text extraction from image...")
        return try {
            // Preprocess the image
            Timber.d("Preprocessing image with dimensions: ${bitmap.width}x${bitmap.height}")
            val inputBuffer = preprocessImage(bitmap)
            Timber.d("Image preprocessed successfully.")
            
            // Run inference
            val outputBuffer = Array(1) { Array(100) { Array(4) { FloatArray(2) } } }
            Timber.d("Running inference on the model...")
            interpreter.run(inputBuffer, outputBuffer)
            Timber.d("Inference completed.")
            
            // Log the raw output
            Timber.d("Raw model output: ${outputBuffer.contentDeepToString()}")
            
            // Postprocess the output
            val extractedText = postprocessOutput(outputBuffer)
            
            Timber.d("Extracted ${extractedText.size} text blocks from image")
            extractedText
        } catch (e: Exception) {
            Timber.e(e, "Error extracting all text from image")
            emptyList()
        }
    }
    
    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val inputWidth = 640
        val inputHeight = 640
        
        // Resize the bitmap
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        
        // Create a ByteBuffer
        val inputBuffer = ByteBuffer.allocateDirect(1 * inputWidth * inputHeight * 3 * 4) // 1 * 640 * 640 * 3 * 4
        inputBuffer.order(ByteOrder.nativeOrder())
        
        // Normalize the bitmap
        for (y in 0 until inputHeight) {
            for (x in 0 until inputWidth) {
                val pixel = resizedBitmap.getPixel(x, y)
                inputBuffer.putFloat(((pixel shr 16 and 0xFF) / 255.0f))
                inputBuffer.putFloat(((pixel shr 8 and 0xFF) / 255.0f))
                inputBuffer.putFloat(((pixel and 0xFF) / 255.0f))
            }
        }
        
        return inputBuffer
    }
    
    private fun postprocessOutput(outputBuffer: Array<Array<Array<FloatArray>>>): List<String> {
        // This is a placeholder for the actual postprocessing logic.
        // You will need to implement this based on your model's output format.
        // For now, it returns an empty list.
        Timber.w("Postprocessing is not yet implemented. Returning empty list.")
        return emptyList()
    }
}