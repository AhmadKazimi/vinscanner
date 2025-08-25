package com.kazimi.syaravin.util

import com.kazimi.syaravin.domain.model.BoundingBox

/**
 * Configuration for the Region Of Interest (ROI) used for scanning.
 * Coordinates are normalized (0f..1f) relative to the analyzed image.
 */
object RoiConfig {
    // The analyzed image aspect ratio after rotation is portrait 9:16 by default
    const val analyzedImageAspectRatio: Float = 9f / 16f

    // Default ROI focused around the center: wide, short band for VIN
    // Left/Right padding ~5%, vertical band around center ~20% height
    val roi: BoundingBox = BoundingBox(
        left = 0.05f,
        top = 0.40f,
        right = 0.95f,
        bottom = 0.60f,
        confidence = 1f
    )
}


