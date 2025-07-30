package com.kazimi.syaravin.data.model

import com.kazimi.syaravin.domain.model.BoundingBox

/**
 * Represents the result of object detection from the ML model
 * @property boundingBoxes List of detected bounding boxes
 * @property processingTimeMs Time taken to process the image in milliseconds
 */
data class DetectionResult(
    val boundingBoxes: List<BoundingBox>,
    val processingTimeMs: Long = 0
)