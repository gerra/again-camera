import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val androidEnabled = rootProject.extra["androidEnabled"] as Boolean

if (androidEnabled) {
    apply(plugin = "com.android.application")
}

kotlin {
    if (androidEnabled) {
        androidTarget {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }

    // A development harness, not a shipping platform: the whole app with a fake camera.
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        // Only MainViewController in iosMain is public; everything else in this module is
        // `internal` so the Objective-C header stays small.
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    // The desktop and iOS both draw with Skia, so they share how a photo file becomes a bitmap.
    applyDefaultHierarchyTemplate {
        common {
            group("skiko") {
                withJvm()
                group("ios")
            }
        }
    }

    sourceSets.all {
        // The resource environment the screen models word their messages in.
        languageSettings.optIn("org.jetbrains.compose.resources.ExperimentalResourceApi")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.material.icons.core)
            implementation(libs.compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
        }
        if (androidEnabled) {
            androidMain.dependencies {
                implementation(libs.compose.ui.tooling.preview)
                implementation(libs.android.activity.compose)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.camerax.core)
                implementation(libs.camerax.camera2)
                implementation(libs.camerax.lifecycle)
                implementation(libs.camerax.view)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.compose.ui.test.junit4)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

// Every string the UI shows lives in src/commonMain/composeResources/values/strings.xml, with a
// values-<lang>/strings.xml per translation; the plugin generates `Res` from them. The Res class is
// kept internal, like the rest of the UI, so it stays out of the iOS framework's header.
compose.resources {
    packageOfResClass = "sh.gerra.again.resources"
    publicResClass = false
}

tasks.withType<Test>().configureEach {
    // The UI tests look for English text, whatever the runner's locale.
    jvmArgs("-Duser.language=en", "-Duser.country=US")
    // TranslationsTest reads the string catalogues to check every language is complete.
    systemProperty("again.composeResourcesDir", file("src/commonMain/composeResources").absolutePath)
    // ...and the iOS and Android lists of languages, and iOS's own permission prompts, for each of them.
    systemProperty("again.iosAppDir", rootProject.file("iosApp/iosApp").absolutePath)
    systemProperty("again.localesConfig", file("src/androidMain/res/xml/locales_config.xml").absolutePath)
    // ScreenshotTest writes into build/screenshots unless `-Pagain.screenshotDir=<dir>` (relative to
    // the repository root) points it elsewhere: `-Pagain.screenshotDir=docs/screenshots` for the README.
    systemProperty(
        "again.screenshotDir",
        project.findProperty("again.screenshotDir")?.toString()?.let { rootProject.file(it).absolutePath }
            ?: layout.buildDirectory.dir("screenshots").get().asFile.absolutePath,
    )
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

compose.desktop {
    application {
        mainClass = "sh.gerra.again.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Again"
            packageVersion = "1.0.0"
        }
    }
}

if (androidEnabled) {
    apply(from = "android.gradle")
}
