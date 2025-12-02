
package com.syarah.vinscanner.di

import com.syarah.vinscanner.util.VinDecoder
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

internal val appModule = module {
    single { VinDecoder(androidContext()) }
}
