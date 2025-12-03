package com.syarah.vinscanner.di

import com.syarah.vinscanner.data.repository.VinScannerRepositoryImpl
import com.syarah.vinscanner.domain.repository.VinScannerRepository
import org.koin.dsl.module

internal val repositoryModule = module {
    // VIN Scanner Repository
    single<VinScannerRepository> { 
        VinScannerRepositoryImpl(
            cameraDataSource = get(),
            vinDetector = get(),
            textExtractor = get(),
            vinValidator = get()
        )
    }
}