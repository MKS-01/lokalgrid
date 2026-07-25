plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.lokalgrid.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.lokalgrid.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        // 64-bit only. 16 KB page sizes are a 64-bit feature, so the 32-bit
        // MapLibre libraries are the ones still linked at 4 KB — carrying them
        // means shipping ~20 MB that cannot run on the only devices this app is
        // built for (a Galaxy S25 and an arm64/x86_64 emulator) while muddying
        // any alignment check.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    // 16 KB page sizes (Android 15+ hardware, and the emulator's 16 KB image).
    // Two separate things have to line up or the map's native library will not
    // load: the .so entries must sit on 16 KB boundaries *inside the APK* — which
    // is what this does, AGP 8.7 zipaligns uncompressed libs to 16 KB — and each
    // .so must itself be linked with 16 KB LOAD alignment, which is why the
    // MapLibre version is pinned in libs.versions.toml.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(project(":protocol"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.okhttp)
    implementation(libs.maplibre)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
