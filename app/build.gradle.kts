import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing credentials, kept out of git (see .gitignore). Absent on a fresh clone, in which
// case the release build simply stays unsigned rather than failing the whole configure phase —
// assembleDebug must keep working for someone who only wants to run the app.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseSigning = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.example.maintenanceapp"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.maintenanceapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off deliberately. Vehicle and the other models travel between Activities as
            // Serializable extras and the app has no keep rules yet; a shrink that renamed or
            // stripped a field would fail at runtime, on a friend's phone, with no stack trace
            // coming back to us. Turn it on only alongside real rules and a device test pass.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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