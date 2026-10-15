// Root build file: declare every plugin once (apply false) so that a single plugin
// classloader holds ONE Kotlin Gradle Plugin version (2.4.10) for the whole build (R2 G1).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
