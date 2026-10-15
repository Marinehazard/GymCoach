# R2 — Verified Android toolchain and dependency versions (as of 2026-09-04)

Scope: the exact, verified-to-exist versions for the GymCoach build (`:core` pure Kotlin/JVM,
`:app` Android/Compose/Material 3, minSdk 26), plus the Gradle skeleton files, the SDK
bootstrap commands, and the Gradle/Kotlin gotchas that matter for this two-module layout.

Method: every version below was checked live on 2026-09-04 by fetching the repository's
`maven-metadata.xml` (or the Gradle version service) and, for the exact version chosen, an
HTTP HEAD on the POM (200 = exists). "Latest listed" columns show the newest version the
repository lists at all (including alpha/beta/RC) so the stable choice is auditable.
Items I could not verify are collected in section 12 — nothing there is silently assumed.

---

## 1. Executive summary (what to pin)

| Component | Pinned | Why this one |
|---|---|---|
| JDK (build machine) | **17 LTS** (Temurin or Microsoft OpenJDK) | AGP 9.4.0 documents JDK 17 minimum/default; Gradle 9.6/9.7 run on JVM 17–26; `jvmToolchain(17)` then needs no download. |
| Gradle wrapper | **9.6.1** (`gradle-9.6.1-bin.zip`) | AGP 9.4.0 requires Gradle ≥ 9.6.0; 9.6.1 is that line's patch release. 9.7.1 is current and also acceptable. |
| Android Gradle Plugin | **9.4.0** | Latest stable (published 2026-09-01). Compose 1.12 requires AGP ≥ 9.2 (compileSdk 37). |
| Kotlin (KGP, compose compiler plugin, serialization plugin, kotlin-test) | **2.4.10** | Latest stable (2026-07-14). 2.4.20 is still RC3. |
| KSP | **2.3.11** | Latest stable (2026-08-03); KSP versions are decoupled from Kotlin since 2.3.0; 2.3.10+ fixed Kotlin-2.4 module-name handling and AGP-9 built-in-Kotlin R-class resolution. |
| kotlinx-coroutines (core/android/test) | **1.11.0** | Latest stable. |
| kotlinx-serialization-json | **1.11.0** | Latest stable (1.12.0-RC is RC). |
| Compose BOM | **2026.08.00** | Latest; maps ui/foundation/tooling → 1.12.0, material3 → 1.4.0, material-icons-extended → 1.7.8. |
| activity-compose | **1.13.0** | Latest stable. |
| navigation-compose | **2.10.0** | Latest stable (2026-08-26). |
| lifecycle-runtime-compose / lifecycle-viewmodel-compose | **2.11.0** | Latest stable (2026-06-17). |
| Room (runtime + compiler via KSP) | **2.8.4** | Latest stable of Room 2. `room-ktx` is an empty shell since 2.8.0 — do not add it. Room 3 (`androidx.room3` 3.0.2) exists; see 6.4 for why not yet. |
| DataStore preferences | **1.2.1** | Latest stable. |
| OkHttp | **5.5.0** | Latest stable (2026-08-16). Requires Android 5.0+/Java 8+. |
| WorkManager | **2.11.2** (`work-runtime`) | Latest stable; `work-runtime-ktx` is a shell that only depends on `work-runtime` (coroutine APIs moved into `work-runtime` in 2.9.0). |
| core-ktx | **1.19.0** | Latest stable. |
| Secure key storage | **Own Android-Keystore AES/GCM wrapper** (no library) | `androidx.security:security-crypto` 1.1.0 deprecated *all* of its APIs "in favour of existing platform APIs and direct use of Android Keystore". |
| Charts | **Hand-drawn Compose `Canvas`** for v1; Vico 3.3.1 verified as upgrade path | Vico 3 is stable and its dependency chain lines up exactly with BOM 2026.08.00, but its 3.x API cannot be written blind on a machine that cannot compile it; see 6.5. |
| Unit tests | JUnit **4.13.2**, `kotlin-test-junit` **2.4.10**, `kotlinx-coroutines-test` **1.11.0** | All verified; JUnit 4 is what AGP/Compose test infra assumes. |
| Desugaring | **Not needed** (minSdk 26 has `java.time`, `java.nio.file`, streams). `desugar_jdk_libs` 2.1.5 recorded if ever needed. | |
| compileSdk / targetSdk / minSdk | **37 / 37 / 26** | Android 17 = API 37, stable since June 2026; Compose 1.12 needs compileSdk 37; Play requires targetSdk ≥ 36 for new apps from 2026-08-31. |
| SDK Build-Tools | **36.0.0** (AGP 9.4 default; leave `buildToolsVersion` unset) | 37.0.0 also exists (stable channel) if ever needed. |
| cmdline-tools | `commandlinetools-win-15859902_latest.zip` | See section 8. |

---

## 2. Verification ledger (every version, with the URL that was checked)

Legend: "latest listed" = newest entry in the metadata regardless of stability; "chosen" = what we pin.

### 2.1 Build tooling

| Item | Chosen | Latest listed | Source checked |
|---|---|---|---|
| Gradle | 9.6.1 (2026-06-26) | 9.7.1 current (2026-08-19) | https://services.gradle.org/versions/current and https://services.gradle.org/versions/all ; HEAD 200 on https://services.gradle.org/distributions/gradle-9.6.1-bin.zip |
| Gradle 9.6.1 sha256 | `9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14` | — | https://services.gradle.org/distributions/gradle-9.6.1-bin.zip.sha256 |
| Gradle 9.7.1 sha256 | `acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a` | — | https://services.gradle.org/distributions/gradle-9.7.1-bin.zip.sha256 (and the `current` JSON) |
| AGP `com.android.tools.build:gradle` | 9.4.0 (POM Last-Modified 2026-09-01) | 9.5.0-alpha04 | https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml ; POM HEAD 200 …/gradle/9.4.0/gradle-9.4.0.pom |
| AGP plugin marker `com.android.application` | 9.4.0 | 9.5.0-alpha04 | https://dl.google.com/dl/android/maven2/com/android/application/com.android.application.gradle.plugin/maven-metadata.xml ; marker POM 9.4.0 HEAD 200 |
| Kotlin Gradle plugin | 2.4.10 (GitHub release 2026-07-14, not pre-release) | 2.4.20-RC3 (2026-09-02, pre-release) | https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml ; https://api.github.com/repos/JetBrains/kotlin/releases/tags/v2.4.10 |
| `org.jetbrains.kotlin.jvm` marker | 2.4.10 | 2.4.20-RC3 | https://repo1.maven.org/maven2/org/jetbrains/kotlin/jvm/org.jetbrains.kotlin.jvm.gradle.plugin/maven-metadata.xml (2.4.10 marker POM HEAD 200) |
| Compose compiler Gradle plugin `org.jetbrains.kotlin.plugin.compose` | 2.4.10 | 2.4.20-RC3 | https://repo1.maven.org/maven2/org/jetbrains/kotlin/compose-compiler-gradle-plugin/maven-metadata.xml ; marker https://repo1.maven.org/maven2/org/jetbrains/kotlin/plugin/compose/org.jetbrains.kotlin.plugin.compose.gradle.plugin/maven-metadata.xml (2.4.10 POM HEAD 200). Its POM depends on `kotlin-gradle-plugin 2.4.10` (verified) — see gotcha G1. |
| Serialization plugin `org.jetbrains.kotlin.plugin.serialization` | 2.4.10 | 2.4.20-RC3 | https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-serialization/maven-metadata.xml ; marker …/org/jetbrains/kotlin/plugin/serialization/org.jetbrains.kotlin.plugin.serialization.gradle.plugin/maven-metadata.xml (2.4.10 HEAD 200) |
| KSP `com.google.devtools.ksp` | 2.3.11 (2026-08-03) | 2.3.11 | https://repo1.maven.org/maven2/com/google/devtools/ksp/symbol-processing-gradle-plugin/maven-metadata.xml ; marker …/com/google/devtools/ksp/com.google.devtools.ksp.gradle.plugin/maven-metadata.xml ; https://api.github.com/repos/google/ksp/releases |
| Foojay toolchain resolver `org.gradle.toolchains.foojay-resolver-convention` | 1.0.0 | 1.0.0 | https://plugins.gradle.org/m2/org/gradle/toolchains/foojay-resolver-convention/org.gradle.toolchains.foojay-resolver-convention.gradle.plugin/maven-metadata.xml (1.0.0 POM HEAD 200); same snippet in https://docs.gradle.org/current/userguide/toolchains.html |
| Room Gradle plugin `androidx.room` (optional, not used by default) | 2.8.4 | 2.8.4 | https://dl.google.com/dl/android/maven2/androidx/room/androidx.room.gradle.plugin/maven-metadata.xml and …/androidx/room/room-gradle-plugin/maven-metadata.xml |
| desugar_jdk_libs (optional) | 2.1.5 | 2.1.5 | https://dl.google.com/dl/android/maven2/com/android/tools/desugar_jdk_libs/maven-metadata.xml (note: lives on Google Maven, NOT Maven Central — the Central metadata is empty) |

