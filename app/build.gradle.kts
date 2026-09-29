plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Google Services plugin needs app/google-services.json (downloaded from your
// Firebase project, see README). It is applied only when that file exists, so the
// project still builds without it. Without it the app shows a "Firebase is not set up" screen.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

android {
    namespace = "com.example.ludoduel"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.example.ludoduel"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        // Empty = use the real Firebase project from google-services.json.
        buildConfigField("String", "FIREBASE_EMULATOR_HOST", "\"\"")
    }

    buildTypes {
        debug {
            // Optional, debug builds only: ./gradlew assembleDebug -PfirebaseEmulatorHost=10.0.2.2
            // makes the app use the local Firebase Auth and Database emulators (see README).
            val emulatorHost = providers.gradleProperty("firebaseEmulatorHost").orNull.orEmpty()
            buildConfigField("String", "FIREBASE_EMULATOR_HOST", "\"$emulatorHost\"")
        }
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.database)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
}
