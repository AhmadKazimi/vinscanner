plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("maven-publish")
    id("kotlin-parcelize")
}

android {
    namespace = "com.syarah.vinscanner"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        targetSdk = 36

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false  // Enable R8 code shrinking & optimization
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
        mlModelBinding = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
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

    // Google AI Edge LiteRT
    implementation(libs.ai.edge.litert.base)
    implementation(libs.ai.edge.litert.gpu)
    implementation(libs.ai.edge.litert.core)

    // ML Kit
    implementation(libs.mlkit.text.recognition)
    implementation(libs.kotlinx.coroutines.play.services)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Utilities
    implementation(libs.gson)
    implementation(libs.accompanist.permissions)

    // Testing dependencies
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Maven Publishing Configuration
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "com.syarah"
                artifactId = "vinscanner"
                version = "1.2.2"

                pom {
                    name.set("Syarah VIN Scanner")
                    description.set("Android library for real-time VIN detection and validation using ML")
                    url.set("https://github.com/AhmadKazimi/vinscanner")

                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }

                    developers {
                        developer {
                            id.set("ahmadkazimi")
                            name.set("Ahmad Kazimi")
                            email.set("Ahmad.kazimi@syarah.com")
                        }
                    }

                    scm {
                        connection.set("scm:git:git://github.com/AhmadKazimi/vinscanner.git")
                        developerConnection.set("scm:git:ssh://git@github.com/AhmadKazimi/vinscanner.git")
                        url.set("https://github.com/AhmadKazimi/vinscanner")
                    }
                }
            }
        }

        repositories {
            // Publish to Maven Local for testing
            mavenLocal()
        }
    }

}
