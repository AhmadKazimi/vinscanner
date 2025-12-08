package com.syarah.vinscanner.data.datasource.validator

import android.util.Log
import com.syarah.vinscanner.data.model.VinValidationResult
import com.syarah.vinscanner.domain.model.VinNumber

private const val TAG = "VinValidatorImpl"

/**
 * Implementation of VinValidator with standard VIN validation rules
 */
internal class VinValidatorImpl : VinValidator {

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

        // Corrections for characters that are invalid in a VIN
        private val OCR_CORRECTIONS = mapOf(
            'I' to '1', 'i' to '1',
            'O' to '0', 'o' to '0',
            'Q' to '0', 'q' to '0'
        )
        
        // Ambiguous characters that can be misread by OCR
        private val AMBIGUOUS_CHARS = mapOf(
            'S' to '5', '5' to 'S',
            'Z' to '2', '2' to 'Z',
            'B' to '8', '8' to 'B',
            'A' to '4', '4' to 'A',
            'G' to '6', '6' to 'G'
        )
    }

    override fun validate(vin: String): VinValidationResult {
        Log.d(TAG, "Validating text: $vin")
        // Strip leading label before applying OCR corrections to avoid turning "VIN" into "V1N"
        val withoutLabel = stripLeadingVinLabel(vin)
        val correctedVin = correctOcrErrors(withoutLabel)
        val (extractedVin, wasTrimmed) = extractVin(correctedVin)

        if (extractedVin == null) {
            val result = VinValidationResult(
                isValid = false,
                errorMessage = "Invalid characters found in middle of VIN or no valid 17-character VIN found.",
                formatValid = false,
                wasTrimmed = wasTrimmed
            )
            Log.d(TAG, "Validation result for '$vin': $result")
            return result
        }

        Log.d(TAG, "Extracted VIN: $extractedVin (wasTrimmed: $wasTrimmed)")


        // 1. Check length
        if (extractedVin.length != VinNumber.VIN_LENGTH) {
            val result = VinValidationResult(
                isValid = false,
                errorMessage = "VIN must be 17 characters long, but was ${extractedVin.length}",
                formatValid = false,
                wasTrimmed = wasTrimmed
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
                formatValid = false,
                wasTrimmed = wasTrimmed
            )
            Log.d(TAG, "Validation result for '$vin': $result")
            return result
        }

        // 3. Basic numeric heuristic – VINs typically contain several digits
        val digitCount = extractedVin.count { it.isDigit() }
        if (digitCount < 5) {
            val result = VinValidationResult(
                isValid = false,
                errorMessage = "VIN likely invalid (insufficient digits)",
                formatValid = false,
                wasTrimmed = wasTrimmed
            )
            Log.d(TAG, "Validation result for '$vin': $result")
            return result
        }


        // 4. Validate Checksum, trying permutations for ambiguous characters (bounded)
        if (validateChecksumWithPermutations(extractedVin)) {
            val result = VinValidationResult(
                isValid = true,
                checksumValid = true,
                formatValid = true,
                wasTrimmed = wasTrimmed
            )
            Log.d(TAG, "Validation result for '$vin': $result")
            return result
        }


        // Checksum failed, but we allow it (soft validation)
        Log.w(TAG, "Checksum validation failed for '$extractedVin', but accepting as valid format.")
        val result = VinValidationResult(
            isValid = true, // Relaxed validation: Accept even if checksum fails
            errorMessage = "Invalid VIN checksum (accepted)",
            checksumValid = false,
            formatValid = true,
            wasTrimmed = wasTrimmed
        )


        Log.d(TAG, "Validation result for '$vin': $result")
        return result
    }

    override fun cleanVin(vin: String): String {
        val withoutLabel = stripLeadingVinLabel(vin)
        val correctedVin = correctOcrErrors(withoutLabel)
        val (extractedVin, _) = extractVin(correctedVin)
        return extractedVin ?: ""
    }
    private fun stripLeadingVinLabel(text: String): String {
        // Remove optional leading label like "VIN:", "vin - ", "VIN no", "VIN#", etc. (case-insensitive)
        val pattern = Regex("(?i)^\\s*VIN(?:\\s*(?:NUMBER|NO|#))?\\s*[:#=\\u2013\\u2014\\-]?\\s*")
        return text.replaceFirst(pattern, "")
    }


    private fun correctOcrErrors(text: String): String {
        return text.map { OCR_CORRECTIONS[it] ?: it }.joinToString("")
    }

    private fun extractVin(text: String): Pair<String?, Boolean> {
        // Normalize input and remove an optional leading "VIN" label with separators (e.g., "VIN:", "Vin - ", etc.)
        val normalized = text.trim().uppercase()
            .replaceFirst(Regex("^VIN\\s*[:#=\u2013\u2014\\-]?\\s*"), "")

        // Trim invalid characters from START
        val trimmedStart = normalized.dropWhile { it !in 'A'..'Z' && it !in '0'..'9' }

        // Trim invalid characters from END
        val trimmedBoth = trimmedStart.dropLastWhile { it !in 'A'..'Z' && it !in '0'..'9' }

        // Track if trimming occurred
        val wasTrimmed = (normalized != trimmedBoth)

        // Check if there are invalid characters IN THE MIDDLE (after trimming start/end)
        val hasMiddleInvalidChars = trimmedBoth.any {
            it !in 'A'..'Z' && it !in '0'..'9'
        }

        if (hasMiddleInvalidChars) {
            // Invalid! Characters like "ERA:PPSNAE..." have invalid chars in middle
            Log.w(TAG, "Invalid characters found in middle of VIN: $trimmedBoth")
            return Pair(null, wasTrimmed)
        }

        // Now we have a clean alphanumeric string, find 17-char VIN (exclude I, O, Q)
        val vinRegex = Regex("[A-HJ-NPR-Z0-9]{17}")
        val match = vinRegex.find(trimmedBoth)

        return Pair(match?.value, wasTrimmed)
    }


    private fun validateChecksum(vin: String): Boolean {
        Log.d(TAG, "Validating checksum for VIN: $vin")

        var sum = 0
        for (i in vin.indices) {
            if (i == 8) continue // Skip the check digit position

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

    private fun validateChecksumWithPermutations(vin: String): Boolean {
        // Prefer the original string; allow at most 1 ambiguous substitution
        val maxChanges = 1
        data class Node(val value: String, val changes: Int)

        val seenVins = mutableSetOf<String>()
        val queue = ArrayDeque<Node>()

        seenVins.add(vin)
        queue.addLast(Node(vin, 0))

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val currentVin = node.value

            if (validateChecksum(currentVin)) {
                Log.i(TAG, "Valid checksum found for permutation: $currentVin")
                return true
            }

            if (node.changes >= maxChanges) continue

            // Generate next level of permutations (single-position swaps only)
            for (i in currentVin.indices) {
                val char = currentVin[i]
                val swappedChar = AMBIGUOUS_CHARS[char]
                if (swappedChar != null) {
                    val nextVin = currentVin.substring(0, i) + swappedChar + currentVin.substring(i + 1)
                    if (seenVins.add(nextVin)) {
                        queue.addLast(Node(nextVin, node.changes + 1))
                    }
                }
            }
        }
        return false
    }
}
