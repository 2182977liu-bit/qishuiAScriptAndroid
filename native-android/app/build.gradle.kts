plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.qingsong.qishuiautomation"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.qingsong.qishuiautomation"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"

        // 目标真机为 arm64；不再把 32 位和 x86 原生库打入每个 APK。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    flavorDimensions += listOf("ocr")
    productFlavors {
        create("offline") {
            dimension = "ocr"
            versionNameSuffix = "-offline"
        }
        create("lite") {
            dimension = "ocr"
            versionNameSuffix = "-lite"
        }
    }

    buildTypes {
        all {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    // Offline：模型随 APK 分发，无 Google Play 服务也可识别。
    "offlineImplementation"("com.google.mlkit:text-recognition-chinese:16.0.1")

    // Lite：中文模型由 Google Play 服务按需下载，APK 不内嵌模型。
    "liteImplementation"("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.2")
}
