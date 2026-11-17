plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tiboard.quest3replika"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tiboard.quest3replika"
        // Android 8.0 minimum pour les tablettes ; le Quest 3 tourne sous Android 12L/14 (Horizon OS).
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.1"
    }

    // Signature :
    //  - "debug" utilise un keystore de debug versionné (keystore/debug.keystore) : toutes les compilations,
    //    locales ou GitHub Actions, ont la même signature, donc `adb install -r` met à jour l'app sans la désinstaller.
    //  - "release" utilise un keystore privé fourni par variables d'environnement (secrets GitHub Actions)
    //    s'il existe ; sinon l'APK release est signé avec le keystore de debug ci-dessus.
    val keystorePath = System.getenv("QR_KEYSTORE_FILE")
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (keystorePath != null && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("QR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("QR_KEY_ALIAS")
                keyPassword = System.getenv("QR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

// Aucune dépendance externe : uniquement le SDK Android (MediaProjection, MediaCodec, NSD, EGL/GLES)
// et la bibliothèque standard Kotlin ajoutée automatiquement par le plugin.
dependencies {
}
