plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gangg111.sklep"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gangg111.sklep"
        minSdk = 29
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // Własny klucz (debug.keystore Play Protect flaguje). Hasło w ~/.android/sklep-release.pass.
        create("release") {
            val home = System.getProperty("user.home")
            val pass = file("$home/.android/sklep-release.pass").readText().trim()
            storeFile = file("$home/.android/sklep-release.jks")
            storePassword = pass
            keyAlias = "sklep"
            keyPassword = pass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
}
