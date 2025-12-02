package com.kazimi.syaravin.data.datasource.validator

import com.kazimi.syaravin.data.model.VinValidationResult

/**
 * Interface for VIN validation
 */
internal interface VinValidator {
    /**
     * Validates a VIN number according to standard rules
     * @param vin The VIN string to validate
     * @return VinValidationResult with validation details
     */
    fun validate(vin: String): VinValidationResult
    
    /**
     * Cleans and normalizes a VIN string
     * @param vin The raw VIN string
     * @return Cleaned VIN string
     */
    fun cleanVin(vin: String): String
}