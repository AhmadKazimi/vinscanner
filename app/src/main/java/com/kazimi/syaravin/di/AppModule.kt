package com.kazimi.syaravin.di

import org.koin.dsl.module

val appModule = module {
    // You don't need to provide the application context manually.
    // Koin makes it available automatically when you initialize it
    // in your Application class.
    // The following line was causing the crash:
    // single { androidContext() }
}
