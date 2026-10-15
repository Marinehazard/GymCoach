# GymCoach — instructions for every Claude Code session (local or cloud)

GymCoach is a native Android personal-trainer app for one user (Johannes, a travelling service
technician). Equipment-aware training plans, gym presets, progress tracking, evidence-based
warm-up / cardio / nutrition / supplement guidance, and a Claude coach chat that can propose plan
changes the user confirms. Offline-first; only the coach chat needs a network.

## Read in this order
1. `docs/REQUIREMENTS.md` — what the user asked for (authoritative).
2. `docs/SPEC.md` (v1.2, ~8,300 lines) — the normative implementation spec. §9 is the work
   breakdown with **exclusive file ownership per work package**; §0.5 is the changelog.
3. `docs/research/R1..R4` — Claude backend policy, verified toolchain versions, training-science
   rule book (rule ids like VOL-01), exercise/equipment taxonomy. SPEC wins where they differ.
4. `docs/cloud/CLOUD_HANDOFF.md` — current state of play and the Phase-1 execution plan.

## Modules and build
- `:core` — pure Kotlin/JVM (kotlinx.serialization, coroutines). **No `android.*` imports, ever**
  (`NoAndroidImportsTest` enforces it). All domain logic, planner, knowledge base, coach protocol.
- `:app` — Android (Jetpack Compose, Room, DataStore, OkHttp). Included by `settings.gradle.kts`
  only when `local.properties` or `ANDROID_HOME` exists, so a machine without the Android SDK still
  builds `:core`.
- Build/test gate: `./gradlew :core:test` must stay green (Phase 0 baseline: 434 tests).
  JDK 17 is the default toolchain. On a machine with only JDK 21 (e.g. a Claude Code cloud VM) run
  `./gradlew :core:test -PgymcoachJdk=21`. First run downloads Gradle 9.6.1 + dependencies.
- `:app` cannot be compiled without the Android SDK. Do **not** download the SDK or accept the
  Android SDK licence unless Johannes says so explicitly in the current session; the
  `tools/setup-android-sdk.ps1` script exists for him to run on his PC.

## House rules (binding)
- Write only the files your work package owns (SPEC §9 table). Never edit Phase-0 contract files
  (`core/.../model/*`, `repo/*`, `json/*`, `explain/*`, `units/*`, `time/*`, `util/*`,
  `app/.../di/AppContainer.kt`, `ui/nav/*`, `ui/components/*`) from a package agent; describe the
  needed change instead. The integration step may edit anything under `core/` and `docs/SPEC.md`.
- Compile-conservative Kotlin (SPEC §1.6): explicit imports, `@OptIn` where Material3/Compose APIs
  are experimental, no reflection, kotlinx.serialization annotations on everything persisted or sent.
- Fix implementations, not expectations: never weaken a test to make it pass unless the test
  contradicts SPEC or a golden number is arithmetically wrong (then fix both and say so).
- Numbers the user logs are never altered by code; every automatic adjustment is shown with its
  rule id (`Explanation` / `Adjustment`).
- The Anthropic API key lives only in the Keystore-backed store; never log it, never write it to
  a file, never commit it. No secrets in `gradle.properties`, `local.properties`, or docs.
- Commit on a branch (`phase1/...`), never rewrite history on `main`, never force-push.
- Every `:app` change ships with a compile-risk list (which AndroidX/Compose/Room/OkHttp symbols it
  relies on and why they are believed to exist in the pinned versions of `gradle/libs.versions.toml`).

## Status (2026-09-30)
- Phase 0 done: build files, every `:core` contract of SPEC §2 (+ §4.1 `PlannerConstants`,
  §4.10 `E1rm`), `:app` shell (AppContainer, theme, nav, shared components, placeholder screens),
  six authored exercise-content batches (156 exercises). 434 `:core` tests green.
- Phase 1 pending: WP-02 knowledge assets + equipment, WP-03 planner, WP-04 progression/health,
  WP-05 coach core, WP-06 app data/network/keystore, WP-07 training UI, WP-08 setup/coach UI.
  Then core integration (all packages compile and test together) and an `:app` static
  consistency pass. See `docs/cloud/CLOUD_HANDOFF.md` for the ready-to-run plan.