### 2.2 Libraries (Maven Central)

| Artifact | Chosen | Latest listed | Source checked |
|---|---|---|---|
| org.jetbrains.kotlinx:kotlinx-coroutines-core | 1.11.0 | 1.11.0 | https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml |
| org.jetbrains.kotlinx:kotlinx-coroutines-android | 1.11.0 | 1.11.0 | https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/maven-metadata.xml |
| org.jetbrains.kotlinx:kotlinx-coroutines-test | 1.11.0 | 1.11.0 | https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-test/maven-metadata.xml |
| org.jetbrains.kotlinx:kotlinx-serialization-json | 1.11.0 | 1.12.0-RC | https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/maven-metadata.xml (1.11.0 JVM POM requires kotlin-stdlib ≥ 2.3.20 — fine with 2.4.10) |
| com.squareup.okhttp3:okhttp | 5.5.0 | 5.5.0 | https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml ; module file: android + jvm variants, requires kotlin-stdlib 2.2.21 and okio 3.18.1 |
| junit:junit | 4.13.2 | 4.13.2 | https://repo1.maven.org/maven2/junit/junit/maven-metadata.xml |
| org.jetbrains.kotlin:kotlin-test / kotlin-test-junit | 2.4.10 | 2.4.20-RC3 | https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-test/maven-metadata.xml ; kotlin-test-junit 2.4.10 POM HEAD 200 |
| com.patrykandpatrick.vico:compose-m3 (evaluated, not pinned) | 3.3.1 (2026-08-28) | 3.3.1 | https://repo1.maven.org/maven2/com/patrykandpatrick/vico/compose-m3/maven-metadata.xml ; https://api.github.com/repos/patrykandpatrick/vico/releases |
| com.patrykandpatrick.vico:compose-m3 2.x line (evaluated) | 2.5.2 (2026-06-21) | — | POM HEAD 200 …/vico/compose-m3/2.5.2/compose-m3-2.5.2.pom (depends on androidx compose-bom 2026.06.00 + vico core 2.5.2) |

### 2.3 AndroidX (Google Maven)

| Artifact | Chosen | Latest listed | Source checked |
|---|---|---|---|
| androidx.compose:compose-bom | 2026.08.00 | 2026.08.00 | https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/maven-metadata.xml ; BOM POM read: 248 managed artifacts |
| androidx.compose.ui:ui / ui-tooling / ui-tooling-preview | 1.12.0 via BOM | 1.13.0-alpha02 | https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui/maven-metadata.xml ; POMs 1.12.0 HEAD 200 |
| androidx.compose.foundation:foundation | 1.12.0 via BOM | 1.13.0-alpha02 | https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation/maven-metadata.xml |
| androidx.compose.material3:material3 | 1.4.0 via BOM (stable since 2025-09-24) | 1.5.0-alpha27 | https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3/maven-metadata.xml ; release notes https://developer.android.com/jetpack/androidx/releases/compose-material3 |
| androidx.compose.material:material-icons-extended (and -core) | 1.7.8 via BOM (frozen; last version ever) | 1.7.8 | https://dl.google.com/dl/android/maven2/androidx/compose/material/material-icons-extended/maven-metadata.xml ; BOM 2026.08.00 POM lists both at 1.7.8 |
| androidx.activity:activity-compose | 1.13.0 (2026-03-11) | 1.14.0-alpha01 | https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/maven-metadata.xml |
| androidx.navigation:navigation-compose | 2.10.0 (2026-08-26) | 2.10.0 | https://dl.google.com/dl/android/maven2/androidx/navigation/navigation-compose/maven-metadata.xml |
| androidx.lifecycle:lifecycle-runtime-compose / lifecycle-viewmodel-compose | 2.11.0 (2026-06-17) | 2.12.0-alpha02 | https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-compose/maven-metadata.xml and …/lifecycle-viewmodel-compose/maven-metadata.xml |
| androidx.room:room-runtime / room-compiler | 2.8.4 (2025-11-19) | 2.8.4 | https://dl.google.com/dl/android/maven2/androidx/room/room-runtime/maven-metadata.xml and …/room-compiler/maven-metadata.xml |
| androidx.room:room-ktx | NOT used (blank shell since 2.8.0) | 2.8.4 | https://dl.google.com/dl/android/maven2/androidx/room/room-ktx/maven-metadata.xml ; Room release notes: "The KTX artifact … has been merged to androidx.room:room-runtime … the artifact is now blank." |
| androidx.room3:room3-runtime (evaluated) | — | 3.0.2 (2026-08-26) | https://dl.google.com/dl/android/maven2/androidx/room3/room3-runtime/maven-metadata.xml ; https://developer.android.com/jetpack/androidx/releases/room3 |
| androidx.datastore:datastore-preferences | 1.2.1 (2026-03-11) | 1.3.0-alpha10 | https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-preferences/maven-metadata.xml |
| androidx.work:work-runtime | 2.11.2 (2026-03-25) | 2.12.0-rc01 | https://dl.google.com/dl/android/maven2/androidx/work/work-runtime-ktx/maven-metadata.xml (same versions) ; work-runtime 2.11.2 POM HEAD 200 ; work-runtime-ktx 2.11.2 POM depends only on work-runtime |
| androidx.core:core-ktx | 1.19.0 (2026-06-03) | 1.19.0 | https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/maven-metadata.xml |
| androidx.security:security-crypto (evaluated, rejected) | — | 1.1.0 (2025-07-30) | https://dl.google.com/dl/android/maven2/androidx/security/security-crypto/maven-metadata.xml ; https://developer.android.com/jetpack/androidx/releases/security |
| androidx.test.ext:junit / espresso-core (instrumented tests, optional) | 1.3.0 / 3.7.0 | same | …/androidx/test/ext/junit/maven-metadata.xml ; …/androidx/test/espresso/espresso-core/maven-metadata.xml |

### 2.4 Android SDK packages (from the SDK repository manifest)

Source: https://dl.google.com/android/repository/repository2-3.xml (channel-0 = stable), plus
https://developer.android.com/studio (cmdline-tools table) and an HTTP HEAD on the zip.

