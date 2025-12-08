package com.syarah.vinscanner.data.model

/**
 * Represents the result of VIN validation
 * @property isValid Whether the VIN is valid
 * @property errorMessage Error message if validation fails
 * @property checksumValid Whether the checksum digit is valid
 * @property formatValid Whether the format is valid
 * @property wasTrimmed Whether invalid characters were removed from start/end
 */
internal data class VinValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null,
    val checksumValid: Boolean = false,
    val formatValid: Boolean = false,
    val wasTrimmed: Boolean = false
)