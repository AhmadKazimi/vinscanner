package com.kazimi.syaravin.domain.usecase

import android.graphics.Bitmap
import com.kazimi.syaravin.domain.model.BoundingBox
import com.kazimi.syaravin.domain.repository.VinScannerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Use case for extracting text from detected regions
 */
internal class ExtractTextUseCase(
    private val repository: VinScannerRepository
) {
    /**
     * Executes the use case to extract text from a region
     * @param bitmap The image to analyze
     * @param boundingBox The region to extract text from
     * @return Extracted text or null if extraction fails
     */
    suspend operator fun invoke(bitmap: Bitmap, boundingBox: BoundingBox): String? = 
        withContext(Dispatchers.Default) {
            repository.extractTextFromRegion(bitmap, boundingBox)
        }
}