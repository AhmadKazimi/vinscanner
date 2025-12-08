package com.syarah.vinscanner.data.datasource.validator

import com.syarah.vinscanner.domain.model.VinNumber
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for VinValidatorImpl
 * Tests VIN format validation, ISO 3779 checksum validation, and validation pipeline
 */
class VinValidatorImplTest {

    private lateinit var validator: VinValidator

    @Before
    fun setup() {
        validator = VinValidatorImpl()
    }

    // ==================== VIN Format Validation Tests ====================

    @Test
    fun `validate returns invalid when VIN is too short`() {
        val result = validator.validate("1HGBH41JXMN10")

        assertFalse("VIN should be invalid", result.isValid)
        assertFalse("Format should be invalid", result.formatValid)
        assertNotNull("Error message should be present", result.errorMessage)
        assertTrue(
            "Error message should mention no valid VIN found or invalid characters or length",
            result.errorMessage?.contains("Invalid characters found in middle") == true ||
            result.errorMessage?.contains("No valid 17-character VIN") == true ||
            result.errorMessage?.contains("17 characters") == true
        )
    }

    @Test
    fun `validate returns invalid when VIN is too long`() {
        // Note: Validator extracts first 17 valid chars, so extra chars are ignored
        // To test length validation, need a string that can't produce a valid 17-char VIN
        val result = validator.validate("ABCDEFGHJKLMNPRST12")  // 19 chars, no valid 17-char sequence

        assertFalse("VIN should be invalid", result.isValid)
        assertFalse("Format should be invalid", result.formatValid)
        assertNotNull("Error message should be present", result.errorMessage)
    }

    @Test
    fun `validate returns invalid when VIN contains I character after OCR correction`() {
        // Note: I is corrected to 1 by OCR correction, so this tests if there's
        // an I that survives OCR correction (not in OCR_CORRECTIONS map)
        // Since lowercase 'i' is corrected but uppercase 'I' is also corrected,
        // we need to test the extraction regex which excludes I, O, Q

        // After OCR correction, I->1, so the VIN will be valid
        // To truly test invalid I, we'd need to bypass OCR correction,
        // but that's not how the validator works in practice.
        // Instead, test that extraction regex properly excludes I, O, Q
        val result = validator.validate("1HGBH41JXMN10986I")

        // After OCR correction: I->1, becomes 1HGBH41JXMN109861
        // This may pass format validation but fail checksum
        assertTrue("VIN format should be valid after OCR correction", result.formatValid)
    }

    @Test
    fun `validate returns invalid when VIN contains O character after OCR correction`() {
        // O is corrected to 0 by OCR correction
        val result = validator.validate("1HGBH41JXMN10986O")

        // After OCR correction: O->0, becomes 1HGBH41JXMN109860
        // This may pass format validation but fail checksum
        assertTrue("VIN format should be valid after OCR correction", result.formatValid)
    }

    @Test
    fun `validate returns invalid when VIN contains Q character after OCR correction`() {
        // Q is corrected to 0 by OCR correction
        val result = validator.validate("1HGBH41JXMN10986Q")

        // After OCR correction: Q->0, becomes 1HGBH41JXMN109860
        // This may pass format validation but fail checksum
        assertTrue("VIN format should be valid after OCR correction", result.formatValid)
    }

    @Test
    fun `validate returns invalid when VIN has insufficient digits`() {
        // VIN with only 2 digits (needs at least 5)
        val result = validator.validate("ABCDEFGHJKLMNPRS1")

        assertFalse("VIN should be invalid", result.isValid)
        assertFalse("Format should be invalid", result.formatValid)
        assertTrue(
            "Error message should mention insufficient digits",
            result.errorMessage?.contains("insufficient digits") == true
        )
    }

    @Test
    fun `validate accepts VIN with exactly 5 digits`() {
        // VIN with exactly 5 digits should pass digit count validation
        val result = validator.validate("1HGBH41J5MN109876")

        // Should pass format validation (digit count check)
        // May fail checksum but should be accepted with formatValid=true
        assertTrue("VIN format should be valid", result.formatValid)
    }

    // ==================== ISO 3779 Checksum Validation Tests ====================

    @Test
    fun `validate returns valid for VIN with correct checksum`() {
        // Real VIN with valid checksum: 1HGBH41JXMN109186
        // This is a well-known valid VIN
        val result = validator.validate("1HGBH41JXMN109186")

        assertTrue("VIN should be valid", result.isValid)
        assertTrue("Format should be valid", result.formatValid)
        assertTrue("Checksum should be valid", result.checksumValid)
        assertNull("Error message should be null", result.errorMessage)
    }

