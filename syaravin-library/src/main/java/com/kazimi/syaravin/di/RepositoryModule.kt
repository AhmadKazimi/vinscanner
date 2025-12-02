package com.kazimi.syaravin.di

import com.kazimi.syaravin.data.repository.VinScannerRepositoryImpl
import com.kazimi.syaravin.domain.repository.VinScannerRepository
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