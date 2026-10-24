plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.organplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.organplayer"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "0.6.2"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"   // kailangan ng Oboe
            }
        }
    }

    buildFeatures { prefab = true }   // kailangan para sa Oboe

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")   // installs directly; replace with your own key for the Play Store
        }
    }
    lint { checkReleaseBuilds = false }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.oboe:oboe:1.9.0")
}
