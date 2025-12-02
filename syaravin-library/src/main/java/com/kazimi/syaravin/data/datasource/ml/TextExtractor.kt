package com.kazimi.syaravin.data.datasource.ml

import android.graphics.Bitmap
import com.kazimi.syaravin.domain.model.BoundingBox

/**
 * Data class representing extracted text with its bounding box
 */
internal data class TextWithBounds(
    val text: String,
    val boundingBox: BoundingBox
)

/**
 * Interface for text extraction from images
 */
internal interface TextExtractor {
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

    /**
     * Extracts all text from an image with their bounding boxes
     * @param bitmap The image to analyze
     * @return List of text blocks with their bounding boxes (normalized coordinates)
     */
    suspend fun extractAllTextWithBounds(bitmap: Bitmap): List<TextWithBounds>
}