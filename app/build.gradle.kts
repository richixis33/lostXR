plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.samrat.cardboardhands"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.samrat.cardboardhands"
        minSdk = 29
        targetSdk = 35
        versionCode = 12
        versionName = "1.0.1"
        // PhoneXR itself runs 64-bit; this keeps OpenCV and MediaPipe for other ABIs out of the APK.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildFeatures {
        buildConfig = true
        compose = true
        aidl = true
    }

    // One PhoneXR key everywhere (local builds, CI, patched games): games signed with it may start
    // hand tracking through the signature permission START_HAND_TRACKING.
    signingConfigs {
        getByName("debug") {
            storeFile = file("src/main/assets/phonexr-signing.p12")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // libvrapi.so of the Gear VR adapter, built by gearvr-shim (see gearvr-shim/STATUS.txt).
    // A local build wins; the checked-in copy lets CI (and a fresh clone) package the adapter.
    val shimBuild = rootProject.file("gearvr-shim/build/assets")
    sourceSets.getByName("main").assets.srcDir(if (shimBuild.isDirectory) shimBuild else rootProject.file("gearvr-shim/prebuilt"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.material:material-icons-extended:1.6.8")

    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    val cameraX = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")

    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    // Speech vs. other sounds for the face's mouth (YAMNet audio classifier).
    implementation("com.google.mediapipe:tasks-audio:0.10.35")
    // 6DoF in the VR home: ARCore tracks where the headset is in the room.
    implementation("com.google.ar:core:1.47.0")
    // Calls: Supabase Realtime over a WebSocket.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.android.tools.build:apksig:8.7.3")
    implementation("com.google.android.material:material:1.12.0")
    // ArUco markers on the Joy-Con for camera tracking.
    implementation("org.opencv:opencv:4.14.0")
    // Shizuku runs the cinema display service as the shell user (virtual display + input for other apps).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Интерфейс в стиле Apple HIG: https://github.com/ienground/compose-hig
    implementation("zone.ien.hig:hig:1.4.1")
    // hig отдаёт их только в runtime; экраны используют их напрямую.
    implementation("androidx.compose.foundation:foundation:1.12.0")
    implementation("androidx.compose.ui:ui:1.12.0")
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("androidx.activity:activity-compose:1.13.0")

    testImplementation("junit:junit:4.13.2")
}
