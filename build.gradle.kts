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

// On an Apple Silicon Mac, Gradle has to run on an arm64 JDK. Under an x86_64 JDK (Rosetta 2) the
// Kotlin Gradle Plugin takes the host for an Intel Mac, `macos_x64`: a deprecated Kotlin/Native host
// the compiler will stop shipping for, and one that cannot run the arm64 iOS simulator tests. The
// build cannot change which JDK it was started on, so rather than warn on every build it stops and
// says what to change.
val rosettaJdk = System.getProperty("os.name") == "Mac OS X" &&
    System.getProperty("os.arch") in setOf("x86_64", "amd64") &&
    runCatching {
        providers.exec {
            // Prints 1 when this process is translated by Rosetta 2; the key does not exist on an Intel Mac.
            commandLine("sysctl", "-n", "sysctl.proc_translated")
            isIgnoreExitValue = true
        }.standardOutput.asText.get()
    }.getOrDefault("").trim() == "1"

if (rosettaJdk) {
    throw GradleException(
        """
        |Gradle is running on an x86_64 JDK under Rosetta 2, but this Mac is Apple Silicon:
        |  ${System.getProperty("java.home")}
        |The Kotlin Gradle Plugin takes that for an Intel Mac ('macos_x64'), a deprecated Kotlin/Native
        |host that cannot run the arm64 iOS simulator tests. Run Gradle on an arm64 (aarch64) JDK:
        |  - Android Studio: Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK
        |  - terminal: point JAVA_HOME at one (`/usr/libexec/java_home -V` lists each JDK with its architecture)
        |  - ~/.gradle/gradle.properties: org.gradle.java.home, if it is set there
        """.trimMargin()
    )
}
