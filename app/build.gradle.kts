plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.stresstest"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.stresstest"
        minSdk = 24  // Android 7.0，覆盖绝大多数设备
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // 测试配置：支持 AndroidJUnitRunner
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 启用 ViewBinding，替代繁琐的 findViewById
    buildFeatures {
        viewBinding = true
    }

    // JDK 21 编译兼容性
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // AndroidX 核心库
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // Material Design 3 — 现代 UI 组件
    implementation("com.google.android.material:material:1.12.0")

    // ConstraintLayout — 灵活布局
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // OkHttp 5.0.0（2025年7月发布，独立 Android 包 com.squareup.okhttp5）
    // 注：OkHttp 5.x 将 Maven 坐标从 okhttp3 改为 okhttp5
    implementation("com.squareup.okhttp5:okhttp:5.0.0")

    // Kotlin Coroutines 1.9.0 — 最新稳定版，支持结构化并发
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ViewModel — MVVM 架构组件
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    // RecyclerView — 用于结果列表展示
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
