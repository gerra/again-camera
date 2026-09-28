import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val androidEnabled = rootProject.extra["androidEnabled"] as Boolean

if (androidEnabled) {
    apply(plugin = "com.android.library")
}

// Plain Kotlin: the models and rules of a recreation (the guide over the camera, the comparison
// afterwards) with no camera, image or UI types, so every one of them is tested on the JVM alone.
kotlin {
    if (androidEnabled) {
        androidTarget {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

if (androidEnabled) {
    apply(from = "android.gradle")
}