| Package | Status |
|---|---|
| `platforms;android-37.0` | stable (channel-0), revision 2, api-level "37.0", extension-level 22, base extension. **There is no `platforms;android-37` package** — Android 17 platforms are published with an explicit minor (`37.0`, `37.1`, `37.2`); AGP maps `compileSdk = 37` onto the base minor (see 12, U3). |
| `platforms;android-37.1`, `platforms;android-37.2` | stable, Android 17 QPR1/QPR2 (minor SDK versions; only needed with `compileSdk { version = release(37) { minorApiLevel = N } }`). |
| `platforms;android-36`, `platforms;android-36.1` | stable (Android 16 / 16 QPR2). |
| `build-tools;36.0.0` | stable — AGP 9.4.0's minimum and default. |
| `build-tools;37.0.0` | stable (also 36.1.0). Not required. |
| `platform-tools` | present (adb etc.). |
| `cmdline-tools;latest` | present; the direct zip is `commandlinetools-win-15859902_latest.zip` (HEAD 200, 155,655,386 bytes, Last-Modified 2026-07-15). Page-listed SHA-256: `90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a` (copied from the page; not recomputed by me). |

### 2.5 Platform / policy facts used

| Fact | Source |
|---|---|
| AGP 9.4.0 (September 2026): Gradle min 9.6.0, Build Tools min 36.0.0, JDK min/default 17, max API level 37 | https://developer.android.com/build/releases/gradle-plugin and https://developer.android.com/build/releases/agp-9-4-0-release-notes |
| AGP 9.3.x: Gradle min 9.5.0; AGP 9.2.x: Gradle min 9.4.1 ("Update Kotlin Gradle plugin dependency to 2.3.10"); AGP 9.1.x: Gradle min 9.3.1 | https://developer.android.com/build/releases/agp-9-3-0-release-notes , …/agp-9-2-0-release-notes , …/agp-9-1-0-release-notes |
| AGP 9.0: built-in Kotlin on by default; `org.jetbrains.kotlin.android` must be removed; runtime dependency on KGP 2.2.10 (a lower KGP is auto-upgraded); higher KGP/KSP is allowed by putting it on the build classpath; `android.builtInKotlin` / `android.newDsl` properties | https://developer.android.com/build/releases/past-releases/agp-9-0-0-release-notes and https://developer.android.com/build/migrate-to-built-in-kotlin |
| AGP 9.4.0 POM still declares runtime deps `kotlin-gradle-plugin 2.2.10`, `kotlin-stdlib 2.2.10`, `symbol-processing-gradle-plugin 2.2.10-2.0.2` | https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.4.0/gradle-9.4.0.pom and `.module` |
| Kotlin 2.4.x KGP: fully-supported Gradle 7.6.3–9.5.0, AGP 8.5.2–9.1.0 ("You can also use newer Gradle versions but may encounter deprecation warnings"); minimum AGP 8.5.2; K1 removed (`-language-version 1.9` gone); Compose feature flags `StrongSkipping`/`IntrinsicRemember` now ERROR-level deprecated; default module name is now `{group}:{project_name}` | https://kotlinlang.org/docs/gradle-configure-project.html and https://kotlinlang.org/docs/whatsnew24.html |
| Gradle 9.6.1 / 9.7.1: "A JVM version between 17 and 26 is required to execute Gradle"; Gradle 9.7 embeds Kotlin 2.4.0 for build scripts; 9.6.1 doc says tested with Kotlin 2.0.0 through 2.4.0-RC | https://docs.gradle.org/current/userguide/compatibility.html , https://docs.gradle.org/9.6.1/userguide/compatibility.html |
| Compose UI 1.12.0 (2026-08-12): "Updated Compose compileSdk to API 37. This means that a minimum AGP version of 9.2.0 is required when using Compose." Same note in Navigation 2.10 and Lifecycle 2.11 notes | https://developer.android.com/jetpack/androidx/releases/compose-ui , …/navigation , …/lifecycle |
| Material 3 1.4.0: icons library "no longer recommended … We have stopped publishing updates to this library and it has been removed from the latest Material 3 library release, you can still manually reference it"; "This library no longer adds a dependency to material-icons-core so … you will have to explicitly add that dependency" | https://developer.android.com/jetpack/androidx/releases/compose-material3 (Version 1.4.0) and https://developer.android.com/develop/ui/compose/graphics/images/material |
| Room 2.8: targets Kotlin 2.0+, KSP2 recommended; minSdk raised to 23; Room Gradle plugin needs AGP ≥ 8.4; `room-ktx` blank | https://developer.android.com/jetpack/androidx/releases/room |
| Room schema export, KSP way (`room.schemaLocation` via `CommandLineArgumentProvider`) and plugin way | https://developer.android.com/training/data-storage/room/migrating-db-versions |
| Navigation 2.10.0: minSdk 24; WorkManager 2.11: minSdk 23; Room 2.8: minSdk 23 — all ≤ 26 | release-notes pages above and https://developer.android.com/jetpack/androidx/releases/work |
| security-crypto 1.1.0-beta01 → 1.1.0: "Deprecated all APIs in favour of existing platform APIs and direct use of Android Keystore." | https://developer.android.com/jetpack/androidx/releases/security |
| Android Keystore AES/GCM reference example (`KeyGenerator` + `KeyGenParameterSpec.Builder(...).setBlockModes(BLOCK_MODE_GCM).setEncryptionPaddings(ENCRYPTION_PADDING_NONE)`, `Cipher.getInstance("AES/GCM/NoPadding")`), API 23+ | https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec |
| Android 17 = API 37, stable June 2026 (OkHttp changelog: "Android 17 (API 37, released June 2026)"); `compileSdk = 37` / `targetSdk = 37` snippets | https://developer.android.com/about/versions/17/setup-sdk ; https://raw.githubusercontent.com/square/okhttp/master/CHANGELOG.md ; SDK manifest channel-0 |
| Minor-SDK DSL: `android { compileSdk { version = release(36) { minorApiLevel = 1 } } }` (AGP ≥ 8.13); DSL reference: `compileSdk: Int?`, `compileSdkMinor: Int?` (added 8.10.1), `compileSdk { }` block (added 8.12.0), `compileSdkVersion(...)` deprecated/removed in AGP 10 | https://developer.android.com/about/versions/16/qpr2/setup-sdk ; https://developer.android.com/reference/tools/gradle-api/9.4/com/android/build/api/dsl/CommonExtension |
| Google Play: from 2026-08-31 new apps/updates must target API ≥ 36 | https://developer.android.com/google/play/requirements/target-sdk |
| sdkmanager usage, `cmdline-tools/latest` layout, `--sdk_root`, `--licenses` (page notes the tool "is deprecated in favor of the Android CLI `android sdk` command" but it still works) | https://developer.android.com/tools/sdkmanager |
| KSP configurations: `ksp(...)` = "Single-target JVM / Android — main source set"; `kspTest`, `kspDebug`, … ; the 2.3.10 deprecation of the global `ksp` configuration applies to KMP projects (`ksp.allow.all.target.configuration`) | https://raw.githubusercontent.com/google/ksp/main/README.md ; https://api.github.com/repos/google/ksp/releases/tags/2.3.10 |
| OkHttp: "OkHttp works on Android 5.0+ (API level 21+) and Java 8+"; 5.x is packaged as separate JVM and Android artifacts (Gradle module metadata picks the right one) | https://raw.githubusercontent.com/square/okhttp/master/README.md ; CHANGELOG 5.0.0 |
| Real-world reference build using this exact stack: Google's compose-samples JetNews pins AGP 9.3.1, Kotlin 2.4.10, KSP 2.3.11, BOM 2026.08.00, compileSdk 37, coroutines 1.11.0, serialization 1.11.0, Room 2.8.4, OkHttp 5.4.0, desugar 2.1.5, Gradle 9.5.0; app module applies only `com.android.application` + `org.jetbrains.kotlin.plugin.serialization` + `org.jetbrains.kotlin.plugin.compose` (no `kotlin-android`), and sets `android { kotlin { compilerOptions { jvmTarget = JvmTarget.fromTarget("17") } } }` | https://raw.githubusercontent.com/android/compose-samples/main/JetNews/gradle/libs.versions.toml , …/JetNews/app/build.gradle.kts , …/Jetcaster/core/data/build.gradle.kts (Room + `ksp(libs.androidx.room.compiler)` under AGP 9) |
| winget IDs that exist: `EclipseAdoptium.Temurin.17.JDK` (17.0.9.9), `Microsoft.OpenJDK.17` (17.0.9.8), `EclipseAdoptium.Temurin.21.JDK` | https://api.github.com/repos/microsoft/winget-pkgs/contents/manifests/e/EclipseAdoptium/Temurin/17/JDK (and the two siblings) |

