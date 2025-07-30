package com.kazimi.syaravin.data.datasource.ml

import android.graphics.Bitmap
import com.kazimi.syaravin.data.model.DetectionResult

/**
 * Interface for VIN detection using ML model
 */
interface VinDetector {
    /**
     * Detects VIN regions in an image
     * @param bitmap The image to analyze
     * @param confidenceThreshold Minimum confidence threshold for detections
     * @return Detection result with bounding boxes
     */
    suspend fun detect(bitmap: Bitmap, confidenceThreshold: Float = 0.5f): DetectionResult
    
    /**
     * Preprocesses bitmap for model input
     * @param bitmap The original bitmap
     * @return Preprocessed bitmap ready for model inference
     */
    fun preprocessImage(bitmap: Bitmap): Bitmap
}