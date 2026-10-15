# Cloud handoff — GymCoach Phase 1 in a Claude Code cloud session

Written 2026-09-30 by the local session that produced Phase 0. A cloud session starts with no
memory of that work; this file plus `CLAUDE.md` and `docs/SPEC.md` are its whole context.

## 1. State of play
| Area | State |
|---|---|
| Spec | `docs/SPEC.md` v1.2 (three critique rounds applied; §0.5 changelog). Treat as frozen. |
| `:core` contracts (WP-01) | Done. `./gradlew :core:test` → 434 tests, 0 failures (verified 2026-09-30 on JDK 17 and JDK 21). |
| `:app` shell (WP-01) | Done: `AppContainer`, theme, nav, 19 shared components, placeholder screens. Not compilable without the Android SDK. |
| Exercise content (WP-09…14) | Done: `core/src/main/resources/gymcoach/knowledge/exercise_content/batch_1..6.json`, 156 entries, status `draft`. Author review notes in `docs/review/CONTENT_REVIEW_NOTES.md`. |
| WP-02…WP-08 | **Not started.** A previous attempt died on the subscription session limit before writing anything. |
| Git | Single branch `main`; work on `phase1/<wp>` branches or one `phase1/all` branch and push. |

## 2. Environment
- Anthropic-hosted cloud VM: Ubuntu 24.04, 4 vCPU, 16 GB RAM, 30 GB disk, OpenJDK 21 + Gradle
  preinstalled. The project's Gradle wrapper downloads Gradle 9.6.1 itself (services.gradle.org is
  on the default **Trusted** allowlist, as are Maven Central, maven.google.com and the Gradle
  plugin portal). Nothing else is needed for `:core`.
- Use `./gradlew :core:test -PgymcoachJdk=21` (the VM has no JDK 17; the property switches the
  toolchain so Gradle does not try to download one).
- `:app` stays excluded (no SDK). Optional, only if Johannes wants the APK built in the cloud and
  has said so explicitly: set the environment's network access to **Custom** with the default list
  plus `dl.google.com`, and add a setup script that installs cmdline-tools
  (`commandlinetools-linux-*_latest.zip`), `platform-tools`, `platforms;android-37.0`,
  `build-tools;36.0.0`, exports `ANDROID_HOME`, and writes `local.properties`. Licence acceptance is
  his decision, not the agent's. JDK 17 would then be needed for `:app` bytecode compatibility
  (`apt-get install openjdk-17-jdk-headless` if the Ubuntu archive is reachable).
- Permission mode: **Auto** (unattended run). Model: Opus 5 for the main loop is sufficient; the
  workflow script passes `uiModel` to the UI packages to save budget.

## 3. Budget
$250 of promotional cloud-session credit (expires 2026-11-05). The four Phase-0/design fan-outs
consumed roughly 11 million subagent tokens; at Opus-tier rates that is on the order of $60–250.
Run leaner: Sonnet 5 for WP-07/WP-08 and the `:app` consistency pass, Opus 5 elsewhere, one
verification round. Check the credit balance between waves (Settings → Usage on claude.ai).

## 4. Execution plan (the script encodes it)
`docs/cloud/phase1-workflow.js` — a Claude Code dynamic-workflow script:
1. **Wave A** (parallel): WP-02 knowledge assets + equipment; WP-06 app data/network/keystore;
   WP-07 training UI; WP-08 setup/health/coach/settings UI; an independent verifier that checks
   SPEC v1.2 ↔ Phase-0 code drift and runs the baseline test.
2. **Wave B** (after WP-02 lands, parallel): WP-03 planner/realizer/substitution/ledger/mutations;
   WP-04 progression/readiness/check-ins/cardio/nutrition; WP-05 coach core (wire layer, tools,
   turn service).
3. **Integrate core**: up to three rounds until `:core:test` is green across all packages
   (ownership lifted for the integrator).
4. **App consistency**: three agents act as the compiler for `:app` (every import, signature,
   exhaustive `when`, `@OptIn`, resource) and write the consolidated compile-risk list.

Run it with the Workflow tool: `Workflow({scriptPath: "docs/cloud/phase1-workflow.js",
args: {gradle: "./gradlew -PgymcoachJdk=21", uiModel: "sonnet"}})`. If the Workflow tool is not
available in the session, execute the same plan with subagents (one per work package, same
prompts, same ownership rules), then the integration loop.

## 5. Prompt to paste into the new cloud session
```
Read CLAUDE.md and docs/cloud/CLOUD_HANDOFF.md first, then docs/SPEC.md §0, §1, §9.
1. Run ./gradlew :core:test -PgymcoachJdk=21 and confirm 434 tests pass (baseline).
2. Run the workflow docs/cloud/phase1-workflow.js with the Workflow tool
   (args: {gradle: "./gradlew -PgymcoachJdk=21", uiModel: "sonnet"}). If the Workflow tool is
   unavailable, execute the same plan with subagents, one work package per agent, respecting
   the SPEC §9 file ownership, then the integration loop.
3. When ./gradlew :core:test -PgymcoachJdk=21 is green across all packages, commit on branch
   phase1/all with a message per work package and push the branch.
4. Write docs/build/COMPILE_RISK_LIST.md (consolidated from every :app package) and
   docs/cloud/PHASE1_REPORT.md: per-package status, test counts, remaining failures, spec drift,
   open decisions for Johannes. Do not download the Android SDK or accept its licence.
```

## 6. Getting the result back
Open the session at claude.ai/code → diff view → **Create PR** (or leave the pushed branch).
Locally: `git fetch && git checkout phase1/all`, then `./gradlew :core:test`. The first `:app`
compile happens on Johannes' PC after `tools/setup-android-sdk.ps1 -Build` (his licence step).
