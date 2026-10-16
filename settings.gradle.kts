pluginManagement {
    repositories {
        google {
            // Google Maven only serves Android/AndroidX artifacts; keep every other lookup away
            // from it so a :core-only build never needs dl.google.com.
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.testing\\.platform.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle download a matching JDK for jvmToolchain(17) if none is installed locally.
    // Version 1.0.0 verified on the Gradle Plugin Portal (R2 §2.1).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            // Google Maven only serves Android/AndroidX artifacts; keep every other lookup away
            // from it so a :core-only build never needs dl.google.com.
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.testing\\.platform.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "GymCoach"
include(":core")

// :app needs the Android SDK (AGP fails at configuration time without one). Android Studio writes
// local.properties (sdk.dir=...) automatically on the first sync, so on the developer machine :app is
// included as soon as the project has been opened once; a machine without an SDK (or a plain
// `gradle :core:test` run before the SDK exists, Appendix A step 4) builds :core alone.
val gymcoachAppIncluded = File(rootDir, "local.properties").exists() ||
    System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null
// Read by the root build.gradle.kts: the Android Gradle plugin is resolved only when :app is built.
gradle.extra["gymcoachAppIncluded"] = gymcoachAppIncluded
if (gymcoachAppIncluded) {
    include(":app")
}
