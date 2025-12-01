plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "uk.co.mrsheep.halive"
    compileSdk = 36

    defaultConfig {
        applicationId = "uk.co.mrsheep.halive"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "${System.getProperty("user.home")}/.config/.android/debug.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    flavorDimensions += "wakeword"

    productFlavors {
        create("onnx") {
            dimension = "wakeword"
            buildConfigField("Boolean", "HAS_WAKE_WORD", "true")
            buildConfigField("Boolean", "HAS_ONNX", "true")
            buildConfigField("Boolean", "HAS_TFLITE", "false")
        }
        create("tflite") {
            dimension = "wakeword"
            buildConfigField("Boolean", "HAS_WAKE_WORD", "true")
            buildConfigField("Boolean", "HAS_ONNX", "false")
            buildConfigField("Boolean", "HAS_TFLITE", "true")
        }
        create("nowake") {
            dimension = "wakeword"
            applicationIdSuffix = ".lite"
            buildConfigField("Boolean", "HAS_WAKE_WORD", "false")
            buildConfigField("Boolean", "HAS_ONNX", "false")
            buildConfigField("Boolean", "HAS_TFLITE", "false")
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // AndroidX Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-ktx:1.8.0")

    // AndroidX Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // AndroidX Browser (for Custom Tabs in OAuth flow)
    implementation("androidx.browser:browser:1.7.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Kotlinx Serialization for JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // OkHttp for SSE connections (MCP client)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // JSON parsing
    implementation("org.json:json:20231013")

    // ONNX Runtime for Android
    "onnxImplementation"(libs.onnx.runtime)

    // LiteRT (formerly TensorFlow Lite) for Android
    "tfliteImplementation"("com.google.ai.edge.litert:litert:1.4.1")

    // ConstraintLayout
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // CameraX for video streaming
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")

    // SwipeRefreshLayout for pull-to-refresh
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Testing
    testImplementation(libs.junit)
    testImplementation("org.mockito:mockito-core:5.7.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}