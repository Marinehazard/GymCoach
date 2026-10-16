# First build checklist

## 1. `:core` alone (any machine, no Android SDK, no Google Maven needed)

```
./gradlew :core:test                    # JDK 17 (default toolchain)
./gradlew :core:test -PgymcoachJdk=21   # JDK-21-only machine, e.g. a Claude Code cloud VM
```

Since Phase 2 (`phase2/profile`), `:core` builds without `dl.google.com`. Three build-file changes make
this work:

| File | Change | Why |
|---|---|---|
| `settings.gradle.kts` | Computes `gymcoachAppIncluded` (`local.properties`, `ANDROID_HOME` or `ANDROID_SDK_ROOT` present), stores it in `gradle.extra`, and includes `:app` only then (same condition as before). Both `google()` declarations get a content filter (`com.android*`, `com.google.android*`, `com.google.testing.platform*`, `androidx*`). | Lookups for non-Android groups never go to Google Maven. A blocked or unreachable `dl.google.com` then only matters when `:app` is built. |
| `build.gradle.kts` (root) | The `com.android.application` alias moved out of the root `plugins {}` block. AGP is put on the **root** classpath in `buildscript {}` only when `gymcoachAppIncluded` is true (`classpath(libs.android.gradle.plugin)`). | `plugins {}` cannot be conditional, but `buildscript {}` can. AGP still sits in the same root classloader as the Kotlin plugins, so R2 gotcha G1 holds: Gradle resolves AGP's KGP 2.2.10 and the markers' KGP 2.4.10 together and picks 2.4.10, and there is one KGP classloader. |
| `app/build.gradle.kts` | `id("com.android.application")` without a version, instead of the catalog alias. | The plugin is already on the root classpath; giving a version again would fail with "plugin is already on the classpath with an unknown version". |
| `gradle/libs.versions.toml` | New library `android-gradle-plugin = com.android.tools.build:gradle`, `version.ref = "agp"`. The `android-application` plugin alias stays. | The catalog remains the single source of the AGP version (9.4.0). |

Checked in the cloud VM (no SDK, Google Maven blocked with a proxy 403):
- `./gradlew :core:test -PgymcoachJdk=21` → BUILD SUCCESSFUL. No request goes to dl.google.com.
- `ANDROID_HOME=/nonexistent ./gradlew help` → fails resolving `com.android.tools.build:gradle:9.4.0` from
  dl.google.com. This is expected: it shows that the `:app` branch puts AGP on the root classpath.

## 2. First `:app` build (Johannes' PC)

1. Run `tools/setup-android-sdk.ps1 -Build`. This is your licence step; cloud runs never do it.
2. If the first Gradle sync fails in the plugin/classpath phase (for example "Kotlin Gradle plugin loaded
   multiple times", or `android {}` / `kotlin {}` not found in `app/build.gradle.kts`), go back to the Phase-1
   layout. This is a two-line revert:
   - root `plugins {}`: add `alias(libs.plugins.android.application) apply false` back and delete the
     `buildscript {}` block;
   - `app/build.gradle.kts`: `alias(libs.plugins.android.application)` instead of `id("com.android.application")`.
   After the revert, `:core` again needs Google Maven to configure.
3. Work through the top items in `docs/build/COMPILE_RISK_LIST.md`.
4. Commit the exported Room schema `app/schemas/.../1.json`.

## 3. Cloud VM notes (never committed)

- Maven Central answers intermittent HTTP 429, and sometimes a spurious 404, through the egress proxy.
  Phase 1 and Phase 2 used a local retrying mirror on `127.0.0.1:8765` (a session-scratchpad script),
  wired in through `~/.gradle/init.d/mirror.gradle.kts`. That is the user-level Gradle home, outside the
  repository, so the repository's build files never point at it. After a run with misses, use
  `--refresh-dependencies` to clear the cached misses.
- `crlf_comments.sse` is now marked `-text` in `.gitattributes`. Before this, the global `*.sse eol=lf` rule
  rewrote the CRLF fixture into LF on checkout, so the CRLF SSE parser test silently ran against LF input.
