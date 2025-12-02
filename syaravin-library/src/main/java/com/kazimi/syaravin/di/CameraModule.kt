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

internal val cameraModule = module {
    // Camera executor for image analysis
    single { Executors.newSingleThreadExecutor() }
    
    // Coroutine dispatcher for camera operations
    single { Dispatchers.Default }
    
    // Camera selector - back camera for VIN scanning
    single { CameraSelector.DEFAULT_BACK_CAMERA }
    
    // Preview use case
    factory {
        Preview.Builder()
            .build()
    }
    
    // Image analysis use case
    factory {
        ImageAnalysis.Builder()
            .setTargetRotation(android.view.Surface.ROTATION_0) // Portrait
            .setTargetResolution(android.util.Size(540, 960)) // Optimized for performance (portrait)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }
    
    // Camera data source
    single<CameraDataSource> { CameraDataSourceImpl(androidContext()) }
}