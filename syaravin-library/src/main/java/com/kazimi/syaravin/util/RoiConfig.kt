@file:Suppress("ktlint:standard:property-naming")

package com.kazimi.syaravin.util

import com.kazimi.syaravin.domain.model.BoundingBox

/**
 * Configuration for the Region Of Interest (ROI) used for scanning.
 * Coordinates are normalized (0f..1f) relative to the analyzed image.
 */
internal object RoiConfig {
    // The app runs in portrait mode with ~9:16 aspect ratio (640×1136)
    const val analyzedImageAspectRatio: Float = 9f / 16f

    // Wide centered band sized to a ~4.3:1 crop (matches a typical VIN label strip). Width is
    // near-max (0.92); the analysis frame is ~3:4, so height 0.16 yields ≈4.3:1.
    // RoiOverlay draws from this same box, so the visual guide tracks the crop.
    val roi: BoundingBox =
        BoundingBox(
            left = 0.04f, // ~16dp padding on left
            top = 0.42f, // Center vertical band (16% height)
            right = 0.96f, // ~16dp padding on right
            bottom = 0.58f,
            confidence = 1f,
        )
}
