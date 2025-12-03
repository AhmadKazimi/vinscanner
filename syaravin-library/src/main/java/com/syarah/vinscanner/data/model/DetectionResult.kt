package com.syarah.vinscanner.data.model

import com.syarah.vinscanner.domain.model.BoundingBox

/**
 * Represents the result of object detection from the ML model
 * @property boundingBoxes List of detected bounding boxes
 * @property processingTimeMs Time taken to process the image in milliseconds
 */
internal data class DetectionResult(
    val boundingBoxes: List<BoundingBox>,
    val processingTimeMs: Long = 0
)