---

## 3. JDK, Gradle and AGP compatibility decision

**Chosen: JDK 17 + Gradle 9.6.1 + AGP 9.4.0 + Kotlin 2.4.10 + KSP 2.3.11.**

Why JDK 17 and not 21:
- AGP 9.4.0's compatibility table says JDK minimum 17, default 17.
- Gradle 9.6.1/9.7.1 run on JVM 17–26, so 17 is inside the window.
- With `jvmToolchain(17)` in `:core` and `compileOptions`/`jvmTarget` 17 in `:app`, a single installed JDK 17 satisfies both the daemon and the toolchain — no auto-provisioning, no second JDK.
- JDK 21 also works as the daemon JDK (Android Studio bundles a 21 runtime). If the user installs 21 instead, the `foojay-resolver-convention` plugin in `settings.gradle.kts` lets Gradle download a JDK 17 for the `:core` toolchain automatically; or set `jvmToolchain(21)` in `:core` (17 bytecode target is not required in `:core`). Do not raise `:app` above 17 without checking D8/R8 support.
- Known wrinkle: AGP 9.3.0/9.3.1 lint crashed on JDK 17 (`NoSuchMethodError: java.util.List.removeLast()`, issue 522845800), fixed in AGP 9.3.2 (published 2026-08-24). AGP 9.4.0 was published 2026-09-01, so the fix should be included, but I could not confirm the 9.4.0 notes mention it (see U4). If `lint` ever throws that error on JDK 17, run Gradle on JDK 21 or use `-x lint` until confirmed.

Why Gradle 9.6.1 and not 9.7.1 or 9.5.x:
- AGP 9.4.0 requires Gradle ≥ 9.6.0 (documented minimum and default). 9.6.1 is that line's patch.
- Kotlin 2.4.10's KGP is "fully supported" only up to Gradle 9.5.0; anything newer is "may encounter deprecation warnings", i.e. warnings, not failures. Evidence it works in practice: Vico 3.3.1 was published from a Gradle 9.7.1 + Kotlin 2.4.10 build (module metadata `createdBy gradle 9.7.1`, stdlib 2.4.10); OkHttp 5.5.0 from Gradle 9.6.1.
- 9.7.1 is fine too (same JVM window, embeds Kotlin 2.4.0 for build scripts). Prefer 9.6.1 for fewer new deprecation surfaces.

Why AGP 9.4.0 and not the reference-proven 9.3.x:
- The brief asks for latest stable, and 9.4.0 is it. Compose 1.12 only needs ≥ 9.2.0.
- The Kotlin table lists AGP ≤ 9.1.0 as "fully supported" for KGP 2.4.x, but the AGP side explicitly supports a *higher* KGP than its bundled 2.2.10 ("To use a higher version of KGP or KSP, add … to your top-level build file"), and Google's own compose-samples run AGP 9.3.1 with KGP 2.4.10 + KSP 2.3.11.
- **Fallback that changes only two strings** if the 9.4.0 combination misbehaves in Android Studio: `agp = "9.3.2"` and `distributionUrl=…/gradle-9.5.1-bin.zip` (sha256 not fetched; get it from https://services.gradle.org/distributions/gradle-9.5.1-bin.zip.sha256). That pairing sits inside KGP 2.4.10's fully-supported Gradle range and matches compose-samples' AGP 9.3.x line.

Why Kotlin 2.4.10 and not 2.3.21:
- 2.4.10 is the latest stable; 2.3.21's documented AGP maximum is 9.0.0 (worse). 2.4.20 is still RC3 (2026-09-02) — do not pin an RC. When 2.4.20 goes stable, bump `kotlin` (and nothing else: the compose and serialization plugins follow the same version string).

Why KSP 2.3.11:
- Latest stable; KSP ≥ 2.3.1 added AGP 9 built-in Kotlin support; 2.3.10 fixed Kotlin 2.4's new default module names (`{group}:{project_name}` contains ':') and R-class resolution under built-in Kotlin. KSP 2.x embeds its own Analysis API (2.3.11's is built on Kotlin 2.3.20), so it is independent of the KGP version — but keep source code to features that exist in Kotlin 2.3 (we do: compile-conservative code).

---

## 4. compileSdk / targetSdk / minSdk / build-tools

- `compileSdk = 37` (Android 17). Required by Compose 1.12.0, Navigation 2.10.0, Lifecycle 2.11.0 (all "updated compileSdk to API 37"). AGP 9.4 supports up to API 37.
- `targetSdk = 37`: "target latest stable" per REQUIREMENTS; satisfies Play's ≥ 36 rule (2026-08-31). Targeting 37 opts into Android 17 behaviour changes (e.g. large-screen orientation/resizability rules) — for a phone-first app this is fine; if something surprises, `targetSdk = 36` is a legitimate temporary step-down and keeps Play compliance.
- `minSdk = 26` (decided). Library floors are all lower: Room 23, WorkManager 23, Navigation 24, OkHttp 21.
- Build-Tools: do **not** set `buildToolsVersion`; AGP 9.4.0 uses its default 36.0.0. Install `build-tools;36.0.0`.
- SDK platform package for compileSdk 37 is `platforms;android-37.0` (there is no `android-37`). The 37.1/37.2 packages are only needed if you later set `compileSdk { version = release(37) { minorApiLevel = 1 } }`.
- Desugaring: not needed. `java.time`, `java.nio.file` and `java.util.stream` are all available from API 26 natively. Keep `isCoreLibraryDesugaringEnabled` off; if a future dependency demands it, use `com.android.tools:desugar_jdk_libs:2.1.5` with `coreLibraryDesugaring(...)` (Jetcaster shows the exact pattern).

---

## 5. Gradle files (copy as-is)

### 5.1 `gradle/libs.versions.toml`

