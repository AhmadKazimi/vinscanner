package com.syarah.vinscanner.data.datasource.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
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
     * @return Bitmap representation of the image
     */
    fun imageToBitmap(imageProxy: ImageProxy): Bitmap
}