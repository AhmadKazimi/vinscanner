package com.kazimi.syaravin.domain.usecase

import android.graphics.Bitmap
import com.kazimi.syaravin.domain.model.BoundingBox
import com.kazimi.syaravin.domain.repository.VinScannerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Use case for detecting VIN regions in images
 */
internal class DetectVinUseCase(
    private val repository: VinScannerRepository
) {
    /**
     * Executes the use case to detect VIN regions
     * @param bitmap The image to analyze
     * @return List of bounding boxes for detected VIN regions
     */
    suspend operator fun invoke(bitmap: Bitmap): List<BoundingBox> = withContext(Dispatchers.Default) {
        repository.detectVinRegions(bitmap)
    }
}