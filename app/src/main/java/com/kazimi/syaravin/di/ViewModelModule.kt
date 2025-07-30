package com.kazimi.syaravin.di

import com.kazimi.syaravin.domain.usecase.DetectVinUseCase
import com.kazimi.syaravin.domain.usecase.ExtractTextUseCase
import com.kazimi.syaravin.domain.usecase.ValidateVinUseCase
import com.kazimi.syaravin.presentation.scanner.ScannerViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {
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