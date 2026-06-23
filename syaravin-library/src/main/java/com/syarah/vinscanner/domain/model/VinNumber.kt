package com.syarah.vinscanner.domain.model

import android.graphics.Bitmap
import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/**
 * Represents a Vehicle Identification Number (VIN)
 * @property value The VIN string (should be 17 characters)
 * @property confidence The confidence score of the detection (0.0 to 1.0)
 * @property isValid Whether the VIN passes validation checks
 * @property croppedImage in-process bitmap used while scanning; never written to a Parcel
 * @property croppedImageUri bounded cache-backed result image URI returned across activities
 */
@Parcelize
data class VinNumber(
    val value: String,
    val confidence: Float = 0f,
    val isValid: Boolean = false,
    @IgnoredOnParcel val croppedImage: Bitmap? = null,
    val croppedImageUri: Uri? = null,
) : Parcelable {
    companion object {
        const val VIN_LENGTH = 17
        val INVALID_CHARACTERS = setOf('I', 'O', 'Q', 'i', 'o', 'q')
        val VALID_PATTERN = Regex("[A-HJ-NPR-Z0-9]{17}", RegexOption.IGNORE_CASE)
    }
}