Alias naming was chosen so that no alias is a prefix of another alias in the same section
(Gradle's type-safe accessor generation is happiest that way).

```toml
[versions]
agp = "9.4.0"
kotlin = "2.4.10"
ksp = "2.3.11"
coroutines = "1.11.0"
serialization = "1.11.0"
compose-bom = "2026.08.00"
activity = "1.13.0"
navigation = "2.10.0"
lifecycle = "2.11.0"
room = "2.8.4"
datastore = "1.2.1"
okhttp = "5.5.0"
work = "2.11.2"
core-ktx = "1.19.0"
junit4 = "4.13.2"
desugar = "2.1.5"
# SDK levels (read with libs.versions.xxx.get().toInt())
compileSdk = "37"
targetSdk = "37"
minSdk = "26"

[libraries]
# Kotlin / kotlinx
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlin-test-junit = { module = "org.jetbrains.kotlin:kotlin-test-junit", version.ref = "kotlin" }
junit4 = { module = "junit:junit", version.ref = "junit4" }

# Compose (versions come from the BOM; do not add version.ref to these)
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "compose-bom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-foundation = { module = "androidx.compose.foundation:foundation" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-icons-extended = { module = "androidx.compose.material:material-icons-extended" }   # BOM pins 1.7.8 (frozen)
compose-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-tooling = { module = "androidx.compose.ui:ui-tooling" }                              # debugImplementation

# AndroidX
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activity" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigation" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "core-ktx" }
androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
androidx-work-runtime = { module = "androidx.work:work-runtime", version.ref = "work" }
androidx-room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
androidx-room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }

# Network
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }

# Optional (not applied by default)
desugar-jdk-libs = { module = "com.android.tools:desugar_jdk_libs", version.ref = "desugar" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

### 5.2 `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle download a matching JDK for jvmToolchain(17) if none is installed locally.
    // Version 1.0.0 verified on the Gradle Plugin Portal and used verbatim in the Gradle toolchains guide.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GymCoach"
include(":core")
include(":app")
```

Notes: `gradle/libs.versions.toml` is picked up automatically as the `libs` catalog. Do not
add a `versionCatalogs {}` block. The AGP plugin marker resolves from `google()`; the Kotlin
and KSP markers from `mavenCentral()`; the foojay plugin from `gradlePluginPortal()`.

### 5.3 Root `build.gradle.kts`

```kotlin
// Root build file: declare every plugin once (apply false) so that a single plugin
// classloader holds ONE Kotlin Gradle Plugin version (2.4.10) for the whole build.
// AGP 9.4.0 itself depends on KGP 2.2.10; Gradle's conflict resolution picks the higher
// 2.4.10 pulled in by these plugins, which is the documented way to run AGP 9 with a newer KGP.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
```

No `allprojects {}` repositories block (FAIL_ON_PROJECT_REPOS is set in settings).

### 5.4 `core/build.gradle.kts` (pure Kotlin/JVM)

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // Sets the toolchain for BOTH Kotlin and Java compile tasks in this module.
    // Requires a JDK 17 on the machine, or the foojay plugin (settings.gradle.kts) to download one.
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnit()   // JUnit 4 (Gradle's default for Kotlin/JVM; stated explicitly)
}
```

Rules for `:core` source: no `android.*`/`androidx.*` imports; `java.time` is fine (API 26+
on the Android side); `kotlinx.serialization` annotations are fine (the plugin is applied here).

### 5.5 `app/build.gradle.kts` (Android, Compose, Room via KSP)

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // NOTE: no org.jetbrains.kotlin.android here — AGP 9 has built-in Kotlin and that plugin is
    // incompatible with the AGP 9 DSL.
    alias(libs.plugins.kotlin.compose)          // Compose compiler; version == Kotlin version
    alias(libs.plugins.kotlin.serialization)    // needed for @Serializable route classes in :app
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.gymcoach.app"               // TODO: final package name
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
            isMinifyEnabled = false   // turn on later together with R8 rules; keep first builds simple
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // isCoreLibraryDesugaringEnabled = true   // not needed at minSdk 26 (see section 4)
    }

    // Built-in Kotlin DSL (AGP 9). jvmTarget defaults to compileOptions.targetCompatibility,
    // but stating it removes any doubt. Pattern verified in Google's compose-samples (AGP 9.3.1).
    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.fromTarget("17")
        }
    }

    buildFeatures {
        compose = true   // still required with the Kotlin 2.x Compose compiler plugin
    }

    packaging {
        resources {
            // Multiple Kotlin/AndroidX artifacts ship these; excluding them avoids duplicate-file failures.
            excludes += "/META-INF/AL2.0"
            excludes += "/META-INF/LGPL2.1"
        }
    }
}

// Room schema export (KSP argument form). Room reads "room.schemaLocation"; the exported JSON
// under app/schemas/ is the basis for migrations and MigrationTestHelper.
// (Alternative with proper Gradle input tracking: the CommandLineArgumentProvider form from
//  https://developer.android.com/training/data-storage/room/migrating-db-versions — see gotcha G8.)
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
    implementation(libs.compose.icons.extended)   // BOM-managed 1.7.8; see gotcha G9 before relying on it
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)

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
```

Create the empty directory `app/schemas/` (with a `.gitkeep`) before the first build so the
exported schema JSON has a home.

### 5.6 `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true

# Required by AGP for AndroidX artifacts
android.useAndroidX=true

# AGP 9 defaults, written out so the intent is explicit (do NOT set these to false:
# false would require the incompatible org.jetbrains.kotlin.android plugin again)
android.builtInKotlin=true
android.newDsl=true

kotlin.code.style=official
```

Leave `org.gradle.configuration-cache` off for the first builds; KSP 2.3.11 and AGP 9 support
it, but it adds a class of failure that is hard to diagnose blind. Turn it on once the
project builds in Android Studio.

### 5.7 `gradle/wrapper/gradle-wrapper.properties`

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.1-bin.zip
distributionSha256Sum=9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

(For 9.7.1: `gradle-9.7.1-bin.zip`, sha256
`acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a`.)

Bootstrapping the wrapper on a machine with no Gradle: the properties file alone is not
enough — `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` must exist. The
documented way is to run `gradle wrapper --gradle-version 9.6.1 --distribution-type bin`
once with a locally unzipped Gradle (download `gradle-9.6.1-bin.zip` from the URL above,
unzip, put its `bin` on PATH for that one command). After that, only `gradlew` is used.
Android Studio also regenerates the wrapper when you accept its "fix Gradle wrapper" prompt.

---

## 6. Component decisions in detail

### 6.1 Secure key storage: write a small Android-Keystore AES/GCM wrapper (no library)

Status of `androidx.security:security-crypto`: 1.1.0 is the latest and last stable (2025-07-30);
from 1.1.0-beta01 onward Google "Deprecated all APIs in favour of existing platform APIs and
direct use of Android Keystore" (`EncryptedSharedPreferences`, `EncryptedFile`, `MasterKey`,
`MasterKeys` are all deprecated). It is still resolvable, but it is a maintenance dead end that
pulls Tink transitively and produces deprecation warnings in every file that touches it.

Recommendation: **Android Keystore AES-256/GCM key + our own ~80-line wrapper**, ciphertext
stored in DataStore Preferences (Base64 IV + Base64 ciphertext). Design points, each traceable
to the platform reference:

- Generate: `KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")`
  initialised with `KeyGenParameterSpec.Builder(alias, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
  .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
  .setKeySize(256).build()` — this is the reference page's own AES example (API 23+; we are at 26).
- Encrypt: `Cipher.getInstance("AES/GCM/NoPadding")`, `init(ENCRYPT_MODE, key)`, take `cipher.iv`
  (12 bytes, generated by the Keystore because randomized encryption is required by default — never
  supply your own IV), `doFinal(plaintext)`.
- Decrypt: `init(DECRYPT_MODE, key, GCMParameterSpec(128, iv))`.
- Do **not** call `setUserAuthenticationRequired(true)` (would force biometric prompts on every
  chat call) and do not request StrongBox (slower, fewer concurrent operations; not needed for an
  API key).
- Failure policy (sacred-content discipline, made explicit in the settings UI): the key never leaves
  the device and is gone after uninstall/new device; if decryption throws
  (`AEADBadTagException`, `KeyPermanentlyInvalidatedException`, missing alias) treat the state as
  "no API key stored" and ask the user again — never silently retry with a new key over old data.
- Exclude the DataStore file that holds the ciphertext from Auto Backup / device transfer
  (`android:dataExtractionRules` / `android:fullBackupContent`), since backed-up ciphertext is
  useless without the device-bound key and would only confuse a restore.
- The wrapper is ~80 lines of `javax.crypto` + `android.security.keystore` calls — all API-26-safe,
  all in `:app`.

Alternative considered: Tink (`com.google.crypto.tink:tink-android` 1.23.0 exists on Central) —
rejected: another dependency and API surface for no gain over the platform primitives.

### 6.2 Charts: hand-drawn Compose `Canvas` for v1; Vico 3.3.1 is the verified upgrade path

