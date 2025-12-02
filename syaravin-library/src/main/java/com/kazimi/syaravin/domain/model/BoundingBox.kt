package com.kazimi.syaravin.domain.model

/**
 * Represents a bounding box for detected objects
 * @property left Left coordinate (0.0 to 1.0, normalized)
 * @property top Top coordinate (0.0 to 1.0, normalized)
 * @property right Right coordinate (0.0 to 1.0, normalized)
 * @property bottom Bottom coordinate (0.0 to 1.0, normalized)
 * @property confidence Detection confidence score (0.0 to 1.0)
 */
internal data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float = 0f
) {
    /**
     * Width of the bounding box (normalized)
     */
    val width: Float
        get() = right - left
    
    /**
     * Height of the bounding box (normalized)
     */
    val height: Float
        get() = bottom - top
    
    /**
     * Center X coordinate (normalized)
     */
    val centerX: Float
        get() = left + width / 2
    
    /**
     * Center Y coordinate (normalized)
     */
    val centerY: Float
        get() = top + height / 2
    
    /**
     * Converts normalized coordinates to pixel coordinates
     * @param imageWidth The width of the image in pixels
     * @param imageHeight The height of the image in pixels
     * @return BoundingBox with pixel coordinates
     */
    fun toPixelCoordinates(imageWidth: Int, imageHeight: Int): BoundingBox {
        return BoundingBox(
            left = left * imageWidth,
            top = top * imageHeight,
            right = right * imageWidth,
            bottom = bottom * imageHeight,
            confidence = confidence
        )
    }
}