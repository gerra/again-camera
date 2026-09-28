// The Android Gradle Plugin is put on the build classpath here (instead of
// via a `plugins {}` block) so that it can be switched off with
// `-Pagain.android=false` on machines that have no Android SDK.
val androidEnabled = (findProperty("again.android")?.toString() ?: "true").toBoolean()

buildscript {
    val androidEnabled = (findProperty("again.android")?.toString() ?: "true").toBoolean()
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        if (androidEnabled) {
            classpath("com.android.tools.build:gradle:${libs.versions.agp.get()}")
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
}

extra["androidEnabled"] = androidEnabled
