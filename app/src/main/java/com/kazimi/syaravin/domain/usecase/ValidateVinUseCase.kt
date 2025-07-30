package com.kazimi.syaravin.domain.usecase

import com.kazimi.syaravin.domain.model.VinNumber
import com.kazimi.syaravin.domain.repository.VinScannerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Use case for validating VIN numbers
 */
class ValidateVinUseCase(
    private val repository: VinScannerRepository
) {
    /**
     * Executes the use case to validate a VIN
     * @param vin The VIN string to validate
     * @return VinNumber with validation result
     */
    suspend operator fun invoke(vin: String): VinNumber = withContext(Dispatchers.Default) {
        repository.validateVin(vin)
    }
}