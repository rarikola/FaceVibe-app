plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.facevibe.app"
    compileSdk = 34

    signingConfigs {
        // Fixed debug key so every new test build installs over the previous one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.face2emoji.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3-milestone3"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "task" }
}

dependencies {
    val camerax = "1.3.4"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}

// The face-landmarker model is downloaded once at build time and bundled inside the APK,
// so the finished app runs fully on-device with no network use.
val modelFile = file("src/main/assets/face_landmarker.task")
val downloadModel by tasks.registering {
    onlyIf { !modelFile.exists() }
    doLast {
        modelFile.parentFile.mkdirs()
        val url = uri("https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task").toURL()
        url.openStream().use { input ->
            modelFile.outputStream().use { out -> input.copyTo(out) }
        }
    }
}
tasks.configureEach {
    if (name == "preBuild") dependsOn(downloadModel)
}
