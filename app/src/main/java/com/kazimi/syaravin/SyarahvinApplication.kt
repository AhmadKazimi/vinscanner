package com.kazimi.syaravin

import android.app.Application
import com.kazimi.syaravin.di.appModule
import com.kazimi.syaravin.di.cameraModule
import com.kazimi.syaravin.di.mlModule
import com.kazimi.syaravin.di.repositoryModule
import com.kazimi.syaravin.di.viewModelModule
import com.squareup.leakcanary.core.BuildConfig
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class SyarahvinApplication : Application() {
	override fun onCreate() {
		super.onCreate()

		// Initialize Koin for dependency injection
		startKoin {
			androidLogger()
			androidContext(this@SyarahvinApplication)
			modules(
				appModule,
				cameraModule,
				mlModule,
				repositoryModule,
				viewModelModule
			)
		}
	}
}