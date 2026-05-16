plugins {
    id("com.android.application")
}

android {
    namespace = "dev.medveed.safeshare"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.medveed.safeshare"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Base URL for the backend. Overridable per build type below.
        buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // AndroidX core.
    //
    // fragment 1.8.x and activity 1.9.x transitively demand
    // lifecycle 2.8.x, which breaks startup under AGP 8.3 (see the
    // lifecycle note below). 1.7.x stays on lifecycle 2.7.
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity:1.8.2")
    implementation("androidx.fragment:fragment:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Material 3
    implementation("com.google.android.material:material:1.12.0")

    // Navigation
    implementation("androidx.navigation:navigation-fragment:2.7.7")
    implementation("androidx.navigation:navigation-ui:2.7.7")

    // Lifecycle / LiveData.
    //
    // We deliberately stay on 2.7.x. Starting with 2.8.0 Google split
    // lifecycle into KMP multi-artifact packaging (-android, -desktop,
    // -jvm). Without a very recent AGP, Gradle can resolve
    // lifecycle-runtime to its -desktop variant, which lacks the
    // Android-only ReportFragment$ActivityInitializationListener inner
    // class. ProcessLifecycleInitializer then crashes at app startup
    // with NoClassDefFoundError. Pinning 2.7.0 keeps all lifecycle
    // artefacts monolithic and Android-only, matching our AGP 8.3.
    val lifecycleVersion = "2.7.0"
    implementation("androidx.lifecycle:lifecycle-runtime:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-process:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-livedata:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel:$lifecycleVersion")
    constraints {
        implementation("androidx.lifecycle:lifecycle-common:$lifecycleVersion") {
            because("keep all lifecycle-* artefacts on the same version")
        }
        implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:$lifecycleVersion")
    }

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")

    // WorkManager (used alongside Foreground Service for queuing)
    implementation("androidx.work:work-runtime:2.9.0")

    // Retrofit / OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // QR: generation + scanning
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // CameraX (for the in-app scanner, since zxing-android-embedded
    // already ships an activity we can optionally replace later).
    implementation("androidx.camera:camera-core:1.3.3")
    implementation("androidx.camera:camera-camera2:1.3.3")
    implementation("androidx.camera:camera-lifecycle:1.3.3")
    implementation("androidx.camera:camera-view:1.3.3")
}
