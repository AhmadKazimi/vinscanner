package com.kazimi.syaravin.data.repository

import android.graphics.Bitmap
import com.kazimi.syaravin.data.datasource.camera.CameraDataSource
import com.kazimi.syaravin.data.datasource.ml.TextExtractor
import com.kazimi.syaravin.data.datasource.ml.VinDetector
import com.kazimi.syaravin.data.datasource.validator.VinValidator
import com.kazimi.syaravin.domain.model.BoundingBox
import com.kazimi.syaravin.domain.model.VinNumber
import com.kazimi.syaravin.domain.repository.VinScannerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * Implementation of VinScannerRepository
 */
class VinScannerRepositoryImpl(
    private val cameraDataSource: CameraDataSource,
    private val vinDetector: VinDetector,
    private val textExtractor: TextExtractor,
    private val vinValidator: VinValidator
) : VinScannerRepository {
    
    override suspend fun detectVinRegions(bitmap: Bitmap): List<BoundingBox> {
        return vinDetector.detect(bitmap).boundingBoxes
    }
    
    override suspend fun extractTextFromRegion(bitmap: Bitmap, boundingBox: BoundingBox): String? {
        return textExtractor.extractText(bitmap, boundingBox)
    }
    
    override suspend fun validateVin(vin: String): VinNumber {
        val validationResult = vinValidator.validate(vin)
        return VinNumber(
            value = vinValidator.cleanVin(vin),
            isValid = validationResult.isValid
        )
    }
    
    override fun startScanning(): Flow<VinNumber> = flow {
        cameraDataSource.startCamera()
            .map { imageProxy ->
                try {
                    // Convert ImageProxy to Bitmap
                    val bitmap = cameraDataSource.imageToBitmap(imageProxy)
                    
                    // Detect VIN regions
                    val detections = vinDetector.detect(bitmap)
                    
                    // Process each detection
                    for (boundingBox in detections.boundingBoxes) {
                        // Extract text from the region
                        val extractedText = textExtractor.extractText(bitmap, boundingBox)
                        
                        if (!extractedText.isNullOrEmpty()) {
                            // Clean and validate the extracted text
                            val cleanedVin = vinValidator.cleanVin(extractedText)
                            
                            // Check if it looks like a VIN (basic length check)
                            if (cleanedVin.length in 15..19) {
                                val validationResult = vinValidator.validate(cleanedVin)
                                
                                // Emit the VIN regardless of validation (let UI decide what to do)
                                emit(
                                    VinNumber(
                                        value = cleanedVin,
                                        confidence = boundingBox.confidence,
                                        isValid = validationResult.isValid
                                    )
                                )
                                
                                // If we found a valid VIN, we can stop processing this frame
                                if (validationResult.isValid) {
                                    break
                                }
                            }
                        }
                    }
                    
                    // Clean up
                    bitmap.recycle()
                } catch (e: Exception) {
                    Timber.e(e, "Error processing camera frame")
                } finally {
                    // Always close the image to avoid memory leaks
                    imageProxy.close()
                }
            }
            .collect { /* Flow collected in map above */ }
    }.flowOn(Dispatchers.Default)
    
    override fun stopScanning() {
        cameraDataSource.stopCamera()
    }
}