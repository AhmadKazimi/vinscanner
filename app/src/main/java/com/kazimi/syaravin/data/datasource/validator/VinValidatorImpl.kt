package com.kazimi.syaravin.data.datasource.validator

import com.kazimi.syaravin.data.model.VinValidationResult
import com.kazimi.syaravin.domain.model.VinNumber
import timber.log.Timber

/**
 * Implementation of VinValidator with standard VIN validation rules
 */
class VinValidatorImpl : VinValidator {
    
    companion object {
        // Character to value mapping for VIN checksum calculation
        private val TRANSLITERATION = mapOf(
            'A' to 1, 'B' to 2, 'C' to 3, 'D' to 4, 'E' to 5, 'F' to 6, 'G' to 7, 'H' to 8,
            'J' to 1, 'K' to 2, 'L' to 3, 'M' to 4, 'N' to 5, 'P' to 7, 'R' to 9,
            'S' to 2, 'T' to 3, 'U' to 4, 'V' to 5, 'W' to 6, 'X' to 7, 'Y' to 8, 'Z' to 9,
            '1' to 1, '2' to 2, '3' to 3, '4' to 4, '5' to 5, '6' to 6, '7' to 7, '8' to 8, '9' to 9, '0' to 0
        )
        
        // Weight factors for each position in VIN
        private val WEIGHTS = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)
        
        // Common OCR misreadings
        private val OCR_CORRECTIONS = mapOf(
            'I' to '1', 'i' to '1',
            'O' to '0', 'o' to '0',
            'Q' to '0', 'q' to '0',
            'S' to '5', 's' to '5',
            'Z' to '2', 'z' to '2',
            // Additional common OCR confusions
            'B' to '8', 'b' to '8',
            'G' to '6', 'g' to '6',
            'T' to '7', 't' to '7',
            'D' to '0', 'd' to '0'
        )
    }
    
    override fun validate(vin: String): VinValidationResult {
        Timber.d("Validating VIN: $vin")
        val cleanedVin = cleanVin(vin)
        
        // Check length
        if (cleanedVin.length != VinNumber.VIN_LENGTH) {
            val result = VinValidationResult(
                isValid = false,
                errorMessage = "VIN must be exactly 17 characters long (found ${cleanedVin.length})",
                formatValid = false
            )
            Timber.d("Validation result for '$vin': $result")
            return result
        }
        
        // Check format
        if (!VinNumber.VALID_PATTERN.matches(cleanedVin)) {
            val result = VinValidationResult(
                isValid = false,
                errorMessage = "VIN contains invalid characters",
                formatValid = false
            )
            Timber.d("Validation result for '$vin': $result")
            return result
        }
        
        // Check for invalid characters
        val hasInvalidChars = cleanedVin.any { char ->
            VinNumber.INVALID_CHARACTERS.contains(char.uppercaseChar())
        }
        
        if (hasInvalidChars) {
            val result = VinValidationResult(
                isValid = false,
                errorMessage = "VIN contains invalid characters (I, O, or Q)",
                formatValid = false
            )
            Timber.d("Validation result for '$vin': $result")
            return result
        }
        
        // Validate checksum (9th position)
        val checksumValid = validateChecksum(cleanedVin)
        
        val result = if (checksumValid) {
            VinValidationResult(
                isValid = true,
                checksumValid = true,
                formatValid = true
            )
        } else {
            VinValidationResult(
                isValid = false,
                errorMessage = "Invalid VIN checksum",
                checksumValid = false,
                formatValid = true
            )
        }
        
        Timber.d("Validation result for '$vin': $result")
        return result
    }
    
    override fun cleanVin(vin: String): String {
        // Remove whitespace and convert to uppercase
        var cleaned = vin.trim().uppercase().replace(Regex("\\s+"), "")
        
        // Apply common OCR corrections
        cleaned = cleaned.map { char ->
            OCR_CORRECTIONS[char] ?: char
        }.joinToString("")
        
        // Remove any non-alphanumeric characters
        cleaned = cleaned.replace(Regex("[^A-Z0-9]"), "")
        
        Timber.d("Cleaned VIN: '$vin' -> '$cleaned'")
        return cleaned
    }
    
    private fun validateChecksum(vin: String): Boolean {
        Timber.d("Validating checksum for VIN: $vin")
        return try {
            var sum = 0
            
            for (i in vin.indices) {
                val char = vin[i]
                val value = TRANSLITERATION[char] ?: return false
                sum += value * WEIGHTS[i]
            }
            
            val checkDigit = sum % 11
            val expectedChar = if (checkDigit == 10) 'X' else checkDigit.toString()[0]
            val actualChar = vin[8] // 9th position (0-indexed)
            
            val isValid = actualChar == expectedChar
            
            if (!isValid) {
                Timber.d("Checksum validation failed: expected $expectedChar, got $actualChar")
            } else {
                Timber.d("Checksum validation successful for VIN: $vin")
            }
            
            isValid
        } catch (e: Exception) {
            Timber.e(e, "Error validating checksum")
            false
        }
    }
}