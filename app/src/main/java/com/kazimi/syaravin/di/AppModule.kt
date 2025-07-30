package com.kazimi.syaravin.di

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    // Provide application context
    single { androidContext() }
}