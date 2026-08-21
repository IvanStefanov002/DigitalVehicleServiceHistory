plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.maintenanceapp"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.maintenanceapp"
        minSdk = 24
        targetSdk = 36
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
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.swiperefreshlayout)   // pull-to-refresh on the data screens
    implementation(libs.work.runtime)         // periodic service-reminder notifications
    implementation(libs.biometric)            // fingerprint/face gate on the saved session
    // Reads the picked photo's EXIF Orientation so portrait shots aren't baked in sideways.
    implementation(libs.exifinterface)
    // QR encoding only — the pure-Java core, not zxing-android-embedded: nothing here scans codes.
    implementation(libs.zxing.core)
    implementation("com.squareup.okhttp3:okhttp:5.3.2") // http requests
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}