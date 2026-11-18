plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.applelogcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.applelogcam"
        minSdk = 34          // Pixel 10 Pro vem com Android 16; 34 dá acesso a tudo que o app usa
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
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

// Sem dependências externas: só o framework do Android e a stdlib do Kotlin.
