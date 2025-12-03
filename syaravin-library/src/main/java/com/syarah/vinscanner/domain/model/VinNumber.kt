package com.syarah.vinscanner.domain.model

import android.graphics.Bitmap
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents a Vehicle Identification Number (VIN)
 * @property value The VIN string (should be 17 characters)
 * @property confidence The confidence score of the detection (0.0 to 1.0)
 * @property isValid Whether the VIN passes validation checks
 * @property croppedImage The cropped bitmap showing the detected VIN region (optional)
 */
@Parcelize
data class VinNumber(
    val value: String,
    val confidence: Float = 0f,
    val isValid: Boolean = false,
    val croppedImage: Bitmap? = null
) : Parcelable {
    companion object {
        const val VIN_LENGTH = 17
        val INVALID_CHARACTERS = setOf('I', 'O', 'Q', 'i', 'o', 'q')
        val VALID_PATTERN = Regex("[A-HJ-NPR-Z0-9]{17}", RegexOption.IGNORE_CASE)
    }
}