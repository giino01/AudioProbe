plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.giino.audioprobe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.giino.audioprobe"
        minSdk = 26
        // targetSdk 28 : garde l'acces classique au stockage partage (/sdcard) sur DiLink 3.0
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
