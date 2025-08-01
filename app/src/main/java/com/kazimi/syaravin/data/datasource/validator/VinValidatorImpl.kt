package com.kazimi.syaravin.data.datasource.validator

import android.util.Log
import com.kazimi.syaravin.data.model.VinValidationResult
import com.kazimi.syaravin.domain.model.VinNumber

private const val TAG = "VinValidatorImpl"

/**
 * Implementation of VinValidator with standard VIN validation rules
 */
class VinValidatorImpl : VinValidator {

    companion object {
        // Character to value mapping for VIN checksum calculation
        private val TRANSLITERATION = mapOf(
            'A' to 1, 'B' to 2, 'C' to 3, 'D' to 4, 'E' to 5, 'F' to 6, 'G' to 7, 'H' to 8,
            'J' to 1, 'K' to 2, 'L' to 3, 'M' to 4, 'N' to 5, 'P' to 7, 'R' to 9, 'S' to 2,
            'T' to 3, 'U' to 4, 'V' to 5, 'W' to 6, 'X' to 7, 'Y' to 8, 'Z' to 9, '1' to 1,
            '2' to 2, '3' to 3, '4' to 4, '5' to 5, '6' to 6, '7' to 7, '8' to 8, '9' to 9, '0' to 0
        )

        // Weight factors for each position in VIN
        private val WEIGHTS = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)

        // Common OCR misreadings
        private val OCR_CORRECTIONS = mapOf(
            'I' to '1', 'i' to '1',
            'O' to '0', 'o' to '0',
            'Q' to '0', 'q' to '0',
            'S' to '5', 's' to '5',
            'Z' to '2', 'z' to '2'
        )
    }

    override fun validate(vin: String): VinValidationResult {
        Log.d(TAG, "Validating text: $vin")
        val correctedVin = correctOcrErrors(vin)
        val extractedVin = extractVin(correctedVin)

        if (extractedVin == null) {
            val result = VinValidationResult(
                isValid = false,
                errorMessage = "No valid 17-character VIN found in the text.",
                formatValid = false
            )
            Log.d(TAG, "Validation result for '$vin': $result")
            return result
        }

        Log.d(TAG, "Extracted VIN: $extractedVin")


        // 1. Check length
        if (extractedVin.length != VinNumber.VIN_LENGTH) {
            val result = VinValidationResult(
                isValid = false,
                errorMessage = "VIN must be 17 characters long, but was ${extractedVin.length}",
                formatValid = false
            )
            Log.d(TAG, "Validation result for '$vin': $result")
            return result
        }

        // 2. Check for invalid characters (I, O, Q)
        val hasInvalidChars = extractedVin.any { it in VinNumber.INVALID_CHARACTERS }
        if (hasInvalidChars) {
            val result = VinValidationResult(
                isValid = false,
                errorMessage = "VIN contains invalid characters (I, O, or Q)",
                formatValid = false
            )
            Log.d(TAG, "Validation result for '$vin': $result")
            return result
        }


        // 3. Validate Checksum
        val checksumValid = validateChecksum(extractedVin)
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
                formatValid = true // Format is valid at this point, but checksum failed
            )
        }

        Log.d(TAG, "Validation result for '$vin': $result")
        return result
    }

    override fun cleanVin(vin: String): String {
        val correctedVin = correctOcrErrors(vin)
        return extractVin(correctedVin) ?: ""
    }

    private fun correctOcrErrors(text: String): String {
        return text.map { OCR_CORRECTIONS[it] ?: it }.joinToString("")
    }

    private fun extractVin(text: String): String? {
        // Remove all non-alphanumeric characters
        val alphanumericText = text.replace(Regex("[^A-Z0-9]"), "")

        // Find a 17-character VIN
        val vinRegex = Regex("[A-Z0-9]{17}")
        val match = vinRegex.find(alphanumericText.uppercase())

        return match?.value
    }


    private fun validateChecksum(vin: String): Boolean {
        Log.d(TAG, "Validating checksum for VIN: $vin")

        // The 9th character is the check digit; it's not part of the sum calculation.
        // We use its space for the weight 10, but multiply the actual character value.
        // The check digit itself is at index 8.

        var sum = 0
        for (i in vin.indices) {
            // Skip the check digit position in the calculation
            if (i == 8) continue

            val char = vin[i]
            val value = TRANSLITERATION[char]
                ?: return false.also { Log.e(TAG, "Invalid character in VIN for checksum: $char") }

            val weight = WEIGHTS[i]
            sum += value * weight
        }

        val remainder = sum % 11
        val checkDigit = vin[8]
        val expectedDigit = if (remainder == 10) 'X' else Character.forDigit(remainder, 10)

        val isValid = checkDigit == expectedDigit
        if (!isValid) {
            Log.w(TAG, "Checksum validation failed for '$vin'. Expected: $expectedDigit, Found: $checkDigit")
        }
        return isValid
    }
}