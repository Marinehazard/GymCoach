import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // NOTE: no org.jetbrains.kotlin.android — AGP 9 has built-in Kotlin (R2 G2).
    alias(libs.plugins.kotlin.compose)          // Compose compiler; version == Kotlin version
    alias(libs.plugins.kotlin.serialization)    // @Serializable route classes in :app (R2 G4)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.gymcoach.app"
    compileSdk = libs.versions.compileSdk.get().toInt()   // 37 -> SDK package "platforms;android-37.0"

    defaultConfig {
        applicationId = "com.gymcoach.app"
        minSdk = libs.versions.minSdk.get().toInt()        // 26
        targetSdk = libs.versions.targetSdk.get().toInt()  // 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false   // turn on later together with proguard-rules.pro (Appendix A.4)
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Built-in Kotlin DSL (AGP 9). Pattern verified in Google's compose-samples (R2 §2.5).
    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.fromTarget("17")
        }
    }

    buildFeatures {
        compose = true
    }

    // Fallback path for the knowledge JSON (§7.5): expose :core's resource directory as assets so
    // AssetKnowledgeSource can read "gymcoach/knowledge/<file>" if class-path loading fails on device.
    // COMPILE-RISK: if Android Studio rejects this block under the AGP 9 DSL, delete it; the
    // class-path path (ClasspathKnowledgeSource) is the primary mechanism and does not need it.
    sourceSets {
        getByName("main") {
            assets.srcDirs("../core/src/main/resources")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/AL2.0"
            excludes += "/META-INF/LGPL2.1"
        }
    }
}

// Room schema export (KSP argument form, R2 §5.5). Exported JSON under app/schemas/ is the basis
// for migrations; switch to the CommandLineArgumentProvider form (R2 G8) after the first build.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    // Compose — one BOM, no versions on the compose artifacts
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    // implementation(libs.androidx.work.runtime)   // v1.1 module only (§9.4)

    // Room (runtime already contains the former room-ktx coroutine APIs)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    // Kotlin / network
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // Unit tests (JVM)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
