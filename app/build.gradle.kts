plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.compose)
}

android {
	namespace = "com.kazimi.syaravin"
	compileSdk = 36

	defaultConfig {
		applicationId = "com.kazimi.syaravin"
		minSdk = 24
		targetSdk = 36
		versionCode = 1
		versionName = "1.0"

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	buildTypes {
		release {
			isMinifyEnabled = false
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro"
			)
		}
		debug {
			isMinifyEnabled = false
		}
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_11
		targetCompatibility = JavaVersion.VERSION_11
	}
	kotlinOptions {
		jvmTarget = "11"
	}
	buildFeatures {
		compose = true
		// Enable ML model binding
		mlModelBinding = true
	}
	composeOptions {
		kotlinCompilerExtensionVersion = "1.5.8"
	}
	// Required for CameraX
	packaging {
		resources {
			excludes += "/META-INF/{AL2.0,LGPL2.1}"
		}
	}

	defaultConfig {

	}
}

dependencies {
	// Core Android dependencies
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	implementation(libs.androidx.activity.compose)
	
	// Compose dependencies
	implementation(platform(libs.androidx.compose.bom))
	implementation(libs.androidx.ui)
	implementation(libs.androidx.ui.graphics)
	implementation(libs.androidx.ui.tooling.preview)
	implementation(libs.androidx.material3)
	implementation(libs.lifecycle.viewmodel.compose)
	
	// CameraX dependencies
	implementation(libs.camera.core)
	implementation(libs.camera.camera2)
	implementation(libs.camera.lifecycle)
	implementation(libs.camera.view)
	
	// Google AI Edge
//	implementation(libs.ai.edge.litert.base)
//	implementation(libs.ai.edge.litert.gpu)
//	implementation(libs.ai.edge.litert.core)

	implementation("com.google.ai.edge.litert:litert:1.4.0")
	implementation("com.google.ai.edge.litert:litert-gpu:1.4.0")
	implementation("com.google.ai.edge.litert:litert-support:1.4.0")


	// ML Kit Text Recognition
	implementation(libs.mlkit.text.recognition)
	
	// Koin for dependency injection
	implementation(libs.koin.android)
	implementation(libs.koin.androidx.compose)
	
	// Coroutines
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.kotlinx.coroutines.core)
	implementation(libs.kotlinx.coroutines.play.services)
	
	// Utilities
	implementation(libs.timber)
	debugImplementation(libs.leakcanary)
	
	// Permissions handling
	implementation(libs.accompanist.permissions)
	
	// Testing dependencies
	testImplementation(libs.junit)
	testImplementation(libs.koin.test)
	androidTestImplementation(libs.androidx.junit)
	androidTestImplementation(libs.androidx.espresso.core)
	androidTestImplementation(platform(libs.androidx.compose.bom))
	androidTestImplementation(libs.androidx.ui.test.junit4)
	debugImplementation(libs.androidx.ui.tooling)
	debugImplementation(libs.androidx.ui.test.manifest)
}