plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // Sets the toolchain for BOTH Kotlin and Java compile tasks in this module (R2 G3).
    // Default 17 (R2). Override with -PgymcoachJdk=21 (or the env var ORG_GRADLE_PROJECT_gymcoachJdk=21)
    // on a machine that has only a newer JDK and no network path for toolchain auto-download, e.g. a
    // Claude Code cloud VM (OpenJDK 21 preinstalled). :app compiles against Java 17 bytecode, so keep
    // the default whenever :app is built in the same run.
    jvmToolchain((findProperty("gymcoachJdk") as String?)?.toInt() ?: 17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    // Golden files regenerate only on explicit request (§10.4).
    systemProperty("gymcoach.regenerateGolden", System.getProperty("gymcoach.regenerateGolden") ?: "false")
}
