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
        versionCode = 3
        versionName = "0.3"
    }

    // Chave de debug fixa no repositório: sem ela cada build do GitHub Actions gera uma chave nova
    // e o Android recusa instalar a atualização por cima.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
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
