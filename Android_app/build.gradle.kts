// Top-level build file. Plugin versions are declared here and applied per-module.
plugins {
    // AGP 9.0+ bundles Kotlin, so the standalone kotlin-android plugin is gone.
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
}
