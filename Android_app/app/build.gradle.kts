plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Reads a signing credential from an environment variable first, then a Gradle
// property (e.g. ~/.gradle/gradle.properties). Returns null if neither is set,
// so a machine without the keystore still builds (falls back to the debug key).
fun signingCredential(name: String): String? =
    System.getenv(name) ?: (project.findProperty(name) as String?)

// Version is the repo-root VERSION file — the SAME file the PC server reads — so
// the app and PC server always ship the same version. Override with
// -PversionName=x.y.z. versionCode is derived deterministically from the digits
// (major*10000 + minor*100 + patch), so bumping VERSION bumps both.
val appVersionName: String =
    (project.findProperty("versionName") as String?)
        ?: rootProject.file("../VERSION").readText().trim()
val appVersionCode: Int =
    (project.findProperty("versionCode") as String?)?.toInt()
        ?: Regex("""\d+""").findAll(appVersionName).map { it.value.toInt() }.toList()
            .let { (it.getOrElse(0) { 0 } * 10000) + (it.getOrElse(1) { 0 } * 100) + it.getOrElse(2) { 0 } }

android {
    namespace = "us.easyconnect.pcremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "us.easyconnect.pcremote"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            // Credentials are read from env vars or Gradle properties — NEVER
            // hardcode them and never commit the keystore (.gitignore covers
            // *.jks/*.keystore). Populated only when PCREMOTE_KEYSTORE is set.
            val storePath = signingCredential("PCREMOTE_KEYSTORE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = signingCredential("PCREMOTE_KEYSTORE_PASSWORD")
                keyAlias = signingCredential("PCREMOTE_KEY_ALIAS")
                keyPassword = signingCredential("PCREMOTE_KEY_PASSWORD")
            }
        }
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
            // Use the real release keystore when its credentials are provided
            // (env vars / ~/.gradle/gradle.properties); otherwise fall back to the
            // debug key so the build still works locally/CI without secrets.
            signingConfig = if (signingCredential("PCREMOTE_KEYSTORE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
