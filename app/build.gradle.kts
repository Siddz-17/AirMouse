plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.airmouse"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.airmouse"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "2.5"
    }
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/airmouse.keystore")
            storePassword = "airmousepassword"
            keyAlias = "airmouse"
            keyPassword = "airmousepassword"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("com.squareup.okhttp3:okhttp:4.12.0") }
