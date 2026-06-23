package com.syarah.vinscanner.data.datasource.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.syarah.vinscanner.domain.model.BoundingBox
import kotlinx.coroutines.flow.Flow

/**
 * Data source interface for camera operations
 */
internal interface CameraDataSource {
    /**
     * Starts camera preview and image analysis
     * @return Flow of captured image frames
     */
    fun startCamera(): Flow<ImageProxy>
    
    /**
     * Stops camera preview and analysis
     */
    fun stopCamera()
    
    /**
     * Converts ImageProxy to Bitmap
     * @param imageProxy The image from camera
     * @param cropRegion optional normalized crop in the rotation-corrected output space
     * @return rotation-corrected bitmap containing only [cropRegion], when supplied
     */
    fun imageToBitmap(imageProxy: ImageProxy, cropRegion: BoundingBox? = null): Bitmap
}
