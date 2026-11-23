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
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // 使用捆绑模型，避免运行时依赖 AScript 或下载 PaddleOCR。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.android.gms:play-services-tasks:18.2.0")
}
