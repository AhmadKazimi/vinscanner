plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("maven-publish")
    id("kotlin-parcelize")
}

android {
    namespace = "com.kazimi.syaravin"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        targetSdk = 36

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        mlModelBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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

    // Koin for dependency injection
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Utilities
    implementation(libs.gson)
    implementation(libs.accompanist.permissions)

    // Testing dependencies
    testImplementation(libs.junit)
    testImplementation(libs.koin.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Maven Publishing Configuration
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "com.kazimi"
                artifactId = "syaravin-scanner"
                version = "1.0.0"

                pom {
                    name.set("Syaravin VIN Scanner")
                    description.set("Android library for real-time VIN detection and validation using ML")
                    url.set("https://bitbucket.org/yourworkspace/syaravin-library")

                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }

                    developers {
                        developer {
                            id.set("kazimi")
                            name.set("Kazimi Team")
                            email.set("team@kazimi.com")
                        }
                    }

                    scm {
                        connection.set("scm:git:git://bitbucket.org/yourworkspace/syaravin-library.git")
                        developerConnection.set("scm:git:ssh://bitbucket.org/yourworkspace/syaravin-library.git")
                        url.set("https://bitbucket.org/yourworkspace/syaravin-library")
                    }
                }
            }
        }

        repositories {
            maven {
                name = "BitbucketPackages"
                url = uri("https://api.bitbucket.org/2.0/repositories/${
                    project.findProperty("bitbucket.workspace") ?: System.getenv("BITBUCKET_WORKSPACE")
                }/${
                    project.findProperty("bitbucket.repoSlug") ?: System.getenv("BITBUCKET_REPO_SLUG")
                }/maven")
                credentials {
                    username = project.findProperty("bitbucket.user") as String? ?: System.getenv("BITBUCKET_USER")
                    password = project.findProperty("bitbucket.appPassword") as String? ?: System.getenv("BITBUCKET_APP_PASSWORD")
                }
            }
        }
    }
}
