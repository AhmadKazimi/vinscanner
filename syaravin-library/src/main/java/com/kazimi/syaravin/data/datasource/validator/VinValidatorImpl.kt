package com.kazimi.syaravin.data.datasource.validator

import android.content.Context
import com.kazimi.syaravin.R
import com.kazimi.syaravin.data.model.VinValidationResult
import com.kazimi.syaravin.domain.model.VinNumber
import com.kazimi.syaravin.util.LogTags
import com.kazimi.syaravin.util.SLog

private const val TAG = LogTags.LIBRARY

/**
 * Implementation of VinValidator with standard VIN validation rules
 */
internal class VinValidatorImpl(
    private val getString: (Int, Array<out Any>) -> String,
) : VinValidator {
    constructor(context: Context) : this(
        getString = { resourceId, formatArgs -> context.getString(resourceId, *formatArgs) },
    )

    companion object {
        // Character to value mapping for VIN checksum calculation
        private val TRANSLITERATION =
            mapOf(
                'A' to 1,
                'B' to 2,
                'C' to 3,
                'D' to 4,
                'E' to 5,
                'F' to 6,
                'G' to 7,
                'H' to 8,
                'J' to 1,
                'K' to 2,
                'L' to 3,
                'M' to 4,
                'N' to 5,
                'P' to 7,
                'R' to 9,
                'S' to 2,
                'T' to 3,
                'U' to 4,
                'V' to 5,
                'W' to 6,
                'X' to 7,
                'Y' to 8,
                'Z' to 9,
                '1' to 1,
                '2' to 2,
                '3' to 3,
                '4' to 4,
                '5' to 5,
                '6' to 6,
                '7' to 7,
                '8' to 8,
                '9' to 9,
                '0' to 0,
            )

        // Weight factors for each position in VIN
        private val WEIGHTS = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)

        // Corrections for characters that are invalid in a VIN
        // Maps invalid/confusable characters to valid VIN-allowed characters
        private val OCR_CORRECTIONS =
            mapOf<Char, Char>(
                // Invalid VIN characters → similar valid ones
                'I' to '1',
                'i' to '1', // I looks like 1
                'O' to '0',
                'o' to '0', // O looks like 0
                'Q' to '0',
                'q' to '0', // Q looks like 0
                // Lowercase letters → uppercase equivalents
                'a' to 'A',
                'b' to 'B',
                'c' to 'C',
                'd' to 'D',
                'e' to 'E',
                'f' to 'F',
                'g' to 'G',
                'h' to 'H',
                'j' to 'J',
                'k' to 'K',
                'l' to '1', // lowercase L looks like 1
                'm' to 'M',
                'n' to 'N',
                'p' to 'P',
                'r' to 'R',
                's' to 'S',
                't' to 'T',
                'u' to 'U',
                'v' to 'V',
                'w' to 'W',
                'x' to 'X',
                'y' to 'Y',
                'z' to 'Z',
                // Common OCR confusions
                '|' to '1', // pipe to 1
                '!' to '1', // exclamation to 1
                'Ø' to '0', // slashed O to 0
                '°' to '0', // degree symbol to 0
                // Note: Special characters like dashes, spaces, dots are filtered by extractVin()
            )

        private val VIN_LABEL_PREFIX = Regex("""(?i)^\s*V[I1]N\s*:\s+""")
        private val VIN_BODY_PREFIX = Regex("""(?i)^V[I1]N\s*:\s+""")
        private val VIN_PATTERN = Regex("""[A-HJ-NPR-Z0-9]{17}""")
    }

    override fun validate(vin: String): VinValidationResult {
        SLog.d(TAG, "Validating text: $vin")
        // Strip leading label before applying OCR corrections to avoid turning "VIN" into "V1N"
        val withoutLabel = stripLeadingVinLabel(vin)
        val correctedVin = correctOcrErrors(withoutLabel)
        val (extractedVin, wasTrimmed) = extractVin(correctedVin)

        if (extractedVin == null) {
            val result =
                VinValidationResult(
                    isValid = false,
                    errorMessage = getString(R.string.validation_invalid_chars_or_no_valid_vin, emptyArray()),
                    formatValid = false,
                    wasTrimmed = wasTrimmed,
                )
            SLog.d(TAG, "Validation result for '$vin': $result")
            return result
        }

        SLog.d(TAG, "Extracted VIN: $extractedVin (wasTrimmed: $wasTrimmed)")

        // 1. Check length
        if (extractedVin.length != VinNumber.VIN_LENGTH) {
            val result =
                VinValidationResult(
                    isValid = false,
                    errorMessage =
                        getString(
                            R.string.validation_wrong_length,
                            arrayOf(extractedVin.length),
                        ),
                    formatValid = false,
                    wasTrimmed = wasTrimmed,
                )
            SLog.d(TAG, "Validation result for '$vin': $result")
            return result
        }

        // 2. Check for invalid characters (I, O, Q)
        val hasInvalidChars = extractedVin.any { it in VinNumber.INVALID_CHARACTERS }
        if (hasInvalidChars) {
            val result =
                VinValidationResult(
                    isValid = false,
                    errorMessage = getString(R.string.validation_contains_invalid_chars, emptyArray()),
                    formatValid = false,
                    wasTrimmed = wasTrimmed,
                )
            SLog.d(TAG, "Validation result for '$vin': $result")
            return result
        }

        // 3. Basic numeric heuristic – VINs typically contain several digits
        val digitCount = extractedVin.count { it.isDigit() }
        if (digitCount < 5) {
            val result =
                VinValidationResult(
                    isValid = false,
                    errorMessage = getString(R.string.validation_insufficient_digits, emptyArray()),
                    formatValid = false,
                    wasTrimmed = wasTrimmed,
                )
            SLog.d(TAG, "Validation result for '$vin': $result")
            return result
        }

        // 4. Validate checksum on the extracted VIN as-is (ambiguous-character permutation
        // recovery is disabled to avoid resolving to a VIN that differs from the true plate).
        if (validateChecksum(extractedVin)) {
            val result =
                VinValidationResult(
                    isValid = true,
                    checksumValid = true,
                    formatValid = true,
                    wasTrimmed = wasTrimmed,
                    correctedVin = extractedVin,
                )
            SLog.d(TAG, "Validation result for '$vin': $result")
            return result
        }

        // Checksum failed, but we allow it (soft validation)
        SLog.w(TAG, "Checksum validation failed for '$extractedVin', but accepting as valid format.")
        val result =
            VinValidationResult(
                isValid = true, // Relaxed validation: Accept even if checksum fails
                errorMessage = getString(R.string.validation_checksum_accepted, emptyArray()),
                checksumValid = false,
                formatValid = true,
                wasTrimmed = wasTrimmed,
            )

        SLog.d(TAG, "Validation result for '$vin': $result")
        return result
    }

    override fun cleanVin(vin: String): String {
        val withoutLabel = stripLeadingVinLabel(vin)
        val correctedVin = correctOcrErrors(withoutLabel)
        val (extractedVin, _) = extractVin(correctedVin)
        return extractedVin ?: ""
    }

    private fun stripLeadingVinLabel(text: String): String = text.replaceFirst(VIN_LABEL_PREFIX, "")

    private fun correctOcrErrors(text: String): String = text.map { OCR_CORRECTIONS[it] ?: it }.joinToString("")

    private fun extractVin(text: String): Pair<String?, Boolean> {
        val normalized =
            text
                .trim()
                .uppercase()
                .replaceFirst(VIN_BODY_PREFIX, "")

        val trimmedStart = normalized.dropWhile { it !in 'A'..'Z' && it !in '0'..'9' }
        val trimmedBoth = trimmedStart.dropLastWhile { it !in 'A'..'Z' && it !in '0'..'9' }
        val wasTrimmed = normalized != trimmedBoth

        // Try regex directly — handles trailing OCR noise like "\nMPY" because the pattern
        // requires 17 consecutive valid VIN chars, so whitespace/newlines act as natural breaks.
        val match = VIN_PATTERN.find(trimmedBoth)
        if (match != null) {
            return Pair(match.value, wasTrimmed || match.value != trimmedBoth)
        }

        // Try again with whitespace collapsed — handles OCR-inserted spaces inside the VIN
        // e.g. "1FMSKBBB0MGC2 1557" → "1FMSKBBB0MGC21557"
        val noWhitespace = trimmedBoth.filter { !it.isWhitespace() }
        if (noWhitespace.length == VinNumber.VIN_LENGTH) {
            val matchNoWs = VIN_PATTERN.find(noWhitespace)
            if (matchNoWs != null) {
                return Pair(matchNoWs.value, true)
            }
        }

        SLog.w(TAG, "No valid 17-char VIN sequence found in: $trimmedBoth")
        return Pair(null, wasTrimmed)
    }

    private fun validateChecksum(vin: String): Boolean {
        SLog.d(TAG, "Validating checksum for VIN: $vin")

        var sum = 0
        for (i in vin.indices) {
            if (i == 8) continue // Skip the check digit position

            val char = vin[i]
            val value =
                TRANSLITERATION[char]
                    ?: return false.also { SLog.e(TAG, "Invalid character in VIN for checksum: $char") }

            val weight = WEIGHTS[i]
            sum += value * weight
        }

        val remainder = sum % 11
        val checkDigit = vin[8]
        val expectedDigit = if (remainder == 10) 'X' else Character.forDigit(remainder, 10)

        val isValid = checkDigit == expectedDigit
        if (!isValid) {
            SLog.w(TAG, "Checksum validation failed for '$vin'. Expected: $expectedDigit, Found: $checkDigit")
        }
        return isValid
    }

}
