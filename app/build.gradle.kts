plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.kioskassistapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.kioskassistapp"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    aaptOptions{
        noCompress("task")
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.object1.detection)
    implementation(libs.pose.detection.common)
    implementation(libs.play.services.mlkit.text.recognition)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Fragment, ViewModel 등
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // --- 1. CameraX (카메라 제어) ---
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // --- 2. Google ML Kit (OCR, 손 인식) ---
    // Text Recognition (OCR) - 한국어 지원
    implementation("com.google.mlkit:text-recognition-korean:16.0.0")
    implementation("com.google.mlkit:object-detection:17.0.2")    // fallback용 ML Kit Object Detection
    implementation("com.google.mlkit:vision-common:17.3.0")
//    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    //그냥 mlkit는 정확도 잘 안나와서 accurate로 바꿔봄ㅇㅅㅇ
//    implementation("com.google.mlkit:pose-detection-accurate:18.0.0-beta5")
    
    // Object Detection (손 인식을 위해 Custom Model과 함께 사용)



    // --- 3. mediapipe ---
    implementation("com.google.mediapipe:tasks-vision:0.10.29")
    // --- 4. Coroutines (비동기 처리) ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

}