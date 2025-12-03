package com.syarah.vinscanner.domain.repository

import android.graphics.Bitmap
import com.syarah.vinscanner.domain.model.BoundingBox
import com.syarah.vinscanner.domain.model.VinNumber
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for VIN scanning operations
 */
internal interface VinScannerRepository {
    /**
     * Detects VIN regions in an image
     * @param bitmap The image to analyze
     * @return List of bounding boxes for detected VIN regions
     */
    suspend fun detectVinRegions(bitmap: Bitmap): List<BoundingBox>
    
    /**
     * Extracts text from a specific region of an image
     * @param bitmap The image to analyze
     * @param boundingBox The region to extract text from
     * @return Extracted text or null if extraction fails
     */
    suspend fun extractTextFromRegion(bitmap: Bitmap, boundingBox: BoundingBox): String?
    
    /**
     * Validates a VIN number
     * @param vin The VIN string to validate
     * @return VinNumber with validation result
     */
    suspend fun validateVin(vin: String): VinNumber
    
    /**
     * Starts continuous VIN scanning
     * @return Flow of detected and validated VIN numbers
     */
    fun startScanning(): Flow<VinNumber>
    
    /**
     * Stops the current scanning session
     */
    fun stopScanning()
}