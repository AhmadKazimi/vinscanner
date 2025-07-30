package com.kazimi.syaravin.data.datasource.ml

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import com.kazimi.syaravin.domain.model.BoundingBox
import kotlinx.coroutines.tasks.await
import timber.log.Timber

/**
 * Implementation of TextExtractor using ML Kit Text Recognition
 */
class TextExtractorImpl(
    private val textRecognizer: TextRecognizer
) : TextExtractor {
    
    override suspend fun extractText(bitmap: Bitmap, boundingBox: BoundingBox): String? {
        return try {
            // Convert normalized coordinates to pixel coordinates
            val pixelBox = boundingBox.toPixelCoordinates(bitmap.width, bitmap.height)
            
            // Crop the bitmap to the bounding box region
            val croppedBitmap = cropBitmap(bitmap, pixelBox)
            
            // Create InputImage from cropped bitmap
            val inputImage = InputImage.fromBitmap(croppedBitmap, 0)
            
            // Process the image
            val result = textRecognizer.process(inputImage).await()
            
            // Clean up
            if (croppedBitmap != bitmap) {
                croppedBitmap.recycle()
            }
            
            // Return the extracted text
            val extractedText = result.text.trim()
            if (extractedText.isNotEmpty()) {
                Timber.d("Extracted text from region: $extractedText")
                extractedText
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Error extracting text from region")
            null
        }
    }
    
    override suspend fun extractAllText(bitmap: Bitmap): List<String> {
        return try {
            // Create InputImage from bitmap
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            
            // Process the image
            val result = textRecognizer.process(inputImage).await()
            
            // Extract all text blocks
            val textBlocks = mutableListOf<String>()
            
            for (block in result.textBlocks) {
                val blockText = block.text.trim()
                if (blockText.isNotEmpty()) {
                    textBlocks.add(blockText)
                }
            }
            
            Timber.d("Extracted ${textBlocks.size} text blocks from image")
            textBlocks
        } catch (e: Exception) {
            Timber.e(e, "Error extracting all text from image")
            emptyList()
        }
    }
    
    private fun cropBitmap(bitmap: Bitmap, boundingBox: BoundingBox): Bitmap {
        // Ensure coordinates are within bitmap bounds
        val left = boundingBox.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = boundingBox.top.toInt().coerceIn(0, bitmap.height - 1)
        val width = (boundingBox.right - boundingBox.left).toInt()
            .coerceIn(1, bitmap.width - left)
        val height = (boundingBox.bottom - boundingBox.top).toInt()
            .coerceIn(1, bitmap.height - top)
        
        return if (left == 0 && top == 0 && width == bitmap.width && height == bitmap.height) {
            // No need to crop, return original
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, left, top, width, height)
        }
    }
}