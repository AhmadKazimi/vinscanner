package com.kazimi.syaravin.di

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import com.kazimi.syaravin.data.datasource.camera.CameraDataSource
import com.kazimi.syaravin.data.datasource.camera.CameraDataSourceImpl
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.util.concurrent.Executors

val cameraModule = module {
    // Camera executor for image analysis
    single { Executors.newSingleThreadExecutor() }
    
    // Coroutine dispatcher for camera operations
    single { Dispatchers.Default }
    
    // Camera selector - back camera for VIN scanning
    single { CameraSelector.DEFAULT_BACK_CAMERA }
    
    // Preview use case
    factory {
        Preview.Builder()
            .setTargetRotation(android.view.Surface.ROTATION_90) // Landscape
            .build()
    }
    
    // Image analysis use case
    factory {
        ImageAnalysis.Builder()
            .setTargetRotation(android.view.Surface.ROTATION_90) // Landscape
            .setTargetResolution(android.util.Size(1280, 720)) // Optimized for performance
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }
    
    // Camera data source
    single<CameraDataSource> { CameraDataSourceImpl(androidContext()) }
}