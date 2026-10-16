// Root build file: declare every plugin once so that a single plugin classloader holds ONE Kotlin
// Gradle Plugin version (2.4.10) for the whole build (R2 G1).
//
// The Android Gradle plugin is put on this same root classpath, but only when :app is included
// (settings.gradle.kts includes :app only when an Android SDK is present). A machine without the
// SDK and without Google Maven (dl.google.com) therefore builds :core without ever resolving AGP.
// AGP 9.4.0 drags in KGP 2.2.10; because AGP and the Kotlin plugins below share this one root
// classpath, Gradle's conflict resolution still picks KGP 2.4.10 (R2 G1, unchanged behaviour).
// The version stays in the catalog (libs.versions.agp / libs.android.gradle.plugin).
// See docs/build/FIRST_BUILD_CHECKLIST.md.
buildscript {
    if (gradle.extra.has("gymcoachAppIncluded") && gradle.extra["gymcoachAppIncluded"] == true) {
        repositories {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
        dependencies {
            classpath(libs.android.gradle.plugin)
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