Vico status (verified): 3.3.1 stable (2026-08-28), artifact `com.patrykandpatrick.vico:compose-m3:3.3.1`.
Its Android dependency chain was inspected end-to-end: `compose-m3` → `org.jetbrains.compose.material3:material3:1.9.0`
→ (android variant) `androidx.compose.material3:material3:1.4.0`; `compose` → `org.jetbrains.compose.{foundation,runtime,ui}:*-android:1.12.0`
→ `androidx.compose.*:*-android:1.12.0`; `kotlinx-coroutines-core 1.11.0`; `kotlin-stdlib 2.4.10`.
Every one of those equals what BOM 2026.08.00 pins, so Vico 3.3.1 would resolve without version
conflicts. The retired 2.x line (last 2.5.2, 2026-06-21) depends on androidx directly (BOM 2026.06.00)
but is no longer developed.

Trade-off:
- Vico 3 gives axes, labels, scrolling, zoom, markers and animations for free — but 3.0 was a
  "moderate breaking changes" restructuring (`multiplatform` module renamed to `compose`, `core`
  merged away, new package roots). Writing Vico 3 code on a machine that cannot compile it means
  guessing exact API names and defaults; a single wrong symbol blocks the whole `:app` build until
  the user opens Android Studio.
- A hand-drawn chart uses only long-stable Compose primitives (`Canvas`, `drawLine`, `drawPath`,
  `drawCircle`, `rememberTextMeasurer`/`drawText`, `Path`, `Stroke`). ~250–300 lines cover what
  GymCoach needs in v1: line chart with time x-axis, y-axis grid + labels, e1RM/volume/body-weight
  series, highlighted last point. Risk: less polish (no pan/zoom), and we own the layout math.

Recommendation: implement `LineChart(points, …)` on `Canvas` behind a tiny `ChartData` model in
`:core` (so the numbers are computed and unit-tested in `:core`), keep the composable dumb, and note
Vico 3.3.1 as the drop-in upgrade once the project compiles in Android Studio. If the user prefers
Vico now anyway, pin `vico = "3.3.1"` and `vico-compose-m3 = { module = "com.patrykandpatrick.vico:compose-m3", version.ref = "vico" }`
— it will resolve; only the source code carries risk.

### 6.3 Material icons

`androidx.compose.material:material-icons-extended` is frozen at 1.7.8: Material 3 1.4.0 release
notes state the icons library is "no longer recommended", "We have stopped publishing updates to
this library and it has been removed from the latest Material 3 library release, you can still
manually reference it", and that material3 no longer depends on `material-icons-core`. The BOM
2026.08.00 POM still manages both icon artifacts at 1.7.8, so the dependency line above resolves
without a version. Google's replacement is per-icon vector drawables from https://fonts.google.com/icons
(Android tab) used via `painterResource`. Practical rule for us: use `Icons.Filled.*` /
`Icons.AutoMirrored.Filled.*` from 1.7.8 in v1 (compile-conservative, huge coverage), accept
the larger debug APK, and let R8 shrink release builds; move to vector drawables when the UI
stabilises.

### 6.4 Room 2.8.4 vs Room 3 (androidx.room3 3.0.2)

Room 3.0.2 (2026-08-26) is a new Maven group/package (`androidx.room3`, plugin id `androidx.room3`,
`room3 { schemaDirectory(...) }`): coroutine-only API, no `SupportSQLite`, Kotlin codegen only,
KSP-only, `SQLiteDriver` based. Room 2.8.4 stays the API with years of documentation and examples
(`RoomDatabase`, `Room.databaseBuilder`, `@Dao` with `suspend`/`Flow`), and Room 2 and 3 are
designed to coexist. For code written without a compiler, Room 2.8.4 is the lower-risk choice;
Room 3 is a later, mechanical migration ("mostly involves updating import references").

Room 2.8 specifics that affect the build files:
- Targets Kotlin 2.0+; KSP2 recommended (our KSP 2.3.11 is KSP2 — KSP1 is deprecated).
- `room-ktx` is blank → only `room-runtime` + `room-compiler` (via `ksp`).
- Schema export: KSP argument `room.schemaLocation` (used above) or the `androidx.room` Gradle
  plugin. I could not verify the 2.8.4 plugin (Nov 2025) against AGP 9's new DSL (Jan 2026+), so
  the plugin is not applied by default (see U2); the KSP argument path has no such dependency.

### 6.5 WorkManager, OkHttp, DataStore, Navigation, Lifecycle

- WorkManager: use `androidx.work:work-runtime:2.11.2`. The `-ktx` artifact's POM depends on nothing
  but `work-runtime` (coroutine APIs moved there in 2.9.0). `CoroutineWorker` lives in `work-runtime`.
- OkHttp 5.5.0: separate JVM/Android artifacts selected automatically through Gradle module metadata
  (`okhttp-android` needs `androidx.startup` 1.2.0, pulled transitively). Requires API 21+/Java 8+;
  needs kotlin-stdlib ≥ 2.2.21 and okio 3.18.1 (both satisfied). The chat client is plain
  `OkHttpClient` + SSE/streaming body reads; `okhttp-coroutines:5.5.0` exists if wanted (POM verified).
- DataStore Preferences 1.2.1: no API changes since 1.2.0; used for settings + the encrypted key blob.
- Navigation 2.10.0: type-safe routes (`@Serializable` route classes) need the serialization plugin
  in `:app` (applied). Navigation 3 (`androidx.navigation3`) is a separate, still-alpha family in the
  compose-samples catalog — not used.
- Lifecycle 2.11.0: `collectAsStateWithLifecycle` (runtime-compose) and `viewModel()` (viewmodel-compose)
  as usual; `ViewModel.onCleared` is now `@EmptySuper`.

---

## 7. Environment bootstrap (Windows; the developer machine currently has nothing installed)

### 7.1 JDK 17

Either of these winget IDs exists (verified in the winget-pkgs manifests):

```powershell
winget install --id EclipseAdoptium.Temurin.17.JDK -e
#   or:  winget install --id Microsoft.OpenJDK.17 -e
```

Then set `JAVA_HOME` to the install directory (e.g. `C:\Program Files\Eclipse Adoptium\jdk-17.0.x.y-hotspot`)
and make sure `%JAVA_HOME%\bin` is on `PATH`. `java -version` must print 17. sdkmanager
(cmdline-tools 15859902) also needs this JDK.

### 7.2 Android command-line tools and SDK packages (user's own steps for the licence)

Zip (Windows): https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip
(155.7 MB; SHA-256 as listed on https://developer.android.com/studio#command-line-tools-only:
`90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a` — verify on the page).

Layout that `sdkmanager` insists on (from the sdkmanager guide): the zip unpacks to a folder named
`cmdline-tools` containing `bin`, `lib`, `NOTICE.txt`, `source.properties`; that content must live
under `<sdk>\cmdline-tools\latest\`.

```powershell
$sdk = "C:\Android\sdk"
New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
Expand-Archive .\commandlinetools-win-15859902_latest.zip -DestinationPath "$env:TEMP\clt"
Move-Item "$env:TEMP\clt\cmdline-tools" "$sdk\cmdline-tools\latest"
# result: C:\Android\sdk\cmdline-tools\latest\bin\sdkmanager.bat

[Environment]::SetEnvironmentVariable("ANDROID_HOME", $sdk, "User")
$sm = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"

# 1) Licences — this is the user's own step (interactive, prints each licence, asks y/N):
& $sm --sdk_root=$sdk --licenses

# 2) Packages actually needed to build GymCoach:
& $sm --sdk_root=$sdk "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"

