plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
    alias(libs.plugins.crashlytics)
}

android {
    namespace = "com.sti.tanghap"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sti.tanghap"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Flask cough-model server. Override with -Ptanghap.flaskUrl=http://<pc-ip>:5000/
        val flaskUrl = (project.findProperty("tanghap.flaskUrl") as String?) ?: "http://192.168.1.5:5000/"
        buildConfigField("String", "FLASK_BASE_URL", "\"$flaskUrl\"")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // AndroidX & Material
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("com.google.android.material:material:1.13.0")

    // Lifecycle / MVVM
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata:2.8.7")

    // Room
    implementation("androidx.room:room-runtime:2.8.4")
    annotationProcessor("androidx.room:room-compiler:2.8.4")

    // WorkManager
    implementation("androidx.work:work-runtime:2.11.2")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:34.12.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-analytics")

    // Signal Processing (FFT)
    implementation("com.github.wendykierp:JTransforms:3.1")

    // Google Sign-In
    implementation("com.google.android.gms:play-services-auth:21.3.0")

    // Facebook SDK
    implementation("com.facebook.android:facebook-login:18.2.3")

    // Chart
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // HTTP Client (Para sa Flask API Audio Upload)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}