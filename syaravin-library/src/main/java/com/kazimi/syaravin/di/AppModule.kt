
package com.kazimi.syaravin.di

import com.kazimi.syaravin.util.VinDecoder
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

internal val appModule = module {
    single { VinDecoder(androidContext()) }
}
