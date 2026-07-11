plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "us.easyconnect.pcremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "us.easyconnect.pcremote"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            // R8 full-mode shrinking + resource shrinking → smallest APK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign with the debug key so the minified APK is directly installable.
            // Swap for a real release keystore before publishing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Trim per-density/per-language splits the app doesn't need.
    androidResources {
        localeFilters += listOf("en")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Kotlin's jvmTarget now defaults to compileOptions.targetCompatibility (17).
    buildFeatures {
        compose = true
        buildConfig = false
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/{AL2.0,LGPL2.1}",
            "META-INF/*.version",
            "kotlin/**",
            "**/*.kotlin_module"
        )
    }
}

// Pin androidx.core to its SDK-36 line so no transitive dependency drags in
// 1.19.x (which would demand compileSdk 37). Drop this once you move to SDK 37.
configurations.configureEach {
    resolutionStrategy {
        force("androidx.core:core:1.18.0")
        force("androidx.core:core-ktx:1.18.0")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose — versions governed by the BOM.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    // Offline QR scanner (drop-in scanner activity + ScanContract).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
