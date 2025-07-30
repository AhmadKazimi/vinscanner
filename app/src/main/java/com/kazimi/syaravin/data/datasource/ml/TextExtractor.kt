package com.kazimi.syaravin.data.datasource.ml

import android.graphics.Bitmap
import com.kazimi.syaravin.domain.model.BoundingBox

/**
 * Interface for text extraction from images
 */
interface TextExtractor {
    /**
     * Extracts text from a specific region of an image
     * @param bitmap The image to analyze
     * @param boundingBox The region to extract text from (normalized coordinates)
     * @return Extracted text or null if extraction fails
     */
    suspend fun extractText(bitmap: Bitmap, boundingBox: BoundingBox): String?
    
    /**
     * Extracts all text from an image
     * @param bitmap The image to analyze
     * @return List of extracted text blocks
     */
    suspend fun extractAllText(bitmap: Bitmap): List<String>
}