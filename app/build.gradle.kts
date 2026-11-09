plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ua.home.smstg"
    compileSdk = 35

    defaultConfig {
        applicationId = "ua.home.smstg"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "2.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// No third-party libraries: only the Android SDK and the Kotlin stdlib.