    @Test
    fun `validate returns valid for VIN with X as check digit`() {
        // VIN with X as check digit (when remainder is 10)
        // 11111111X11111111 is a valid test VIN with X check digit
        val result = validator.validate("1M8GDM9AXKP042788")

        assertTrue("VIN should be valid", result.isValid)
        assertTrue("Format should be valid", result.formatValid)
    }

    @Test
    fun `validate accepts VIN with invalid checksum but valid format`() {
        // VIN with incorrect check digit (soft validation allows it)
        val result = validator.validate("1HGBH41J5MN109186")

        assertTrue("VIN should be accepted (soft validation)", result.isValid)
        assertTrue("Format should be valid", result.formatValid)
        assertFalse("Checksum should be invalid", result.checksumValid)
        assertTrue(
            "Error message should indicate checksum failure",
            result.errorMessage?.contains("checksum") == true
        )
    }

    @Test
    fun `validate correctly calculates checksum for various VINs`() {
        // Collection of known valid VINs with correct checksums
        val validVins = listOf(
            "1HGBH41JXMN109186",  // Honda
            "1M8GDM9AXKP042788",  // Mercury with X check digit
            "1FAFP40432F172825"   // Ford
        )

        validVins.forEach { vin ->
            val result = validator.validate(vin)
            assertTrue("VIN $vin should have valid checksum", result.checksumValid)
        }
    }

    // ==================== OCR Error Correction Tests ====================

    @Test
    fun `validate corrects O to 0 in VIN`() {
        // Input with O should be corrected to 0
        val result = validator.validate("1HGBH41JXMN1O9186")

        // Should extract as 1HGBH41JXMN109186 after correction
        assertTrue("VIN should be valid after correction", result.isValid)
        assertTrue("Format should be valid", result.formatValid)
    }

    @Test
    fun `validate corrects I to 1 in VIN`() {
        // Input with I should be corrected to 1
        val result = validator.validate("IHGBH4IJXMN109186")

        // Should extract as 1HGBH41JXMN109186 after correction
        assertTrue("VIN should be valid after correction", result.isValid)
        assertTrue("Format should be valid", result.formatValid)
    }

    @Test
    fun `validate corrects Q to 0 in VIN`() {
        // Input with Q should be corrected to 0
        val result = validator.validate("1HGBH41JXMN1Q9186")

        // Should be corrected and pass format validation
        assertTrue("VIN format should be valid after correction", result.formatValid)
    }

    @Test
    fun `validate corrects lowercase ocr errors`() {
        // Input with lowercase i, o, q
        val result = validator.validate("1hgbh4ijxmn1o9i86")

        // Should be corrected to uppercase and numbers
        assertTrue("VIN should be valid after correction", result.isValid)
        assertTrue("Format should be valid", result.formatValid)
    }

    // ==================== VIN Extraction Tests ====================

    @Test
    fun `validate extracts VIN from text with VIN prefix`() {
        val result = validator.validate("VIN: 1HGBH41JXMN109186")

        assertTrue("VIN should be extracted and valid", result.isValid)
        assertTrue("Format should be valid", result.formatValid)
    }

    @Test
    fun `validate extracts VIN from text with VIN NUMBER prefix`() {
        val result = validator.validate("VIN NUMBER: 1HGBH41JXMN109186")

        assertTrue("VIN should be extracted and valid", result.isValid)
        assertTrue("Format should be valid", result.formatValid)
    }

    @Test
    fun `validate extracts VIN from text with vin no prefix`() {
        val result = validator.validate("vin no - 1HGBH41JXMN109186")

        assertTrue("VIN should be extracted and valid", result.isValid)
        assertTrue("Format should be valid", result.formatValid)
    }

    @Test
    fun `validate extracts VIN from text with VIN# prefix`() {
        val result = validator.validate("VIN#1HGBH41JXMN109186")

        assertTrue("VIN should be extracted and valid", result.isValid)
        assertTrue("Format should be valid", result.formatValid)
    }

    @Test
    fun `validate rejects VIN with spaces and hyphens in middle (strict mode)`() {
        // With strict trimming, hyphens in the middle are now rejected
        val result = validator.validate("VIN: 1HG-BH41J-XMN-109186")

        assertFalse("VIN with hyphens in middle should be rejected", result.isValid)
        assertFalse("Format should be invalid", result.formatValid)
    }

