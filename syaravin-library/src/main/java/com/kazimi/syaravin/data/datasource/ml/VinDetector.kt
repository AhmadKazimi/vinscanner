package com.kazimi.syaravin.data.datasource.ml

import android.graphics.Bitmap
import com.kazimi.syaravin.data.model.DetectionResult

/**
 * Interface for VIN detection using ML model
 */
internal interface VinDetector {
    /** Initializes the interpreter and runs delegate warmup on its inference thread. */
    suspend fun warmUp()

    /**
     * Detects VIN regions in an image
     * @param bitmap The image to analyze
     * @param confidenceThreshold Minimum confidence threshold for detections
     * @return Detection result with bounding boxes
     */
    suspend fun detect(
        bitmap: Bitmap,
        confidenceThreshold: Float = 0.25f,
    ): DetectionResult

    /**
     * Preprocesses bitmap for model input
     * @param bitmap The original bitmap
     * @return Preprocessed bitmap ready for model inference
     */
    fun preprocessImage(bitmap: Bitmap): Bitmap
}
