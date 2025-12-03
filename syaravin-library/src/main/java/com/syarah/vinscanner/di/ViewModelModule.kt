package com.syarah.vinscanner.di

import com.syarah.vinscanner.domain.usecase.DetectVinUseCase
import com.syarah.vinscanner.domain.usecase.ExtractTextUseCase
import com.syarah.vinscanner.domain.usecase.ValidateVinUseCase
import com.syarah.vinscanner.presentation.scanner.ScannerViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

internal val viewModelModule = module {
    // Use cases
    factory { DetectVinUseCase(get()) }
    factory { ExtractTextUseCase(get()) }
    factory { ValidateVinUseCase(get()) }
    
    // ViewModels
    viewModel { 
        ScannerViewModel(
            detectVinUseCase = get(),
            extractTextUseCase = get(),
            validateVinUseCase = get()
        )
    }
}