# 3) Optional, for an emulator later (large downloads; pick via `sdkmanager --list`):
#    & $sm --sdk_root=$sdk "emulator" "system-images;android-37.0;google_apis;x86_64"
#    (exact system-image path not verified here — read it from --list)
```

Package-name facts: the API-37 platform is `platforms;android-37.0` (the manifest has no bare
`android-37`); `build-tools;36.0.0` is AGP 9.4's default; `platform-tools` is unversioned.
`sdkmanager --list --channel=0` shows the stable inventory if anything differs by the time this is run.

Point the build at the SDK with either `ANDROID_HOME` (set above) or a `local.properties` in the
project root: `sdk.dir=C\:\\Android\\sdk`. Android Studio writes that file itself later.

### 7.3 Build order for the current situation

1. `:core` first: `gradlew :core:test` — needs only JDK 17 (+ network for dependencies).
2. `:app` compiles once the SDK packages above are installed: `gradlew :app:assembleDebug`.
   The first `:app` build is expected to happen inside Android Studio (Quail 4 | 2026.1.4 is the
   current stable per the download page), which will also offer an SDK-manager UI as fallback.

---

## 8. Gradle/Kotlin gotchas for this exact layout (`:core` JVM + `:app` Android)

G1. **One KGP version per build, and it must come from the root `plugins {}` block.** AGP 9.4.0
    drags in KGP 2.2.10 as a runtime dependency; the `kotlin.jvm`, `kotlin.plugin.compose` and
    `kotlin.plugin.serialization` markers (all 2.4.10) put KGP 2.4.10 on the same root classpath and
    Gradle picks the highest. Never declare a Kotlin plugin version only inside `core/build.gradle.kts`
    while another module gets KGP through AGP — that yields two KGP classloaders and errors like
    "Kotlin Gradle plugin loaded multiple times" or missing `kotlin {}` extension. The root file in 5.3
    handles this (`apply false` everywhere).

G2. **Do not apply `org.jetbrains.kotlin.android` in `:app`.** With AGP 9's built-in Kotlin it is
    "not compatible with the new DSL". `kotlinOptions {}` is gone; use
    `android { kotlin { compilerOptions { jvmTarget = … } } }` (built-in Kotlin also defaults
    `jvmTarget` to `compileOptions.targetCompatibility`). `org.jetbrains.kotlin.kapt` is incompatible
    too (KSP is what we use). The KMP plugin together with `com.android.application` is not allowed.

G3. **`jvmToolchain(17)` belongs in `:core` only.** In `:core` (KGP `kotlin.jvm`) it aligns Kotlin and
    Java compile tasks and avoids the "Inconsistent JVM-target compatibility" failure. In `:app` use
    `compileOptions` + `kotlin.compilerOptions.jvmTarget` as in 5.5; whether the built-in-Kotlin
    `kotlin {}` extension exposes `jvmToolchain` was not verified (U5), so don't rely on it there.
    Remember "The Kotlin compiler always runs on the JDK the Gradle daemon is running on" — the
    toolchain only governs what is compiled against/for. Without a local JDK 17 and without the foojay
    plugin, `jvmToolchain(17)` fails with "No matching toolchains found"; that is why 5.2 applies foojay.

G4. **kotlinx-serialization in a JVM module.** Apply `org.jetbrains.kotlin.plugin.serialization` in
    `:core` (where the `@Serializable` domain types live) *and* in `:app` (Navigation type-safe routes
    are `@Serializable` classes declared in `:app`). The runtime library `kotlinx-serialization-json`
    is multiplatform; the JVM variant resolves automatically in `:core`, the Android variant in `:app`.
    Do not annotate classes in `:app` with `@Serializable` unless the plugin is applied there — the
    failure mode is a confusing "Serializer for class X is not found" at runtime, not at compile time.

G5. **Kotlin 2.4 specifics.** K1 is gone — never set `languageVersion = "1.9"`. Default module names
    are now `{group}:{project_name}`; KSP < 2.3.10 choked on the ':' (we use 2.3.11). Do not pass Compose
    `featureFlags` (`StrongSkipping`, `IntrinsicRemember` are ERROR-level deprecated; they are on by
    default anyway). If `internal` members of `:core` must be visible to `:app` tests, that is a
    friend-path question — avoid by testing `:core` inside `:core`.

G6. **Compose compiler plugin.** Version string is the Kotlin version, always. `buildFeatures { compose = true }`
    is still required; `composeOptions.kotlinCompilerExtensionVersion` must not appear (Kotlin 2.x).
    Optional `composeCompiler { reportsDestination = layout.buildDirectory.dir("compose_compiler") }`
    exists but is not needed.

G7. **KSP + Room in a single-platform Android module.** `ksp(libs.androidx.room.compiler)` is the
    correct configuration ("Single-target JVM / Android — main source set"); the 2.3.10 deprecation
    of the global `ksp` configuration concerns KMP projects (`ksp.allow.all.target.configuration`).
    Variant-specific `kspDebug`/`kspRelease`/`kspTest` exist if ever needed. KSP2 is the default
    (`ksp.useKSP2` is deprecated/no-op). Room requires KSP; there is no kapt path anymore.

G8. **Room schema export input tracking.** The plain `ksp { arg("room.schemaLocation", …) }` works but
    Gradle cannot see the directory as a task input, so build-cache hits can skip schema regeneration.
    The official KSP-form is a `CommandLineArgumentProvider` class in the build script:

    ```kotlin
    import java.io.File
    class RoomSchemaArgProvider(
        @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) val schemaDir: File
    ) : CommandLineArgumentProvider {
        override fun asArguments(): Iterable<String> = listOf("room.schemaLocation=${schemaDir.path}")
    }
    ksp { arg(RoomSchemaArgProvider(File(projectDir, "schemas"))) }
    ```
    Caveat: `@InputDirectory` requires the directory to exist, hence the `app/schemas/.gitkeep`.
    Switch to this form (or to the `androidx.room` plugin, 2.8.4, `room { schemaDirectory("$projectDir/schemas") }`)
    once the project compiles in Android Studio.

G9. **material-icons-extended is frozen at 1.7.8** while everything else is 1.12.0. It is BOM-managed
    and Google's own samples still include it, but material3 1.4 no longer pulls `material-icons-core`,
    so if only a handful of icons are used, `material-icons-core` (also 1.7.8 via BOM) or vector
    drawables are lighter. If a future BOM drops the icon lines, pin `1.7.8` explicitly — that artifact
    will not get another version.

G10. **`packaging.resources.excludes`.** Duplicate `META-INF/AL2.0` and `META-INF/LGPL2.1` come from
     several Kotlin/AndroidX artifacts; the two excludes in 5.5 are the standard fix (also present in
     compose-samples). OkHttp 5's `publicsuffixes.gz` and `META-INF/versions/9/module-info.class`
     (Java 9 modules since 5.2) do not conflict and must not be excluded.

G11. **`android.useAndroidX=true` is mandatory**; `android.enableJetifier` no longer exists in AGP 9.
     `namespace` is mandatory (since AGP 8); `applicationId` is separate and may differ.

G12. **`compileSdk = 37` is an `Int` property** (the `compileSdkVersion(...)` functions are deprecated
     and removed in AGP 10). Minor versions (37.1/37.2) use the `compileSdk { version = release(37) { minorApiLevel = 1 } }`
     block; the SDK package names then carry the minor (`platforms;android-37.1`).

G13. **Gradle 9 Kotlin DSL** is compiled with the embedded Kotlin (2.4.0 in Gradle 9.7, Kotlin language
     level 2.2). Build scripts must not use Kotlin 2.4-only syntax; the skeletons above don't.
     `RepositoriesMode.FAIL_ON_PROJECT_REPOS` means no `repositories {}` in any module file.

G14. **Unit tests in `:app` run on a plain JVM** (no Android runtime): keep Android-touching code out of
     whatever is unit-tested there, or use Robolectric later. `kotlin("test")` without a version resolves
     only through KGP's dependency management; under built-in Kotlin in `:app` that inference was not
     verified, which is why 5.5 uses the explicit `kotlin-test-junit:2.4.10` + `junit:4.13.2` pair.

G15. **PowerShell redirection writes UTF-16** — when teeing Gradle output to a file for review use
     `gradlew ... | Tee-Object -FilePath build.log` and read it with a UTF-16-aware tool, or run under
     Git Bash. (Same lesson as the verimark project.)

G16. **AGP 9.4 warns about strict variant parity** between app and dynamic-feature modules and about
     the legacy Variant API (`android.newDsl.optOut`); neither applies to a plain two-module app.

---

## 9. Transitive requirement cross-check (why the set is internally consistent)

| Consumer | Needs | We provide |
|---|---|---|
| kotlinx-serialization-json 1.11.0 (JVM POM) | kotlin-stdlib ≥ 2.3.20 | 2.4.10 |
| kotlinx-coroutines 1.11.0 | kotlin-stdlib ≥ 2.2.20 | 2.4.10 |
| OkHttp 5.5.0 | kotlin-stdlib ≥ 2.2.21, okio 3.18.1, API 21+ | 2.4.10, okio transitively, minSdk 26 |
| Room 2.8.4 runtime | kotlin-stdlib ≥ 2.1.20, coroutines ≥ 1.8.1, androidx.sqlite 2.6.2, atomicfu 0.27.0, minSdk 23 | 2.4.10, 1.11.0, transitively, 26 |
| Compose 1.12.0 | compileSdk 37, AGP ≥ 9.2.0, kotlin-stdlib ≥ 2.1.20 | 37, 9.4.0, 2.4.10 |
| Material3 1.4.0 | compose foundation/ui ≥ 1.8.x, runtime ≥ 1.9.0 | 1.12.0 via BOM |
| navigation-compose 2.10.0 | compose ≥ 1.10.5, KGP ≥ 2.0, minSdk 24 | 1.12.0, 2.4.10, 26 |
| lifecycle-runtime-compose 2.11.0 | compose runtime ≥ 1.11.0 | 1.12.0 |
| activity-compose 1.13.0 | compose runtime ≥ 1.7.0 | 1.12.0 |
| WorkManager 2.11.2 | room-runtime ≥ 2.7.0, coroutines ≥ 1.9.0, minSdk 23 | 2.8.4, 1.11.0, 26 |
| AGP 9.4.0 | Gradle ≥ 9.6.0, JDK ≥ 17, Build-Tools ≥ 36.0.0, KGP ≥ 2.2.10 (runtime) | 9.6.1, 17, 36.0.0, 2.4.10 |
| KGP 2.4.10 | Gradle ≥ 7.6.3, AGP ≥ 8.5.2 | 9.6.1 (above tested max 9.5.0 — warnings possible), 9.4.0 (above tested max 9.1.0 — see U1) |
| KSP 2.3.11 | AGP 9 built-in Kotlin (supported since 2.3.1), Kotlin 2.4 module names (fixed 2.3.10) | ok |

Gradle picks the highest version whenever two constraints meet (`requires`, no `strictly`
constraints were seen in any inspected module file), so all the "≥" rows resolve upward to our pins.

---

## 10. Optional/extra artifacts recorded for later (all verified to exist)

- `androidx.compose.material3:material3-window-size-class` 1.4.0 (BOM-managed) — adaptive layouts.
- `androidx.compose.ui:ui-test-junit4` / `ui-test-manifest` 1.12.0 (BOM) — Compose UI tests.
- `androidx.test.ext:junit` 1.3.0, `androidx.test.espresso:espresso-core` 3.7.0 — instrumented tests.
- `com.squareup.okhttp3:okhttp-coroutines` 5.5.0, `logging-interceptor` 5.5.0.
- `androidx.room:room-testing` 2.8.4 (migration tests; listed in Room's POM constraints).
- `com.android.tools:desugar_jdk_libs` 2.1.5 / `desugar_jdk_libs_nio` 2.1.5 (Google Maven).
- `com.patrykandpatrick.vico:compose-m3` 3.3.1 (and 2.5.2 for the retired direct-androidx line).
- `androidx.room3:*` 3.0.2 — future migration target.

---

## 11. Upgrade watch-list (what to bump next and when)

- Kotlin 2.4.20 (currently RC3, 2026-09-02): when stable, bump `kotlin` only; expect its compatibility
  table to declare Gradle 9.6/9.7 and AGP 9.3/9.4 fully supported, which retires U1.
- Gradle 9.7.1: bump anytime; not required.
- WorkManager 2.12.0 (rc01 now), Compose BOM 2026.09.xx, lifecycle 2.12 (alpha), DataStore 1.3 (alpha),
  Material3 1.5 (alpha27) — all pre-release; do not pin.
- AGP 9.5 (alpha04) — alpha; and AGP 10 will remove `android.newDsl=false` and `compileSdkVersion(...)`;
  nothing in this skeleton relies on either.

---

## 12. Not verified / inferred (explicit)

U1. **The exact triple AGP 9.4.0 + KGP 2.4.10 + Gradle 9.6.1** has no single authoritative
    compatibility statement: Kotlin's table caps "fully supported" at Gradle 9.5.0 / AGP 9.1.0 for
    KGP 2.4.x, while AGP's docs only state minimums and explicitly allow a higher KGP. Evidence that
    it works: Google's compose-samples build AGP 9.3.1 + KGP 2.4.10 + KSP 2.3.11 + Gradle 9.5.0; Vico 3.3.1
    was built with Gradle 9.7.1 + Kotlin 2.4.10. Fallback in section 3 (AGP 9.3.2 + Gradle 9.5.1).
U2. **Room Gradle plugin 2.8.4 under AGP 9's new DSL** — not verified; therefore not applied.
    The KSP `room.schemaLocation` path is documented by Google and plugin-independent.
U3. **`compileSdk = 37` → `platforms/android-37.0` folder mapping** is inferred: Google's Android 17
    setup page says `compileSdk = 37`, the SDK manifest has only `android-37.0/37.1/37.2`, and
    compose-samples build with `compileSdk = 37` — so AGP must resolve 37 to the base minor. If AGP ever
    complains "platform android-37 not found", install `platforms;android-37.0` (already in the
    command) and, as a last resort, use `compileSdk { version = release(37) { minorApiLevel = 0 } }`.
U4. **AGP 9.4.0 contains the 9.3.2 lint-on-JDK-17 fix** — inferred from publish dates
    (9.3.2: 2026-08-24; 9.4.0: 2026-09-01); the 9.4.0 notes fetched did not list the issue id.
U5. Whether the built-in-Kotlin `kotlin {}` extension in an Android module supports `jvmToolchain(...)`
    — not verified; the skeleton avoids needing it.
U6. cmdline-tools SHA-256 was copied from the download page as rendered by the fetch tool, not
    recomputed from the file (downloading 156 MB was outside my remit). Compare with the page.
U7. Emulator system-image package name for API 37 (`system-images;android-37.0;google_apis;x86_64`)
    was not looked up; read it from `sdkmanager --list`.
U8. Winget package *versions* seen (Temurin 17.0.9.9, Microsoft 17.0.9.8) come from the manifest folder
    listing and may lag the actual latest 17.0.x; the IDs themselves are what matters.
U9. `material-icons-extended 1.7.8` binary compatibility with Compose 1.12 is asserted only by its
    continued presence in the 2026.08.00 BOM and in Google's samples; no explicit statement was found.
U10. Google Play target-SDK rule quoted (≥ 36 from 2026-08-31) is for Play distribution; a sideloaded
    personal build is unaffected either way.