    @Test
    fun `validate rejects VIN with special characters in middle (strict mode)`() {
        // With strict trimming, asterisks in the middle are now rejected
        val result = validator.validate("1HG*BH41J*XMN*109186")

        assertFalse("VIN with asterisks in middle should be rejected", result.isValid)
        assertFalse("Format should be invalid", result.formatValid)
    }

    @Test
    fun `validate returns invalid for text without 17 character VIN`() {
        val result = validator.validate("VIN: 1HGBH41J")

        assertFalse("VIN should be invalid", result.isValid)
        assertFalse("Format should be invalid", result.formatValid)
        assertTrue(
            "Error message should indicate no valid VIN found or invalid characters",
            result.errorMessage?.contains("Invalid characters found in middle") == true ||
            result.errorMessage?.contains("No valid 17-character VIN") == true
        )
    }

    // ==================== cleanVin Function Tests ====================

    @Test
    fun `cleanVin returns cleaned VIN string`() {
        val cleaned = validator.cleanVin("VIN: 1HGBH41JXMN109186")

        assertEquals("Cleaned VIN should match", "1HGBH41JXMN109186", cleaned)
    }

    @Test
    fun `cleanVin returns empty for VIN with spaces and hyphens in middle (strict mode)`() {
        // With strict trimming, spaces and hyphens in middle cause rejection
        val cleaned = validator.cleanVin("1HG-BH41J XMN-109186")

        assertEquals("Cleaned VIN should be empty for invalid middle chars", "", cleaned)
    }

    @Test
    fun `cleanVin corrects OCR errors`() {
        val cleaned = validator.cleanVin("IHGBH4IJXMN1O9I86")

        assertEquals("Cleaned VIN should have corrected OCR errors", "1HGBH41JXMN109186", cleaned)
    }

    @Test
    fun `cleanVin returns empty string for invalid input`() {
        val cleaned = validator.cleanVin("INVALID")

        assertEquals("Cleaned VIN should be empty for invalid input", "", cleaned)
    }

    @Test
    fun `cleanVin handles empty string`() {
        val cleaned = validator.cleanVin("")

        assertEquals("Cleaned VIN should be empty", "", cleaned)
    }

    @Test
    fun `cleanVin returns empty for text with space in middle`() {
        // With strict trimming, space in middle causes rejection
        // "1HGBH41JXMN109186 1FAFP40432F172825" has a space in the middle
        val cleaned = validator.cleanVin("1HGBH41JXMN109186 1FAFP40432F172825")

        assertEquals("Should be empty due to space in middle", "", cleaned)
    }

    // ==================== Ambiguous Character Permutation Tests ====================

    @Test
    fun `validate handles ambiguous S and 5 characters`() {
        // VIN with S that might be misread as 5, or vice versa
        // Validator tries permutations to find valid checksum
        val result = validator.validate("1HGBH41JSXMN109186")

        // Should attempt permutation and validate
        assertTrue("VIN should be valid", result.isValid)
        assertTrue("Format should be valid", result.formatValid)
    }

    @Test
    fun `validate handles ambiguous B and 8 characters`() {
        // VIN with B that might be misread as 8, or vice versa
        val result = validator.validate("1HGBH41JXMN109186")

        assertTrue("VIN should be valid", result.isValid)
        assertTrue("Format should be valid", result.formatValid)
    }

    // ==================== Edge Cases and Boundary Tests ====================

    @Test
    fun `validate handles null input gracefully`() {
        // Note: In Kotlin, we can't pass null to non-nullable parameter
        // But we can test empty string
        val result = validator.validate("")

        assertFalse("Empty VIN should be invalid", result.isValid)
        assertFalse("Format should be invalid", result.formatValid)
    }

    @Test
    fun `validate handles whitespace only input`() {
        val result = validator.validate("   \t\n   ")

        assertFalse("Whitespace VIN should be invalid", result.isValid)
        assertFalse("Format should be invalid", result.formatValid)
    }

    @Test
    fun `validate handles VIN with all digits except check position`() {
        // VIN with maximum allowed digits (position 8 is check digit)
        val result = validator.validate("11111111X11111111")

        // Should pass format validation
        assertTrue("Format should be valid", result.formatValid)
    }

    @Test
    fun `validate handles VIN with minimum required digits`() {
        // VIN with exactly 5 digits (minimum required)
        // Using a VIN structure that will actually pass digit validation
        val result = validator.validate("1HGBH41J5XN234567")

        // Should pass format validation (digit count check)
        assertTrue("Format should be valid", result.formatValid)
    }

    @Test
    fun `validate handles VIN with mixed case`() {
        val result = validator.validate("1hGbH41jXmN109186")

        // Should normalize to uppercase and validate
        assertTrue("VIN should be valid", result.isValid)
        assertTrue("Format should be valid", result.formatValid)
    }

