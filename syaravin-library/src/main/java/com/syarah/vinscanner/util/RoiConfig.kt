package com.syarah.vinscanner.util

import com.syarah.vinscanner.domain.model.BoundingBox

/**
 * Configuration for the Region Of Interest (ROI) used for scanning.
 * Coordinates are normalized (0f..1f) relative to the analyzed image.
 */
internal object RoiConfig {
    // The app runs in portrait mode with 9:16 aspect ratio (540×960)
    const val analyzedImageAspectRatio: Float = 9f / 16f

    // Updated ROI: Wider (match parent with ~16dp padding) and shorter (rectangle strip)
    val roi: BoundingBox = BoundingBox(
        left = 0.04f,   // ~16dp padding on left
        top = 0.44f,    // Center vertical strip (12% height)
        right = 0.96f,  // ~16dp padding on right
        bottom = 0.56f,
        confidence = 1f
    )
}


