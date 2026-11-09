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
        versionCode = 12
        versionName = "2.6"
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

dependencies {
    // Test-only (not in the APK): JVM unit tests for the pure logic, run by build.ps1.
    testImplementation("junit:junit:4.13.2")
}

// The app itself has no third-party libraries: only the Android SDK and the Kotlin stdlib.