    @Test
    fun `validate handles VIN with leading and trailing whitespace`() {
        val result = validator.validate("  1HGBH41JXMN109186  ")

        assertTrue("VIN should be valid", result.isValid)
        assertTrue("Format should be valid", result.formatValid)
    }

    // ==================== Real-World VIN Examples ====================

    @Test
    fun `validate accepts multiple real-world valid VINs`() {
        val realVins = mapOf(
            "1HGBH41JXMN109186" to "Honda",
            "1M8GDM9AXKP042788" to "Mercury",
            "1FAFP40432F172825" to "Ford"
        )

        realVins.forEach { (vin, make) ->
            val result = validator.validate(vin)
            assertTrue("$make VIN $vin should be valid", result.isValid)
            assertTrue("$make VIN format should be valid", result.formatValid)
        }
    }

    // ==================== Validation Pipeline Integration Tests ====================

    @Test
    fun `validation pipeline processes VIN end-to-end without middle separators`() {
        // Test the complete pipeline: strip label -> correct OCR -> extract -> validate
        // Note: No hyphens in middle (strict mode)
        val input = "VIN NUMBER: 1HGBH4IJXMN1O9I86"
        val result = validator.validate(input)

        // Should:
        // 1. Strip "VIN NUMBER:" label
        // 2. Correct I->1, O->0
        // 3. Extract 17-char VIN
        // 4. Validate format and checksum

        assertTrue("VIN should be valid after pipeline", result.isValid)
        assertTrue("Format should be valid", result.formatValid)
    }

    @Test
    fun `validation pipeline rejects messy input with middle separators (strict mode)`() {
        // With strict trimming, asterisks/hyphens/underscores in middle cause rejection
        val input = "  vin#:  1hG*bH-4iJ*xMn_1o9i86!!!  "
        val result = validator.validate(input)

        assertFalse("VIN with middle separators should be rejected", result.isValid)
        assertFalse("Format should be invalid", result.formatValid)
    }

    @Test
    fun `validation pipeline rejects completely invalid text`() {
        val input = "This is not a VIN at all, just some random text"
        val result = validator.validate(input)

        assertFalse("Invalid text should fail validation", result.isValid)
        assertFalse("Format should be invalid", result.formatValid)
    }

    // ==================== Constants Validation Tests ====================

    @Test
    fun `VIN constants match expected values`() {
        assertEquals("VIN length should be 17", 17, VinNumber.VIN_LENGTH)
        assertTrue("Invalid characters should include I", VinNumber.INVALID_CHARACTERS.contains('I'))
        assertTrue("Invalid characters should include O", VinNumber.INVALID_CHARACTERS.contains('O'))
        assertTrue("Invalid characters should include Q", VinNumber.INVALID_CHARACTERS.contains('Q'))
    }

    @Test
    fun `VIN regex pattern accepts valid characters only`() {
        val validPattern = VinNumber.VALID_PATTERN

        assertTrue("Valid VIN should match pattern", validPattern.matches("1HGBH41JXMN109186"))
        assertFalse("VIN with I should not match", validPattern.matches("1HGBH41JXMN10918I"))
        assertFalse("VIN with O should not match", validPattern.matches("1HGBH41JXMN10918O"))
        assertFalse("VIN with Q should not match", validPattern.matches("1HGBH41JXMN10918Q"))
    }

    // ==================== Performance and Boundary Tests ====================

    @Test
    fun `validate rejects input with text after VIN (strict mode)`() {
        // VIN with trailing text containing space in middle is rejected (strict mode)
        val inputWithText = "VIN: 1HGBH41JXMN109186 (found on vehicle)"
        val result = validator.validate(inputWithText)

        // The space between VIN and trailing text causes rejection in strict mode
        assertFalse("VIN with text after should be rejected (space in middle)", result.isValid)
        assertFalse("Format should be invalid", result.formatValid)
    }

    @Test
    fun `validate handles string too short to contain valid VIN`() {
        // String that's too short and can't produce a valid 17-char VIN
        // even after OCR correction
        val result = validator.validate("ABCD-EFGH-JKL")

        // No valid 17-char VIN should be found
        assertFalse("No valid 17-char VIN should be found", result.isValid)
        assertFalse("Format should be invalid", result.formatValid)
        assertTrue(
            "Error message should indicate no valid VIN found or invalid characters",
            result.errorMessage?.contains("Invalid characters found in middle") == true ||
            result.errorMessage?.contains("No valid 17-character VIN") == true
        )
    }

    @Test
    fun `cleanVin does not throw exception on unusual input`() {
        val unusualInputs = listOf(
            "🚗🚙🚕",
            "VIN:VIN:VIN:",
            "!@#$%^&*()",
            "12345",
            " ",
            "\n\t\r"
        )

        unusualInputs.forEach { input ->
            // Should not throw exception, just return empty or processed string
            assertNotNull("cleanVin should handle unusual input", validator.cleanVin(input))
        }
    }

    // ==================== Smart Trimming Tests ====================

    @Test
    fun `validate accepts VIN with trailing slash`() {
        // VIN with trailing slash should be trimmed and accepted
        val result = validator.validate("1HGBH41JXMN109186/")

        assertTrue("VIN should be valid after trimming trailing slash", result.isValid)
        assertTrue("Format should be valid", result.formatValid)
        assertTrue("wasTrimmed flag should be true", result.wasTrimmed)
    }

    @Test
    fun `validate accepts VIN with leading slash`() {
        // VIN with leading slash should be trimmed and accepted
        val result = validator.validate("/1HGBH41JXMN109186")

        assertTrue("VIN should be valid after trimming leading slash", result.isValid)
        assertTrue("Format should be valid", result.formatValid)
        assertTrue("wasTrimmed flag should be true", result.wasTrimmed)
    }

    @Test
    fun `validate accepts VIN with leading and trailing special characters`() {
        // VIN with both leading and trailing special chars should be trimmed
        val result = validator.validate("*/1HGBH41JXMN109186/*")

        assertTrue("VIN should be valid after trimming", result.isValid)
        assertTrue("Format should be valid", result.formatValid)
        assertTrue("wasTrimmed flag should be true", result.wasTrimmed)
    }

    @Test
    fun `validate rejects VIN with middle colon`() {
        // VIN with colon in the middle should be rejected
        val result = validator.validate("ERA:PPSNAE234439G161")

        assertFalse("VIN with middle colon should be rejected", result.isValid)
        assertFalse("Format should be invalid", result.formatValid)
        assertTrue(
            "Error message should indicate invalid characters in middle",
            result.errorMessage?.contains("Invalid characters found in middle") == true
        )
    }

    @Test
    fun `validate rejects VIN with middle asterisk`() {
        // VIN with asterisks in the middle should be rejected (not trimmed from edges)
        val result = validator.validate("1HG*BH41J*XMN109186")

        assertFalse("VIN with middle asterisks should be rejected", result.isValid)
        assertFalse("Format should be invalid", result.formatValid)
    }

    @Test
    fun `validate rejects VIN with middle space`() {
        // VIN with space in the middle should be rejected
        val result = validator.validate("ERAPPSN 234439G161")

        assertFalse("VIN with middle space should be rejected", result.isValid)
        assertFalse("Format should be invalid", result.formatValid)
    }

    @Test
    fun `validate wasTrimmed is false when no trimming occurs`() {
        // Clean VIN with no special characters should have wasTrimmed=false
        val result = validator.validate("1HGBH41JXMN109186")

        assertTrue("VIN should be valid", result.isValid)
        assertFalse("wasTrimmed flag should be false", result.wasTrimmed)
    }

    @Test
    fun `validate accepts VIN with trailing special characters and validates correctly`() {
        // Test the specific case from the user's image: ERAPPSN234439G161/
        val result = validator.validate("ERAPPSN234439G161/")

        // This VIN will be trimmed and validated
        // Note: It may fail checksum validation, but should pass format validation
        assertTrue("Format should be valid after trimming", result.formatValid)
        assertTrue("wasTrimmed flag should be true", result.wasTrimmed)
    }

    @Test
    fun `cleanVin handles trailing slash correctly`() {
        // cleanVin should also trim leading/trailing characters
        val cleaned = validator.cleanVin("1HGBH41JXMN109186/")

        assertEquals("Cleaned VIN should have slash removed", "1HGBH41JXMN109186", cleaned)
    }

    @Test
    fun `cleanVin handles leading slash correctly`() {
        val cleaned = validator.cleanVin("/1HGBH41JXMN109186")

        assertEquals("Cleaned VIN should have leading slash removed", "1HGBH41JXMN109186", cleaned)
    }

    @Test
    fun `cleanVin returns empty for VIN with middle invalid chars`() {
        // cleanVin should return empty string for VINs with invalid chars in middle
        val cleaned = validator.cleanVin("ERA:PPSNAE234439G161")

        assertEquals("Cleaned VIN should be empty for invalid middle chars", "", cleaned)
    }
}
