# GymCoach — Implementation Specification (SPEC.md)

Version 1.2 — 2026-09-05 (revision round 2: Phase-0 contract freeze — DLD-03 flow, `WeekRollResult`
ladder channel, `PlanInputs.deloadTriggersActive`, strength split rows, confidence normalisation,
`PlanCoordinator.plannerProposals`/`recordDeloadDecision`; changelog in §0.5. Round 1 (v1.1): contract,
science and coverage fixes; §2.14–2.15, §4.1 groups, §6.6 mapping table and §6.7 chain semantics
added). Status: **normative**. This document is the single source of truth for
the first implementation of GymCoach. Twelve independent agents implement the work packages of
§9 seeing only this file plus the four research files (`docs/research/R1-claude-backend.md`,
`R2-android-toolchain.md`, `R3-training-science.md`, `R4-exercise-taxonomy.md`). Where this
document and a research file disagree, this document wins; where this document is silent, the
research file is the reference and the implementer notes the gap in the PR.

Conventions used throughout:

- `R1 §3.5`, `R3 VOL-04`, `R4 §2.2` cite the research files. Rule IDs (`VOL-04`, `PROG-01`, …)
  are R3 §10 IDs and are used verbatim in code, JSON, UI chips and the coach prompt.
- Kotlin shown in fenced blocks under a `// path` comment is **copied verbatim** into that file
  by the owning work package. Exception: a block whose heading or first comment says
  **"signature contract"** is not copied verbatim — the implementer keeps every public name,
  parameter list and return type exactly, adds the `package` line, explicit imports (HR-1;
  `import com.gymcoach.core.model.*` inside such a block is illustrative only) and the bodies.
  One fenced block = one file; a block that lists several `// path` comments is several files.
  Everything else (prose, pseudocode, tables) is normative behaviour the owning package
  implements.
- "Sacred content" = REQUIREMENTS.md's discipline: numbers the user logs are never altered;
  every automatic adjustment is shown with its rule ID and its trigger (R3 POL-01).
- `[weak]` after a number means an R3 *weak/convention* value: it lives in `PlannerConstants`
  (§4.1), carries provenance in `rules.json` (§3.5), and is user-adjustable in Settings ▸
  Advanced. `[unverified]` means R3 §12 / R2 §12 / R1 §4 could not verify it live; it is still
  shipped, tagged `Confidence.UNVERIFIED`.

---

## 0. Decision record and verification ledger

### 0.1 Which proposal supplies what

Three design proposals (`docs/design/proposal-A.md` "Planner-first", `proposal-B.md`
"gym-floor-UX-first", `proposal-C.md` "robustness-and-compile-safety-first") were judged.
This specification is **C's skeleton** (module layout, house rules, wire layer, contract v1,
Phase-0 interface freeze, append-only transcript, `PlannerConstants`, `PlanValidator`) with
**A's planner** (pure-function plan, append-only overrides, week-level `SetAllocator`, fractional
ledger with intra-week carryover, per-gym `ProgressionKey`, `MutationValidator`,
`TRAVEL_MAINTENANCE` week mode, worked examples as golden fixtures) and **B's gym-floor UX**
(active-session screen, quick readiness, gym editor, swap sheet, adjustment log with undo,
Knowledge screen, empty/error master table, manual gym-floor test script, `SystemPromptTest`,
offline queued draft) grafted in. B's foreground-service rest timer, WorkManager reminders and
SAF export/import are a **v1.1 module** (§9.4) that lands after `:app` has compiled once.

### 0.2 Judges' hard errors and how this document resolves each

| # | Error (source) | Resolution in this document |
|---|---|---|
| H1 | A: `propose_change_volume` used `"minimum": -6, "maximum": 6`; B: `"maxLength": 400`, `"maxItems": 6` — numerical/string/array constraints are unsupported in strict schemas (400) | All tool schemas (§6.6) use only: basic types, `enum` (strings/integers), `required` (subset), `additionalProperties: false`, `format: "date"`, `description`. Numeric bounds are validated on device by `ToolContract.validate` and returned as `is_error` tool results. Re-verified live 2026-09-05 (§0.3). |
| H2 | B: enums without `@SerialName`; kotlinx decodes by exact constant name, `JsonNamingStrategy` covers properties only | Every enum in §2 carries an explicit `@SerialName` for every constant. `decodeEnumsCaseInsensitive` is **not** used. |
| H3 | B: `GoalPrescription(repLo, repHi, …)` cannot decode R4's `"range": [3, 6]`; A/B `ParamSpec`/`PresetItem` cannot decode R4's `"default": false` / `"params": {"max_kg": 20}` | `GoalPrescription.range: Band` with `BandSerializer` (`[lo, hi]`); `ParamSpec.default: JsonElement?`; `PresetItem.params: Map<String, JsonElement>`; `GymItem.params: Map<String, JsonElement>` (§2.3, §2.4). Assets are decoded with `CoreJson.assets` (`ignoreUnknownKeys = false`), so R4 §6.3 test 7 catches dropped keys. |
| H4 | A: `ModalBottomSheet` without `@OptIn` policy → compile error | House rule HR-3 (§1.6): every file using Material 3 sheets/app bars carries `@file:OptIn(ExperimentalMaterial3Api::class)`. |
| H5 | B: keep-tail rolling summary edits the prefix (cache invalidation; fails the preserved-thinking history-editing check) | Append-only transcript; compaction = new conversation seeded with a ≤ 600-token summary; never while proposals are undecided (§6.9). Verified live 2026-09-05: the prefix check is enforced by default for accounts created on/after 2026-08-31 00:00 UTC. |
| H6 | C: `ProgressionState` keyed by `exerciseId` only | `ProgressionKey(exerciseId, gymScope)`; machine/cable/smith/sled loads are gym-scoped (§2.7, §4.9). |
| H7 | C: `FAT_LOSS` → `general_fitness` rep profile | `FAT_LOSS` → `hypertrophy` profile (R3 DEF-06 defines fat_loss as hypertrophy-style training in a deficit; the LOAD-03 grouping with general_fitness differs only by one rep on barbell lifts) with VOL-09 volume (§2.2, §4.4). |
| H8 | C: `Limitation` bare enum, no pain/side, no ANKLE | `Limitation(region, side, pain0to10, since, active, notes)`; `LimitationRegion` includes `ANKLE` and `NECK`; INJ-00 uses `pain > 3` (not `≥ 3`, B's error) (§2.4, §4.3). |
| H9 | C: `E1rm.pctOfMaxForReps` left as `TODO()`; e1RM HIGH only for 2–6 reps | Full table + interpolation written out (§4.10); confidence HIGH for `1 ≤ reps ≤ 10`, LOW above (E1RM-02). |
| H10 | B: quick-readiness example 58 (correct: 56); cardio HR 112–131 mismatched | Worked numbers recomputed (§5.1: quick mode 38/68 → 56; §5.4: 38 y, RHR 58 → zone 2 109–127 bpm by HRmax, 107–131 by HRR). |
| H11 | A: `layoffMonths ≥ 12 → NOVICE` without recency | DEF-01: novice if `layoffMonths ≥ 12 && monthsRetrainedSinceLayoff < 6` (§4.2). |
| H12 | A: `data class Override` shadows `java.lang.Override` | Renamed `PlanOverride` (§2.8). |
| H13 | C: `AppContainer.backend(NONE)` returned a real backend | `BackendMode.NONE` → `NotConfiguredBackend` yielding `BackendError.NotConfigured` (§1.5). |
| H14 | A: NUT-04 adaptive TDEE example needs kcal logs the app did not collect | Optional daily kcal entry exists (`NutritionLog.kcal`); adaptive TDEE runs only with ≥ 10 kcal entries in the 14-day window, otherwise the NUT-16 rate loop alone (§5.5). |
| H15 | A: RIR-05 +1 audit missing from the progression decision | `ProgressionEngine` applies `effRir = rir + 1` for sets > 12 reps before every PROG-01/PROG-04 test (§4.9). |
| H16 | A/C: strength "moderate day" at 6–10 reps contradicts LOAD-03 | Second weekly exposure of a main lift is the **back-off day**: 4–8 reps at 85 % of the heavy-day load [weak] (§4.5). |
| H17 | A: week-1 RIR +1 stacked on Red +2 → RIR 5 | RIR modifier stack is capped: final prescribed RIR ≤ 4 (DEF-04), except the fixed deload value (§4.7). |
| H18 | B: imperial entries stored as kg only | `EnteredWeight(entered, unit, kg)` / `EnteredLength(entered, unit, cm)` are the canonical logged types (§2.3). |
| H19 | B: `front_delts` target unfillable | Front delts are TIER_3 (no target, advisory direct cap) (§4.4). |
| H20 | C: reactive deload without the "≥ 5 days" condition | `DeloadPolicy.reactive` requires ≥ 2 DLD-03 triggers present for ≥ 5 days (§4.11). |
| H21 | C: no AMRAP calibration set (RIR-05) | `SetKind.CALIBRATION`; scheduled every 3rd week on one machine/isolation exercise (§4.7 step 5). |
| H22 | C: lower-barbell increment "5 kg while load < 60 kg" | PROG-02: lower barbell +5 kg, +2.5 kg once the load exceeds 100 kg or for novice/female users (§4.9). |
| H23 | All: VOL-04 band applied to small muscles | Tier convention stated as weak; the under-target report is the normal, quiet outcome (§4.4). |
| H24 | B: readiness reminder at 21:00 the evening before | `AppSettings.readinessReminderTime` defaults to 07:30 on the morning of a training day (v1.1 notification, §9.4; v1 shows an in-app card) (§2.10). |

### 0.3 Live verification performed for this document (2026-09-05)

| Fact | Source | Result |
|---|---|---|
| Strict/structured-output JSON Schema subset | https://platform.claude.com/docs/en/build-with-claude/structured-outputs#json-schema-limitations | Supported: all basic types, `enum` (strings, numbers, bools, nulls), `const`, `anyOf`/`allOf` (limits), `$ref`/`$defs`, `default`, `required` and `additionalProperties` (must be `false`), string formats incl. `date`, array `minItems` 0/1. Not supported: recursion, numerical constraints (`minimum`, `maximum`, `multipleOf`), string constraints (`minLength`, `maxLength`), other array constraints, `additionalProperties` ≠ `false`. |
| `required` may be a subset | https://platform.claude.com/docs/en/agents-and-tools/tool-use/strict-tool-use | Official example: `properties: {location, unit}`, `required: ["location"]`, `additionalProperties: false`; integer `enum` example `passengers: {"type":"integer","enum":[1,…,10]}`; `format: "date"` example. |
| Preserved-thinking prefix check | https://platform.claude.com/docs/en/build-with-claude/thinking#preserved-thinking | "A block stays valid only while the top-level `system` prompt, the `tools`, and the messages before it are unchanged. If any of them changes … the API rejects the request with a 400 error or drops the invalid blocks." "The API enforces the prefix check by default for accounts created on or after August 31, 2026, 00:00 UTC … make your integration append-only now." "Modified thinking blocks are rejected with a 400 error." `redacted_thinking` must be passed back unchanged. |
| kotlinx.serialization enum decoding | https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/json.md | "Json uses exact Kotlin enum values names for decoding by default"; `decodeEnumsCaseInsensitive` default false (experimental); naming strategy applies to properties; `ignoreUnknownKeys` default false; `explicitNulls` default true; `encodeDefaults` default false. |

Everything else (versions, wire contract, policy text, rule numbers, taxonomy) is inherited
from R1–R4 as verified on 2026-09-04 and is not re-verified here. Items R1 §4, R2 §12, R3 §12
and R4 §7 list as unverified remain unverified (§12).

### 0.4 Reading order for implementers

1. §1 (module layout, house rules, Gradle) — everyone.
2. §2 (domain contracts) — everyone; copy verbatim, do not edit outside WP-01.
3. Your work package row in §9, then the sections it cites.
4. §10 (tests) for the acceptance bar; §11 for traceability.

### 0.5 Changelog v1.1 → v1.2 (2026-09-05, contract freeze before Phase 1)

Every item below is reflected in the Phase-0 code on disk (`:core` model files, `AppContainer`);
downstream packages code against the v1.2 text.

1. **DLD-03 flow** (§4.11, §7.6, §8.6): `PlanCoordinator.realizeToday()` is pure (no DLD-03
   evaluation, writes nothing); `deloadSuggestion(today)` stays a pure read; new
   `recordDeloadDecision(today)` records the active triggers' `"<kind>@<onset>"` into
   `AppSettings.deloadTriggersProposed` — called by `TodayViewModel.onAcceptDeload` and the new
   `onDismissDeload`; `rollWeekIfDue()` keeps recording when it appends the `ScheduleDeload` card.
   The Today card persists until accepted, dismissed or the week rolls; nothing else records an onset.
2. **PLAT-02 ladder channel** (§2.9, §4.11, §7.6): `WeekRollResult.states` (advanced
   `ProgressionState`s) and `WeekRollResult.ladderNotes` (one `Explanation(PLAT-02)` per advance);
   `rollWeekIfDue` saves the states and records one `Adjustment` per note before recompute.
3. **Invariant 17 scope** (§3.4, §4.5, §4.16): applies only to split rows with a strength variant
   (`upper_lower_4`, `ul_ppl_5`, `ul_6`, `upper_lower_full_3`); `full_body_1/2/3` and `travel_2`
   require an `Explanation(FRQ-02)` on the mesocycle instead; new `PlanValidatorTest` case.
4. **Confidence normalisation** (§3.5): composite R3 tags → the weakest component; printed tag kept
   verbatim in `note`; same rule for `SupplementEntry.evidence` and `NutritionTip.confidence`.
5. **Strength split rows** (§3.4, §3.9 rule 10, §4.5): `templates.json.splits` carries the
   STRENGTH rows as separate `SplitTemplate` entries (`for_goals: ["strength"]`); `SplitSelector`
   selects rows by (days, goal, training age) — no code-side template substitution; `ul_6` is the
   strength row; `ppl_6` also serves ENDURANCE_SUPPORT; the 5-day FRQ-03 fallback is `upper_lower_4`
   with an `Explanation(SPLIT-01)`.
6. **Planner proposal cards** (§7.6, §8.6, §8.10): `PlanCoordinator.plannerProposals(today)` — pure,
   memoised per `inputsHash` — feeds `TodayUiState.plannerProposals` / `PlanUiState.proposals`.
7. **VOL-08 ↔ DLD-03** (§2.9, §4.4, §7.6): `PlanInputs.deloadTriggersActive` (filled by
   `PlanCoordinator.buildInputs()` from `DeloadPolicy.signals(...).active(...)`); `TargetPolicy`
   holds every ramp while it is non-empty (`weekTargets(..., deloadTriggersActive)`, §2.15).
8. Cross-reference drift fixed (§1.2, §1.4, §3 file table).
9. Coverage: WP-06 compile-time dependencies (§9, §9.2); `ContextInputs.gym` fallback (§6.5, §6.7);
   every exercise/equipment/drill id used in §3.4, §4.17, §5.7 and §5.8 was checked against R4 §2.3–2.5
   and §5.6–5.11 (all 156 + 77 resolve; `leg_swings` is a §3.6 drill id, footnoted in §4.17).
10. Phase-0 code reconciled with §2: `WeeklyCheckin.holds`, `SessionPlan.schemaVersion`,
    `AppContainer.deloadPolicy(...)`/`deloadPolicyFactory` restored from the SPEC text (they were
    missing on disk); the two new fields of items 2 and 7 added to `PlanInputs.kt` with their
    round-trip and serial-name tests.


### 0.6 Integration round 1 (2026-10-15, `:core` after Phase 1)

Contract additions and corrections found while integrating WP-02…WP-05 (code on disk is the truth):

1. Equipment count **78** (R4 §2.3 prints 78 rows; §3 file table, §3.3, §7.5 and the WP-02 row updated).
2. §3.1 `rep_overrides` example: `rir`/`sets` are copied from the profile's goal entry (the old `[2,0]` was not a
   valid `Band`). §3.5 `ROUNDING` example: `user_adjustable: true` (follows the normalised weak tag).
3. §4.5 TIER_2 band for STRENGTH / GENERAL_FITNESS is `round(0.6 × 8–12)` = **5–7** (was printed 4–7).
4. §6.3 / §2.15 additive WP-05 API documented: `SseParser.flush(): List<SseEvent>`,
   `StreamAccumulator.takeCompletedToolUses()` / `toolUses()` with `ToolUseBlock`, `DeltaInputs.gym`,
   `CoachContextBuilder.delta(inputs, proposalResults)`.
5. §10.2 `RuleCoverageTest`: `RuleIds.ALL` is excluded from the `RuleIds.X` constant matches. Every `RuleIds`
   constant is now referenced in main: content-table checks in `KnowledgeValidator` (CHK-01 bank Q1–Q15;
   NUT-02/03/12/13/14 tips; SUP-01…SUP-10 rows and the tier-C SUP-11.n rows; NUT-18 constant-free policy entry),
   `ProgressSeries.trainingLoadSpike` (RDY-06 advisory note, `rdy06SpikeFactor` × 4-week mean),
   `WeightTrend.explanation` (NUT-15 / NUT-14 (4) noise text), `MacroTargets` NUT-07 recomposition note
   (hypertrophy goal and novice, ≥ 3-month layoff with < 3 months retrained, or body fat > 20 % men / 30 % women),
   `TimeConcessions.advancedTechniqueAllowed` (TIME-03 eligibility; drop sets / rest-pause are not prescribed
   automatically in v1).
6. Open, not regenerated here: the §4.17 E1/E2/E3/G5 golden tables differ from the pinned planner output in the
   places listed by WP-03 (Stage 3–5 allocation tie-breaks, E3 DB-bench ramp 16 kg, E2 glute bridge 0.511, E2 warm-up
   drills `cat_cow`/`bodyweight_squat`, G5 travel session 40 min); the golden files under `core/src/test` are normative.

---

## 1. Architecture, module layout, house rules, build files

### 1.1 Modules and dependency direction

```
:app  (Android: Compose UI, Room, DataStore, Keystore, OkHttp, manual DI)   ──►  :core
:core (pure Kotlin/JVM: model, knowledge, planner, progression, readiness, cardio, nutrition,
       check-ins, mobility, coach wire + contract + turn service, tests)      ──►  kotlin-stdlib,
                                                                                 kotlinx-coroutines-core,
                                                                                 kotlinx-serialization-json
```

`:core` never imports `android.*` or `androidx.*` (enforced by `NoAndroidImportsTest`, §10.2). It
never touches the wall clock, the file system or the network directly: it receives a `Clock`, a
`KnowledgeSource` and a `ClaudeBackend` (interfaces in §2.11) from `:app`. `:core` also has no
OkHttp: the transport is `:app`'s, the protocol is `:core`'s.

Toolchain (R2 §1, verified 2026-09-04): JDK 17, Gradle 9.6.1, AGP 9.4.0, Kotlin 2.4.10 (KGP,
Compose compiler plugin, serialization plugin, kotlin-test), KSP 2.3.11, kotlinx-coroutines 1.11.0,
kotlinx-serialization-json 1.11.0, Compose BOM 2026.08.00 (ui/foundation 1.12.0, material3 1.4.0,
material-icons-extended 1.7.8), activity-compose 1.13.0, navigation-compose 2.10.0, lifecycle
2.11.0, Room 2.8.4, DataStore Preferences 1.2.1, OkHttp 5.5.0, core-ktx 1.19.0, JUnit 4.13.2;
compileSdk = targetSdk = 37, minSdk = 26. WorkManager 2.11.2 is declared in the catalogue but not
added to `:app` until the v1.1 module (§9.4). Fallback that changes only two strings if the
AGP 9.4.0 + KGP 2.4.10 + Gradle 9.6.1 triple misbehaves in Android Studio (R2 §3, U1):
`agp = "9.3.2"` and `distributionUrl=…/gradle-9.5.1-bin.zip` (fetch its sha256 from
https://services.gradle.org/distributions/gradle-9.5.1-bin.zip.sha256).

### 1.2 Repository layout

```
GymCoach/
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  gradle/libs.versions.toml
  gradle/wrapper/gradle-wrapper.properties        (+ gradle-wrapper.jar, gradlew, gradlew.bat: generated, see R2 §5.7)
  core/
    build.gradle.kts
    src/main/kotlin/com/gymcoach/core/...         (§1.3)
    src/main/resources/gymcoach/knowledge/...     (§3: JSON assets — single source of truth)
    src/main/resources/gymcoach/coach/...         (§6.4: system_prompt_v1.txt, compaction_prompt_v1.txt)
    src/test/kotlin/com/gymcoach/core/...
    src/test/resources/golden/...                 (§10.4)
    src/test/resources/sse/...                    (§10.4)
  app/
    build.gradle.kts
    proguard-rules.pro
    schemas/.gitkeep
    src/main/AndroidManifest.xml
    src/main/res/xml/{backup_rules,data_extraction_rules}.xml
    src/main/res/values/{strings,strings_training,strings_setup,strings_backend_policy,themes}.xml
                                                  (strings = app_name + §8.4 component strings, WP-01;
                                                   strings_training = WP-07 screens; strings_setup = WP-08 screens)
    src/main/res/drawable/ic_launcher.xml
    src/main/kotlin/com/gymcoach/app/...          (§1.4)
    src/test/kotlin/com/gymcoach/app/...
  docs/
    SPEC.md (this file), REQUIREMENTS.md, research/, design/
    build/FIRST_BUILD_CHECKLIST.md                (Appendix A)
    build/COMPILE_RISK_LIST.md                    (assembled in Phase 2, §9.3)
    bridge/PROTOCOL.md                            (§6.13)
    test/GYM_FLOOR_SCRIPT.md                      (Appendix B)
```

### 1.3 `:core` package map (`com.gymcoach.core.*`)

| Package | Contents | Owner (§9) |
|---|---|---|
| `model` | Every domain type and enum (§2.2–2.10) plus the view rows of §2.14 (`Views.kt`: `ChartPoint`, `ChartSeries`, `MuscleBandRow`, `SupplementAdvice`). | WP-01 |
| `json` | `CoreJson` (three configured `Json` instances), `LocalDateIsoSerializer`, `InstantIsoSerializer`, `BandSerializer`. | WP-01 |
| `repo` | Repository interfaces, `ClaudeBackend`, `BackendError`, `ApiKeyStore`, `KnowledgeSource`, `NetworkMonitor` (§2.11). | WP-01 |
| `explain` | `RuleIds` (every R3 §10 ID as `const val`), `Explanations` helpers. | WP-01 |
| `units` | kg↔lb, cm↔in, display formatting, `EnteredWeight` helpers. | WP-01 |
| `time` | `Clock`, `SystemClock`, `FixedClock`, `Weeks` (ISO week arithmetic). | WP-01 |
| `util` | `IdGen` (UUID strings), `Hashing` (SHA-256 hex). | WP-01 |
| `knowledge` | `KnowledgeBase`, `KnowledgeLoader`, `ClasspathKnowledgeSource`, `KnowledgeValidator`, `RuleBook`, `RpLandmarks`, `SessionTemplates`. | WP-02 |
| `equipment` | `EquipmentResolver` (R4 §2.2 algebra), `GymParams`, `IncrementResolver` (R4 §5.4), `LoadRounder`, `Presets`. | WP-02 |
| `volume` | `VolumeAccountant`, `WeeklyLedger`, `MuscleTiers`, `TargetPolicy`. | WP-03 |
| `planner` | `PlannerConstants` (**file owned by WP-01**, Phase 0, §4.1 — every WP-03/WP-04 class takes it), `Planner` (facade), `Normalizer`, `MesocycleBuilder`, `SplitSelector`, `ExerciseSelector`, `SetAllocator`, `TimeModel`, `AgePolicy`, `PlanValidator`. | WP-03 (except `PlannerConstants.kt`) |
| `planner.realize` | `SessionRealizer`, `ReadinessModifiers`, `LayoffPolicy`, `TimeConcessions`, `WarmupGenerator`, `CardioFinisher`, `RirStack`, `LoadZones`. | WP-03 |
| `substitution` | `SubstitutionEngine`, `LoadTransfer`. | WP-03 |
| `mobility` | `MobilityPlanner`. | WP-03 |
| `mutation` | `MutationValidator` (+ `ValidationResult`), `OverrideResolver`, `PlanDiffer` (produces the §2.9 `PlanDiff`). | WP-03 |
| `progression` | `ProgressionEngine`, `E1rm` (**file owned by WP-01**, Phase 0, §4.10 — called by WP-03's realizer), `PlateauDetector`, `DeloadPolicy`, `ProgressSeries`. | WP-04 (except `E1rm.kt`) |
| `readiness` | `ReadinessScorer`, `RedFlags`, `SleepRules`. | WP-04 |
| `checkin` | `CheckinBank`, `CheckinEvaluator`. | WP-04 |
| `cardio` | `CardioPlanner`, `HeartRateZones`, `HiitProtocols`. | WP-04 |
| `nutrition` | `EnergyEstimator`, `TdeeAdapter`, `WeightTrend`, `MacroTargets`, `SupplementAdvisor`. | WP-04 |
| `coach.wire` | `WireTypes`, `RequestBuilder`, `SseParser`, `StreamAccumulator`. | WP-05 |
| `coach` | `SystemPrompt`, `CoachContextBuilder`, `ToolSchemas`, `ToolContract`, `ToolExecutor`, `ProposalApplier`, `TranscriptCompactor`, `CoachTurnService` (+ `CoachTurnInputs`, §2.15), `TurnEvent`, `PricingTable`, `SpendMeter`, `TokenEstimator`, `RedFlagScanner`, `SupplementCaveat`. | WP-05 |

### 1.4 `:app` package map (`com.gymcoach.app.*`)

| Package | Contents | Owner |
|---|---|---|
| (root) | `GymCoachApp : Application` (holds `AppContainer`), `MainActivity`. | WP-01 |
| `di` | `AppContainer` — manual DI, lazy singletons (§1.5). No Hilt/Dagger/Koin. | WP-01 |
| `ui.theme`, `ui.nav`, `ui.components` | Theme, routes, `AppNavHost`, `BottomBar`, shared composables (§8.2–8.4). | WP-01 |
| `data.db` | `AppDatabase`, entities, DAOs, `Migrations` (§7.1). | WP-06 |
| `data.repo` | Room-backed implementations of every `core.repo` interface + `Mappers`, `EnumNames`, `DocVersions`, `InMemoryUnreadableDocs` (§7.2). | WP-06 |
| `data.prefs` | `DataStores` (the two `preferencesDataStore` delegates), `SettingsDataStore` (§7.3). | WP-06 |
| `data.secure` | `KeystoreCipher`, `KeystoreApiKeyStore` (§7.4). | WP-06 |
| `data.assets` | `AssetKnowledgeSource` (fallback loader, §7.5). | WP-06 |
| `coordinator` | `PlanCoordinator` — plan recompute / realize / rollover / session-close orchestration over the `core.repo` interfaces (§7.6). | WP-06 |
| `network` | `AndroidNetworkMonitor` (`ConnectivityManager` → `core.repo.NetworkMonitor`, §6.10). | WP-06 |
| `network.claude` | `DirectApiBackend`, `RetryPolicy`, `HttpErrorMapper`, `SseBodyReader` (§6.10). | WP-06 |
| `network.bridge` | `BridgeBackend`, `NotConfiguredBackend` (§6.13). | WP-06 |
| `ui.today`, `ui.readiness`, `ui.session`, `ui.plan`, `ui.progress` | Training screens + ViewModels (§8.6–8.11). | WP-07 |
| `ui.onboarding`, `ui.profile`, `ui.gyms`, `ui.library`, `ui.checkin`, `ui.nutrition`, `ui.knowledge`, `ui.more` | Setup and health screens (§8.5, §8.12–8.16). | WP-08 |
| `ui.coach`, `ui.settings`, `ui.usage` | Coach chat, proposal cards, settings, usage (§8.17–8.19). | WP-08 |
| `session`, `notify`, `work`, `backup` | v1.1 only (§9.4): foreground-service rest timer, notifications, WorkManager reminders, SAF export/import. | WP-V1.1 |

Dependency rules (grep-checkable before the first Android build):

```
ui.*        ──►  ViewModels  ──►  core.repo interfaces + core services + coordinator.PlanCoordinator   (never data.db, okhttp3, androidx.room)
data.repo   ──►  core.model + core.repo + data.db
data.prefs  ──►  core.model + core.repo (+ androidx.datastore)
coordinator ──►  core.repo interfaces + core.planner/progression/knowledge services + core.model   (never data.db, never di)
network.*   ──►  core.coach.wire + core.repo (ClaudeBackend, NetworkMonitor, ApiKeyStore)
di.AppContainer ──► everything (the only place that knows concrete classes)
ViewModels never import android.content.Context.
ViewModels reach PlanCoordinator ONLY through container.planCoordinator (never construct one).
```

### 1.5 Manual DI — `AppContainer` (Phase-0 frozen file; names every class other packages must provide)

```kotlin
// app/src/main/kotlin/com/gymcoach/app/di/AppContainer.kt
package com.gymcoach.app.di

import android.content.Context
import androidx.room.Room
import com.gymcoach.app.coordinator.PlanCoordinator
import com.gymcoach.app.data.assets.AssetKnowledgeSource
import com.gymcoach.app.data.db.AppDatabase
import com.gymcoach.app.data.db.Migrations
import com.gymcoach.app.data.prefs.SettingsDataStore
import com.gymcoach.app.data.repo.InMemoryUnreadableDocs
import com.gymcoach.app.data.repo.RoomCoachRepository
import com.gymcoach.app.data.repo.RoomGymRepository
import com.gymcoach.app.data.repo.RoomMetricsRepository
import com.gymcoach.app.data.repo.RoomPlanRepository
import com.gymcoach.app.data.repo.RoomProfileRepository
import com.gymcoach.app.data.repo.RoomProgressionRepository
import com.gymcoach.app.data.repo.RoomWorkoutRepository
import com.gymcoach.app.data.secure.KeystoreApiKeyStore
import com.gymcoach.app.network.AndroidNetworkMonitor
import com.gymcoach.app.network.bridge.BridgeBackend
import com.gymcoach.app.network.bridge.NotConfiguredBackend
import com.gymcoach.app.network.claude.DirectApiBackend
import com.gymcoach.core.cardio.CardioPlanner
import com.gymcoach.core.checkin.CheckinBank
import com.gymcoach.core.checkin.CheckinEvaluator
import com.gymcoach.core.coach.CoachTurnService
import com.gymcoach.core.coach.ProposalApplier
import com.gymcoach.core.coach.SpendMeter
import com.gymcoach.core.coach.ToolExecutor
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.ClasspathKnowledgeSource
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.knowledge.KnowledgeLoader
import com.gymcoach.core.model.BackendMode
import com.gymcoach.core.mutation.MutationValidator
import com.gymcoach.core.nutrition.EnergyEstimator
import com.gymcoach.core.nutrition.MacroTargets
import com.gymcoach.core.nutrition.SupplementAdvisor
import com.gymcoach.core.nutrition.TdeeAdapter
import com.gymcoach.core.nutrition.WeightTrend
import com.gymcoach.core.planner.Planner
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.progression.DeloadPolicy
import com.gymcoach.core.progression.ProgressSeries
import com.gymcoach.core.progression.ProgressionEngine
import com.gymcoach.core.readiness.ReadinessScorer
import com.gymcoach.core.repo.ApiKeyStore
import com.gymcoach.core.repo.ClaudeBackend
import com.gymcoach.core.repo.KnowledgeSource
import com.gymcoach.core.repo.NetworkMonitor
import com.gymcoach.core.repo.SettingsRepository
import com.gymcoach.core.repo.UnreadableDocsRepository
import com.gymcoach.core.substitution.SubstitutionEngine
import com.gymcoach.core.time.Clock
import com.gymcoach.core.time.SystemClock
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Single composition root. Everything is `by lazy` so Application start stays cheap and a
 * construction failure surfaces at first use with a stack trace naming the member.
 * Phase-0 frozen: other work packages provide classes with EXACTLY these names and constructors.
 */
class AppContainer(private val appContext: Context) {

    val clock: Clock = SystemClock()

    /** Class-path resources first (§7.5); assets fallback if the resource is absent. */
    val knowledgeSource: KnowledgeSource by lazy {
        val classpath = ClasspathKnowledgeSource()
        if (classpath.exists("exercises.json")) classpath else AssetKnowledgeSource(appContext.assets)
    }
    val knowledge: KnowledgeBase by lazy { KnowledgeLoader(CoreJson.assets).load(knowledgeSource) }

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "gymcoach.db")
            .addMigrations(*Migrations.all)
            .build()
    }

    val settingsRepository: SettingsRepository by lazy { SettingsDataStore(appContext) }
    val apiKeyStore: ApiKeyStore by lazy { KeystoreApiKeyStore(appContext) }
    /** ConnectivityManager + NET_CAPABILITY_VALIDATED behind the :core interface (§6.10). */
    val networkMonitor: NetworkMonitor by lazy { AndroidNetworkMonitor(appContext) }
    /** Rows with a newer schema_version than this build knows (§7.2); MainActivity shows the banner. */
    val unreadableDocs: UnreadableDocsRepository by lazy { InMemoryUnreadableDocs() }

    val profileRepository by lazy { RoomProfileRepository(database.profileDao(), CoreJson.stored, unreadableDocs) }
    val gymRepository by lazy { RoomGymRepository(database.gymDao(), CoreJson.stored, unreadableDocs) }
    val planRepository by lazy { RoomPlanRepository(database.planDao(), CoreJson.stored, unreadableDocs) }
    val workoutRepository by lazy { RoomWorkoutRepository(database.workoutDao(), CoreJson.stored, unreadableDocs) }
    val progressionRepository by lazy { RoomProgressionRepository(database.progressionDao(), CoreJson.stored, unreadableDocs) }
    val metricsRepository by lazy { RoomMetricsRepository(database.metricsDao(), CoreJson.stored, unreadableDocs) }
    val coachRepository by lazy { RoomCoachRepository(database.coachDao(), CoreJson.stored, unreadableDocs) }

    /** Defaults merged with the user's Settings ▸ Advanced overrides at read time (§4.1). */
    fun plannerConstants(overridesJson: String): PlannerConstants =
        PlannerConstants.withOverrides(PlannerConstants.DEFAULT, overridesJson, CoreJson.stored)

    fun planner(constants: PlannerConstants): Planner = Planner(knowledge, constants)
    fun progressionEngine(constants: PlannerConstants): ProgressionEngine = ProgressionEngine(knowledge, constants)
    /** DLD-03 signals/reactive proposals (§4.11); run by PlanCoordinator in rollWeekIfDue, deloadSuggestion, recordDeloadDecision and buildInputs — never in realizeToday (§7.6). */
    fun deloadPolicy(constants: PlannerConstants): DeloadPolicy = DeloadPolicy(knowledge, constants)
    fun readinessScorer(constants: PlannerConstants): ReadinessScorer = ReadinessScorer(constants)
    fun substitutionEngine(constants: PlannerConstants): SubstitutionEngine = SubstitutionEngine(knowledge, constants)
    fun mutationValidator(constants: PlannerConstants): MutationValidator = MutationValidator(knowledge, constants)
    fun cardioPlanner(constants: PlannerConstants): CardioPlanner = CardioPlanner(knowledge, constants)
    fun energyEstimator(constants: PlannerConstants): EnergyEstimator = EnergyEstimator(constants)
    fun tdeeAdapter(constants: PlannerConstants): TdeeAdapter = TdeeAdapter(constants)
    fun weightTrend(constants: PlannerConstants): WeightTrend = WeightTrend(constants)
    fun macroTargets(constants: PlannerConstants): MacroTargets = MacroTargets(constants)
    fun supplementAdvisor(constants: PlannerConstants): SupplementAdvisor = SupplementAdvisor(knowledge, constants)
    fun checkinBank(): CheckinBank = CheckinBank(knowledge)
    fun checkinEvaluator(constants: PlannerConstants): CheckinEvaluator = CheckinEvaluator(knowledge, constants)
    val progressSeries: ProgressSeries by lazy { ProgressSeries(knowledge) }

    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)     // SSE ping cadence undocumented (R1 §4.4)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    /** Backend chosen per turn from settings, so switching in Settings needs no restart. */
    fun backend(mode: BackendMode): ClaudeBackend = when (mode) {
        BackendMode.NONE -> NotConfiguredBackend()
        BackendMode.DIRECT_API -> DirectApiBackend(okHttp, apiKeyStore)
        BackendMode.HOME_BRIDGE -> BridgeBackend(okHttp, settingsRepository, apiKeyStore)
    }

    val spendMeter: SpendMeter by lazy { SpendMeter(coachRepository, clock) }
    val toolExecutor: ToolExecutor by lazy {
        ToolExecutor(knowledge, workoutRepository, progressionRepository, gymRepository, planRepository)
    }
    val proposalApplier: ProposalApplier by lazy { ProposalApplier(planRepository, coachRepository, clock) }
    val coachTurnService: CoachTurnService by lazy {
        CoachTurnService(
            knowledge = knowledge,
            profile = profileRepository,
            gyms = gymRepository,
            plans = planRepository,
            workouts = workoutRepository,
            progression = progressionRepository,
            metrics = metricsRepository,
            coach = coachRepository,
            settings = settingsRepository,
            backendFactory = ::backend,
            toolExecutor = toolExecutor,
            spend = spendMeter,
            network = networkMonitor,
            clock = clock,
        )
    }

    /** The only PlanCoordinator instance; ViewModels never construct their own (§1.4, §7.6). */
    val planCoordinator: PlanCoordinator by lazy {
        PlanCoordinator(
            plans = planRepository,
            profile = profileRepository,
            gyms = gymRepository,
            workouts = workoutRepository,
            progression = progressionRepository,
            metrics = metricsRepository,
            settings = settingsRepository,
            knowledge = knowledge,
            plannerFactory = ::planner,
            engineFactory = ::progressionEngine,
            deloadPolicyFactory = ::deloadPolicy,
            clock = clock,
        )
    }
}
```

Access from Compose (no `CompositionLocal` of our own):

```kotlin
val container = (LocalContext.current.applicationContext as GymCoachApp).container
val vm: TodayViewModel = viewModel { TodayViewModel(container) }
```

`viewModel(initializer: CreationExtras.() -> VM)` (`lifecycle-viewmodel-compose` 2.11.0) was
verified in the AndroidX sources by proposal C on 2026-09-05; fallback is
`viewModelFactory { initializer { … } }` passed as `factory =`. ViewModels take the whole
`AppContainer` in their constructor (one parameter; they read the members they need) — this keeps
every ViewModel constructor identical and removes a class of blind-compile mistakes.

### 1.6 Compile-conservative house rules (binding for every work package)

- **HR-1** Explicit imports only; no wildcard imports; `import … as` only when two types share a simple name.
- **HR-2** Only APIs that exist in the pinned versions and have existed for ≥ 2 years, unless R1/R2/§0.3 verified them. Every other `:app` symbol goes on the WP's **compile-risk list** (§9.3).
- **HR-3** Every `:app` file that calls `TopAppBar`, `ModalBottomSheet`, `rememberModalBottomSheetState`, `SegmentedButton`, `DatePicker` or `TimePicker` carries `@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)` as its first line. Over-opting is a warning; under-opting is an error. `TimePicker`, `DatePicker`, `SegmentedButton`, `SearchBar`, `PullToRefreshBox` are **not used** (times via two steppers, dates via `MonthGrid`, choices via `FilterChip` rows, search via `OutlinedTextField`).
- **HR-4** Icons: `Icons.Filled.*` and `Icons.AutoMirrored.Filled.*` only, from the BOM-managed `material-icons-extended` 1.7.8 (R2 §6.3). No custom vector assets except `ic_launcher.xml`.
- **HR-5** No `kotlin-reflect`, no `Class.forName`, no annotation processing except Room via KSP.
- **HR-6** No context parameters, no `@JvmInline value class` in Room entities or `@Serializable` types crossing the wire, no `sealed interface` polymorphism except `PlanMutation` (sealed class, `classDiscriminator = "type"`, every subclass `@SerialName`ed).
- **HR-7** Coroutines: `Dispatchers.IO` inside repository implementations; `viewModelScope` in ViewModels; `Flow` from DAOs; `StateFlow` to UI via `collectAsStateWithLifecycle()`. Planner/progression calls run inside `withContext(Dispatchers.Default)`.
- **HR-8** Room entities use only `Long`, `Int`, `Double`, `String`, `Boolean` columns. No `@TypeConverters`. Dates are ISO-8601 strings; instants are epoch milliseconds (`Long`); documents are JSON strings.
- **HR-9** Serialization: `@Serializable` data classes with default values for every optional field; stored documents use `CoreJson.stored` (`ignoreUnknownKeys = true`); knowledge assets use `CoreJson.assets` (`ignoreUnknownKeys = false`); the wire uses `CoreJson.wire`. Never a named companion object on a `@Serializable` class.
- **HR-10** Never `!!` in `:app`; `checkNotNull(x) { "field name" }` instead.
- **HR-11** Every `when` over an enum is exhaustive (no `else`).
- **HR-12** No Gradle configuration cache, no KSP flags, no build-script Kotlin beyond §1.7.
- **HR-13** UI state is one `data class XxxUiState` per screen; screens are pure functions of state + callbacks; events are methods on the ViewModel (`fun onXxx(...)`).
- **HR-14** No `LaunchedEffect(Unit)` that starts network work; network work starts only from explicit user actions.
- **HR-15** Every string the coach or the planner shows the user comes through `Explanation.message` / `Adjustment.summary` or a chat text block; no ad-hoc concatenation of rule IDs in UI code.
- **HR-16** `:core` tests: JUnit 4 + `kotlin-test-junit` assertions; `kotlinx-coroutines-test` `runTest` for suspend code; hand-written fakes, no mocking library.
- **HR-17** Logged numbers are written only by the session/logging ViewModels through `WorkoutRepository.addSet` / `supersedeSet` / `MetricsRepository.add*`; `:core` never constructs a `SetLog` except in tests.
- **HR-18** Determinism: no `Random`, no wall clock inside `:core` planner/progression code; ties broken by knowledge order (`batch`, then `id`); floating point rounded to 2 decimals at ledger boundaries. `IdGen` is **never** called from `planner`, `planner.realize`, `substitution`, `volume` or `mutation` — ids there are derived from inputs (§2.7: `Mesocycle.id`, `PlannedSession.id`, `SessionPlan.id`, `ExerciseBlock.blockId`); `progression` may use `IdGen` for `Adjustment.id` only (tests ignore adjustment ids).
- **HR-19** Every `:app` screen file ≤ 400 lines; split into `XxxScreen.kt` + `XxxSections.kt` when longer (small files are easier to fix blind).
- **HR-20** Sacred display: a logged value renders from `EnteredWeight.entered` + `unit` exactly as typed; the converted value is shown only when the display unit differs, prefixed "≈".

### 1.7 Build files (copy verbatim)

#### `gradle/libs.versions.toml`

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

#### `settings.gradle.kts`

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
    // Version 1.0.0 verified on the Gradle Plugin Portal (R2 §2.1).
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

#### Root `build.gradle.kts`

```kotlin
// Root build file: declare every plugin once (apply false) so that a single plugin
// classloader holds ONE Kotlin Gradle Plugin version (2.4.10) for the whole build (R2 G1).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
```

#### `core/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // Sets the toolchain for BOTH Kotlin and Java compile tasks in this module (R2 G3).
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
    useJUnit()
    // Golden files regenerate only on explicit request (§10.4).
    systemProperty("gymcoach.regenerateGolden", System.getProperty("gymcoach.regenerateGolden") ?: "false")
}
```

#### `app/build.gradle.kts`

```kotlin
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
```

#### `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true

# Required by AGP for AndroidX artifacts
android.useAndroidX=true

# AGP 9 defaults, written out so the intent is explicit (do NOT set these to false)
android.builtInKotlin=true
android.newDsl=true

kotlin.code.style=official
```

#### `gradle/wrapper/gradle-wrapper.properties`

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

`gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` are generated once with a
locally unzipped Gradle 9.6.1 (`gradle wrapper --gradle-version 9.6.1 --distribution-type bin`,
R2 §5.7) and committed. `app/schemas/.gitkeep` and `core/src/main/resources/gymcoach/knowledge/.gitkeep`
exist before the first build.

#### `.gitignore`

```
.gradle/
build/
*/build/
local.properties
.idea/
*.iml
.kotlin/
```

#### `app/src/main/AndroidManifest.xml` (v1)

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <application
        android:name=".GymCoachApp"
        android:label="@string/app_name"
        android:icon="@drawable/ic_launcher"
        android:allowBackup="true"
        android:fullBackupContent="@xml/backup_rules"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:supportsRtl="true"
        android:theme="@style/Theme.GymCoach">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`res/values/themes.xml`: `<style name="Theme.GymCoach" parent="android:Theme.Material.NoActionBar" />`
(platform theme; no AppCompat). `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`
exclude `datastore/secrets.preferences_pb` from cloud backup and device transfer (§7.4).
`ic_launcher.xml` is a simple vector drawable (a filled circle with a bar shape; no binary asset).

### 1.8 Threading and state

- Repositories: `suspend` for one-shot, `Flow` for observation; implementations switch to
  `Dispatchers.IO` so callers never think about threads.
- Planner/progression: pure, synchronous, no I/O; ViewModels call them inside
  `withContext(Dispatchers.Default)`.
- Coach turn: `CoachTurnService.send()` returns `Flow<TurnEvent>`; the ViewModel collects it and
  updates `StateFlow<CoachUiState>`; cancelling the collection cancels the OkHttp call
  (`DirectApiBackend` wraps the call in `callbackFlow` and calls `call.cancel()` in `awaitClose`).

---
## 2. Domain contracts — `:core` source (copy verbatim; owned by WP-01)

All files compile against Kotlin 2.4.10 + kotlinx-serialization 1.11.0 with explicit imports and no
experimental features. JSON field names for knowledge assets follow the R4 schemas (snake_case via
`@SerialName`); user-data documents use the Kotlin property names as written (camelCase) — the
`@SerialName`s below are exactly the ones that appear in stored documents. Ids of user data are
UUID strings (`IdGen.newId()`); ids of knowledge rows are R4 slugs. Masses are kg, lengths cm,
durations seconds, dates `java.time.LocalDate`, instants `java.time.Instant`.

### 2.1 JSON configuration and serializers

```kotlin
// core/src/main/kotlin/com/gymcoach/core/json/Serializers.kt
package com.gymcoach.core.json

import com.gymcoach.core.model.Band
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** ISO-8601 calendar date, e.g. "2026-09-05". */
object LocalDateIsoSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) { encoder.encodeString(value.toString()) }
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

/** ISO-8601 instant in UTC, e.g. "2026-09-05T06:40:00Z". */
object InstantIsoSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) { encoder.encodeString(value.toString()) }
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/** R4 schemas encode integer ranges as the JSON array [lo, hi] ("intRange"). */
object BandSerializer : KSerializer<Band> {
    private val delegate: KSerializer<List<Int>> = ListSerializer(Int.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Band) { delegate.serialize(encoder, listOf(value.lo, value.hi)) }
    override fun deserialize(decoder: Decoder): Band {
        val list = delegate.deserialize(decoder)
        require(list.size == 2) { "Band must have exactly two integers, got $list" }
        return Band(list[0], list[1])
    }
}
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/json/CoreJson.kt
package com.gymcoach.core.json

import kotlinx.serialization.json.Json

/**
 * Three Json instances, chosen by data class:
 *  - [stored]: user documents persisted by :app (plan snapshots, mutations, chat content). Lenient
 *    to unknown keys so a newer document version never crashes an older reader.
 *  - [assets]: knowledge JSON shipped with the app. STRICT (ignoreUnknownKeys = false) so a typo or a
 *    silently dropped key fails the :core tests (R4 §6.3 test 7; sacred content applied to data files).
 *  - [wire]: Messages-API bodies. explicitNulls = false so optional fields are omitted, encodeDefaults =
 *    true so `stream: true` and `cache_control` defaults are always sent.
 */
object CoreJson {
    val stored: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
        prettyPrint = false
    }
    val assets: Json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
        prettyPrint = false
    }
    val wire: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }
}
```

### 2.2 Enums (`model/Enums.kt`) — every constant carries an explicit `@SerialName`

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/Enums.kt
package com.gymcoach.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Sex { @SerialName("male") MALE, @SerialName("female") FEMALE }

/**
 * R3 DEF-06 goals. FAT_LOSS uses the hypertrophy rep profile (DEF-06: "hypertrophy-style training in
 * deficit") with VOL-09 volume; ENDURANCE_SUPPORT uses the endurance profile. Decision record H7.
 */
@Serializable
enum class Goal {
    @SerialName("hypertrophy") HYPERTROPHY,
    @SerialName("strength") STRENGTH,
    @SerialName("general_fitness") GENERAL_FITNESS,
    @SerialName("fat_loss") FAT_LOSS,
    @SerialName("endurance_support") ENDURANCE_SUPPORT;

    /** Key into RepProfile.byGoal (R4 §5.2 has four goal keys). */
    val repProfileKey: String
        get() = when (this) {
            HYPERTROPHY -> "hypertrophy"
            STRENGTH -> "strength"
            GENERAL_FITNESS -> "general_fitness"
            FAT_LOSS -> "hypertrophy"
            ENDURANCE_SUPPORT -> "endurance"
        }
}

/** R3 DEF-01. Derived by Normalizer, never entered directly. */
@Serializable
enum class TrainingAge { @SerialName("novice") NOVICE, @SerialName("intermediate") INTERMEDIATE, @SerialName("advanced") ADVANCED }

@Serializable enum class UnitSystem { @SerialName("metric") METRIC, @SerialName("imperial") IMPERIAL }
@Serializable enum class WeightUnit { @SerialName("kg") KG, @SerialName("lb") LB }
@Serializable enum class LengthUnit { @SerialName("cm") CM, @SerialName("in") IN }

/** R3 §0.1 confidence tags plus UNVERIFIED for R3 §12 items. */
@Serializable
enum class Confidence {
    @SerialName("strong") STRONG, @SerialName("moderate") MODERATE, @SerialName("weak") WEAK,
    @SerialName("contested") CONTESTED, @SerialName("unverified") UNVERIFIED
}

/** R4 §3 — 29 movement patterns. */
@Serializable
enum class MovementPattern {
    @SerialName("squat") SQUAT, @SerialName("hinge") HINGE, @SerialName("lunge_split") LUNGE_SPLIT,
    @SerialName("horizontal_push") HORIZONTAL_PUSH, @SerialName("incline_push") INCLINE_PUSH,
    @SerialName("vertical_push") VERTICAL_PUSH, @SerialName("chest_fly") CHEST_FLY,
    @SerialName("horizontal_pull") HORIZONTAL_PULL, @SerialName("vertical_pull") VERTICAL_PULL,
    @SerialName("elbow_flexion") ELBOW_FLEXION, @SerialName("elbow_extension") ELBOW_EXTENSION,
    @SerialName("shoulder_abduction") SHOULDER_ABDUCTION, @SerialName("shoulder_er_rear_delt") SHOULDER_ER_REAR_DELT,
    @SerialName("scapular_elevation") SCAPULAR_ELEVATION, @SerialName("hip_extension_isolation") HIP_EXTENSION_ISOLATION,
    @SerialName("hip_abduction") HIP_ABDUCTION, @SerialName("hip_adduction") HIP_ADDUCTION,
    @SerialName("knee_flexion_isolation") KNEE_FLEXION_ISOLATION, @SerialName("knee_extension_isolation") KNEE_EXTENSION_ISOLATION,
    @SerialName("calf") CALF, @SerialName("core_anti_extension") CORE_ANTI_EXTENSION,
    @SerialName("core_anti_rotation") CORE_ANTI_ROTATION, @SerialName("core_flexion") CORE_FLEXION,
    @SerialName("core_rotation") CORE_ROTATION, @SerialName("carry") CARRY, @SerialName("grip") GRIP,
    @SerialName("power") POWER, @SerialName("cardio") CARDIO, @SerialName("mobility") MOBILITY
}

/** R4 §4.1 — 21 muscles. */
@Serializable
enum class Muscle {
    @SerialName("chest") CHEST, @SerialName("front_delts") FRONT_DELTS, @SerialName("side_delts") SIDE_DELTS,
    @SerialName("rear_delts") REAR_DELTS, @SerialName("rotator_cuff") ROTATOR_CUFF, @SerialName("lats") LATS,
    @SerialName("upper_back") UPPER_BACK, @SerialName("traps_upper") TRAPS_UPPER, @SerialName("lower_back") LOWER_BACK,
    @SerialName("biceps") BICEPS, @SerialName("triceps") TRICEPS, @SerialName("forearms") FOREARMS,
    @SerialName("abs") ABS, @SerialName("obliques") OBLIQUES, @SerialName("hip_flexors") HIP_FLEXORS,
    @SerialName("quads") QUADS, @SerialName("hamstrings") HAMSTRINGS, @SerialName("glutes") GLUTES,
    @SerialName("glute_med") GLUTE_MED, @SerialName("adductors") ADDUCTORS, @SerialName("calves") CALVES
}

/** R4 §6.2 loadType. Gym-scoped types have no transferable load between gyms (E1RM-04, TRV-04). */
@Serializable
enum class LoadType {
    @SerialName("barbell") BARBELL, @SerialName("dumbbell") DUMBBELL, @SerialName("machine") MACHINE,
    @SerialName("cable") CABLE, @SerialName("bodyweight") BODYWEIGHT, @SerialName("band") BAND,
    @SerialName("kettlebell") KETTLEBELL, @SerialName("smith") SMITH, @SerialName("sled") SLED,
    @SerialName("medicine_ball") MEDICINE_BALL, @SerialName("cardio") CARDIO, @SerialName("none") NONE;

    val isGymScoped: Boolean get() = this == MACHINE || this == CABLE || this == SMITH || this == SLED
}

@Serializable enum class Difficulty { @SerialName("beginner") BEGINNER, @SerialName("intermediate") INTERMEDIATE, @SerialName("advanced") ADVANCED }

@Serializable
enum class EquipmentCategory {
    @SerialName("free_weights") FREE_WEIGHTS, @SerialName("racks_benches") RACKS_BENCHES, @SerialName("cables") CABLES,
    @SerialName("machines_upper") MACHINES_UPPER, @SerialName("machines_lower") MACHINES_LOWER,
    @SerialName("bodyweight_stations") BODYWEIGHT_STATIONS, @SerialName("cardio") CARDIO, @SerialName("accessories") ACCESSORIES
}

@Serializable enum class GroupKind { @SerialName("any_of") ANY_OF, @SerialName("all_of") ALL_OF }
@Serializable enum class RepUnit { @SerialName("reps") REPS, @SerialName("seconds") SECONDS, @SerialName("metres") METRES, @SerialName("minutes") MINUTES }

@Serializable
enum class IncrementKind {
    @SerialName("kg") KG, @SerialName("band_level") BAND_LEVEL, @SerialName("reps") REPS, @SerialName("time") TIME,
    @SerialName("distance") DISTANCE, @SerialName("box_height") BOX_HEIGHT, @SerialName("none") NONE
}

@Serializable enum class IncrementDirection { @SerialName("load") LOAD, @SerialName("assist") ASSIST }

@Serializable
enum class ProgressionKind {
    @SerialName("linear_load") LINEAR_LOAD, @SerialName("double_progression") DOUBLE_PROGRESSION,
    @SerialName("rep_progression") REP_PROGRESSION, @SerialName("time_progression") TIME_PROGRESSION,
    @SerialName("band_progression") BAND_PROGRESSION, @SerialName("distance_or_time") DISTANCE_OR_TIME,
    @SerialName("none") NONE
}

@Serializable enum class ContentStatus { @SerialName("todo") TODO, @SerialName("draft") DRAFT, @SerialName("reviewed") REVIEWED }

/** INJ-01..04 regions plus HIP/ANKLE/NECK (MOB-07 references ankle limitations). */
@Serializable
enum class LimitationRegion {
    @SerialName("knee") KNEE, @SerialName("shoulder") SHOULDER, @SerialName("low_back") LOW_BACK,
    @SerialName("elbow_wrist") ELBOW_WRIST, @SerialName("hip") HIP, @SerialName("ankle") ANKLE,
    @SerialName("neck") NECK, @SerialName("other") OTHER
}

@Serializable enum class Side { @SerialName("left") LEFT, @SerialName("right") RIGHT, @SerialName("both") BOTH }

/** RED-03 gate items; any member present gates HIIT, MAX_STRENGTH zone and supplement doses. */
@Serializable
enum class MedicalFlag {
    @SerialName("heart_disease") HEART_DISEASE, @SerialName("kidney_disease") KIDNEY_DISEASE,
    @SerialName("liver_disease") LIVER_DISEASE, @SerialName("pregnancy") PREGNANCY,
    @SerialName("diabetes_on_medication") DIABETES_ON_MEDICATION,
    @SerialName("uncontrolled_hypertension") UNCONTROLLED_HYPERTENSION,
    @SerialName("doctor_restriction") DOCTOR_RESTRICTION, @SerialName("other") OTHER
}

/** Planner muscle tiers (§4.4, convention, weak). */
@Serializable enum class MuscleTier { @SerialName("tier_1") TIER_1, @SerialName("tier_2") TIER_2, @SerialName("tier_3") TIER_3 }

/** SPLIT-01 rows. */
@Serializable
enum class SplitKind {
    @SerialName("full_body_1") FULL_BODY_1, @SerialName("full_body_2") FULL_BODY_2, @SerialName("full_body_3") FULL_BODY_3,
    @SerialName("upper_lower_full_3") UPPER_LOWER_FULL_3, @SerialName("upper_lower_4") UPPER_LOWER_4,
    @SerialName("ul_ppl_5") UL_PPL_5, @SerialName("ppl_6") PPL_6, @SerialName("ul_6") UL_6,
    @SerialName("travel_2") TRAVEL_2
}

@Serializable
enum class WeekMode {
    @SerialName("normal") NORMAL, @SerialName("deload") DELOAD, @SerialName("reentry") REENTRY,
    @SerialName("travel_maintenance") TRAVEL_MAINTENANCE
}

@Serializable
enum class SlotRole {
    @SerialName("primary") PRIMARY, @SerialName("secondary") SECONDARY, @SerialName("accessory") ACCESSORY,
    @SerialName("isolation") ISOLATION, @SerialName("core") CORE, @SerialName("optional") OPTIONAL
}

@Serializable
enum class SessionMode {
    @SerialName("as_planned") AS_PLANNED, @SerialName("amber") AMBER, @SerialName("red") RED,
    @SerialName("alternative_z2_mobility") ALTERNATIVE_Z2_MOBILITY, @SerialName("deload") DELOAD,
    @SerialName("travel") TRAVEL
}

/** DEF-04 / WU-03: only WORKING, AMRAP, CALIBRATION and DROP count as volume. */
@Serializable
enum class SetKind {
    @SerialName("ramp") RAMP, @SerialName("working") WORKING, @SerialName("amrap") AMRAP,
    @SerialName("calibration") CALIBRATION, @SerialName("drop") DROP
}

@Serializable enum class ReadinessBand { @SerialName("green") GREEN, @SerialName("amber") AMBER, @SerialName("red") RED }

@Serializable
enum class ProgressionStatus {
    @SerialName("unknown") UNKNOWN, @SerialName("calibrating") CALIBRATING, @SerialName("progressing") PROGRESSING,
    @SerialName("plateau") PLATEAU, @SerialName("regressing") REGRESSING, @SerialName("deloading") DELOADING,
    @SerialName("reentry") REENTRY
}

@Serializable
enum class AdjustmentKind {
    @SerialName("readiness") READINESS, @SerialName("layoff") LAYOFF, @SerialName("equipment") EQUIPMENT,
    @SerialName("time_budget") TIME_BUDGET, @SerialName("progression") PROGRESSION,
    @SerialName("autoregulation") AUTOREGULATION, @SerialName("deload") DELOAD, @SerialName("volume") VOLUME,
    @SerialName("rounding") ROUNDING, @SerialName("safety") SAFETY, @SerialName("override") OVERRIDE,
    @SerialName("nutrition") NUTRITION, @SerialName("checkin") CHECKIN, @SerialName("user") USER
}

@Serializable enum class OverrideSource { @SerialName("user") USER, @SerialName("chat") CHAT, @SerialName("planner") PLANNER }

@Serializable
enum class MutationScope {
    @SerialName("today") TODAY, @SerialName("rest_of_mesocycle") REST_OF_MESOCYCLE, @SerialName("permanent") PERMANENT
}

/** CAR-02/05. */
@Serializable
enum class CardioKind {
    @SerialName("zone2") ZONE2, @SerialName("walk") WALK, @SerialName("hiit_4x4") HIIT_4X4,
    @SerialName("hiit_10x1") HIIT_10X1, @SerialName("sit") SIT
}

@Serializable
enum class CardioPlacement { @SerialName("finisher") FINISHER, @SerialName("separate_day") SEPARATE_DAY, @SerialName("off_day") OFF_DAY }

/** LOAD-04 zones. */
@Serializable
enum class LoadZone {
    @SerialName("max_strength") MAX_STRENGTH, @SerialName("heavy") HEAVY, @SerialName("moderate") MODERATE,
    @SerialName("light") LIGHT, @SerialName("power") POWER
}

/** R3 DEF-07 classes for increments and RIR. */
@Serializable
enum class ExerciseClass {
    @SerialName("lower_barbell") LOWER_BARBELL, @SerialName("upper_barbell") UPPER_BARBELL,
    @SerialName("dumbbell") DUMBBELL, @SerialName("machine") MACHINE, @SerialName("cable") CABLE,
    @SerialName("bodyweight") BODYWEIGHT, @SerialName("isolation") ISOLATION
}

/** E1RM-02: HIGH for 1..10 reps (≈ 3 % error); LOW above 10 (display only, never for decisions). */
@Serializable enum class E1rmConfidence { @SerialName("high") HIGH, @SerialName("low") LOW }

@Serializable
enum class LoadSource {
    @SerialName("progression_state") PROGRESSION_STATE, @SerialName("e1rm_estimate") E1RM_ESTIMATE,
    @SerialName("transfer_seed") TRANSFER_SEED, @SerialName("load_find") LOAD_FIND,
    @SerialName("user_override") USER_OVERRIDE, @SerialName("none") NONE
}

@Serializable
enum class SessionStatus {
    @SerialName("planned") PLANNED, @SerialName("in_progress") IN_PROGRESS, @SerialName("completed") COMPLETED,
    @SerialName("abandoned") ABANDONED, @SerialName("skipped") SKIPPED
}

@Serializable
enum class ProposalStatus {
    @SerialName("pending") PENDING, @SerialName("applied") APPLIED, @SerialName("rejected") REJECTED,
    @SerialName("deferred") DEFERRED, @SerialName("expired") EXPIRED, @SerialName("invalid") INVALID,
    @SerialName("failed") FAILED
}

@Serializable enum class BackendMode { @SerialName("none") NONE, @SerialName("direct_api") DIRECT_API, @SerialName("home_bridge") HOME_BRIDGE }
@Serializable enum class Effort { @SerialName("low") LOW, @SerialName("medium") MEDIUM }
@Serializable enum class ChatRole { @SerialName("user") USER, @SerialName("assistant") ASSISTANT }

@Serializable
enum class ConversationStatus {
    @SerialName("open") OPEN, @SerialName("awaiting_tool_results") AWAITING_TOOL_RESULTS,
    @SerialName("compacted") COMPACTED, @SerialName("closed") CLOSED
}

@Serializable enum class CheckinAnswerType { @SerialName("number") NUMBER, @SerialName("scale5") SCALE5, @SerialName("yesno") YESNO, @SerialName("text") TEXT }
@Serializable enum class CheckinSchedule { @SerialName("daily") DAILY, @SerialName("weekly") WEEKLY, @SerialName("once") ONCE }
@Serializable enum class DrillKind { @SerialName("dynamic") DYNAMIC, @SerialName("static") STATIC, @SerialName("strength_through_rom") STRENGTH_THROUGH_ROM }
@Serializable enum class Joint { @SerialName("hips") HIPS, @SerialName("t_spine") T_SPINE, @SerialName("shoulders") SHOULDERS, @SerialName("ankles") ANKLES, @SerialName("hamstrings") HAMSTRINGS, @SerialName("wrists") WRISTS }
```

### 2.3 Values, explanations, entered units (`model/Values.kt`)

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/Values.kt
package com.gymcoach.core.model

import com.gymcoach.core.json.BandSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Inclusive integer range, encoded as [lo, hi]. */
@Serializable(with = BandSerializer::class)
data class Band(val lo: Int, val hi: Int) {
    init { require(lo <= hi) { "Band lo > hi: $lo > $hi" } }
    operator fun contains(v: Int): Boolean = v in lo..hi
    fun shifted(byLo: Int, byHi: Int): Band = Band(lo + byLo, hi + byHi)
}

/** Why something is the way it is. ruleIds are R3 §10 IDs or planner convention ids (SEL, SUB, REBAL, TIME-L1..7). */
@Serializable
data class Explanation(
    @SerialName("rule_ids") val ruleIds: List<String>,
    val message: String,
    val inputs: Map<String, String> = emptyMap(),
    val confidence: Confidence? = null,
)

/** What the user typed, verbatim, plus the derived SI value (sacred content; decision record H18). */
@Serializable
data class EnteredWeight(val entered: Double, val unit: WeightUnit, val kg: Double)

@Serializable
data class EnteredLength(val entered: Double, val unit: LengthUnit, val cm: Double)
```

### 2.4 Knowledge assets (`model/Knowledge.kt`) — mirrors R4 §6 one-to-one

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/Knowledge.kt
package com.gymcoach.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class MuscleWeight(val muscle: Muscle, val weight: Double)          // 1.0 | 0.5 | 0.25

@Serializable
data class EquipmentOption(
    @SerialName("all_of") val allOf: List<String>,
    @SerialName("load_type") val loadType: LoadType? = null,
    val note: String? = null,
    val preferred: Boolean = false,
)

@Serializable
data class GoalPrescription(
    val range: Band,
    val rir: Band,
    @SerialName("rir_last_week") val rirLastWeek: Band? = null,
    val sets: Band? = null,
    val note: String? = null,
)

@Serializable
data class RepProfile(
    val id: String,
    val unit: RepUnit,
    @SerialName("by_goal") val byGoal: Map<String, GoalPrescription>,   // keys: strength | hypertrophy | endurance | general_fitness
    val notes: String? = null,
    val sources: List<String> = emptyList(),
) {
    fun forGoal(goal: Goal): GoalPrescription = checkNotNull(byGoal[goal.repProfileKey]) { "rep profile $id lacks ${goal.repProfileKey}" }
}

@Serializable
data class Increment(
    val kind: IncrementKind,
    @SerialName("default_kg") val defaultKg: Double? = null,
    @SerialName("min_kg") val minKg: Double? = null,
    @SerialName("per_hand") val perHand: Boolean = false,
    val direction: IncrementDirection = IncrementDirection.LOAD,
    val note: String? = null,
)

@Serializable
data class ExerciseFlags(
    val compound: Boolean,
    @SerialName("spinal_loading") val spinalLoading: Boolean,
    @SerialName("shoulder_sensitive") val shoulderSensitive: Boolean,
    @SerialName("knee_sensitive") val kneeSensitive: Boolean,
    @SerialName("low_back_sensitive") val lowBackSensitive: Boolean,
    @SerialName("requires_spotter_or_safeties") val requiresSpotterOrSafeties: Boolean,
)

@Serializable
data class ExerciseContent(
    val description: String = "",
    val setup: List<String> = emptyList(),
    val cues: List<String> = emptyList(),
    val mistakes: List<String> = emptyList(),
    val safety: List<String> = emptyList(),
    @SerialName("substitution_rationale") val substitutionRationale: Map<String, String> = emptyMap(),
    val sources: List<String> = emptyList(),
    @SerialName("review_notes") val reviewNotes: String? = null,
)

@Serializable
data class Exercise(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val pattern: MovementPattern,
    val muscles: List<MuscleWeight> = emptyList(),
    @SerialName("equipment_options") val equipmentOptions: List<EquipmentOption>,
    @SerialName("load_type") val loadType: LoadType,
    val unilateral: Boolean,
    val difficulty: Difficulty,
    @SerialName("rep_profile") val repProfile: String,
    @SerialName("rep_overrides") val repOverrides: Map<String, GoalPrescription> = emptyMap(),
    val increment: Increment,
    val progression: ProgressionKind,
    val substitutions: List<String> = emptyList(),
    val flags: ExerciseFlags,
    val batch: Int,
    val tags: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val content: ExerciseContent? = null,
    @SerialName("content_status") val contentStatus: ContentStatus = ContentStatus.TODO,
) {
    val isCardioOrMobility: Boolean get() = pattern == MovementPattern.CARDIO || pattern == MovementPattern.MOBILITY
    val isIsolation: Boolean get() = !flags.compound && !isCardioOrMobility
    val primaryMuscles: List<Muscle> get() = muscles.filter { it.weight >= 1.0 }.map { it.muscle }
    fun weightOf(muscle: Muscle): Double = muscles.firstOrNull { it.muscle == muscle }?.weight ?: 0.0

    /** R3 DEF-07 class for the effective load type of the chosen equipment option. */
    fun exerciseClass(effectiveLoadType: LoadType): ExerciseClass {
        if (isIsolation) return ExerciseClass.ISOLATION
        return when (effectiveLoadType) {
            LoadType.BARBELL, LoadType.SMITH ->
                if (pattern == MovementPattern.SQUAT || pattern == MovementPattern.HINGE ||
                    pattern == MovementPattern.LUNGE_SPLIT || pattern == MovementPattern.HIP_EXTENSION_ISOLATION)
                    ExerciseClass.LOWER_BARBELL else ExerciseClass.UPPER_BARBELL
            LoadType.DUMBBELL, LoadType.KETTLEBELL -> ExerciseClass.DUMBBELL
            LoadType.MACHINE, LoadType.SLED -> ExerciseClass.MACHINE
            LoadType.CABLE -> ExerciseClass.CABLE
            LoadType.BODYWEIGHT, LoadType.BAND, LoadType.MEDICINE_BALL, LoadType.CARDIO, LoadType.NONE -> ExerciseClass.BODYWEIGHT
        }
    }
}

@Serializable
data class ExerciseLibraryFile(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("renamed_ids") val renamedIds: Map<String, String> = emptyMap(),   // old id -> new id
    @SerialName("rep_profiles") val repProfiles: List<RepProfile>,
    val exercises: List<Exercise>,
)

/** One content-overlay file per authoring batch (R4 §5.12): exercise_content/batch_N.json. */
@Serializable
data class ContentOverlay(
    @SerialName("schema_version") val schemaVersion: Int,
    val batch: Int,
    val entries: Map<String, ExerciseContent>,
    val status: Map<String, ContentStatus> = emptyMap(),
)

@Serializable
data class ParamSpec(
    val key: String,
    val type: String,                        // "number" | "integer" | "boolean" | "number_list"
    val unit: String? = null,
    val default: JsonElement? = null,        // R4 §6.1: number | boolean | array
    val min: Double? = null,
    val max: Double? = null,
    val description: String? = null,
)

@Serializable
data class EquipmentDef(
    val id: String,
    val name: String,
    val category: EquipmentCategory,
    val aliases: List<String> = emptyList(),
    val description: String? = null,
    val params: List<ParamSpec> = emptyList(),
    @SerialName("session_flag") val sessionFlag: Boolean = false,
    val packable: Boolean = false,
    @SerialName("default_in") val defaultIn: List<String> = emptyList(),
    val notes: String? = null,
)

@Serializable
data class EquipmentGroup(val id: String, val kind: GroupKind, val name: String? = null, val members: List<String>, val notes: String? = null)

@Serializable
data class PresetItem(@SerialName("equipment_id") val equipmentId: String, val params: Map<String, JsonElement> = emptyMap())

@Serializable
data class EquipmentPreset(val id: String, val name: String, val description: String? = null, @SerialName("default_session_minutes") val defaultSessionMinutes: Int? = null, val items: List<PresetItem>)

@Serializable
data class EquipmentCatalogFile(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("renamed_ids") val renamedIds: Map<String, String> = emptyMap(),
    val equipment: List<EquipmentDef>,
    val groups: List<EquipmentGroup>,
    val presets: List<EquipmentPreset>,
)

/** rules.json: one statement per R3 rule ID with provenance; constants are the PlannerConstants defaults (§3.5). */
@Serializable
data class RuleEntry(
    val id: String,                          // "VOL-04"
    val section: String,                     // "Resistance training / Weekly volume"
    val statement: String,
    val confidence: Confidence,
    val citation: String,                    // author-year or position stand
    val url: String? = null,
    val constants: Map<String, Double> = emptyMap(),   // e.g. "intermediate.lo": 12.0
    @SerialName("user_adjustable") val userAdjustable: Boolean = false,
    val note: String? = null,
)

@Serializable
data class RulesFile(@SerialName("schema_version") val schemaVersion: Int, val rules: List<RuleEntry>)

/** mobility_drills.json (MOB-07). */
@Serializable
data class Drill(
    val id: String,
    val name: String,
    val joint: Joint,
    val kind: DrillKind,
    val patterns: List<MovementPattern> = emptyList(),   // session patterns this drill prepares
    @SerialName("limitation_regions") val limitationRegions: List<LimitationRegion> = emptyList(),
    @SerialName("dose_reps") val doseReps: Band? = null,
    @SerialName("dose_seconds") val doseSeconds: Band? = null,
    @SerialName("exercise_id") val exerciseId: String? = null,   // when the drill is an R4 mobility exercise
    val sources: List<String> = emptyList(),
)

@Serializable
data class DrillsFile(@SerialName("schema_version") val schemaVersion: Int, val drills: List<Drill>)

/** templates.json (§4.5): session templates and split rows. */
@Serializable
data class SlotTemplate(
    @SerialName("slot_id") val slotId: String,
    val role: SlotRole,
    val patterns: List<MovementPattern>,                 // first is preferred; later ones are family fallbacks
    val heavy: Boolean = false,
    @SerialName("prefer_load_types") val preferLoadTypes: List<LoadType> = emptyList(),
    @SerialName("target_muscles") val targetMuscles: List<Muscle> = emptyList(),   // ISOLATION slots; empty = "priority deficit"
    val required: Boolean = true,
    @SerialName("superset_with") val supersetWith: String? = null,
    @SerialName("backoff_of") val backoffOf: String? = null,          // STRENGTH variants (§3.4): "<templateId>.<slotId>" of the heavy slot this slot backs off; templateId must be listed in the SAME split row (KnowledgeValidator rule 10)
)

@Serializable
data class SessionTemplate(val id: String, val name: String, val slots: List<SlotTemplate>)

@Serializable
data class SplitTemplate(
    val kind: SplitKind,
    @SerialName("days_per_week") val daysPerWeek: Int,
    @SerialName("session_template_ids") val sessionTemplateIds: List<String>,   // weekly order
    @SerialName("default_weekdays") val defaultWeekdays: List<Int>,            // ISO 1=Mon..7=Sun
    @SerialName("for_goals") val forGoals: List<Goal> = emptyList(),           // empty = any
    @SerialName("for_training_ages") val forTrainingAges: List<TrainingAge> = emptyList(),
)

@Serializable
data class TemplatesFile(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("session_templates") val sessionTemplates: List<SessionTemplate>,
    val splits: List<SplitTemplate>,
)

/** checkin_questions.json (CHK-01). */
@Serializable
data class CheckinQuestionDef(
    val id: String,                          // "Q1".."Q15"
    val text: String,
    @SerialName("answer_type") val answerType: CheckinAnswerType,
    @SerialName("auto_source") val autoSource: String? = null,   // "weight_trend" | "steps" | "sessions_completed" | null
    @SerialName("trigger_rule_ids") val triggerRuleIds: List<String>,
    @SerialName("trigger_condition") val triggerCondition: String,   // human-readable, evaluated in CheckinEvaluator by id
    @SerialName("only_in_deficit") val onlyInDeficit: Boolean = false,
    @SerialName("always_ask") val alwaysAsk: Boolean = false,
)

@Serializable
data class CheckinQuestionsFile(@SerialName("schema_version") val schemaVersion: Int, val questions: List<CheckinQuestionDef>)

/** rp_landmarks.json (R4 §4.5, direct-count sets/week, intermediate). */
@Serializable
data class RpLandmark(val muscle: Muscle, val mv: Band, val mev: Band, val mav: Band, val mrv: Band, @SerialName("sessions_per_week") val sessionsPerWeek: Band)

@Serializable
data class RpLandmarksFile(@SerialName("schema_version") val schemaVersion: Int, val source: String, @SerialName("fetched_on") val fetchedOn: String, val landmarks: List<RpLandmark>)

/** supplements.json (R3 §7). */
@Serializable
data class SupplementEntry(
    val id: String,                          // "SUP-01"
    val name: String,
    val tier: String,                        // "A" | "B" | "C"
    val dose: String,
    val timing: String,
    val evidence: Confidence,
    val citation: String,
    val notes: List<String> = emptyList(),
    @SerialName("do_not_reason") val doNotReason: String? = null,   // tier C
    @SerialName("gated_by_medical") val gatedByMedical: List<MedicalFlag> = emptyList(),
    @SerialName("legal_note") val legalNote: String? = null,
)

@Serializable
data class SupplementsFile(@SerialName("schema_version") val schemaVersion: Int, @SerialName("caveat_sup12") val caveatSup12: List<String>, @SerialName("priority_order") val priorityOrder: String, val entries: List<SupplementEntry>)

/** cardio_modalities.json (CAR-08). */
@Serializable
data class CardioModalityEntry(
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("zone2_control") val zone2Control: String,
    @SerialName("hiit_suitability") val hiitSuitability: String,     // "low" | "moderate" | "high"
    @SerialName("leg_day_adjacency") val legDayAdjacency: String,   // "good" | "moderate" | "poor"
    @SerialName("is_running") val isRunning: Boolean = false,
    @SerialName("knee_impact") val kneeImpact: Boolean = false,
)

@Serializable
data class CardioModalitiesFile(@SerialName("schema_version") val schemaVersion: Int, val modalities: List<CardioModalityEntry>)

/** nutrition_rules.json: display tables for the Nutrition/Knowledge screens and the coach digest (NUT-02/03/12/14). */
@Serializable
data class NutritionTip(val id: String, @SerialName("rule_id") val ruleId: String, val title: String, val body: String, val confidence: Confidence, val citation: String)

@Serializable
data class NutritionTipsFile(@SerialName("schema_version") val schemaVersion: Int, val tips: List<NutritionTip>)

/** load_transfer.json (Appendix C): find-load seeds only. */
@Serializable
data class LoadTransferSeed(@SerialName("from_exercise_id") val fromExerciseId: String, @SerialName("to_exercise_id") val toExerciseId: String, val factor: Double, val note: String? = null)

/** Body-weight-fraction seed for a ~10-rep set when nothing else is known (B graft, weak): "starting guess". */
@Serializable
data class BodyweightSeed(val pattern: MovementPattern, @SerialName("load_type") val loadType: LoadType, val fraction: Double, @SerialName("per_hand") val perHand: Boolean = false)

@Serializable
data class LoadTransferFile(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("unilateral_factor") val unilateralFactor: Double,          // 0.5
    val seeds: List<LoadTransferSeed>,
    @SerialName("bodyweight_seeds") val bodyweightSeeds: List<BodyweightSeed>,
    @SerialName("female_factor") val femaleFactor: Double,                  // 0.6
    @SerialName("intermediate_factor") val intermediateFactor: Double,      // 1.4
    @SerialName("advanced_factor") val advancedFactor: Double,              // 1.7
)
```
### 2.5 Profile, body metrics, gyms, today's context (`model/Profile.kt`, `model/Gym.kt`)

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/Profile.kt
@file:UseSerializers(LocalDateIsoSerializer::class, InstantIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.json.InstantIsoSerializer
import com.gymcoach.core.json.LocalDateIsoSerializer
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** INJ-00: pain ≤ 3/10 acceptable (penalty + note); > 3 → regress/exclude. RED-02 uses `since`. */
@Serializable
data class Limitation(
    val id: String,
    val region: LimitationRegion,
    val side: Side = Side.BOTH,
    @SerialName("pain_0_10") val pain0to10: Int,
    val since: LocalDate,
    val active: Boolean = true,
    @SerialName("limited_range") val limitedRange: Boolean = false,   // MOB-02 chronic dose applies
    val notes: String = "",
)

@Serializable
data class Profile(
    val sex: Sex,
    @SerialName("birth_year") val birthYear: Int,
    val height: EnteredLength,
    @SerialName("training_months_consistent") val trainingMonthsConsistent: Int,   // DEF-01: months of ≥ 2 sessions/week
    @SerialName("layoff_months") val layoffMonths: Int = 0,                         // length of the most recent complete layoff
    @SerialName("months_retrained_since_layoff") val monthsRetrainedSinceLayoff: Int = 0,   // DEF-01 recency (H11)
    val goal: Goal,
    @SerialName("days_per_week") val daysPerWeek: Int,                              // 1..6
    @SerialName("session_minutes") val sessionMinutes: Int,                         // 30..120
    @SerialName("preferred_weekdays") val preferredWeekdays: List<Int> = emptyList(),   // ISO 1..7; empty = split defaults
    val limitations: List<Limitation> = emptyList(),
    @SerialName("medical_flags") val medicalFlags: Set<MedicalFlag> = emptySet(),
    @SerialName("clearance_confirmed_on") val clearanceConfirmedOn: LocalDate? = null,   // RED-03
    @SerialName("priority_muscles") val priorityMuscles: List<Muscle> = emptyList(),      // ≤ 3
    @SerialName("liked_exercise_ids") val likedExerciseIds: List<String> = emptyList(),
    @SerialName("disliked_exercise_ids") val dislikedExerciseIds: List<String> = emptyList(),
    @SerialName("known_loads_kg") val knownLoadsKg: Map<String, Double> = emptyMap(),   // exerciseId -> working load from onboarding
    @SerialName("allow_advanced_exercises") val allowAdvancedExercises: Boolean = false,   // R4 §5.3
    @SerialName("body_fat_pct") val bodyFatPct: Double? = null,                          // NUT-01/05 leanness gate
    @SerialName("physical_job") val physicalJob: Boolean = false,                        // NUT-04 activity factor
    @SerialName("activity_factor_override") val activityFactorOverride: Double? = null,  // NUT-04, user-set
    @SerialName("has_hr_monitor") val hasHrMonitor: Boolean = false,
    @SerialName("measured_hr_max") val measuredHrMax: Int? = null,                       // CAR-03: preferred over Tanaka
    @SerialName("wake_time_local") val wakeTimeLocal: String = "06:30",                  // "HH:mm", WU-06
    @SerialName("bedtime_local") val bedtimeLocal: String = "23:00",                     // "HH:mm", SUP-02
    @SerialName("usual_session_time") val usualSessionTime: String = "evening",          // "morning" | "midday" | "evening" (SLP-03)
    @SerialName("cardio_inside_session_minutes") val cardioInsideSessionMinutes: Boolean = false,
    @SerialName("updated_at") val updatedAt: Instant,
) {
    fun ageOn(date: LocalDate): Int = date.year - birthYear
}

/** Morning metrics. `weight` is the sacred typed value; MA7 etc. are derived elsewhere (NUT-15). */
@Serializable
data class BodyMetric(
    val id: String,
    val date: LocalDate,
    val weight: EnteredWeight? = null,
    @SerialName("waist_cm") val waistCm: Double? = null,
    @SerialName("resting_hr") val restingHr: Int? = null,
    val steps: Int? = null,
    val note: String = "",
    @SerialName("logged_at") val loggedAt: Instant,
)
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/Gym.kt
@file:UseSerializers(LocalDateIsoSerializer::class, InstantIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.json.InstantIsoSerializer
import com.gymcoach.core.json.LocalDateIsoSerializer
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonElement

/** One line of a named gym set; params override the catalogue defaults (R4 §2.6). */
@Serializable
data class GymItem(@SerialName("equipment_id") val equipmentId: String, val params: Map<String, JsonElement> = emptyMap())

@Serializable
data class GymSet(
    val id: String,
    val name: String,
    @SerialName("preset_origin") val presetOrigin: String? = null,        // "base_gym" | "home_gym" | "hotel_gym" | null
    val items: List<GymItem>,
    @SerialName("default_session_minutes") val defaultSessionMinutes: Int? = null,   // home 60 / hotel 45 (B graft)
    @SerialName("color_index") val colorIndex: Int = 0,                   // 0..7
    @SerialName("travel_kit_merged") val travelKitMerged: Boolean = false,
    val notes: String = "",
    @SerialName("is_default") val isDefault: Boolean = false,
    val archived: Boolean = false,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("last_used_at") val lastUsedAt: Instant? = null,
    @SerialName("schema_version") val schemaVersion: Int = 1,           // §7.2 DocVersions
)

/** Everything that varies day to day; the realizer never reads anything else about "today". */
@Serializable
data class TodayContext(
    val date: LocalDate,
    @SerialName("gym_id") val gymId: String,
    @SerialName("extra_items") val extraItems: List<GymItem> = emptyList(),               // one-off additions / travel kit
    @SerialName("removed_equipment_ids") val removedEquipmentIds: List<String> = emptyList(),   // "the leg press is broken today"
    val spotter: Boolean = false,
    @SerialName("minutes_override") val minutesOverride: Int? = null,
    val readiness: ReadinessCheckin? = null,
    @SerialName("ambient_cold") val ambientCold: Boolean = false,                            // WU-06
    @SerialName("minutes_since_waking") val minutesSinceWaking: Int? = null,                // WU-06
    @SerialName("illness_symptoms") val illnessSymptoms: Boolean = false,                    // RDY-03 / LAY-02
    @SerialName("alcohol_drinks_last_night") val alcoholDrinksLastNight: Int = 0,           // NUT-13
)

/** Resolved equipment for one day = gym + extras − removals + session flag (R4 §2.2 item 4). Not persisted. */
data class SessionEquipment(
    val gym: GymSet,
    val extraItems: List<GymItem> = emptyList(),
    val removedEquipmentIds: Set<String> = emptySet(),
    val spotterPresent: Boolean = false,
) {
    val items: List<GymItem>
        get() = (gym.items.filter { it.equipmentId !in removedEquipmentIds } + extraItems)
    fun has(equipmentId: String): Boolean = items.any { it.equipmentId == equipmentId }
}
```

### 2.6 Readiness, check-ins (`model/Readiness.kt`, `model/Checkin.kt`)

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/Readiness.kt
@file:UseSerializers(LocalDateIsoSerializer::class, InstantIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.json.InstantIsoSerializer
import com.gymcoach.core.json.LocalDateIsoSerializer
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * RDY-01 inputs. Quick mode (B graft) leaves sleepQuality and stress null; the scorer renormalises
 * over answered items and lists them in `assumedFields`. Score/band are derived (stored for history).
 */
@Serializable
data class ReadinessCheckin(
    val id: String,
    val date: LocalDate,
    @SerialName("quick_mode") val quickMode: Boolean = false,
    @SerialName("sleep_hours") val sleepHours: Double? = null,
    @SerialName("sleep_quality") val sleepQuality: Int? = null,      // 1..5
    val soreness: Int? = null,                                       // 1..5 (5 severe)
    @SerialName("soreness_region") val sorenessRegion: Muscle? = null,
    val stress: Int? = null,                                         // 1..5 (5 severe)
    val energy: Int? = null,                                         // 1..5 (5 full)
    val motivation: Int? = null,                                     // 1..5
    @SerialName("resting_hr") val restingHr: Int? = null,
    @SerialName("joint_pain_0_10") val jointPain0to10: Int = 0,
    @SerialName("joint_region") val jointRegion: LimitationRegion? = null,
    @SerialName("red_flag_reported") val redFlagReported: Boolean = false,
    @SerialName("assumed_fields") val assumedFields: List<String> = emptyList(),
    val score: Int,                                                  // 0..100 (RDY-01)
    val band: ReadinessBand,                                         // RDY-02
    val explanation: Explanation,
    @SerialName("logged_at") val loggedAt: Instant,
    @SerialName("schema_version") val schemaVersion: Int = 1,           // §7.2 DocVersions
)
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/Checkin.kt
@file:UseSerializers(LocalDateIsoSerializer::class, InstantIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.json.InstantIsoSerializer
import com.gymcoach.core.json.LocalDateIsoSerializer
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** An active question: a CHK-01 bank item (id "Q1".."Q15") or a coach/user-added one ("C-<uuid>"). */
@Serializable
data class CheckinQuestion(
    val id: String,
    val text: String,
    @SerialName("answer_type") val answerType: CheckinAnswerType,
    val schedule: CheckinSchedule = CheckinSchedule.WEEKLY,
    @SerialName("on_date") val onDate: LocalDate? = null,             // schedule ONCE
    @SerialName("trigger_rule_ids") val triggerRuleIds: List<String> = emptyList(),
    @SerialName("added_by") val addedBy: OverrideSource = OverrideSource.PLANNER,
    @SerialName("proposal_id") val proposalId: String? = null,
    @SerialName("active_from") val activeFrom: LocalDate? = null,
    @SerialName("active_until") val activeUntil: LocalDate? = null,
    val answered: Boolean = false,
    val answer: String? = null,
    @SerialName("answered_at") val answeredAt: Instant? = null,
    @SerialName("schema_version") val schemaVersion: Int = 1,           // §7.2 DocVersions
)

@Serializable
data class CheckinAnswer(@SerialName("question_id") val questionId: String, val number: Double? = null, val yes: Boolean? = null, val text: String? = null)

@Serializable
data class WeeklyCheckin(
    val id: String,
    @SerialName("week_start") val weekStart: LocalDate,
    val answers: List<CheckinAnswer>,
    @SerialName("auto_answered") val autoAnswered: List<String> = emptyList(),
    val triggered: List<Explanation> = emptyList(),                  // what CheckinEvaluator concluded
    val holds: List<Muscle> = emptyList(),                           // VOL-08 holds from CheckinOutcome.holds (Q6 / Q12, §5.2), enum order; written by CheckinViewModel, read by TargetPolicy from the latest inputs.weeklyCheckins entry (§4.4)
    @SerialName("completed_at") val completedAt: Instant? = null,
    @SerialName("schema_version") val schemaVersion: Int = 1,           // §7.2 DocVersions
)
```

### 2.7 Plan documents, realized sessions, logs, progression (`model/Plan.kt`, `model/Session.kt`, `model/Progression.kt`)

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/Plan.kt
@file:UseSerializers(LocalDateIsoSerializer::class, InstantIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.json.InstantIsoSerializer
import com.gymcoach.core.json.LocalDateIsoSerializer
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class PlannedSlot(
    @SerialName("slot_id") val slotId: String,
    val role: SlotRole,
    val pattern: MovementPattern,
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("option_index") val optionIndex: Int,                 // which equipment option matched in the plan gym
    val sets: Int,
    val heavy: Boolean,
    @SerialName("superset_group") val supersetGroup: String? = null,
    val explanation: Explanation,
)

@Serializable
data class CardioPrescription(
    @SerialName("exercise_id") val exerciseId: String,                 // R4 cardio exercise id, e.g. "stationary_bike"
    val minutes: Int,
    val kind: CardioKind,
    @SerialName("hr_low") val hrLow: Int? = null,
    @SerialName("hr_high") val hrHigh: Int? = null,
    @SerialName("rpe_low") val rpeLow: Int? = null,                    // Borg 6-20
    @SerialName("rpe_high") val rpeHigh: Int? = null,
    val intervals: Int? = null,
    val placement: CardioPlacement,
    val weekday: Int,                                                  // ISO 1..7
    val explanation: Explanation,
)

@Serializable
data class PlannedSession(
    val id: String,                                                    // "<mesoId>:w<k>:d<isoWeekday>:<templateId>" — deterministic (HR-18); the weekday keeps it unique when a template repeats inside a week (ppl_6, ul_6; §4.16 #18)
    @SerialName("template_id") val templateId: String,
    val name: String,
    val weekday: Int,                                                  // ISO 1..7
    val slots: List<PlannedSlot>,
    @SerialName("estimated_minutes") val estimatedMinutes: Double,
    @SerialName("volume_fractional") val volumeFractional: Map<Muscle, Double>,
    @SerialName("volume_direct") val volumeDirect: Map<Muscle, Int>,
)

@Serializable
data class MesoWeek(
    val index: Int,                                                    // 1-based within the mesocycle
    @SerialName("start_date") val startDate: LocalDate,
    val mode: WeekMode,
    @SerialName("rir_offset") val rirOffset: Int,                      // RIR-04: +1 week 1, 0 middle, -1 last; DELOAD uses fixed RIR
    val targets: Map<Muscle, Double>,                                  // fractional sets/week (§4.4)
    val sessions: List<PlannedSession>,
    val cardio: List<CardioPrescription> = emptyList(),
    val notes: List<Explanation> = emptyList(),
)

@Serializable
data class Mesocycle(
    val id: String,                                                    // "meso-" + inputsHash.take(16) — deterministic, never IdGen (HR-18)
    @SerialName("start_date") val startDate: LocalDate,
    val goal: Goal,
    @SerialName("training_age") val trainingAge: TrainingAge,
    val split: SplitKind,
    @SerialName("gym_id") val gymId: String,                           // the gym the plan was built for
    val weeks: List<MesoWeek>,
    @SerialName("deload_week_index") val deloadWeekIndex: Int? = null,
    @SerialName("inputs_hash") val inputsHash: String,
    @SerialName("under_target_report") val underTargetReport: List<Explanation> = emptyList(),
    val explanations: List<Explanation> = emptyList(),
    @SerialName("schema_version") val schemaVersion: Int = 1,
)

@Serializable
data class DrillPrescription(
    @SerialName("drill_id") val drillId: String,
    val sets: Int,
    val reps: Band? = null,
    val seconds: Band? = null,
    val explanation: Explanation,
)

@Serializable
data class Warmup(
    @SerialName("raise_minutes") val raiseMinutes: Int,                // WU-01; 0 when trimmed (WU-05)
    @SerialName("raise_exercise_id") val raiseExerciseId: String? = null,
    val drills: List<DrillPrescription>,                               // MOB-01 dynamic drills 2×8–10
    @SerialName("total_minutes") val totalMinutes: Int,
    val explanation: Explanation,
)

@Serializable
data class SetPrescription(
    val index: Int,
    val kind: SetKind,
    val reps: Band? = null,
    val seconds: Band? = null,
    val metres: Band? = null,
    @SerialName("load_kg") val loadKg: Double? = null,                 // rounded by LoadRounder
    @SerialName("load_label") val loadLabel: String? = null,           // "2×22 kg", "band: heavy", "BW+10 kg", "stack 45 kg"
    @SerialName("per_hand") val perHand: Boolean = false,
    val rir: Int? = null,
    @SerialName("rest_seconds") val restSeconds: Int,
    @SerialName("load_source") val loadSource: LoadSource = LoadSource.NONE,
)

@Serializable
data class ExerciseBlock(
    @SerialName("block_id") val blockId: String,                      // slotId + ":" + exerciseId — deterministic (HR-18); a free-session block uses "free<n>:" + exerciseId
    @SerialName("slot_id") val slotId: String,
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("option_index") val optionIndex: Int,
    @SerialName("effective_load_type") val effectiveLoadType: LoadType,
    @SerialName("substituted_for") val substitutedFor: String? = null,
    @SerialName("slot_role") val slotRole: SlotRole,
    val order: Int,
    val sets: List<SetPrescription>,
    @SerialName("superset_group") val supersetGroup: String? = null,
    @SerialName("progression_key") val progressionKey: ProgressionKey,
    val warnings: List<Explanation> = emptyList(),                     // dumbbell max exceeded, no safeties, …
    val explanation: Explanation,
)

@Serializable
data class VolumeSnapshot(val fractional: Map<Muscle, Double>, val direct: Map<Muscle, Int>)

/** Today's realized session (Stage 6). Frozen into SessionRecord.plan when the session starts. */
@Serializable
data class SessionPlan(
    val id: String,                                                    // (plannedSessionId ?: "free") + "@" + date.toString() — deterministic (HR-18)
    @SerialName("planned_session_id") val plannedSessionId: String?,
    @SerialName("template_id") val templateId: String,
    val name: String,
    val date: LocalDate,
    @SerialName("gym_id") val gymId: String,
    val mode: SessionMode,
    @SerialName("week_index") val weekIndex: Int,
    @SerialName("readiness_score") val readinessScore: Int? = null,
    val warmup: Warmup,
    val blocks: List<ExerciseBlock>,
    val cardio: CardioPrescription? = null,
    val cooldown: List<DrillPrescription> = emptyList(),
    val modifiers: List<Explanation>,                                  // every step of §4.7 that changed something
    @SerialName("estimated_minutes") val estimatedMinutes: Double,
    val volume: VolumeSnapshot,
    @SerialName("ledger_carryover") val ledgerCarryover: Map<Muscle, Double> = emptyMap(),
    @SerialName("alternative_offered") val alternativeOffered: Boolean = false,   // Red day: Z2 + mobility alternative
    @SerialName("schema_version") val schemaVersion: Int = 1,           // §7.2 DocVersions ("SessionPlan"; stored as sessions.plan_json)
)
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/Session.kt
@file:UseSerializers(LocalDateIsoSerializer::class, InstantIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.json.InstantIsoSerializer
import com.gymcoach.core.json.LocalDateIsoSerializer
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * A logged set. SACRED: written only by the logging UI through WorkoutRepository; never updated in
 * place — a correction inserts a new row and sets supersededById on the old one.
 */
@Serializable
data class SetLog(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("block_id") val blockId: String? = null,
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("set_index") val setIndex: Int,
    val kind: SetKind,
    val weight: EnteredWeight? = null,
    @SerialName("per_hand") val perHand: Boolean = false,
    val reps: Int? = null,
    val seconds: Int? = null,
    val metres: Int? = null,
    val rir: Int? = null,
    val side: Side = Side.BOTH,
    @SerialName("prescribed_load_kg") val prescribedLoadKg: Double? = null,
    @SerialName("prescribed_reps") val prescribedReps: Band? = null,
    @SerialName("prescribed_rir") val prescribedRir: Int? = null,
    @SerialName("logged_at") val loggedAt: Instant,
    @SerialName("superseded_by_id") val supersededById: String? = null,
    @SerialName("supersedes_id") val supersedesId: String? = null,
    val note: String = "",
)

@Serializable
data class SessionRecord(
    val id: String,
    val date: LocalDate,
    @SerialName("gym_id") val gymId: String,
    @SerialName("planned_session_id") val plannedSessionId: String? = null,
    @SerialName("template_id") val templateId: String? = null,
    val plan: SessionPlan? = null,                                     // frozen at start
    val status: SessionStatus,
    @SerialName("started_at") val startedAt: Instant? = null,
    @SerialName("finished_at") val finishedAt: Instant? = null,
    @SerialName("session_rpe") val sessionRpe: Int? = null,            // RDY-06 (0-10)
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
    @SerialName("readiness_checkin_id") val readinessCheckinId: String? = null,
    @SerialName("skip_reason") val skipReason: String? = null,
    val notes: String = "",
)

/** Persisted record of every automatic or user-confirmed change (POL-01). Append-only; undo sets undoneAt. */
@Serializable
data class Adjustment(
    val id: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("exercise_id") val exerciseId: String? = null,
    val kind: AdjustmentKind,
    @SerialName("rule_ids") val ruleIds: List<String>,
    val summary: String,                                               // "Load 80 → 82.5 kg (PROG-01: all sets hit 10 reps at ≤ 2 RIR)"
    val inputs: Map<String, String> = emptyMap(),
    @SerialName("before_json") val beforeJson: String,
    @SerialName("after_json") val afterJson: String,
    val source: OverrideSource,
    @SerialName("proposal_id") val proposalId: String? = null,
    @SerialName("override_id") val overrideId: String? = null,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("undone_at") val undoneAt: Instant? = null,
)

@Serializable
data class CardioLog(
    val id: String,
    val date: LocalDate,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("exercise_id") val exerciseId: String,
    val kind: CardioKind,
    val minutes: Int,
    @SerialName("avg_hr") val avgHr: Int? = null,
    val rpe: Int? = null,
    val note: String = "",
    @SerialName("logged_at") val loggedAt: Instant,
)

/** Daily ordinal nutrition inputs (NUT-14 (5)); kcal is optional and enables NUT-04 (H14). */
@Serializable
data class NutritionLog(
    val id: String,
    val date: LocalDate,
    val kcal: Int? = null,
    @SerialName("protein_g") val proteinG: Int? = null,
    @SerialName("protein_target_hit") val proteinTargetHit: Boolean? = null,
    @SerialName("meals_heaviness") val mealsHeaviness: Int? = null,     // 1 light .. 3 heavy
    @SerialName("alcohol_drinks") val alcoholDrinks: Int? = null,       // NUT-13
    @SerialName("travel_day") val travelDay: Boolean = false,           // NUT-14 (4): suppress trend reactions
    val note: String = "",
    @SerialName("logged_at") val loggedAt: Instant,
)
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/Progression.kt
@file:UseSerializers(LocalDateIsoSerializer::class, InstantIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.json.InstantIsoSerializer
import com.gymcoach.core.json.LocalDateIsoSerializer
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * Progression is tracked per exercise; for MACHINE/CABLE/SMITH/SLED loads additionally per gym
 * (E1RM-04, TRV-04; decision record H6). gymScope is the GymSet id or null.
 */
@Serializable
data class ProgressionKey(@SerialName("exercise_id") val exerciseId: String, @SerialName("gym_scope") val gymScope: String? = null) {
    val id: String get() = if (gymScope == null) exerciseId else "$exerciseId@$gymScope"
    companion object {
        fun forExercise(exerciseId: String, effectiveLoadType: LoadType, gymId: String): ProgressionKey =
            ProgressionKey(exerciseId, if (effectiveLoadType.isGymScoped) gymId else null)
    }
}

@Serializable
data class E1rmEstimate(
    val kg: Double,
    @SerialName("from_weight_kg") val fromWeightKg: Double,
    @SerialName("from_reps") val fromReps: Int,
    @SerialName("from_rir") val fromRir: Int?,
    val confidence: E1rmConfidence,
    val date: LocalDate,
    @SerialName("formula_id") val formulaId: String = "E1RM-01",
)

@Serializable
data class SessionSummary(
    val date: LocalDate,
    @SerialName("load_kg") val loadKg: Double?,
    @SerialName("best_reps") val bestReps: Int,
    @SerialName("min_rir") val minRir: Int?,
    @SerialName("all_sets_at_hi") val allSetsAtHi: Boolean,
    val e1rm: E1rmEstimate? = null,
    @SerialName("load_x_reps") val loadXReps: Double? = null,            // machines/cables (E1RM-04)
    @SerialName("readiness_band") val readinessBand: ReadinessBand? = null,
    @SerialName("week_mode") val weekMode: WeekMode = WeekMode.NORMAL,
    val fair: Boolean = true,                                            // false for deload/re-entry/travel/capped/Red/external-cause sessions (§4.9.3)
)

@Serializable
data class ProgressionState(
    val key: ProgressionKey,
    val status: ProgressionStatus,
    @SerialName("working_load_kg") val workingLoadKg: Double? = null,
    @SerialName("band_level") val bandLevel: Int? = null,               // BAND_PROGRESSION
    @SerialName("working_reps") val workingReps: Band,
    @SerialName("rep_hi_base") val repHiBase: Int,                      // PROG-02 > 10 % rule (B graft)
    @SerialName("rep_hi_extended") val repHiExtended: Boolean = false,
    @SerialName("target_rir") val targetRir: Int,
    @SerialName("consecutive_hits") val consecutiveHits: Int = 0,       // R4 §5.4 two-session rule for coarse implements
    @SerialName("last_session_date") val lastSessionDate: LocalDate? = null,
    @SerialName("last_increment_date") val lastIncrementDate: LocalDate? = null,
    @SerialName("sessions_since_increment") val sessionsSinceIncrement: Int = 0,
    @SerialName("no_progress_count") val noProgressCount: Int = 0,      // PLAT-01
    @SerialName("best_e1rm_14d") val bestE1rm14d: Double? = null,        // E1RM-04
    @SerialName("best_e1rm_14d_date") val bestE1rm14dDate: LocalDate? = null,
    @SerialName("plateau_step") val plateauStep: Int = 0,               // PLAT-02 ladder position 0..5
    @SerialName("calibration_sessions") val calibrationSessions: Int = 0,
    @SerialName("rep_zone_shift") val repZoneShift: Int = 0,            // PLAT-02 step 2: +1 = one zone lighter
    val history: List<SessionSummary> = emptyList(),                    // last 8 sessions, newest first
    @SerialName("updated_at") val updatedAt: Instant,
    @SerialName("schema_version") val schemaVersion: Int = 1,           // §7.2 DocVersions
)
```

### 2.8 Plan mutations and overrides (`model/Mutation.kt`) — the chat/user contract

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/Mutation.kt
@file:UseSerializers(LocalDateIsoSerializer::class, InstantIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.json.InstantIsoSerializer
import com.gymcoach.core.json.LocalDateIsoSerializer
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** Closed set of things the user or the coach can change. Each maps 1:1 onto a coach tool (§6.6). */
@Serializable
sealed class PlanMutation {
    abstract val rationale: String

    @Serializable @SerialName("swap_exercise")
    data class SwapExercise(
        @SerialName("from_exercise_id") val fromExerciseId: String,
        @SerialName("to_exercise_id") val toExerciseId: String,
        @SerialName("template_id") val templateId: String? = null,     // null = every session containing `from`
        val scope: MutationScope,
        override val rationale: String,
    ) : PlanMutation()

    @Serializable @SerialName("change_volume")
    data class ChangeVolume(
        val muscle: Muscle,
        @SerialName("delta_sets_per_week") val deltaSetsPerWeek: Int,   // validated −6..+6
        val scope: MutationScope,
        override val rationale: String,
    ) : PlanMutation()

    @Serializable @SerialName("change_rep_range")
    data class ChangeRepRange(
        @SerialName("exercise_id") val exerciseId: String,
        @SerialName("rep_lo") val repLo: Int,
        @SerialName("rep_hi") val repHi: Int,
        val scope: MutationScope,
        override val rationale: String,
    ) : PlanMutation()

    @Serializable @SerialName("add_cardio")
    data class AddCardio(
        val weekday: Int,                                               // ISO 1..7
        val minutes: Int,
        @SerialName("exercise_id") val exerciseId: String? = null,      // null = planner picks (CAR-08)
        val kind: CardioKind,
        override val rationale: String,
    ) : PlanMutation()

    @Serializable @SerialName("remove_cardio")
    data class RemoveCardio(val weekday: Int, override val rationale: String) : PlanMutation()

    @Serializable @SerialName("change_goal")
    data class ChangeGoal(val goal: Goal, @SerialName("start_next_week") val startNextWeek: Boolean, override val rationale: String) : PlanMutation()

    @Serializable @SerialName("change_schedule")
    data class ChangeSchedule(
        @SerialName("days_per_week") val daysPerWeek: Int? = null,
        @SerialName("session_minutes") val sessionMinutes: Int? = null,
        @SerialName("preferred_weekdays") val preferredWeekdays: List<Int>? = null,
        override val rationale: String,
    ) : PlanMutation()

    @Serializable @SerialName("set_checkin_question")
    data class SetCheckinQuestion(
        val question: String,
        @SerialName("answer_type") val answerType: CheckinAnswerType,
        val schedule: CheckinSchedule,
        @SerialName("on_date") val onDate: LocalDate? = null,
        @SerialName("weeks_active") val weeksActive: Int = 4,
        override val rationale: String,
    ) : PlanMutation()

    @Serializable @SerialName("exclude_exercise")
    data class ExcludeExercise(@SerialName("exercise_id") val exerciseId: String, val until: LocalDate? = null, override val rationale: String) : PlanMutation()

    @Serializable @SerialName("pin_exercise")
    data class PinExercise(@SerialName("exercise_id") val exerciseId: String, @SerialName("template_id") val templateId: String? = null, override val rationale: String) : PlanMutation()

    @Serializable @SerialName("adjust_working_load")
    data class AdjustWorkingLoad(
        @SerialName("exercise_id") val exerciseId: String,
        @SerialName("gym_scope") val gymScope: String? = null,
        @SerialName("new_working_load_kg") val newWorkingLoadKg: Double,
        override val rationale: String,
    ) : PlanMutation()

    @Serializable @SerialName("set_priority_muscles")
    data class SetPriorityMuscles(val muscles: List<Muscle>, override val rationale: String) : PlanMutation()

    @Serializable @SerialName("schedule_deload")
    data class ScheduleDeload(@SerialName("start_this_week") val startThisWeek: Boolean, override val rationale: String) : PlanMutation()

    @Serializable @SerialName("set_limitation")
    data class SetLimitation(val limitation: Limitation, override val rationale: String) : PlanMutation()

    @Serializable @SerialName("set_travel_days")
    data class SetTravelDays(val from: LocalDate, val to: LocalDate, @SerialName("gym_id") val gymId: String? = null, override val rationale: String) : PlanMutation()

    @Serializable @SerialName("update_nutrition_targets")
    data class UpdateNutritionTargets(
        @SerialName("kcal_target") val kcalTarget: Int? = null,
        @SerialName("protein_g_per_kg") val proteinGPerKg: Double? = null,
        override val rationale: String,
    ) : PlanMutation()
}

/** Append-only. Revocation sets revokedAt; expiry is a date; later overrides win over earlier ones for the same target. */
@Serializable
data class PlanOverride(
    val id: String,
    @SerialName("created_at") val createdAt: Instant,
    val source: OverrideSource,
    @SerialName("proposal_id") val proposalId: String? = null,         // coach tool_use id when source == CHAT
    val mutation: PlanMutation,
    @SerialName("revoked_at") val revokedAt: Instant? = null,
    @SerialName("expires_on") val expiresOn: LocalDate? = null,        // TODAY-scope mutations: set to the day they apply (§6.8)
    @SerialName("schema_version") val schemaVersion: Int = 1,           // §7.2 DocVersions
)
```

### 2.9 Planner inputs and outputs (`model/PlanInputs.kt`)

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/PlanInputs.kt
@file:UseSerializers(LocalDateIsoSerializer::class, InstantIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.json.InstantIsoSerializer
import com.gymcoach.core.json.LocalDateIsoSerializer
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** Fully materialized planner input. :app builds it from Room/DataStore; tests build it from JSON fixtures. */
@Serializable
data class PlanInputs(
    val profile: Profile,
    val gyms: List<GymSet>,
    @SerialName("default_gym_id") val defaultGymId: String,
    val overrides: List<PlanOverride>,
    @SerialName("progression_states") val progressionStates: List<ProgressionState>,
    @SerialName("recent_sessions") val recentSessions: List<SessionRecord>,      // last 12 weeks
    @SerialName("recent_sets") val recentSets: List<SetLog>,                     // sets of recentSessions (non-superseded)
    @SerialName("body_metrics") val bodyMetrics: List<BodyMetric>,               // last 12 weeks
    @SerialName("weekly_checkins") val weeklyCheckins: List<WeeklyCheckin>,      // last 8
    @SerialName("readiness_history") val readinessHistory: List<ReadinessCheckin>,   // last 14 days
    @SerialName("mesocycle_anchor") val mesocycleAnchor: LocalDate? = null,      // start of the current mesocycle; null = start today
    @SerialName("week_of_mesocycle") val weekOfMesocycle: Int? = null,           // paused during TRAVEL_MAINTENANCE
    @SerialName("travel_weeks") val travelWeeks: List<LocalDate> = emptyList(),  // week-start dates in TRAVEL_MAINTENANCE mode
    @SerialName("week_starts_on_monday") val weekStartsOnMonday: Boolean = true,
    @SerialName("mesocycles_completed") val mesocyclesCompleted: Int = 0,        // from AppSettings; 0 = first mesocycle (DLD-01 novice rule, §4.4)
    @SerialName("last_deload_week_start") val lastDeloadWeekStart: LocalDate? = null,   // scheduled or reactive; §4.11 two-week guard
    @SerialName("deload_triggers_proposed") val deloadTriggersProposed: List<String> = emptyList(),   // "<kind>@<onset>" already turned into a proposal (§4.11)
    /** Every DLD-03 trigger active today as "<kind>@<onset>" — filled by PlanCoordinator.buildInputs() from DeloadPolicy.signals(...).active(today, c.plat.dld03MinDays) (§7.6); TargetPolicy holds every VOL-08 ramp while it is non-empty (§4.4). Empty in tests that do not exercise the hold. */
    @SerialName("deload_triggers_active") val deloadTriggersActive: List<String> = emptyList(),
    /** Last date any exercise of the pattern was logged (any gym), also beyond the 12-week window — LAY-01 (§4.7 step 2). Built by :app from WorkoutRepository.lastLoggedAtByExercise() + KnowledgeBase patterns. */
    @SerialName("pattern_last_trained") val patternLastTrained: Map<MovementPattern, LocalDate> = emptyMap(),
)

@Serializable
data class PlanOutput(
    val mesocycle: Mesocycle,
    @SerialName("current_week_index") val currentWeekIndex: Int,
    val explanations: List<Explanation>,
    val proposals: List<PlanMutation> = emptyList(),                             // PLANNER-source cards (deload, micro-session …)
)

@Serializable
data class SessionCloseResult(
    val states: List<ProgressionState>,
    val adjustments: List<Adjustment>,
    val flags: List<Explanation>,                                                // PLATEAU / REGRESSION flags
)

@Serializable
data class WeekRollResult(
    @SerialName("week_start") val weekStart: LocalDate,
    @SerialName("next_mode") val nextMode: WeekMode,
    @SerialName("volume_holds") val volumeHolds: List<Explanation>,              // VOL-08 holds
    @SerialName("deload_triggers") val deloadTriggers: List<Explanation>,        // DLD-03 — filled by PlanCoordinator from WP-04's DeloadPolicy (§7.6); ALWAYS empty when returned by Planner.rollWeek (WP-03 never calls DeloadPolicy)
    val proposals: List<PlanMutation>,                                           // PLAT-02 step 3/5 proposals from rollWeek; PlanCoordinator appends the reactive ScheduleDeload PLANNER card here (§4.11, §7.6)
    val states: List<ProgressionState> = emptyList(),                            // PLAT-02 ladder advances (plateauStep / repZoneShift written by rollWeek, §4.11); PlanCoordinator persists them via ProgressionRepository.saveAll (§7.6)
    @SerialName("ladder_notes") val ladderNotes: List<Explanation> = emptyList(), // one Explanation(PLAT-02) per advanced state (inputs: "exercise_id", "step"); PlanCoordinator records one Adjustment per entry (§7.6)
)

/** Returned to the coach in the tool_result after a proposal is applied (A graft). */
@Serializable
data class PlanDiff(
    @SerialName("sessions_changed") val sessionsChanged: List<String>,
    @SerialName("weekly_sets_before") val weeklySetsBefore: Map<Muscle, Double>,
    @SerialName("weekly_sets_after") val weeklySetsAfter: Map<Muscle, Double>,
    @SerialName("exercises_added") val exercisesAdded: List<String> = emptyList(),
    @SerialName("exercises_removed") val exercisesRemoved: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    @SerialName("under_target_report") val underTargetReport: List<String> = emptyList(),
)
```

### 2.10 Nutrition, coach, settings (`model/Nutrition.kt`, `model/Coach.kt`, `model/Settings.kt`)

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/Nutrition.kt
@file:UseSerializers(LocalDateIsoSerializer::class, InstantIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.json.InstantIsoSerializer
import com.gymcoach.core.json.LocalDateIsoSerializer
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class TdeeAdjustment(
    val date: LocalDate,
    @SerialName("observed_rate_kg_per_week") val observedRateKgPerWeek: Double,
    @SerialName("expected_rate_kg_per_week") val expectedRateKgPerWeek: Double,
    @SerialName("delta_kcal") val deltaKcal: Int,
    val explanation: Explanation,
)

@Serializable
data class EnergyModel(
    @SerialName("ree_kcal") val reeKcal: Int,                                   // NUT-04 Mifflin–St Jeor
    @SerialName("activity_factor") val activityFactor: Double,                  // NUT-04 [weak]
    @SerialName("tdee_estimate_kcal") val tdeeEstimateKcal: Int,                // after adaptive corrections
    val adjustments: List<TdeeAdjustment> = emptyList(),
    val explanation: Explanation,
)

@Serializable
data class NutritionTargets(
    val kcal: Int,
    @SerialName("protein_g") val proteinG: Int,                                 // NUT-01
    @SerialName("carbs_g") val carbsG: Int,                                     // NUT-08
    @SerialName("fat_g") val fatG: Int,                                         // NUT-09
    @SerialName("fibre_g") val fibreG: Int,                                     // NUT-10
    @SerialName("water_l") val waterL: Double,                                  // NUT-11
    @SerialName("energy_delta_kcal") val energyDeltaKcal: Int,                  // negative = deficit
    @SerialName("goal_rate_bw_pct_per_week_lo") val goalRateLo: Double? = null,
    @SerialName("goal_rate_bw_pct_per_week_hi") val goalRateHi: Double? = null,
    @SerialName("next_review_date") val nextReviewDate: LocalDate? = null,      // NUT-16
    val explanation: Explanation,
)

@Serializable
data class WeightTrendPoint(val date: LocalDate, @SerialName("raw_kg") val rawKg: Double? = null, @SerialName("ma7_kg") val ma7Kg: Double? = null, val noise: Boolean = false)

/** User-owned nutrition settings (targets can be overridden by an UpdateNutritionTargets override). */
@Serializable
data class NutritionState(
    @SerialName("tdee_estimate_kcal") val tdeeEstimateKcal: Int? = null,
    @SerialName("kcal_target_override") val kcalTargetOverride: Int? = null,
    @SerialName("protein_g_per_kg_override") val proteinGPerKgOverride: Double? = null,
    @SerialName("last_review_date") val lastReviewDate: LocalDate? = null,
    @SerialName("deficit_started_on") val deficitStartedOn: LocalDate? = null,   // NUT-17
    @SerialName("creatine_started_on") val creatineStartedOn: LocalDate? = null, // SUP-01 water-weight note
    @SerialName("updated_at") val updatedAt: Instant,
)
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/Coach.kt
@file:UseSerializers(LocalDateIsoSerializer::class, InstantIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.json.InstantIsoSerializer
import com.gymcoach.core.json.LocalDateIsoSerializer
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class ChatConversation(
    val id: String,
    @SerialName("started_at") val startedAt: Instant,
    @SerialName("ended_at") val endedAt: Instant? = null,
    val backend: BackendMode,
    val model: String,
    val effort: Effort,
    @SerialName("system_prompt_hash") val systemPromptHash: String,        // SHA-256 of the byte-stable prompt (§6.4)
    @SerialName("tool_contract_version") val toolContractVersion: Int,
    @SerialName("seed_summary") val seedSummary: String? = null,           // set when started by compaction (§6.9)
    val status: ConversationStatus,
    @SerialName("assistant_turns") val assistantTurns: Int = 0,
    @SerialName("last_input_tokens") val lastInputTokens: Int = 0,         // input + cache_read + cache_creation of the last response
    @SerialName("is_default") val isDefault: Boolean = true,               // false for a refused conversation left open by "Retry on Sonnet 5" (§6.7)
    @SerialName("schema_version") val schemaVersion: Int = 1,              // §7.2 DocVersions
)

/**
 * One transcript entry. contentJson is the verbatim Messages-API content array (thinking blocks included).
 * A synthetic user message (tool_result-only follow-up, §6.7) has displayText == "" and is hidden by the UI.
 */
@Serializable
data class ChatMessage(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    val seq: Int,
    val role: ChatRole,
    @SerialName("content_json") val contentJson: String,
    @SerialName("display_text") val displayText: String,                   // text blocks joined; never sent back
    @SerialName("created_at") val createdAt: Instant,
    val usage: TokenUsage? = null,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("stop_details_json") val stopDetailsJson: String? = null,
    @SerialName("caveat_appended") val caveatAppended: Boolean = false,    // SUP-12 post-check (§6.12)
    /** Assistant messages only: {tool_use_id: result JSON (≤ 2,000 chars)} for every READ-ONLY tool_use block of this message, so the next request can echo the tool_result after process death (§6.7). */
    @SerialName("tool_results_json") val toolResultsJson: String? = null,
)

@Serializable
data class TokenUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int = 0,
    @SerialName("thinking_tokens") val thinkingTokens: Int? = null,
) {
    val totalInput: Int get() = inputTokens + cacheReadInputTokens + cacheCreationInputTokens
}

@Serializable
data class Proposal(
    val id: String,                                                        // == tool_use id
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("assistant_message_id") val assistantMessageId: String,
    @SerialName("tool_name") val toolName: String,
    @SerialName("contract_version") val contractVersion: Int,
    @SerialName("payload_json") val payloadJson: String,                   // verbatim tool_use.input
    val mutation: PlanMutation? = null,                                    // null when INVALID
    val rationale: String,
    val status: ProposalStatus,
    @SerialName("validation_warnings") val validationWarnings: List<String> = emptyList(),
    @SerialName("validation_errors") val validationErrors: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("expires_on") val expiresOn: LocalDate,                    // created + 7 days
    @SerialName("decided_at") val decidedAt: Instant? = null,
    @SerialName("override_id") val overrideId: String? = null,
    @SerialName("result_summary") val resultSummary: String? = null,       // text sent back as tool_result
    @SerialName("result_sent") val resultSent: Boolean = false,
    @SerialName("schema_version") val schemaVersion: Int = 1,              // §7.2 DocVersions
)

@Serializable
data class SpendRecord(val id: String, val at: Instant, val model: String, val usage: TokenUsage, @SerialName("cost_usd") val costUsd: Double, val backend: BackendMode, @SerialName("conversation_id") val conversationId: String)
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/Settings.kt
package com.gymcoach.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    @SerialName("unit_system") val unitSystem: UnitSystem = UnitSystem.METRIC,
    @SerialName("backend_mode") val backendMode: BackendMode = BackendMode.NONE,
    @SerialName("model_id") val modelId: String = "claude-opus-5",
    val effort: Effort = Effort.LOW,
    @SerialName("monthly_budget_usd") val monthlyBudgetUsd: Double = 30.0,
    @SerialName("bridge_url") val bridgeUrl: String = "",
    @SerialName("show_experimental") val showExperimental: Boolean = false,
    @SerialName("onboarding_done") val onboardingDone: Boolean = false,
    @SerialName("week_starts_on_monday") val weekStartsOnMonday: Boolean = true,
    @SerialName("planner_overrides_json") val plannerOverridesJson: String = "{}",   // Settings ▸ Advanced (§4.1): nested by group, e.g. {"vol":{"vol04Novice":[8,12]}}
    @SerialName("coach_user_id") val coachUserId: String = "",                   // opaque UUID for metadata.user_id; SettingsDataStore fills it on first read (§7.3)
    @SerialName("readiness_reminder_time") val readinessReminderTime: String = "07:30",   // v1.1 notification; v1 in-app card
    @SerialName("weekly_checkin_weekday") val weeklyCheckinWeekday: Int = 7,     // ISO Sunday
    @SerialName("keep_screen_on_in_session") val keepScreenOnInSession: Boolean = true,
    @SerialName("last_week_rollover") val lastWeekRollover: String = "",         // ISO date
    @SerialName("catalog_version_seen") val catalogVersionSeen: Int = 0,
    @SerialName("mesocycle_anchor") val mesocycleAnchor: String = "",           // ISO date of the current mesocycle start ("" = none yet)
    @SerialName("mesocycle_week_paused_at") val mesocycleWeekPausedAt: Int = 0, // week index paused by TRAVEL_MAINTENANCE (0 = not paused)
    @SerialName("mesocycles_completed") val mesocyclesCompleted: Int = 0,       // PlanCoordinator increments when a mesocycle ends (§4.4 DLD-01 novice rule)
    @SerialName("last_deload_week_start") val lastDeloadWeekStart: String = "", // ISO Monday of the last DELOAD week ("" = none); §4.11 two-week guard
    @SerialName("deload_triggers_proposed") val deloadTriggersProposed: String = "[]",   // JSON list of "<kind>@<onset>" (§4.11); PlanCoordinator maintains it
    @SerialName("active_gym_id") val activeGymId: String = "",                  // gym chosen on Today ("" = the default gym); §8.6, ToolExecutor default
    @SerialName("today_context_json") val todayContextJson: String = "",        // TodayContext of the current date (extras/removed/spotter/minutes), cleared when the date changes; survives process death on Today
    @SerialName("session_draft_json") val sessionDraftJson: String = "",        // unlogged stepper state of the active session (§8.8 RECOVERED); "" = none
    @SerialName("onboarding_draft_json") val onboardingDraftJson: String = "",  // rotation/kill-safe onboarding state (§8.5); "" = none
)
```

`AppSettings` is the **only** place per-device drafts live: there are no separate DataStore keys
for drafts, the active gym or the catalogue version (§7.3); every screen that needs them reads
`SettingsRepository.get()` / `observe()` and writes through `update { }`.

### 2.11 Repository interfaces, backend, knowledge source, errors (`repo/*.kt`)

```kotlin
// core/src/main/kotlin/com/gymcoach/core/repo/Repositories.kt
package com.gymcoach.core.repo

import com.gymcoach.core.model.Adjustment
import com.gymcoach.core.model.AppSettings
import com.gymcoach.core.model.BodyMetric
import com.gymcoach.core.model.CardioLog
import com.gymcoach.core.model.ChatConversation
import com.gymcoach.core.model.ChatMessage
import com.gymcoach.core.model.CheckinQuestion
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.NutritionLog
import com.gymcoach.core.model.NutritionState
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.Proposal
import com.gymcoach.core.model.ProposalStatus
import com.gymcoach.core.model.ReadinessCheckin
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.model.SpendRecord
import com.gymcoach.core.model.WeeklyCheckin
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

interface ProfileRepository {
    fun observe(): Flow<Profile?>
    suspend fun get(): Profile?
    suspend fun save(profile: Profile)
}

interface GymRepository {
    fun observeAll(includeArchived: Boolean = false): Flow<List<GymSet>>
    suspend fun getAll(includeArchived: Boolean = false): List<GymSet>
    suspend fun get(id: String): GymSet?
    suspend fun upsert(gym: GymSet)
    suspend fun setDefault(id: String)
    suspend fun touchLastUsed(id: String, at: Instant)
    suspend fun setArchived(id: String, archived: Boolean)
}

interface PlanRepository {
    fun observeCurrent(): Flow<Mesocycle?>
    suspend fun getCurrent(): Mesocycle?
    suspend fun saveSnapshot(mesocycle: Mesocycle, computedAt: Instant)     // marks it current; keeps older snapshots
    suspend fun snapshotByInputsHash(hash: String): Mesocycle?
    fun observeOverrides(): Flow<List<PlanOverride>>
    suspend fun overrides(): List<PlanOverride>
    suspend fun addOverride(override: PlanOverride)
    suspend fun revokeOverride(id: String, at: Instant)
    suspend fun nutritionState(): NutritionState?
    suspend fun saveNutritionState(state: NutritionState)
}

interface WorkoutRepository {
    suspend fun startSession(record: SessionRecord)
    suspend fun updateSession(record: SessionRecord)
    suspend fun getSession(id: String): SessionRecord?
    fun observeOpenSession(): Flow<SessionRecord?>                          // status IN_PROGRESS
    suspend fun sessionsBetween(from: LocalDate, to: LocalDate): List<SessionRecord>
    fun observeSets(sessionId: String): Flow<List<SetLog>>                  // non-superseded, ordered
    suspend fun setsForSession(sessionId: String, includeSuperseded: Boolean = false): List<SetLog>
    suspend fun setsForSessions(sessionIds: List<String>): List<SetLog>
    suspend fun setsForExercise(exerciseId: String, limitSessions: Int): List<SetLog>
    /** exerciseId -> instant of the newest non-superseded WORKING/AMRAP/CALIBRATION/DROP set, over ALL history (LAY-01 gaps beyond 12 weeks, §4.7 step 2). */
    suspend fun lastLoggedAtByExercise(): Map<String, Instant>
    suspend fun addSet(set: SetLog)
    suspend fun supersedeSet(oldId: String, replacement: SetLog)            // never UPDATE values in place
    suspend fun recordAdjustment(adjustment: Adjustment)
    suspend fun undoAdjustment(id: String, at: Instant)
    fun observeAdjustments(limit: Int): Flow<List<Adjustment>>
    suspend fun adjustmentsForSession(sessionId: String): List<Adjustment>
}

interface ProgressionRepository {
    suspend fun get(key: ProgressionKey): ProgressionState?
    suspend fun getAll(): List<ProgressionState>
    suspend fun getForExercise(exerciseId: String): List<ProgressionState>
    suspend fun save(state: ProgressionState)
    suspend fun saveAll(states: List<ProgressionState>)
}

interface MetricsRepository {
    fun observeBodyMetrics(days: Int): Flow<List<BodyMetric>>
    suspend fun bodyMetricsBetween(from: LocalDate, to: LocalDate): List<BodyMetric>
    suspend fun addBodyMetric(metric: BodyMetric)
    suspend fun addCardioLog(log: CardioLog)
    suspend fun cardioBetween(from: LocalDate, to: LocalDate): List<CardioLog>
    suspend fun addNutritionLog(log: NutritionLog)
    suspend fun nutritionBetween(from: LocalDate, to: LocalDate): List<NutritionLog>
    suspend fun addReadiness(checkin: ReadinessCheckin)
    suspend fun readinessFor(date: LocalDate): ReadinessCheckin?             // latest for the date
    suspend fun readinessBetween(from: LocalDate, to: LocalDate): List<ReadinessCheckin>
    fun observeReadiness(days: Int): Flow<List<ReadinessCheckin>>
    suspend fun saveWeeklyCheckin(checkin: WeeklyCheckin)
    suspend fun weeklyCheckinFor(weekStart: LocalDate): WeeklyCheckin?
    suspend fun latestWeeklyCheckins(count: Int): List<WeeklyCheckin>
    fun observeQuestions(): Flow<List<CheckinQuestion>>
    suspend fun activeQuestions(on: LocalDate): List<CheckinQuestion>
    suspend fun saveQuestion(question: CheckinQuestion)
}

interface CoachRepository {
    suspend fun openConversation(): ChatConversation?
    suspend fun conversation(id: String): ChatConversation?
    suspend fun listConversations(): List<ChatConversation>
    suspend fun createConversation(conversation: ChatConversation)
    suspend fun updateConversation(conversation: ChatConversation)
    fun observeMessages(conversationId: String): Flow<List<ChatMessage>>
    suspend fun messages(conversationId: String): List<ChatMessage>
    suspend fun addMessage(message: ChatMessage)
    suspend fun updateMessage(message: ChatMessage)
    fun observePendingProposals(): Flow<List<Proposal>>
    suspend fun proposals(conversationId: String): List<Proposal>
    suspend fun proposal(id: String): Proposal?
    suspend fun addProposal(proposal: Proposal)
    suspend fun updateProposal(proposal: Proposal)
    suspend fun decideProposal(id: String, status: ProposalStatus, decidedAt: Instant, resultSummary: String, overrideId: String?)
    suspend fun addSpend(record: SpendRecord)
    suspend fun spendSince(from: Instant): List<SpendRecord>
}

interface SettingsRepository {
    fun observe(): Flow<AppSettings>
    suspend fun get(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

interface ApiKeyStore {
    suspend fun hasKey(): Boolean
    suspend fun store(key: String)
    suspend fun read(): String?            // null = "no key stored" (also after any decrypt failure, R2 §6.1)
    suspend fun clear()
    suspend fun storeBridgeSecret(secret: String)
    suspend fun readBridgeSecret(): String?
}

/** Byte source for the knowledge JSON; :core provides the class-path implementation, :app the assets fallback. */
interface KnowledgeSource {
    fun exists(relativePath: String): Boolean          // relative to "gymcoach/knowledge/"
    fun readText(relativePath: String): String
}
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/repo/NetworkMonitor.kt
package com.gymcoach.core.repo

import kotlinx.coroutines.flow.Flow

/**
 * Connectivity as :core sees it. :app implements it with ConnectivityManager
 * (AndroidNetworkMonitor, §6.10: a network counts only with NET_CAPABILITY_VALIDATED);
 * tests use FakeNetworkMonitor. CoachTurnService checks isOnline() before building a request;
 * CoachViewModel collects observe() to flush the one queued draft (§8.17).
 */
interface NetworkMonitor {
    fun isOnline(): Boolean
    fun observe(): Flow<Boolean>          // emits the current value first, then every change
}
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/repo/UnreadableDocs.kt
package com.gymcoach.core.repo

import kotlinx.coroutines.flow.Flow

/** A stored JSON document whose schema_version is newer than this build understands (§7.2). */
data class UnreadableDoc(val type: String, val version: Int, val id: String)

interface UnreadableDocsRepository {
    fun observe(): Flow<List<UnreadableDoc>>
    fun report(doc: UnreadableDoc)        // called by :app mappers; idempotent per (type, id)
}
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/repo/ClaudeBackend.kt
package com.gymcoach.core.repo

import com.gymcoach.core.model.BackendMode
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * Backend-agnostic and deliberately untyped at the boundary (R1 §3.12): `body` is the exact
 * Messages-API request JSON; each emitted element is one Messages-API stream event as parsed JSON
 * (the `data:` payload of an SSE event, or the bridge's equivalent). Transport failures and HTTP
 * errors surface as [BackendException]. Typed parsing happens in core.coach.wire.
 */
interface ClaudeBackend {
    val mode: BackendMode
    /** Streaming request (`stream: true`). Cancelling the collector cancels the call. */
    fun stream(body: JsonObject): Flow<StreamFrame>
    /**
     * Non-streamed request (`stream: false`): one POST, the full Message JSON returned as
     * [StreamFrame.event] plus the request-id header. Used by TranscriptCompactor (§6.9) and by
     * the Backend screen's Test (§8.18). Throws [BackendException] on any HTTP/transport error.
     */
    suspend fun complete(body: JsonObject): StreamFrame
    /** Sends RequestBuilder.buildTest(settings) via [complete]; never throws. */
    suspend fun test(): TestResult
}

/** One stream event (or a whole non-streamed Message) plus the response headers captured once (request-id, rate-limit headers). */
data class StreamFrame(val event: JsonObject, val requestId: String? = null)

/** error == null means the test request succeeded; requestId is shown on the Backend screen either way (never the key). */
data class TestResult(val error: BackendError?, val requestId: String?)

class BackendException(val error: BackendError, val requestId: String? = null) : Exception(error.toString())
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/repo/BackendError.kt
package com.gymcoach.core.repo

/** Every failure class the UI must handle (R1 §3.8/§3.9); copy and retry verdicts in §6.10. */
sealed class BackendError {
    object NotConfigured : BackendError()
    object Offline : BackendError()
    data class Transport(val message: String, val partialOutput: Boolean) : BackendError()
    object Authentication : BackendError()                                   // 401
    object Billing : BackendError()                                          // 402
    data class Permission(val message: String) : BackendError()              // 403
    data class InvalidRequest(val status: Int, val message: String) : BackendError()   // 400 / 404 / 413
    data class RateLimited(val retryAfterSec: Int?) : BackendError()         // 429 with retry-after
    object SpendCapReached : BackendError()                                  // 429 enforced_spend_limit_reached
    data class ServerError(val status: Int, val message: String) : BackendError()   // 500 / 504
    data class Overloaded(val midStream: Boolean) : BackendError()           // 529 or event: error overloaded_error
    data class StreamError(val type: String, val message: String) : BackendError()   // other event: error
    data class BridgeUnavailable(val reason: String) : BackendError()
    data class Unknown(val message: String) : BackendError()
}
```

### 2.12 Rule ids, time, units, ids (`explain/RuleIds.kt`, `time/Clock.kt`, `units/Units.kt`, `util/*.kt`)

```kotlin
// core/src/main/kotlin/com/gymcoach/core/explain/RuleIds.kt
package com.gymcoach.core.explain

/** Every R3 §10 ID as a constant so that typos fail at compile time; RuleCoverageTest checks usage. */
object RuleIds {
    const val POL_01 = "POL-01"; const val POL_02 = "POL-02"; const val POL_03 = "POL-03"
    const val DEF_01 = "DEF-01"; const val DEF_02 = "DEF-02"; const val DEF_03 = "DEF-03"; const val DEF_04 = "DEF-04"
    const val DEF_05 = "DEF-05"; const val DEF_06 = "DEF-06"; const val DEF_07 = "DEF-07"
    const val VOL_01 = "VOL-01"; const val VOL_02 = "VOL-02"; const val VOL_03 = "VOL-03"; const val VOL_04 = "VOL-04"
    const val VOL_05 = "VOL-05"; const val VOL_06 = "VOL-06"; const val VOL_07 = "VOL-07"; const val VOL_08 = "VOL-08"; const val VOL_09 = "VOL-09"
    const val FRQ_01 = "FRQ-01"; const val FRQ_02 = "FRQ-02"; const val FRQ_03 = "FRQ-03"
    const val LOAD_01 = "LOAD-01"; const val LOAD_02 = "LOAD-02"; const val LOAD_03 = "LOAD-03"; const val LOAD_04 = "LOAD-04"
    const val RIR_01 = "RIR-01"; const val RIR_02 = "RIR-02"; const val RIR_03 = "RIR-03"; const val RIR_04 = "RIR-04"; const val RIR_05 = "RIR-05"
    const val REST_01 = "REST-01"; const val REST_02 = "REST-02"; const val REST_03 = "REST-03"; const val REST_04 = "REST-04"
    const val ORD_01 = "ORD-01"; const val ORD_02 = "ORD-02"; const val ORD_03 = "ORD-03"
    const val MIX_01 = "MIX-01"; const val MIX_02 = "MIX-02"; const val MIX_03 = "MIX-03"
    const val UNI_01 = "UNI-01"; const val UNI_02 = "UNI-02"
    const val TEMPO_01 = "TEMPO-01"; const val TEMPO_02 = "TEMPO-02"
    const val ROM_01 = "ROM-01"; const val ROM_02 = "ROM-02"; const val ROM_03 = "ROM-03"
    const val SESS_01 = "SESS-01"; const val SESS_02 = "SESS-02"; const val SPLIT_01 = "SPLIT-01"; const val SPLIT_02 = "SPLIT-02"
    const val PROG_01 = "PROG-01"; const val PROG_02 = "PROG-02"; const val PROG_03 = "PROG-03"; const val PROG_04 = "PROG-04"; const val PROG_05 = "PROG-05"
    const val PLAT_01 = "PLAT-01"; const val PLAT_02 = "PLAT-02"; const val PLAT_03 = "PLAT-03"
    const val DLD_01 = "DLD-01"; const val DLD_02 = "DLD-02"; const val DLD_03 = "DLD-03"; const val DLD_04 = "DLD-04"
    const val E1RM_01 = "E1RM-01"; const val E1RM_02 = "E1RM-02"; const val E1RM_03 = "E1RM-03"; const val E1RM_04 = "E1RM-04"
    const val RDY_01 = "RDY-01"; const val RDY_02 = "RDY-02"; const val RDY_03 = "RDY-03"; const val RDY_04 = "RDY-04"; const val RDY_05 = "RDY-05"; const val RDY_06 = "RDY-06"
    const val CHK_01 = "CHK-01"
    const val RED_01 = "RED-01"; const val RED_02 = "RED-02"; const val RED_03 = "RED-03"
    const val WU_01 = "WU-01"; const val WU_02 = "WU-02"; const val WU_03 = "WU-03"; const val WU_04 = "WU-04"; const val WU_05 = "WU-05"; const val WU_06 = "WU-06"
    const val MOB_01 = "MOB-01"; const val MOB_02 = "MOB-02"; const val MOB_03 = "MOB-03"; const val MOB_04 = "MOB-04"; const val MOB_05 = "MOB-05"; const val MOB_06 = "MOB-06"; const val MOB_07 = "MOB-07"
    const val CAR_01 = "CAR-01"; const val CAR_02 = "CAR-02"; const val CAR_03 = "CAR-03"; const val CAR_04 = "CAR-04"; const val CAR_05 = "CAR-05"
    const val CAR_06 = "CAR-06"; const val CAR_07 = "CAR-07"; const val CAR_08 = "CAR-08"; const val CAR_09 = "CAR-09"
    const val NUT_01 = "NUT-01"; const val NUT_02 = "NUT-02"; const val NUT_03 = "NUT-03"; const val NUT_04 = "NUT-04"; const val NUT_05 = "NUT-05"
    const val NUT_06 = "NUT-06"; const val NUT_07 = "NUT-07"; const val NUT_08 = "NUT-08"; const val NUT_09 = "NUT-09"; const val NUT_10 = "NUT-10"
    const val NUT_11 = "NUT-11"; const val NUT_12 = "NUT-12"; const val NUT_13 = "NUT-13"; const val NUT_14 = "NUT-14"; const val NUT_15 = "NUT-15"
    const val NUT_16 = "NUT-16"; const val NUT_17 = "NUT-17"; const val NUT_18 = "NUT-18"
    const val SUP_01 = "SUP-01"; const val SUP_02 = "SUP-02"; const val SUP_03 = "SUP-03"; const val SUP_04 = "SUP-04"; const val SUP_05 = "SUP-05"
    const val SUP_06 = "SUP-06"; const val SUP_07 = "SUP-07"; const val SUP_08 = "SUP-08"; const val SUP_09 = "SUP-09"; const val SUP_10 = "SUP-10"
    const val SUP_11 = "SUP-11"; const val SUP_12 = "SUP-12"
    const val SLP_01 = "SLP-01"; const val SLP_02 = "SLP-02"; const val SLP_03 = "SLP-03"
    const val STP_01 = "STP-01"; const val STP_02 = "STP-02"; const val STR_01 = "STR-01"; const val STR_02 = "STR-02"
    const val AGE_01 = "AGE-01"; const val AGE_02 = "AGE-02"; const val AGE_03 = "AGE-03"
    const val LAY_01 = "LAY-01"; const val LAY_02 = "LAY-02"; const val LAY_03 = "LAY-03"
    const val TRV_01 = "TRV-01"; const val TRV_02 = "TRV-02"; const val TRV_03 = "TRV-03"; const val TRV_04 = "TRV-04"
    const val TIME_01 = "TIME-01"; const val TIME_02 = "TIME-02"; const val TIME_03 = "TIME-03"
    const val INJ_00 = "INJ-00"; const val INJ_01 = "INJ-01"; const val INJ_02 = "INJ-02"; const val INJ_03 = "INJ-03"; const val INJ_04 = "INJ-04"
    // Planner conventions (not R3): documented in rules.json with confidence "weak".
    const val SEL = "SEL"; const val SUB = "SUB"; const val REBAL = "REBAL"; const val TIER = "TIER"
    const val R4_2_6 = "R4-2.6"; const val R4_4_5 = "R4-4.5"; const val R4_5_4 = "R4-5.4"; const val R4_5_5 = "R4-5.5"
    const val RIR_CAP = "RIR-CAP"; const val ROUNDING = "ROUNDING"; const val OVERRIDE = "OVERRIDE"
    const val TIME_L1 = "TIME-L1"; const val TIME_L2 = "TIME-L2"; const val TIME_L3 = "TIME-L3"; const val TIME_L4 = "TIME-L4"
    const val TIME_L5 = "TIME-L5"; const val TIME_L6 = "TIME-L6"; const val TIME_L7 = "TIME-L7"

    /** Every constant above, for RuleCoverageTest / KnowledgeValidationTest ("every RuleIds value has a rules.json entry"). */
    val ALL: List<String> = listOf(
        POL_01, POL_02, POL_03, DEF_01, DEF_02, DEF_03, DEF_04, DEF_05, DEF_06, DEF_07,
        VOL_01, VOL_02, VOL_03, VOL_04, VOL_05, VOL_06, VOL_07, VOL_08, VOL_09, FRQ_01, FRQ_02, FRQ_03,
        LOAD_01, LOAD_02, LOAD_03, LOAD_04, RIR_01, RIR_02, RIR_03, RIR_04, RIR_05, REST_01, REST_02, REST_03, REST_04,
        ORD_01, ORD_02, ORD_03, MIX_01, MIX_02, MIX_03, UNI_01, UNI_02, TEMPO_01, TEMPO_02, ROM_01, ROM_02, ROM_03,
        SESS_01, SESS_02, SPLIT_01, SPLIT_02, PROG_01, PROG_02, PROG_03, PROG_04, PROG_05, PLAT_01, PLAT_02, PLAT_03,
        DLD_01, DLD_02, DLD_03, DLD_04, E1RM_01, E1RM_02, E1RM_03, E1RM_04, RDY_01, RDY_02, RDY_03, RDY_04, RDY_05, RDY_06,
        CHK_01, RED_01, RED_02, RED_03, WU_01, WU_02, WU_03, WU_04, WU_05, WU_06, MOB_01, MOB_02, MOB_03, MOB_04, MOB_05, MOB_06, MOB_07,
        CAR_01, CAR_02, CAR_03, CAR_04, CAR_05, CAR_06, CAR_07, CAR_08, CAR_09,
        NUT_01, NUT_02, NUT_03, NUT_04, NUT_05, NUT_06, NUT_07, NUT_08, NUT_09, NUT_10, NUT_11, NUT_12, NUT_13, NUT_14, NUT_15, NUT_16, NUT_17, NUT_18,
        SUP_01, SUP_02, SUP_03, SUP_04, SUP_05, SUP_06, SUP_07, SUP_08, SUP_09, SUP_10, SUP_11, SUP_12,
        SLP_01, SLP_02, SLP_03, STP_01, STP_02, STR_01, STR_02, AGE_01, AGE_02, AGE_03, LAY_01, LAY_02, LAY_03,
        TRV_01, TRV_02, TRV_03, TRV_04, TIME_01, TIME_02, TIME_03, INJ_00, INJ_01, INJ_02, INJ_03, INJ_04,
        SEL, SUB, REBAL, TIER, R4_2_6, R4_4_5, R4_5_4, R4_5_5, RIR_CAP, ROUNDING, OVERRIDE,
        TIME_L1, TIME_L2, TIME_L3, TIME_L4, TIME_L5, TIME_L6, TIME_L7,
    )
}
```

Every id in `RuleIds.ALL` has a `rules.json` entry (§3.5) — including the planner conventions
`RIR-CAP`, `ROUNDING` (load rounding > 5 %, `AdjustmentKind.ROUNDING`), `OVERRIDE` (a user/chat
override applied by the realizer) and `TIME-L1..L7` — so `PlanValidator` invariant 10 never rejects
a plan for citing one of them.

```kotlin
// core/src/main/kotlin/com/gymcoach/core/time/Clock.kt
package com.gymcoach.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

interface Clock {
    fun now(): Instant
    fun today(): LocalDate
    fun zone(): ZoneId
}

class SystemClock : Clock {
    override fun now(): Instant = Instant.now()
    override fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

class FixedClock(private val instant: Instant, private val zoneId: ZoneId = ZoneId.of("Europe/Berlin")) : Clock {
    override fun now(): Instant = instant
    override fun today(): LocalDate = instant.atZone(zoneId).toLocalDate()
    override fun zone(): ZoneId = zoneId
}
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/time/Weeks.kt
package com.gymcoach.core.time

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object Weeks {
    fun weekStart(date: LocalDate, mondayStart: Boolean = true): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(if (mondayStart) DayOfWeek.MONDAY else DayOfWeek.SUNDAY))
    fun daysBetween(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)
    /** ISO weekday 1..7 of a date. */
    fun isoWeekday(date: LocalDate): Int = date.dayOfWeek.value
    fun dateFor(weekStart: LocalDate, isoWeekday: Int): LocalDate = weekStart.plusDays((isoWeekday - 1).toLong())
}
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/units/Units.kt
package com.gymcoach.core.units

import com.gymcoach.core.model.EnteredLength
import com.gymcoach.core.model.EnteredWeight
import com.gymcoach.core.model.LengthUnit
import com.gymcoach.core.model.UnitSystem
import com.gymcoach.core.model.WeightUnit
import java.util.Locale

object Units {
    const val KG_PER_LB = 0.45359237
    const val CM_PER_IN = 2.54

    fun lbToKg(lb: Double): Double = lb * KG_PER_LB
    fun kgToLb(kg: Double): Double = kg / KG_PER_LB
    fun inToCm(inches: Double): Double = inches * CM_PER_IN
    fun cmToIn(cm: Double): Double = cm / CM_PER_IN

    /** Builds the sacred typed value; the kg conversion is exact (not rounded). */
    fun enteredWeight(value: Double, unit: WeightUnit): EnteredWeight =
        EnteredWeight(entered = value, unit = unit, kg = if (unit == WeightUnit.KG) value else lbToKg(value))

    fun enteredLength(value: Double, unit: LengthUnit): EnteredLength =
        EnteredLength(entered = value, unit = unit, cm = if (unit == LengthUnit.CM) value else inToCm(value))

    fun weightUnitFor(system: UnitSystem): WeightUnit = if (system == UnitSystem.METRIC) WeightUnit.KG else WeightUnit.LB
    fun lengthUnitFor(system: UnitSystem): LengthUnit = if (system == UnitSystem.METRIC) LengthUnit.CM else LengthUnit.IN

    /** "80 kg", "82.5 kg", "176.4 lb" — at most 2 decimals, trailing zeros trimmed. HR-20: entered value shown verbatim. */
    fun formatWeight(w: EnteredWeight, display: WeightUnit): String =
        if (w.unit == display) "${trim(w.entered)} ${unitLabel(display)}"
        else "≈ ${trim(if (display == WeightUnit.KG) w.kg else kgToLb(w.kg))} ${unitLabel(display)}"

    fun formatKg(kg: Double, display: WeightUnit): String =
        if (display == WeightUnit.KG) "${trim(kg)} kg" else "${trim(kgToLb(kg))} lb"

    fun unitLabel(u: WeightUnit): String = if (u == WeightUnit.KG) "kg" else "lb"

    fun trim(v: Double): String {
        val s = String.format(Locale.ROOT, "%.2f", v)
        return s.trimEnd('0').trimEnd('.')
    }
}
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/util/IdGen.kt
package com.gymcoach.core.util

import java.util.UUID

object IdGen {
    fun newId(): String = UUID.randomUUID().toString()
}
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/util/Hashing.kt
package com.gymcoach.core.util

import java.security.MessageDigest

object Hashing {
    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
```

### 2.13 Explanation helpers (`explain/Explanations.kt`)

```kotlin
// core/src/main/kotlin/com/gymcoach/core/explain/Explanations.kt
package com.gymcoach.core.explain

import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Explanation

object Explanations {
    fun of(ruleId: String, message: String, vararg inputs: Pair<String, String>, confidence: Confidence? = null): Explanation =
        Explanation(listOf(ruleId), message, inputs.toMap(), confidence)
    fun of(ruleIds: List<String>, message: String, inputs: Map<String, String> = emptyMap(), confidence: Confidence? = null): Explanation =
        Explanation(ruleIds, message, inputs, confidence)
}
```

### 2.14 View rows shared by `:core` services and `:app` composables (`model/Views.kt`, WP-01)

These are the parameter types of the frozen §8.4 composables (`LineChart`, `MuscleBandBars`) and
of the §5.6 supplement list. They are produced by WP-04 (`ProgressSeries`, `SupplementAdvisor`),
WP-03 (`WeeklyLedger`) and consumed by WP-07/WP-08; nobody redefines them.

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/Views.kt
package com.gymcoach.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One chart sample; dateEpochDay = LocalDate.toEpochDay(). lowConfidence → dashed segment (E1RM-02 LOW). */
@Serializable
data class ChartPoint(
    @SerialName("date_epoch_day") val dateEpochDay: Long,
    val value: Double,
    @SerialName("low_confidence") val lowConfidence: Boolean = false,
)

@Serializable
data class ChartSeries(val name: String, val points: List<ChartPoint>)

/** One row of the weekly ledger / Progress volume card. target == null for TIER_3 (no target, advisory cap only). */
@Serializable
data class MuscleBandRow(
    val muscle: Muscle,
    val tier: MuscleTier,
    val target: Double? = null,
    @SerialName("achieved_fractional") val achievedFractional: Double,
    @SerialName("planned_remaining") val plannedRemaining: Double,
    val direct: Int,
    @SerialName("rp_mev") val rpMev: Band? = null,
    @SerialName("rp_mrv") val rpMrv: Band? = null,
)

/** One supplement line as rendered on the Nutrition screen (§5.6); doses are already computed text. */
@Serializable
data class SupplementAdvice(
    val id: String,                      // SupplementEntry.id, e.g. "SUP-01"
    val name: String,
    val tier: String,                    // "A" | "B" | "C"
    @SerialName("dose_text") val doseText: String,        // "5 g/day" — or the RED-03 gate text when gated
    val timing: String,
    val evidence: Confidence,
    val citation: String,
    val gated: Boolean,                  // RED-03: medical flag without clearance, or gated_by_medical hit → no numbers
    val notes: List<String> = emptyList(),
    @SerialName("legal_note") val legalNote: String? = null,
    @SerialName("do_not_reason") val doNotReason: String? = null,   // tier C
)
```

### 2.15 Service signature contracts (Phase-0 frozen; bodies by the owning WP)

Every class named in §1.3 that another work package **calls** has its public surface fixed here
(the style of §3.9/§4.2). Owners implement exactly these names and parameter lists; callers code
against them from day one, so Phase 2 replaces nothing. Result/value types declared here live in
the file named by the `// path` comment and are owned by that file's WP. Blocks marked
"signature contract" follow the convention of the preamble (package + explicit imports + bodies
added by the owner). Classes whose complete contract already appears elsewhere are only
referenced: `Planner` (§4.2), `ProgressionEngine` (§4.9), `E1rm` (§4.10), `ReadinessScorer`
(§5.1), `HeartRateZones` (§5.4), `KnowledgeBase`/`EquipmentResolver`/`GymParams`/
`IncrementResolver`/`LoadRounder`/`Presets` (§3.9).

```kotlin
// ---- WP-03 ----------------------------------------------------------------------------------
// core/src/main/kotlin/com/gymcoach/core/mutation/MutationValidator.kt (signature contract)
sealed class ValidationResult {
    data class Valid(val warnings: List<String>) : ValidationResult()
    data class Invalid(val errors: List<String>) : ValidationResult()
}
class MutationValidator(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    /**
     * §4.15 table. `mesocycle` is null before the first plan exists (then only ChangeGoal, ChangeSchedule,
     * SetLimitation, SetTravelDays, SetPriorityMuscles and UpdateNutritionTargets can be Valid). `today` is
     * null when validating outside a day context (Plan screen); `nutrition` is the current targets for
     * UpdateNutritionTargets bounds (§5.5) or null.
     */
    fun validate(mutation: PlanMutation, inputs: PlanInputs, mesocycle: Mesocycle?, today: TodayContext?, nutrition: NutritionTargets? = null): ValidationResult
}

// core/src/main/kotlin/com/gymcoach/core/mutation/OverrideResolver.kt (signature contract)
object OverrideResolver {
    /** §4.3 "active overrides": not revoked, not expired at `today`; later createdAt wins per target; ExcludeExercise beats PinExercise. */
    fun active(overrides: List<PlanOverride>, today: LocalDate): List<PlanOverride>
}

// core/src/main/kotlin/com/gymcoach/core/mutation/PlanDiffer.kt (signature contract)
object PlanDiffer {
    /** Fills the §2.9 PlanDiff: sessions whose slot list or set counts differ, weekly fractional sets per muscle before/after (current week), exercises added/removed. */
    fun between(before: Mesocycle, after: Mesocycle, warnings: List<String> = emptyList()): PlanDiff
}

// core/src/main/kotlin/com/gymcoach/core/substitution/SubstitutionEngine.kt (signature contract)
/** One ranked alternative for the swap sheet (§8.8) and the realizer (§4.12). `terms` keys: "pattern", "cosine", "loadFit", "continuity", "difficulty", "rankBonus", "profilePenalty", "limitationPenalty". */
data class SubstitutionCandidate(
    val exerciseId: String,
    val score: Double,
    val terms: Map<String, Double>,
    val available: Boolean,
    val optionIndex: Int?,
    val effectiveLoadType: LoadType?,
    val prescribedLoadKg: Double?,          // what the planner would prescribe (state, seed or achievable max)
    val ledgerDelta: Map<Muscle, Double>,   // fractional credit change for the week if this candidate replaces the block
    val rationale: String,                  // content.substitutionRationale[...] or the generated line
)
class SubstitutionEngine(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    /** §4.12 steps 1–3, sorted by score desc then knowledge order; `excluded`/`penalized`/`trainingAge` come from `Planner.normalize(inputs, today).{excludedExerciseIds, penalizedExerciseIds, trainingAge}` (§4.2 — the swap sheet obtains them that way, §8.8); never returns the block's own exercise. */
    fun rank(block: ExerciseBlock, equipment: SessionEquipment, params: GymParams, states: List<ProgressionState>, goal: Goal, trainingAge: TrainingAge, excluded: Set<String>, penalized: Set<String>, allowAdvanced: Boolean, profile: Profile): List<SubstitutionCandidate>
}

// core/src/main/kotlin/com/gymcoach/core/substitution/LoadTransfer.kt (signature contract)
class LoadTransfer(private val kb: KnowledgeBase) {
    /** Appendix C: seed for `to` from a sibling's state (×factor, ×0.5 unilateral) else the body-weight fraction × sex/age factors; null = floor (§4.9.2). */
    fun seed(to: Exercise, effectiveLoadType: LoadType, states: List<ProgressionState>, profile: Profile, trainingAge: TrainingAge, bodyWeightKg: Double): Pair<Double, Explanation>?
}

// core/src/main/kotlin/com/gymcoach/core/volume/VolumeAccountant.kt (signature contract)
data class VolumeView(val direct: Int, val fractional: Double, val sessions: Int)
object VolumeAccountant {
    /** §4.13: WORKING/AMRAP/CALIBRATION/DROP only; LEFT/RIGHT rows paired per (sessionId, blockId, setIndex) count once (UNI-02). */
    fun weekly(sets: List<SetLog>, kb: KnowledgeBase): Map<Muscle, VolumeView>
    fun ofSets(sets: List<SetLog>, kb: KnowledgeBase): VolumeSnapshot
    fun ofPlan(plan: SessionPlan, kb: KnowledgeBase): VolumeSnapshot
    fun ofPlanned(session: PlannedSession, kb: KnowledgeBase): VolumeSnapshot
    /** Progress-screen aggregates (back = lats ∪ upper_back counted once at the larger weight; delts; arms; legs). */
    fun aggregates(weekly: Map<Muscle, VolumeView>): Map<String, VolumeView>
}

// core/src/main/kotlin/com/gymcoach/core/volume/WeeklyLedger.kt (signature contract)
data class WeeklyLedger(
    val weekStart: LocalDate,
    val targets: Map<Muscle, Double>,
    val achieved: Map<Muscle, VolumeView>,
    val plannedRemaining: Map<Muscle, Double>,
    val carryover: Map<Muscle, Double>,
) {
    fun deficit(m: Muscle): Double                       // max(0, target − achieved.fractional − plannedRemaining)
    fun rows(tiers: Map<Muscle, MuscleTier>, kb: KnowledgeBase): List<MuscleBandRow>   // §2.14, tier order then knowledge order
    companion object {
        /** Rebuilt on every realize() from logs + the week's remaining planned sessions (+ today's realized plan when given). */
        fun build(week: MesoWeek, sets: List<SetLog>, sessions: List<SessionRecord>, todayPlan: SessionPlan?, kb: KnowledgeBase): WeeklyLedger
    }
}

// core/src/main/kotlin/com/gymcoach/core/volume/MuscleTiers.kt (signature contract)
object MuscleTiers {
    fun assign(priorityMuscles: List<Muscle>): Map<Muscle, MuscleTier>       // §4.3 row "muscle tiers"
}

// core/src/main/kotlin/com/gymcoach/core/volume/TargetPolicy.kt (signature contract)
class TargetPolicy(private val c: PlannerConstants) {
    /** §4.4 weekly targets for week k incl. VOL-08 ramp, holds, FRQ-03 (daysPerWeek == 1), AGE-02 and VOL-09 accustomed volume. `deloadTriggersActive` = PlanInputs.deloadTriggersActive (§2.9): while non-empty EVERY ramp is held (Explanation([VOL-08, DLD-03])). */
    fun weekTargets(goal: Goal, trainingAge: TrainingAge, tiers: Map<Muscle, MuscleTier>, weekIndex: Int, holds: Set<Muscle>, accustomed: Map<Muscle, Double>?, daysPerWeek: Int, age: Int, deloadTriggersActive: List<String> = emptyList()): Map<Muscle, Double>
}

// core/src/main/kotlin/com/gymcoach/core/planner/TimeModel.kt (signature contract)
class TimeModel(private val c: PlannerConstants) {
    /** §4.6 minutes per working set for one block (concessionLevel ≥ 4 → isolation 1.5). */
    fun costPerSet(exercise: Exercise, effectiveLoadType: LoadType, slotHeavy: Boolean, concessionLevel: Int = 0): Double
    /** Whole session incl. superset pairing (REST-03), warm-up and finisher. */
    fun sessionMinutes(blocks: List<ExerciseBlock>, kb: KnowledgeBase, warmupMinutes: Int, finisherMinutes: Int, concessionLevel: Int = 0): Double
    fun warmupMinutes(sessionMinutes: Int, wu06: Boolean): Int
}

// core/src/main/kotlin/com/gymcoach/core/planner/AgePolicy.kt (signature contract; §4.4 "AgePolicy")
class AgePolicy(private val c: PlannerConstants) {
    fun over60(age: Int): Boolean                                    // age ≥ c.age.age02Threshold
    fun setsBand(age: Int, profileSets: Band): Band                  // AGE-02 overrides the profile sets band
    fun repBand(age: Int, exercise: Exercise, profileRange: Band): Band   // compounds → age02CompoundReps; isolation unchanged
    fun restSec(age: Int, defaultRest: Int): Int                     // clamp into age02RestSec
    fun maxStrengthAllowed(age: Int, trainingAge: TrainingAge, allowAdvanced: Boolean): Boolean   // AGE-03
    fun proteinFloorGPerKg(age: Int): Double?                        // AGE-01 (≥ 40 → 1.9), NUT-01 (≥ 65 → 1.2 floor stays)
}

// core/src/main/kotlin/com/gymcoach/core/planner/realize/LayoffPolicy.kt (signature contract)
data class LayoffModifier(val row: Int, val loadFactor: Double, val setFactor: Double?, val setDelta: Int, val rirOffset: Int, val noviceRestart: Boolean, val explanation: Explanation)
class LayoffPolicy(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    /** §4.7 step 2: excess over the planned cadence; null = no modifier. */
    fun forBlock(pattern: MovementPattern, exerciseId: String, today: LocalDate, mesocycle: Mesocycle, inputs: PlanInputs, states: List<ProgressionState>, age: Int, trainingAge: TrainingAge): LayoffModifier?
    fun scheduledGapDays(pattern: MovementPattern, mesocycle: Mesocycle): Int
}

// core/src/main/kotlin/com/gymcoach/core/planner/realize/RirStack.kt (signature contract)
data class RirInput(val profileTargetRir: Int, val weekOffset: Int, val readinessOffset: Int, val layoffOffset: Int, val sleepOffset: Int, val stressOffset: Int, val keepAtMaxOffset: Int, val floor: Int, val fixedDeloadRir: Int?, val fixedEnduranceRir: Int?)
object RirStack {
    /** §4.7: clamp(sum, floor, c.rir.rirCapMax); fixed values bypass the stack. Second element = the RIR-CAP explanation when capped. */
    fun finalRir(input: RirInput, c: PlannerConstants): Pair<Int, Explanation?>
    fun profileTargetRir(prescription: GoalPrescription): Int              // ceil((lo + hi) / 2)
    fun lastWeekTargetRir(prescription: GoalPrescription): Int             // rirLastWeek.hi, else profileTargetRir − 1
}

// core/src/main/kotlin/com/gymcoach/core/planner/realize/LoadZones.kt (signature contract)
object LoadZones {
    /** §4.6 LoadZone.of: rep-band midpoint, or LOAD-04 %1RM cut-points when a HIGH-confidence e1RM exists. */
    fun of(band: Band, targetRir: Int, slotHeavy: Boolean, isPowerProfile: Boolean, e1rm: E1rmEstimate?, c: PlannerConstants): LoadZone
}

// core/src/main/kotlin/com/gymcoach/core/planner/realize/WarmupGenerator.kt (signature contract)
class WarmupGenerator(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    /** §5.7. `firstWorkingLoads` = block id → load of the first WORKING set (already rounded), for WU-02 ramps. */
    fun generate(blocks: List<ExerciseBlock>, sessionMinutes: Int, today: TodayContext, age: Int, limitations: List<Limitation>, equipment: SessionEquipment, params: GymParams, zones: Map<String, LoadZone>): Warmup
}

// core/src/main/kotlin/com/gymcoach/core/mobility/MobilityPlanner.kt (signature contract)
class MobilityPlanner(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    fun cooldown(limitations: List<Limitation>, sessionPatterns: List<MovementPattern>, afterHiit: Boolean): List<DrillPrescription>
    fun restDayCard(limitations: List<Limitation>): List<DrillPrescription>
    /** joint → minutes this week incl. MOB-03 credit (30 s per full-ROM working set of the mapped exercises). */
    fun weeklyMinutes(limitations: List<Limitation>, sets: List<SetLog>, weekStart: LocalDate): Map<Joint, Int>
}

// ---- WP-04 ----------------------------------------------------------------------------------
// core/src/main/kotlin/com/gymcoach/core/progression/PlateauDetector.kt (signature contract)
data class PlateauVerdict(val noProgressCount: Int, val status: ProgressionStatus, val flags: List<Explanation>)
class PlateauDetector(private val c: PlannerConstants) {
    /** PLAT-01/PLAT-03 inside afterSession (§4.9.3); returns the state's new counters/status. Ladder steps are advanced by Planner.rollWeek (WP-03), not here. */
    fun evaluate(state: ProgressionState, summary: SessionSummary, fair: Boolean, weekMode: WeekMode): PlateauVerdict
}

// core/src/main/kotlin/com/gymcoach/core/progression/DeloadPolicy.kt (signature contract)
/** One DLD-03 trigger with its onset (the day it became true) and resolution (first later day it was false); §4.11. */
data class DeloadTrigger(val ruleId: String, val kind: String, val onset: LocalDate, val resolved: LocalDate?, val detail: String)
data class DeloadSignals(val triggers: List<DeloadTrigger>) {
    fun active(today: LocalDate, minDays: Int): List<DeloadTrigger>   // unresolved and onset ≤ today − minDays
}
class DeloadPolicy(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    fun signals(inputs: PlanInputs, states: List<ProgressionState>, today: LocalDate): DeloadSignals
    /** Non-null when ≥ c.plat.dld03MinTriggers active triggers exist whose kind set differs from `alreadyProposed` (§4.11). */
    fun reactive(signals: DeloadSignals, today: LocalDate, alreadyProposed: Set<String>): Explanation?
    /** DLD-02 factors for the realizer's use are read straight from PlannerConstants by WP-03; this is the display/explanation helper. */
    fun scheduledExplanation(week: MesoWeek): Explanation
    fun reentryExplanation(week: MesoWeek): Explanation
}

// core/src/main/kotlin/com/gymcoach/core/progression/ProgressSeries.kt (signature contract)
class ProgressSeries(private val kb: KnowledgeBase) {
    /** (raw weigh-ins, MA7) as two series; raw points carry the NUT-15 noise flag in lowConfidence. */
    fun weight(metrics: List<BodyMetric>): Pair<ChartSeries, ChartSeries>
    /** One point per session with a HIGH e1RM (LOW → lowConfidence = true, dashed). */
    fun e1rm(states: List<ProgressionState>, exerciseId: String): ChartSeries
    /** Best load × reps per session for gym-scoped keys (E1RM-04). */
    fun loadXReps(state: ProgressionState): ChartSeries
    fun volume(sets: List<SetLog>, weekStart: LocalDate, targets: Map<Muscle, Double>, tiers: Map<Muscle, MuscleTier>): List<MuscleBandRow>
    fun volumeByWeek(sets: List<SetLog>, weeks: Int, today: LocalDate, muscle: Muscle): ChartSeries
    fun readiness(checkins: List<ReadinessCheckin>): ChartSeries
    fun restingHr(metrics: List<BodyMetric>): ChartSeries?
    fun trainingLoad(sessions: List<SessionRecord>, weeks: Int, today: LocalDate): List<Pair<LocalDate, Int>>   // RDY-06 Σ RPE × minutes per week
}

// core/src/main/kotlin/com/gymcoach/core/readiness/RedFlags.kt (signature contract)
data class RedFlagItem(val id: String, val text: String, val emergency: Boolean)
object RedFlags {
    val items: List<RedFlagItem>                                     // RED-01 list in §5.2 order
    fun handoffText(emergency: Boolean): String                      // "stop now and call emergency services" / "see a professional now"
}

// core/src/main/kotlin/com/gymcoach/core/readiness/SleepRules.kt (signature contract)
data class SleepModifiers(val atLeastAmber: Boolean, val noMaxStrength: Boolean, val setFactor: Double, val heavyRirOffset: Int, val explanations: List<Explanation>)
class SleepRules(private val c: PlannerConstants) {
    /** SLP-03 from today's check-in and the last 7 days. */
    fun evaluate(today: ReadinessCheckin?, last7: List<ReadinessCheckin>): SleepModifiers
}

// core/src/main/kotlin/com/gymcoach/core/checkin/CheckinBank.kt (signature contract)
class CheckinBank(private val kb: KnowledgeBase) {
    /** §5.2 selection (≤ 8): fixed items, rotation over history, deficit/travel conditions, plus active custom questions. */
    fun select(weekStart: LocalDate, history: List<WeeklyCheckin>, inDeficit: Boolean, travelDaysNextWeek: Boolean, custom: List<CheckinQuestion>): List<CheckinQuestion>
    fun autoAnswers(weekStart: LocalDate, metrics: List<BodyMetric>, sessions: List<SessionRecord>, plannedCount: Int): Map<String, CheckinAnswer>   // Q4, Q5, Q8
}

// core/src/main/kotlin/com/gymcoach/core/checkin/CheckinEvaluator.kt (signature contract)
data class CheckinOutcome(val triggered: List<Explanation>, val proposals: List<PlanMutation>, val pendingModifiers: List<Explanation>, val holds: Set<Muscle>)
class CheckinEvaluator(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    /** §5.2 trigger table; `history` = earlier check-ins newest first (for "two consecutive" rules). */
    fun evaluate(checkin: WeeklyCheckin, history: List<WeeklyCheckin>, inDeficit: Boolean): CheckinOutcome
}

// core/src/main/kotlin/com/gymcoach/core/cardio/CardioPlanner.kt (signature contract)
class CardioPlanner(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    /** §5.4 weekly prescriptions by goal with CAR-06 placement against the week's lifting days; `hiitEligible` per CAR-05 gating. */
    fun weekly(goal: Goal, profile: Profile, age: Int, restingHr: Int?, week: MesoWeek, liftingWeekdays: List<Int>, heavyLegWeekdays: List<Int>, overrides: List<PlanOverride>, hiitEligible: Boolean, equipment: SessionEquipment): List<CardioPrescription>
    fun hiitEligible(cardioLogs: List<CardioLog>, today: LocalDate, medicalGate: Boolean): Boolean
}

// core/src/main/kotlin/com/gymcoach/core/cardio/HiitProtocols.kt (signature contract)
data class HiitProtocol(val kind: CardioKind, val intervals: Int, val workSec: Int, val restSec: Int, val workHr: HrZone, val restHr: HrZone?, val explanation: Explanation)
object HiitProtocols {
    fun protocol(kind: CardioKind, hrMax: Double, intervalsCompleted: Int, advanced: Boolean): HiitProtocol   // CAR-05/09
}

// core/src/main/kotlin/com/gymcoach/core/nutrition/EnergyEstimator.kt (signature contract)
class EnergyEstimator(private val c: PlannerConstants) {
    fun model(profile: Profile, weightKg: Double, age: Int, daysPerWeek: Int, stepsMean14d: Int?, state: NutritionState?): EnergyModel   // NUT-04 REE + activity factor (+ stored adaptive TDEE)
}

// core/src/main/kotlin/com/gymcoach/core/nutrition/TdeeAdapter.kt (signature contract)
class TdeeAdapter(private val c: PlannerConstants) {
    /** Null when the 14-day window lacks ≥ nut04MinKcalLogs kcal logs or ≥ 10 weigh-ins, or contains ≥ 3 travel days. */
    fun review(model: EnergyModel, logs: List<NutritionLog>, trend: List<WeightTrendPoint>, today: LocalDate): TdeeAdjustment?
}

// core/src/main/kotlin/com/gymcoach/core/nutrition/WeightTrend.kt (signature contract)
class WeightTrend(private val c: PlannerConstants) {
    fun series(metrics: List<BodyMetric>): List<WeightTrendPoint>            // NUT-15 MA7 + noise flags
    fun ratePctPerWeek(points: List<WeightTrendPoint>, days: Int = 14): Double?   // Δ(MA7) over `days` as % of body weight per week; null below nut15MinDays
    fun delta14Kg(points: List<WeightTrendPoint>): Double?
}

// core/src/main/kotlin/com/gymcoach/core/nutrition/MacroTargets.kt (signature contract)
class MacroTargets(private val c: PlannerConstants) {
    /** §5.5 Targets incl. the NUT-05 floor rules; `cardioSessionsPerWeek` drives the NUT-08 carb band. */
    fun compute(profile: Profile, weightKg: Double, age: Int, goal: Goal, trainingAge: TrainingAge, energy: EnergyModel, state: NutritionState?, cardioSessionsPerWeek: Int, today: LocalDate): NutritionTargets
    /** NUT-16/NUT-05 14-day review: ±100–200 kcal proposal, the > 1.5 %/week supervision card, or null. */
    fun review(targets: NutritionTargets, observedRatePctPerWeek: Double?, today: LocalDate): Pair<PlanMutation?, Explanation>?
}

// core/src/main/kotlin/com/gymcoach/core/nutrition/SupplementAdvisor.kt (signature contract)
class SupplementAdvisor(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    /** §5.6; `medicalGate` = RED-03 without clearance → every dose text replaced by the gate sentence, gated = true. */
    fun advise(profile: Profile, weightKg: Double, goal: Goal, medicalGate: Boolean, fishServingsPerWeek: Int?, vitDRiskFactors: Int, travelling: Boolean): List<SupplementAdvice>
    fun caffeineCutoffs(bedtimeLocal: String): Pair<String, String>          // ("10:00" for ≥ 200 mg, "14:00" for ≤ 100 mg)
}

// ---- WP-05 ----------------------------------------------------------------------------------
// core/src/main/kotlin/com/gymcoach/core/coach/CoachContextBuilder.kt (signature contract)
data class ContextInputs(
    val today: TodayContext, val units: UnitSystem, val profile: Profile, val trainingAge: TrainingAge, val weightMa7Kg: Double?,
    val gym: GymSet, val availableExerciseIds: List<String>, val mesocycle: Mesocycle?, val weekIndex: Int, val weekSessions: List<SessionRecord>,
    val ledger: WeeklyLedger?, val todaySession: SessionPlan?, val readiness7d: List<ReadinessCheckin>, val states: List<ProgressionState>,
    val recentSessions: List<SessionRecord>, val recentSets: List<SetLog>, val openQuestions: List<CheckinQuestion>, val lastCheckin: WeeklyCheckin?,
    val nutrition: NutritionTargets?, val energy: EnergyModel?, val trendKgPerWeek: Double?, val pendingProposals: List<Proposal>, val appliedSince: List<Proposal>,
)
data class DeltaInputs(val today: LocalDate?, val sessionsLogged: List<SessionRecord>, val sets: List<SetLog>, val proposals: List<Proposal>, val profileChanged: Boolean, val gymChanged: Boolean, val planChangedFields: List<String>, val readiness: ReadinessCheckin?, val checkinAnswered: Boolean,
    val gym: GymSet? = null)   // additive: rendered as gym_today {id,name} so the next delta can detect gym_changed
class CoachContextBuilder(private val kb: KnowledgeBase, private val tokens: TokenEstimator) {
    fun snapshot(inputs: ContextInputs, budgetTokens: Int = 2500): String        // "<context schema=\"1\">{…}</context>", §6.5 key order and truncation
    fun delta(inputs: DeltaInputs): String                                        // "<delta schema=\"1\">{…}</delta>"
    fun delta(inputs: DeltaInputs, proposalResults: Map<String, String>): String  // additive overload (HOME_BRIDGE: tool results inlined)
    fun summaryBlock(summary: String): String                                     // "<summary>…</summary>"
}

// core/src/main/kotlin/com/gymcoach/core/coach/ToolContract.kt (signature contract)
sealed class ParsedTool {
    data class ReadOnly(val kind: String, val args: JsonObject) : ParsedTool()           // kind = tool name
    data class ProposalCall(val mutation: PlanMutation, val rationale: String) : ParsedTool()
    data class Invalid(val errors: List<String>) : ParsedTool()
}
class ToolContract(private val kb: KnowledgeBase, private val validator: MutationValidator, private val clock: Clock) {
    companion object { const val CONTRACT_VERSION: Int = 1 }
    fun parse(toolName: String, input: JsonObject): ParsedTool                     // §6.6 mapping table; clock.today() for SetLimitation.since
    fun validate(mutation: PlanMutation, inputs: PlanInputs, mesocycle: Mesocycle?, today: TodayContext?, nutrition: NutritionTargets?): ValidationResult
}

// core/src/main/kotlin/com/gymcoach/core/coach/ToolExecutor.kt (signature contract)
data class ToolExecution(val resultJson: String, val isError: Boolean)          // resultJson ≤ 2,000 chars
class ToolExecutor(
    private val kb: KnowledgeBase, private val workouts: WorkoutRepository, private val progression: ProgressionRepository,
    private val gyms: GymRepository, private val plans: PlanRepository,
) {
    /** §6.6 read-only tools. gym_id omitted → today.gymId; availability resolved against SessionEquipment(today). Unknown tool → isError. */
    suspend fun execute(name: String, input: JsonObject, today: TodayContext): ToolExecution
}

// core/src/main/kotlin/com/gymcoach/core/coach/ProposalApplier.kt (signature contract)
class ProposalApplier(private val plans: PlanRepository, private val coach: CoachRepository, private val clock: Clock) {
    /** §6.8: appends the PlanOverride (expiresOn = today for TODAY-scope), marks the proposal APPLIED (provisional summary), returns the override. */
    suspend fun apply(proposal: Proposal, today: LocalDate): PlanOverride
    suspend fun reject(proposal: Proposal, reason: String?)
    suspend fun defer(proposal: Proposal)
    suspend fun finalize(proposalId: String, diff: PlanDiff, overrideId: String)   // decideProposal(APPLIED, diff JSON)
    suspend fun markFailed(proposalId: String, message: String)
}

// core/src/main/kotlin/com/gymcoach/core/coach/SpendMeter.kt (signature contract)
class SpendMeter(private val coach: CoachRepository, private val clock: Clock) {
    suspend fun monthToDate(): Double
    suspend fun record(model: String, usage: TokenUsage, backend: BackendMode, conversationId: String): SpendRecord   // PricingTable cost
    suspend fun cacheHitRate(since: Instant): Double?
}

// core/src/main/kotlin/com/gymcoach/core/coach/RedFlagScanner.kt (signature contract)
data class RedFlagHit(val phrase: String, val emergency: Boolean)
object RedFlagScanner { fun scan(text: String): RedFlagHit? }                 // §6.12 vocabulary

// core/src/main/kotlin/com/gymcoach/core/coach/SupplementCaveat.kt (signature contract)
object SupplementCaveat {
    const val TEXT: String = "Not medical advice; check with a physician or pharmacist if you take medication or have a heart, kidney, liver, thyroid or psychiatric condition; prefer third-party-tested products; legal status differs by country."
    fun check(assistantText: String): Boolean                                   // true = caveat must be appended
}

// core/src/main/kotlin/com/gymcoach/core/coach/SystemPrompt.kt (signature contract)
object SystemPrompt {
    const val PROMPT_VERSION: String = "v1"
    fun assemble(kb: KnowledgeBase): String
    fun hash(kb: KnowledgeBase): String                                          // Hashing.sha256Hex(assemble(kb))
}

// core/src/main/kotlin/com/gymcoach/core/coach/wire/RequestBuilder.kt (signature contract)
object RequestBuilder {
    fun build(settings: AppSettings, systemText: String, tools: List<ToolDefinition>?, history: List<ChatMessage>, newUserContent: JsonArray): JsonObject
    fun buildCompaction(settings: AppSettings, compactionPrompt: String, transcriptText: String): JsonObject   // §6.9: no tools, stream false, max_tokens 1024, effort low
    fun buildTest(settings: AppSettings): JsonObject                             // §8.18: max_tokens 32, stream false, thinking disabled, "ping", no tools
}

// core/src/main/kotlin/com/gymcoach/core/coach/TranscriptCompactor.kt (signature contract)
class TranscriptCompactor(private val coach: CoachRepository, private val backendFactory: (BackendMode) -> ClaudeBackend, private val settings: SettingsRepository, private val clock: Clock) {
    /** §6.9: ClaudeBackend.complete(buildCompaction(...)) → summary; marks the old conversation COMPACTED; returns the new OPEN conversation (seedSummary set). Throws IllegalStateException while proposals are PENDING. */
    suspend fun compact(conversationId: String, compactionPrompt: String): ChatConversation
    fun needsCompaction(conversation: ChatConversation, now: Instant): Boolean
}

// core/src/main/kotlin/com/gymcoach/core/coach/PricingTable.kt / TokenEstimator.kt (signature contract)
object PricingTable { fun costUsd(model: String, usage: TokenUsage): Double }   // §6.11
object TokenEstimator { fun estimate(text: String): Int }                        // ≈ chars / 4

// core/src/main/kotlin/com/gymcoach/core/coach/CoachTurnService.kt (signature contract; constructor exactly as §1.5)
/**
 * Everything one turn needs that only :app can compute (PlanCoordinator.buildInputs()/constants() live in :app, §7.6;
 * :core never calls them). Built by CoachViewModel.onSend (§8.17) once per send; CoachTurnService derives the §6.5
 * ContextInputs from it plus its own repositories, and builds `MutationValidator(knowledge, constants)` from it per turn.
 */
data class CoachTurnInputs(
    val today: TodayContext,
    val todaySession: SessionPlan?,           // the open session's frozen plan, else PlanCoordinator.realizeToday(today), else null (rest day / no plan)
    val planInputs: PlanInputs,               // PlanCoordinator.buildInputs(): the 12-week/8-checkin/14-day windows, patternLastTrained, anchor, deloadTriggersProposed …
    val constants: PlannerConstants,          // PlanCoordinator.constants() — user overrides included
    val mesocycle: Mesocycle?,                // PlanRepository.getCurrent()
    val trainingAge: TrainingAge,             // Planner.normalize(planInputs, today.date).trainingAge (§4.2, DEF-01)
    val ledger: WeeklyLedger?,                // WeeklyLedger.build(currentWeek, weekSets, weekSessions, todaySession, kb); null without a plan
    val nutrition: NutritionTargets?,         // MacroTargets.compute(...) (§5.5); null when no body weight is known
    val energy: EnergyModel?,                 // EnergyEstimator.model(...); null when no body weight is known
    val trendKgPerWeek: Double?,              // WeightTrend.delta14Kg(points) / 2, null below nut15MinDays
    val weightMa7Kg: Double?,                 // latest WeightTrendPoint.ma7Kg, null without weigh-ins
    val skipRedFlagScan: Boolean = false,     // true only for the "It's not that — send anyway" re-send (§6.12); the text is sent unchanged
)
class CoachTurnService(
    private val knowledge: KnowledgeBase, private val profile: ProfileRepository, private val gyms: GymRepository, private val plans: PlanRepository,
    private val workouts: WorkoutRepository, private val progression: ProgressionRepository, private val metrics: MetricsRepository,
    private val coach: CoachRepository, private val settings: SettingsRepository, private val backendFactory: (BackendMode) -> ClaudeBackend,
    private val toolExecutor: ToolExecutor, private val spend: SpendMeter, private val network: NetworkMonitor, private val clock: Clock,
) {
    /** §6.7. One user turn: snapshot/delta from `inputs`, tool chain, persistence; proposals validated with MutationValidator(knowledge, inputs.constants). */
    fun send(userText: String, inputs: CoachTurnInputs): Flow<TurnEvent>
    /** "Retry on Sonnet 5" after a refusal (§6.7): new conversation on claude-sonnet-5 for this conversation only, fresh snapshot from `inputs`, re-sends `userText`. */
    fun retryOnSonnet(userText: String, inputs: CoachTurnInputs): Flow<TurnEvent>
    suspend fun expirePendingProposals(now: Instant)
}
```

---
## 3. Knowledge assets — files, schemas, examples, loader API (owned by WP-02; content overlays by WP-09…WP-14)

All assets live in `core/src/main/resources/gymcoach/knowledge/` and are the single source of truth
for tests and the APK (§7.5). Every file starts with `"schema_version": 1`. They are decoded with
`CoreJson.assets` (strict). File list:

| File | Kotlin type | Source | Owner |
|---|---|---|---|
| `exercises.json` | `ExerciseLibraryFile` | R4 §5.6–5.11 (156 exercises), §5.2 (11 rep profiles) | WP-02 |
| `exercise_content/batch_1.json` … `batch_6.json` | `ContentOverlay` | R4 §5.12 batches | WP-09 … WP-14 (one file each; **not** WP-02) |
| `equipment.json` | `EquipmentCatalogFile` | R4 §2.3 (78 items), §2.4 (19 groups/bundles), §2.5 (4 presets) | WP-02 |
| `templates.json` | `TemplatesFile` | §3.4 below | WP-02 |
| `rules.json` | `RulesFile` | R3 §10 + planner conventions (§3.5) | WP-02 |
| `mobility_drills.json` | `DrillsFile` | R3 MOB-07 | WP-02 |
| `checkin_questions.json` | `CheckinQuestionsFile` | R3 CHK-01 | WP-02 |
| `supplements.json` | `SupplementsFile` | R3 §7 | WP-02 |
| `cardio_modalities.json` | `CardioModalitiesFile` | R3 CAR-08 | WP-02 |
| `nutrition_rules.json` | `NutritionTipsFile` | R3 NUT-02/03/12/13/14 | WP-02 |
| `rp_landmarks.json` | `RpLandmarksFile` | R4 §4.5 | WP-02 |
| `load_transfer.json` | `LoadTransferFile` | Appendix C | WP-02 |

Id rule everywhere: `^[a-z][a-z0-9_]{1,63}$`. Muscle weights ∈ {1.0, 0.5, 0.25}.

### 3.1 `exercises.json`

Schema: R4 §6.2 verbatim, plus the top-level `"renamed_ids": {}` map (old id → new id; empty at
v1). Every one of the 156 R4 exercises is transcribed **exactly** from the R4 §5.6–5.11 tables:
`id`, `name`, `aliases` (EN + DE from R4 and the equipment alias style), `pattern`, `muscles`
(weights as printed, order as printed), `equipment_options` (each `;`-separated option becomes one
`{all_of: [...]}` entry, in the printed order; a parenthesised load type becomes `load_type`; the
first option is `preferred: true`), `load_type`, `unilateral`, `difficulty`, `rep_profile`,
`rep_overrides` (only where the R4 profile column says so, e.g. `sissy_squat` "isolation (8–15)" →
`{"hypertrophy": {"range": [8,15], "rir": <copied>, "sets": <copied>}}` for every goal key it applies to — `rir` and
`sets` are copied from the named profile's entry for that goal; only `range` is replaced),
`increment` (from the "inc (kg)" column: `kind: kg` with `default_kg`/`min_kg`; `reps`, `band_level`,
`time`, `distance`, `box_height`, `none`; `direction: assist` for the leading `−`),
`progression` (barbell compounds `linear_load`; dumbbell/machine/cable/kettlebell `double_progression`;
bodyweight `rep_progression`; band `band_progression`; `core_time`/carry `time_progression`/`distance_or_time`;
cardio/mobility `none`), `substitutions` (ranked as printed), `flags` (from the flags column),
`batch`, `tags` (`warmup`, `travel`, `optional_equipment`, `exrx_verified` for daggered rows,
`big_lift` for the six main barbell lifts), `sources`, `content: null`, `content_status: "todo"`.

The 11 rep profiles are R4 §5.2 rows transcribed into `by_goal` with keys `strength`,
`hypertrophy`, `endurance`, `general_fitness`; each `GoalPrescription` has `range`, `rir` (the RIR
band; for "3→1" drifts `rir: [1, 3]` plus `rir_last_week: [1, 1]`), and `sets` (the per-exercise
sets band used by the allocator, §4.6): `barbell_compound` [3,5], `compound` [3,4], `isolation` [2,4],
`isolation_light` [2,4], `bodyweight_progression` [3,4], `core_reps` [2,3], `core_time` [2,3],
`carry` [2,3], `power` [3,5], `cardio` [1,1], `mobility` [2,2]. Unit per profile as in R4.

Example (one rep profile, one exercise — the shape every entry follows):

```json
{
  "schema_version": 1,
  "renamed_ids": {},
  "rep_profiles": [
    {
      "id": "barbell_compound",
      "unit": "reps",
      "by_goal": {
        "strength":        { "range": [3, 6],   "rir": [2, 3], "rir_last_week": [1, 2], "sets": [3, 5], "note": "top set 1-2 reps at RIR 2-3 (LOAD-03)" },
        "hypertrophy":     { "range": [5, 10],  "rir": [1, 3], "rir_last_week": [1, 1], "sets": [3, 5] },
        "endurance":       { "range": [10, 15], "rir": [2, 3], "rir_last_week": [1, 2], "sets": [2, 3] },
        "general_fitness": { "range": [6, 10],  "rir": [2, 3], "rir_last_week": [1, 2], "sets": [3, 5] }
      },
      "notes": "Heavy free-weight compounds. rir_last_week floor is 1 for bar-over-body lifts and never 0 without safeties or a spotter (R4 §5.5).",
      "sources": ["RP chest hypertrophy guide (rpstrength.com, fetched 2026-09-04)", "Stronger by Science, reps-in-reserve (fetched 2026-09-04)", "R3 LOAD-03, RIR-03"]
    }
  ],
  "exercises": [
    {
      "id": "barbell_bench_press",
      "name": "Barbell Bench Press",
      "aliases": ["bench press", "flat bench", "Bankdrücken"],
      "pattern": "horizontal_push",
      "muscles": [ { "muscle": "chest", "weight": 1.0 }, { "muscle": "front_delts", "weight": 0.5 }, { "muscle": "triceps", "weight": 0.5 } ],
      "equipment_options": [
        { "all_of": ["bb_loaded", "bench_flat_any", "rack_or_spotter"], "preferred": true, "note": "Safeties just below chest height, or a spotter." }
      ],
      "load_type": "barbell",
      "unilateral": false,
      "difficulty": "intermediate",
      "rep_profile": "barbell_compound",
      "increment": { "kind": "kg", "default_kg": 2.5, "min_kg": 1.0, "per_hand": false, "direction": "load", "note": "1.0 kg only with 0.5 kg microplates; else 2.5 kg." },
      "progression": "linear_load",
      "substitutions": ["dumbbell_bench_press", "smith_bench_press", "machine_chest_press", "push_up"],
      "flags": { "compound": true, "spinal_loading": false, "shoulder_sensitive": true, "knee_sensitive": false, "low_back_sensitive": false, "requires_spotter_or_safeties": true },
      "batch": 1,
      "tags": ["big_lift", "exrx_verified"],
      "sources": ["ExRx.net Barbell Bench Press (target pec major sternal; synergists pec clavicular, anterior deltoid, triceps)"],
      "content": null,
      "content_status": "todo"
    }
  ]
}
```

`renamed_ids` semantics (B graft): when an exercise id is renamed in a later catalogue version the
old id is kept here; `KnowledgeLoader` applies the map to plans, overrides and `ProgressionKey`s at
load time; an id that is neither present nor renamed renders as "Exercise unavailable in this
version" in the UI (§8.7) and never crashes. `KnowledgeValidator` rule 9 forbids removing an id
without a `renamed_ids` entry (checked against `core/src/test/resources/knowledge/ids_v1.txt`, the
frozen list of 156 ids).

**Safeties / spotter decision (binding for WP-02, WP-03, WP-07, WP-08; resolves the R4 rows-vs-§5.5
tension):** the R4 equipment rows are transcribed *as printed*, so every bar-over-face lift
(`barbell_bench_press`, `incline_barbell_bench_press`, `close_grip_bench_press`, `smith_*` variants
excepted) carries `rack_or_spotter` inside its `all_of`. Consequently those lifts are
**unavailable** at a gym without safeties or a spotter and are substituted (dumbbell / machine /
push-up variants) — never prescribed with an RIR floor. The "RIR floor 2 without safeties" path
(§4.7 step 7, R4 §5.5) exists only for SP-flagged exercises whose *satisfied* equipment option
lacks `rack_or_spotter`: `back_squat`/`safety_bar_squat`/`front_squat` on a `squat_rack` without
safeties (their options use `rack_any`), `good_morning`, and any `requires_spotter_or_safeties`
exercise whose printed option does not name `rack_or_spotter`. `KnowledgeValidationTest`
("SP ⇒ rack/smith option") asserts that every `requires_spotter_or_safeties` exercise has at least
one option naming `rack_or_spotter`, `rack_any` or a smith machine, and that the three barbell
bench variants name `rack_or_spotter` in every option. Toggling *Spotter* in the session-start
sheet (§8.6) makes them available for that day.

### 3.2 `exercise_content/batch_N.json` (six files, one owner each)

```json
{
  "schema_version": 1,
  "batch": 1,
  "entries": {
    "barbell_bench_press": {
      "description": "Two to four sentences: what the exercise trains, why it is in the plan, what a good rep looks like.",
      "setup": ["Bar at chest height on the safeties or a spotter present (R4 SP flag).", "Feet flat, shoulder blades pulled down and back, bar over the eyes at the start."],
      "cues": ["Lower to the lower chest with the elbows at about 45° to the torso.", "Press the bar back up over the shoulders, not straight up.", "Keep the glutes on the bench."],
      "mistakes": ["Elbows flared to 90° (shoulder-sensitive position).", "Bouncing the bar off the chest.", "Losing upper-back tightness at lockout."],
      "safety": ["Without safeties or a spotter stop at RIR 2 (R4 §5.5).", "Clip the plates."],
      "substitution_rationale": { "dumbbell_bench_press": "same pattern, no bar over the face, freer shoulder path", "machine_chest_press": "same pattern, fixed path, no spotter needed" },
      "sources": ["ExRx.net Barbell Bench Press", "NSCA Essentials of Strength Training and Conditioning, 4th ed., ch. 15 (exercise technique)"]
    }
  },
  "status": { "barbell_bench_press": "draft" }
}
```

Authoring rules (R4 §5.13): description 2–4 sentences; `setup` 2–4; `cues` 3–6; `mistakes` 3–5;
`safety` 1–4 and **must** mention the mechanism behind every set flag of the exercise; one source
per exercise family; never a brand name; never a number that contradicts `exercises.json` (authors
cannot change numbers — they do not own that file); objections go to `review_notes`. Every id of the
batch must have an entry; `status` is `"draft"` for authored entries.

**Ownership and the missing-file rule (no file has two owners):** WP-02 does **not** create the
batch files. Each of WP-09 … WP-14 creates its own `batch_N.json` from the shape above (the id
list is R4 §5.12 verbatim for that batch). Until a batch file exists, `KnowledgeLoader` treats it
as "every id of that batch is `todo`" (`exists("exercise_content/batch_N.json") == false` → no
overlay, no error), so the app and `:core` tests build before any batch lands. WP-02 instead owns
`core/src/test/kotlin/com/gymcoach/core/knowledge/ContentBatchesTest.kt`, which asserts for every
batch file that *exists*: `batch` matches the file name, `entries.keys == ` the R4 §5.12 id list
for that batch, every entry satisfies the authoring counts above, and — across all existing files —
no id appears in two batches; plus, for every id of `exercises.json`, `Exercise.batch` names the
batch whose R4 §5.12 list contains it. The Library shows "Description not written yet — muscles,
equipment and numbers are exact" for `todo` entries.

### 3.3 `equipment.json`

Schema: R4 §6.1 verbatim plus top-level `"renamed_ids": {}` and per-preset
`"default_session_minutes"`. All 78 R4 §2.3 items with `aliases` (EN + DE as printed), `params`
exactly as printed (`bar_kg`, `max_kg`, `step_kg`, `min_kg`, `smallest_plate_kg`, `has_microplates`,
`available_kg`, `has_safeties`, `has_pullup_bar`, `is_angled`, `stack_step_kg`, `half_step_kg`,
`plate_loaded`, `sled_kg`, `has_rear_delt_mode`, `heights_cm`, `max_incline_pct`, `levels`),
`session_flag: true` only for `spotter`, `packable: true` for `suspension_trainer`, `jump_rope`,
`resistance_bands`, `band_door_anchor`, `lifting_straps`, and `default_in` from the ✓ columns. The
19 groups/bundles of R4 §2.4. Presets: `base_gym` (60 min), `home_gym` (60), `hotel_gym` (45),
`travel_kit` (null) with the R4 §2.5 item lists and parameter overrides (`base_gym`: dumbbells
max 50 step 2, plates smallest 1.25; `home_gym`: dumbbells max 32 step 2, kettlebells [16, 24],
power_rack has_pullup_bar true; `hotel_gym`: dumbbells max 20 min 2 step 2).

```json
{
  "schema_version": 1,
  "renamed_ids": {},
  "equipment": [
    {
      "id": "dumbbells",
      "name": "Dumbbells",
      "category": "free_weights",
      "aliases": ["DBs", "hex dumbbells", "adjustable dumbbells", "Kurzhanteln"],
      "description": "Fixed or adjustable dumbbells. max_kg is the heaviest pair available; step_kg the rack increment.",
      "params": [
        { "key": "max_kg", "type": "number", "unit": "kg", "default": 50, "min": 1, "max": 100, "description": "Heaviest dumbbell available (per hand)." },
        { "key": "min_kg", "type": "number", "unit": "kg", "default": 2, "min": 0.5, "max": 20 },
        { "key": "step_kg", "type": "number", "unit": "kg", "default": 2, "min": 0.5, "max": 5, "description": "Increment between consecutive dumbbells." }
      ],
      "session_flag": false,
      "packable": false,
      "default_in": ["base_gym", "home_gym", "hotel_gym"],
      "notes": "Hotel racks commonly stop at 20-25 kg; the planner warns when a prescription exceeds max_kg."
    },
    {
      "id": "power_rack", "name": "Power rack / cage", "category": "racks_benches",
      "aliases": ["cage", "full rack", "Power-Rack"],
      "params": [
        { "key": "has_safeties", "type": "boolean", "default": true },
        { "key": "has_pullup_bar", "type": "boolean", "default": true }
      ],
      "default_in": ["base_gym", "home_gym"]
    },
    { "id": "spotter", "name": "Spotter available (session flag)", "category": "accessories", "aliases": ["training partner"], "session_flag": true }
  ],
  "groups": [
    { "id": "rack_or_spotter", "kind": "any_of", "name": "Safeties or spotter", "members": ["power_rack", "half_rack", "spotter"], "notes": "A squat_rack without safeties does not qualify; power_rack/half_rack only with has_safeties = true." },
    { "id": "bb_loaded", "kind": "all_of", "name": "Loaded barbell", "members": ["barbell", "plates"] }
  ],
  "presets": [
    { "id": "hotel_gym", "name": "Hotel gym", "description": "Typical hotel fitness room.", "default_session_minutes": 45,
      "items": [
        { "equipment_id": "dumbbells", "params": { "max_kg": 20, "min_kg": 2, "step_kg": 2 } },
        { "equipment_id": "adjustable_bench" }, { "equipment_id": "mat" },
        { "equipment_id": "treadmill" }, { "equipment_id": "stationary_bike" }, { "equipment_id": "elliptical" }
      ] }
  ]
}
```

### 3.4 `templates.json` — session templates and split rows (§4.5 uses these)

Slot notation used below: `role:patterns(heavy)(prefer)(target)`; `*` = required; `⟂` = superset
pair (`superset_with` = the paired slot id). Every template becomes a `SessionTemplate` with slot ids
`s1`, `s2`, …; JSON example of one template and one split:

```json
{
  "schema_version": 1,
  "session_templates": [
    {
      "id": "UPPER_A", "name": "Upper A",
      "slots": [
        { "slot_id": "s1", "role": "primary",   "patterns": ["horizontal_push"], "heavy": true,  "prefer_load_types": ["barbell", "machine"], "required": true },
        { "slot_id": "s2", "role": "primary",   "patterns": ["horizontal_pull"], "required": true },
        { "slot_id": "s3", "role": "secondary", "patterns": ["vertical_push"], "required": true },
        { "slot_id": "s4", "role": "secondary", "patterns": ["vertical_pull", "horizontal_pull"], "required": true },
        { "slot_id": "s5", "role": "isolation", "patterns": ["shoulder_abduction"], "target_muscles": ["side_delts"], "required": true, "superset_with": "s6" },
        { "slot_id": "s6", "role": "isolation", "patterns": ["shoulder_er_rear_delt"], "target_muscles": ["rear_delts"], "required": true, "superset_with": "s5" },
        { "slot_id": "s7", "role": "accessory", "patterns": ["chest_fly", "elbow_extension"], "target_muscles": ["chest", "triceps"], "required": false, "superset_with": "s8" },
        { "slot_id": "s8", "role": "isolation", "patterns": ["elbow_flexion"], "target_muscles": ["biceps"], "required": false, "superset_with": "s7" }
      ]
    }
  ],
  "splits": [
    { "kind": "upper_lower_4", "days_per_week": 4, "session_template_ids": ["UPPER_A", "LOWER_A", "UPPER_B", "LOWER_B"], "default_weekdays": [1, 2, 4, 5] }
  ]
}
```

Full template set (WP-02 transcribes each into JSON exactly as listed):

- **UPPER_A** (as in the example above).
- **UPPER_B**: `s1 primary:vertical_pull*` · `s2 primary:incline_push*` · `s3 secondary:vertical_push (heavy, prefer barbell)*` · `s4 secondary:horizontal_pull (prefer dumbbell,machine; target upper_back)*` · `s5 isolation:shoulder_abduction (side_delts)* ⟂ s6 isolation:shoulder_er_rear_delt (rear_delts)*` · `s7 accessory:chest_fly|elbow_extension (chest,triceps) ⟂ s8 isolation:elbow_flexion (biceps)` · `s9 optional:scapular_elevation|grip`.
- **LOWER_A**: `s1 primary:squat (heavy, prefer barbell)* ` · `s2 secondary:hinge (target hamstrings)*` · `s3 accessory:squat|lunge_split (prefer machine,dumbbell)` · `s4 isolation:knee_flexion_isolation (hamstrings)*` · `s5 isolation:calf (calves)* ⟂ s6 core:core_flexion|core_anti_extension (abs)`.
- **LOWER_B**: `s1 primary:hinge (heavy, prefer barbell)*` · `s2 secondary:squat|lunge_split (prefer machine,dumbbell)*` · `s3 accessory:hip_extension_isolation (glutes)` · `s4 accessory:lunge_split` · `s5 isolation:knee_flexion_isolation (hamstrings)*` · `s6 isolation:calf (calves)* ⟂ s7 core:core_anti_rotation|core_flexion (abs, obliques)`.
- **FULL_A**: `s1 primary:squat (heavy)*` · `s2 primary:horizontal_push*` · `s3 primary:horizontal_pull*` · `s4 secondary:hinge*` · `s5 secondary:vertical_push` · `s6 isolation:priority ⟂ s7 isolation:priority` (empty `target_muscles` = "two largest deficits after compounds", §4.6) · `s8 core:core_anti_extension|core_flexion`.
- **FULL_B**: `s1 primary:hinge (heavy)*` · `s2 primary:vertical_pull*` · `s3 primary:incline_push|vertical_push*` · `s4 secondary:lunge_split*` · `s5 secondary:horizontal_pull` · `s6 isolation:priority ⟂ s7 isolation:priority` · `s8 core:core_anti_rotation|core_flexion`.
- **FULL_C**: `s1 primary:squat (prefer machine,dumbbell)*` · `s2 primary:horizontal_push (prefer dumbbell,machine)*` · `s3 primary:horizontal_pull*` · `s4 secondary:hip_extension_isolation|hinge (glutes)*` · `s5 isolation:shoulder_abduction (side_delts) ⟂ s6 isolation:shoulder_er_rear_delt (rear_delts)` · `s7 isolation:calf (calves)*` · `s8 core:core_flexion|core_anti_extension`.
- **UPPER_X** (3-day U/L/F): identical to UPPER_A. **LOWER_X**: identical to LOWER_A. The 3-day
  intermediate split is `UPPER_LOWER_FULL_3 = [UPPER_X, LOWER_X, FULL_A]`.
- **PUSH**: `s1 primary:horizontal_push (heavy)*` · `s2 primary:vertical_push*` · `s3 secondary:incline_push|chest_fly*` · `s4 isolation:shoulder_abduction (side_delts)*` · `s5 isolation:elbow_extension (triceps) ⟂ s6 isolation:elbow_extension (triceps)`.
- **PULL**: `s1 primary:vertical_pull*` · `s2 primary:horizontal_pull (heavy)*` · `s3 secondary:horizontal_pull*` · `s4 isolation:shoulder_er_rear_delt (rear_delts)*` · `s5 isolation:elbow_flexion (biceps) ⟂ s6 isolation:elbow_flexion (biceps)` · `s7 optional:grip|carry`.
- **LEGS**: `s1 primary:squat (heavy)*` · `s2 primary:hinge*` · `s3 secondary:lunge_split*` · `s4 isolation:knee_flexion_isolation (hamstrings)*` · `s5 accessory:knee_extension_isolation|hip_extension_isolation` · `s6 isolation:calf (calves)* ⟂ s7 core:core_anti_extension|core_flexion`.
- **TRAVEL_A**: `s1 primary:squat|lunge_split*` · `s2 primary:hinge*` · `s3 primary:horizontal_push*` · `s4 primary:horizontal_pull|vertical_pull*` · `s5 isolation:shoulder_abduction (side_delts) ⟂ s6 accessory:horizontal_push|core_anti_extension`.
- **TRAVEL_B**: `s1 primary:lunge_split|squat*` · `s2 primary:hip_extension_isolation|hinge*` · `s3 primary:vertical_push|incline_push*` · `s4 primary:horizontal_pull*` · `s5 isolation:shoulder_abduction (side_delts) ⟂ s6 isolation:elbow_flexion|elbow_extension` (side_delts get a second exposure so a travel week reaches VOL-01 for every TIER_1 muscle, TRV-01; G5).

**STRENGTH variants** (goal == STRENGTH only; they are listed as their **own split rows** in
`templates.json` — same `kind` as the base row, `for_goals: ["strength"]` — and `SplitSelector`
*selects* that row; there is no code-side template substitution; §4.5, H16). A slot with
`backoff_of: "<templateId>.<slotId>"` is pinned to
the **same exercise** the referenced heavy slot selected and is realized as the back-off day
(`load03BackoffReps` 4–8 at `load03BackoffFactor` 0.85 × the heavy-day working load); the
referenced slot must be `heavy: true` **and `<templateId>` must be an id that is actually listed
in the same strength split row** (never a base id such as `UPPER_A` when the row carries
`UPPER_A_S4`) — `KnowledgeValidator` rule 10 checks this per split row, so a reference can always be
resolved inside the week that uses it. Where the heavy partner differs between splits, the
template has one variant per split (`_S4` for `upper_lower_4`/`ul_6`, `_S5` for `ul_ppl_5`). Only
the differences from the base template are listed:

- **UPPER_A_S4** = UPPER_A with `s3 secondary:vertical_push (backoff_of UPPER_B_S.s3)*` (`s1` stays the heavy bench).
- **UPPER_A_S5** = UPPER_A with `s3 secondary:vertical_push (backoff_of PUSH_S.s2)*` (`s1` stays the heavy bench).
- **UPPER_B_S** = UPPER_B with `s2 primary:horizontal_push (backoff_of UPPER_A_S4.s1)*` (incline moves to `s7 accessory:incline_push|chest_fly|elbow_extension`; `s3` stays the heavy OHP).
- **LOWER_A_S** = LOWER_A with `s2 secondary:hinge (backoff_of LOWER_B_S.s1, target hamstrings)*` (`s1` stays the heavy squat).
- **LOWER_A_S5** = LOWER_A with `s2 secondary:hinge (backoff_of LEGS_S.s2, target hamstrings)*` (`s1` stays the heavy squat).
- **LOWER_B_S** = LOWER_B with `s2 secondary:squat (backoff_of LOWER_A_S.s1, prefer barbell)*` (`s1` stays the heavy hinge).
- **PUSH_S** = PUSH with `s1 primary:horizontal_push (backoff_of UPPER_A_S5.s1)*` (no longer heavy) and `s2 primary:vertical_push (heavy, prefer barbell)*`.
- **PULL_S** = PULL unchanged (rows are not main lifts; listed for symmetry).
- **LEGS_S** = LEGS with `s1 primary:squat (backoff_of LOWER_A_S5.s1)*` (no longer heavy) and `s2 primary:hinge (heavy, prefer barbell)*`.
- **FULL_A_S** = FULL_A with `s1 primary:squat (backoff_of LOWER_X.s1)*` and `s2 primary:horizontal_push (backoff_of UPPER_X.s1)*` (neither heavy any more; hinge and vertical push have no heavy slot in this split, so invariant 17 does not require a back-off for them).
- Strength split rows — **separate `SplitTemplate` entries** in `templates.json.splits`, same
  `kind` as the base row, `for_goals: ["strength"]` (table below): `upper_lower_4` → [UPPER_A_S4,
  LOWER_A_S, UPPER_B_S, LOWER_B_S]; `ul_ppl_5` → [UPPER_A_S5, LOWER_A_S5, PUSH_S, PULL_S, LEGS_S];
  `ul_6` → [UPPER_A_S4, LOWER_A_S, UPPER_B_S, LOWER_B_S, UPPER_A_S4, LOWER_A_S] — this row
  **replaces** the base-id `ul_6` row (there is no non-strength `ul_6`; a template that appears twice
  holds the same main lift on both days — §4.6 pre-pass); `upper_lower_full_3` → [UPPER_X, LOWER_X,
  FULL_A_S] with `for_training_ages: [intermediate, advanced]`. The non-strength `upper_lower_4`,
  `ul_ppl_5` and `upper_lower_full_3` rows carry `for_goals: [hypertrophy, general_fitness,
  fat_loss, endurance_support]`. 1–2-day, `full_body_3` and travel splits have no strength variant
  (one exposure per lift; `Explanation(FRQ-02)` on the mesocycle notes it — invariant 17 requires
  that note on those rows, §4.16). `KnowledgeValidator` rule 10 runs over these real rows.
  Resolution table (heavy → back-off): 4/6-day bench `UPPER_A_S4.s1 → UPPER_B_S.s2`,
  OHP `UPPER_B_S.s3 → UPPER_A_S4.s3`, squat `LOWER_A_S.s1 → LOWER_B_S.s2`, hinge `LOWER_B_S.s1 →
  LOWER_A_S.s2`; 5-day bench `UPPER_A_S5.s1 → PUSH_S.s1`, OHP `PUSH_S.s2 → UPPER_A_S5.s3`, squat
  `LOWER_A_S5.s1 → LEGS_S.s1`, hinge `LEGS_S.s2 → LOWER_A_S5.s2`; 3-day squat `LOWER_X.s1 →
  FULL_A_S.s1`, bench `UPPER_X.s1 → FULL_A_S.s2`. `PlanValidator` invariant 17 checks the result.

Splits (`kind`, days, templates in weekly order, default ISO weekdays, applicability — the `for`
column is transcribed literally into `for_goals` / `for_training_ages`; an empty list means "any";
rows are listed in this file order and `SplitSelector` takes the first applicable row, §4.5):

| kind | days | templates | default weekdays | `for_goals` | `for_training_ages` |
|---|---|---|---|---|---|
| `full_body_1` | 1 | FULL_A | [3] | [] | [] |
| `full_body_2` | 2 | FULL_A, FULL_B | [1, 4] | [] | [] |
| `full_body_3` | 3 | FULL_A, FULL_B, FULL_C | [1, 3, 5] | [] | [novice] |
| `full_body_3` | 3 | FULL_A, FULL_B, FULL_C | [1, 3, 5] | [endurance_support] | [] |
| `upper_lower_full_3` | 3 | UPPER_X, LOWER_X, FULL_A | [1, 3, 5] | [hypertrophy, general_fitness, fat_loss, endurance_support] | [intermediate, advanced] |
| `upper_lower_full_3` | 3 | UPPER_X, LOWER_X, FULL_A_S | [1, 3, 5] | [strength] | [intermediate, advanced] |
| `upper_lower_4` | 4 | UPPER_A, LOWER_A, UPPER_B, LOWER_B | [1, 2, 4, 5] | [hypertrophy, general_fitness, fat_loss, endurance_support] | [] |
| `upper_lower_4` | 4 | UPPER_A_S4, LOWER_A_S, UPPER_B_S, LOWER_B_S | [1, 2, 4, 5] | [strength] | [] |
| `ul_ppl_5` | 5 | UPPER_A, LOWER_A, PUSH, PULL, LEGS | [1, 2, 3, 5, 6] | [hypertrophy, general_fitness, fat_loss, endurance_support] | [] |
| `ul_ppl_5` | 5 | UPPER_A_S5, LOWER_A_S5, PUSH_S, PULL_S, LEGS_S | [1, 2, 3, 5, 6] | [strength] | [] |
| `ppl_6` | 6 | PUSH, PULL, LEGS, PUSH, PULL, LEGS | [1, 2, 3, 4, 5, 6] | [hypertrophy, general_fitness, fat_loss, endurance_support] | [] |
| `ul_6` | 6 | UPPER_A_S4, LOWER_A_S, UPPER_B_S, LOWER_B_S, UPPER_A_S4, LOWER_A_S | [1, 2, 3, 4, 5, 6] | [strength] | [] |
| `travel_2` | 2 | TRAVEL_A, TRAVEL_B | [2, 4] | [] | [] |

`travel_2` is chosen by week mode (TRAVEL_MAINTENANCE, §4.14), never by the row walk. The two
`full_body_3` rows read: NOVICE with any goal; any training age with goal ENDURANCE_SUPPORT. Every
`(daysPerWeek 1–6, goal, trainingAge)` combination of §2.2 matches at least one row (ENDURANCE_SUPPORT
at 3 days always lands on `full_body_3`, at 6 days on `ppl_6`; a NOVICE with goal STRENGTH at 3 days
lands on `full_body_3` and gets the FRQ-02 note). `SplitSelectorTest` asserts this exhaustively.

### 3.5 `rules.json` — every rule with provenance; constants = `PlannerConstants` defaults

One `RuleEntry` per R3 §10 row (statement verbatim from the R3 table, `confidence` = the R3 tag
**normalised** by the rule below, `citation` = the "Key source" column, `url` where R3 §11 has one) plus one entry per planner
convention — every id of `RuleIds.ALL` that is not an R3 id: `SEL`, `SUB`, `REBAL`, `TIER`,
`TIME-L1` … `TIME-L7`, `R4-2.6`, `R4-4.5`, `R4-5.4`, `R4-5.5`, `RIR-CAP`, `ROUNDING`, `OVERRIDE` —
with `confidence: "weak"` and `citation: "planner convention (SPEC §<section>)"`
(`ROUNDING`: "load rounded to what the gym can load; > 5 % rounding error is reported",
citation "planner convention (SPEC §3.9)"; `OVERRIDE`: "a user or coach override replaced the
planner's choice", citation "planner convention (SPEC §4.7)") **plus one entry each for the R3 ids
that are defined in prose but have no §10 row** — `POL-03` (R3 §0.2, `section: "Policy"`,
`confidence: "strong"`, citation `"R3 §0.2"`, `note: "policy statement, not an evidence claim"`),
`DEF-06` and `DEF-07` (R3 §0.3, `section: "Definitions"`, `confidence: "moderate"`, citation
`"R3 §0.3"`), `NUT-18` (R3 §6, `section: "Nutrition"`, `confidence: "strong"`, citation `"R3 §6"`,
the same policy note) — `statement` copied from the R3 prose sentence, `constants: {}`,
`user_adjustable: false`. Without these four the file built from the §10 table alone lacks ids
that `RuleIds.ALL` contains. `KnowledgeValidationTest` (rule 11) and `RuleCoverageTest` assert
`RuleIds.ALL ⊆ rules.json ids`. A constant is attached to the rule its **field prefix** names
(`ruleIdOf`), even when that rule's §10 statement omits the number: `CAR-05` carries
`hiitBaseWeeks: 4` (R3's CAR-05 prose says "requires ≥ 4 weeks of zone-2 base"; the CAR-09 row
restates it) — `RuleBookTest` checks the prefix mapping and the values, never the statement text.

**Confidence normalisation** (R3 §0.1 prints composite tags; `Confidence` is a single enum, §2.2):
`confidence` = the **weakest component** of the printed R3 tag — `strong/moderate`,
`strong–moderate` → `moderate`; `moderate/weak`, `weak/moderate`, `strong/weak`,
`strong/weak/moderate`, `strong (effect)/weak (relevance)` → `weak`; `weak/contested` →
`contested`; `policy`, `strong (policy)` → `strong` with `note: "policy statement, not an evidence
claim"`; a plain `strong` / `moderate` / `weak` / `contested` stays as printed; R3 §12 items are
`unverified`. Whenever a tag is normalised the printed tag is copied **verbatim** into `note`
(`note: "R3 tag: strong/weak/moderate"`; the policy note is appended after it with `"; "`).
The same rule applies to `SupplementEntry.evidence` (R3 §7 tables) and `NutritionTip.confidence`
(R3 §6). `user_adjustable` follows the **normalised** tag (true for weak/contested/unverified).
`RuleBookTest` asserts that every `rules.json` entry decodes under `CoreJson.assets` (strict) and that
every entry whose `note` names a composite tag (`"R3 tag: "` prefix) carries the normalised value the
rule above prescribes for that tag.

**Constants key convention** (`RuleBookTest`, §10.2, checks it both ways — every leaf field of
`PlannerConstants` ↔ one constant of the rule `PlannerConstants.ruleIdOf(field)`, equal value):
`key = field name with its rule prefix removed and the first letter lower-cased`, where the prefix
is `fieldName.takeWhile { !it.isUpperCase() }` (the same prefix `ruleIdOf` uses): `vol04Novice` →
`novice`, `tierTier2Factor` → `tier2Factor`, `selPatternPref` → `patternPref`, `r426KeepAtMaxThreshold`
→ `keepAtMaxThreshold`, `rirCapMax` → `capMax`. A `Band` field produces two constants `<key>.lo`
and `<key>.hi`; a `List<Double>` / `List<Int>` field produces `<key>.0`, `<key>.1`, …; `Boolean`
fields encode as 0/1; `Int` and `Double` fields one constant each. `user_adjustable` is true for
every weak/contested/unverified entry and false otherwise.

```json
{
  "schema_version": 1,
  "rules": [
    { "id": "VOL-04", "section": "Resistance training / Weekly volume",
      "statement": "Hypertrophy sets/muscle/week: novice 8-12, intermediate 12-18, advanced 15-25 (fractional). Start each mesocycle at the lower bound.",
      "confidence": "weak", "citation": "Schoenfeld 2017; Pelland 2025", "url": "https://pubmed.ncbi.nlm.nih.gov/27433992/",
      "constants": { "novice.lo": 8, "novice.hi": 12, "intermediate.lo": 12, "intermediate.hi": 18, "advanced.lo": 15, "advanced.hi": 25 },
      "user_adjustable": true, "note": "moderate for 10-20 being productive; weak for the class boundaries" },
    { "id": "E1RM-01", "section": "Resistance training / Estimated 1RM",
      "statement": "Epley w(1+r/30); Brzycki w*36/(37-r); mean of both for r <= 10.",
      "confidence": "strong", "citation": "LeSuer 1997", "constants": {}, "user_adjustable": false },
    { "id": "TIER", "section": "Planner conventions",
      "statement": "Tier-1 muscles get the VOL-04 band; tier-2 get round(0.6 x band) with floor VOL-01; tier-3 have no target and an advisory direct cap of 6.",
      "confidence": "weak", "citation": "planner convention (SPEC §4.4); VOL-04 was measured on large muscle groups, not small ones",
      "constants": { "tier2Factor": 0.6, "tier3DirectCap": 6 }, "user_adjustable": true },
    { "id": "WU-02", "section": "Warm-up",
      "statement": "Ramp: heavy 40 % x 8, 55 % x 5, 70 % x 3, 80-85 % x 1-2; moderate 50 % x 8, 70 % x 5; machine 60 % x 8.",
      "confidence": "moderate", "citation": "Fradkin 2010; ACSM 2009",
      "constants": { "heavyPcts.0": 0.40, "heavyPcts.1": 0.55, "heavyPcts.2": 0.70, "heavyPcts.3": 0.825, "heavyReps.0": 8, "heavyReps.1": 5, "heavyReps.2": 3, "heavyReps.3": 2,
                     "moderatePcts.0": 0.50, "moderatePcts.1": 0.70, "moderateReps.0": 8, "moderateReps.1": 5, "lightPcts.0": 0.60, "lightReps.0": 8,
                     "isolationPct": 0.55, "warmPatternPct": 0.65, "rampRestSec": 60 },
      "user_adjustable": false },
    { "id": "ROUNDING", "section": "Planner conventions",
      "statement": "A prescribed load is rounded to what the gym can load (bar + plate pairs, dumbbell rack, stack step); a rounding error above 5 % is shown as a modifier.",
      "confidence": "weak", "citation": "planner convention (SPEC §3.9)", "constants": {}, "user_adjustable": true }
  ]
}
```

### 3.6 `mobility_drills.json` (MOB-07)

One `Drill` per cell of the R3 MOB-07 table, `kind` = column (`dynamic` / `static` /
`strength_through_rom`), `joint` = row, `patterns` = the session patterns the row prepares
(hips → squat, hinge, lunge_split; t_spine → vertical_push, vertical_pull, horizontal_pull;
shoulders → horizontal_push, incline_push, vertical_push, chest_fly; ankles → squat, lunge_split,
calf; hamstrings → hinge; wrists → horizontal_push, elbow_flexion), `limitation_regions`
(hips → HIP; t_spine → SHOULDER, NECK; shoulders → SHOULDER; ankles → ANKLE, KNEE; hamstrings →
LOW_BACK; wrists → ELBOW_WRIST), dose (`dose_reps: [8, 10]` for dynamic per MOB-01; `dose_seconds:
[30, 60]` for static per MOB-02), and `exercise_id` when the drill is an R4 mobility exercise
(`hip_90_90`, `couch_stretch`, `worlds_greatest_stretch`, `ankle_dorsiflexion_rock`,
`thoracic_rotation`, `cat_cow`, `shoulder_pass_through`, `wall_slide`, `band_pull_apart`,
`band_external_rotation`, `foam_rolling`, `dead_hang`, `bodyweight_squat`, `glute_bridge`,
`band_lateral_walk`). Example:

```json
{ "schema_version": 1, "drills": [
  { "id": "leg_swings", "name": "Leg swings (front/side)", "joint": "hips", "kind": "dynamic", "patterns": ["squat", "hinge", "lunge_split"], "limitation_regions": ["hip"], "dose_reps": [8, 10], "sources": ["R3 MOB-07"] },
  { "id": "hip_90_90", "name": "90/90 hip switch", "joint": "hips", "kind": "dynamic", "patterns": ["squat", "lunge_split"], "limitation_regions": ["hip"], "dose_reps": [8, 10], "exercise_id": "hip_90_90", "sources": ["R3 MOB-07"] },
  { "id": "half_kneeling_hip_flexor_stretch", "name": "Half-kneeling hip-flexor stretch", "joint": "hips", "kind": "static", "limitation_regions": ["hip"], "dose_seconds": [30, 60], "sources": ["R3 MOB-02"] },
  { "id": "knee_to_wall_pulses", "name": "Knee-to-wall pulses", "joint": "ankles", "kind": "dynamic", "patterns": ["squat", "lunge_split", "calf"], "limitation_regions": ["ankle", "knee"], "dose_reps": [8, 10], "exercise_id": "ankle_dorsiflexion_rock", "sources": ["R3 MOB-07"] }
] }
```

### 3.7 `checkin_questions.json` (CHK-01, all 15)

```json
{ "schema_version": 1, "questions": [
  { "id": "Q1",  "text": "Average sleep hours this week?", "answer_type": "number", "trigger_rule_ids": ["SLP-03"], "trigger_condition": "< 7", "always_ask": true },
  { "id": "Q2",  "text": "Sleep quality on most nights?", "answer_type": "scale5", "trigger_rule_ids": ["SLP-03"], "trigger_condition": "<= 2" },
  { "id": "Q3",  "text": "How many days did you hit your protein target?", "answer_type": "number", "trigger_rule_ids": ["NUT-01"], "trigger_condition": "<= 4", "always_ask": true },
  { "id": "Q4",  "text": "7-day average body weight vs last week", "answer_type": "number", "auto_source": "weight_trend", "trigger_rule_ids": ["NUT-16"], "trigger_condition": "outside goal band", "always_ask": true },
  { "id": "Q5",  "text": "Average daily steps?", "answer_type": "number", "auto_source": "steps", "trigger_rule_ids": ["STP-02"], "trigger_condition": "< STP-01 target" },
  { "id": "Q6",  "text": "Stress level this week?", "answer_type": "scale5", "trigger_rule_ids": ["RDY-05"], "trigger_condition": ">= 4 on two consecutive check-ins" },
  { "id": "Q7",  "text": "Any joint pain above 3/10 during training? (which)", "answer_type": "text", "trigger_rule_ids": ["INJ-00", "RED-02"], "trigger_condition": "yes -> INJ; > 10 days -> RED-02", "always_ask": true },
  { "id": "Q8",  "text": "Sessions completed vs planned", "answer_type": "number", "auto_source": "sessions_completed", "trigger_rule_ids": ["TIME-01", "SPLIT-01"], "trigger_condition": "< 70 %", "always_ask": true },
  { "id": "Q9",  "text": "Alcohol: standard drinks this week?", "answer_type": "number", "trigger_rule_ids": ["NUT-13"], "trigger_condition": "> 7 or any day > 3" },
  { "id": "Q10", "text": "Days travelling / hotel-gym days next week?", "answer_type": "number", "trigger_rule_ids": ["TRV-01"], "trigger_condition": ">= 3" },
  { "id": "Q11", "text": "Motivation to train next week?", "answer_type": "scale5", "trigger_rule_ids": ["DLD-03"], "trigger_condition": "<= 2" },
  { "id": "Q12", "text": "Soreness lasting more than 48 h after any session?", "answer_type": "yesno", "trigger_rule_ids": ["VOL-08"], "trigger_condition": "yes twice -> pause ramp" },
  { "id": "Q13", "text": "Caffeine after 15:00 on 3 or more days?", "answer_type": "yesno", "trigger_rule_ids": ["SUP-02"], "trigger_condition": "yes" },
  { "id": "Q14", "text": "Did you finish planned work sets at the target RIR most days?", "answer_type": "scale5", "trigger_rule_ids": ["PROG-04"], "trigger_condition": "<= 2" },
  { "id": "Q15", "text": "Appetite/hunger (1 = none, 5 = constant)", "answer_type": "scale5", "trigger_rule_ids": ["NUT-17"], "trigger_condition": ">= 4 for two weeks in a deficit", "only_in_deficit": true }
] }
```

### 3.8 `supplements.json`, `cardio_modalities.json`, `nutrition_rules.json`, `rp_landmarks.json`, `load_transfer.json`

- `supplements.json`: `caveat_sup12` = the four SUP-12 sentences verbatim from R3; `priority_order` =
  "total protein, calories, sleep, training consistency, then creatine and caffeine"; `entries` =
  SUP-01…SUP-10 (tier A/B) and one entry per SUP-11 row (tier C, `do_not_reason`), each with `dose`,
  `timing`, `evidence`, `citation`, `gated_by_medical` (SUP-01 → `["kidney_disease"]`; SUP-02 →
  `["uncontrolled_hypertension", "heart_disease", "pregnancy"]`; every tier-A/B entry additionally
  gated by `doctor_restriction`), `legal_note` (SUP-08 melatonin: "prescription-only in several EU
  countries at ≥ 2 mg — check local rules; not verified live (R3 §12.13)").
- `cardio_modalities.json`: the CAR-08 table, one row per R4 cardio exercise id
  (`treadmill` walk vs run are two rows: `treadmill` with `is_running: false` (incline walk) and
  the same id flagged by the planner's `CardioKind`; `outdoor_run_walk` likewise), `leg_day_adjacency`
  from the table, `knee_impact` true for running, jump rope, stair climber.
- `nutrition_rules.json`: display tips for NUT-02, NUT-03, NUT-12, NUT-13, NUT-14 (1)–(7) as
  `NutritionTip`s with rule id, confidence and citation.
- `rp_landmarks.json`: the R4 §4.5 table verbatim (`source: "rpstrength.com hypertrophy guides"`,
  `fetched_on: "2026-09-04"`), one row per RP muscle mapped to the app muscle id (Back → `lats`,
  documented as the back aggregate; Abs → `abs`; Traps → `traps_upper`).
- `load_transfer.json`: Appendix C seeds, `unilateral_factor: 0.5`.

### 3.9 Knowledge API (signature contract; bodies by WP-02)

Every block below is a **signature contract** (preamble convention): one file per `// path`,
public names and parameter lists exact, WP-02 adds `package`, explicit imports (the wildcard
`import com.gymcoach.core.model.*` shown is illustrative only, HR-1) and the bodies of the
bodiless members.

```kotlin
// core/src/main/kotlin/com/gymcoach/core/knowledge/KnowledgeBase.kt (signature contract)
package com.gymcoach.core.knowledge

import com.gymcoach.core.model.*   // illustrative; explicit imports in the real file (HR-1)

class KnowledgeBase(
    val exercises: Map<String, Exercise>,                 // by id, content overlays merged, ordered by (batch, id)
    val exerciseOrder: List<String>,                      // deterministic tie-break order (HR-18)
    val repProfiles: Map<String, RepProfile>,
    val equipment: Map<String, EquipmentDef>,
    val groups: Map<String, EquipmentGroup>,
    val presets: Map<String, EquipmentPreset>,
    val renamedExerciseIds: Map<String, String>,
    val renamedEquipmentIds: Map<String, String>,
    val templates: Map<String, SessionTemplate>,
    val splits: List<SplitTemplate>,
    val rules: RuleBook,
    val drills: List<Drill>,
    val checkinQuestions: List<CheckinQuestionDef>,
    val supplements: SupplementsFile,
    val cardioModalities: Map<String, CardioModalityEntry>,
    val nutritionTips: List<NutritionTip>,
    val rpLandmarks: Map<Muscle, RpLandmark>,
    val loadTransfer: LoadTransferFile,
) {
    fun exercise(id: String): Exercise? = exercises[id] ?: renamedExerciseIds[id]?.let { exercises[it] }
    fun requireExercise(id: String): Exercise = checkNotNull(exercise(id)) { "unknown exercise id $id" }
    fun repProfile(exercise: Exercise): RepProfile = checkNotNull(repProfiles[exercise.repProfile]) { "unknown rep profile ${exercise.repProfile}" }
    fun prescription(exercise: Exercise, goal: Goal): GoalPrescription =
        exercise.repOverrides[goal.repProfileKey] ?: repProfile(exercise).forGoal(goal)
    fun byPattern(pattern: MovementPattern): List<Exercise> = exerciseOrder.mapNotNull { exercises[it] }.filter { it.pattern == pattern }
    fun searchByNameOrAlias(query: String): List<Exercise>   // case-insensitive contains over name + aliases (EN/DE)
    fun searchEquipment(query: String): List<EquipmentDef>
}
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/knowledge/KnowledgeLoader.kt (signature contract)
class KnowledgeLoader(private val json: kotlinx.serialization.json.Json) {
    /**
     * Reads every file of §3 from the source, merges content overlays, applies renamed ids. Throws IllegalStateException
     * naming the path when a REQUIRED file is missing; a missing exercise_content/batch_N.json is NOT an error
     * (every id of that batch stays `todo`, §3.2).
     */
    fun load(source: com.gymcoach.core.repo.KnowledgeSource): KnowledgeBase
}
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/knowledge/ClasspathKnowledgeSource.kt (copied verbatim; complete)
package com.gymcoach.core.knowledge

class ClasspathKnowledgeSource : com.gymcoach.core.repo.KnowledgeSource {
    override fun exists(relativePath: String): Boolean =
        ClasspathKnowledgeSource::class.java.getResource("/gymcoach/knowledge/$relativePath") != null
    override fun readText(relativePath: String): String =
        checkNotNull(ClasspathKnowledgeSource::class.java.getResourceAsStream("/gymcoach/knowledge/$relativePath")) { "missing knowledge resource $relativePath" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
}
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/knowledge/KnowledgeValidator.kt (signature contract)
/** R4 §6.3 rules 1–8 plus rule 9 (renamed ids), rule 10 (templates reference existing patterns/muscles; every `backoff_of` points at a `heavy` slot AND, for every split row, every `backoff_of` of every template listed in that row resolves to a heavy slot of a template listed in the SAME row — §3.4; the strength rows are real `SplitTemplate` entries, so the check runs over `kb.splits` as loaded, with no code-side substitution) and rule 11 (RuleIds.ALL ⊆ rules.json, incl. the prose-only ids of §3.5). Returns violations; empty = valid. */
class KnowledgeValidator(private val kb: KnowledgeBase, private val frozenIds: List<String>) {
    fun validate(): List<String>
}
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/knowledge/RuleBook.kt (signature contract)
class RuleBook(val entries: Map<String, RuleEntry>) {
    fun get(id: String): RuleEntry? = entries[id]
    fun require(id: String): RuleEntry = checkNotNull(entries[id]) { "unknown rule $id" }
    fun constant(id: String, key: String): Double = checkNotNull(require(id).constants[key]) { "rule $id has no constant $key" }
    fun citation(id: String): String = require(id).citation
    /** One line per rule: "VOL-04 [weak] Hypertrophy sets/muscle/week … — Schoenfeld 2017; Pelland 2025" (used by the coach digest and Knowledge screen). */
    fun digestLine(id: String): String
}
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/equipment/EquipmentResolver.kt (signature contract)
package com.gymcoach.core.equipment

import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.*   // illustrative; explicit imports in the real file (HR-1)

/** Result of resolving one exercise against today's equipment. */
data class Availability(
    val available: Boolean,
    val optionIndex: Int?,                       // first satisfied option (preferred first), null if none
    val effectiveLoadType: LoadType?,            // option override or exercise default
    val safetiesOrSpotter: Boolean,              // rack_or_spotter satisfied
    val missing: List<String>,                   // items missing from the best option (for the UI)
)

class EquipmentResolver(private val kb: KnowledgeBase) {
    /**
     * R4 §2.2 literally: available iff at least one equipment option has every all_of item satisfied.
     * Item satisfied if: concrete id present; any_of group with ≥ 1 member present; all_of bundle with all present;
     * session flag `spotter` toggled. `pullup_bar_any` honours power_rack.has_pullup_bar; `rack_or_spotter` is
     * satisfied by power_rack/half_rack only when has_safeties is true (default true), or by spotterPresent.
     * `all_of: []` is always satisfied.
     */
    fun resolve(exercise: Exercise, equipment: SessionEquipment): Availability
    fun availableExerciseIds(equipment: SessionEquipment): Set<String>
    fun isItemSatisfied(item: String, equipment: SessionEquipment): Boolean
}
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/equipment/GymParams.kt (signature contract)
/** Typed accessors over GymItem.params with catalogue defaults (ParamSpec.default). */
class GymParams(private val kb: KnowledgeBase, private val equipment: SessionEquipment) {
    fun number(equipmentId: String, key: String): Double?
    fun bool(equipmentId: String, key: String): Boolean?
    fun numbers(equipmentId: String, key: String): List<Double>?
    val dumbbellMaxKg: Double? get() = number("dumbbells", "max_kg")
    val dumbbellMinKg: Double? get() = number("dumbbells", "min_kg")
    val dumbbellStepKg: Double? get() = number("dumbbells", "step_kg")
    val smallestPlateKg: Double? get() = number("plates", "smallest_plate_kg")
    val hasMicroplates: Boolean get() = bool("plates", "has_microplates") ?: false
    val barKg: Double get() = number("barbell", "bar_kg") ?: 20.0
    val kettlebellsKg: List<Double>? get() = numbers("kettlebells", "available_kg")
    fun stackStepKg(equipmentId: String): Double? = number(equipmentId, "stack_step_kg")
    fun halfStepKg(equipmentId: String): Double? = number(equipmentId, "half_step_kg")
    fun bandLevels(): List<String>?                                 // resistance_bands.levels as strings
}
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/equipment/IncrementResolver.kt (signature contract)
/** `kg` is PER HAND for dumbbell/kettlebell pairs (perHand = true) and per total load otherwise; callers normalise the logged load to the same basis before comparing (§4.9.3). */
data class LoadStep(val kg: Double, val perHand: Boolean, val direction: IncrementDirection, val explanation: Explanation)

/** R4 §5.4 / PROG-02: the smallest sensible load jump for an exercise in this gym. */
class IncrementResolver(private val kb: KnowledgeBase) {
    fun step(exercise: Exercise, effectiveLoadType: LoadType, params: GymParams, currentLoadKg: Double?, sex: Sex, trainingAge: TrainingAge): LoadStep
    /** 2 for a bilateral dumbbell/kettlebell exercise held with two implements, 1 for unilateral or single-implement exercises (goblet, suitcase carry) and every other load type. */
    fun implements(exercise: Exercise, effectiveLoadType: LoadType): Int
}
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/equipment/LoadRounder.kt (signature contract)
data class RoundedLoad(val kg: Double, val label: String, val roundingErrorPct: Double, val perHand: Boolean)

/** Rounds a desired load to what the gym can actually load (bar + plate pairs, dumbbell rack step, stack step/half step, kettlebell sizes, assist stacks). */
class LoadRounder(private val kb: KnowledgeBase) {
    fun round(desiredKg: Double, exercise: Exercise, effectiveLoadType: LoadType, params: GymParams): RoundedLoad
    /** Total load ceiling: dumbbell/kettlebell max × IncrementResolver.implements(exercise) (2 × max for a bilateral pair, 1 × max for unilateral or single-implement); stack max; plates unlimited unless the gym says otherwise; null = unlimited. */
    fun achievableMaxKg(exercise: Exercise, effectiveLoadType: LoadType, params: GymParams): Double?
}
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/equipment/Presets.kt (signature contract)
object Presets {
    /** Builds a GymSet from a preset id (items + params from the preset). */
    fun gymFromPreset(kb: KnowledgeBase, presetId: String, name: String, id: String, createdAt: java.time.Instant): GymSet
    /** Adds the travel_kit items to a gym (idempotent). */
    fun mergeTravelKit(kb: KnowledgeBase, gym: GymSet): GymSet
}
```

Rounding rules for `LoadRounder` (all labels in kg, converted at display time only):

| Effective load type | Achievable loads | Label |
|---|---|---|
| BARBELL, SMITH | `barKg + 2 × k × smallestPlateKg` (k ≥ 0); with microplates the step is `2 × 0.5 = 1.0`; never below the bar | "82.5 kg" |
| DUMBBELL (bilateral pair) | `2 × (minKg + k × stepKg)`, ≤ `2 × maxKg` | "2×22 kg" |
| DUMBBELL (unilateral / single, `perHand`) | `minKg + k × stepKg` ≤ `maxKg` | "22 kg" |
| KETTLEBELL | nearest `available_kg` | "24 kg" |
| MACHINE, CABLE (selectorized) | multiples of `stack_step_kg` (+ `half_step_kg` if present) | "stack 45 kg" |
| MACHINE plate-loaded, SLED | `2 × k × smallestPlateKg` (+ sled_kg noted) | "80 kg on the sled" |
| BODYWEIGHT with external load | `2 × k × smallestPlateKg` or 2.5-kg belt steps | "BW+10 kg" |
| BAND | ordinal band level | "band: heavy" |
| assisted machines (`direction: assist`) | multiples of `stack_step_kg`, label negative | "assist 25 kg" |

Nearest achievable wins; on a tie the lower load; if `roundingErrorPct > 5` the caller appends
`Explanations.of(RuleIds.ROUNDING, "…", "desired" to …, "loaded" to …, confidence = Confidence.WEAK)`
to the block's `warnings`/`SessionPlan.modifiers` (and the session ViewModel records the matching
`Adjustment(kind = AdjustmentKind.ROUNDING)`). `ROUNDING` is a planner-convention rule id present
in `rules.json` (§3.5), so invariant 10 accepts it.

---
## 4. Planner and progression (WP-01 owns §4.1 `PlannerConstants.kt` and §4.10 `E1rm.kt`; WP-03 §4.2–4.8, 4.12–4.16; WP-04 §4.9, 4.11)

The plan is a **pure function**: `Mesocycle = Planner.plan(inputs, today)`. Nothing edits a plan in
place. The UI appends *logs* (what the user did) and *overrides* (what the user or the coach
decided); the planner re-derives the mesocycle, the week and today's session from those inputs,
deterministically, and explains every decision with a rule ID. Recompute triggers: profile saved,
gym edited, override added/revoked, session closed, week rollover, travel answer, deload accepted.
`inputsHash` (SHA-256 of `CoreJson.stored.encodeToString(PlanInputs)`) lets `:app` skip a recompute
when nothing changed. Every id the planner emits is derived from its inputs (HR-18):
`Mesocycle.id = "meso-" + inputsHash.take(16)`, `PlannedSession.id = "<mesoId>:w<k>:d<isoWeekday>:<templateId>"`
(the ISO weekday `d1`..`d7` keeps the id unique when the same template appears twice in one week —
`ppl_6`, `ul_6`; invariant 18, §4.16), `SessionPlan.id = (plannedSessionId ?: "free") + "@" + date`,
`ExerciseBlock.blockId = slotId + ":" + exerciseId` — so two runs on identical inputs are
byte-identical (`DeterminismTest`) and `PlanRepository.snapshotByInputsHash` can reuse a snapshot.

Automatic vs proposed (governance rule, A graft): **automatic = per-session and ≥ moderate
confidence** (readiness modifiers, layoff regression, rounding, substitution for missing equipment,
double progression); **proposed = structural or weak-confidence** (reactive deload, +1 training
day, micro-session, variation swap in the plateau ladder). Proposals are `PlanMutation`s with
`OverrideSource.PLANNER` rendered as cards on Today; nothing structural changes until the user taps.

### 4.1 `PlannerConstants` — every adjustable threshold, with provenance (file owned by **WP-01**, Phase 0)

The constants are split into **groups** of ≤ 30 leaf fields. One flat data class with ~220
constructor parameters (78 of them `Double`, two JVM slots each) exceeds the 255-slot limit of a
JVM method descriptor (JVMS §4.3.3) in its primary constructor, the default-argument synthetic
constructor, the kotlinx-serialization synthetic constructor and `copy()`, so the class could not be
emitted or loaded. `PlannerConstantsShapeTest` (§10.2) guards the shape. Group and leaf names are
the *only* names used in code: `c.vol.vol04Novice`, `c.rest.rest02HeavySec`, `c.trv.trv04RampFactors`.

```kotlin
// core/src/main/kotlin/com/gymcoach/core/planner/PlannerConstants.kt
package com.gymcoach.core.planner

import com.gymcoach.core.model.Band
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/*
 * Every leaf field is an R3 planner default (weak/convention unless noted) or a moderate/strong value
 * kept here so that ONE object tree holds every number the planner uses. Leaf names carry the rule ID
 * (prefix before the first capital letter, e.g. vol04Novice -> "VOL-04", e1rm02MaxReps -> "E1RM-02";
 * exceptions in RULE_ID_EXCEPTIONS); rules.json holds the statement, confidence, citation and the
 * same defaults under the §3.5 key convention (RuleBookTest). User overrides from Settings ▸ Advanced
 * are merged over DEFAULT by [PlannerConstants.withOverrides] (nested JSON, e.g. {"vol":{"vol04Novice":[8,12]}});
 * only leaves whose rule is user_adjustable are shown as editable.
 */

/** DEF-01. */
@Serializable
data class DefConstants(
    val def01NoviceMaxMonths: Int = 6,
    val def01IntermediateMaxMonths: Int = 24,
    val def01LayoffResetMonths: Int = 12,
    val def01RetrainMonthsToLeaveNovice: Int = 6,
)

/** VOL-01/04/05/06/07/08/09, MIX-03, R4-4.5 (fractional sets per muscle per week unless stated). */
@Serializable
data class VolConstants(
    val vol01Minimum: Int = 4,
    val vol04Novice: Band = Band(8, 12),
    val vol04Intermediate: Band = Band(12, 18),
    val vol04Advanced: Band = Band(15, 25),
    val vol05StrengthPerLift: Band = Band(4, 10),
    val vol06PerSessionDefaultStop: Int = 10,
    val vol06PerSessionCap: Int = 12,
    val vol07MinSetsPerAppearance: Int = 2,
    val vol08StepTier1: Double = 1.0,
    val vol08StepTier2: Double = 0.5,
    val vol08StepMax: Int = 2,
    val vol08SorenessGate: Int = 3,
    val vol08RirTolerance: Double = 0.5,             // hold when mean reported RIR < prescribed RIR − tolerance (§4.4)
    val vol09DeficitFractionLo: Double = 0.6,
    val vol09DeficitFractionHi: Double = 1.0,
    val vol09NoHistoryFactor: Double = 0.8,
    val mix03CompoundShare: Band = Band(60, 75),
    val r45SoftCapFactor: Double = 0.5,              // allocator gain × 0.5 once direct(m) ≥ RP MRV.lo(m), NOVICE/INTERMEDIATE only (§4.4)
)

/** TIER (planner convention). */
@Serializable
data class TierConstants(
    val tierTier2Factor: Double = 0.6,
    val tierTier3DirectCap: Int = 6,
)

/** SESS-01/02. */
@Serializable
data class SessConstants(
    val sess01HeavyLowerMin: Double = 4.0,
    val sess01HeavyUpperMin: Double = 3.5,
    val sess01CompoundMin: Double = 2.5,
    val sess01IsolationMin: Double = 1.75,
    val sess01IsolationRestCutMin: Double = 1.5,
    val sess01SupersetPairMin: Double = 2.5,
    val sess01SupersetFactor: Double = 0.7,
    val sess01UnilateralFactor: Double = 1.7,
    val sess01BudgetToleranceMin: Double = 0.0,
    val sess02CapDefault: Int = 25,
    val sess02CapNovice: Int = 20,
    val sess02CapAdvanced: Int = 32,
)

/** WU-01/02/05/06. */
@Serializable
data class WuConstants(
    val wu01Minutes: Int = 10,
    val wu05TrimmedMinutes: Int = 5,
    val wu05TrimAtOrBelowMinutes: Int = 45,
    val wu06ExtraMinutes: Int = 2,
    val wu06AgeThreshold: Int = 40,
    val wu06MinutesSinceWakingThreshold: Int = 60,
    val wu02HeavyPcts: List<Double> = listOf(0.40, 0.55, 0.70, 0.825),
    val wu02HeavyReps: List<Int> = listOf(8, 5, 3, 2),
    val wu02ModeratePcts: List<Double> = listOf(0.50, 0.70),
    val wu02ModerateReps: List<Int> = listOf(8, 5),
    val wu02LightPcts: List<Double> = listOf(0.60),
    val wu02LightReps: List<Int> = listOf(8),
    val wu02IsolationPct: Double = 0.55,
    val wu02WarmPatternPct: Double = 0.65,
    val wu02RampRestSec: Int = 60,
)

/** REST-01..04 (seconds). */
@Serializable
data class RestConstants(
    val rest01HypertrophySec: Int = 120,             // moderate compounds (REST-01)
    val rest02HeavySec: Int = 180,                   // heavy compounds (REST-02)
    val rest02TopSetSec: Int = 240,                  // ≤ 6-rep top sets (REST-02)
    val rest03IsolationSec: Int = 90,                // isolation / machines (REST-03)
    val rest03SupersetTransitionSec: Int = 30,
    val rest04CutSec: Int = 60,                      // concession level 4 (REST-04)
    val rest04MainLiftFloorSec: Int = 120,
)

/** RIR-03/04/05, RIR-CAP. */
@Serializable
data class RirConstants(
    val rir03LowerBarbellFloor: Int = 2,
    val rir03UpperBarbellFloor: Int = 1,
    val rir03SpWithoutRackFloor: Int = 2,            // SP exercise whose satisfied option lacks rack_or_spotter (§3.1 decision)
    val rir04Week1Offset: Int = 1,
    val rir04LastWeekOffset: Int = -1,               // fallback when the profile has no rir_last_week
    val rir04LastWeekCompoundFloor: Int = 1,
    val rir04LastWeekIsolationFloor: Int = 0,
    val rir04DeloadRir: Int = 4,
    val rir05HighRepThreshold: Int = 12,
    val rir05CalibrationEveryWeeks: Int = 3,
    val rirCapMax: Int = 4,                          // RIR-CAP (DEF-04)
)

/** PROG-02/03/04/05, LOAD-03/04, E1RM-02/04. */
@Serializable
data class ProgConstants(
    val prog02LowerBarbellKg: Double = 5.0,
    val prog02LowerBarbellSmallKg: Double = 2.5,
    val prog02LowerBarbellSmallAboveKg: Double = 100.0,
    val prog02UpperBarbellKg: Double = 2.5,
    val prog02MicroplateKg: Double = 1.0,
    val prog02BodyweightExternalKg: Double = 2.5,
    val prog02MaxJumpPct: Double = 10.0,
    val prog02RepExtension: Int = 2,
    val prog03IntermediateMinDays: Int = 7,
    val prog04EasyOffset: Int = 2,
    val prog04HardOffset: Int = 2,
    val prog04BackoffFactor: Double = 0.95,
    val prog05NoviceRir: Band = Band(3, 4),
    val prog05NoviceWeeks: Int = 2,
    val load03BackoffFactor: Double = 0.85,          // strength back-off day (H16)
    val load03BackoffReps: Band = Band(4, 8),
    val load04MaxStrengthRepsMax: Double = 5.0,      // LoadZones.of rep-midpoint cut-points (§4.6)
    val load04HeavyRepsMax: Double = 8.0,
    val load04ModerateRepsMax: Double = 15.0,
    val load04MaxStrengthPct: Double = 0.85,         // %1RM cut-points when a HIGH e1RM exists
    val load04HeavyPct: Double = 0.78,
    val load04ModeratePct: Double = 0.65,
    val e1rm02MaxReps: Int = 10,
    val e1rm04RollingDays: Int = 14,
)

/** PLAT-01/03, DLD-01..04. */
@Serializable
data class PlatDldConstants(
    val plat01Sessions: Int = 3,
    val plat01ImprovementFactor: Double = 1.005,
    val plat03DropFactor: Double = 0.95,
    val plat03Sessions: Int = 2,
    val dld01NoviceWeeks: Int = 12,
    val dld01IntermediateWeeks: Int = 6,
    val dld01AdvancedWeeks: Int = 5,
    val dld02SetsFactor: Double = 0.5,
    val dld02CompoundLoadFactor: Double = 0.85,
    val dld03MinTriggers: Int = 2,
    val dld03MinDays: Int = 5,
    val dld03RedDaysOf7: Int = 3,
    val dld03SorenessLevel: Int = 4,
    val dld03SorenessHours: Int = 72,
    val dld03SleepQualityLevel: Int = 2,
    val dld03SleepQualityNights: Int = 4,
    val dld03MotivationLevel: Int = 2,
    val dld03MotivationDays: Int = 4,
    val dld03JointPain: Int = 3,
    val dld03MinWeeksBetweenDeloads: Int = 2,        // ScheduleDeload rejected inside this window (§4.11, §4.15)
    val dld04LoadFactor: Double = 0.95,
    val dld04RirOffset: Int = 1,                     // == the RIR-04 week-1 offset carried by MesoWeek.rirOffset; never added twice (§4.7 step 4)
)

/** LAY-01/02. The `*Days` bands are EXCESS days beyond the plan's own cadence (§4.7 step 2). */
@Serializable
data class LayConstants(
    val lay01MinExcessDays: Int = 5,                 // below this excess nothing is a layoff
    val lay01Row1Days: Band = Band(5, 13),
    val lay01Row1Load: Double = 0.95,
    val lay01Row1SetDelta: Int = -1,
    val lay01Row1Rir: Int = 1,
    val lay01Row2Days: Band = Band(14, 28),
    val lay01Row2Load: Double = 0.875,
    val lay01Row2SetFactor: Double = 0.7,
    val lay01Row2Rir: Int = 2,
    val lay01Row3Days: Band = Band(29, 56),
    val lay01Row3Load: Double = 0.775,
    val lay01Row3SetFactor: Double = 0.5,
    val lay01Row3Rir: Int = 2,
    val lay01Row4Load: Double = 0.65,
    val lay01Row4Rir: Band = Band(3, 4),
    val lay01ConservativeAge: Int = 65,
    val lay01ExerciseGapDays: Int = 14,              // per-exercise rule: excess ≥ this while the pattern was trained → row 1 only
    val lay02Zone2OnlyDays: Int = 3,
)

/** RDY-01..06, SLP-03. */
@Serializable
data class RdyConstants(
    val rdy02GreenMin: Int = 70,
    val rdy02AmberMin: Int = 45,
    val rdy02AmberRirOffset: Int = 1,
    val rdy02AmberSetDropAfter: Int = 2,
    val rdy02RedSetFactor: Double = 0.6,
    val rdy02RedRirOffset: Int = 2,
    val rdy03RhrAmberDelta: Int = 5,
    val rdy03RhrRedDelta: Int = 10,
    val rdy03RhrDays: Int = 2,
    val rdy04SorenessLevel: Int = 4,
    val rdy05StressLevel: Int = 4,
    val rdy05Checkins: Int = 2,
    val rdy05SetFactor: Double = 0.8,
    val rdy05RirOffset: Int = 1,
    val rdy06SpikeFactor: Double = 1.5,
    val slp03WeeklyAvgHours: Double = 7.0,
    val slp03SetFactor: Double = 0.9,
    val slp03RirOffset: Int = 1,
    val slp03NightHours: Double = 6.0,
    val slp03TwoNightsNoMaxStrength: Int = 2,
)

/** TRV-01/04, TIME-01. */
@Serializable
data class TrvTimeConstants(
    val trv01Sessions: Int = 2,
    val trv01Minutes: Band = Band(25, 40),
    val trv01Exercises: Band = Band(4, 6),
    val trv01Sets: Band = Band(2, 3),
    val trv01TravelDaysThreshold: Int = 3,
    val trv01EnduranceReps: Band = Band(15, 30),     // light-load fallback (LOAD-01): fixed RIR, no stack
    val trv01EnduranceRir: Int = 1,
    val trv04RampFactors: List<Double> = listOf(1.0, 1.15, 1.30, 1.45),   // find-load protocol (§4.9.2)
    val trv04StopRirOffset: Int = 1,                 // stop when reported RIR ≤ min(target + 1, rirCapMax)
    val time01MinViableRequiredSets: Int = 2,        // per required COMPOUND slot (§4.7 step 5)
    val time01MinViablePrioritySets: Int = 1,
)

/** SEL / SUB / REBAL / R4-2.6 (planner conventions). */
@Serializable
data class SelSubConstants(
    val selPatternPref: Double = 0.35,
    val selCoverage: Double = 0.25,
    val selContinuity: Double = 0.15,
    val selPreference: Double = 0.10,
    val selLoadTypePref: Double = 0.10,
    val selDifficultyFit: Double = 0.05,
    val selFamilyPatternScore: Double = 0.6,
    val selRepeatInSessionPenalty: Double = 0.5,
    val selVarietyPenalty: Double = 0.2,
    val selMildLimitationPenalty: Double = 0.3,
    val selNoRackPenalty: Double = 0.3,
    val subPattern: Double = 0.35,
    val subCosine: Double = 0.35,
    val subLoadFit: Double = 0.15,
    val subContinuity: Double = 0.10,
    val subDifficulty: Double = 0.05,
    val subRankBonus: Double = 0.10,
    val subProfilePenalty: Double = 0.20,
    val subLoadFitTier1: Double = 0.85,
    val subLoadFitTier2: Double = 0.60,
    val subLoadFitTier3: Double = 0.40,
    val rebalDeficitMin: Double = 1.0,
    val rebalSurplusMin: Double = 2.0,
    val rebalTolerance: Double = 1.0,
    val r426KeepAtMaxThreshold: Double = 0.6,
    val r426KeepAtMaxRepExtension: Int = 2,          // "keep at max load, rep band +2 at the top, RIR −1" (§4.7 step 1)
    val r426KeepAtMaxRirOffset: Int = -1,
)

/** CAR-05/06/07/09. */
@Serializable
data class CarConstants(
    val car05HiitBaseWeeks: Int = 4,                 // keyed to CAR-05 by prefix (R3 §5 CAR-05 prose: "requires ≥ 4 weeks of zone-2 base"); the R3 §10 CAR-05 row omits the number and CAR-09 restates it — rules.json's CAR-05 entry carries constants.hiitBaseWeeks (§3.5)
    val car06SeparationHours: Int = 6,
    val car06RunBeforeLegsHours: Int = 24,
    val car06HypertrophyCapSessions: Int = 3,
    val car06HypertrophyCapMinutes: Int = 150,
    val car09WeeklyIncreasePct: Double = 10.0,
)

/** NUT-01/04/05/06/08/09/15/16/17. */
@Serializable
data class NutConstants(
    val nut01DefaultGPerKg: Double = 1.8,
    val nut04ActivityFactors: List<Double> = listOf(1.2, 1.375, 1.55, 1.725, 1.9),
    val nut04KcalPerKg: Int = 7700,
    val nut04ClampKcal: Int = 150,
    val nut04WindowDays: Int = 14,
    val nut04MinKcalLogs: Int = 10,
    val nut05DeficitKcal: Band = Band(300, 500),
    val nut05DeficitPctOfTdee: Double = 0.20,        // deficit = min(nut05DeficitKcal.hi, round(0.20 × TDEE)) (§5.5)
    val nut05RatePctLo: Double = 0.5,
    val nut05RatePctHi: Double = 1.0,
    val nut05LeanRatePctHi: Double = 0.7,
    val nut05MaxRatePctPerWeek: Double = 1.5,        // observed loss above this → +nut05FastLossStepKcal proposal + supervision text
    val nut05FastLossStepKcal: Int = 200,
    val nut05KcalFloorTdeeFactor: Double = 0.75,     // MutationValidator: kcal ≥ max(REE, 0.75 × TDEE)
    val nut06SurplusPct: Band = Band(10, 20),
    val nut06RatePctLo: Double = 0.25,
    val nut06RatePctHi: Double = 0.5,
    val nut06KcalCeilingTdeeFactor: Double = 1.3,    // MutationValidator: kcal ≤ 1.3 × TDEE
    val nut08CarbFloorGPerKg: Double = 3.0,
    val nut09FatGPerKgDefault: Double = 1.0,
    val nut09FatGPerKgFloor: Double = 0.5,
    val nut15NoiseSingleDayPct: Double = 1.0,
    val nut15MinDays: Int = 14,
    val nut16StepKcal: Band = Band(100, 200),
    val nut17DietBreakAfterWeeks: Int = 8,
)

/** STP-01/02, SUP-02. */
@Serializable
data class HabitConstants(
    val stp01Under60: Band = Band(7000, 8000),
    val stp01Over60: Band = Band(6000, 7000),
    val stp02Bonus: Int = 2000,
    val stp02Cap: Int = 12000,
    val sup02CutoffSmallHours: Int = 9,
    val sup02CutoffLargeHours: Int = 13,
    val sup02DayMaxMg: Int = 400,
)

/** AGE-01/02/03 (§4.4 AgePolicy). */
@Serializable
data class AgeConstants(
    val age01Threshold: Int = 40,
    val age01ProteinGPerKg: Double = 1.9,            // NUT-01 upper half replaces nut01DefaultGPerKg from age01Threshold on
    val age02Threshold: Int = 60,
    val age02SetsPerExercise: Band = Band(2, 3),     // overrides the profile `sets` bands
    val age02ExercisesPerSession: Band = Band(8, 10),
    val age02CompoundReps: Band = Band(7, 9),        // overrides compound rep bands (≈ 70–79 % 1RM); isolation bands unchanged
    val age02RestSec: Band = Band(60, 120),
    val age02SingleSetStartWeeks: Int = 2,           // NOVICE ≥ 60: one working set per exercise in weeks 1–2
    val age02PowerSetsPerWeek: Band = Band(1, 2),    // one POWER-profile slot at 40–60 % of the pattern's e1RM, fast concentric, RIR ≥ 3
    val age02PowerPctLo: Double = 0.40,
    val age02PowerPctHi: Double = 0.60,
    val age02PowerMinRir: Int = 3,
    val age02BalanceDaysPerWeek: Int = 2,            // 5-min balance block in the cool-down list
    val age02BalanceMinutes: Int = 5,
    val age03MaxStrengthAllowed: Boolean = false,    // ≥ 60: no MAX_STRENGTH zone unless ADVANCED && allowAdvancedExercises
)

@Serializable
data class PlannerConstants(
    val def: DefConstants = DefConstants(),
    val vol: VolConstants = VolConstants(),
    val tier: TierConstants = TierConstants(),
    val sess: SessConstants = SessConstants(),
    val wu: WuConstants = WuConstants(),
    val rest: RestConstants = RestConstants(),
    val rir: RirConstants = RirConstants(),
    val prog: ProgConstants = ProgConstants(),
    val plat: PlatDldConstants = PlatDldConstants(),
    val lay: LayConstants = LayConstants(),
    val rdy: RdyConstants = RdyConstants(),
    val trv: TrvTimeConstants = TrvTimeConstants(),
    val sel: SelSubConstants = SelSubConstants(),
    val car: CarConstants = CarConstants(),
    val nut: NutConstants = NutConstants(),
    val hab: HabitConstants = HabitConstants(),
    val age: AgeConstants = AgeConstants(),
) {
    companion object {
        val DEFAULT: PlannerConstants = PlannerConstants()

        /** Leaf names whose rule id is not derivable from the prefix rule. Keyed by full field name OR by prefix. */
        private val RULE_ID_EXCEPTIONS: Map<String, String> = mapOf(
            "rirCapMax" to "RIR-CAP",
            "r426" to "R4-2.6",
            "r45" to "R4-4.5",
        )

        /**
         * Merges a NESTED JSON object ({"group":{"leaf":value}}) over `base`: unknown groups/leaves are
         * ignored, nested objects merge key by key, leaves are replaced. A leaf that fails to decode
         * discards the whole override (the caller logs it) — the app never runs on a half-applied set.
         */
        fun withOverrides(base: PlannerConstants, overridesJson: String, json: Json): PlannerConstants {
            if (overridesJson.isBlank() || overridesJson == "{}") return base
            val baseObj = json.encodeToJsonElement(serializer(), base).jsonObject
            val over = try { json.parseToJsonElement(overridesJson).jsonObject } catch (e: Exception) { return base }
            return try { json.decodeFromJsonElement(serializer(), mergeObjects(baseObj, over)) } catch (e: Exception) { base }
        }

        private fun mergeObjects(base: JsonObject, over: JsonObject): JsonObject {
            val out = base.toMutableMap()
            for ((key, value) in over) {
                val b = base[key] ?: continue
                out[key] = if (b is JsonObject && value is JsonObject) mergeObjects(b, value) else value
            }
            return JsonObject(out)
        }

        /** The rule prefix of a LEAF field name: everything before the first upper-case letter ("vol04Novice" -> "vol04"). */
        fun rulePrefixOf(fieldName: String): String = fieldName.takeWhile { !it.isUpperCase() }

        /**
         * "vol04Novice" -> "VOL-04"; "e1rm02MaxReps" -> "E1RM-02"; "time01MinViableRequiredSets" -> "TIME-01";
         * "selPatternPref" -> "SEL"; "tierTier2Factor" -> "TIER"; "rirCapMax" -> "RIR-CAP";
         * "r426KeepAtMaxThreshold" -> "R4-2.6"; "r45SoftCapFactor" -> "R4-4.5". Operates on leaf names only.
         */
        fun ruleIdOf(fieldName: String): String {
            RULE_ID_EXCEPTIONS[fieldName]?.let { return it }
            val prefix = rulePrefixOf(fieldName)
            RULE_ID_EXCEPTIONS[prefix]?.let { return it }
            val digits = prefix.takeLastWhile { it.isDigit() }
            val letters = prefix.dropLast(digits.length).uppercase()
            return if (digits.isEmpty()) letters else "$letters-$digits"
        }

        /** §3.5 constants key: "vol04Novice" -> "novice"; "tierTier2Factor" -> "tier2Factor"; "rirCapMax" -> "capMax". Bands add ".lo"/".hi", lists ".0", ".1", … */
        fun constantKeyOf(fieldName: String): String =
            fieldName.drop(rulePrefixOf(fieldName).length).replaceFirstChar { it.lowercaseChar() }

        /** (groupName, leafName, leafDescriptor) for every leaf, walked from serializer().descriptor — no reflection (HR-5). */
        fun leafFields(): List<Triple<String, String, SerialDescriptor>> {
            val root = serializer().descriptor
            val out = ArrayList<Triple<String, String, SerialDescriptor>>()
            for (g in 0 until root.elementsCount) {
                val groupName = root.getElementName(g)
                val groupDesc = root.getElementDescriptor(g)
                if (groupDesc.kind != StructureKind.CLASS) continue
                for (i in 0 until groupDesc.elementsCount) {
                    out.add(Triple(groupName, groupDesc.getElementName(i), groupDesc.getElementDescriptor(i)))
                }
            }
            return out
        }
    }
}
```

Every group is a `@Serializable` data class with only primitives, `Boolean`, `Band` and lists, so
the Settings ▸ Advanced editor renders every leaf generically by walking `leafFields()` (group,
name, rule id via `ruleIdOf`, default from `DEFAULT`, current value, confidence and citation from
`RuleBook`) and writes the nested override JSON — no `kotlin-reflect` (HR-5). The `leaf name →
rule id` and `leaf name → constants key` conventions are asserted by `RuleBookTest`;
`PlannerConstantsShapeTest` asserts that every group descriptor has < 200 JVM argument slots
(`Double` leaves count 2, everything else 1), that `PlannerConstants()` constructs and round-trips
through `CoreJson.stored`, that `withOverrides` merges a nested override and ignores an unknown
group/leaf, and that `ruleIdOf` returns the documented value for every example above.

### 4.2 Planner API (signature contract; bodies by WP-03)

```kotlin
// core/src/main/kotlin/com/gymcoach/core/planner/Planner.kt
package com.gymcoach.core.planner

import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.*
import java.time.LocalDate

class Planner(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    /** Stages 0–5. Pure; same inputs → same output. */
    fun plan(inputs: PlanInputs, today: LocalDate): PlanOutput

    /** Stage 6. Pure. `planned` may be null on a rest day (returns a mobility/cardio-only SessionPlan) or for a free session. */
    fun realize(planned: PlannedSession?, week: MesoWeek, inputs: PlanInputs, today: TodayContext, states: List<ProgressionState>): SessionPlan

    /** Week rollover: VOL-08 gating, travel mode and the PLAT-02 ladder ONLY (§4.11) — advanced states in WeekRollResult.states, one Explanation(PLAT-02) per advance in ladderNotes, step-3/5 proposals in proposals. Pure. DLD-03 is NOT evaluated here — it lives in WP-04's DeloadPolicy, which PlanCoordinator runs next to this call (§7.6); the returned deloadTriggers list is therefore always empty. */
    fun rollWeek(inputs: PlanInputs, weekStart: LocalDate): WeekRollResult

    /** Validation used by tests and before any plan is shown; empty = valid. */
    fun validate(mesocycle: Mesocycle, inputs: PlanInputs): List<Explanation>

    /** Stage 0 only (§4.3), exposed for callers outside WP-03 that need Normalized values: WP-07's swap sheet (§8.8 — excluded/penalized ids, trainingAge, allowAdvanced) and WP-05's CoachTurnInputs builder in CoachViewModel (§8.17 — trainingAge). Pure. */
    fun normalize(inputs: PlanInputs, today: LocalDate): Normalized

    /** DEF-01 (H11) alone, as §4.3 states it; Normalizer uses the same function. Pure. */
    fun trainingAgeOf(profile: Profile, today: LocalDate): TrainingAge
}

// Normalized view of the inputs (stage 0 output), reused by every later stage.
data class Normalized(
    val age: Int,
    val trainingAge: TrainingAge,
    val goal: Goal,
    val tiers: Map<Muscle, MuscleTier>,
    val excludedExerciseIds: Set<String>,          // hard exclusions (limitations pain > 3, dislikes, ExcludeExercise overrides, difficulty gate)
    val penalizedExerciseIds: Set<String>,         // mild limitation (pain ≤ 3): SEL penalty + form note
    val elbowWristHalved: Boolean,                  // INJ-04
    val medicalGate: Boolean,                       // RED-03 without clearance
    val beginnerOnly: Boolean,                      // R4 §5.3
    val maxStrengthAllowed: Boolean,                // RED-03 and AGE-03 (§4.3)
    val over60: Boolean,                            // AGE-02 (AgePolicy, §4.4)
    val firstMesocycle: Boolean,                    // inputs.mesocyclesCompleted == 0 (DLD-01 / PROG-05 novice rules)
    val activeOverrides: List<PlanOverride>,
    val pinned: Map<String, String>,                // templateId ("*" = all) -> exerciseId
    val permanentSwaps: Map<String, String>,        // fromExerciseId -> toExerciseId (scope PERMANENT / REST_OF_MESOCYCLE)
    val sessionMinutes: Int,
    val daysPerWeek: Int,
    val preferredWeekdays: List<Int>,
    val priorityMuscles: List<Muscle>,
    val explanations: List<Explanation>,
)
```

### 4.3 Stage 0 — normalize (`Normalizer`)

| Derived value | Rule | Computation |
|---|---|---|
| `age` | — | `today.year − profile.birthYear` |
| `trainingAge` | DEF-01 (H11) | `NOVICE` if `trainingMonthsConsistent < 6` **or** (`layoffMonths ≥ 12` **and** `monthsRetrainedSinceLayoff < 6`); else `INTERMEDIATE` if `trainingMonthsConsistent ≤ 24`; else `ADVANCED` |
| `goal` | DEF-06 | `profile.goal`, replaced by an active `ChangeGoal` override whose start date has passed |
| exclusions | INJ-00..04, R4 §5.5 | active limitation with `pain0to10 > 3`: exclude exercises whose matching flag is set (KNEE → `knee_sensitive`; SHOULDER → `shoulder_sensitive`; LOW_BACK → `low_back_sensitive` **or** `spinal_loading`; HIP → `knee_sensitive` deep-flexion members of patterns SQUAT/LUNGE_SPLIT are *penalized*, not excluded; ANKLE → `knee_sensitive` exercises with `tags` containing `impact` are excluded, others penalized; NECK → `spinal_loading` penalized); `pain ≤ 3` → `penalizedExerciseIds` (−0.3 SEL penalty + form note); ELBOW_WRIST → `elbowWristHalved = true` (curl/pressdown weekly sets × 0.5) |
| dislikes / excludes | — | `dislikedExerciseIds` and active `ExcludeExercise` overrides (until ≥ today or null) join `excludedExerciseIds` |
| medical gate | RED-03 | `medicalFlags` non-empty and `clearanceConfirmedOn == null` → no HIIT (CAR-05), no MAX_STRENGTH zone (LOAD-04), supplement doses behind a banner |
| age gate | AGE-03 | `age ≥ c.age.age02Threshold` (60) and not (`trainingAge == ADVANCED && allowAdvancedExercises`) → no MAX_STRENGTH zone (`AgePolicy.maxStrengthAllowed`, §4.4); `Normalized.maxStrengthAllowed = !medicalGate && AgePolicy.maxStrengthAllowed(...)` |
| difficulty gate | R4 §5.3 | `NOVICE` with < 8 weeks of logged sessions → `beginnerOnly` (BEGINNER exercises only); `ADVANCED` difficulty only if `allowAdvancedExercises`; INTERMEDIATE difficulty allowed otherwise |
| muscle tiers | TIER [weak] | TIER_1 = {chest, lats, upper_back, quads, hamstrings, glutes, side_delts} ∪ `priorityMuscles`; TIER_2 = {biceps, triceps, rear_delts, calves, abs} minus promoted; TIER_3 = everything else (front_delts, rotator_cuff, traps_upper, lower_back, forearms, obliques, hip_flexors, glute_med, adductors): no target, advisory direct cap `tierTier3DirectCap` |
| active overrides | — | `overrides.filter { revokedAt == null && (expiresOn == null || expiresOn ≥ today) }`; later `createdAt` wins for the same target |
| schedule | — | `daysPerWeek`, `sessionMinutes`, `preferredWeekdays` from the profile, replaced by an active `ChangeSchedule` override |

### 4.4 Stage 1 — mesocycle skeleton (`MesocycleBuilder`)

| Training age | Accumulation weeks | Scheduled deload | Rule |
|---|---|---|---|
| NOVICE, **first** mesocycle (`inputs.mesocyclesCompleted == 0`) | 12 | none; reactive only | DLD-01 ("no scheduled deload in the first 12 weeks") |
| NOVICE, every later mesocycle | 6 | week 7 | DLD-01 — the 12-week exemption is once; a user who stays NOVICE (DEF-01 allows up to 6 months) still deloads every 7 weeks afterwards |
| INTERMEDIATE | 6 | week 7 (`deloadWeekIndex = 7`) | DLD-01 (6–8) |
| ADVANCED, or any age whose targets sit at the VOL-04 upper bound | 5 | week 6 | DLD-01 (4–6) |

Week modes and RIR offsets (RIR-04, DLD-02, DLD-04):

| Week | Mode | `rirOffset` | Set policy |
|---|---|---|---|
| 1 | NORMAL (REENTRY if it follows a deload) | +1 (`rir04Week1Offset`) — for REENTRY this **is** the DLD-04 "+1"; nothing adds a second +1 | VOL-04 lower bound; REENTRY additionally prescribes `dld04LoadFactor` 0.95 × the pre-deload working loads (a prescription modifier, the state keeps its load) |
| 2 … N−1 | NORMAL | 0 | ramp per VOL-08 |
| N (last accumulation week) | NORMAL | the profile's `rir_last_week.hi` replaces the target (fallback target + `rir04LastWeekOffset` −1); floors: isolation 0, compounds 1, lower barbell 2, SP-without-rack 2 | hold |
| N+1 | DELOAD | fixed RIR `rir04DeloadRir` 4 | sets `ceil(n × 0.5)`; compound loads × 0.85; machine/isolation loads unchanged; warm-up and mobility kept |
| any | TRAVEL_MAINTENANCE | 0 (TRV-01: 0–2 RIR; light-load endurance sets use the fixed `trv01EnduranceRir` 1) | §4.14; mesocycle week counter paused |

Mesocycle anchor: `inputs.mesocycleAnchor ?: weekStart(today)`. Current week index =
`floor(daysBetween(anchor, today) / 7) + 1 − travelWeeksSinceAnchor`, clamped to `[1, N+1]`;
beyond N+1 a new mesocycle starts at the next week boundary (the anchor moves; `:app` writes the
new anchor and increments `mesocyclesCompleted` when it stores the snapshot). An accepted
**reactive** deload (`ScheduleDeload`, §4.11) ends the mesocycle the same way: the DELOAD week is
its last week and the following Monday starts a new mesocycle (week 1 = REENTRY, targets restart
at the lower bound, RIR-04 restarts, `deloadWeekIndex` recomputed from the new anchor).

**Weekly volume targets** (fractional sets per muscle per week, DEF-05):

| Goal | TIER_1 band | TIER_2 band | Notes |
|---|---|---|---|
| HYPERTROPHY | VOL-04 by training age: novice 8–12, intermediate 12–18, advanced 15–25 | `round(0.6 × band)`, floor VOL-01 (4): novice 5–7, intermediate 7–11, advanced 9–15 | tier factor [weak] |
| STRENGTH | muscles: novice band 8–12 regardless of age; **plus** main lifts 4–10 sets/lift/week over ≥ 2 sessions (VOL-05) | 5–7 (`round(0.6 × 8–12)`) | strength volume is counted per *lift family* (squat, bench, deadlift, OHP patterns) on top of muscle accounting |
| GENERAL_FITNESS | 8–12 | 5–7 | plus cardio per CAR-07 |
| FAT_LOSS | `clamp(0.6–1.0 × accustomed, VOL-01, VOL-04.hi)` where accustomed = mean weekly fractional sets over the last completed mesocycle (from `recentSets`); no history → `0.8 × hypertrophy band lo` | same rule | VOL-09: prefer trimming sets over load |
| ENDURANCE_SUPPORT | 4–6 (VOL-01/02 floors) | 2–4 | 2 full-body sessions |
| any goal, age ≥ 60 | targets = the **novice** band of the goal row (AGE-02) — the other AGE-02 items are applied by `AgePolicy` (below) | novice TIER_2 band | |
| any goal, `daysPerWeek == 1` | `vol01Minimum` (4) for every TIER_1 and TIER_2 muscle, **no VOL-08 ramp** — FRQ-03: one full-body session cannot carry the training-age band under VOL-06/SESS-02, so the floor is the target and no under-target alarm fires | 4 | `Explanation(FRQ-03)` + the FRQ-01 "≥ 2 days recommended" note; the PLANNER proposal card appears only if even 4 cannot fit |

**`AgePolicy`** (`planner/AgePolicy.kt`, WP-03; constants `c.age.*`, rules AGE-01/02/03; applied in
Stages 0, 1, 3 and 6): for `age ≥ age02Threshold` (60) — (a) every per-exercise `sets` band becomes
`age02SetsPerExercise` [2, 3] (Stage 4 initial fill and cap); (b) every *compound* rep band becomes
`age02CompoundReps` [7, 9] (≈ 70–79 % 1RM, invariant 11 is evaluated against the AGE-02 band);
isolation bands are unchanged; (c) rest is clamped into `age02RestSec` [60, 120] except main-lift
floors (REST-04); (d) a NOVICE ≥ 60 gets **one** working set per exercise in weeks 1–2
(`age02SingleSetStartWeeks`), then the [2, 3] band; (e) one POWER slot per week (`age02PowerSetsPerWeek`
[1, 2] sets, `power` profile: 40–60 % of the pattern's e1RM, fast concentric, RIR ≥ 3) is added as
the first accessory of the first lower session that has budget, `Explanation(AGE-02)`; (f)
`age02BalanceDaysPerWeek` (2) cool-down entries "5-min balance block" (single-leg stance, tandem
walk — mobility drills tagged `balance`) on the two first sessions of the week; (g) no MAX_STRENGTH
zone unless `trainingAge == ADVANCED && allowAdvancedExercises` (AGE-03; a strength-goal user ≥ 60
gets HEAVY top sets of 4–6 instead, `Explanation(AGE-03)`). For `age ≥ age01Threshold` (40) the
protein default rises to `age01ProteinGPerKg` 1.9 g/kg (NUT-01 upper half, AGE-01; §5.5). Every
AGE change is an explanation on the slot/block; `AgePolicyTest` and the `age 62` golden case (§10.2)
pin the numbers.

`target(m, k)` for week k (VOL-08): `min(lo(m) + (k − 1) × step(m), hi(m))` with `step = 1.0`
for TIER_1 and `0.5` for TIER_2, evaluated at each rollover and **held** for a muscle when any of:
the mean *reported* RIR over that muscle's working sets last week was below the mean *prescribed*
RIR of those sets (`SetLog.prescribedRir`) by more than `vol08RirTolerance` (0.5) — the user worked
harder than prescribed, so the ramp waits; any readiness `soreness ≥ vol08SorenessGate` with
`sorenessRegion` in that muscle's group; `inputs.deloadTriggersActive` is non-empty (any DLD-03
trigger active, §2.9 — then **every** TIER_1/TIER_2 ramp is held and the hold explanation is
`Explanation([VOL-08, DLD-03], …, inputs = "triggers" to the joined list)`; filled by
`PlanCoordinator.buildInputs()` from WP-04's `DeloadPolicy.signals(...).active(...)`, §7.6, so WP-03
never calls `DeloadPolicy`); a PLAT-03 regression on an
exercise crediting that muscle with weight 1.0; the muscle is named in the latest
`WeeklyCheckin.holds` (the check-in's VOL-08 holds — CHK Q12 answered "yes" twice, Q6 ≥ 4 twice —
computed by WP-04's `CheckinEvaluator`, stored by `CheckinViewModel`, §5.2/§8.15; `TargetPolicy`
reads `inputs.weeklyCheckins.maxByOrNull { it.weekStart }?.holds` and passes it as
`weekTargets(holds = …, deloadTriggersActive = inputs.deloadTriggersActive)` — the only channel
from a check-in to the planner). Every hold is an
`Explanation(VOL-08)`. (The comparison is against the *prescribed* RIR, never against the profile
target, so a week-1 "+1" or an Amber "+1" cannot hold the ramp by itself.)

Caps: per session per muscle VOL-06 = 12 fractional (the default fill stops at 10 and exceeds it
only when no other session can take the set); per exercise per session from the profile's `sets`
band (§3.1) or the AGE-02 band; session total SESS-02 (25; novice 20; advanced 32). **RP landmark
soft cap** (R4 §4.5, intermediate landmarks — "for comparison, never alarm"): for NOVICE and
INTERMEDIATE users, once a muscle's **direct** count has reached the RP MRV lower bound *and* its
fractional ledger has reached `target_m`, every further direct set's allocator gain is multiplied by
`r45SoftCapFactor` (0.5, weak) and an `Explanation(R4-4.5)` note is attached; while
`ledger_m < target_m` the factor is skipped, so the landmark can never keep a muscle below its
target, and it never blocks REBAL. For ADVANCED users the landmarks are display-only (Progress
screen: "vs RP MRV"). The two counting views are shown side by side and never mixed (R4 §4.2).

**Under-target report is the normal outcome, not an alarm** (H23): the VOL-04 band was measured
on large muscle groups; applying it to abs/calves/small muscles is a convention tagged weak. The
Plan screen shows remaining deficits quietly ("abs −3 this week") with option cards; the only
escalation is a TIER_1/TIER_2 muscle projected below VOL-01 (4), which produces a PLANNER
proposal card ("+1 training day" / "+10 min per session" / "20-min micro-session" / "accept").

### 4.5 Stage 2 — split and session templates (`SplitSelector`)

SPLIT-01 mapping (`templates.json` §3.4), validated by FRQ-03 (every TIER_1 muscle with target ≥ 10
appears ≥ 2×/week; per-session sets within budget; per-muscle ≤ VOL-06). **Row selection is
data-driven**: `SplitSelector` walks `kb.splits` in file order and takes the first row with
`row.daysPerWeek == daysPerWeek && (row.forGoals.isEmpty() || goal ∈ row.forGoals) &&
(row.forTrainingAges.isEmpty() || trainingAge ∈ row.forTrainingAges)`; the STRENGTH `_S` templates
come from the strength rows themselves (`for_goals: ["strength"]`, §3.4) — there is **no code-side
template substitution**. `travel_2` is never selected by this walk (§4.14). The resulting mapping:

| `daysPerWeek` | `SplitKind` (row, §3.4) | Condition | Default weekdays |
|---|---|---|---|
| 1 | FULL_BODY_1 | plus `Explanation(FRQ-01, "≥ 2 days recommended")`; FRQ-02 note (no strength variant) | Wed |
| 2 | FULL_BODY_2 | FRQ-02 note (no strength variant) | Mon, Thu |
| 3 | FULL_BODY_3 (NOVICE any goal — incl. STRENGTH, with the FRQ-02 note; ENDURANCE_SUPPORT any age) / UPPER_LOWER_FULL_3 (INTERMEDIATE, ADVANCED; the `[strength]` row carries FULL_A_S) | | Mon, Wed, Fri |
| 4 | UPPER_LOWER_4 (the `[strength]` row carries the `_S4` templates) | | Mon, Tue, Thu, Fri |
| 5 | UL_PPL_5 (the `[strength]` row carries the `_S5` templates) | | Mon, Tue, Wed, Fri, Sat |
| 6 | PPL_6 (HYPERTROPHY, GENERAL_FITNESS, FAT_LOSS, ENDURANCE_SUPPORT) / UL_6 (STRENGTH only) | | Mon–Sat |

`preferredWeekdays` (when its size equals `daysPerWeek`) replace the defaults. SPLIT-02 then
checks that no two sessions with a heavy slot for the same muscle group fall on consecutive days
and, if they do, rotates the template order (e.g. 4 days on Mon/Tue/Wed/Fri → UPPER_A Mon,
LOWER_A Tue, UPPER_B Wed, LOWER_B Fri). If no order satisfies SPLIT-02 the plan is still produced
with an `Explanation(SPLIT-02)`; if FRQ-03 fails for a split row the selector **falls to the
applicable row with one day fewer** for the same goal and training age (6 → `ul_ppl_5`; 5 →
`upper_lower_4` — four sessions, the fifth day becomes a rest/cardio day; 4 → the 3-day row for the
training age; `upper_lower_full_3` → `full_body_3`; 3 → `full_body_2`), each fall recorded as an
`Explanation(SPLIT-01)` on the mesocycle naming the dropped day. There is no synthetic "extra
FULL_A" day and no `SplitKind` outside §2.2.

**Strength goal rows** (FRQ-02, PROG-03, H16): for goal STRENGTH the row walk lands on the
`for_goals: ["strength"]` rows of §3.4 (`UPPER_A_S4`/`LOWER_A_S`/`UPPER_B_S`/`LOWER_B_S` for 4 and
6 days, `UPPER_A_S5`/`LOWER_A_S5`/`PUSH_S`/`PULL_S`/`LEGS_S` for 5 days, `UPPER_X`/`LOWER_X`/`FULL_A_S`
for 3 days INTERMEDIATE/ADVANCED), whose `backoff_of` slots guarantee that each main lift (`back_squat`/`safety_bar_squat`,
`conventional_deadlift`/`trap_bar_deadlift`, `barbell_bench_press`, `overhead_press_barbell` —
whichever is available and not excluded, else the highest-scoring barbell compound of the
pattern) appears twice per week in a slot whose pattern list contains it. First appearance
(`heavy: true`) = **heavy day**: `barbell_compound` strength profile 3–6 @ RIR 2–3, one top set of
1–2 reps at the top of the RIR band allowed for ADVANCED (never for age ≥ 60 without AGE-03
clearance). Second appearance (`backoff_of`) = **back-off day**: the *same exercise*, `c.prog.load03BackoffReps`
4–8 reps at `c.prog.load03BackoffFactor` (0.85) × the heavy-day working load [weak], progression
state shared (one key). For INTERMEDIATE the back-off day keeps two exposures without two maximal
ones; for ADVANCED it is the undulation PROG-03 asks for. Rows without a strength variant
(`full_body_1`, `full_body_2`, `full_body_3` — the only 3-day row a NOVICE matches — and
`travel_2`) give one exposure per lift with an `Explanation(FRQ-02)` on the mesocycle; on those rows
invariant 17 (§4.16) requires exactly that note instead of a back-off slot. Invariant 17 checks the
result on every row.

### 4.6 Stages 3–5 — exercise selection, set allocation, time model

**Time model** (`TimeModel`, SESS-01, minutes per working set including rest):

```
cost(block) =
  4.0  if pattern ∈ {SQUAT, HINGE, LUNGE_SPLIT} && effectiveLoadType ∈ {BARBELL, SMITH} && compound                              (heavy lower: walk-out/unrack + 180-s rests)
  3.5  else if slot.heavy && effectiveLoadType ∈ {BARBELL, SMITH}                                                                (heavy upper)
  2.5  else if exercise.flags.compound                                                                                            (moderate compound — includes barbell hip_thrust / glute bridge: HIP_EXTENSION_ISOLATION rests 120 s like a moderate compound, no walk-out)
  1.75 else                                                                                                                       (isolation, machine isolation, core, calf)
  × 1.7 if exercise.unilateral                                                                                                    (UNI-02)
  isolation cost becomes 1.5 under time concession level 4 (rest cut)                                                             [weak]
superset pair (a, b), per pair of sets:  max(2.5, 0.7 × (cost_a + cost_b))                                                       (REST-03)
  unpaired leftover sets of the longer partner cost their own price
warm-up minutes: 10 (WU-01); 5 when session ≤ 45 min (WU-05); +2 and one extra ramp set when WU-06 applies
cardio finisher inside the session (profile.cardioInsideSessionMinutes): cardioMinutes + 3
budget(session) = sessionMinutes − warmup − finisher
```

Budgets → working sets (SESS-01 sanity): 30 min ≈ 8–10; 45 ≈ 14–18; 60 ≈ 20–26; 90 ≈ 30–36.

**Pipeline order (deterministic; `ExerciseSelectorTest` asserts order-dependence):** Stage 3 first
runs a **heavy-slot pre-pass** (STRENGTH variants only; a no-op for every other goal): before the
weekday walk, every `heavy` slot that is the target of some `backoff_of` reference in the week's
templates is selected first — in weekday order, then template order, with `ledgerSoFar = 0` and the
full §4.6 score — and the result is pinned per `(templateId, slotId)`. The walk then treats every
`backoff_of` slot as pinned to that exercise (so a Tuesday back-off of a Friday heavy slot is
resolvable), and a template that appears twice in the week (`ul_6`: `UPPER_A_S4` Mon + Fri) holds
the same pinned exercise on both days (main lifts repeat on purpose, FRQ-02). Then Stage 3 walks
the week's sessions in **weekday order** and each session's slots in **template order**, selecting
one exercise per slot. `ledgerSoFar_m` at any point = Σ credits of the slots already selected
(the pre-pass picks, earlier sessions and earlier slots of this session), each counted at its
profile `sets.lo` when the slot is required and at 0 when optional. Stage 4 then allocates the
whole week. The REBAL pass
may **re-select** an exercise only for ACCESSORY / ISOLATION / OPTIONAL slots, only among
candidates whose `pattern ∈ slot.patterns`, re-scoring with the full week ledger; PRIMARY and
SECONDARY picks are never changed after Stage 3, and no further selection pass exists.

**Exercise selection** (`ExerciseSelector`, per slot, per session): candidates are exercises whose
`pattern ∈ slot.patterns`, available in the mesocycle's gym (`EquipmentResolver`), not excluded,
not difficulty-gated. A pinned exercise (`PinExercise` override for this template or `"*"`), a
`SwapExercise` override (scope PERMANENT / REST_OF_MESOCYCLE, `from` currently selected) or a
`backoff_of` reference (STRENGTH variants, §3.4: the exercise the referenced heavy slot chose in
the pre-pass above — the reference names a template of the same split row, so it always resolves)
takes the slot outright if available. Otherwise:

```
score(e, slot, session, week) =
    selPatternPref   (0.35) × (1.0 if e.pattern == slot.patterns[0] else selFamilyPatternScore 0.6)
  + selCoverage      (0.25) × Σ_m e.weight(m) × deficit(m) / maxCoverage        deficit(m) = max(0, target_m − ledgerSoFar_m) × tierWeight(m); tierWeight 1.0 / 0.6 / 0
  + selContinuity    (0.15) × (1.0 if a PROGRESSING/PLATEAU state for e exists (any gym scope) and was used in the last 8 weeks; 0.5 any state or knownLoadsKg entry; 0 none)
  + selPreference    (0.10) × (1.0 if e ∈ likedExerciseIds; 0 otherwise)      (disliked are excluded)
  + selLoadTypePref  (0.10) × (1.0 if effectiveLoadType ∈ slot.preferLoadTypes or slot.preferLoadTypes is empty and goal-default fits: STRENGTH → barbell; GENERAL_FITNESS/FAT_LOSS → machine, dumbbell; else 0.5)
  + selDifficultyFit (0.05) × (1.0 if e.difficulty ≤ training-age level, 0.5 otherwise)
  − selRepeatInSessionPenalty 0.5  if e already selected in this session
  − selVarietyPenalty 0.2          if e selected in another session this week and slot.role ∈ {SECONDARY, ACCESSORY}   (main lifts repeat on purpose (FRQ-02); isolation may repeat)
  − selMildLimitationPenalty 0.3   if e ∈ penalizedExerciseIds (INJ-00 pain ≤ 3)
  − selNoRackPenalty 0.3           if e.flags.requiresSpotterOrSafeties and the SATISFIED option lacks rack_or_spotter (squat-family lifts on a rack without
                                   safeties, good morning — §3.1 decision; still selectable with the RIR floor 2 + safety note, R4 §5.5). Bar-over-face lifts
                                   whose every option names rack_or_spotter are simply unavailable without safeties/spotter and never reach this line.
tie-break: knowledge order (batch, id)
```

`coverage` is what makes selection volume-aware: on UPPER_B the `chest_fly|elbow_extension`
accessory slot picks a fly when chest is behind target and a triceps extension when triceps is.
For ISOLATION slots with empty `target_muscles` ("priority"), the target is the TIER_1/TIER_2
muscle with the largest current deficit that has an available isolation pattern (side_delts →
SHOULDER_ABDUCTION, rear_delts → SHOULDER_ER_REAR_DELT, biceps → ELBOW_FLEXION, triceps →
ELBOW_EXTENSION, hamstrings → KNEE_FLEXION_ISOLATION, quads → KNEE_EXTENSION_ISOLATION, calves →
CALF, chest → CHEST_FLY, glutes → HIP_EXTENSION_ISOLATION, abs → CORE_ANTI_EXTENSION/CORE_FLEXION,
lats → VERTICAL_PULL straight-arm variants).

**Set allocation** (`SetAllocator`, week-level, A graft):

```
allocate(week):
  for each session s and slot: sets[s][slot] = profile(e).sets.lo if slot.required else 0
  ledger = Σ fractional credits over the week (DEF-05: 1.0 / 0.5 / 0.25 per set)
  budget[s] = minutes(s) − warmup(s) − finisher(s)
  loop:
    best = null
    for each (s, slot) with sets[s][slot] < profile(e).sets.hi
                          and (sets[s][slot] ≥ 1 or slot not required)             -- an optional slot opens only if the gain ≥ 2 × step
                          and time(s) + marginalCost(s, slot) ≤ budget[s]
                          and sessionFractional(s, m) + w_m ≤ vol06PerSessionCap for every m credited by e
                          and sessionSets(s) < sess02Cap(trainingAge):
        gain = Σ_m w_m × max(0, target_m − ledger_m) / target_m × tierWeight(m)
        if trainingAge ∈ {NOVICE, INTERMEDIATE} and ∃ m with w_m == 1.0, an RP landmark, direct(m) ≥ RP_MRV.lo(m) and ledger_m ≥ target_m:
            gain ×= r45SoftCapFactor (0.5)                                                   -- soft landmark (R4-4.5, §4.4); never a stop condition
        keep the largest gain; ties → earlier session, earlier slot
    if best == null or gain ≤ 0: break
    sets[best] += 1; update ledger and time
  invariant after loop: every required slot ≥ vol07MinSetsPerAppearance (2); an opened optional slot ≥ 2 (VOL-07) — the loop adds the second set immediately when it opens a slot
  REBAL pass (planner convention, weak):
    while ∃ muscle d with deficit(d) ≥ rebalDeficitMin (tier-1 first, then any tier-2 muscle projected below VOL-01)
      and ∃ muscle s with surplus(s) ≥ rebalSurplusMin credited with weight 1.0 by a non-required slot in some session
      and that session has an available exercise for d at ≤ the same time cost
          WHOSE pattern ∈ that slot's `patterns` (a swap never leaves the slot's pattern list — invariant 15; the slot's role must be ACCESSORY/ISOLATION/OPTIONAL):
        move one set (swapping the slot's exercise if needed, keeping the superset pairing and the 1.0-credit of d);
        if no slot of the right pattern exists for d, the surplus set is dropped instead and d stays in the under-target report
  under-target report: remaining deficits → Mesocycle.underTargetReport (quiet cards);
    any TIER_1/TIER_2 muscle < VOL-01 (4) → PlanOutput.proposals += PLANNER card ("+1 training day" / "+10 min per session" / "micro-session" / "accept")
```

The allocator fills *the week*, not one session at a time, so a muscle that cannot fit on Monday
is placed on Thursday. It never exceeds a session's minute budget (the user's session length is a
hard real-world constraint on the road).

**Order inside a session** (ORD-01/02): slot order as in the template (PRIMARY heavy first);
compounds before isolation; supersets keep their pairing; `PlannedSlot.explanation` names the
score terms that decided the pick.

**Reps / RIR / rest per slot** (LOAD-03/04, RIR-03/04, REST-01..04):

- Rep band and RIR band from `kb.prescription(exercise, goal)` (AGE-02 compound band for age ≥ 60).
  **`profileTargetRir = ceil((rir.lo + rir.hi) / 2)`** — hypertrophy `barbell_compound` [1, 3] → 2,
  `compound` (dumbbell/machine) [1, 3] → 2, lower-barbell strength [2, 3] → 3, isolation [0, 2] → 1,
  bodyweight [0, 2] → 1 (R3 RIR-01/03: the benefit tapers past ~2 RIR, so the band's *middle*, not
  its top, is the mid-mesocycle target). Week 1 adds `rir04Week1Offset` +1; middle weeks 0; the
  **last accumulation week** uses `rir_last_week.hi` from the profile (`barbell_compound`
  hypertrophy [1, 1] → 1) with fallback `profileTargetRir − 1`, subject to the RIR-04 floors
  (isolation 0, compounds 1, lower barbell 2, SP-without-rack 2). See `RirStack` §4.7.
- Rest: heavy compounds `c.rest.rest02HeavySec` 180 (`rest02TopSetSec` 240 for ≤ 6-rep top sets,
  REST-02); moderate compounds `rest01HypertrophySec` 120 (REST-01); isolation/machine
  `rest03IsolationSec` 90 (REST-03); superset transition `rest03SupersetTransitionSec` 30 s;
  `rest04CutSec` 60 under concession level 4, never below `rest04MainLiftFloorSec` 120 on main lifts
  (REST-04); age ≥ 60 clamps into `age02RestSec` (AGE-02).
- **`LoadZone` (`LoadZones.of`, LOAD-04 — one convention for WP-03 and WP-04):** with the rep band
  midpoint `m = (lo + hi) / 2`: `m ≤ load04MaxStrengthRepsMax` (5) → MAX_STRENGTH; `5 < m ≤ 8` →
  HEAVY; `8 < m ≤ 15` → MODERATE; `m > 15` → LIGHT. `slot.heavy` forces at least HEAVY. When a
  HIGH-confidence e1RM exists for the block's key, `%1RM = E1rm.pctOfMaxForReps(lo + targetRir)`
  decides instead with the LOAD-04 cut-points `≥ load04MaxStrengthPct` (0.85) MAX_STRENGTH,
  `≥ 0.78` HEAVY, `≥ 0.65` MODERATE, else LIGHT. `power` profile → POWER. Examples: bench 5–10 (m 7.5)
  → HEAVY (heavy WU-02 ramp, Red-day replacement applies, SPLIT-02 counts it); row 8–12 (m 10) →
  MODERATE; lateral raise 10–20 → LIGHT. MAX_STRENGTH is replaced by HEAVY when
  `Normalized.maxStrengthAllowed == false` (RED-03 / AGE-03).

### 4.7 Stage 6 — daily realization (`SessionRealizer`)

Applied in this order; each step that changes something appends an `Explanation` to
`SessionPlan.modifiers` (and the ViewModel records an `Adjustment` row when the session starts):

1. **Today-scope overrides, then equipment** (OVERRIDE; TRV-04, R4 §2.2/§2.6). First, every active
   `SwapExercise` override with `scope == TODAY` whose `expiresOn == today.date` (set by
   `ProposalApplier`/the swap sheet, §6.8) replaces the matching block (`from` → `to`, all sessions
   or the named template) *before* availability resolution — `Explanation(OVERRIDE, source CHAT or
   USER)`, `substitutedFor = from`; a TODAY swap whose `to` is unavailable today falls through to
   substitution with a warning naming the override. Then availability against
   `SessionEquipment(today.gym, extras, removed, spotter)`. Unavailable exercise →
   `SubstitutionEngine` (§4.12). Available but load-infeasible (`LoadRounder.achievableMaxKg <
   workingLoad`) → explicit warning + ranked alternatives, never a silent change: default choice
   "keep at max load, rep band +`r426KeepAtMaxRepExtension` (2) at the top, RIR
   `r426KeepAtMaxRirOffset` (−1, floor per RIR-03)" when `achievable ≥ r426KeepAtMaxThreshold (0.6) ×
   working`, else the top substitution; the user picks in the substitution sheet. Every set of a
   kept-at-max block is prescribed with `prescribedLoadKg = achievable` (< the state's load), which
   is what tells `afterSession` the session was **capped** (§4.9.3).
2. **Layoff** (LAY-01/02; `LayoffPolicy`). LAY-01 is defined for a *break* — training of a pattern
   that stopped **beyond the plan's own cadence** — never for the normal gap between two planned
   appearances. `scheduledGapDays(pattern)` = the mesocycle's planned interval between consecutive
   appearances of that pattern (7 for a once-weekly pattern, 3–4 for twice-weekly, 7 for
   FULL_BODY_1); `lastTrained(pattern)` = the newest of `inputs.patternLastTrained[pattern]` (persisted
   beyond the 12-week window, §2.9) and any set of that pattern in `recentSets`;
   `excess = daysSince(lastTrained) − scheduledGapDays`. **Nothing applies while `excess <
   lay01MinExcessDays` (5).** Otherwise the excess (not the raw gap) selects the row: 5–13 → loads ×
   0.95, −1 set, RIR +1; 14–28 → × 0.875, sets × 0.7, RIR +2; 29–56 → × 0.775, sets × 0.5, RIR +2;
   > 56 (or no record of the pattern at all for a user with ≥ 1 completed mesocycle) → × 0.65 with
   VOL-04 lower-bound sets and RIR 3–4 (novice restart PROG-05 if the user is novice); a brand-new
   user (no history, `mesocyclesCompleted == 0`) gets no layoff modifier — find-load handles the
   start. Age ≥ 65 one row more conservative. Per-exercise rule: if the pattern is on cadence but
   this particular exercise (its `ProgressionState.lastSessionDate`) has an excess ≥
   `lay01ExerciseGapDays` (14) over its own planned interval, apply the 5–13-day row only [weak].
   Illness (`today.illnessSymptoms` or `LAY-02` within 24 h): session blocked with LAY-02 text; the
   first session after illness uses the 14–28-day row; cardio zone 2 only for 3 days.
   `LayoffPolicyTest` asserts that E1's bench on week-2 Monday (7-day gap, once-weekly pattern,
   excess 0) gets **no** layoff modifier, that a 9-day gap on a twice-weekly pattern (excess 5–6)
   gets row 1, and that a pattern last trained 100 days ago (outside the 12-week window, via
   `patternLastTrained`) gets row 4.
3. **Readiness** (RDY-01..04, RDY-05, SLP-03, RDY-03; `ReadinessModifiers`, §5.1). Score → band →
   `SessionMode` and modifiers: Amber: +1 RIR on all sets, −1 set on every exercise after the first
   two, loads unchanged, no PROG-01 increments; Red: sets × 0.6 (round up), +2 RIR, heavy barbell
   sets (LoadZone HEAVY/MAX_STRENGTH) replaced by their first available moderate machine/dumbbell
   substitution, and `alternativeOffered = true` (25–30 min zone 2 + mobility as a choice, not a
   default). Sleep < 6 h → at least Amber; two consecutive nights < 6 h → no MAX_STRENGTH zone;
   7-day sleep average < 7 h → sets × 0.9 and RIR +1 on heavy compounds (SLP-03); stress ≥ 4 on the
   last 2 check-ins → sets × 0.8, main-lift RIR +1 (RDY-05); soreness ≥ 4 in a muscle of today's
   heavy work → swap today's template with the next open session of the week if that removes the
   conflict, else Amber modifiers on that muscle's blocks (RDY-04); `alcoholDrinksLastNight ≥ 3` →
   at least Amber (NUT-13); RHR rule (RDY-03) from `bodyMetrics`.
4. **Deload / re-entry** (DLD-02/04) from `week.mode`, read **directly from `PlannerConstants`**
   (WP-03 never calls WP-04's `DeloadPolicy`; that class only produces explanations and reactive
   proposals): DELOAD → sets `ceil(n × c.plat.dld02SetsFactor)`, compound loads ×
   `dld02CompoundLoadFactor` 0.85, machine/isolation loads unchanged, RIR fixed `c.rir.rir04DeloadRir`
   4 (stack skipped); REENTRY → loads × `dld04LoadFactor` 0.95 and sets at the lower bound **only** —
   the DLD-04 "+1 RIR" is already the RIR-04 week-1 offset carried by `MesoWeek.rirOffset` (§4.4),
   so REENTRY contributes **no** additional RIR offset (`dld04RirOffset` documents the value; the
   realizer never adds it on top). The state's `workingLoadKg` is *not* lowered — both are
   prescription modifiers; every set carries `prescribedLoadKg` below the state's load, which
   `afterSession` treats as capped (§4.9.3).
5. **Time budget** (TIME-01; `TimeConcessions`). If `estimate > budget(today.minutesOverride ?:
   gym.defaultSessionMinutes ?: profile.sessionMinutes)`, apply concession levels in order until a
   *minimum viable session* fits — **≥ `time01MinViableRequiredSets` (2) sets on every required
   COMPOUND slot** (isolation/core slots may go to 0) and ≥ 1 on the two priority lifts — then a
   greedy **refill** adds sets back in deficit order while the budget allows:
   `L1` warm-up trim (WU-05) — **skipped and not logged when WU-05 already applies**, i.e. when the
   session is ≤ 45 min the warm-up is 5 min *before* the ladder starts (§4.6, §5.7) and L1 has
   nothing left to trim (E2's log is therefore `[L2]`) · `L2` drop isolation sets — required or not — for muscles that already
   have ≥ VOL-01 (4) fractional sets *from compounds* this week, down to 0 sets on that slot
   (E2 drops `slider_leg_curl` entirely although LOWER_B s5 is required) · `L3` antagonist/
   non-competing supersets for ISOLATION/ACCESSORY slots (REST-03) · `L4` rest cut on
   machine/isolation to 60 s (REST-04) · `L5` one exercise per pattern · `L6` two sets per exercise
   (never < 1 on the two priority lifts) · `L7` move the lowest-priority pattern's slot to another
   open session this week (ledger carryover, §4.13). Each applied level is an
   `Explanation(TIME-L<n>)` in `modifiers`.
6. **Prescription per set** from the block's `ProgressionState` (§4.9): load (`LoadRounder`; a
   rounding error > 5 % appends `Explanations.of(RuleIds.ROUNDING, …, confidence = WEAK)` to the
   block's warnings, §3.9), rep band = profile band (or AGE-02 band) ± `repZoneShift`
   (PLAT-02 step 2) ± PROG-02 extension ± the R4-2.6 extension, RIR from `RirStack`, rest from §4.6.
   `UNKNOWN` state → find-load protocol (§4.9.2). Light-load endurance fallback (loadFit tier ≤
   0.4, TRV-01/LOAD-01): reps `trv01EnduranceReps` 15–30 and the **fixed** `trv01EnduranceRir` 1 — the
   RIR stack is bypassed (proximity to failure is what makes light loads work). **RIR-05 calibration
   set**: in weeks where `weekIndex % rir05CalibrationEveryWeeks == 0`, the last set of the first
   machine/isolation block (not SP-flagged) is `SetKind.CALIBRATION` (AMRAP with the RIR target
   shown as "to failure — safe exercise; used to recalibrate your RIR reports").
7. **Safety** (R4 §5.5, §3.1 decision): an SP-flagged exercise whose *satisfied* equipment option
   lacks `rack_or_spotter` (squat family on a rack without safeties, good morning) → RIR floor
   `c.rir.rir03SpWithoutRackFloor` (2) and the exercise's safety note as a warning. Bar-over-face
   lifts never reach this step without safeties or a spotter: they are unavailable and were
   substituted in step 1.
8. **Warm-up** (§5.7 `WarmupGenerator`): WU-01/02/05/06 + MOB-01 drills by the session's patterns
   and active limitations.
9. **Cardio finisher** if the week's cardio slots place cardio on this day (ORD-03, CAR-06/07/08).
10. **Cool-down**: optional 5 min easy movement (MOB-06); static holds only for joints with a
    chronic mobility dose (MOB-02/04).
11. **Volume snapshot**, ledger carryover, explanations.

**`RirStack`** (H17): `finalRir = clamp(base + weekOffset + readinessOffset + layoffOffset +
sleepOffset + stressOffset + keepAtMaxOffset, floor, rirCapMax)` where `base = profileTargetRir =
ceil((rir.lo + rir.hi) / 2)` in weeks 1 … N−1 (with `weekOffset` = `MesoWeek.rirOffset`: +1 in
week 1, else 0) and `base = rirLastWeek.hi` (fallback `profileTargetRir + rir04LastWeekOffset`) with
`weekOffset = 0` in the last accumulation week; `floor` = `rir04LastWeekIsolationFloor` 0 for
isolation / `rir04LastWeekCompoundFloor` 1 for compounds (RIR-04), `rir03LowerBarbellFloor` 2 for
lower barbell, `rir03UpperBarbellFloor` 1 for upper barbell, `rir03SpWithoutRackFloor` 2 when
unsafe; DELOAD weeks use the fixed `rir04DeloadRir` (4) and the endurance fallback the fixed
`trv01EnduranceRir` (1) — both skip the stack. `rirCapMax` = 4 (DEF-04: a working set is ≤ 4 RIR);
when the stack is capped the explanation cites `RIR-CAP` ("Red day in week 1: RIR capped at 4
(DEF-04)"). Resulting mid-mesocycle targets: upper barbell 2, lower barbell 3, dumbbell/machine 2,
isolation 1 — week 1 one higher, last week `rir_last_week.hi` (barbell compounds 1). Amber (+1)
and Red (+2) days therefore stay distinguishable except when the cap binds (Red in week 1).

### 4.8 Cardio placement, mobility dose, rest-day sessions

`CardioFinisher`/`CardioPlanner` (§5.4) produce `MesoWeek.cardio` (one `CardioPrescription` per
cardio slot with `weekday` and `placement`). `realize(null, …)` on a rest day returns a
`SessionPlan` with `mode = AS_PLANNED`, no blocks, the day's `cardio` if any, and `cooldown` = the
chronic mobility holds due that day (MOB-02); the Today screen renders it as a rest-day card.
### 4.9 Progression engine (`ProgressionEngine`, owned by WP-04)

```kotlin
// core/src/main/kotlin/com/gymcoach/core/progression/ProgressionEngine.kt (signature contract)
package com.gymcoach.core.progression

import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.*
import com.gymcoach.core.planner.PlannerConstants
import java.time.LocalDate

data class AfterSessionInput(
    val record: SessionRecord,                 // status COMPLETED, plan frozen
    val sets: List<SetLog>,                    // non-superseded sets of the session
    val states: List<ProgressionState>,        // current states for the keys in the plan
    val profile: Profile,
    val trainingAge: TrainingAge,
    val readinessBand: ReadinessBand?,
    val weekMode: WeekMode,
    val weekIndex: Int,
    val gym: GymSet,                           // the session's gym (record.gymId resolved). IncrementResolver.step reads GymParams(kb, SessionEquipment(gym)) — a day-only travel kit / extras / removals (TodayContext) are deliberately NOT consulted: the step sizes the NEXT planned session's load and the realizer re-rounds it to what is available that day (§4.7 step 1)
    val today: LocalDate,
    val externalCause: Boolean,                // illness flag, travel week, sleep < 6 h on the day, Red readiness (the engine ORs in DELOAD/REENTRY/TRAVEL_MAINTENANCE and capped sessions itself, §4.9.3)
    val firstMesocycle: Boolean,               // Normalized.firstMesocycle (PROG-05 novice gate)
)

class ProgressionEngine(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    /**
     * Seed for a key that has no stored state. knownLoadKg != null (profile.knownLoadsKg[exerciseId], §4.9.2) → status CALIBRATING,
     * workingLoadKg = knownLoadKg (unrounded; the realizer rounds per gym), calibrationSessions = 0 (PROGRESSING after 2 sessions with
     * a working load, §4.9.1) — E1's bench "80 kg, CALIBRATING from known load". knownLoadKg == null → status UNKNOWN, workingLoadKg =
     * null (find-load, §4.9.2). In both cases workingReps/repHiBase = kb.prescription(exercise, goal).reps, targetRir = RirStack.profileTargetRir,
     * every counter 0, history empty, updatedAt = now.
     */
    fun initialState(key: ProgressionKey, exercise: Exercise, goal: Goal, knownLoadKg: Double?, now: java.time.Instant): ProgressionState
    fun afterSession(input: AfterSessionInput): SessionCloseResult
    /** PROG-04 second clause, called by the session ViewModel after set 1 is logged. Returns the suggested load for sets 2..n or null. */
    fun inSessionBackoff(block: ExerciseBlock, firstSet: SetLog, params: com.gymcoach.core.equipment.GymParams): Pair<Double, Explanation>?
    /** Working load for the find-load protocol after a ramp set is logged (§4.9.2). */
    fun findLoadDecision(block: ExerciseBlock, rampSet: SetLog): Boolean   // true = "use this as the working load"
}
```

#### 4.9.1 State machine (per `ProgressionKey`)

```
(no state) ──initialState(knownLoadKg = null)──► UNKNOWN          (no state) ──initialState(knownLoadKg != null, onboarding load)──► CALIBRATING (no find-load)
UNKNOWN ──first session (find-load)──► CALIBRATING ──2 sessions with a working load──► PROGRESSING
PROGRESSING ──noProgressCount ≥ plat01Sessions (3)──► PLATEAU ──improvement──► PROGRESSING
PROGRESSING ──e1RM ≤ 0.95 × best14d on 2 consecutive sessions, no external cause (PLAT-03)──► REGRESSING
PLATEAU / REGRESSING / PROGRESSING ──deload week starts──► DELOADING ──week ends──► REENTRY ──1 week──► PROGRESSING
LAYOFF is not a state: it is a prescription modifier computed from dates (§4.7 step 2).
```

`ProgressionKey.forExercise(exerciseId, effectiveLoadType, gymId)`: gym-scoped for MACHINE, CABLE,
SMITH, SLED (E1RM-04, TRV-04); global for barbell, dumbbell, kettlebell, bodyweight, band. A new gym
therefore starts every machine at find-load (one ramp; the UI says so up front).

#### 4.9.2 Find-load protocol (`UNKNOWN`; TRV-04 "RIR-based load find")

Start load `L0` = `profile.knownLoadsKg[exerciseId]` if present, else a transfer seed
(`LoadTransfer`, Appendix C: sibling exercise with a state × factor; same pattern + same load type
+ both unilateral or both bilateral → factor 1.0), else the body-weight-fraction seed
(`load_transfer.json.bodyweight_seeds` × sex/age factors, "starting guess"), else the floor (empty
bar `bar_kg`, lightest dumbbell, one stack plate, body weight). `L0` is capped at
`LoadRounder.achievableMaxKg`. The block prescribes up to four `RAMP` sets of `lo + 2` reps at
`L0 × c.trv.trv04RampFactors` (1.0, 1.15, 1.30, 1.45; rounded), rest 60–90 s, `loadSource = LOAD_FIND`.
After each logged ramp set, `findLoadDecision` returns true when the reported RIR ≤
`min(target + c.trv.trv04StopRirOffset (1), c.rir.rirCapMax (4))`; the UI then converts the remaining sets to `WORKING` at that load
("Use N kg as your working load?" [Yes]). On session close: `workingLoadKg` = load of the first
WORKING set, `status = CALIBRATING`, `calibrationSessions = 1`. When `L0` already equals the
achievable maximum (hotel rack), the ramp is skipped and set 1 is WORKING at the maximum with the
R4-2.6 warning.

#### 4.9.3 `afterSession` decision (PROG-01/02/03/04, RIR-05, PLAT-01/03)

For each block of the frozen plan (or each exercise with logged sets in a free session):

```
rows     = sets for the key with kind ∈ {WORKING, AMRAP, CALIBRATION, DROP}, non-superseded          (DEF-04, WU-03)
working  = rows grouped by (sessionId, blockId, setIndex): a LEFT + RIGHT pair is ONE set with reps = min(reps), rir = min(rir),
           weight = the pair's weight; an unpaired LEFT or RIGHT row counts as 0.5 set (weight 0.5 in every `all`/`count` below,
           Explanation(UNI-02)); BOTH rows count 1                                                     (UNI-02; the session UI writes BOTH, §8.8)
if working is empty: skip (state unchanged, lastSessionDate unchanged)
audited  = working.map { s -> if (s.reps > rir05HighRepThreshold) s.rir + 1 else s.rir }              (RIR-05, H15; null rir → treated as target)
loadKg   = working[0].weight?.kg
implements = IncrementResolver.implements(exercise, effectiveLoadType)                                 (2 for a bilateral dumbbell/kettlebell pair, else 1)
perHandLoad = if (working[0].perHand) loadKg else loadKg / implements                                 (§3.9: the step's basis; G1 12 → 14 kg per hand)
capped   = working.any { it.prescribedLoadKg != null && state.workingLoadKg != null && it.prescribedLoadKg < state.workingLoadKg }
           (the realizer prescribed BELOW the state's load: R4-2.6 keep-at-max, REENTRY 0.95, layoff, Red replacement, user-chosen lighter
            substitute merge — a good session at a lower load must never regress or "count against" the state)
allAtHi  = working.all { reps ≥ state.workingReps.hi && auditedRir ≤ state.targetRir }               (PROG-01)
allEasy  = working.all { auditedRir ≥ state.targetRir + prog04EasyOffset }                           (PROG-04 first clause)
below    = working.count { reps < state.workingReps.lo } ≥ 2                                          (PROG-01 hold)
coarse   = effectiveLoadType ∈ {DUMBBELL, KETTLEBELL, MACHINE, CABLE, SLED}                            (R4 §5.4)
hitsNeeded = if (trainingAge == ADVANCED || coarse) 2 else 1
hits     = if (allAtHi) state.consecutiveHits + 1 else 0
cadenceOk = when (trainingAge) { NOVICE -> true; INTERMEDIATE, ADVANCED -> lastIncrementDate == null || daysBetween(lastIncrementDate, today) ≥ prog03IntermediateMinDays }
noviceGate = trainingAge == NOVICE && firstMesocycle && weekIndex ≤ prog05NoviceWeeks                 (PROG-05: weeks 1–2 at RIR 3–4, no increments; hits still accumulate)
blocked  = readinessBand ∈ {AMBER, RED} || weekMode ∈ {DELOAD, REENTRY, TRAVEL_MAINTENANCE} || capped || noviceGate   (RDY-02; DLD-02/04; R4-2.6; PROG-05)
unfair   = externalCause || weekMode ∈ {DELOAD, REENTRY, TRAVEL_MAINTENANCE} || capped || readinessBand == RED         (never counts toward PLAT-01/03)
summary  = SessionSummary(date, loadKg, bestReps, minRir, allAtHi, e1rm = best HIGH-confidence E1rm.estimate over working, loadXReps = max(load × reps) for gym-scoped keys, readinessBand)

if (!blocked && cadenceOk && (hits ≥ hitsNeeded || allEasy)) → INCREMENT
else if (below && !capped) → HOLD (consecutiveHits = 0; explanation PROG-01 "reps below range on ≥ 2 sets: load held")
else → HOLD (consecutiveHits = hits; explanation names the blocker: PROG-05 / RDY-02 / DLD-02 / R4-2.6 "capped session: state kept at N kg")

INCREMENT by progression kind:
  LINEAR_LOAD, DOUBLE_PROGRESSION:
     step = IncrementResolver.step(exercise, effectiveLoadType, params, loadKg, sex, trainingAge)
     base = max(state.workingLoadKg ?: 0.0, loadKg)                                                   (never below the state's own load)
     if (perHandLoad > 0 && step.kg / perHandLoad > prog02MaxJumpPct/100 && !state.repHiExtended)     (step.kg and perHandLoad in the same basis)
         → workingReps.hi += prog02RepExtension (2); repHiExtended = true; explanation PROG-02 ("jump of step kg > 10 %: rep ceiling +2 first")
     else → workingLoadKg = LoadRounder.round(base + step.kg × (if (step.perHand && !working[0].perHand) implements else 1)).kg
            (workingLoadKg is stored in the same basis as the state's loadLabel: pair total for "2×22 kg", per hand for a single implement);
            workingReps = Band(lo, repHiBase); repHiExtended = false;
            lastIncrementDate = today; sessionsSinceIncrement = 0; consecutiveHits = 0; noProgressCount = 0
            explanation PROG-01 or PROG-04 ("all sets at hi reps ≤ target RIR → +step kg" / "RIR ≥ target+2 on all sets → +step kg")
  REP_PROGRESSION (bodyweight): workingReps = workingReps.shifted(+2, +2) until hi ≥ repHiBase + 4, then external load +prog02BodyweightExternalKg (2.5) and reps reset to the base band; PROG-02 bodyweight row
  BAND_PROGRESSION: bandLevel += 1 when hits ≥ 2 (next band); reps reset
  TIME_PROGRESSION (core_time): workingReps (seconds band) += 10 per hit until hi ≥ 60, then "add lever/load" note (R4 profile note)
  DISTANCE_OR_TIME (carry): +5 m or +2 kg alternately
  NONE: no change

PLAT-01 (only when not INCREMENT, only in a NORMAL week, only while status ∈ {PROGRESSING, PLATEAU}; skipped entirely when `unfair`):
  improved = (summary.e1rm != null && summary.e1rm.confidence == HIGH && summary.e1rm.kg > (bestE1rm14d ?: 0) × plat01ImprovementFactor)
             || (loadKg == previous.loadKg && bestReps > previous.bestReps) || (loadKg > previous.loadKg)
             (`previous` = the newest history entry from a NORMAL, fair week — deload/re-entry/capped entries are skipped when looking back)
  noProgressCount = if (improved) 0 else noProgressCount + 1
  if (noProgressCount ≥ plat01Sessions) status = PLATEAU, plateauStep = 0, flag "PLAT-01"

PLAT-03 (main lifts, HIGH-confidence e1RM only; same gate: NORMAL week, status ∈ {PROGRESSING, PLATEAU}, !unfair, and the previous compared session also fair):
  if (summary.e1rm.kg ≤ best14d × plat03DropFactor && lastFair.e1rm.kg ≤ best14d × plat03DropFactor) status = REGRESSING, flag "PLAT-03"

E1RM-04 rolling best: bestE1rm14d = max over HIGH-confidence e1RM estimates from NORMAL-week, non-capped sessions in the last e1rm04RollingDays (14) days including today's
  (deload/re-entry estimates are stored in history but excluded from the best, so the week after a deload is not compared against a 15 %-lighter week).
status transitions: UNKNOWN→CALIBRATING (first session with a WORKING set), CALIBRATING→PROGRESSING (calibrationSessions ≥ 2), PLATEAU→PROGRESSING (improved),
  DELOADING (weekMode == DELOAD; noProgressCount = 0 on entry), REENTRY (weekMode == REENTRY), REENTRY→PROGRESSING (next NORMAL week; counters untouched)
history = (summary + history).take(8)   (summary.weekMode and summary.fair = !unfair are stored, §2.7, so look-backs can skip unfair entries)
Every state change produces an Adjustment(kind = PROGRESSION, source = PLANNER, before/after JSON of the state) — input SetLogs are never modified (asserted by test).
```

`ProgressionEngineTest` pins, among others: "hotel day at rack max does not lower `workingLoadKg`"
(E2 goblet squat: state 24 kg, session 3 × 14 @ 1 at 20 kg → state stays 24, `consecutiveHits`
unchanged, no PLAT-01 count); "G1 after week 7 (DELOAD) and week 8 (REENTRY): bench status
PROGRESSING, `noProgressCount` 0, no PLAT-03 flag"; "novice week 1–2 hits accumulate but no
increment (PROG-05)"; `IncrementResolverTest` covers the per-hand vs pair-total logging styles
(2 → 24 kg pair = 8.3 % of the pair but 16.7 % per hand → rep extension in both styles);
`VolumeAccountantTest` covers LEFT/RIGHT pairing and the unpaired 0.5 rule.

**Increment table** (`IncrementResolver`, PROG-02, R4 §5.4; the gym parameter overrides the default):

| Class (DEF-07) | Step | Notes |
|---|---|---|
| LOWER_BARBELL | `prog02LowerBarbellKg` 5.0; `prog02LowerBarbellSmallKg` 2.5 when load > 100 kg or `sex == FEMALE` or `trainingAge == NOVICE` (H22) | rounded by `LoadRounder` |
| UPPER_BARBELL | 2.5; 1.0 with microplates (`plates.has_microplates`) | |
| DUMBBELL (KETTLEBELL) | next rack step per hand (`dumbbells.step_kg`, default 2; kettlebell next `available_kg`) | if the jump > 10 % → rep extension first (above) |
| MACHINE selectorized | `stack_step_kg` (default 5); `half_step_kg` (2.5) if present | |
| MACHINE plate-loaded / SLED | `2 × smallest_plate_kg` (default 5.0; 2.5 acceptable on sleds) | |
| CABLE | `stack_step_kg` / `half_step_kg` (5 / 2.5) | |
| BODYWEIGHT | reps first; then +2.5 kg external (`dip_belt`+plates or vest) | |
| BAND | next ordinal level | |
| assisted machines (`direction: assist`) | −5 kg assistance (−2.5 with half step) | label "assist" |
| ISOLATION | the implement's smallest step; "prefer +reps" note | |

#### 4.9.4 In-session autoregulation (PROG-04 second clause)

When the logged RIR of working set 1 is ≤ `targetRir − prog04HardOffset` (2), `inSessionBackoff`
returns `round(load × prog04BackoffFactor)` and an explanation; the session screen shows a chip
"First set RIR 0 vs target 2: use −5 % (75 kg) for the remaining sets? [Apply]". It is **never
auto-applied**; tapping Apply re-prescribes sets 2..n and records
`Adjustment(kind = AUTOREGULATION, source = USER, ruleIds = [PROG-04])`. The logged set 1 stays
exactly as entered.

### 4.10 e1RM (`E1rm`, E1RM-01..04) — complete (file owned by **WP-01**, Phase 0; used by WP-03's realizer and WP-04's engine)

```kotlin
// core/src/main/kotlin/com/gymcoach/core/progression/E1rm.kt (copied verbatim by WP-01)
package com.gymcoach.core.progression

import com.gymcoach.core.model.E1rmConfidence
import com.gymcoach.core.model.E1rmEstimate
import java.time.LocalDate

object E1rm {
    fun epley(w: Double, r: Int): Double = w * (1.0 + r / 30.0)
    fun brzycki(w: Double, r: Int): Double = w * 36.0 / (37.0 - r)

    /**
     * E1RM-01/02/03: r_eff = min(reps + rir, maxReps); mean of Epley and Brzycki at r_eff.
     * Confidence HIGH for 1 ≤ reps ≤ maxReps (≈ 3 % error, LeSuer 1997); LOW above maxReps (displayed, never used for decisions).
     * Returns null for reps < 1 or w ≤ 0.
     */
    fun estimate(w: Double, reps: Int, rir: Int?, date: LocalDate, maxReps: Int = 10): E1rmEstimate? {
        if (reps < 1 || w <= 0.0) return null
        val rEff = minOf(reps + (rir ?: 0), maxReps)
        val kg = (epley(w, rEff) + brzycki(w, rEff)) / 2.0
        val confidence = if (reps <= maxReps) E1rmConfidence.HIGH else E1rmConfidence.LOW
        return E1rmEstimate(kg = round1(kg), fromWeightKg = w, fromReps = reps, fromRir = rir, confidence = confidence, date = date)
    }

    /** E1RM-03 %1RM table: 1:100 2:94 3:91 4:88 5:86 6:83 8:79 10:75 12:71 15:67; linear interpolation between rows; clamped outside 1..15. */
    private val table: List<Pair<Int, Double>> = listOf(
        1 to 100.0, 2 to 94.0, 3 to 91.0, 4 to 88.0, 5 to 86.0, 6 to 83.0, 8 to 79.0, 10 to 75.0, 12 to 71.0, 15 to 67.0,
    )

    fun pctOfMaxForReps(reps: Int): Double {
        if (reps <= table.first().first) return table.first().second / 100.0
        if (reps >= table.last().first) return table.last().second / 100.0
        for (i in 0 until table.size - 1) {
            val (r0, p0) = table[i]
            val (r1, p1) = table[i + 1]
            if (reps == r0) return p0 / 100.0
            if (reps in (r0 + 1) until r1) return (p0 + (p1 - p0) * (reps - r0).toDouble() / (r1 - r0).toDouble()) / 100.0
            if (reps == r1) return p1 / 100.0
        }
        return table.last().second / 100.0
    }

    /** Load for a target rep count from an e1RM (E1RM-03): e1rm × pct(reps + rir). */
    fun loadFor(e1rmKg: Double, reps: Int, rir: Int): Double = e1rmKg * pctOfMaxForReps(reps + rir)

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
}
```

Verified rows (golden, §10.4): 80 × 8 @ 3 → r_eff 11 → 10 → Epley 106.67, Brzycki 106.67, mean
**106.7**; 100 × 6 @ 2 → r_eff 8 → Epley 126.67, Brzycki 124.14 → **125.4**; 100 × 5 @ 2 →
r_eff 7 → Epley 123.33, Brzycki 120.00 → **121.7**; 60 × 12 @ 1 → LOW confidence, r_eff 10 →
80.0 (display only); `pctOfMaxForReps(7)` = 0.81, `(9)` = 0.77, `(11)` = 0.73.
Because r_eff is capped at 10, any set with reps + RIR ≥ 10 yields the same e1RM; for hypertrophy
rep ranges the primary progress signal is **reps at load** and e1RM is the tie-breaker; for
strength ranges it is the reverse. Machines/cables track best `load × reps` at RIR ≤ 2 per gym
scope instead (E1RM-04); deadlift estimates carry the note "equations under-estimate deadlift 1RM".

### 4.11 Plateau ladder, deloads, regression (`PlateauDetector`, `DeloadPolicy`; WP-04)

Division of labour (breaks the WP-03 ↔ WP-04 compile cycle): `PlateauDetector` (WP-04) computes
only the PLAT-01/PLAT-03 verdict *inside* `ProgressionEngine.afterSession` (§4.9.3, `PlateauVerdict`).
The ladder below is **advanced by `Planner.rollWeek` (WP-03)**, which reads `ProgressionState.status`
and writes `plateauStep`/`repZoneShift` itself — the advanced states are returned in
`WeekRollResult.states`, one `Explanation(PLAT-02)` per advance (inputs `exercise_id`, `step`) in
`WeekRollResult.ladderNotes`, the step-3/step-5 proposals in `WeekRollResult.proposals`, and
`PlanCoordinator` persists the states and records one `Adjustment` per note (§7.6); WP-03 never calls a WP-04 class, and WP-04 never
calls a WP-03 class — both depend only on WP-01's `PlannerConstants`, `E1rm` and the models. The
DLD-03 evaluation (`DeloadPolicy.signals` + `reactive`, WP-04) is therefore **not** part of
`rollWeek` or `realize`: `PlanCoordinator` (`:app`, §7.6) is the single composition point that runs
it next to `Planner.rollWeek` (filling `WeekRollResult.deloadTriggers` / `WeekRollResult.proposals`)
and as the pure `deloadSuggestion(today)` read behind `TodayUiState.deloadSuggestion`; `realizeToday`
never evaluates it.

Plateau ladder (PLAT-02, one step per week while `status == PLATEAU`, advanced by `rollWeek`):

| `plateauStep` | Action | Who acts |
|---|---|---|
| 1 | Audit: push CHK Q1–Q3 to the next check-in; coach context flags the plateau; SLP-03 / NUT-01 modifiers apply if the answers trigger them | planner + coach message |
| 2 | Rep-zone shift one zone lighter (LOAD-04): `repZoneShift = +1` for two weeks (hypertrophy barbell 5–10 → 8–12; strength 3–6 → 5–8); load re-seeded from the e1RM table (E1RM-03) | planner (automatic, per-exercise, `Adjustment`) |
| 3 | Variation swap to the first available `substitutions[]` entry for the rest of the mesocycle; the new key starts CALIBRATING with a transfer seed | planner **proposal** (`SwapExercise`, scope REST_OF_MESOCYCLE) |
| 4 | +1–2 weekly sets for the muscle if under the VOL-04 upper bound | planner (automatic, VOLUME `Adjustment`) |
| 5 | Reactive deload | planner **proposal** (`ScheduleDeload`) |

`DeloadPolicy` (signatures §2.15):

- `scheduledExplanation(week)` / `reentryExplanation(week)` → the DLD-02 / DLD-04 explanation
  texts shown on the Plan and Today screens. The *factors* (`dld02SetsFactor`,
  `dld02CompoundLoadFactor`, `rir04DeloadRir`, `dld04LoadFactor`) are applied by WP-03's realizer
  straight from `PlannerConstants` (§4.7 step 4); DLD-04's "+1 RIR" is the RIR-04 week-1 offset and
  is never added a second time.
- `signals(inputs, states, today): DeloadSignals` — every DLD-03 trigger as a `DeloadTrigger` with
  an **onset date** and a **resolved date** (H20 made evaluable):

  | Trigger (kind) | Onset | Resolved |
  |---|---|---|
  | `regressing` — e1RM or reps down ≥ 5 % on two sessions (a key in REGRESSING) | date of the second qualifying session | first later session of that key above `plat03DropFactor × best14d` |
  | `red_days` — readiness Red ≥ `dld03RedDaysOf7` (3) of the last 7 days | date of the 3rd Red in any 7-day window | first day on which the trailing 7 days contain < 3 Red |
  | `soreness` — soreness ≥ 4 persisting > 72 h | date of the second entry ≥ 4 that is ≥ 72 h after the first | first later entry < 4 |
  | `sleep_quality` — ≤ 2 on ≥ `dld03SleepQualityNights` (4) nights (rolling 7) | date of the 4th qualifying night | first day on which the trailing 7 nights contain < 4 |
  | `motivation` — ≤ 2 on ≥ 4 days (rolling 7; readiness `motivation` or CHK Q11) | date of the 4th qualifying day | first day on which the trailing 7 days contain < 4 |
  | `joint_pain` — new joint pain ≥ `dld03JointPain` 3/10 (readiness `jointPain0to10` or CHK Q7) | first entry ≥ 3 | first later entry < 3 (or the region's limitation cleared) |

- `reactive(signals, today, alreadyProposed): Explanation?` → non-null when ≥ `dld03MinTriggers` (2)
  triggers are **active**: unresolved and `onset ≤ today − dld03MinDays` (5), i.e. present for at
  least 5 days. Evaluated by `PlanCoordinator.rollWeekIfDue()` and by the pure read
  `PlanCoordinator.deloadSuggestion(today)` (§7.6; `realizeToday()` is pure and never evaluates
  it) — never by `Planner` itself. The active triggers' `"<kind>@<onset ISO date>"` pairs are
  recorded in `AppSettings.deloadTriggersProposed` (JSON list) in exactly two places: by
  `rollWeekIfDue()` when it appends the `ScheduleDeload` card, and by
  `PlanCoordinator.recordDeloadDecision(today)` when the user accepts **or dismisses** the Today card
  (`TodayViewModel.onAcceptDeload` / `onDismissDeload`, §8.6) — nothing else records an onset, so the
  card persists until one of those happens or the week rolls. The list reaches the planner as
  `PlanInputs.deloadTriggersProposed` (= `alreadyProposed`); a trigger whose `kind@onset` is in that
  set is not re-proposed — only a *new onset* of the same kind can trigger again, whatever the user
  decided. The *active* triggers (recorded or not) also reach the planner as
  `PlanInputs.deloadTriggersActive` for the VOL-08 hold (§4.4). `DeloadPolicyTest` covers "fires
  once", "does not fire on stale (resolved) triggers", "does not fire on onsets younger than 5 days".
- The result is a **proposal card** on Today ("Deload this week? 2 of 6 triggers: …"); dismissing
  it only records the onsets (`recordDeloadDecision`); accepting
  writes a `ScheduleDeload` override (source USER), records the onsets and recomputes; the override's effect is: the chosen week (this week or
  next) is DELOAD and **ends the mesocycle** — `PlanCoordinator` writes `mesocycleAnchor` = the
  Monday after the deload week, `lastDeloadWeekStart` = the deload Monday and increments
  `mesocyclesCompleted` when the deload week closes; the new mesocycle's week 1 is REENTRY (95 %
  loads, lower-bound targets, RIR-04 restart), exactly like the scheduled path (§4.4). Never
  automatic. `MutationValidator` **rejects** (not warns) a `ScheduleDeload` when
  `daysBetween(inputs.lastDeloadWeekStart, weekStart) < 7 × dld03MinWeeksBetweenDeloads` (14).
- Re-entry: DLD-04 = 95 % loads + lower-bound sets, with the week-1 RIR offset (+1) that every
  mesocycle's week 1 already carries — one +1, not two (§4.4, §4.7 step 4).

### 4.12 Substitution engine (`SubstitutionEngine`, `LoadTransfer`; WP-03)

The public surface is `SubstitutionEngine.rank(...)` (§2.15: steps 1–3 below, returning
`List<SubstitutionCandidate>` — the swap sheet shows that list); steps 4–6 are performed by the
realizer (`SessionRealizer`, §4.7 step 1) on the chosen candidate.

```
substitute(block, todayEquipment, ledgerContext, states, params):
  1. ranked = exercise.substitutions.filter { available(it, todayEquipment) && !excluded(it) }
  2. candidates = ranked (kept in R4 rank order; +subRankBonus 0.10 for position 0)
                  ∪ kb.exercises.filter { it.pattern ∈ family(exercise.pattern) && available && !excluded && !difficultyGated }
  3. score(c) = subPattern  (0.35) × (1.0 same pattern; 0.6 same family; family map below)
              + subCosine   (0.35) × cosine(muscleVector(exercise), muscleVector(c))          (21-dim vectors of fractional weights)
              + subLoadFit  (0.15) × loadFit(c)     loadFit = achievable / desired: ≥ subLoadFitTier1 0.85 → 1.0; ≥ 0.60 → 0.7 (top of rep band);
                                                    ≥ 0.40 → 0.4 (endurance band 15–30 @ 0–1 RIR, TRV-01/LOAD-01); else 0.1
              + subContinuity (0.10) × (1.0 if c has a PROGRESSING/CALIBRATING state in the same gym scope; 0.5 any state; 0 none)
              + subDifficulty (0.05) × difficultyFit
              − subProfilePenalty 0.20 if c.repProfile ∈ {power, carry, cardio} while the slot is a compound strength/hypertrophy slot
              − selMildLimitationPenalty 0.30 for a mild-limitation flag conflict; excluded if pain > 3
  4. pick the max; if the winner is already in the session, MERGE (sets add up to the per-exercise cap)
  5. re-run VolumeAccountant for the session; deficit(m) = plannedFractional(m) − realizedFractional(m)
  6. carry deficits to the remaining sessions of this week (§4.13); Explanation(EQUIPMENT/TRV-04) with the score table for the sheet
family(pattern): {SQUAT, LUNGE_SPLIT, KNEE_EXTENSION_ISOLATION} · {HINGE, HIP_EXTENSION_ISOLATION, KNEE_FLEXION_ISOLATION} ·
                 {HORIZONTAL_PUSH, INCLINE_PUSH, CHEST_FLY} · {VERTICAL_PUSH, INCLINE_PUSH} · {HORIZONTAL_PULL, VERTICAL_PULL} ·
                 {CORE_ANTI_EXTENSION, CORE_ANTI_ROTATION, CORE_FLEXION, CORE_ROTATION, CARRY} · every other pattern is its own family
desired working load for c = c's own state workingLoadKg, else LoadTransfer seed from the replaced exercise's working load (Appendix C; unilateral × 0.5), else bodyweight seed
achievable = LoadRounder.achievableMaxKg(c) = dumbbell/kettlebell max × IncrementResolver.implements(c) (2 for a bilateral pair held with two implements,
             1 for a unilateral exercise or a single-implement hold such as goblet/suitcase — a single-leg RDL with ONE dumbbell is 1 × max);
             stack max; plates unlimited unless the gym says otherwise
```

The `−0.20` profile penalty exists because muscle similarity alone ranks a dumbbell swing above a
Romanian deadlift as a deadlift substitute (E2 shows the numbers); a hypertrophy compound slot must
not silently become a power drill. The substitution sheet lists every candidate with its score
terms, availability, muscle overlap and ledger effect ("quads this week 12 → 10"); the user's
choice records `substitutedFor` on the block and an `Adjustment(EQUIPMENT, USER)`.

### 4.13 Weekly ledger and carryover (`WeeklyLedger`, `VolumeAccountant`; WP-03)

`VolumeAccountant.weekly(sets, kb)` → `Map<Muscle, VolumeView(direct, fractional, sessions)>`
(signatures §2.15): only WORKING/AMRAP/CALIBRATION/DROP sets count; **unilateral pairing (UNI-02)**:
the session UI writes **one row with `side = BOTH`** for a unilateral exercise (one logged set =
both sides; per-side entry is v1.1), so normally every row is one set. When LEFT/RIGHT rows do
exist (imports, corrections), rows are grouped by `(sessionId, blockId, setIndex)`; a LEFT + RIGHT
pair is one set (progression uses `min(reps)`, `min(rir)`); an unpaired LEFT or RIGHT row counts
**0.5 set** with an `Explanation(UNI-02)`; a `supersedeSet` correction replaces the row of the same
side only. `direct` sums weight-1.0 muscles; `fractional` sums 1.0/0.5/0.25. Aggregates `back = lats
∪ upper_back` (a set counts once at the larger weight), `delts`, `arms`, `legs` are computed for the
Progress screen. RP landmarks (R4 §4.5) are compared **only** with `direct`; the planner's bands use
`fractional`. `VolumeAccountantTest` has a case for the LEFT/RIGHT pairing and the 0.5 rule.

`WeeklyLedger(weekStart, targets, achieved, plannedRemaining, carryover)` is rebuilt from logs and
the realized sessions on every `realize()`:

1. `deficit(m)` after a substituted or trimmed session = planned − realized fractional credit.
2. For each remaining session of the week in weekday order: add sets to an exercise already
   crediting `m` (respecting the per-exercise cap, VOL-06 = 12, SESS-02 and that session's minute
   budget); if none can take a set, open an OPTIONAL isolation slot for `m` if the budget allows.
3. Whatever is left is reported (`SessionPlan.ledgerCarryover` and the Plan screen's "this week"
   card). If a TIER_1/TIER_2 muscle would end the week below VOL-01 (4), the planner proposes a
   20-minute micro-session (bodyweight/band, no gym) as a PLANNER card.
4. **Carryover never crosses a week boundary** — next week's targets are what VOL-08 says.
5. Surplus from a substitute that credits an already-full muscle is fine unless it breaks VOL-06 for
   that session, in which case the substitute's sets are reduced and noted.

### 4.14 One-off hotel day vs travel week (TRV-01..04)

- **One-off day.** Today → gym chip → "Hotel gym" preset (or "Quick set: dumbbells up to 20 kg +
  bench", which creates a temporary gym set) → optional minutes → the planned session for that day
  is realized against that set (§4.7). Progression keys for substitutes are separate (dumbbell
  exercises have their own global state; machine/cable keys are per gym), so the barbell lifts'
  states are untouched; the pattern stays trained, so no LAY-01 regression applies afterwards. The
  week's ledger absorbs what it can.
- **Travel week** (`TRAVEL_MAINTENANCE`): triggered by CHK Q10 ≥ `trv01TravelDaysThreshold` (3), a
  `SetTravelDays` override spanning ≥ 3 days of the week, or two consecutive sessions realized
  against a hotel-origin gym. The week is re-planned with the `travel_2` split: two full-body
  sessions (TRAVEL_A, TRAVEL_B) of 25–40 minutes, 4–6 exercises covering MIX-02 patterns as far as
  the equipment allows, 2–3 sets each, 6–15 reps (15–30 to 0–1 RIR when only light loads exist,
  LOAD-01), rest 60–90 s or antagonist supersets (TRV-01); TRV-02 templates are the target output
  (dumbbells-only / bodyweight + band / hotel gym with cables and 1–2 machines). Targets are the
  VOL-01 floors. The mesocycle week counter, RIR schedule and deload cadence pause. On return the
  next week resumes the paused week index; the LAY-01 *excess* rule of §4.7 step 2 applies —
  patterns were maintained by the travel sessions, so no pattern-level layoff fires after one
  travel week; an individual exercise whose own excess over its planned interval is ≥
  `lay01ExerciseGapDays` (14) (e.g. a barbell lift not touched for three weeks) gets the 5–13-day
  row only (95 %, RIR +1). TRV-03 (≈ ⅓ of accustomed volume maintains for weeks) is shown once as
  reassurance, tagged weak/unverified.

### 4.15 Chat proposals as plan mutations (`MutationValidator`, `OverrideResolver`, `PlanDiffer`; WP-03)

Every coach tool call is mapped into a `PlanMutation` (§2.8) by `ToolContract.parse` (mapping table
§6.6) and passes `MutationValidator.validate(mutation, inputs, mesocycle, today, nutrition)`
(§2.15) before a card is shown. Result: `ValidationResult.Valid(warnings)` or
`ValidationResult.Invalid(errors)`; invalid → `is_error` tool result.

| Mutation | Reject (error) | Warn (card shows the warning) |
|---|---|---|
| `SwapExercise` | unknown id; `to` unavailable in the mesocycle's gym (or today's gym for TODAY) — including a bar-over-face lift whose options all need `rack_or_spotter` at a gym without safeties/spotter ("needs safeties or a spotter; toggle Spotter for today", §3.1); flag conflict with an active limitation pain > 3; `from` not in the plan | pattern family differs ("pattern change: X → Y, weekly coverage for m drops by n"); `advanced` difficulty not allowed; SP-flagged `to` whose satisfied option lacks `rack_or_spotter` ("RIR floor 2 without safeties") |
| `ChangeVolume` | `delta ∉ −6..+6`; resulting weekly target < VOL-01 | above VOL-04 upper bound ("beyond the evidence band; sets beyond hi are user-owned"); per-session sets > VOL-06 ("will need a session added") |
| `ChangeRepRange` | `repLo ≥ repHi`; outside 1–30; exercise not in plan | outside the goal's LOAD-03 table; > 12 reps on a barbell compound (Halperin 2022 RIR-accuracy note, RIR-05) |
| `AddCardio` | weekday ∉ 1..7; minutes ∉ 10..90; HIIT without ≥ 4 weeks zone-2 base in logs; HIIT/any while RED-03 medical gate active | CAR-06: hard cardio < 24 h before a heavy leg session (planner switches modality to bike/rower/elliptical, CAR-08); > 3 sessions or > 150 min beyond the floor for HYPERTROPHY/STRENGTH |
| `RemoveCardio` | no cardio on that weekday | — |
| `ChangeGoal` | — | applies next week by default; progression states keep loads but rep bands change |
| `ChangeSchedule` | days ∉ 1..6; minutes ∉ 20..120; weekdays size ≠ days | SPLIT-02 conflict; mid-mesocycle rebuild |
| `SetCheckinQuestion` | empty text or > 200 chars; ONCE without a date or date in the past; > 5 custom questions active | — |
| `ExcludeExercise` | unknown id; PRIMARY slot exercise with no available substitute | — |
| `PinExercise` | unknown id; not available in the gym; pattern not in any slot of the template | difficulty above allowed |
| `AdjustWorkingLoad` | no state or state UNKNOWN; > +10 % or < −20 % of the current working load ("log a set instead") | — |
| `SetPriorityMuscles` | > 3 muscles | — |
| `ScheduleDeload` | during TRAVEL_MAINTENANCE; `< dld03MinWeeksBetweenDeloads` (2) weeks since the last DELOAD week (`inputs.lastDeloadWeekStart`) — "a deload ended n days ago; log a week first" | — |
| `SetLimitation` | pain ∉ 0..10; pain ≥ 7 or `redFlagReported` → reject with the RED-01/02 hand-off text | — |
| `SetTravelDays` | `from > to`; span > 21 days; from in the past | — |
| `UpdateNutritionTargets` | protein ∉ 1.0..3.5 g/kg; kcal < `max(REE, nut05KcalFloorTdeeFactor 0.75 × TDEE)` or kcal > `nut06KcalCeilingTdeeFactor 1.3 × TDEE` ("outside NUT-05/06 bands — log a set of weigh-ins instead"); no `EnergyModel` yet (needs a weigh-in) | step > 200 kcal vs current (NUT-16) |

Application: user taps **Apply** → `PlanOverride(source = CHAT, proposalId = tool_use.id)` is
appended → `PlanRepository.saveSnapshot(planner.plan(...))` → `PlanDiffer.between(before, after)`
returns the §2.9 `PlanDiff` (sessions changed, sets per muscle before/after, warnings, under-target
report) → `tool_result` content `{"status":"applied","diff":{…}}`. **Reject** →
`{"status":"rejected","reason":"<optional text>"}`.
**Later** → `{"status":"pending","note":"the user has not decided; do not re-propose unless asked"}`.
Overrides can be revoked from the Adjustment log ("undo this change", 5-minute snackbar or later
from the log); revocation appends `revokedAt` — history is append-only. `OverrideResolver` computes
the active set (§4.3) and applies conflict rules (later wins per target; `ExcludeExercise` beats
`PinExercise` for the same id; a revoked `ChangeGoal` reverts to the profile goal at the next
week boundary).

### 4.16 `PlanValidator` invariants (a failing plan never ships; WP-03)

1. Every TIER_1 and TIER_2 muscle has fractional weekly sets ≥ VOL-01 (4) **or** appears in
   `underTargetReport` with a PLANNER proposal (the validator fails only when a deficit is silent).
2. Every session's `estimatedMinutes` ≤ `sessionMinutes` + `sess01BudgetToleranceMin` (0).
3. No session exceeds the SESS-02 cap; no muscle exceeds VOL-06 (12) fractional in one session.
4. Every exercise in every slot is available in the plan's gym (R4 algebra) and not excluded.
5. No block prescribes RIR 0 on a `requiresSpotterOrSafeties` exercise; RIR ≥ 2 when the block's
   satisfied option lacks `rack_or_spotter` (§3.1 decision — bar-over-face lifts cannot appear
   without it at all, invariant 4); every prescribed RIR ≤ 4 except deload (fixed 4).
6. MIX-02 pattern coverage holds for the week (squat or lunge_split when no bilateral squat is
   available; hinge; horizontal push; vertical or incline push; horizontal pull; vertical pull or a
   second horizontal pull when nothing vertical is available).
7. No two sessions on consecutive days share a heavy (HEAVY/MAX_STRENGTH) lift for the same muscle
   group when `daysPerWeek ≤ 5` (SPLIT-02), unless an `Explanation(SPLIT-02)` is present.
8. RAMP sets are never counted in `sets` / volume (WU-03).
9. Cardio slots respect CAR-06 (no HIIT the day before a lower session; running not adjacent).
10. Every slot, block and modifier has a non-empty `Explanation.ruleIds`, each present in `rules.json`
    (planner conventions such as `ROUNDING`, `OVERRIDE`, `RIR-CAP`, `TIME-L1..7` are entries, §3.5).
11. Rep ranges lie inside the rep profile's goal range (or the AGE-02 compound band for age ≥ 60)
    shifted by `repZoneShift`/PROG-02 extension/R4-2.6 extension.
12. Plan JSON round-trips through `CoreJson.stored` unchanged (byte-identical after decode → encode).
13. Every required slot has ≥ 2 sets; every opened optional slot has ≥ 2 sets (VOL-07).
14. Exercises of `beginnerOnly` plans are BEGINNER; ADVANCED exercises only when allowed.
15. Every `PlannedSlot.exerciseId`'s `pattern` is in the template slot's `patterns` list (selection
    and REBAL never leave the slot's pattern family).
16. Every `supersetGroup` pairs exactly the two slots the template links by `superset_with`; no
    other slot shares that group id.
17. Goal STRENGTH, by split row (§3.4): on a row **that has a strength variant** (`upper_lower_4`,
    `ul_ppl_5`, `ul_6`, `upper_lower_full_3` — the `for_goals: ["strength"]` rows) every main-lift
    pattern (squat, hinge, horizontal push, vertical push) **that has a `heavy` slot in the row's
    templates** and is available in the plan gym appears in ≥ 2 sessions of the week — at least once
    in that `heavy` slot and at least once in a `backoff_of` slot that references it by
    `<templateId>.<slotId>` of the same split row and holds the same exercise (§3.4 `_S` templates,
    §4.5); `ul_6` repeating the heavy day (UPPER_A_S4 / LOWER_A_S twice) is allowed. Patterns without
    a heavy slot in the row (hinge and vertical push in `upper_lower_full_3`) are not required to
    have a back-off. On the rows **without** a strength variant (`full_body_1`, `full_body_2`,
    `full_body_3`, `travel_2`) the invariant instead requires an `Explanation(FRQ-02)` in
    `Mesocycle.explanations` (one exposure per lift, §4.5). `PlanValidatorTest` case "NOVICE +
    STRENGTH + 3 days (FULL_BODY_3) validates with the FRQ-02 note" pins the second branch.
18. `PlannedSession.id` is unique within the `Mesocycle` (the `d<isoWeekday>` segment, §2.7) —
    `PlanValidatorTest` checks it with a `ppl_6` plan (PUSH/PULL/LEGS twice a week) and a `ul_6`
    plan.

The generator retries with the next split row when validation fails, then fails loudly with the
violated invariants (never returns an invalid plan). `PlanValidatorTest` builds one violating plan
per invariant, 1–18.
### 4.17 Worked examples (golden regression fixtures; numbers computed with the formulas above)

Placeholder profile **P1** (used by E1, E2 and the golden fixtures G1–G6; the real values come
from onboarding): male, born 1988 (38 y in 2026), 84.0 kg, 180 cm, 18 months consistent training,
no layoff → INTERMEDIATE; goal HYPERTROPHY; 4 days/week; 60 minutes; no limitations; no medical
flags; resting HR 58; bedtime 23:00. Known loads (onboarding): `barbell_bench_press` 80,
`back_squat` 110, `romanian_deadlift` 100, `conventional_deadlift` 140, `overhead_press_barbell` 50,
`barbell_row` 70, `hip_thrust` 120, `bulgarian_split_squat` 22 per hand, `goblet_squat` 24 (kettlebell).
Gym **HOME** = R4 `home_gym` preset: barbell + plates (smallest 1.25, no microplates), adjustable
dumbbells max 32 step 2 (min 2), kettlebells [16, 24], adjustable bench, power rack (safeties,
pull-up bar), pull-up bar, mat, resistance bands, foam roller, jump rope; default session 60 min.

#### E1 — home gym, 4 days upper/lower (Stages 0–5, week 1)

Stage 0: INTERMEDIATE; rep-profile key `hypertrophy`; tiers TIER_1 = chest, lats, upper_back,
quads, hamstrings, glutes, side_delts; TIER_2 = biceps, triceps, rear_delts, calves, abs.
Stage 1: 6 accumulation weeks + deload week 7; week-1 `rirOffset = +1`. Stage 2: UPPER_LOWER_4 on
Mon/Tue/Thu/Fri (SPLIT-02 satisfied). Stage 3 week-1 targets: TIER_1 12.0, TIER_2 7.0
(`round(0.6 × 12) = 7`). Budget per session: 60 − 10 warm-up = **50.0 min**; SESS-02 cap 25.

Availability at HOME excludes every machine, cable and lat-pulldown exercise; rows, presses and
pulls resolve to barbell/dumbbell/bodyweight/band options (`face_pull` via
`resistance_bands + band_anchor_any`; `chest_supported_dumbbell_row` via `dumbbells + bench_incline_any`;
`inverted_row` via `rack_any + barbell`; `slider_leg_curl` needs nothing; `nordic_hamstring_curl`
and `front_squat` are ADVANCED and excluded; `pallof_press` via bands).

Final allocation after the greedy fill and the REBAL pass (min/set from §4.6; fractional credits
per set from R4):

**UPPER_A (Mon) — 23 working sets, 50.0 + 10 = 60.0 min**

| Slot | Exercise | Sets | min/set | Credit per set |
|---|---|---|---|---|
| s1 PRIMARY heavy | `barbell_bench_press` | 4 | 3.5 | chest 1.0, front_delts 0.5, triceps 0.5 |
| s2 PRIMARY | `barbell_row` | 3 | 2.5 | lats 1.0, upper_back 1.0, biceps 0.5, rear_delts 0.5, lower_back 0.25 |
| s3 SECONDARY | `seated_dumbbell_shoulder_press` | 3 | 2.5 | front_delts 1.0, side_delts 0.5, triceps 0.5 |
| s4 SECONDARY | `pull_up` | 3 | 2.5 | lats 1.0, upper_back 0.5, biceps 0.5, rear_delts 0.25 |
| s5 ISOLATION ⟂ s6 | `dumbbell_lateral_raise` 4 ⟂ `face_pull` (band) 2 | 4 + 2 | 2 pairs × 2.5 + 2 singles × 1.75 = 8.5 | side_delts 1.0, traps_upper 0.25 / rear_delts 1.0, upper_back 0.5, rotator_cuff 0.5 |
| s7 ACCESSORY ⟂ s8 | `dumbbell_fly` 2 ⟂ `incline_dumbbell_curl` 2 | 2 + 2 | 2 pairs × 2.5 = 5.0 | chest 1.0, front_delts 0.25 / biceps 1.0 |

Time: 14.0 + 7.5 + 7.5 + 7.5 + 8.5 + 5.0 = **50.0**.

**LOWER_A (Tue) — 16 sets, 48.4 + 10 = 58.4 min:** `back_squat` 4 (4.0 → 16.0), `romanian_deadlift`
3 (4.0 → 12.0), `bulgarian_split_squat` 2 (2.5 × 1.7 = 4.25 → 8.5, unilateral), `slider_leg_curl` 3
(1.75 → 5.25), `single_leg_calf_raise` 2 ⟂ `dead_bug` 2 (pair = max(2.5, 0.7 × (2.975 + 1.75)) =
3.31 → 6.62). Total 48.37; the 1.63 min left fit no further set (smallest add is 1.75).

**UPPER_B (Thu) — 22 sets, 50.0 + 10 = 60.0 min:** `pull_up` 4 (2.5 → 10.0),
`incline_dumbbell_press` 4 (2.5 → 10.0), `overhead_press_barbell` 3 (3.5, heavy → 10.5),
`chest_supported_dumbbell_row` 3 (2.5 → 7.5), `dumbbell_lateral_raise` 4 ⟂ `dumbbell_rear_delt_fly` 2
(2 pairs 5.0 + 2 singles 3.5 = 8.5), `dumbbell_fly` 2 in s7, unpaired (2 × 1.75 = 3.5; the optional
s8 `elbow_flexion` slot stayed closed).

**LOWER_B (Fri) — 19 sets, 48.2 + 10 = 58.2 min:** `conventional_deadlift` 3 (4.0 → 12.0),
`goblet_squat` 3 (2.5 → 7.5), `hip_thrust` 2 (2.5 → 5.0; HIP_EXTENSION_ISOLATION is a moderate
compound in the time model, §4.6), `reverse_lunge` 2 (4.25 → 8.5), `slider_leg_curl` 3
(1.75 → 5.25), `single_leg_calf_raise` 3 ⟂ `pallof_press` (band) 3 (3 pairs × 3.31 = 9.92). Total 48.17.

How the allocator got there. The greedy fill (required slots at their lower bound, then
marginal-gain adds until each session's 50 lifting minutes were used) first produced UPPER_B's
accessory superset as `lying_triceps_extension` 2 ⟂ `hammer_curl` 2 (UPPER_B 49.75 min) and LOWER_B
with `hip_thrust` 4 (10.0 min at 2.5/set). That week's ledger read: chest **10.0** (target 12),
side_delts **10.0** (12), but triceps **9.0** (target 7), biceps **10.5** (7) and glutes **17.0**
(12). The REBAL pass moved sets from surpluses to deficits at equal or lower time cost, **staying
inside each slot's pattern list** (invariant 15): UPPER_B s7 (`chest_fly|elbow_extension`)
`lying_triceps_extension` 2 → `dumbbell_fly` 2 (+2 chest, −2 triceps); UPPER_B s8 (`elbow_flexion`,
optional) `hammer_curl` 2 → closed (−2 biceps; the fly is now unpaired, 2 × 1.75 = 3.5 instead of
the 5.0 pair, −1.5 min); the freed minutes pay for UPPER_B s5 `dumbbell_lateral_raise` 3 → 4
(+1 side_delts, +1.75 min → 50.0 min exactly). No other slot of UPPER_A/UPPER_B admits a
`shoulder_abduction` set (s5 is at its 4-set profile cap on both days; s7/s8 exclude the pattern —
a `band_lateral_raise` in s7 would violate invariant 15), so side_delts end at 11 and go to the
under-target report. LOWER_B `hip_thrust` 4 → 2 and the freed 5.0 min refilled with +1
`single_leg_calf_raise` and +1 `pallof_press` (+1 calves, +0.5 abs, +1 obliques). Final week-1
ledger (80 working sets):

| Muscle | Tier | Target | Fractional | Direct | Δ | RP direct landmark (R4 §4.5) |
|---|---|---|---|---|---|---|
| chest | 1 | 12 | **12.00** | 12 | 0 | MAV 6–16 ✓ |
| lats | 1 | 12 | 13.00 | 13 | +1.0 | back MAV 8–20 ✓ |
| upper_back | 1 | 12 | 13.00 | 6 | +1.0 | (back aggregate) |
| quads | 1 | 12 | 13.00 | 11 | +1.0 | MAV 6–14 ✓ |
| hamstrings | 1 | 12 | 13.00 | 9 | +1.0 | direct 9 ≥ MRV lower bound 8 and ledger ≥ target → R4-4.5 soft cap (gain × 0.5) applied to further direct sets; note attached |
| glutes | 1 | 12 | 15.00 | 5 | +3.0 | MEV 6–8 — direct view under MAV, fractional above target (why both views are shown) |
| side_delts | 1 | 12 | 11.00 | 8 | **−1.0** | MAV 8–24: direct 8 at MAV lo; no further shoulder_abduction slot in UPPER_A/UPPER_B (under-target report) |
| biceps | 2 | 7 | 8.50 | 2 | +1.5 | MV 6–8 direct: "covered by pulls" |
| triceps | 2 | 7 | **7.00** | 0 | 0 | MV 0–4 |
| rear_delts | 2 | 7 | 8.75 | 4 | +1.75 | MAV 4–12 ✓ |
| calves | 2 | 7 | 5.00 | 5 | **−2.0** | MEV 4–6: direct 5 is at MEV |
| abs | 2 | 7 | 4.25 | 2 | **−2.75** | MEV 0–4 |
| front_delts / lower_back / adductors / obliques / forearms / traps_upper / rotator_cuff / hip_flexors | 3 | — | 11.0 / 6.25 / 4.5 / 3.0 / 1.5 / 2.0 / 1.0 / 0.5 | 6 / 3 / 0 / 3 / 0 / 0 / 0 / 0 | — | no target; direct ≤ 6 cap ✓ |

Credit arithmetic per session (fractional): UPPER_A chest 6, front_delts 5.5 (bench 4 × 0.5 +
DB shoulder press 3 × 1.0 + fly 2 × 0.25), triceps 3.5, lats 6, upper_back 5.5, biceps 5,
rear_delts 4.25, lower_back 0.75, side_delts 5.5, traps_upper 1, rotator_cuff 1. UPPER_B chest 6,
front_delts 5.5 (incline 4 × 0.5 + OHP 3 × 1.0 + fly 2 × 0.25), triceps 3.5, lats 7, upper_back 6.75,
biceps 3.5, rear_delts 4.5, side_delts 5.5 (lateral raise 4 × 1.0 + OHP 3 × 0.5), traps_upper 1.0.
LOWER_A quads 6, glutes 6, adductors 3.25, hamstrings 7.5, lower_back 2.5, forearms 0.75, calves 2,
abs 2, hip_flexors 0.5. LOWER_B quads 7, glutes 9, hamstrings 5.5, lower_back 3, adductors 1.25,
abs 2.25, calves 3, obliques 3, upper_back 0.75, forearms 0.75. (lower_back week total 0.75 + 2.5 +
3.0 = 6.25; front_delts 5.5 + 5.5 = 11.0.)

Under-target report (quiet cards): "side_delts −1, calves −2, abs −2.75 this week (60-minute budget
exhausted at 50.0 / 48.4 / 50.0 / 48.2 min; the upper templates have one shoulder_abduction slot
each). Options: two 6-minute home blocks on Wed/Sat (single-leg calf raise 2 × 15–25 + plank
2 × 30–60 s + band lateral raise 2 × 15–20, no equipment) — or accept: RP's direct landmarks
(side_delts MAV 8–24, abs MEV 0–4, calves MEV 4–6) say the direct counts are at or above the
minimum effective dose." No VOL-01 floor is threatened, so no proposal card.

Week-1 prescriptions for the first two UPPER_A blocks (`rirOffset +1`; `barbell_compound`
hypertrophy 5–10 @ rir [1,3] → `profileTargetRir` ceil(2) = 2, +1 → **3**; `compound` 8–12 @
[1,3] → 2, +1 → 3; the last accumulation week (6) uses `rir_last_week` [1,1] → 1; cap 4 never
binds on a Green day):

| Block | Sets | Reps | Load | RIR | Rest | Ramp (WU-02; bench: rep-band mid 7.5 → HEAVY zone 40/55/70/82.5 % × 8/5/3/2; row: mid 10 → MODERATE) |
|---|---|---|---|---|---|---|
| `barbell_bench_press` (80 kg, CALIBRATING from known load) | 4 | 5–10 | 80 kg | 3 | 180 s | 32.5 kg × 8, 45 kg × 5, 55 kg × 3, 65 kg × 2 (0.4/0.55/0.7/0.825 × 80 = 32/44/56/66 → nearest loads with 2.5-kg steps) |
| `barbell_row` (70 kg known) | 3 | 8–12 | 70 kg | 3 | 120 s | 0–1 ramp set at 65 % (pattern already warm: 45 kg × 5) |

Golden assertions (`PlanGeneratorGoldenTest` case `e1_home_ul4`): 80 sets; session minutes
50.0 / 48.37 / 50.0 / 48.17; the ledger table above to 2 decimals; underTargetReport =
[side_delts −1.0, calves −2.0, abs −2.75]; no proposals; every PlannedSlot's exercise pattern is in
its slot's `patterns` (invariant 15); every slot explanation cites SEL/REBAL and VOL-04/VOL-06.

#### E2 — one-off hotel day, dumbbells only, 45 minutes (Friday LOWER_B of E1's **week 1**)

Today: gym = `hotel_gym` preset (dumbbells max 20 step 2 min 2, adjustable bench, mat, treadmill,
bike, elliptical; default 45 min), `minutesOverride = null` → 45 min; readiness Green; week 1
(`rirOffset +1`; the week-1 targets and the E1 LOWER_B allocation above apply unchanged).
Availability: `conventional_deadlift`, `hip_thrust`, `pallof_press` are
unavailable; `goblet_squat` (dumbbell option), `reverse_lunge`, `slider_leg_curl`,
`single_leg_calf_raise` are available.

Substitution for the `conventional_deadlift` slot (desired working load 140 kg; muscle vector
glutes 1.0, lower_back 1.0, hamstrings 0.5, quads 0.5, upper_back 0.25, forearms 0.25; its ranked
list `trap_bar_deadlift`, `romanian_deadlift`, `sumo_deadlift`, `hip_thrust`, `back_extension_45`
is entirely unavailable → library search in the hinge family):

| Candidate | pattern | cosine | loadFit (desired → achievable) | continuity | difficulty | penalty | **score** |
|---|---|---|---|---|---|---|---|
| `dumbbell_romanian_deadlift` | hinge 1.0 → 0.350 | 0.757 → 0.265 | deadlift → RDL seed 0.70 × 140 = 98 vs 2 × 20 = 40 → 0.41 → tier 0.4 → 0.060 | 0 | 0.05 | — | **0.725** |
| `single_leg_romanian_deadlift` | 0.350 | 0.539 → 0.189 | 98 × 0.5 (unilateral) = 49 vs 1 × 20 = 20 (one dumbbell: `implements = 1`) → 0.41 → tier 0.4 → 0.060 | 0 | 0.05 | — | 0.649 |
| `glute_bridge` (single-leg + DB) | family 0.6 → 0.210 | 0.674 → 0.236 | hip thrust → bridge 0.5 × 120 = 60, × 0.5 = 30 vs 20 → 0.67 → 0.7 → 0.105 | 0 | 0.05 | — | 0.601 |
| `kettlebell_swing` (dumbbell option) | 0.350 | 0.790 → 0.276 | hinge → swing 0.20 × 140 = 28 vs 20 → 0.71 → 0.7 → 0.105 | 0 | 0.05 | power profile −0.20 | 0.581 |

Winner `dumbbell_romanian_deadlift` (the sheet lists the single-leg RDL second; one tap picks it).
`hip_thrust` → `glute_bridge` (first available ranked entry, +0.10 rank bonus); `pallof_press` →
`side_plank` (first available ranked entry).

Time budget: 45 min → warm-up already 5 min by WU-05 (automatic for a ≤ 45-min session, §4.6; L1
therefore has nothing to trim and is not logged, §4.7 step 5) → 40 min lifting. Full realization: DB RDL
3 × 2.5 = 7.5; goblet 3 × 2.5 = 7.5; glute bridge 2 × 1.75 = 3.5; reverse lunge 2 × 4.25 = 8.5;
slider curl 3 × 1.75 = 5.25; single-leg calf 3 ⟂ side plank 3 (both unilateral: 2.975 each; pair =
max(2.5, 0.7 × 5.95) = 4.165 → 12.5) → **44.7 min > 40**. Concession **L2**: hamstrings already
have 4.5 fractional sets from compounds this week (LOWER_A: RDL 3 × 1.0, back squat 4 × 0.25,
Bulgarian 2 × 0.25) ≥ VOL-01 → `slider_leg_curl` dropped (−5.25) → **39.5 ≤ 40** ✓. Refill: 0.5 min
left → nothing fits. Final: 16 sets, 39.5 + 5 = **44.5 min**.

| Block | Sets | Reps | Load | RIR (week 1: profile target +1, then modifiers) | Note |
|---|---|---|---|---|---|
| `dumbbell_romanian_deadlift` (sub for deadlift) | 3 | 15–30 | 2 × 20 kg (rack max; seed 98 → achievable 40, loadFit tier 0.4 → endurance band, LOAD-01/TRV-01) | 1 (fixed `trv01EnduranceRir`; stack bypassed) | find-load skipped: achievable max already binding |
| `goblet_squat` (state: 24 kg, KETTLEBELL → global key) | 3 | 8–14 | **20 kg** (working 24 > max 20: 20/24 = 0.83 ≥ 0.6 → keep at max, band +2, RIR −1) | 2 (`compound` [1,3] → 2, +1 week 1, −1 keep-at-max) | R4-2.6 warning shown; user chose "keep at max"; every set `prescribedLoadKg = 20 < 24` → capped session, state stays 24 (§4.9.3) |
| `glute_bridge` single-leg + DB (sub for hip thrust) | 2 | 6–12 (AMRAP−2) | 20 kg | 2 (`bodyweight_progression` [0,2] → 1, +1) | `bodyweight_progression` |
| `reverse_lunge` (seed: same family/load type from BSS 2 × 22 → 44 vs 40 → 0.91) | 2 | 8–14 per leg | 2 × 20 kg | 2 (`compound` 2, +1, −1 keep-at-max) | R4-2.6 warning (prescribed 2 × 22 > max 20) |
| `single_leg_calf_raise` ⟂ `side_plank` (sub for Pallof) | 3 + 3 | 15–25 / 30–60 s | 20 kg / — | 2 (`isolation` [0,2] → 1, +1) / — | superset, 30 s transition |

Volume re-accounting: planned LOWER_B credited quads 7.0, glutes 9.0, hamstrings 5.5, lower_back 3.0,
adductors 1.25, abs 2.25, calves 3, obliques 3, upper_back 0.75, forearms 0.75; realized: quads 5.0
(−2.0), glutes 6.0 (−3.0), hamstrings 4.0 (−1.5), lower_back 1.5 (−1.5), adductors 2.0 (+0.75),
abs 0.75 (−1.5), calves 3.0, obliques 3.0, glute_med +0.75. Week totals become quads 11.0
(target 12, ≥ VOL-01), glutes 12.0, hamstrings 11.5, abs 2.75, calves 5.0. Friday is the last
session of the week, so nothing absorbs the carryover; the Plan screen shows "quads −1.0,
hamstrings −0.5, abs −4.25 this week (hotel day)". No LAY-01 regression next week: the squat and
hinge patterns were trained (LAY-01 excess 0). Warm-up (WU-05): no Raise phase; drills `leg_swings`
(a `mobility_drills.json` drill id, §3.6 — not an R4 exercise; every other id in §4.17 is an R4 §5
exercise id) 2 × 10, `hip_90_90` 2 × 8 (≈ 3 min); ramp for block 1 (15–30 reps → LIGHT zone, one ramp set at
60 %): 2 × 12 kg × 8.

Golden assertions (`SessionRealizerTest` case `e2_hotel_45`): the score table above to 3 decimals
(0.725 > 0.649 > 0.601 > 0.581); concession ladder log = [L2] (L1 skipped: WU-05 already applied); final 39.5 / 44.5 min; 16 sets;
the RIR column above; realized credits as listed; carryover map {quads −1.0, hamstrings −0.5,
abs −4.25} (plus the muscles whose deficits existed before the hotel day are reported unchanged).

#### E3 — commercial gym, 3 days full body, beginner

Profile **P2**: male, born 1997 (29 y), 78 kg, 176 cm, 2 months consistent training → NOVICE;
goal GENERAL_FITNESS; 3 days (Mon/Wed/Fri); 60 minutes; no limitations; no known loads; gym =
`base_gym` preset (default 60 min). Stage 0: NOVICE, `beginnerOnly = true` (< 8 weeks of logged
sessions) → `back_squat`, `barbell_bench_press`, `conventional_deadlift`, `romanian_deadlift`,
`overhead_press_barbell`, `pull_up`, `barbell_row` are INTERMEDIATE and excluded; the plan is built
from BEGINNER machine/dumbbell/bodyweight exercises. Stage 1: first mesocycle (`mesocyclesCompleted
== 0`) → 12 accumulation weeks, no scheduled deload (reactive only); the second NOVICE mesocycle
would get 6 + deload (§4.4). PROG-05 → weeks 1–2 at RIR 3–4 and **no load increments** (hits
accumulate; the first PROG-01 increment can land in week 3). Stage 2: FULL_BODY_3 (FULL_A, FULL_B,
FULL_C). Stage 3 targets: TIER_1 **8.0**, TIER_2 **5.0** (`round(0.6 × 8) = 5`, floor 4). Budget
50 min; **SESS-02 novice cap 20 sets** — it binds before the time budget in two of three sessions.

**FULL_A (Mon) — 20 sets, 43.5 + 10 = 53.5 min:** `hack_squat` 3 (2.5 → 7.5; quads 1.0, glutes 0.5,
adductors 0.25), `dumbbell_bench_press` 3 (7.5), `seated_cable_row` 3 (7.5), `dumbbell_romanian_deadlift`
3 (7.5), `machine_shoulder_press` 2 (5.0), `dumbbell_lateral_raise` 2 ⟂ `face_pull` 2 (5.0), `plank`
2 (3.5). The cap stops the fill at 20 sets with 6.5 min unused.

**FULL_B (Wed) — 18 sets, 49.75 + 10 = 59.75 min:** `trap_bar_deadlift` 3 (4.0 → 12.0),
`lat_pulldown` 3 (7.5), `incline_dumbbell_press` 3 (7.5), `split_squat` 3 (4.25 → 12.75), 
`chest_supported_dumbbell_row` 2 (5.0), `dumbbell_lateral_raise` 2 ⟂ `seated_calf_raise` 2 (5.0).
The budget stops the fill (core slot would need 3.5 min).

**FULL_C (Fri) — 20 sets, 39.75 + 10 = 49.75 min:** `leg_press_45` 3 (7.5), `machine_chest_press`
3 (7.5), `machine_row` 3 (7.5), `back_extension_45` 3 (1.75 → 5.25; glutes 1.0, hamstrings 0.5,
lower_back 0.5), `dumbbell_lateral_raise` 2 ⟂ `reverse_pec_deck` 2 (5.0), `standing_calf_raise` 2
(3.5), `cable_crunch` 2 (3.5). Cap reached.

REBAL: the greedy first put `cable_curl` 2 in FULL_B's superset (biceps 7.5 vs target 5) while
calves ended at 2 < VOL-01; REBAL swapped it for `seated_calf_raise` 2 at equal time cost (calves
4 ✓ VOL-01; biceps 5.5 ✓).

Week ledger (58 sets; targets TIER_1 8 / TIER_2 5): chest 9 ✓, lats 11, upper_back 12.25,
quads 10.5, glutes 12, hamstrings 6.75 (−1.25), side_delts 7 (−1), biceps 5.5 ✓, triceps 5.5 ✓,
rear_delts 8.75, calves 4 (−1, ≥ VOL-01), abs 4 (−1); tier 3: front_delts 6.5, lower_back 5.25
(cap 6 ✓), adductors 3, forearms 1.5, traps_upper 1.5, rotator_cuff 1, obliques 1.5. Under-target
report: "side_delts −1, hamstrings −1.25, abs −1, calves −1 (novice session cap of 20 sets, SESS-02);
all ≥ VOL-01" — quiet cards, no proposal.

Week-1 prescriptions (PROG-05 `prog05NoviceRir` [3,4] → target ceil(3.5) = 4; all states UNKNOWN →
find-load): `hack_squat` 3 × 8–12 @ 4, find-load from the body-weight seed (SQUAT/MACHINE fraction
1.0 × 78 = 78 kg sled load, "starting guess") ramp 78 → 90 → 100 → 112.5 (rounded to 2 × 2.5 kg
plates) until the reported RIR ≤ min(4 + `trv04StopRirOffset` 1, cap 4) = 4; `dumbbell_bench_press`
3 × 8–12 @ 4, seed 0.15 × 78 = 11.7 → 2 × 12 kg ramp 12 → 14 → 16 → 18; `seated_cable_row`
(gym-scoped key `seated_cable_row@<base gym id>`) 3 × 8–12 @ 4, seed 0.45 × 78 = 35 → stack 35 →
40 → 45 → 50; `plank` 2 × 20–60 s. Increment cadence for a novice from week 3 on: every session
that hits PROG-01 on barbell class; two consecutive hits on machines/dumbbells (R4 §5.4); weeks 1–2
are gated by PROG-05 (`MesocycleBuilderTest` / `ProgressionEngineTest`).

Golden assertions (`PlanGeneratorGoldenTest` case `e3_commercial_fb3_novice`): 58 sets; session
sets 20/18/20; minutes 43.5 / 49.75 / 39.75; every exercise BEGINNER; ledger as above; no deload
week (first mesocycle); `underTargetReport` size 4; no proposals.

#### Golden fixtures G1–G6 (numbers verified; each is a test case in §10.4)

- **G1 bench progression across a mesocycle (P1, PROG-01/02/03, E1RM):** prescribed RIR by week
  (`barbell_compound` hypertrophy [1,3] → profile target 2; `rir_last_week` [1,1]): **3 / 2 / 2 / 2 /
  2 / 1**, deload 4, re-entry 3. Week 1 (RIR 3): 4 × 5–10 @ 80, logged 8, 8, 7, 7 @ 3 → hold. Week 2
  (RIR 2): 9, 9, 8, 8 @ 2 → hold, reps-at-load improved → `noProgressCount = 0`. Week 3: 10, 10, 9, 9
  @ 2 → hold (2 sets short of hi). Week 4: 10, 10, 10, 10 @ 2 → PROG-01, cadence ok (intermediate,
  ≥ 7 days since the last increment or never) → **+2.5 kg** (UPPER_BARBELL) → 82.5 kg. Week 5: 8, 8,
  7, 7 @ 2 → hold. Week 6 (last accumulation week: `rir_last_week.hi` = 1; the compound floor 1
  does not bind): 9, 9, 8, 8 @ **1** → hold. Week 7 DELOAD: `ceil(4 × 0.5) = 2` × 5–10 @ 0.85 ×
  82.5 = 70.1 → **70 kg**, RIR 4; logged 70 × 8 @ 4 → e1RM 93.3 is stored in history but is
  **unfair** (DELOAD) and excluded from `bestE1rm14d` (stays 110.0 from week 6). Week 8 (REENTRY;
  RIR 3 = profile 2 + the week-1 offset, no second +1): 3 × 5–10 @ 0.95 × 82.5 = 78.4 → **77.5 kg**;
  state keeps 82.5; the session is capped (`prescribedLoadKg 77.5 < 82.5`) → no INCREMENT, no
  PLAT-01 count, no PLAT-03 comparison. Assertions after week 7: status DELOADING, `noProgressCount`
  0; after week 8: status REENTRY, `noProgressCount` 0, no PLAT-03 flag; week 9 (NORMAL):
  PROGRESSING. e1RM: 80 × 8 @ 3 → 106.7 (HIGH); 100 × 6 @ 2 → 125.4. Autoregulation: week 3 set 1
  logged 80 × 7 @ **0** → chip: sets 2–4 at 0.95 × 80 = 76 → **75 kg** (2.5-kg steps). Dumbbell
  increments (per-hand basis, §4.9.3): 22 → 24 kg is +9.1 % (ok); 12 → 14 kg is +16.7 % → rep
  ceiling +2 first — identical whether the user logs "12 kg per hand" or the pair "24 kg".
- **G2 readiness (RDY-01/02):** full form sleep 6.5 h → 10; quality 3 → 8; soreness 2 → 12;
  stress 3 → 8; energy 3 → 8; motivation 4 → 12 = **58 → Amber**. With RHR +6 bpm over the 7-day
  mean: 58 − 8 = **50**, Amber. Red: sleep 5.5 → 4; quality 2 → 4; soreness 3 → 8; stress 4 → 4;
  energy 2 → 4; motivation 3 → 8 = **32 → Red**. Quick mode: slept "6–7 h" (midpoint 6.5 → 10),
  feel 3 → energy 8 + motivation 8, sore 2 → 12: 38 of a 68-point maximum → **56 → Amber**,
  `assumedFields = [sleep_quality, stress]`. All items at best = **100**; at worst = **0**.
  Amber on E1 UPPER_A: bench 4, row 3 (first two unchanged), then −1 set each: DB press 2, pull-up 2,
  lateral raise 3, face pull 1, fly 1, curl 1 → 17 sets, RIR +1 (week 1: bench 3 → 4, at the cap
  but not capped; mid-mesocycle 2 → 3), no increments. Red in week 1: 3 + 2 = 5 → **capped 4**
  with the RIR-CAP explanation — the only case where Amber and Red coincide; mid-mesocycle Red is
  2 + 2 = 4, Amber 3.
- **G3 cardio zones (P1, CAR-02/03/04):** HRmax = 208 − 0.7 × 38 = **181.4**; zone 2 by HRmax
  60–70 % → **109–127 bpm**; by HRR (rest 58, reserve 123.4) 40–59 % → **107–131 bpm**; vigorous
  77–95 % HRmax → **140–172**; 4 × 4 work bouts 90–95 % → **163–172**, recovery ~70 % → 127.
- **G4 nutrition (P1, NUT-01/04/05/06/08/09/10/11/15/16):** REE = 10 × 84 + 6.25 × 180 − 5 × 38 + 5
  = **1,780 kcal**; TDEE × 1.55 = **2,759**; HYPERTROPHY +10 % = **3,035**; protein 1.8 × 84 =
  **151 g**; fat 1.0 × 84 = **84 g**; carbs = (3,035 − 604.8 − 756) / 4 = **419 g** (5.0 g/kg ≥
  3 g/kg ✓); fibre 14 × 3.035 = **42 g**; water **3.7 L**. Adaptive TDEE with ≥ 10 kcal logs (mean
  intake 3,000; observed +0.6 kg/week): expected = (3,000 − 2,759) × 7 / 7,700 = +0.219 kg/week;
  raw correction = (0.219 − 0.6) × 7,700 / 7 = −419 → clamped **−150 → 2,609**. Without kcal logs:
  NUT-16 only (hypertrophy band 0.25–0.5 %/week = 0.21–0.42 kg/week; observed +0.6 → −150 kcal
  step, review in 14 days). Caffeine cut-offs (bedtime 23:00): ≥ 200 mg not after **10:00**;
  ≤ 100 mg not after **14:00**. Creatine 0.1 × 84 = 8.4 g → capped to the 3–5 g band → **5 g/day**.
- **G5 travel week (P1, TRV-01/02):** CHK Q10 = 5 → week 3 replanned as `travel_2` on Tue/Thu
  at `hotel_gym`, 35 min each, week counter paused at 3. TRAVEL_A: `bulgarian_split_squat` 3 × 10–14
  @ 2 × 20, `single_leg_romanian_deadlift` 3 × 12–15, `dumbbell_bench_press` 3 × 15–25 (working
  2 × 30 → warning, keep at max), `dumbbell_row` 3 × 10–14 per side, `dumbbell_lateral_raise` 2 ⟂
  `push_up` 2 AMRAP. TRAVEL_B (§3.4): `split_squat` 3 × 12–15, `dumbbell_romanian_deadlift` 3 ×
  20–30 @ 1 (fixed endurance RIR), `seated_dumbbell_shoulder_press` 3 × 10–15,
  `chest_supported_dumbbell_row` 3 × 10–14, `dumbbell_lateral_raise` 2 ⟂ `incline_dumbbell_curl` 2.
  RIR (TRV-01 0–2): dumbbell compounds `profileTargetRir` 2 with offset 0 → 2; isolation 1;
  light-load endurance sets fixed 1. VOL-01 check (R4 weights): chest 3 + 2 = **5** ✓;
  lats/upper_back 3 + 3 = **6** ✓; quads BSS 3 + split squat 3 = **6** ✓; hamstrings SL-RDL 3 +
  DB RDL 3 = **6** ✓; glutes 1.5 + 1.5 + 1.5 + 1.5 = **6.0** ✓; side_delts 2 + 2 + 1.5 = **5.5** ✓
  (asserted in the `travel` golden case). On return week 3 resumes; barbell lifts skipped for one
  travel week resume at their state loads (per-exercise excess 14 − 7 = 7 < `lay01ExerciseGapDays`,
  patterns maintained); only after two consecutive travel weeks does the 5–13-day LAY-01 row
  (95 %, RIR +1) apply to them.
- **G6 plateau ladder (P1 bench):** weeks 3–5 of a later mesocycle: 82.5 × (8, 8, 7, 7), (8, 8, 8, 7),
  (8, 8, 7, 7) @ RIR 2 with readiness ≥ Amber (fair, NORMAL weeks) → `noProgressCount = 3` →
  PLATEAU. Step 1: CHK Q1 = 6.2 h → SLP-03 (sets × 0.9, RIR +1 on heavy compounds) + coach message
  citing Craven 2022. Step 2 (next week, advanced by `rollWeek`): band 5–10 → 8–12 (`repZoneShift =
  1`), load re-seeded from `bestE1rm14d` = 82.5 × 8 @ 2 → r_eff 10 → **110.0**: 110.0 × pct(10 + 2 =
  12) = 110.0 × 0.71 = 78.1 → **77.5 kg** (2.5-kg steps). Step 3: proposal
  `SwapExercise(barbell_bench_press → dumbbell_bench_press, REST_OF_MESOCYCLE)` (first available
  ranked entry at HOME).

---
## 5. Readiness, check-ins, cardio, nutrition, supplements, warm-up, mobility (WP-04; warm-up/mobility WP-03)

### 5.1 Daily readiness (`ReadinessScorer`, RDY-01..04, SLP-03, NUT-13)

```kotlin
// core/src/main/kotlin/com/gymcoach/core/readiness/ReadinessScorer.kt (signature contract)
package com.gymcoach.core.readiness

import com.gymcoach.core.model.*
import com.gymcoach.core.planner.PlannerConstants
import java.time.Instant
import java.time.LocalDate

data class ReadinessInput(
    val date: LocalDate,
    val quickMode: Boolean,
    val sleepHours: Double?, val sleepQuality: Int?, val soreness: Int?, val sorenessRegion: Muscle?,
    val stress: Int?, val energy: Int?, val motivation: Int?,
    val restingHr: Int?, val restingHrBaseline7d: Double?,        // mean of the previous 7 morning RHR values
    val jointPain0to10: Int = 0, val jointRegion: LimitationRegion? = null, val redFlagReported: Boolean = false,
    val alcoholDrinksLastNight: Int = 0,
    val restingHrElevatedDays: Int = 0,                            // consecutive prior days with RHR ≥ baseline + 5 (RDY-03)
    val illness: Boolean = false,
)

class ReadinessScorer(private val c: PlannerConstants) {
    fun score(input: ReadinessInput, id: String, now: Instant): ReadinessCheckin
}
```

Points (RDY-01; the weights are conventions, weak):

| Input | Points | Max |
|---|---|---|
| Sleep hours | ≥ 7.5 → 20; 7.0–7.4 → 16; 6.0–6.9 → 10; 5.0–5.9 → 4; < 5 → 0 | 20 |
| Sleep quality q (1–5) | (q − 1) × 4 | 16 |
| Soreness s (1–5, 5 severe) | (5 − s) × 4 | 16 |
| Stress st (1–5) | (5 − st) × 4 | 16 |
| Energy e (1–5) | (e − 1) × 4 | 16 |
| Motivation m (1–5) | (m − 1) × 4 | 16 |
| RHR delta vs 7-day baseline (optional) | ≤ +3 → 0; +4..+7 → −8; ≥ +8 → −16 | adjustment |

`score = round(100 × Σ points(answered) / Σ max(answered)) + rhrAdjustment`, clamped 0..100. Missing
inputs are dropped from both sums (renormalisation) and listed in `assumedFields`. Quick mode
(B graft, 3 taps): "Slept" chips `< 5 · 5–6 · 6–7 · 7–7.5 · 7.5+` (midpoints 4.5 / 5.5 / 6.5 /
7.25 / 8.0), "Feel" 1–5 (fills energy **and** motivation), "Sore" 1–5; sleep quality and stress are
assumed. Band (RDY-02): Green ≥ 70, Amber 45–69, Red < 45. Overrides: sleep < 6 h → at least Amber;
`restingHrElevatedDays ≥ 2` (≥ +5 bpm) → at least Amber, ≥ +10 bpm or `illness` → Red (RDY-03);
`alcoholDrinksLastNight ≥ 3` → at least Amber (NUT-13); `redFlagReported` → the RED-01 flow
(§5.2), no session. The `Explanation` lists every contributing item and every override.

Modifiers are applied by `ReadinessModifiers` inside the realizer (§4.7 step 3). The user may still
tap "Train as planned" (recorded as `Adjustment(kind = USER, ruleIds = [RDY-02])`).

### 5.2 Weekly check-in and red flags (`CheckinBank`, `CheckinEvaluator`, `RedFlags`)

Selection (CHK-01, ≤ 8 items): always Q4 and Q8 (auto-computed, shown for confirmation), Q1 (or Q2
on alternate weeks), Q3, Q7; then rotate Q5/Q6/Q9/Q10/Q11/Q12/Q13/Q14/Q15 so each appears at least
every third week (Q15 only in a deficit; Q10 always in the week before marked travel days); plus
open coach/user questions (`CheckinQuestion`, ≤ 5 custom active). Auto-answers: Q4 from
`WeightTrend` (14-day MA7 change), Q5 from `BodyMetric.steps` mean, Q8 from completed/planned
sessions. Trigger mapping (`CheckinEvaluator.evaluate(checkin, history, inDeficit): CheckinOutcome`
— `triggered` explanations, `proposals`, `pendingModifiers` and VOL-08 `holds`, §2.15; the
`CheckinViewModel` saves `checkin.copy(triggered = outcome.triggered, holds = outcome.holds.sorted())`
through `MetricsRepository.saveWeeklyCheckin`, which is how the holds reach `TargetPolicy` at the
next `rollWeek`/`recompute` via `inputs.weeklyCheckins` (§4.4) — `CheckinOutcome` itself is never
persisted):

| Q | Trigger | Effect |
|---|---|---|
| Q1 < 7 / Q2 ≤ 2 | SLP-03 | weekly sets × 0.9, RIR +1 on heavy compounds until the average is ≥ 7 h; coach prompt with Craven 2022 |
| Q3 ≤ 4 | NUT-01 | coach protein coaching; Nutrition card "protein days 3/7" |
| Q4 outside the goal band | NUT-16 | `UpdateNutritionTargets` proposal ±100–200 kcal with trigger values |
| Q5 < STP-01 target | STP-02 | step target card |
| Q6 ≥ 4 on two consecutive check-ins | RDY-05 | sets × 0.8, main-lift RIR +1, VOL-08 ramp held, zone-2 walks offered, no new HIIT (STR-02) |
| Q7 yes | INJ-00..04 | limitation card ("log it?" → `SetLimitation`); same region two weeks or > 10 days → RED-02 referral card |
| Q8 < 70 % | TIME-01 / SPLIT-01 | re-plan proposal ("shorter sessions / fewer days?") |
| Q9 > 7 or any day > 3 | NUT-13 | coach prompt; alcohol chip |
| Q10 ≥ 3 | TRV-01 | travel-week proposal (`SetTravelDays`) |
| Q11 ≤ 2 | DLD-03 | counts as a trigger |
| Q12 yes twice | VOL-08 | ramp paused for the muscles named |
| Q13 yes | SUP-02 | caffeine cut-off card |
| Q14 ≤ 2 | PROG-04 | audit note in the coach context; calibration set scheduled next week |
| Q15 ≥ 4 for two weeks in a deficit | NUT-17 | diet-break proposal (1 week at maintenance) |

Red flags (`RedFlags`): the RED-01 symptom list (chest pain/pressure radiating; unusual dyspnoea;
dizziness/fainting; palpitations with symptoms — these four → "call emergency services"; new
numbness/tingling/weakness; bladder/bowel loss or saddle numbness with back pain; hot swollen
locking joint; sudden pop with loss of function; severe headache during lifting; calf
swelling/pain with warmth; dark urine or extreme muscle pain/swelling; fever/systemic illness) is
shown in onboarding, behind "Something feels wrong" on the session and readiness screens, and by
`RedFlagScanner` (§6.12) before a chat message is sent. Any selection ends the session, records
`Adjustment(kind = SAFETY, ruleIds = [RED-01])`, blocks training advice for that region until the
user clears it in Profile, and shows the hand-off text. RED-02: pain > 3/10 persisting > 10 days
(from `Limitation.since` with pain > 3, or Q7 twice) → "see a physiotherapist/physician" card;
substitutions continue for other regions only. RED-03: `medicalFlags` non-empty without
`clearanceConfirmedOn` → HIIT, MAX_STRENGTH zone and supplement doses gated. POL-02: no diagnosis
language anywhere (copy review is a §10 acceptance item).

### 5.3 Sleep, steps, stress, weekly training load (SLP-01..03, STP-01/02, STR-01/02, RDY-06)

- SLP-01 target 7–9 h (≥ 65 y 7–8). SLP-03 as in §5.2/§4.7. Sleep-hygiene items the coach may
  raise: consistent wake time, caffeine cut-off (SUP-02), alcohol (NUT-13), dark/cool room, ≤ 20-min
  nap before 15:00 on travel days.
- STP-01 default target 7,000–8,000 (< 60 y) / 6,000–7,000 (≥ 60); STP-02 in FAT_LOSS:
  `min(max(target, baseline14d + 2,000), 12,000)`. Steps are manual entries (`BodyMetric.steps`)
  in v1, entered in the Readiness full form (next to resting HR, §8.7) or the Nutrition weigh-in row
  (§8.16); both write `BodyMetric.steps` for the date, and CHK Q5 auto-answers from the 7-day mean
  ("from your step entries — edit if wrong").
- STR-01/02 → RDY-05 modifiers; zone-2 walks 20–30 min offered; no new HIIT.
- RDY-06 weekly training load = Σ (session RPE × minutes); a week > 150 % of the previous 4-week
  mean shows an advisory "spike" note on Progress (never a modifier).

### 5.4 Cardio (`CardioPlanner`, `HeartRateZones`, `HiitProtocols`; CAR-01..09)

```kotlin
// core/src/main/kotlin/com/gymcoach/core/cardio/HeartRateZones.kt
package com.gymcoach.core.cardio

data class HrZone(val lo: Int, val hi: Int)

object HeartRateZones {
    /** CAR-03 Tanaka; a measured max wins. */
    fun hrMax(age: Int, measured: Int?): Double = measured?.toDouble() ?: (208.0 - 0.7 * age)
    fun pctOfMax(hrMax: Double, lo: Double, hi: Double): HrZone = HrZone(Math.round(hrMax * lo).toInt(), Math.round(hrMax * hi).toInt())
    /** Karvonen: rest + fraction × (max − rest). */
    fun karvonen(hrMax: Double, restingHr: Int, lo: Double, hi: Double): HrZone {
        val reserve = hrMax - restingHr
        return HrZone(Math.round(restingHr + lo * reserve).toInt(), Math.round(restingHr + hi * reserve).toInt())
    }
    fun zone2ByMax(hrMax: Double): HrZone = pctOfMax(hrMax, 0.60, 0.70)            // CAR-02
    fun zone2ByReserve(hrMax: Double, restingHr: Int): HrZone = karvonen(hrMax, restingHr, 0.40, 0.59)
    fun vigorousByMax(hrMax: Double): HrZone = pctOfMax(hrMax, 0.77, 0.95)         // CAR-04
    fun hiit4x4Work(hrMax: Double): HrZone = pctOfMax(hrMax, 0.90, 0.95)           // CAR-05
}
```

Weekly prescription by goal (CAR-07) and placement (CAR-06, ORD-03):

| Goal | Prescription | Placement |
|---|---|---|
| GENERAL_FITNESS | 2 × 30–45 min zone 2 + 1 HIIT (once eligible) → meets CAR-01 | zone 2 as finishers after UPPER sessions or on off days; HIIT on an off day ≥ 24 h before a leg day |
| HYPERTROPHY / STRENGTH | 2 × 20–30 min zone 2 (health floor); HIIT optional 1×, not on/before a leg day | finishers after UPPER sessions or off days; cap ≤ 3 sessions / ≤ 150 min beyond the floor unless the user asks |
| FAT_LOSS | 150–250 min/week moderate, rising toward > 250 if progress stalls (NUT-16 loop); HIIT not favoured for weight (Jakicic 2024); STP-02 steps | spread over off days and finishers |
| ENDURANCE_SUPPORT | user's own plan is primary | lifting ≥ 6 h from key cardio sessions |

Placement rules: (1) off days first; (2) on a lifting day after lifting (ORD-03) or a separate slot
≥ `car06SeparationHours` (6) away, 24 h preferred; (3) no running within 24 h before a heavy leg
session; bike/rower/elliptical/ski-erg ranked above treadmill running next to leg days (CAR-08
adjacency from `cardio_modalities.json`); jump rope and running never with a knee limitation;
(4) HYPERTROPHY/STRENGTH cap as above. Intensity control: zone 2 = two of {talk test, 60–70 %
HRmax, 40–59 % HRR, RPE 11–13}; the card shows the HR band (HRmax-based; HRR-based when resting HR
is logged), the talk-test sentence and RPE 3–4/10 so a session without a strap is still
prescribable. HIIT menu (CAR-05): 4 × 4 min @ 90–95 % HRmax / 3 min @ ~70 % (default); 10 × 1 min
@ ~90 % / 60–75 s easy; SIT 4–6 × 30 s all-out / 4 min (ADVANCED, no medical flags). Gating: ≥
`car05HiitBaseWeeks` (4) weeks with ≥ 2 logged zone-2 sessions/week and no RED-03 gate. Progression
(CAR-09): +10 % duration/week to the target; add one interval (4 × 4 → up to 6; 10 × 1 → up to 12)
before raising intensity. A finisher is charged on top of `sessionMinutes` unless
`profile.cardioInsideSessionMinutes` (then the lifting budget shrinks by `minutes + 3`). Modality
resolution uses `EquipmentResolver` (cardio machines are R4 batch-6 exercises; `outdoor_run_walk`
and `jump_rope` need no gym).

### 5.5 Nutrition (`EnergyEstimator`, `TdeeAdapter`, `WeightTrend`, `MacroTargets`; NUT-01..17)

- **REE** (NUT-04, Mifflin–St Jeor): men `10W + 6.25H − 5A + 5`; women `10W + 6.25H − 5A − 161`
  (W = latest MA7 kg or latest weigh-in, H cm, A years). **Activity factor** [weak]: from
  `profile.activityFactorOverride`, else chosen from `daysPerWeek` and the 14-day step mean:
  1.2 (< 5,000 steps and < 2 sessions), 1.375 (1–3 sessions or 5–8k), 1.55 (3–5 sessions or 8–12k or
  `physicalJob`), 1.725 (6–7 sessions or physical job + training), 1.9 (extreme; user-set only).
  Shown as "starting guess (NUT-04)".
- **Adaptive TDEE** (NUT-04, H14): every `nut04WindowDays` (14) days, only when the window has
  ≥ `nut04MinKcalLogs` (10) `NutritionLog.kcal` entries and ≥ 10 weigh-ins: `observed =
  Δ(MA7) per week`; `expected = (meanIntake − TDEE) × 7 / 7,700`; `TDEE += clamp((expected −
  observed) × 7,700 / 7, ±150)`; each step is a `TdeeAdjustment` with its inputs. Weeks containing
  ≥ 3 `travelDay` logs or Q10 ≥ 3 are skipped (NUT-14 (4) sodium/weekend noise).
- **Targets** (`MacroTargets.compute(profile, weightKg, age, goal, trainingAge, energy, state,
  cardioSessionsPerWeek, today)`, §2.15): protein NUT-01 — HYPERTROPHY/STRENGTH 1.6–2.2 g/kg
  (default `nut01DefaultGPerKg` 1.8; **age ≥ 40 → `age01ProteinGPerKg` 1.9**, the NUT-01 upper half,
  AGE-01); FAT_LOSS 2.3–3.1 g/kg FFM when `bodyFatPct` is known, else 2.0–2.6 g/kg BW if lean (men
  ≤ 15 %, women ≤ 25 % by the user's estimate) else 1.8–2.2 (2.4 g/kg BW default in a deficit);
  GENERAL_FITNESS 1.4–2.0; ≥ 65 y floor 1.2; recomposition ≥ 2.2 (up to 3.0). **Energy delta**
  (NUT-05/06): FAT_LOSS `deficit = min(nut05DeficitKcal.hi 500, round(nut05DeficitPctOfTdee 0.20 ×
  TDEE))` and `kcal = max(TDEE − deficit, REE)` — the deficit never exceeds 20 % of TDEE and intake
  never drops below REE — targeting 0.5–1.0 % BW/week (0.5–0.7 when lean or STRENGTH);
  HYPERTROPHY/STRENGTH +10–20 % (default +10 %; ADVANCED +200–300 kcal; rate 0.25–0.5 %/week,
  advanced 0.1–0.25); recomposition (NOVICE, returning from ≥ 3 months layoff, or > 20 % men /
  30 % women body fat with a hypertrophy goal) maintenance to −200 kcal offered as an option. Fat
  NUT-09 default 1.0 g/kg, floor `nut09FatGPerKgFloor` 0.5, 20–35 % of energy; carbs = remaining
  energy / 4, floor `nut08CarbFloorGPerKg` 3 g/kg (NUT-08; in a deficit cut fat to its floor before
  carbs go below 3 g/kg), 5–7 g/kg when cardio ≥ 4 sessions/week, 6–10 for ENDURANCE_SUPPORT.
  **Floor precedence (binding):** if `proteinKcal + fatFloorKcal + carbFloorKcal > kcal`, the kcal
  target is **raised** to that sum, `energyDeltaKcal` is recomputed against TDEE and the targets
  carry `Explanation(NUT-08, "floors bind; deficit reduced to n kcal")` — the engine never emits
  negative carbs and never silently breaks a floor. Fibre 14 g/1,000 kcal with floors 38 g men /
  25 g women (NUT-10); water 3.7 L men / 2.7 L women (NUT-11). A user `UpdateNutritionTargets`
  override replaces kcal/protein and is shown as "user-set"; `MutationValidator` bounds it to
  `[max(REE, 0.75 × TDEE), 1.3 × TDEE]` (§4.15). Worked floor case (`MacroTargetsTest`): woman 55 kg,
  160 cm, 45 y, sedentary (1.2), FAT_LOSS, no body-fat estimate, not lean: REE = 550 + 1,000 − 225 −
  161 = **1,164**; TDEE = **1,397**; deficit = min(500, 279) = **279**; kcal = max(1,118, 1,164) =
  **1,164**; protein 2.4 × 55 = 132 g (528 kcal), fat floor 0.5 × 55 = 28 g (248 kcal), carb floor
  3 × 55 = 165 g (660 kcal) → floors sum **1,436 > 1,164** → kcal = **1,436**, carbs **165 g**, fat
  **28 g**, `energyDeltaKcal = +39`, explanation NUT-08 "floors bind; the deficit comes from activity
  (steps) instead". `MacroTargets.review` (NUT-16/NUT-05): every 14 days, when the observed loss is
  > `nut05MaxRatePctPerWeek` 1.5 %/week the proposal is **+`nut05FastLossStepKcal` 200 kcal** and the
  card shows the NUT-05 sentence "losing faster than 1.5 % per week — consider professional
  supervision" (never a diagnosis, POL-02); otherwise the ±100–200 kcal band step.
- **Weight trend** (NUT-15, `WeightTrend.series(metrics)`): daily morning weigh-ins; 7-day simple
  moving average; a single-day change ≥ 1 % of body weight is flagged `noise`; ≥ 14 days of data
  before any adjustment; expected noise 0.4–0.6 kg day-to-day, 0.35 % weekly cycle, +0.5–1.5 kg after
  sodium/weekend days (NUT-14) — shown as explanation text; `creatineStartedOn` adds a "+1–2 kg
  water expected" marker (SUP-01).
- **Rate loop** (NUT-16): every 14 days compare the 14-day change in MA7 with the goal band; outside
  → `UpdateNutritionTargets` proposal ±100–200 kcal (never > 200 per step) with the trigger values;
  `nextReviewDate` +14 days. Diet breaks (NUT-17): deficit > 8 weeks or Q15 ≥ 4 for two weeks → "1
  week at maintenance" proposal. NUT-18: weight-class cuts are out of scope (refer).
- Peri-workout, distribution, meal frequency and travel heuristics (NUT-02/03/12/14) are coach and
  Knowledge-screen content from `nutrition_rules.json`; nothing is computed from them.

### 5.6 Supplements (`SupplementAdvisor`, SUP-01..12)

`SupplementAdvisor.advise(profile, weightKg, goal, medicalGate, fishServingsPerWeek, vitDRiskFactors,
travelling): List<SupplementAdvice>` (§2.15; `SupplementAdvice` is the §2.14 row: id, name, tier,
doseText, timing, evidence, citation, gated, notes, legalNote, doNotReason — bedtime and session time
come from `profile`) returns tiered items from `supplements.json`, each with dose text computed
where numeric: creatine 3–5 g/day (0.1 g/kg capped
to 3–5 → P1 5 g; loading 20 g/day × 5–7 days optional; `KIDNEY_DISEASE` → "refer" instead of a
dose); caffeine 2–3 mg/kg to start (P1 170–250 mg), up to 3–6 mg/kg, 60 min pre-session; cut-off
= `bedtime − sup02CutoffSmallHours` (9 h) for ≤ 100 mg and `bedtime − sup02CutoffLargeHours` (13 h)
for ≥ 200 mg; day max 400 mg [unverified EFSA value]; protein powder as food (SUP-03); vitamin D only
with ≥ 2 risk factors or a low test (SUP-04, contested); electrolytes only > 1–2 h/heat (SUP-09);
tier B beta-alanine, citrulline, melatonin (travel only; legal note), omega-3 (fish < 2/week),
multivitamin (restriction only); tier C the SUP-11 do-not list with reasons. Every item carries the
SUP-12 caveat block; the priority-order sentence sits above the list. RED-03: any medical flag
without clearance hides dosing numbers behind "discuss with your physician/pharmacist first".

### 5.7 Warm-up generator (`WarmupGenerator`, WU-01..06, MOB-01; WP-03)

| Phase | Rule | Prescription |
|---|---|---|
| Raise | WU-01 | 3–5 min easy cardio on the first available of `stationary_bike` > `rower` > `treadmill` (walk) > `jump_rope` > bodyweight calisthenics ("2 min brisk march + 20 bodyweight squats + 10 push-ups"); 8–10 min (+`wu06ExtraMinutes`) when WU-06 applies (age ≥ 40, `ambientCold`, or `minutesSinceWaking < 60`); **0 min** when the session ≤ 45 min (WU-05) |
| Activate + mobilise | MOB-01, MOB-07 | 3–5 dynamic drills × 2 × 8–10 from `mobility_drills.json` chosen by the session's first two patterns, plus one drill per active limitation joint; 2–3 min under WU-05 |
| Potentiate | WU-02 | ramp sets of the first exercise from its first working load: HEAVY/MAX_STRENGTH zone (≥ 80 % 1RM): 40 % × 8, 55 % × 5, 70 % × 3, 82.5 % × 1–2; MODERATE (8–15): 50 % × 8, 70 % × 5; LIGHT / machine compound: 60 % × 8; isolation: 1 × 8–10 at 55 % (skipped if the muscle is already warm); later exercises of an already-warm pattern: 0–1 set at 65 %; rest 60 s; one extra ramp set under WU-06; loads rounded by `LoadRounder`, never below the empty bar |
| Static stretching | WU-04 | never ≥ 60 s per muscle before lifting; the pre-session drill list contains only dynamic drills |

Drill selection by pattern (from `Drill.patterns`; first match in file order, no duplicates):
SQUAT/LUNGE_SPLIT → `bodyweight_squat` (pause at bottom), `hip_90_90`, `ankle_dorsiflexion_rock`,
`worlds_greatest_stretch`; HINGE → `cat_cow`, `glute_bridge`, `band_lateral_walk`,
`worlds_greatest_stretch`; HORIZONTAL_PUSH/INCLINE_PUSH → `band_pull_apart`, `shoulder_pass_through`,
`wall_slide`; VERTICAL_PUSH → `shoulder_pass_through`, `wall_slide`, `thoracic_rotation`;
HORIZONTAL_PULL/VERTICAL_PULL → `band_pull_apart`, `thoracic_rotation`, `dead_hang` (20 s); full
body → `bodyweight_squat`, `band_pull_apart`, `worlds_greatest_stretch`, `cat_cow`. Ramp sets are
`SetKind.RAMP` and never count as volume (WU-03). Warm-up total is what the time model charges
(10 / 5 / 12 min).

### 5.8 Mobility program (`MobilityPlanner`, MOB-02..07; WP-03)

For each joint the user marked `limitedRange` (or that an active limitation implies): 5–10 min/week
of 30–60 s holds, 2–4 holds per day, ≥ 5 days/week (MOB-02), scheduled as the cool-down's static
holds (MOB-04) and as a rest-day card; weekly minutes per joint are tracked and credited 30 s per
full-ROM working set of the joint's strength-through-ROM exercises (MOB-03, weak; map: hips ←
`goblet_squat`/`back_squat`/`split_squat`/`romanian_deadlift`; t_spine ← `overhead_press_barbell`/
`pull_up`/`front_squat`; shoulders ← `landmine_press`/`face_pull`; ankles ← `standing_calf_raise`/
`seated_calf_raise`/`single_leg_calf_raise`). Foam rolling (MOB-05) is an optional 30–60 s
pre-session line only when the user marks it helpful; cool-down (MOB-06) is an optional 5–10 min
toggle, on by default only after HIIT. Mobility completions are logged as `SetLog` rows with the
mobility exercise id and `seconds`; mobility exercises carry no muscle weights, so volume accounting
ignores them by construction.

---
## 6. Claude coach (core: WP-05; transport, keystore, settings UI: WP-06/WP-08)

The **planner decides; the coach explains, asks and proposes**. The coach never computes a plan,
never writes a log and never edits an override; it reads a compact snapshot, calls read-only tools
immediately on device, and turns requests into validated `PlanMutation` proposals that the app
applies only after the user confirms. Everything else works offline (REQUIREMENTS).

### 6.1 Backends and policy (R1, adopted without change)

| Backend | Policy status (R1 §1.10) | Ships |
|---|---|---|
| Direct Anthropic Messages API with a personal Console API key held in the device keystore | compliant; ≈ $20/month Opus 5 or ≈ $8 Sonnet 5 at 30 turns/day before thinking overhead; realistic band $20–45 / $8–17 (R1 §2.4) | **v1, default** |
| Home bridge: the unmodified `claude -p` on Johannes' PC under his own subscription login, reached over Tailscale with a bridge secret | tolerated grey zone, revocable without notice; Anthropic cut off third-party harnesses on 2026-04-04 | phone-side adapter + protocol (§6.13) in v1, off by default, behind "Show experimental"; PC side is a separate later deliverable |
| Any claude.ai / Claude Code OAuth token on the phone (`/login`, `claude setup-token`, `CLAUDE_CODE_OAUTH_TOKEN`) | **prohibited** (R1 §1.4/§1.5) | **never**: no input field; a pasted key that does not start with `sk-ant-api` is rejected with the copy below |
| Remote Control / MCP escape hatch | compliant but outside GymCoach | documented only |

Settings copy (`res/values/strings_backend_policy.xml`, rendered verbatim on the Backend screen,
reviewed word-for-word against R1 §2.5 in §10):

```
backend_title:        Coach chat backend
backend_api_title:    Anthropic API key (recommended)
backend_api_body:     GymCoach calls Anthropic directly with a key from your Claude Console account. Billed per token at API rates (typically a few cents per message — see Usage). This is the only method Anthropic documents for third-party apps. Your key is stored in this device's hardware-backed keystore and never leaves the device except to api.anthropic.com.
backend_bridge_title: Home bridge (experimental)
backend_bridge_body:  GymCoach talks to a small server on your own PC, which runs Anthropic's unmodified Claude Code signed in with your Claude subscription. Nothing is billed per token; usage counts against your plan's limits. Anthropic currently states that `claude -p` and third-party-app usage "still draw from your subscription's usage limits", but its policy restricts subscription logins to "ordinary use of Claude Code and other native Anthropic applications" and it may enforce that "without prior notice" (it did so in April 2026). Use this only for yourself, keep the PC reachable only via your VPN, and expect it to stop working someday. Requires the PC to be on and online.
backend_never:        GymCoach will never ask for, store, or use a claude.ai login or Claude Code OAuth token on this phone; Anthropic prohibits apps from handling those credentials.
backend_key_rejected: Only Claude Console API keys (sk-ant-api…) can be used. GymCoach cannot use claude.ai or Claude Code login tokens.
backend_cost_line:    ≈ $20–45 per month on Opus 5, ≈ $8–17 on Sonnet 5 at 30 messages/day (Anthropic list prices read on 2026-09-04) — in addition to your Max subscription. Changing model or effort starts a new conversation (cache).
```

### 6.2 Request profile (R1 §2.4, §3.5; §0.3)

| Field | Value | Note |
|---|---|---|
| endpoint | `POST https://api.anthropic.com/v1/messages` | |
| headers | `x-api-key: <key>`, `anthropic-version: 2023-06-01`, `content-type: application/json`, `accept: text/event-stream` | no `anthropic-beta`; no `anthropic-workspace-id` (single-workspace key) |
| `model` | `settings.modelId` (`claude-opus-5` default; `claude-sonnet-5` selectable) | dateless ids are pinned snapshots |
| `max_tokens` | 4096 | thinking counts toward it |
| `stream` | `true` always | |
| `system` | `[{"type":"text","text":<byte-stable prompt>,"cache_control":{"type":"ephemeral"}}]` | ≥ 512 tokens (Opus 5) / 1,024 (Sonnet 5) so it caches |
| `tools` | the 20 contract tools (§6.6), `strict: true` (HOME_BRIDGE: the 16 proposal tools only, §6.13) | rendered before `system` in the cache prefix; omitted (with `tool_choice`) in the compaction and Test requests |
| `tool_choice` | `{"type":"auto"}` | never changed within a conversation |
| `thinking` | `{"type":"adaptive"}` | display omitted (default); never `enabled`/`budget_tokens` (400) |
| `output_config` | `{"effort":"low"}` or `"medium"` | frozen per conversation |
| top-level `cache_control` | `{"type":"ephemeral"}` | automatic breakpoints over the growing history |
| `metadata.user_id` | `settings.coachUserId` (opaque UUID; `SettingsDataStore` generates it with `IdGen.newId()` on the first read that finds it blank, §7.3) | `RequestBuilder` **omits** `metadata` entirely while `coachUserId` is blank — an empty string is never sent |
| never sent | `temperature`, `top_p`, `top_k`, `stop_sequences`, assistant prefill | 400 on Opus 5 / Sonnet 5 |

Full example body (one turn after a proposal was applied):

```json
{
  "model": "claude-opus-5",
  "max_tokens": 4096,
  "stream": true,
  "system": [ { "type": "text", "text": "<system_prompt_v1 + vocabulary, byte-identical every turn>", "cache_control": { "type": "ephemeral" } } ],
  "tools": [ { "name": "lookup_exercises", "description": "…", "strict": true, "input_schema": { "…": "…" } }, "…19 more…" ],
  "tool_choice": { "type": "auto" },
  "thinking": { "type": "adaptive" },
  "output_config": { "effort": "low" },
  "cache_control": { "type": "ephemeral" },
  "metadata": { "user_id": "3f9c…" },
  "messages": [
    { "role": "user", "content": [ { "type": "text", "text": "<context schema=\"1\">{…snapshot…}</context>" }, { "type": "text", "text": "Can we add a bike day on Wednesday?" } ] },
    { "role": "assistant", "content": [ { "type": "thinking", "thinking": "", "signature": "Eos…" }, { "type": "text", "text": "…" }, { "type": "tool_use", "id": "toolu_01A", "name": "propose_add_cardio", "input": { "contract_version": 1, "weekday": 3, "minutes": 30, "kind": "zone2", "exercise_id": "stationary_bike", "rationale": "CAR-07 …" } } ] },
    { "role": "user", "content": [ { "type": "tool_result", "tool_use_id": "toolu_01A", "content": "{\"status\":\"applied\",\"diff\":{\"sessions_changed\":[\"cardio Wed\"],\"weekly_sets_before\":{},\"weekly_sets_after\":{},\"warnings\":[]}}" }, { "type": "text", "text": "<delta schema=\"1\">{\"applied\":[\"toolu_01A\"]}</delta>" }, { "type": "text", "text": "Thanks. What HR should I hold?" } ] }
  ]
}
```

### 6.3 Wire layer (`coach.wire`, all in `:core`, all unit-tested)

```kotlin
// core/src/main/kotlin/com/gymcoach/core/coach/wire/WireTypes.kt
package com.gymcoach.core.coach.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Serializable data class CacheControl(val type: String = "ephemeral")
@Serializable data class SystemBlock(val type: String = "text", val text: String, @SerialName("cache_control") val cacheControl: CacheControl? = null)
@Serializable data class ToolDefinition(val name: String, val description: String, val strict: Boolean = true, @SerialName("input_schema") val inputSchema: JsonObject)
@Serializable data class MessageParam(val role: String, val content: JsonArray)     // content echoed verbatim
@Serializable
data class MessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val stream: Boolean = true,
    val system: List<SystemBlock>,
    val tools: List<ToolDefinition>? = null,                 // null = omitted (compaction and Test requests; explicitNulls = false)
    @SerialName("tool_choice") val toolChoice: JsonObject? = null,   // null = omitted; never sent without `tools` (400)
    val thinking: JsonObject,
    @SerialName("output_config") val outputConfig: JsonObject,
    @SerialName("cache_control") val cacheControl: CacheControl? = CacheControl(),   // null for the non-streamed one-off requests
    val metadata: JsonObject? = null,                        // omitted while settings.coachUserId is blank
    val messages: List<MessageParam>,
)

/** Accumulated assistant message (R1 §3.7 shape), content kept verbatim as JsonArray. */
data class AccumulatedMessage(
    val id: String?,
    val model: String?,
    val content: JsonArray,
    val stopReason: String?,
    val stopDetails: JsonObject?,
    val usage: com.gymcoach.core.model.TokenUsage,
    val unknownEventTypes: Int,
)
```

`RequestBuilder.build(settings, systemText, tools, history: List<ChatMessage>, newUserContent:
JsonArray): JsonObject` enforces: `tool_result` blocks come first in the new user content and one
per **every** `tool_use` block of the last assistant message (read-only and proposal alike, §6.7);
every assistant `content_json` is echoed unchanged (thinking / signature / `redacted_thinking`
included); the request never ends with an assistant message; consecutive user messages are
allowed; no forbidden fields; `metadata` omitted while `coachUserId` is blank; in HOME_BRIDGE mode
the four read-only tools are omitted (§6.13). Two more builders (§2.15):
`RequestBuilder.buildCompaction(settings, compactionPrompt, transcriptText)` — `system` = the
compaction prompt block (no `cache_control`), **no `tools`, no `tool_choice`**, `stream: false`,
`max_tokens: 1024`, `thinking: {"type":"adaptive"}`, `output_config: {"effort":"low"}`, one user
message holding the transcript text; `RequestBuilder.buildTest(settings)` — `max_tokens: 32`,
`stream: false`, `thinking: {"type":"disabled"}` (accepted at effort ≤ high), no tools, one user
message `"ping"`. `ClaudeBackend.test()` sends `buildTest` through `complete` and inspects only
the HTTP status / `error.type` (`TestResult.error`) plus the `request-id` header. Covered by
`RequestBuilderTest` (incl. "compaction body has no tools/tool_choice keys", "test body is
non-streamed").

`SseParser.feed(chunk: String): List<SseEvent>` is incremental (holds a partial line across
chunks, so it accepts **arbitrary chunk boundaries**, even inside a line or a multi-byte code
point already decoded to text), handles `\n` and `\r\n`, joins multi-line `data:` with `\n`, ignores
`:` comments and `id:`/`retry:`, dispatches on a blank line; `flush(): List<SseEvent>` at end of stream returns any event still pending.
`SseEvent(name: String?, data: String)`. **`SseBodyReader` (`:app`) reads raw chunks** —
`source.read(buffer, 8192)` decoded with a `CharsetDecoder` (UTF-8) that carries partial code points
across reads — and calls `parser.feed(chunkText)` for each; at end of body it calls
`parser.flush()`. It never uses `readUtf8Line()` (that strips the terminators the parser
dispatches on). The bridge backend feeds the same parser. `SseParserFuzzTest` splits every fixture
at every byte offset and asserts identical event lists.

`StreamAccumulator.accept(event: JsonObject): List<TurnEvent>` emits `TextDelta`, `ToolUseStarted` and a terminal
`Failed`; it cannot build `ToolUseComplete` (that needs the Proposal or the read-only result), so completed tool_use
blocks are handed out through the additive `takeCompletedToolUses(): List<ToolUseBlock>` / `toolUses()`
(`ToolUseBlock(index, id, name, input, inputError)` in `WireTypes.kt`; `inputError` set for unparseable JSON) and
`CoachTurnService` turns each into `ToolUseComplete`. It dispatches on
`event["type"].jsonPrimitive.content` (never on the SSE event name):

| type | action |
|---|---|
| `message_start` | record `message.id`, `model`, initial `usage` |
| `content_block_start` | open block at `index`; keep the raw `content_block` object |
| `content_block_delta` → `text_delta` | append to the text block; emit `TextDelta` |
| `content_block_delta` → `input_json_delta` | append `partial_json` to the tool block's buffer |
| `content_block_delta` → `thinking_delta` / `signature_delta` | append to the thinking block / set `signature` |
| `content_block_stop` | close block; for `tool_use` parse the buffer (empty buffer → `{}`) into `input`; emit `ToolUseComplete` |
| `message_delta` | merge `stop_reason`, `stop_details`, cumulative `usage` (incl. `output_tokens_details.thinking_tokens`) |
| `message_stop` | finish; build the verbatim content array |
| `ping` | ignore |
| `error` | terminal → `BackendError.Overloaded(midStream = true)` for `overloaded_error`, else `StreamError(type, message)` |
| anything else | count in `unknownEventTypes` and ignore (new event types are promised) |

### 6.4 System prompt — byte-stable, versioned, cached

`SystemPrompt.assemble(kb: KnowledgeBase): String` = the text of
`core/src/main/resources/gymcoach/coach/system_prompt_v1.txt` **plus** the generated vocabulary
appendix (156 `id — name` lines from the knowledge base in knowledge order, then the 29 patterns
and 21 muscles). `PROMPT_VERSION = "v1"`; `SystemPromptTest` asserts that the SHA-256 of the
assembled prompt equals the content of `core/src/test/resources/coach/system_prompt_v1.sha256`
(updated deliberately when the text changes), that the prompt contains no date/time/name/number
that varies per user, that every rule id listed in the digest exists in `rules.json`, and that the
estimated length is ≥ 512 tokens. The hash is stored on every conversation; a changed hash (app
update) forces a new conversation.

Full text of `system_prompt_v1.txt` (implementers copy verbatim; blank-line structure matters for
the hash test):

```
You are the coach inside GymCoach, an offline-first Android training app used by one person: a travelling service technician and mechanical engineer who trains in many different gyms and at home. The app is the system of record. It owns the plan, the logs, the gyms and the profile. You advise, explain, ask, and PROPOSE structured changes through tools; the app validates every proposal and applies it only after the user confirms.

## Hard rules

1. Never state, restate, round or "correct" a number the user logged. Quote logged numbers exactly as they appear in the context. Never ask the app to change a logged number.
2. You cannot change anything yourself. Every change goes through a propose_* tool. Assume nothing was applied until a tool_result says "applied". Never describe a change in prose as if it had happened.
3. No diagnosis. Health patterns are "a reason to see a professional", never a diagnosis. If the user reports any of these, reply ONLY with the stop-and-refer text and no training advice: chest pain or pressure (also radiating to arm, jaw or neck), unusual shortness of breath at low effort, dizziness or fainting, palpitations with symptoms — for these four say "stop now and call emergency services" — new numbness, tingling or weakness in a limb, loss of bladder or bowel control or saddle numbness with back pain, a joint that is hot, swollen, locking or giving way, a sudden pop with loss of function, a severe headache that started during lifting, calf swelling or pain with warmth, dark or cola-coloured urine or extreme muscle pain and swelling after training, fever or systemic illness. Pain above 3/10 that persists more than 7 to 10 days, worsens across sessions or wakes the user at night: recommend a physiotherapist or physician (RED-02). Pain up to 3/10 that is not worse the next morning is acceptable; otherwise regress range, load or pattern (INJ-00). Never say "push through".
4. Whenever you name a supplement, add this sentence: "Not medical advice; check with a physician or pharmacist if you take medication or have a heart, kidney, liver, thyroid or psychiatric condition; prefer third-party-tested products; legal status differs by country." If context.medical_gate is true, give no doses at all.
5. Cite the rule id (for example VOL-04, PROG-01) for every numeric recommendation. Rules tagged weak are app defaults the user may adjust, say so when relevant. If something is not in the digest below, say "not in my rules" and use get_rule or lookup_exercises rather than inventing.
6. Use exercise ids only from the vocabulary at the end of this prompt or from lookup_exercises results. Never invent an id.
7. Keep answers short: at most 120 words unless the user asks for detail. Numbers with units. Metric unless context.units is "imperial". No emojis, no motivational filler. One clarifying question at a time.

## How the app talks to you

The first user message of a conversation contains a <context schema="1"> JSON block: today's date, units, profile (age, sex, height, weight trend, training age, goal, days and minutes, limitations, medical_gate, priority muscles), the gym of the day with its equipment and parameters, available_exercises (ids available in that gym), the mesocycle (split, week n of N, mode, deload week), this week's sessions with their status, the weekly volume ledger (targets, achieved, planned remaining, under-target report), today's realized session with its modifiers, readiness (today and last 7 days), progression flags (plateaus, regressions), recent sessions (best sets), open check-in questions, nutrition targets and weight trend, and pending or applied proposals. Later user messages carry a small <delta schema="1"> block with only what changed since the last message (sessions logged, proposals decided, profile or gym or plan changes, new readiness, a new date). A conversation started after compaction begins with a <summary> block of standing decisions from the previous conversation. Today's date is in the context, never assume it.

The volume ledger counts sets FRACTIONALLY: the prime mover of an exercise gets 1.0 set, a secondary muscle 0.5, a minor one 0.25 (DEF-05). The Progress screen also shows DIRECT counts (1.0-weight sets only); Renaissance Periodization landmarks apply to direct counts only. Never compare fractional totals with RP landmarks.

Every automatic change the app made carries a rule id and a reason ("modifier"). When the user asks "why", read the modifiers in the context first.

## Tools

Read-only tools (lookup_exercises, get_exercise_history, get_rule, get_gym_equipment) run immediately on the phone and return data in the same turn; use them freely.

Proposal tools (propose_*) create a card the user must confirm. One tool call per distinct change; several calls in one turn are fine. Put the reason in the rationale field, citing rule ids. The app answers each proposal in the NEXT user message with a tool_result: "applied" with a diff of what actually changed, "rejected by user" (do not retry the same change unless the user asks), "pending" (the user has not decided; do not re-propose), or an error with the validation reason (you may correct the input and propose again in the same turn). Sets stay within VOL-06 (12 sets per muscle per session) and SESS-02 (25 sets per session; novice 20; advanced 32). Swaps keep the movement pattern unless the user asks otherwise. If a tool input is rejected as invalid, fix it; do not ask the user to fix it for you.

If the user asks for something the tools cannot express, say what the app can do instead.

## Knowledge digest (rule id [confidence] statement — source)

DEF-01 [moderate] Training age: novice < 6 months consistent training or a layoff of 12+ months not yet retrained 6 months; intermediate 6-24 months; advanced > 24 months — ACSM 2009.
DEF-02 [strong] RPE 10/9/8/7/6 = 0/1/2/3/4 RIR — Zourdos 2016.
DEF-03 [moderate] Heavy ≤ 8 RM (≥ 80 % 1RM); moderate 9-15 RM; light 16-30 RM — Lopez 2021.
DEF-04 [moderate] A working set is ≤ 4 RIR; warm-up and ramp sets never count — Robinson 2024.
DEF-05 [moderate] Fractional set counting 1.0 / 0.5 / 0.25 — Pelland 2025.
VOL-01 [strong] Minimum effective dose: ≥ 4 working sets per muscle per week at 6-15 RM — Iversen 2021.
VOL-02 [moderate] Strength minimum: 1 set x 6-12 at 70-85 % 1RM per lift, 2-3 sessions per week — Androulakis-Korakakis 2020.
VOL-03 [strong] More weekly sets, more growth and strength with diminishing returns (stronger for strength) — Pelland 2025; Schoenfeld 2017.
VOL-04 [weak] Hypertrophy sets per muscle per week: novice 8-12, intermediate 12-18, advanced 15-25; start a mesocycle at the lower bound. The band was measured on large muscles; the app's tier convention for small muscles is weak — Schoenfeld 2017; Pelland 2025.
VOL-05 [moderate] Strength: 4-10 sets per lift per week over ≥ 2 sessions — Pelland 2025.
VOL-06 [weak, unverified] 6-10 sets per muscle per session by default; hard cap 12, then add a session — secondary summaries of Pelland/Remmert.
VOL-08 [weak] +1 set per muscle per week (max +2) when RIR is at target and soreness < 3/5; stop at the cap; then deload — Enes 2024.
VOL-09 [weak] Fat loss: keep 60-100 % of accustomed sets, never below VOL-01; trim sets before load.
FRQ-01 [strong] Train each muscle ≥ 2x per week when weekly sets ≥ 10; frequency itself adds nothing at equal volume — Schoenfeld 2016/2019; Pelland 2025.
FRQ-02 [moderate] Practise each main lift ≥ 2x per week for strength — Pelland 2025; Grgic 2018.
LOAD-01 [strong] Hypertrophy is load-agnostic from ~30 to ~85 % 1RM (5-30 reps) near failure — Schoenfeld 2017; Lopez 2021; Currier 2023.
LOAD-02 [strong] Maximal strength needs ≥ 80 % 1RM — Currier 2023.
LOAD-03 [weak] Rep ranges by goal: strength barbell 3-6 top sets / 4-8 back-off, machines 6-10, isolation 10-15; hypertrophy barbell 5-10, machine/dumbbell 8-15, isolation 10-20, bodyweight 8-20; general fitness and fat loss barbell 6-10, machine 8-15, isolation 12-20. Barbell compounds stay ≤ 10-12 reps because RIR estimates are more accurate there — Halperin 2022.
LOAD-04 [moderate] Zones: 85-95 % x 1-5 max strength; 78-85 % x 5-8 heavy; 65-78 % x 8-15 moderate; 50-65 % x 15-30 light — Lopez 2021; ACSM 2009.
RIR-01 [strong] Hypertrophy: 0-3 RIR; benefit tapers past 2 RIR; failure adds only a trivial amount (ES ≈ 0.19) — Robinson 2024; Refalo 2023/2024.
RIR-02 [strong] Strength: 2-5 RIR is sufficient — Robinson 2024.
RIR-03 [moderate] Class RIR targets: lower barbell 2-3 (never 0 without safeties), upper barbell 1-3, dumbbell/machine/cable 1-2, isolation 0-2, bodyweight 0-2.
RIR-04 [weak] Week 1 target +1; middle weeks target; last week target −1 (floor 0 isolation, 1 compounds); deload 4-5.
RIR-05 [moderate] People under-predict RIR by about 1 rep; above 12 reps the app treats a reported RIR as +1; an AMRAP calibration set on a safe machine or isolation exercise every 3 weeks — Halperin 2022.
REST-01 [moderate] Hypertrophy: 90-120 s default; ≥ 60-90 s minimum — Singer 2024; Schoenfeld 2016.
REST-02 [strong] Heavy compounds 2-5 min (3-5 min for ≤ 6 RM) — ACSM 2009; Grgic 2018.
REST-03 [moderate] Isolation and machines 60-90 s; antagonist supersets allowed — Iversen 2021.
ORD-01 [strong] The priority exercise goes first; strength gains are largest in the first exercise — Nunes 2021.
ORD-03 [moderate] Same-session cardio after lifting — Eddens 2018.
MIX-01 [strong] Isolation adds nothing for muscles the compounds already train; use it for under-covered muscles — Gentil 2015; Rosa 2022.
MIX-02 [moderate] Each week: knee-dominant, hinge, horizontal push, vertical or incline push, horizontal pull, vertical pull — Iversen 2021.
UNI-01 [strong] Unilateral and bilateral training grow muscle equally; strength is mode-specific — Kassiano 2025.
TEMPO-01 [strong] 0.5-8 s reps are equal; > 10 s is inferior; cue 1-3 s eccentric — Schoenfeld 2015.
ROM-01 [strong] Full ROM by default — Pallarés 2021. ROM-02 [moderate] Lengthened partials are an acceptable substitute; shortened partials are inferior — Wolf 2023.
SESS-01 [weak] Minutes per working set incl. rest: heavy lower 4.0, heavy upper 3.5, compound 2.5, isolation 1.75; 30/45/60/90 min ≈ 8-10/14-18/20-26/30-36 sets.
SESS-02 [weak] Session cap 25 sets (novice 20, advanced 32).
SPLIT-01 [weak] 1-2 days full body; 3 days full body x3 or upper/lower/full; 4 days upper/lower; 5 days U/L/push/pull/legs; 6 days PPL x2 or U/L x3.
PROG-01 [strong] Double progression: all sets at the top of the range at ≤ target RIR → load up next session and reps back to the bottom; reps below the range on ≥ 2 sets → hold — ACSM 2009.
PROG-02 [moderate] Increments: lower barbell +5 kg (+2.5 above 100 kg, novice or female); upper barbell +2.5 kg (1.0 with microplates); dumbbell next pair, machine one plate — if the jump is > 10 % the rep ceiling rises by 2 first; bodyweight reps first then +2.5 kg — ACSM 2009.
PROG-03 [moderate] Cadence: novice every session; intermediate weekly; advanced per mesocycle with heavy and back-off days — ACSM 2009.
PROG-04 [moderate] RIR ≥ target + 2 on all sets → load up next session; first set RIR ≤ target − 2 → offer −5 % for the remaining sets (the user taps; logs are never changed) — Graham & Cleather 2021.
PROG-05 [weak] Novices: first two weeks at 3-4 RIR.
PLAT-01 [weak] Plateau: no e1RM or reps-at-load gain over 3 fair sessions. PLAT-02 [weak] Ladder: audit sleep/protein → rep zone one lighter → variation swap → +1-2 sets → deload. PLAT-03 [weak] e1RM −5 % on two sessions without cause → reactive deload — Bell 2025.
DLD-01 [moderate] Deload every 4-8 weeks (practice 5.6 ± 2.3 weeks); novice reactive only; intermediate 6; advanced 5 — Bell 2025; Rogerson 2024. A deload manages fatigue; it is not a growth stimulus (Coleman 2024).
DLD-02 [moderate] Deload week: sets −50 %, compound loads −15 %, RIR 4-5, frequency unchanged — Rogerson 2024.
DLD-03 [weak] Reactive deload when ≥ 2 of: e1RM/reps −5 % twice, Red readiness ≥ 3 of 7 days, soreness ≥ 4 > 72 h, sleep quality ≤ 2 x 4 nights, motivation ≤ 2 x 4 days, joint ache ≥ 3/10 — present ≥ 5 days. Always a proposal.
DLD-04 [weak] Week after a deload: 95 % loads, lower-bound sets, RIR +1.
E1RM-01 [strong] e1RM = mean of Epley w(1 + r/30) and Brzycki w x 36/(37 − r) with r = reps + RIR capped at 10 — LeSuer 1997. E1RM-02 [strong] Valid ≤ 10 reps; above that display only. E1RM-04 [moderate] Deadlift is under-estimated; machines track load x reps per gym; 14-day rolling best.
RDY-01 [moderate] Readiness 0-100 from sleep hours and quality, soreness, stress, energy, motivation, optional resting HR — Hooper 1995; Buchheit 2014. RDY-02 [moderate] Green ≥ 70 as planned; Amber 45-69: +1 RIR, −1 set after the first two exercises, no load increases; Red < 45: −40 % sets, +2 RIR, heavy barbell → moderate machine/dumbbell, or 25-30 min zone 2 + mobility; sleep < 6 h → at least Amber — Craven 2022. RDY-03 [weak] Resting HR +5 bpm x 2 days → Amber; +10 or illness → Red. RDY-05 [moderate] Stress ≥ 4/5 for two weeks → sets −20 %, RIR +1 — Stults-Kolehmainen 2014.
WU-01 [strong] RAMP warm-up 8-12 min: raise 3-5, mobilise 3-5, ramp sets — Fradkin 2010. WU-02 [moderate] Ramp: heavy 40 % x 8, 55 % x 5, 70 % x 3, 80-85 % x 1-2; moderate 50 % x 8, 70 % x 5; machine 60 % x 8. WU-04 [strong] No static stretch ≥ 60 s before lifting — Behm 2016. WU-05 [moderate] ≤ 45 min sessions: specific ramp sets only.
MOB-02 [strong] Chronic ROM: ≥ 5 min per joint per week over ≥ 5 days, 30-60 s holds — Thomas 2018; Ingram 2025. MOB-03 [strong] Full-ROM strength work counts as mobility — Afonso 2021.
CAR-01 [strong] 150-300 min moderate or 75-150 vigorous per week plus ≥ 2 strength days — WHO 2020. CAR-02 [moderate] Zone 2 = talk test + 60-70 % HRmax or 40-59 % HRR + RPE 11-13 (two of three). CAR-03 [strong] HRmax = 208 − 0.7 x age; Karvonen for HRR — Tanaka 2001. CAR-05 [strong] HIIT: 4 x 4 min at 90-95 % HRmax with 3 min easy; 10 x 1 min at 90 %; SIT 4-6 x 30 s (advanced) — Helgerud 2007; requires 4 weeks of zone-2 base. CAR-06 [strong/moderate] Cardio does not blunt hypertrophy or strength; separate hard cardio from lifting by ≥ 6 h (24 better), lift first, no running within 24 h before a heavy leg day, cycling/rowing next to leg days; hypertrophy/strength: ≤ 3 sessions and ≤ 150 min beyond the floor — Schumann 2022; Robineau 2016; Wilson 2012. CAR-07 [strong] Fitness 2 x 30-45 min zone 2 + 1 HIIT; hypertrophy 2 x 20-30 zone 2; fat loss 150-250 → > 250 min per week, HIIT not superior for weight — Donnelly 2009; Jakicic 2024.
NUT-01 [strong] Protein 1.6-2.2 g/kg; deficit 2.3-3.1 g/kg fat-free mass; ≥ 65 y never below 1.2; recomposition ≥ 2.2 — Morton 2018; Jäger 2017; Helms 2014. NUT-02 [moderate] 0.4 g/kg per meal over ≥ 4 meals; optional 30-40 g casein before sleep. NUT-04 [strong/weak] Mifflin-St Jeor REE; activity factors are a starting guess; the 14-day weight trend corrects the estimate (±150 kcal per step). NUT-05 [strong] Fat loss 300-500 kcal deficit, 0.5-1.0 % body weight per week (0.5-0.7 when lean) — Helms 2014; Garthe 2011. NUT-06 [moderate] Lean gain +10-20 %, 0.25-0.5 % per week (advanced 0.1-0.25) — Iraki 2019. NUT-08 [moderate] Carbs ≥ 3 g/kg — Thomas 2016. NUT-09 [moderate] Fat 0.5-1.5 g/kg, floor 0.5. NUT-13 [moderate] Alcohol ≤ 1-2 drinks on training days, none within 4 h after, ≤ 7 per week; ≥ 3 drinks → next day at least Amber — Parr 2014. NUT-14 [weak] Travel: protein anchor 30-40 g per meal, plate rule (half vegetables, quarter protein, quarter starch), petrol-station kit (skyr, jerky, nuts, fruit, protein bar), expect +0.5-1.5 kg after sodium or weekend days and do not react. NUT-15 [moderate] Daily morning weigh-in, 7-day average, single-day change ≥ 1 % is noise, ≥ 14 days before adjusting — Kutáč 2015. NUT-16 [moderate] Every 14 days ±100-200 kcal when outside the goal band. NUT-17 [weak] Deficits > 8 weeks or hunger ≥ 4/5 for two weeks → one week at maintenance.
SUP-01 [strong] Creatine monohydrate 3-5 g per day, any time; optional 20 g/day for 5-7 days; +1-2 kg water at first; not with kidney disease — Kreider 2017. SUP-02 [strong] Caffeine 3-6 mg/kg (start 2-3) 60 min before; no ≥ 200 mg dose within 13 h of bedtime, no ≤ 100 mg within 9 h; ≤ 400 mg per day — Guest 2021; Gardiner 2023. SUP-03 [strong] Protein powder is food, 20-40 g servings. SUP-11 [strong-moderate] Do not recommend: BCAA, HMB (trained), glutamine, testosterone boosters, stimulant fat burners, SARMs/prohormones, arginine, CLA, D-ribose, chromium, detox products, collagen for muscle, high-dose vitamin D without a test. SUP-12 [policy] Caveat sentence with every supplement; priority: protein, calories, sleep, consistency, then creatine and caffeine — Maughan 2018.
SLP-01 [strong] 7-9 h per night (≥ 65 y 7-8) — Hirshkowitz 2015. SLP-03 [moderate] 7-day average < 7 h → sets −10-20 % and RIR +1 on heavy compounds; last night < 6 h → at least Amber, prefer morning training; two nights < 6 h → no max-strength sets — Craven 2022. STP-01 [strong] 7,000-8,000 steps per day (< 60 y), 6,000-7,000 (≥ 60) — Ding 2025. STR-02 [weak] High-stress weeks: zone-2 walks, Amber modifiers, no new HIIT.
AGE-02 [moderate] ≥ 60 y: 2-3 sessions per week, 2-3 sets per exercise, 7-9 reps at 70-79 % 1RM, power-style sets 1-2x per week, protein ≥ 1.2 g/kg — Borde 2015; Fragala 2019.
LAY-01 [moderate/weak] After a break: 5-13 days 95 % loads, −1 set, RIR +1; 2-4 weeks 85-90 %, −30 % sets, RIR +2; 4-8 weeks 75-80 %, −50 % sets; > 8 weeks 60-70 % or novice restart; ≥ 65 y one row more conservative; losses are regained in about 5 weeks — Halonen 2024; Bosquet 2013. LAY-02 [weak] Illness: no training until 24 h symptom-free without medication; then the 2-4-week row for a week; zone 2 only for 3 days. LAY-03 [moderate] Short breaks ≤ 2 weeks do not compromise long-term results — Halonen 2024.
TRV-01 [moderate] Travel week: 2 full-body sessions of 25-40 min, 4-6 exercises, 2-3 sets, 6-15 reps (15-30 to 0-1 RIR with light loads), 0-2 RIR — Iversen 2021. TRV-03 [weak, unverified] About one third of accustomed volume maintains strength and size for weeks if effort is kept — Bickel 2011. TRV-04 [weak] Gym presets substitute at the pattern level; progression state is per exercise, machines per gym.
TIME-01 [moderate] When time shrinks: trim warm-up → drop isolation for muscles already at VOL-01 from compounds → antagonist supersets → 60 s rest on isolation/machines → one exercise per pattern → 2 sets per exercise → move a pattern to another day; keep every muscle ≥ VOL-01 — Iversen 2021. TIME-03 [moderate] Drop sets and rest-pause only on machines, cables and isolation.
INJ-00 [weak] Pain ≤ 3/10 and not worse next morning is acceptable; otherwise regress. INJ-01 [moderate/weak] Knee: 0-50° flexion is low-force; box or partial squats, hinges, limited-depth press, step-ups, reverse lunges, leg curls, isometrics; cycle instead of run — Escamilla 2001. INJ-02 [moderate/weak] Shoulder: neutral-grip dumbbell or landmine press, floor or pin press; avoid behind-the-neck, deep dips, wide-grip bench, upright rows; add external rotations and face pulls — Kolber 2010. INJ-03 [moderate/weak] Low back: keep training; neutral-spine hinges with lighter load (trap bar, block pull), hip thrust, belt squat or leg press, split squats, chest-supported rows; no loaded flexion during flare-ups; bird dog, side plank, dead bug — Owen 2020. INJ-04 [weak] Elbow/wrist: halve curl and pressdown volume, neutral grips, slow eccentrics, ≤ 3/10 pain.
RED-03 [strong] Known heart, kidney or liver disease, pregnancy, diabetes on medication, uncontrolled hypertension or a doctor's restriction → clearance before HIIT, maximal lifts and supplements — Riebe 2015.

## Style

Direct, numbers first, short sentences. German terms in parentheses when they help (the user is German; the UI is English). When unsure whether a change is safe, propose the conservative option and say why. When the context shows an under-target report, treat it as normal, not as a failure. When a request needs data not in the context, use a read-only tool rather than guessing.

## Exercise vocabulary (id — name)
```

(The vocabulary appendix, then `## Patterns` and `## Muscles` lists, are appended by
`SystemPrompt.assemble` from the knowledge base.)

### 6.5 Context assembly (`CoachContextBuilder`) — append-only, snapshot then deltas

- **Snapshot** (first user message of a conversation; target 2,000–2,500 tokens by `TokenEstimator`
  ≈ 4 chars/token; keys in the fixed order below; numbers rounded to 1 decimal). `CoachTurnService`
  builds the `ContextInputs` (§2.15) from the `CoachTurnInputs` that `CoachViewModel` passes into
  `send(userText, inputs)` (§2.15, §6.7, §8.17) plus its own repositories — it holds no planner and
  never calls `PlanCoordinator`: `today = inputs.today`; `units = settings.get().unitSystem`;
  `profile = inputs.planInputs.profile`; `trainingAge = inputs.trainingAge`; `weightMa7Kg =
  inputs.weightMa7Kg`; `gym = gyms.get(inputs.today.gymId)?.takeUnless { it.archived } ?:
  gyms.getAll().firstOrNull { it.isDefault } ?: gyms.getAll().firstOrNull()` (an unknown or
  archived `gymId` falls back to the default gym, then to any active gym; the fallback is stated in
  the snapshot as `"gym_today": {..., "fallback": "default"}`; when no gym exists at all the turn
  fails in §6.7 step 0 with `Failed(BackendError.InvalidRequest(0, "no gym configured"), draftKept
  = true)` and nothing is sent — `ContextInputs.gym` therefore stays non-null); `availableExerciseIds =
  EquipmentResolver(kb).availableExerciseIds(SessionEquipment(gym, today.extraItems,
  today.removedEquipmentIds.toSet(), today.spotter))` in knowledge order; `mesocycle =
  inputs.mesocycle` and `weekIndex` = the index of the `MesoWeek` containing `today.date`;
  `weekSessions` = `inputs.planInputs.recentSessions` inside that week; `ledger = inputs.ledger`;
  `todaySession = inputs.todaySession` — the open session's frozen plan if one is IN_PROGRESS, else
  `container.planCoordinator.realizeToday(today)`, else `null` → `"today_session": null` (rest day
  or no plan); `readiness7d` = `planInputs.readinessHistory` of the last 7 days; `states =
  planInputs.progressionStates`; `recentSessions`/`recentSets` = `planInputs.recentSessions`/
  `recentSets`; `openQuestions = metrics.activeQuestions(today.date)`; `lastCheckin =
  planInputs.weeklyCheckins.maxByOrNull { it.weekStart }`; `nutrition`/`energy`/`trendKgPerWeek` =
  `inputs.*` (null → the `nutrition` key is `null`, never silently omitted); `pendingProposals` =
  `coach.proposals(conv.id)` with status PENDING; `appliedSince` = APPLIED proposals decided after
  the previous conversation's `endedAt`:

```json
<context schema="1">{
  "today": "2026-09-05", "units": "metric",
  "profile": {"sex":"male","age":38,"height_cm":180,"weight_ma7_kg":84.4,"training_age":"intermediate","goal":"hypertrophy","days":4,"minutes":60,
              "limitations":[{"region":"shoulder","side":"right","pain":2,"since":"2026-08-20"}],"medical_gate":false,"priority_muscles":[]},
  "gym_today": {"id":"g-hotel-1","name":"Hotel Ulm","origin":"hotel_gym","session_minutes":45,
                "equipment":["dumbbells(max 20, step 2)","adjustable_bench","mat","treadmill","stationary_bike","elliptical"]},
  "available_exercises": ["dumbbell_bench_press","goblet_squat","…"],
  "mesocycle": {"week":3,"of":6,"mode":"normal","rir_offset":0,"deload_week":7,"split":"upper_lower_4","gym":"Home gym"},
  "week": {"sessions":[{"day":"Mon","template":"UPPER_A","status":"completed","sets":23},{"day":"Tue","template":"LOWER_A","status":"completed","sets":16},
                       {"day":"Thu","template":"UPPER_B","status":"planned","sets":22},{"day":"Fri","template":"LOWER_B","status":"planned_today","sets":19,"gym":"Hotel Ulm"}],
           "cardio":[{"day":"Wed","exercise_id":"stationary_bike","minutes":30,"kind":"zone2"}]},
  "ledger": {"targets":{"chest":14,"quads":14,"…":0},"achieved":{"chest":6,"quads":6},"planned_remaining":{"chest":6,"quads":7},"under_target":["calves -2","abs -2.75"]},
  "today_session": {"template":"LOWER_B","blocks":[{"ex":"dumbbell_romanian_deadlift","sub_for":"conventional_deadlift","sets":3,"reps":"15-30","load":"2x20 kg","rir":1},"…"],
                    "modifiers":["TRV-04: conventional_deadlift → dumbbell_romanian_deadlift (score 0.725)","TIME-01: L2 slider_leg_curl dropped (warm-up already 5 min, WU-05), 39.5 min"]},
  "readiness": {"today":58,"band":"amber","last7":[80,74,58,66,71,69,58]},
  "progression_flags": [{"ex":"barbell_bench_press","status":"plateau","step":1,"working_kg":82.5,"best_e1rm_14d":106.7}],
  "recent": [{"date":"2026-09-01","template":"UPPER_A","top_sets":[{"ex":"barbell_bench_press","kg":82.5,"reps":8,"rir":2}]},"…"],
  "checkin": {"open_questions":["Q1","Q3","Q10"],"last_answers":{"Q1":"6.2","Q6":"3"},"pending_checkin":false},
  "nutrition": {"tdee_est":2609,"target_kcal":2870,"protein_g":151,"weight_ma7":84.4,"trend_kg_per_week":0.35,"next_review":"2026-09-14"},
  "proposals": {"pending":[],"applied_since_last_conversation":["propose_add_cardio Wed 30 min zone2"]}
}</context>
```

  Budget rules: `recent` ≤ 8 sessions, top sets only, last 14 days; `ledger` only TIER_1/TIER_2
  muscles; exercise content never inlined (the coach uses `lookup_exercises`); `available_exercises`
  collapsed to pattern counts when over budget. Truncation order when over budget: `recent`
  (14 → 7 → 3 days) → `available_exercises` (ids → counts) → `readiness.last7` → `week.sessions`
  (keep today + tomorrow); the truncation is stated in the JSON (`"truncated": ["recent"]`).
- **Delta** (every later user message; a text block placed after any `tool_result` blocks and before
  the user's text): `<delta schema="1">{…}</delta>` with only: `today` if changed,
  `sessions_logged` (date, template, top sets), `proposals` (applied / rejected / pending ids),
  `profile_changed`, `gym_changed`, `plan_changed` (fields), `readiness` (new), `checkin_answered`.
  Deltas are small (often < 100 tokens) and are never removed later — the transcript stays
  append-only. **Synthetic tool_result-only user messages** (the automatic read-only follow-ups
  of §6.7) carry **no** delta and no text block — only the `tool_result` blocks.
- **Compacted start**: `<summary>` (≤ 600 tokens, §6.9) followed by a fresh snapshot.

### 6.6 Tool contract v1 (`ToolSchemas`, `ToolContract`) — 20 strict tools

Every schema uses only: `type` (object/string/integer/number/boolean/array), `enum`
(strings/integers), `required` (subset), `additionalProperties: false`, `format: "date"`,
`description`, `items`. **No** `minimum`, `maximum`, `minLength`, `maxLength`, `maxItems`,
`pattern` (§0.3). Every proposal tool carries `contract_version: {"type":"integer","enum":[1]}` and
`rationale: {"type":"string"}`. Numeric bounds are validated on device by `ToolContract.validate`
(→ `MutationValidator`, §4.15) and returned as `is_error` tool results with an instructive message.
Tool names never carry a version; an input with an unknown `contract_version` is answered with
`is_error` "unsupported contract_version". `ProposalSchemasTest` parses each schema, asserts the
keyword whitelist, `additionalProperties: false` everywhere, every `required` key present in
`properties`, and round-trips a sample input through `ToolContract.parse`.

Shared enums used below: `MUSCLE` = the 21 muscle ids; `SCOPE` = `["today","rest_of_mesocycle","permanent"]`;
`GOAL` = `["hypertrophy","strength","general_fitness","fat_loss","endurance_support"]`;
`CARDIO_KIND` = `["zone2","walk","hiit_4x4","hiit_10x1","sit"]`; `REGION` = `["knee","shoulder","low_back","elbow_wrist","hip","ankle","neck","other"]`;
`SIDE` = `["left","right","both"]`; `WEEKDAY` = `[1,2,3,4,5,6,7]`; `DELTA6` = `[-6,-5,-4,-3,-2,-1,0,1,2,3,4,5,6]`;
`PAIN` = `[0,1,2,3,4,5,6,7,8,9,10]`; `WEEKS8` = `[1,2,3,4,5,6,7,8]`.

```json
[
 {"name":"lookup_exercises","description":"Search the exercise library by name, alias (English or German), movement pattern or muscle. Returns at most 10 matches with id, name, pattern, muscles with weights, availability in today's gym, ranked substitutions and safety flags. Executed immediately on the phone.","strict":true,
  "input_schema":{"type":"object","properties":{"query":{"type":"string","description":"Free text; empty string allowed when pattern or muscle is given"},"pattern":{"type":"string","description":"Optional movement pattern id, e.g. horizontal_push"},"muscle":{"type":"string","description":"Optional muscle id"},"available_only":{"type":"boolean"}},"required":["query","available_only"],"additionalProperties":false}},
 {"name":"get_exercise_history","description":"Best set per session for one exercise over the last N days (1-90), the e1RM with its confidence, and the progression state (working load, rep range, target RIR, status). Executed immediately.","strict":true,
  "input_schema":{"type":"object","properties":{"exercise_id":{"type":"string"},"days":{"type":"integer","description":"1 to 90"}},"required":["exercise_id","days"],"additionalProperties":false}},
 {"name":"get_rule","description":"Statement, confidence, citation and the app's current constants for one rule id (e.g. VOL-04). Executed immediately.","strict":true,
  "input_schema":{"type":"object","properties":{"rule_id":{"type":"string"}},"required":["rule_id"],"additionalProperties":false}},
 {"name":"get_gym_equipment","description":"Full equipment list with parameters for a gym (today's gym when gym_id is omitted), plus the count of available exercises. Executed immediately.","strict":true,
  "input_schema":{"type":"object","properties":{"gym_id":{"type":"string"}},"required":[],"additionalProperties":false}},
 {"name":"propose_swap_exercise","description":"Propose replacing one exercise in the plan with another for today, the rest of the mesocycle, or permanently. The app validates equipment, injuries and the rule book and applies only after the user confirms. Use ids from the vocabulary or lookup_exercises. One call per swap.","strict":true,
  "input_schema":{"type":"object","properties":{"contract_version":{"type":"integer","enum":[1]},"from_exercise_id":{"type":"string"},"to_exercise_id":{"type":"string"},"template_id":{"type":"string","description":"Session template id such as UPPER_A; omit for every session containing the exercise"},"scope":{"type":"string","enum":["today","rest_of_mesocycle","permanent"]},"rationale":{"type":"string"}},"required":["contract_version","from_exercise_id","to_exercise_id","scope","rationale"],"additionalProperties":false}},
 {"name":"propose_change_volume","description":"Propose changing the weekly working-set target of one muscle by a delta between -6 and +6 sets. The app checks VOL-01 (floor 4), VOL-04 (evidence band) and VOL-06 (per-session cap).","strict":true,
  "input_schema":{"type":"object","properties":{"contract_version":{"type":"integer","enum":[1]},"muscle":{"type":"string","enum":["chest","front_delts","side_delts","rear_delts","rotator_cuff","lats","upper_back","traps_upper","lower_back","biceps","triceps","forearms","abs","obliques","hip_flexors","quads","hamstrings","glutes","glute_med","adductors","calves"]},"delta_sets_per_week":{"type":"integer","enum":[-6,-5,-4,-3,-2,-1,0,1,2,3,4,5,6]},"scope":{"type":"string","enum":["rest_of_mesocycle","permanent"]},"rationale":{"type":"string"}},"required":["contract_version","muscle","delta_sets_per_week","scope","rationale"],"additionalProperties":false}},
 {"name":"propose_change_rep_range","description":"Propose a new rep range (1-30) for one exercise. Barbell compounds above 12 reps get a warning (RIR accuracy, RIR-05).","strict":true,
  "input_schema":{"type":"object","properties":{"contract_version":{"type":"integer","enum":[1]},"exercise_id":{"type":"string"},"rep_lo":{"type":"integer","description":"1 to 30"},"rep_hi":{"type":"integer","description":"1 to 30, greater than rep_lo"},"scope":{"type":"string","enum":["rest_of_mesocycle","permanent"]},"rationale":{"type":"string"}},"required":["contract_version","exercise_id","rep_lo","rep_hi","scope","rationale"],"additionalProperties":false}},
 {"name":"propose_add_cardio","description":"Propose a cardio slot on a weekday (1 = Monday … 7 = Sunday), 10-90 minutes. HIIT requires 4 weeks of zone-2 base and no medical gate. The app checks CAR-06 placement.","strict":true,
  "input_schema":{"type":"object","properties":{"contract_version":{"type":"integer","enum":[1]},"weekday":{"type":"integer","enum":[1,2,3,4,5,6,7]},"minutes":{"type":"integer","description":"10 to 90"},"kind":{"type":"string","enum":["zone2","walk","hiit_4x4","hiit_10x1","sit"]},"exercise_id":{"type":"string","description":"Cardio exercise id available in the gym, e.g. stationary_bike; omit to let the app choose"},"rationale":{"type":"string"}},"required":["contract_version","weekday","minutes","kind","rationale"],"additionalProperties":false}},
 {"name":"propose_remove_cardio","description":"Propose removing the cardio slot on a weekday.","strict":true,
  "input_schema":{"type":"object","properties":{"contract_version":{"type":"integer","enum":[1]},"weekday":{"type":"integer","enum":[1,2,3,4,5,6,7]},"rationale":{"type":"string"}},"required":["contract_version","weekday","rationale"],"additionalProperties":false}},
 {"name":"propose_change_goal","description":"Propose changing the primary training goal; applies next week unless start_next_week is false.","strict":true,
  "input_schema":{"type":"object","properties":{"contract_version":{"type":"integer","enum":[1]},"goal":{"type":"string","enum":["hypertrophy","strength","general_fitness","fat_loss","endurance_support"]},"start_next_week":{"type":"boolean"},"rationale":{"type":"string"}},"required":["contract_version","goal","start_next_week","rationale"],"additionalProperties":false}},
 {"name":"propose_change_schedule","description":"Propose new training days per week (1-6), session minutes (20-120) and/or preferred weekdays. Omitted fields stay unchanged.","strict":true,
  "input_schema":{"type":"object","properties":{"contract_version":{"type":"integer","enum":[1]},"days_per_week":{"type":"integer","enum":[1,2,3,4,5,6]},"session_minutes":{"type":"integer","description":"20 to 120"},"preferred_weekdays":{"type":"array","items":{"type":"integer","enum":[1,2,3,4,5,6,7]}},"rationale":{"type":"string"}},"required":["contract_version","rationale"],"additionalProperties":false}},
 {"name":"propose_checkin_question","description":"Propose a question the app asks daily, weekly or once on a date (format date), for up to 8 weeks, to find where nutrition, sleep, stress or steps can be improved.","strict":true,
  "input_schema":{"type":"object","properties":{"contract_version":{"type":"integer","enum":[1]},"question":{"type":"string","description":"At most 200 characters"},"answer_type":{"type":"string","enum":["number","scale5","yesno","text"]},"schedule":{"type":"string","enum":["daily","weekly","once"]},"on_date":{"type":"string","format":"date"},"weeks_active":{"type":"integer","enum":[1,2,3,4,5,6,7,8]},"rationale":{"type":"string"}},"required":["contract_version","question","answer_type","schedule","rationale"],"additionalProperties":false}},
 {"name":"propose_exclude_exercise","description":"Propose excluding an exercise from the plan until a date (omit for indefinitely).","strict":true,
  "input_schema":{"type":"object","properties":{"contract_version":{"type":"integer","enum":[1]},"exercise_id":{"type":"string"},"until":{"type":"string","format":"date"},"rationale":{"type":"string"}},"required":["contract_version","exercise_id","rationale"],"additionalProperties":false}},
 {"name":"propose_pin_exercise","description":"Propose pinning an exercise into its pattern slot of one template (or every template when omitted).","strict":true,
  "input_schema":{"type":"object","properties":{"contract_version":{"type":"integer","enum":[1]},"exercise_id":{"type":"string"},"template_id":{"type":"string"},"rationale":{"type":"string"}},"required":["contract_version","exercise_id","rationale"],"additionalProperties":false}},
 {"name":"propose_adjust_working_load","description":"Propose a new working load in kg for an exercise (within +10 % / -20 % of the current one; otherwise the user should log a set instead).","strict":true,
  "input_schema":{"type":"object","properties":{"contract_version":{"type":"integer","enum":[1]},"exercise_id":{"type":"string"},"gym_id":{"type":"string","description":"Only for machine, cable, smith or sled loads (gym-scoped)"},"new_working_load_kg":{"type":"number"},"rationale":{"type":"string"}},"required":["contract_version","exercise_id","new_working_load_kg","rationale"],"additionalProperties":false}},
 {"name":"propose_set_priority_muscles","description":"Propose up to three priority muscles (promoted to tier 1 targets and split choice).","strict":true,
  "input_schema":{"type":"object","properties":{"contract_version":{"type":"integer","enum":[1]},"muscles":{"type":"array","items":{"type":"string","enum":["chest","front_delts","side_delts","rear_delts","rotator_cuff","lats","upper_back","traps_upper","lower_back","biceps","triceps","forearms","abs","obliques","hip_flexors","quads","hamstrings","glutes","glute_med","adductors","calves"]}},"rationale":{"type":"string"}},"required":["contract_version","muscles","rationale"],"additionalProperties":false}},
 {"name":"propose_deload","description":"Propose a deload week (DLD-02: sets -50 %, compound loads -15 %, RIR 4) this week or next. Cite the DLD-03 triggers observed.","strict":true,
  "input_schema":{"type":"object","properties":{"contract_version":{"type":"integer","enum":[1]},"start_this_week":{"type":"boolean"},"rationale":{"type":"string"}},"required":["contract_version","start_this_week","rationale"],"additionalProperties":false}},
 {"name":"propose_set_limitation","description":"Propose logging a limitation (region, side, pain 0-10). Pain 7 or above is rejected with the hand-off text; the app then substitutes flag-sensitive exercises.","strict":true,
  "input_schema":{"type":"object","properties":{"contract_version":{"type":"integer","enum":[1]},"region":{"type":"string","enum":["knee","shoulder","low_back","elbow_wrist","hip","ankle","neck","other"]},"side":{"type":"string","enum":["left","right","both"]},"pain_0_10":{"type":"integer","enum":[0,1,2,3,4,5,6,7,8,9,10]},"limited_range":{"type":"boolean"},"notes":{"type":"string"},"rationale":{"type":"string"}},"required":["contract_version","region","side","pain_0_10","rationale"],"additionalProperties":false}},
 {"name":"propose_travel_days","description":"Propose marking travel days (from/to, format date, at most 21 days) with an optional gym; 3+ days in a week switch that week to travel-maintenance mode.","strict":true,
  "input_schema":{"type":"object","properties":{"contract_version":{"type":"integer","enum":[1]},"from":{"type":"string","format":"date"},"to":{"type":"string","format":"date"},"gym_id":{"type":"string"},"rationale":{"type":"string"}},"required":["contract_version","from","to","rationale"],"additionalProperties":false}},
 {"name":"propose_update_nutrition_targets","description":"Propose a new daily kcal target and/or protein in g per kg (1.0-3.5); kcal must be within 0.6-1.5 x the TDEE estimate; steps above 200 kcal get a warning (NUT-16).","strict":true,
  "input_schema":{"type":"object","properties":{"contract_version":{"type":"integer","enum":[1]},"kcal_target":{"type":"integer"},"protein_g_per_kg":{"type":"number"},"rationale":{"type":"string"}},"required":["contract_version","rationale"],"additionalProperties":false}}
]
```

**Tool → `PlanMutation` mapping (normative; `ToolContract.parse`, §2.15).** Tool inputs cannot be
decoded with `PlanMutation.serializer()` directly: they carry `contract_version`, lack the `type`
discriminator, and some field names/shapes differ. `parse(toolName, input)` therefore: (1) reads
and removes `contract_version` (≠ 1 → `Invalid("unsupported contract_version")`); (2) applies the
renames/wrapping of the table; (3) inserts `"type": <@SerialName>`; (4) decodes with
`CoreJson.stored` (`PlanMutation.serializer()`); any decode failure → `Invalid(errors)`. The four
read-only tools parse to `ReadOnly(kind = toolName, args = input)`.

| Tool | `@SerialName` / subclass | Renames and wrapping |
|---|---|---|
| `propose_swap_exercise` | `swap_exercise` `SwapExercise` | none |
| `propose_change_volume` | `change_volume` `ChangeVolume` | none |
| `propose_change_rep_range` | `change_rep_range` `ChangeRepRange` | none |
| `propose_add_cardio` | `add_cardio` `AddCardio` | none |
| `propose_remove_cardio` | `remove_cardio` `RemoveCardio` | none |
| `propose_change_goal` | `change_goal` `ChangeGoal` | none |
| `propose_change_schedule` | `change_schedule` `ChangeSchedule` | none |
| `propose_checkin_question` | `set_checkin_question` `SetCheckinQuestion` | `weeks_active` absent → 4 (data-class default) |
| `propose_exclude_exercise` | `exclude_exercise` `ExcludeExercise` | none |
| `propose_pin_exercise` | `pin_exercise` `PinExercise` | none |
| `propose_adjust_working_load` | `adjust_working_load` `AdjustWorkingLoad` | `gym_id` → `gym_scope` |
| `propose_set_priority_muscles` | `set_priority_muscles` `SetPriorityMuscles` | none |
| `propose_deload` | `schedule_deload` `ScheduleDeload` | none |
| `propose_set_limitation` | `set_limitation` `SetLimitation` | flat fields wrapped: `limitation = {id: IdGen.newId(), region, side, pain_0_10, since: clock.today(), active: true, limited_range: limited_range ?: false, notes: notes ?: ""}` (`ToolContract` takes `clock: Clock` for this) |
| `propose_travel_days` | `set_travel_days` `SetTravelDays` | none (`from`, `to`, `gym_id`) |
| `propose_update_nutrition_targets` | `update_nutrition_targets` `UpdateNutritionTargets` | none |

`ProposalSchemasTest` round-trips one sample input per tool through this mapping and asserts the
resulting subclass. `ToolContract.validate(mutation, inputs, mesocycle, today, nutrition)`
delegates to `MutationValidator` (§4.15). Read-only tools run through
`ToolExecutor.execute(name, input, today: TodayContext): ToolExecution` (§2.15) with the
`TodayContext` that `CoachTurnService.send` received: `get_gym_equipment` without `gym_id` → the
gym `today.gymId`; `lookup_exercises.available_only` and every `available` flag are resolved
against `SessionEquipment(today)` (extras, removals and the spotter flag included). Results
(compact JSON ≤ 2,000 chars): `lookup_exercises` → `[{id, name, pattern, muscles:{m:w}, available,
substitutions, flags, difficulty}]`; `get_exercise_history` → `{sessions:[{date, best:"80x8@3",
e1rm, confidence}], state:{working_kg, reps, target_rir, status, gym_scope}}`; `get_rule` → `{id,
statement, confidence, citation, constants}`; `get_gym_equipment` → `{gym, items:[{id, params}],
available_exercises}`. Invalid inputs (unknown id, days ∉ 1..90) → `is_error: true` with
`{"status":"invalid","errors":["unknown exercise id 'db_rdl'; use lookup_exercises"]}` so the model
corrects itself without user involvement.

### 6.7 Turn orchestration (`CoachTurnService`)

```kotlin
// core/src/main/kotlin/com/gymcoach/core/coach/TurnEvent.kt (copied verbatim; WP-05 adds `package com.gymcoach.core.coach` and explicit imports of Proposal, TokenUsage, BackendError)
sealed class TurnEvent {
    data class TextDelta(val text: String) : TurnEvent()
    data class ToolUseStarted(val toolUseId: String, val name: String) : TurnEvent()
    /** One per completed tool_use block; readOnlyResult is the ToolExecution JSON for read-only tools. */
    data class ToolUseComplete(val proposal: Proposal?, val readOnlyResult: String?, val error: String?) : TurnEvent()
    /** One per API response in the chain (§6.7 step 4), i.e. one per SpendRecord. */
    data class Usage(val usage: TokenUsage, val costUsd: Double) : TurnEvent()
    /** Emitted exactly ONCE per user turn, after the whole read-only follow-up chain has ended; assistantMessageId = the last assistant row — null ONLY for stopReason "red_flag" (§6.7 step 0: nothing was sent, no assistant row exists). */
    data class Done(val stopReason: String, val stopDetailsJson: String?, val assistantMessageId: String?) : TurnEvent()
    data class Failed(val error: BackendError, val draftKept: Boolean) : TurnEvent()
}
```

```
send(userText, inputs: CoachTurnInputs):                                  // today = inputs.today, todaySession = inputs.todaySession (§2.15)
  0. unless inputs.skipRedFlagScan: RedFlagScanner.scan(userText) → if hit: emit Done("red_flag", stopDetailsJson = {"phrase":…,"emergency":…},
     assistantMessageId = null) — the UI shows the RED-01 card; nothing is sent (§6.12); "send anyway" re-sends with skipRedFlagScan = true
     if !network.isOnline(): emit Failed(BackendError.Offline, draftKept = true); nothing is built or sent
     gym = the §6.5 fallback chain for inputs.today.gymId; if null (no gym at all): emit Failed(BackendError.InvalidRequest(0, "no gym configured"), draftKept = true); nothing is sent
         // status 0 marks a local precondition; HttpErrorMapper never produces it; the UI copy is the §8.20 "no gym" row
     validator = MutationValidator(knowledge, inputs.constants); contract = ToolContract(knowledge, validator, clock)   // per turn, user overrides included
  1. conv = coach.openConversation() ?: newConversation()                 // §6.9 lifecycle; hash/contract/model/effort frozen
  2. content = []
     last = the last ASSISTANT ChatMessage of conv (if any) with its content blocks in order
     for EVERY tool_use block of `last`, in content order:                  // one tool_result per tool_use, always, or the API rejects the request (400)
         read-only tool (name ∈ {lookup_exercises, get_exercise_history, get_rule, get_gym_equipment}):
             content += tool_result(tool_use_id, text = last.toolResultsJson[tool_use_id], is_error = the stored result's `status == "invalid"|"error"`)
         proposal tool (Proposal row with resultSent == false):
             content += tool_result(tool_use_id, text = "{\"status\":\"applied\",\"diff\":…}" | "{\"status\":\"rejected\",\"reason\":…}" |
                                    "{\"status\":\"pending\",\"note\":\"the user has not decided; do not re-propose unless asked\"}" |
                                    "{\"status\":\"expired\"}" | "{\"status\":\"invalid\",\"errors\":[…]}" | "{\"status\":\"failed\",\"message\":…}",
                                    is_error = status == INVALID || status == FAILED)
         (a tool_use whose result was already sent — resultSent == true — is never on the last assistant message, because
          sending it created a newer user message; a Failed turn persists nothing, so a retry rebuilds the same content)
     content += text(<context> snapshot(ContextInputs built from `inputs`, §6.5) if first message else <delta>)
     content += text(userText)
  3. body = RequestBuilder.build(settings, SystemPrompt.assemble(kb), ToolSchemas.all (bridge: without the read-only tools, §6.13),
                                 history = committed messages, newUser = content)
  4. loop (chain of API calls for ONE user turn; at most 3 automatic follow-ups after the first response):
       events = backend(mode).stream(body) → StreamAccumulator → TurnEvents (TextDelta, ToolUseStarted, ToolUseComplete, Usage)
       read-only tool_use blocks are executed as they complete (ToolExecutor.execute(name, input, inputs.today)) and their results
         collected into toolResults{tool_use_id → ToolExecution}
       proposal tool_use blocks → contract.parse → contract.validate(mutation, inputs.planInputs, inputs.mesocycle, inputs.today, inputs.nutrition)
         → Proposal(PENDING | INVALID) persisted (resultSent = false)
       on message_stop:
         persist THIS request/response pair NOW (step 5) — every pair in the chain is its own (user, assistant) row pair with
         consecutive `seq`, so the next request's prefix is byte-identical after process death (preserved thinking)
         if stop_reason == tool_use:
           if ALL tool_use blocks are read-only and followUps < 3:
               followUps++; body = RequestBuilder.build(…, history = committed (incl. the pair just persisted),
                                    newUser = exactly the tool_result blocks in content order — no delta, no text); continue loop
           else if ALL tool_use blocks are read-only (cap reached):
               store for every read-only block the result {"status":"error","errors":["tool budget for this turn exhausted; answer with what you have"]}
               (is_error = true) in toolResultsJson of the assistant row; conv.status = AWAITING_TOOL_RESULTS; end turn
           else (mixed read-only + proposal, or proposals only):
               the read-only results are already stored in the assistant row's toolResultsJson (step 5); INVALID and PENDING
               proposals alike wait for the next user message (no partial follow-up); conv.status = AWAITING_TOOL_RESULTS; end turn
         else end turn (end_turn | max_tokens | refusal handling below)
     emit Done(stopReason, stopDetailsJson, assistantMessageId of the LAST response) exactly once, after the chain ends
  5. persist one request/response pair (called per response in the chain):
       user ChatMessage(contentJson = the user content sent; displayText = userText for the first pair, "" for a synthetic
         tool_result-only follow-up (hidden by the UI)) and assistant ChatMessage(contentJson = accumulated content VERBATIM,
         thinking/signature/redacted blocks included; toolResultsJson = {tool_use_id: result JSON} for its read-only tool_use blocks;
         usage, requestId, stopReason, stopDetailsJson); one SpendRecord per response (SpendMeter.record; emitted as Usage);
       conv.assistantTurns++, conv.lastInputTokens = this response's total input;
       conv.status = AWAITING_TOOL_RESULTS if the assistant message has any tool_use block, else OPEN.
       SupplementCaveat.check(displayText) → caveatAppended (§6.12)
     on Done(refusal) (any response of the chain): discard that response's partial output; persist nothing for that pair
       (earlier pairs of the chain stay); emit Done("refusal"); the UI offers "Retry on Sonnet 5?" → retryOnSonnet(...) (below).
     on Done(model_context_window_exceeded): treat as truncated; force compaction (§6.9) before the next send.
     on Failed (transport/HTTP, incl. Offline): persist nothing for the failed pair (the model never saw it); keep the draft text.
  6. Proposal decisions (Apply / Reject / Later) are independent of sending: Apply → ProposalApplier (override appended,
     recompute, PlanDiffer.between → PlanDiff) → status APPLIED (or FAILED with the message); Reject → REJECTED; Later →
     DEFERRED; nothing is sent until the next user message (R1 §3.5 pattern 1). Proposals older than 7 days become EXPIRED.
```

**Retry on Sonnet 5** (`retryOnSonnet(userText, inputs: CoachTurnInputs)`): starts a **new** conversation
with `ChatConversation.model = "claude-sonnet-5"` for that conversation only (`settings.modelId`
unchanged), seeds it with a fresh `<context>` snapshot (no `<summary>`), re-sends the kept draft,
and leaves the refused conversation OPEN but `isDefault = false` (it is not the one
`openConversation()` returns; readable from the list). If the refused conversation has PENDING
proposals the UI asks the user to decide them first (same rule as compaction, §6.9).

**Offline** (`network: NetworkMonitor`, §2.11): `send` fails fast with `Failed(Offline, draftKept =
true)` before building anything; `CoachViewModel` keeps the one queued draft, shows
`OfflineBanner`, collects `network.observe()` and calls `send` again on the first `true` (the user
can cancel the queue). Decisions on proposals stay local and are sent as `tool_result`s with the
next online message.

`max_tokens` → the UI shows "truncated" and offers "Continue" (a normal user message "continue";
the assistant content is echoed unchanged). Consecutive user messages are allowed (a failed turn
leaves no trace). `CoachTurnServiceTest` pins: "lookup_exercises + propose_swap_exercise in one
turn → the next request carries both tool_results first, in content order, the read-only one from
`toolResultsJson`"; "four consecutive read-only turns → the fourth is answered with the is_error
budget message"; "2 read-only follow-ups → 3 assistant + 3 user rows, `seq` 1..6, synthetic user
rows have empty displayText and no delta, and the next request's prefix is byte-identical";
"offline → Failed(Offline) before any backend call".

### 6.8 Proposal cards and application (`ProposalApplier`)

`ProposalApplier.apply(proposal, today): PlanOverride` (`:core`, §2.15) appends `PlanOverride(source =
CHAT, proposalId = proposal.id, mutation, expiresOn = today for a mutation whose `scope == TODAY`
(the realizer consumes it in §4.7 step 1; the swap sheet's "This session" writes the same
`expiresOn`), else null)` through `PlanRepository.addOverride` and marks the proposal APPLIED with
a provisional summary; the ViewModel (`:app`) then calls `container.planCoordinator.recompute("proposal")`
(§7.6), computes `PlanDiffer.between(before, after)` (`:core` `mutation/PlanDiffer.kt`, returns the
§2.9 `PlanDiff`), records `Adjustment(kind = OVERRIDE, source = CHAT, proposalId, overrideId,
before/after = the mutated fields)` and calls `ProposalApplier.finalize(proposalId, diff, overrideId)`
(→ `coach.decideProposal(APPLIED, resultSummary = diff JSON)`). `SessionRealizerTest` has the case
"TODAY-scope swap override with expiresOn == today replaces the block; the same override is
ignored tomorrow". The card (§8.17) shows
title by kind, the coach's rationale, validator warnings, the planned diff preview ("Wednesday:
+30 min bike, zone 2 109–127 bpm"), **Apply / Reject / Later**; after Apply: "Applied · Undo"
(5-minute snackbar → `revokeOverride` + `undoAdjustment`, status stays APPLIED with
`resultSummary` amended "undone by user"; the next delta reports it). Cards remain actionable until
expiry; pending proposals can be decided offline (they are local); the decision is sent with the
next online message.

### 6.9 Conversation lifecycle and compaction (preserved-thinking safe)

- One conversation = one frozen `(systemPromptHash, toolContractVersion, model, effort, backend)`.
  Changing any of them in Settings closes the current conversation and starts a new one.
- Within a conversation the transcript is strictly append-only: no turn is edited, reordered or
  removed; thinking blocks are echoed verbatim; `system` and `tools` are byte-identical on every
  request; `tool_choice`, `thinking` and `output_config` never vary.
- **Compaction trigger**: `conv.lastInputTokens > 40,000`, or `assistantTurns > 40`, or the
  conversation is older than 7 days, or `model_context_window_exceeded`.
- **Method** ("simple compaction", `TranscriptCompactor.compact`, §2.15): one non-streamed request
  `ClaudeBackend.complete(RequestBuilder.buildCompaction(settings, compactionPrompt, transcriptText))`
  (no tools, no `tool_choice`, `stream: false`, `max_tokens: 1024`, `effort: low`; the fixed
  `compaction_prompt_v1.txt`: "Summarise the following coaching conversation in at most 600
  tokens: standing decisions, user preferences, open questions, pending proposals. Facts only, no
  advice."; the full transcript's display text as one user message) produces the summary (the text
  blocks of the returned Message; a `SpendRecord` is written for it); the old conversation is marked
  COMPACTED (read-only in the UI); a new conversation starts whose first user message is
  `<summary>` + a fresh `<context>` snapshot. Nothing else is carried over — no earlier turns, no
  thinking blocks. Keep-tail compaction is explicitly rejected (§0.3).
- Never compact while proposals are PENDING: the UI asks the user to decide first.
- The user can start a new conversation manually at any time; old ones stay readable.
- `CoachTurnServiceTest` asserts byte-identical request prefixes between consecutive requests of a
  conversation (system, tools, all earlier messages).

### 6.10 Errors, retry, offline (R1 §3.9 → `BackendError`; `HttpErrorMapper`, `RetryPolicy` in `:app`)

| Condition | `BackendError` | Automatic retry | UI copy / action |
|---|---|---|---|
| no key stored | NotConfigured | no | "Add an API key in Settings → Backend" (link) |
| no validated network — `NetworkMonitor.isOnline()` false at send time (`AndroidNetworkMonitor`: `ConnectivityManager.activeNetwork` has `NET_CAPABILITY_VALIDATED`, tracked with `registerDefaultNetworkCallback`) | Offline | no | `OfflineBanner`; one queued draft is sent automatically when `NetworkMonitor.observe()` turns true (B graft; `CoachViewModel`, §6.7) |
| connect/read timeout, IOException before `message_stop` | Transport(partialOutput) | 2 × exponential (1 s, 4 s) when no output was received; otherwise none | "Connection lost. Retry?" — draft kept; partial text shown as "interrupted" and **not** stored |
| 400 (incl. self-set spend limit) | InvalidRequest(400, message) | no | message; "Report" copies request-id (never the key) |
| 401 | Authentication | no | "Key rejected or expired — enter a new key in Settings" |
| 402 | Billing | no | "Console credits exhausted — top up at platform.claude.com" |
| 403 | Permission | no | message |
| 404 | InvalidRequest(404) | no | "Model not available for this key (update the app?)" |
| 408 request timeout | ServerError(408, message) | 2 × backoff (1 s, 4 s), only if no output yet | "Anthropic timed out, retrying…" |
| 409 `conflict_error` | ServerError(409, message) | 2 × backoff (1 s, 4 s), only if no output yet | "Anthropic error, retrying…" |
| 413 | InvalidRequest(413) | no; the service halves `recent` and retries once | "Message too large" |
| 429 with `retry-after` | RateLimited(sec) | wait `retry-after` (≤ 30 s) once | "Rate limited, retrying in n s" |
| 429 with `error.details.error_code == "enforced_spend_limit_reached"` | SpendCapReached | no | "Console spend cap reached" |
| 500 / 504 | ServerError | 2 × backoff | "Anthropic error, retrying…" |
| 529 or mid-stream `overloaded_error` | Overloaded | 2 × backoff (only if no output yet) | "Overloaded — try again in a minute" |
| other `event: error` | StreamError | no | type + message |
| `stop_reason: refusal` | (not an error; `Done`) | no | "Claude declined (category). Retry with Sonnet 5?" |
| bridge unreachable / 401 / 503 | BridgeUnavailable(reason) | no | "PC offline / secret wrong / login expired / usage limit / disabled by policy" |

This table is the **single normative retry rule set** (it supersedes R1 §3.9 where the two differ;
`RetryPolicyTest` covers every row). `RetryPolicy` never retries 400/401/402/403/404/413 or the
spend-cap 429 and never retries a request that has already produced output. `request-id` from the
response headers is stored on the `ChatMessage`.

### 6.11 Cost and usage guardrails (`PricingTable`, `SpendMeter`, `TokenEstimator`)

`PricingTable` (R1 §2.3, read 2026-09-04; per MTok): Opus 5 input $5 / 5-min write $6.25 / 1-h
write $10 / cache read $0.50 / output $25; Sonnet 5 $2 / $2.50 / $4 / $0.20 / $10. `costUsd = input
× p_in + cacheCreation × p_w5 + cacheRead × p_r + output × p_out` (thinking tokens are inside
`output_tokens`). `SpendMeter.monthToDate()` sums `SpendRecord`s; the Coach screen shows "$x.xx this
month of $budget"; at 80 % a banner appears; at 100 % Send asks for explicit confirmation ("Continue
anyway"); the number is labelled "estimated from token counts and list prices". Usage shows cache
effectiveness (`cache_read / total input`) so a broken cache (byte-unstable prompt) is visible
immediately. Verified per-turn arithmetic (golden): 3,500 cached + 2,000 input + 400 output on
Opus 5 = $0.00175 + $0.01 + $0.01 = **$0.02175**. Token estimates ≈ 4 chars/token; actual counts
come from `usage` after the first call.

### 6.12 On-device safety checks (`RedFlagScanner`, `SupplementCaveat`)

- `RedFlagScanner.scan(text): RedFlagHit?` runs **before** a chat message is sent (no model needed):
  case-insensitive vocabulary (EN + DE) — "chest pain", "Brustschmerz", "pressure in my chest",
  "radiating", "short of breath", "Atemnot", "fainted", "faint", "dizzy", "schwindelig",
  "palpitations", "Herzrasen", "numb", "taub", "tingling", "Kribbeln", "can't feel", "bladder",
  "bowel", "saddle", "hot and swollen", "locked", "gave way", "popped", "Knacken mit", "cola
  urine", "dark urine", "dunkler Urin", "fever", "Fieber", "calf swollen", "Wade geschwollen",
  "worst headache" — a hit shows the RED-01 hand-off card first ("This may be a reason to stop and
  see a professional now…") with "It's not that — send anyway" / "Cancel"; a sent message still
  carries the user's text unchanged.
- `SupplementCaveat.check(assistantText): Boolean` runs **after** a turn: if the text names a
  supplement (vocabulary: creatine/Kreatin, caffeine/Koffein, whey, casein, protein powder,
  beta-alanine, citrulline, melatonin, omega-3, fish oil, vitamin D, multivitamin, electrolyte,
  BCAA, HMB, glutamine, booster, fat burner, SARM, prohormone, ashwagandha, pre-workout) and does
  not contain "Not medical advice", the UI appends the SUP-12 block beneath the message and sets
  `caveatAppended = true`. The appended text is **not** part of the transcript.

### 6.13 Home bridge protocol (phone side ships in v1; `docs/bridge/PROTOCOL.md` is normative)

`POST {bridgeUrl}/v1/messages` with `Authorization: Bearer <bridge secret>` (stored through the same
`KeystoreCipher`) and the **same JSON body** the direct backend would send; the bridge answers with
the same SSE stream (it drives the unmodified `claude -p --output-format stream-json --verbose
--include-partial-messages --system-prompt … --permission-mode dontAsk --json-schema … [--resume
<session_id>]` and maps `{"type":"stream_event","event":{…}}` lines to their inner event, the final
`result` line to synthesized `message_delta` + `message_stop`, and `structured_output` to a
synthesized `tool_use` block, R1 §3.12). Failure JSON from the bridge
(`{"type":"error","error":{"type":"bridge_unavailable","message":"login expired"}}`) maps to
`BridgeUnavailable`. The conditions R1 §2.2 lists (unmodified binary, own account, Tailscale-only,
no `--bare`, no resale, low volume) are printed on the Backend settings screen with the "may stop
working without notice" sentence. `claude -p` flags are re-checked when the PC side is built
(R1 §4.7).

**v1 semantics in HOME_BRIDGE mode (reduced protocol; the bridge cannot run phone-side tools or
receive `tool_result` echoes):** `RequestBuilder` **omits the four read-only tools** (only the 16
`propose_*` tools are declared) and `CoachTurnService` **never auto-follows** (read-only calls
cannot occur); proposal outcomes are conveyed **only** through the `<delta>` text
(`proposals.applied / rejected / pending / invalid` with the diff or the validation errors as
text) and the `tool_result` blocks are **dropped from the body** the phone sends to the bridge;
the bridge maps only the **last user message's text blocks** onto the `-p` prompt and uses
`--resume <session_id>` (stored per conversation) for continuity, so earlier messages are not
re-sent; the bridge synthesises **at most one** `tool_use` block per turn from `structured_output`
(the `--json-schema` is the union of the 16 proposal inputs plus a `text` field). Everything else
(policy text, secret handling, error mapping) is as above; `docs/bridge/PROTOCOL.md` (WP-06)
documents exactly this reduced contract. The mode stays selectable behind "Show experimental" with
the sentence "the home bridge cannot use the lookup tools; the coach answers from the context
only".

---
## 7. Persistence (`:app`, owned by WP-06)

### 7.1 Room 2.8.4 schema — version 1

Design rules: entities carry only `Long/Int/Double/String/Boolean` columns (HR-8); dates are
ISO-8601 strings; instants epoch milliseconds; structures that will evolve (plan snapshot, gym
items, readiness, mutation payloads, chat content, usage) are JSON documents with their own
`schemaVersion`, decoded with `CoreJson.stored` — so most future changes need no SQL migration.
Queryable fields are real columns with indices. No foreign keys with cascade delete (deletion is
rare and explicit). Ids are UUID strings from `:core`.

```kotlin
// app/src/main/kotlin/com/gymcoach/app/data/db/Entities.kt
package com.gymcoach.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = 1,                                   // single row
    @ColumnInfo(name = "json") val json: String,                   // core Profile
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "body_metrics", indices = [Index(value = ["date"])])
data class BodyMetricEntity(
    @PrimaryKey val id: String,
    val date: String,
    @ColumnInfo(name = "entered_weight") val enteredWeight: Double?,
    @ColumnInfo(name = "entered_weight_unit") val enteredWeightUnit: String?,
    @ColumnInfo(name = "weight_kg") val weightKg: Double?,
    @ColumnInfo(name = "waist_cm") val waistCm: Double?,
    @ColumnInfo(name = "resting_hr") val restingHr: Int?,
    val steps: Int?,
    val note: String,
    @ColumnInfo(name = "logged_at") val loggedAt: Long,
)

@Entity(tableName = "gyms", indices = [Index(value = ["last_used_at"])])
data class GymEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "preset_origin") val presetOrigin: String?,
    @ColumnInfo(name = "items_json") val itemsJson: String,        // List<GymItem>
    @ColumnInfo(name = "default_session_minutes") val defaultSessionMinutes: Int?,
    @ColumnInfo(name = "color_index") val colorIndex: Int,
    @ColumnInfo(name = "travel_kit_merged") val travelKitMerged: Boolean,
    val notes: String,
    @ColumnInfo(name = "is_default") val isDefault: Boolean,
    val archived: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long?,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,   // GymSet.schemaVersion (columnar document, §7.2)
)

@Entity(tableName = "sessions", indices = [Index(value = ["date"]), Index(value = ["status"])])
data class SessionEntity(
    @PrimaryKey val id: String,
    val date: String,
    @ColumnInfo(name = "gym_id") val gymId: String,
    @ColumnInfo(name = "planned_session_id") val plannedSessionId: String?,
    @ColumnInfo(name = "template_id") val templateId: String?,
    @ColumnInfo(name = "plan_json") val planJson: String?,         // SessionPlan frozen at start
    val status: String,                                            // SessionStatus serial name
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    @ColumnInfo(name = "session_rpe") val sessionRpe: Int?,
    @ColumnInfo(name = "duration_minutes") val durationMinutes: Int?,
    @ColumnInfo(name = "readiness_checkin_id") val readinessCheckinId: String?,
    @ColumnInfo(name = "skip_reason") val skipReason: String?,
    val notes: String,
)

@Entity(tableName = "set_logs", indices = [Index(value = ["session_id"]), Index(value = ["exercise_id", "logged_at"])])
data class SetLogEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "block_id") val blockId: String?,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "set_index") val setIndex: Int,
    val kind: String,
    @ColumnInfo(name = "entered_weight") val enteredWeight: Double?,
    @ColumnInfo(name = "entered_weight_unit") val enteredWeightUnit: String?,
    @ColumnInfo(name = "weight_kg") val weightKg: Double?,
    @ColumnInfo(name = "per_hand") val perHand: Boolean,
    val reps: Int?,
    val seconds: Int?,
    val metres: Int?,
    val rir: Int?,
    val side: String,
    @ColumnInfo(name = "prescribed_load_kg") val prescribedLoadKg: Double?,
    @ColumnInfo(name = "prescribed_reps_lo") val prescribedRepsLo: Int?,
    @ColumnInfo(name = "prescribed_reps_hi") val prescribedRepsHi: Int?,
    @ColumnInfo(name = "prescribed_rir") val prescribedRir: Int?,
    @ColumnInfo(name = "logged_at") val loggedAt: Long,
    @ColumnInfo(name = "superseded_by_id") val supersededById: String?,   // the only column ever UPDATEd
    @ColumnInfo(name = "supersedes_id") val supersedesId: String?,
    val note: String,
)

@Entity(tableName = "adjustments", indices = [Index(value = ["created_at"]), Index(value = ["session_id"])])
data class AdjustmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "exercise_id") val exerciseId: String?,
    val kind: String,
    @ColumnInfo(name = "rule_ids") val ruleIds: String,            // comma-joined
    val summary: String,
    @ColumnInfo(name = "inputs_json") val inputsJson: String,
    @ColumnInfo(name = "before_json") val beforeJson: String,
    @ColumnInfo(name = "after_json") val afterJson: String,
    val source: String,
    @ColumnInfo(name = "proposal_id") val proposalId: String?,
    @ColumnInfo(name = "override_id") val overrideId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "undone_at") val undoneAt: Long?,
)

@Entity(tableName = "progression_states", indices = [Index(value = ["exercise_id"])])
data class ProgressionStateEntity(
    @PrimaryKey val key: String,                                   // ProgressionKey.id
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "gym_scope") val gymScope: String?,
    val status: String,
    val json: String,                                              // core ProgressionState
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "overrides", indices = [Index(value = ["created_at"])])
data class OverrideEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val source: String,
    @ColumnInfo(name = "proposal_id") val proposalId: String?,
    @ColumnInfo(name = "mutation_type") val mutationType: String,  // PlanMutation discriminator
    @ColumnInfo(name = "mutation_json") val mutationJson: String,
    @ColumnInfo(name = "revoked_at") val revokedAt: Long?,
    @ColumnInfo(name = "expires_on") val expiresOn: String?,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,   // PlanOverride.schemaVersion (columnar document, §7.2)
)

@Entity(tableName = "plan_snapshots", indices = [Index(value = ["is_current"]), Index(value = ["inputs_hash"])])
data class PlanSnapshotEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "inputs_hash") val inputsHash: String,
    @ColumnInfo(name = "mesocycle_json") val mesocycleJson: String,
    @ColumnInfo(name = "computed_at") val computedAt: Long,
    @ColumnInfo(name = "is_current") val isCurrent: Boolean,
)

@Entity(tableName = "nutrition_state")
data class NutritionStateEntity(@PrimaryKey val id: Int = 1, val json: String, @ColumnInfo(name = "updated_at") val updatedAt: Long)

@Entity(tableName = "readiness", indices = [Index(value = ["date", "logged_at"])])
data class ReadinessEntity(
    @PrimaryKey val id: String,
    val date: String,
    val json: String,                                              // core ReadinessCheckin
    val score: Int,
    val band: String,
    @ColumnInfo(name = "logged_at") val loggedAt: Long,
)

@Entity(tableName = "weekly_checkins", indices = [Index(value = ["week_start"], unique = true)])
data class WeeklyCheckinEntity(@PrimaryKey val id: String, @ColumnInfo(name = "week_start") val weekStart: String, val json: String, @ColumnInfo(name = "completed_at") val completedAt: Long?)

@Entity(tableName = "checkin_questions", indices = [Index(value = ["answered"])])
data class CheckinQuestionEntity(@PrimaryKey val id: String, val json: String, val answered: Boolean, @ColumnInfo(name = "active_until") val activeUntil: String?)

@Entity(tableName = "nutrition_logs", indices = [Index(value = ["date"], unique = true)])
data class NutritionLogEntity(
    @PrimaryKey val id: String,
    val date: String,
    val kcal: Int?,
    @ColumnInfo(name = "protein_g") val proteinG: Int?,
    @ColumnInfo(name = "protein_target_hit") val proteinTargetHit: Boolean?,
    @ColumnInfo(name = "meals_heaviness") val mealsHeaviness: Int?,
    @ColumnInfo(name = "alcohol_drinks") val alcoholDrinks: Int?,
    @ColumnInfo(name = "travel_day") val travelDay: Boolean,
    val note: String,
    @ColumnInfo(name = "logged_at") val loggedAt: Long,
)

@Entity(tableName = "cardio_logs", indices = [Index(value = ["date"])])
data class CardioLogEntity(
    @PrimaryKey val id: String,
    val date: String,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    val kind: String,
    val minutes: Int,
    @ColumnInfo(name = "avg_hr") val avgHr: Int?,
    val rpe: Int?,
    val note: String,
    @ColumnInfo(name = "logged_at") val loggedAt: Long,
)

@Entity(tableName = "chat_conversations", indices = [Index(value = ["status"]), Index(value = ["started_at"])])
data class ChatConversationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    val backend: String,
    val model: String,
    val effort: String,
    @ColumnInfo(name = "system_prompt_hash") val systemPromptHash: String,
    @ColumnInfo(name = "tool_contract_version") val toolContractVersion: Int,
    @ColumnInfo(name = "seed_summary") val seedSummary: String?,
    val status: String,
    @ColumnInfo(name = "assistant_turns") val assistantTurns: Int,
    @ColumnInfo(name = "last_input_tokens") val lastInputTokens: Int,
    @ColumnInfo(name = "is_default") val isDefault: Boolean,       // ChatConversation.isDefault (§2.10; false for a refused conversation left open by "Retry on Sonnet 5", §6.7)
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,   // ChatConversation.schemaVersion (columnar document, §7.2)
)

@Entity(tableName = "chat_messages", indices = [Index(value = ["conversation_id", "seq"], unique = true)])
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val seq: Int,
    val role: String,
    @ColumnInfo(name = "content_json") val contentJson: String,   // verbatim content-block array
    @ColumnInfo(name = "display_text") val displayText: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "usage_json") val usageJson: String?,
    @ColumnInfo(name = "request_id") val requestId: String?,
    @ColumnInfo(name = "stop_reason") val stopReason: String?,
    @ColumnInfo(name = "stop_details_json") val stopDetailsJson: String?,
    @ColumnInfo(name = "caveat_appended") val caveatAppended: Boolean,
    @ColumnInfo(name = "tool_results_json") val toolResultsJson: String?,   // assistant rows: {tool_use_id: read-only result JSON} (§6.7)
)

@Entity(tableName = "proposals", indices = [Index(value = ["conversation_id"]), Index(value = ["status"])])
data class ProposalEntity(
    @PrimaryKey val id: String,                                    // tool_use id
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "assistant_message_id") val assistantMessageId: String,
    @ColumnInfo(name = "tool_name") val toolName: String,
    @ColumnInfo(name = "contract_version") val contractVersion: Int,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "mutation_json") val mutationJson: String?,
    val rationale: String,
    val status: String,
    @ColumnInfo(name = "warnings_json") val warningsJson: String,
    @ColumnInfo(name = "errors_json") val errorsJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "expires_on") val expiresOn: String,
    @ColumnInfo(name = "decided_at") val decidedAt: Long?,
    @ColumnInfo(name = "override_id") val overrideId: String?,
    @ColumnInfo(name = "result_summary") val resultSummary: String?,
    @ColumnInfo(name = "result_sent") val resultSent: Boolean,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,   // Proposal.schemaVersion (columnar document, §7.2)
)

@Entity(tableName = "spend_records", indices = [Index(value = ["at"])])
data class SpendRecordEntity(@PrimaryKey val id: String, val at: Long, val model: String, @ColumnInfo(name = "usage_json") val usageJson: String, @ColumnInfo(name = "cost_usd") val costUsd: Double, val backend: String, @ColumnInfo(name = "conversation_id") val conversationId: String)

/** Projection for WorkoutDao.lastLoggedAtByExercise (not an entity). */
data class ExerciseLastLogged(
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "last_logged_at") val lastLoggedAt: Long,
)
```

Nineteen entities (+ one projection). DAOs (`suspend`/`Flow`, all in `room-runtime` 2.8.x;
annotations that have existed since Room 1.0 only). The block is **copied verbatim** including the
package line and imports below; `Daos.kt` holds all seven interfaces (one file):

```kotlin
// app/src/main/kotlin/com/gymcoach/app/data/db/Daos.kt
package com.gymcoach.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao interface ProfileDao {
    @Query("SELECT * FROM profile WHERE id = 1") fun observe(): Flow<ProfileEntity?>
    @Query("SELECT * FROM profile WHERE id = 1") suspend fun get(): ProfileEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: ProfileEntity)
}
@Dao interface GymDao {
    @Query("SELECT * FROM gyms WHERE (:includeArchived = 1 OR archived = 0) ORDER BY last_used_at DESC, created_at DESC") fun observeAll(includeArchived: Boolean): Flow<List<GymEntity>>
    @Query("SELECT * FROM gyms WHERE (:includeArchived = 1 OR archived = 0) ORDER BY last_used_at DESC, created_at DESC") suspend fun getAll(includeArchived: Boolean): List<GymEntity>
    @Query("SELECT * FROM gyms WHERE id = :id") suspend fun get(id: String): GymEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: GymEntity)
    @Query("UPDATE gyms SET is_default = (id = :id)") suspend fun setDefault(id: String)
    @Query("UPDATE gyms SET last_used_at = :at WHERE id = :id") suspend fun touch(id: String, at: Long)
    @Query("UPDATE gyms SET archived = :archived WHERE id = :id") suspend fun setArchived(id: String, archived: Boolean)
}
@Dao interface PlanDao {
    @Query("SELECT * FROM plan_snapshots WHERE is_current = 1 LIMIT 1") fun observeCurrent(): Flow<PlanSnapshotEntity?>
    @Query("SELECT * FROM plan_snapshots WHERE is_current = 1 LIMIT 1") suspend fun current(): PlanSnapshotEntity?
    @Query("SELECT * FROM plan_snapshots WHERE inputs_hash = :hash ORDER BY computed_at DESC LIMIT 1") suspend fun byHash(hash: String): PlanSnapshotEntity?
    @Query("UPDATE plan_snapshots SET is_current = 0") suspend fun clearCurrent()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSnapshot(e: PlanSnapshotEntity)
    @Transaction suspend fun saveCurrent(e: PlanSnapshotEntity) { clearCurrent(); insertSnapshot(e) }
    @Query("SELECT * FROM overrides ORDER BY created_at ASC") fun observeOverrides(): Flow<List<OverrideEntity>>
    @Query("SELECT * FROM overrides ORDER BY created_at ASC") suspend fun overrides(): List<OverrideEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertOverride(e: OverrideEntity)
    @Query("UPDATE overrides SET revoked_at = :at WHERE id = :id AND revoked_at IS NULL") suspend fun revoke(id: String, at: Long)
    // nutrition_state lives here because RoomPlanRepository (PlanDao only, §1.5) implements PlanRepository.nutritionState()
    @Query("SELECT * FROM nutrition_state WHERE id = 1") suspend fun nutritionState(): NutritionStateEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertNutritionState(e: NutritionStateEntity)
}
@Dao interface WorkoutDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSession(e: SessionEntity)
    @Update suspend fun updateSession(e: SessionEntity)
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun session(id: String): SessionEntity?
    @Query("SELECT * FROM sessions WHERE status = 'in_progress' ORDER BY started_at DESC LIMIT 1") fun observeOpen(): Flow<SessionEntity?>
    @Query("SELECT * FROM sessions WHERE date BETWEEN :from AND :to ORDER BY date ASC") suspend fun sessionsBetween(from: String, to: String): List<SessionEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSet(e: SetLogEntity)
    @Query("UPDATE set_logs SET superseded_by_id = :newId WHERE id = :oldId AND superseded_by_id IS NULL") suspend fun markSuperseded(oldId: String, newId: String): Int
    @Transaction suspend fun supersede(oldId: String, replacement: SetLogEntity) { insertSet(replacement); markSuperseded(oldId, replacement.id) }
    @Query("SELECT * FROM set_logs WHERE session_id = :sessionId AND superseded_by_id IS NULL ORDER BY logged_at ASC") fun observeSets(sessionId: String): Flow<List<SetLogEntity>>
    @Query("SELECT * FROM set_logs WHERE session_id = :sessionId AND (:includeSuperseded = 1 OR superseded_by_id IS NULL) ORDER BY logged_at ASC") suspend fun setsForSession(sessionId: String, includeSuperseded: Boolean): List<SetLogEntity>
    @Query("SELECT * FROM set_logs WHERE session_id IN (:ids) AND superseded_by_id IS NULL ORDER BY logged_at ASC") suspend fun setsForSessions(ids: List<String>): List<SetLogEntity>
    @Query("SELECT * FROM set_logs WHERE exercise_id = :exerciseId AND superseded_by_id IS NULL ORDER BY logged_at DESC LIMIT :limit") suspend fun recentSetsForExercise(exerciseId: String, limit: Int): List<SetLogEntity>
    @Query("SELECT exercise_id, MAX(logged_at) AS last_logged_at FROM set_logs WHERE superseded_by_id IS NULL AND kind IN ('working','amrap','calibration','drop') GROUP BY exercise_id") suspend fun lastLoggedAtByExercise(): List<ExerciseLastLogged>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAdjustment(e: AdjustmentEntity)
    @Query("UPDATE adjustments SET undone_at = :at WHERE id = :id AND undone_at IS NULL") suspend fun undoAdjustment(id: String, at: Long)
    @Query("SELECT * FROM adjustments ORDER BY created_at DESC LIMIT :limit") fun observeAdjustments(limit: Int): Flow<List<AdjustmentEntity>>
    @Query("SELECT * FROM adjustments WHERE session_id = :sessionId ORDER BY created_at ASC") suspend fun adjustmentsForSession(sessionId: String): List<AdjustmentEntity>
}
@Dao interface ProgressionDao {
    @Query("SELECT * FROM progression_states WHERE `key` = :key") suspend fun get(key: String): ProgressionStateEntity?
    @Query("SELECT * FROM progression_states") suspend fun getAll(): List<ProgressionStateEntity>
    @Query("SELECT * FROM progression_states WHERE exercise_id = :exerciseId") suspend fun forExercise(exerciseId: String): List<ProgressionStateEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: ProgressionStateEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(list: List<ProgressionStateEntity>)
}
@Dao interface MetricsDao {
    @Query("SELECT * FROM body_metrics WHERE date >= :from ORDER BY date ASC") fun observeBodyMetrics(from: String): Flow<List<BodyMetricEntity>>
    @Query("SELECT * FROM body_metrics WHERE date BETWEEN :from AND :to ORDER BY date ASC") suspend fun bodyMetricsBetween(from: String, to: String): List<BodyMetricEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertBodyMetric(e: BodyMetricEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertCardio(e: CardioLogEntity)
    @Query("SELECT * FROM cardio_logs WHERE date BETWEEN :from AND :to ORDER BY date ASC") suspend fun cardioBetween(from: String, to: String): List<CardioLogEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertNutrition(e: NutritionLogEntity)
    @Query("SELECT * FROM nutrition_logs WHERE date BETWEEN :from AND :to ORDER BY date ASC") suspend fun nutritionBetween(from: String, to: String): List<NutritionLogEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertReadiness(e: ReadinessEntity)
    @Query("SELECT * FROM readiness WHERE date = :date ORDER BY logged_at DESC LIMIT 1") suspend fun readinessFor(date: String): ReadinessEntity?
    @Query("SELECT * FROM readiness WHERE date BETWEEN :from AND :to ORDER BY date ASC, logged_at DESC") suspend fun readinessBetween(from: String, to: String): List<ReadinessEntity>
    @Query("SELECT * FROM readiness WHERE date >= :from ORDER BY date ASC, logged_at DESC") fun observeReadiness(from: String): Flow<List<ReadinessEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertWeekly(e: WeeklyCheckinEntity)
    @Query("SELECT * FROM weekly_checkins WHERE week_start = :weekStart") suspend fun weeklyFor(weekStart: String): WeeklyCheckinEntity?
    @Query("SELECT * FROM weekly_checkins ORDER BY week_start DESC LIMIT :count") suspend fun latestWeekly(count: Int): List<WeeklyCheckinEntity>
    @Query("SELECT * FROM checkin_questions") fun observeQuestions(): Flow<List<CheckinQuestionEntity>>
    @Query("SELECT * FROM checkin_questions WHERE answered = 0 AND (active_until IS NULL OR active_until >= :on)") suspend fun activeQuestions(on: String): List<CheckinQuestionEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertQuestion(e: CheckinQuestionEntity)
}
@Dao interface CoachDao {
    @Query("SELECT * FROM chat_conversations WHERE status IN ('open','awaiting_tool_results') AND is_default = 1 ORDER BY started_at DESC LIMIT 1") suspend fun open(): ChatConversationEntity?   // a refused conversation (is_default = 0, §6.7) is never the default again
    @Query("SELECT * FROM chat_conversations WHERE id = :id") suspend fun conversation(id: String): ChatConversationEntity?
    @Query("SELECT * FROM chat_conversations ORDER BY started_at DESC") suspend fun list(): List<ChatConversationEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertConversation(e: ChatConversationEntity)
    @Update suspend fun updateConversation(e: ChatConversationEntity)
    @Query("SELECT * FROM chat_messages WHERE conversation_id = :id ORDER BY seq ASC") fun observeMessages(id: String): Flow<List<ChatMessageEntity>>
    @Query("SELECT * FROM chat_messages WHERE conversation_id = :id ORDER BY seq ASC") suspend fun messages(id: String): List<ChatMessageEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertMessage(e: ChatMessageEntity)
    @Update suspend fun updateMessage(e: ChatMessageEntity)
    @Query("SELECT * FROM proposals WHERE status = 'pending' ORDER BY created_at ASC") fun observePending(): Flow<List<ProposalEntity>>
    @Query("SELECT * FROM proposals WHERE conversation_id = :id ORDER BY created_at ASC") suspend fun proposals(id: String): List<ProposalEntity>
    @Query("SELECT * FROM proposals WHERE id = :id") suspend fun proposal(id: String): ProposalEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertProposal(e: ProposalEntity)
    @Update suspend fun updateProposal(e: ProposalEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSpend(e: SpendRecordEntity)
    @Query("SELECT * FROM spend_records WHERE at >= :from ORDER BY at ASC") suspend fun spendSince(from: Long): List<SpendRecordEntity>
}
```

```kotlin
// app/src/main/kotlin/com/gymcoach/app/data/db/AppDatabase.kt
package com.gymcoach.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ProfileEntity::class, BodyMetricEntity::class, GymEntity::class, SessionEntity::class, SetLogEntity::class,
        AdjustmentEntity::class, ProgressionStateEntity::class, OverrideEntity::class, PlanSnapshotEntity::class,
        NutritionStateEntity::class, ReadinessEntity::class, WeeklyCheckinEntity::class, CheckinQuestionEntity::class,
        NutritionLogEntity::class, CardioLogEntity::class, ChatConversationEntity::class, ChatMessageEntity::class,
        ProposalEntity::class, SpendRecordEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun gymDao(): GymDao
    abstract fun planDao(): PlanDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun progressionDao(): ProgressionDao
    abstract fun metricsDao(): MetricsDao
    abstract fun coachDao(): CoachDao
}
```

```kotlin
// app/src/main/kotlin/com/gymcoach/app/data/db/Migrations.kt
package com.gymcoach.app.data.db

import androidx.room.migration.Migration

object Migrations { val all: Array<Migration> = arrayOf() }
```

**Migration policy**: `exportSchema = true`; `app/schemas/com.gymcoach.app.data.db.AppDatabase/1.json`
is committed after the first Android Studio build and reviewed on every change. Every later schema
change: `version++`, a hand-written `Migration(from, to) { db.execSQL(…) }` (the
`migrate(db: SupportSQLiteDatabase)` variant, verified in the 2.8.4 sources by proposal C), a review
of the exported schema diff, and a `MigrationTestHelper` instrumented test. **Never**
`fallbackToDestructiveMigration()` in any build type — user logs are sacred; a missing migration
crashes loudly at startup. Document columns evolve by `schemaVersion` + defaults; a document newer
than the reader knows shows "data from a newer app version" instead of guessing.

### 7.2 Repositories and mappers (`data/repo`)

`Mappers.kt` holds pure `toEntity()` / `toModel()` functions; documents are encoded/decoded with
`CoreJson.stored`; enums are stored by their serial name (`Json.encodeToString` of the enum without
quotes → use the `@SerialName` string via a small `EnumNames` helper). Repository implementations
wrap DAO calls in `withContext(Dispatchers.IO)` and map `Flow`s with `.map { list -> list.map {
it.toModel() } }`. Mappers are unit-tested on the JVM (`MappersTest`: entity ↔ model round-trip for
all 19 tables). `RoomWorkoutRepository.supersedeSet` calls `WorkoutDao.supersede` (one transaction,
no value UPDATE). `RoomMetricsRepository.addBodyMetric` for a date that already has a row inserts a
new row (history kept; the newest wins in reads — `bodyMetricsBetween` returns the latest per date).
`RoomWorkoutRepository.lastLoggedAtByExercise()` maps `WorkoutDao.lastLoggedAtByExercise()` to
`Map<String, Instant>`.

**Document versions (`DocVersions.kt`, `data/repo`):** `object DocVersions { val KNOWN: Map<String, Int> }`
lists the newest `schema_version` this build understands per document type (`"Mesocycle" to 1`,
`"ProgressionState" to 1`, `"ReadinessCheckin" to 1`, `"GymSet" to 1`, `"PlanOverride" to 1`,
`"Proposal" to 1`, `"ChatConversation" to 1`, `"WeeklyCheckin" to 1`, `"CheckinQuestion" to 1`,
`"SessionPlan" to 1`). Every `toModel()` that decodes a JSON document first reads its
`schema_version` (a missing key = 1) and compares it with `KNOWN[type]`: greater → the mapper
returns the sealed wrapper `Doc.Unreadable(type, newerVersion, rowId)` instead of a model (`sealed
class Doc<T> { data class Ok<T>(val value: T) : Doc<T>(); data class Unreadable<T>(val type: String,
val version: Int, val id: String) : Doc<T>() }` in `Mappers.kt`); repositories drop `Unreadable`
rows from lists and `report` them to `UnreadableDocsRepository` (§2.11; `AppContainer.unreadableDocs`,
implemented by `data/repo/InMemoryUnreadableDocs.kt`), which `MainActivity` observes to show the
"data from a newer app version" banner (§7.1 policy). Because `CoreJson.stored` ignores unknown
keys, a document of a *known* version with extra fields decodes normally. **Columnar documents**
— `GymSet` (`gyms`, `items_json` holds only the item list), `PlanOverride` (`overrides`,
`mutation_json` holds only the mutation), `ChatConversation` (`chat_conversations`) and `Proposal`
(`proposals`) — carry their `schemaVersion` in the `schema_version` **column** (§7.1), not in a
JSON key: `toEntity()` writes `model.schemaVersion` into the column and `toModel()` compares the
column with `DocVersions.KNOWN[type]` exactly as above (greater → `Doc.Unreadable`), so the value
survives the round trip (`MappersTest` asserts `schemaVersion` and, for conversations, `isDefault`
for both `true` and `false`) and the newer-document path can fire for these four tables too.

### 7.3 DataStore Preferences 1.2.1

Two stores: `settings` (backed up) and `secrets` (excluded from backup). Both delegates live in
**one** file, so no second `preferencesDataStore(name = …)` for the same file is ever declared
(DataStore throws `IllegalStateException: There are multiple DataStores active for the same file`
at runtime otherwise); `SettingsDataStore` (`data/prefs`) and `KeystoreApiKeyStore` (`data/secure`)
both import from it:

```kotlin
// app/src/main/kotlin/com/gymcoach/app/data/prefs/DataStores.kt
package com.gymcoach.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

internal val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
internal val Context.secretsStore: DataStore<Preferences> by preferencesDataStore(name = "secrets")
```

`settings` keys: **only** `settings_json` (the whole `AppSettings` as one JSON string) and
`settings_version` (int = 1). Drafts, the active gym, today's context and the catalogue version
are fields of `AppSettings` (§2.10: `onboardingDraftJson`, `sessionDraftJson`, `todayContextJson`,
`activeGymId`, `catalogVersionSeen`) — there are no separate keys, so every consumer goes through
`SettingsRepository`. `secrets` keys: `api_key_iv`, `api_key_ct`, `bridge_secret_iv`,
`bridge_secret_ct` (Base64 strings). `SettingsDataStore(appContext)` implements
`SettingsRepository` with `observe() = appContext.settingsStore.data.map { it.toAppSettings() }`
and `update` via `settingsStore.edit { }`; a corrupt store (`IOException` on read) is caught once
and mapped to defaults with a log line. On the first `get()`/`observe()` emission that finds
`coachUserId` blank, `SettingsDataStore` writes `coachUserId = IdGen.newId()` (once; the
`metadata.user_id` of §6.2).

### 7.4 Secure key storage (`KeystoreCipher`, `KeystoreApiKeyStore`; R2 §6.1 design verbatim)

```kotlin
// app/src/main/kotlin/com/gymcoach/app/data/secure/KeystoreCipher.kt
package com.gymcoach.app.data.secure

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreCipher(private val alias: String = "gymcoach_master_v1") {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
    fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> {        // iv, ciphertext
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv to c.doFinal(plain)
    }
    /** null on ANY failure (AEADBadTagException, KeyPermanentlyInvalidatedException, UnrecoverableKeyException, missing alias) = "no key stored". */
    fun decrypt(iv: ByteArray, ct: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        c.doFinal(ct)
    } catch (e: GeneralSecurityException) { null }
}
```

`KeystoreApiKeyStore(appContext)` (implements `ApiKeyStore`; reads and writes
`appContext.secretsStore` from `data/prefs/DataStores.kt`, never its own delegate): `store(key)`
validates the `sk-ant-api` prefix (else throws `IllegalArgumentException` with
`backend_key_rejected`), encrypts, writes Base64 iv/ct to `secrets`; `read()` decrypts, returns
`null` and **clears the blob** on failure (the settings
screen then shows "The stored key could not be read on this device — enter it again"); the
plaintext byte array is zeroed after use. No `setUserAuthenticationRequired`, no StrongBox. The key
is only ever placed in the `x-api-key` header to `https://api.anthropic.com` (or the bearer header to
the user's own bridge URL); never logged, never in a URL, never in error text.

Backup exclusion — `res/xml/backup_rules.xml` (API ≤ 30) and `res/xml/data_extraction_rules.xml`
(API 31+): exclude `datastore/secrets.preferences_pb` from cloud backup and device transfer; the
database and `settings` are backed up (the user's data survives a phone change; the key does not,
by design).

```xml
<!-- res/xml/backup_rules.xml -->
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <exclude domain="file" path="datastore/secrets.preferences_pb" />
</full-backup-content>
<!-- res/xml/data_extraction_rules.xml -->
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup><exclude domain="file" path="datastore/secrets.preferences_pb" /></cloud-backup>
    <device-transfer><exclude domain="file" path="datastore/secrets.preferences_pb" /></device-transfer>
</data-extraction-rules>
```

### 7.5 Knowledge loading on device

Primary: `ClasspathKnowledgeSource` (§3.9) reads `/gymcoach/knowledge/*.json` from the `:core` jar
merged into the APK as Java resources (AGP merges library-module Java resources; OkHttp's own
`publicsuffixes.gz` is loaded the same way — inferred, not verified: R2 G10, C Appendix D item 3).
Fallback: `AssetKnowledgeSource(assets)` reads `gymcoach/knowledge/<file>` through the `sourceSets`
assets line in `app/build.gradle.kts` (§1.7). `AppContainer.knowledgeSource` picks the class-path
source when `exists("exercises.json")`, else assets; if both fail the app shows a fatal error naming
the path (never an empty library). A startup smoke check logs the counts (156 exercises, 78
equipment ids, 19 groups, 4 presets). `catalog_version_seen` in settings drives a one-time "library
updated" note.

### 7.6 Plan recompute orchestration (`PlanCoordinator`, `app/coordinator`, WP-06)

Lives in `app/src/main/kotlin/com/gymcoach/app/coordinator/PlanCoordinator.kt`, depends only on
`core.repo` interfaces and `:core` services (never on `AppContainer`, `data.db` or a ViewModel;
§1.4), and is instantiated once by `AppContainer.planCoordinator` (§1.5). ViewModels never
construct it.

```kotlin
// app/src/main/kotlin/com/gymcoach/app/coordinator/PlanCoordinator.kt (signature contract; constructor exactly as §1.5 calls it)
class PlanCoordinator(
    private val plans: PlanRepository,
    private val profile: ProfileRepository,
    private val gyms: GymRepository,
    private val workouts: WorkoutRepository,
    private val progression: ProgressionRepository,
    private val metrics: MetricsRepository,
    private val settings: SettingsRepository,
    private val knowledge: KnowledgeBase,
    private val plannerFactory: (PlannerConstants) -> Planner,
    private val engineFactory: (PlannerConstants) -> ProgressionEngine,
    private val deloadPolicyFactory: (PlannerConstants) -> DeloadPolicy,
    private val clock: Clock,
) {
    /** PlannerConstants.withOverrides(DEFAULT, settings.plannerOverridesJson, CoreJson.stored). */
    suspend fun constants(): PlannerConstants
    /** Builds PlanInputs from repositories, hashes them, reuses the snapshot with the same hash, else runs Planner.plan and stores it. */
    suspend fun recompute(reason: String): Mesocycle
    /** Today's SessionPlan for the given TodayContext (never stored until the session starts). PURE: Planner.realize over buildInputs() — no DLD-03 evaluation, writes nothing. */
    suspend fun realizeToday(today: TodayContext): SessionPlan
    /**
     * Week rollover on first app open in a new ISO week (settings.lastWeekRollover): Planner.rollWeek → progression.saveAll(result.states) +
     * one Adjustment per result.ladderNotes entry (PLAT-02, below) → DLD-03 evaluation (below; appends the ScheduleDeload card to result.proposals,
     * fills result.deloadTriggers and records the onsets) → recompute("week_roll"). Returns null when no week boundary was crossed.
     */
    suspend fun rollWeekIfDue(): WeekRollResult?
    /** After a session is closed: ProgressionEngine.afterSession → save states + adjustments → recompute. */
    suspend fun onSessionClosed(sessionId: String): SessionCloseResult
    /** PlanInputs from the repositories (windows below), incl. deloadTriggersActive from DeloadPolicy.signals (§4.4 VOL-08 hold). Pure read. */
    suspend fun buildInputs(): PlanInputs
    /** The current reactive-deload suggestion for Today (§4.11): DeloadPolicy.reactive(...) over fresh signals, or null. Read by TodayViewModel into TodayUiState.deloadSuggestion (§8.6). Pure read — proposes nothing, writes nothing. */
    suspend fun deloadSuggestion(today: LocalDate): Explanation?
    /**
     * Records the user's decision on the Today card: AppSettings.deloadTriggersProposed += every ACTIVE trigger's "<kind>@<onset>"
     * (DeloadPolicy.signals(...).active(today, c.plat.dld03MinDays)), so the same onset is never proposed again (§4.11). Called by
     * TodayViewModel.onAcceptDeload (after writing the ScheduleDeload override, before recompute("deload")) and by onDismissDeload (§8.6).
     * Idempotent; a no-op when nothing is active.
     */
    suspend fun recordDeloadDecision(today: LocalDate)
    /**
     * PLANNER proposal cards of the current plan (§2.9 PlanOutput.proposals — under-target "+1 day / +10 min / micro-session / accept",
     * PLAT-02 step-3 swaps …): plannerFactory(constants()).plan(buildInputs(), today).proposals, memoised per inputsHash (the same hash
     * recompute() keys snapshots by). PURE — persists nothing. Read into TodayUiState.plannerProposals and PlanUiState.proposals (§8.6, §8.10);
     * the reactive ScheduleDeload card is separate (deloadSuggestion).
     */
    suspend fun plannerProposals(today: LocalDate): List<PlanMutation>
}
```

**DLD-03 orchestration (the only place WP-03's planner and WP-04's `DeloadPolicy` meet):**
`rollWeekIfDue()` runs, after `Planner.rollWeek` and after persisting the ladder advances, `val
policy = deloadPolicyFactory(constants())`, `val signals = policy.signals(inputs,
progression.getAll(), today)` and `val hit = policy.reactive(signals, today,
inputs.deloadTriggersProposed.toSet())`. When `hit != null`: build the PLANNER proposal
`ScheduleDeload` (this week or next, source PLANNER, rationale = `hit.message`), append it to
`WeekRollResult.proposals`, fill `WeekRollResult.deloadTriggers` with the active triggers'
explanations, and record the onsets exactly as `recordDeloadDecision(today)` does
(`AppSettings.deloadTriggersProposed` += every active trigger's `"<kind>@<onset>"`), so the same
onset is never proposed twice (§4.11). `realizeToday()` is **pure**: it runs no DLD-03 evaluation
and writes nothing. On Today the card is `deloadSuggestion(today)` (`TodayUiState.deloadSuggestion`,
§8.6) — a pure read that repeats the evaluation over fresh signals and never touches
`deloadTriggersProposed`; **the card persists until the user accepts or dismisses it
(`recordDeloadDecision`) or the week rolls; nothing else records an onset.** `onAcceptDeload` writes
the `ScheduleDeload` override (source USER), calls `recordDeloadDecision(date)` and then
`recompute("deload")`; `onDismissDeload` calls `recordDeloadDecision(date)` only (§8.6).
`Planner.rollWeek` itself returns `deloadTriggers = emptyList()` and never sees `DeloadPolicy`.

**PLAT-02 ladder persistence** (§4.11): `rollWeekIfDue` calls `progression.saveAll(result.states)`
and, for every `result.ladderNotes` entry, `workouts.recordAdjustment(Adjustment(id = IdGen.newId(),
sessionId = null, exerciseId = note.inputs["exercise_id"], kind = PROGRESSION for step 2 / VOLUME
for step 4 (`note.inputs["step"]`), ruleIds = [PLAT-02], summary = note.message, inputs =
note.inputs, beforeJson/afterJson = the state before/after, source = PLANNER, createdAt =
clock.now()))` — all before `recompute("week_roll")`, so the recompute already sees the advanced
states. Step-3/step-5 proposals arrive in `result.proposals` and are shown as PLANNER cards
(`plannerProposals`, above).

`PlanInputs` windows: sessions/sets last 12 weeks, body metrics 12 weeks, weekly check-ins 8,
readiness 14 days; `patternLastTrained` from `workouts.lastLoggedAtByExercise()` mapped through
`knowledge.exercises[id].pattern` (newest date per pattern, all history); `deloadTriggersActive =
deloadPolicyFactory(constants()).signals(inputs, states, today).active(today,
c.plat.dld03MinDays).map { "${it.kind}@${it.onset}" }` (computed over the otherwise-complete
inputs, `today = clock.today()`; `deloadTriggersProposed` = `AppSettings.deloadTriggersProposed`
decoded). The mesocycle clock lives
in `AppSettings`: `mesocycleAnchor` (ISO date of the current mesocycle start; `PlanCoordinator` sets
it when a mesocycle starts or ends — scheduled end, or the Monday after an accepted reactive DELOAD
week), `mesocyclesCompleted` (incremented at every mesocycle end), `lastDeloadWeekStart` (written
when any DELOAD week starts) and `mesocycleWeekPausedAt` (the week index frozen while a
TRAVEL_MAINTENANCE week runs; 0 when not paused); `PlanInputs.travelWeeks` is derived from active
`SetTravelDays` overrides and CHK Q10 answers. Recompute is cheap (< 50 ms target for a 6-week
mesocycle) and is the only way a plan changes; `plannerProposals`, `deloadSuggestion` and
`realizeToday` are reads and never change one.

---
## 8. UI (`:app`; shell WP-01, training screens WP-07, setup/health/coach/settings WP-08)

### 8.1 Design principles (B graft) and quantified targets

Five moments of a travelling technician drive every screen: Sunday planning (Today/Plan/Coach),
06:40 in an unknown hotel gym (Today → gym switcher → editor → session), mid-session logging
(Session), the evening check-in (Readiness/Weekly/Coach), Saturday at home (Session/Progress).
Principles: **prefilled, not empty**; **one thumb, bottom third** (primary actions in the bottom
40 %, 56–64 dp targets, steppers instead of keyboards); **offline is the normal state**; **never a
silent change** (every modification shows its rule chip before a set is logged and lands in the
Adjustment log with Undo); **gyms are first-class**; **the coach proposes, the app applies**;
**honesty in settings**.

| Target | Value |
|---|---|
| Onboarding to "Start session" available | ≤ 3 min (6 pages, 2 skippable) |
| Pick an existing gym | 2 taps, ≤ 5 s |
| New gym from a preset | ≤ 60 s |
| Log a set matching the target | 1 tap ("Log set") |
| Log a set with a different load/reps | ≤ 4 taps |
| Swap a busy exercise | 3 taps (Swap → pick → confirm) |
| Daily readiness (quick) | 3 taps + Done |
| Accept a coach proposal | 1 tap |

### 8.2 Theme and global conventions (`ui/theme`, WP-01)

Material 3 1.4.0, dynamic colour on API 31+ (`dynamicLightColorScheme`/`dynamicDarkColorScheme`)
with a fixed fallback palette; dark theme follows the system. Type scale: session numerals
`displayMedium` for the current load/reps, `titleLarge` for targets; minimum body 16 sp. Touch
targets 56 dp for steppers/chips on the session screen, 64 dp for "Log set", 48 dp elsewhere.
Readiness colours use the theme's tertiary/secondary/error containers with the band word always
printed. Portrait only. Font scaling up to 1.5× tested on the session screen (steppers wrap to two
rows above 1.3×). Every icon button has a `contentDescription`; TalkBack order = visual order; the
rest countdown is announced at 10 s; all sheets are dismissible by swipe and a close button.
Units: storage metric; imperial converts at display and parses input back to `EnteredWeight`
(the typed value is kept, HR-20); after logging, the row shows both units for one second
("36 kg / 79.4 lb"). Numeric entry: `OutlinedTextField` with `KeyboardType.Decimal`; parsing accepts
`.` and `,`. Haptics via `LocalHapticFeedback` on "Log set" and rest-over.

### 8.3 Navigation (`ui/nav`, WP-01)

```kotlin
// app/src/main/kotlin/com/gymcoach/app/ui/nav/Routes.kt
package com.gymcoach.app.ui.nav

import kotlinx.serialization.Serializable

// Tabs (bottom bar visible): Today · Plan · Progress · Coach · More
@Serializable object TodayRoute
@Serializable object PlanRoute
@Serializable object ProgressRoute
@Serializable object CoachRoute
@Serializable object MoreRoute

// Full-screen destinations (bottom bar hidden)
@Serializable object OnboardingRoute
@Serializable data class ReadinessRoute(val date: String)                      // ISO date
@Serializable data class SessionRoute(val sessionId: String)
@Serializable data class SessionSummaryRoute(val sessionId: String)
@Serializable data class PlanSessionRoute(val plannedSessionId: String)
@Serializable data class ExerciseProgressRoute(val exerciseId: String)
@Serializable object GymsRoute
@Serializable data class GymEditRoute(val gymId: String, val fromPreset: String)   // "" = new gym / no preset
@Serializable object LibraryRoute
@Serializable data class ExerciseDetailRoute(val exerciseId: String)
@Serializable object ProfileRoute
@Serializable object CheckinRoute
@Serializable object NutritionRoute
@Serializable object KnowledgeRoute
@Serializable data class RuleDetailRoute(val ruleId: String)
@Serializable object AdjustmentLogRoute
@Serializable data class ConversationRoute(val conversationId: String)
@Serializable object SettingsRoute
@Serializable object BackendSettingsRoute
@Serializable object AdvancedConstantsRoute
@Serializable object UsageRoute
```

`AppNavHost(container: AppContainer, navController: NavHostController)` has one `composable<T>` entry
per route calling `XxxScreen(container, navController)` (fixed signature for every screen — Phase-0
contract; routes with arguments pass `backStackEntry.toRoute<T>()` as a third parameter). Start
destination: `OnboardingRoute` until `settings.onboardingDone`; else `TodayRoute`; if a session
with status IN_PROGRESS exists → `SessionRoute` directly. `BottomBar` shows on the five tab routes
only. Edges: Today → ⇢ gym switcher sheet, ⇢ session-start sheet → `SessionRoute`; → `ReadinessRoute`,
`GymEditRoute`, `CheckinRoute`, `NutritionRoute`, `ConversationRoute` (from a proposal card),
`SessionSummaryRoute`, `AdjustmentLogRoute`. Session → sheets (plates, swap, info, notes, red flag,
finish) → `SessionSummaryRoute` (Session popped); back = "Pause & minimise". Plan →
`PlanSessionRoute`, `AdjustmentLogRoute`, `ExerciseDetailRoute`. Progress → `ExerciseProgressRoute`.
Coach → `ConversationRoute`. More → Gyms, Library, Profile, Check-in, Nutrition, Knowledge, Settings,
Usage. Deep links: none in v1 (v1.1 adds notification links).

### 8.4 Shared components (`ui/components`, WP-01; signatures frozen)

| Composable | Signature | Purpose |
|---|---|---|
| `NumberField` | `(value: String, onValueChange: (String) -> Unit, label: String, unit: String, modifier)` | decimal entry with unit label |
| `Stepper` | `(value: String, onMinus: () -> Unit, onPlus: () -> Unit, onTap: () -> Unit, modifier, enabled: Boolean = true)` | 56 dp load/reps stepper |
| `RirChips` | `(selected: Int?, target: Int?, onSelect: (Int?) -> Unit)` | 0 · 1 · 2 · 3 · 4+ |
| `BigPrimaryButton` | `(text: String, onClick: () -> Unit, modifier, enabled: Boolean = true)` | 64 dp primary |
| `RuleTag` | `(ruleId: String, onClick: (String) -> Unit)` | outlined chip → `RuleDetailRoute` |
| `ExplanationChip` | `(explanation: Explanation, onRule: (String) -> Unit)` | rule chips + message |
| `ProposalCard` | `(proposal: Proposal, diffPreview: String?, onApply, onReject, onLater, onUndo: (() -> Unit)?)` | §8.17 |
| `LineChart` | `(series: List<ChartSeries>, modifier, yLabel: String)` | Canvas line chart (R2 §6.2); `ChartSeries`/`ChartPoint` are the §2.14 `core.model` types (produced by `ProgressSeries`, WP-04); `lowConfidence` points draw dashed |
| `MuscleBandBars` | `(rows: List<MuscleBandRow>, showDirect: Boolean)` | direct/fractional bars vs band; `MuscleBandRow` is the §2.14 `core.model` type (`target == null` rows show the advisory cap only) |
| `EmptyState` | `(icon: ImageVector, title: String, body: String, actionText: String?, onAction: (() -> Unit)?)` | |
| `ErrorBanner` | `(title: String, body: String, onRetry: (() -> Unit)?, details: String?)` | |
| `OfflineBanner` | `()` | Coach only |
| `ConfirmDialog` | `(title, body, confirmText, onConfirm, onDismiss)` | |
| `SectionHeader` | `(text: String)` | |
| `MonthGrid` | `(selected: LocalDate?, onSelect: (LocalDate) -> Unit)` | date picker replacement (HR-3) |
| `HourMinuteStepper` | `(minutesOfDay: Int, onChange: (Int) -> Unit)` | time entry |
| `PlateDiagram` | `(barKg: Double, perSidePlates: List<Double>)` | Canvas plates |
| `LoadingBox` | `()` | skeleton rows |

Every screen has `EmptyState` and `ErrorBanner` states; loading uses skeleton rows, never a
blocking spinner (except the knowledge load on first start, < 1 s expected).

### 8.5 Onboarding (`ui/onboarding`, WP-08) — route `OnboardingRoute`, 6 pages, ≤ 3 min

```kotlin
data class OnboardingUiState(
    val page: Int, val draft: ProfileDraft, val gymDraft: GymSet?, val presetId: String?,
    val building: Boolean, val error: String?, val builtSummary: String?,
)
data class ProfileDraft(val sex: Sex = Sex.MALE, val birthYear: Int = 1990, val heightValue: String = "178", val weightValue: String = "80",
    val unitSystem: UnitSystem = UnitSystem.METRIC, val goal: Goal = Goal.GENERAL_FITNESS, val daysPerWeek: Int = 3,
    val weekdays: List<Int> = listOf(1, 3, 5), val sessionMinutes: Int = 60, val trainingMonths: Int = 0, val layoffMonths: Int = 0,
    val retrainedMonths: Int = 0, val limitations: List<Limitation> = emptyList(), val medicalFlags: Set<MedicalFlag> = emptySet(),
    val knownLoads: Map<String, String> = emptyMap(), val allowAdvanced: Boolean = false)
```

Events: `onNext`, `onBack`, `onSkipPage`, `onDraftChange(ProfileDraft)`, `onPresetSelected(id)`,
`onReviewEquipment`, `onFinish`. Pages: (1) Welcome; (2) About you: sex chips, birth year stepper,
height, weight, unit toggle; (3) Goal & schedule: 5 goal cards with DEF-06 one-liners, days chips
1–6, weekday toggles, minutes chips 30/45/60/90; (4) Experience & limits (skippable): months
consistent stepper, layoff months + retrained months, limitation chips (knee/shoulder/low back/
elbow-wrist/hip/ankle/neck) each with side + pain 0–10 slider, medical-flag checklist with what it
gates (RED-03), the RED-01 list acknowledged; (5) Your gym: preset cards (Commercial / Home /
Hotel / Bands + bodyweight), name field, "Review equipment" → the onboarding **persists** the
preset gym first (`GymRepository.upsert(gym.copy(isDefault = true))`, id kept in the draft) and
navigates to `GymEditRoute(gymId, fromPreset = "")`; on return it re-reads the gym from
`gymRepository.observeAll()` (no result channel needed; `gymDraft` is only a pre-save cache);
optional known loads for the six main lifts; (6) Your week: the generated mesocycle (built from the
**stored** gym) as a 7-day strip with set counts, the under-target cards, "Coach chat needs an API
key — set up later in Settings"; "Start" → Today. Draft saved to `AppSettings.onboardingDraftJson`
(`settingsRepository.update { it.copy(onboardingDraftJson = …) }`) on every change and cleared on
finish. Validation: height 120–230 cm, weight 30–250 kg, birth year 1920–2015 (inline messages,
clamped on save). `onFinish` runs `container.planCoordinator.recompute("onboarding")` on
`Dispatchers.Default` (< 200 ms expected).

### 8.6 Today (`ui/today`, WP-07) — route `TodayRoute`

```kotlin
data class TodayUiState(
    val date: LocalDate, val gym: GymSet?, val gyms: List<GymSet>, val readiness: ReadinessCheckin?,
    val sessionPlan: SessionPlan?, val plannedSession: PlannedSession?, val openSession: SessionRecord?,
    val weekMode: WeekMode, val restDay: Boolean, val trainAsPlanned: Boolean,
    val cardio: CardioPrescription?, val cardioLoggedToday: Boolean,
    val pendingProposals: List<Proposal>, val plannerProposals: List<PlanMutation>, val weeklyCheckinDue: Boolean,
    val deloadSuggestion: Explanation?, val underTarget: List<Explanation>, val nutritionStrip: NutritionStrip,
    val yesterday: SessionRecord?, val noPlan: Boolean, val loading: Boolean, val error: String?,
    val minutesToday: Int?, val spotter: Boolean, val travelKit: Boolean, val removedEquipment: Set<String>,
)
data class NutritionStrip(val weightMa7Kg: Double?, val delta14Kg: Double?, val proteinG: Int?, val caffeineCutoff: String?, val proteinDays7: Int?)
data class CardioLogDraft(val exerciseId: String, val kind: CardioKind, val minutes: String, val avgHr: String, val rpe: Int?)
```

Events: `onSelectGym(id)` (writes `AppSettings.activeGymId`), `onNewGymFromPreset(preset)`,
`onToggleSpotter`, `onToggleTravelKit`, `onRemoveEquipmentToday(ids)`, `onMinutesToday(Int?)`
(these four persist the day's `TodayContext` as `AppSettings.todayContextJson`, restored on the
next launch of the same date and cleared when the date changes, so process death on Today loses
nothing), `onTrainAsPlanned(Boolean)`, `onStartSession()` (freezes the plan →
`WorkoutRepository.startSession` → records modifiers as Adjustments → `SessionRoute`),
`onOpenReadiness`, `onSkipToday(reason)` (moves the session if the week has room, else SKIPPED),
`onAcceptDeload`, `onDismissDeload`, `onProposalApply/Reject/Later(id)`, `onPlannerProposal(mutation, accept)`,
`onQuickWeight(value)`, `onLogCardio` → opens the cardio-log sheet (`CardioLogDraft`: modality
chips from the day's prescription or the gym's cardio machines, minutes stepper, optional average
HR, RPE chips 6–20; "Log" writes `MetricsRepository.addCardioLog`; the sheet is prefilled from the
day's `CardioPrescription`), `onResumeSession`. Today's session plan comes from
`container.planCoordinator.realizeToday(todayContext)` (pure, §7.6); `deloadSuggestion` comes from
`container.planCoordinator.deloadSuggestion(date)` (§7.6 — the H20 reactive-deload card; null when
no DLD-03 pair is active or the onset set was already recorded); `onAcceptDeload` writes the
matching `ScheduleDeload` override (source USER, §4.11), then calls
`container.planCoordinator.recordDeloadDecision(date)` and `recompute("deload")`; `onDismissDeload`
calls `recordDeloadDecision(date)` only — the card stays until one of the two (or the week roll)
records its onsets. `plannerProposals` comes from `container.planCoordinator.plannerProposals(date)`
(the pure, memoised PLANNER cards of §2.9 `PlanOutput.proposals`; `onPlannerProposal(mutation,
accept)` writes the override with source PLANNER and recomputes).
Layout (LazyColumn): header (date, gym chip "Home gym ▾" → gym switcher sheet, readiness chip
"Check in" / "Amber 58"); modifier banner (every `SessionPlan.modifiers` explanation with
`RuleTag`s, "Train as planned" switch); session card (name, estimated minutes incl. warm-up, N
exercises, muscle chips, compact block list with substitution icons, **Start** full width, Preview,
"I have 30 min" chip, "Skip today"); cardio card; pending cards (coach proposals, planner
proposals, weekly check-in due, deload suggestion, RED-02 referral, reminder questions); nutrition
strip; yesterday link. Alternate states: `noPlan` → EmptyState "No plan yet" → onboarding pages
3–5; `restDay` → card "Rest day · Mobility 8 min / Zone-2 walk 30 min"; `openSession` → "Resume
session · 23 min elapsed"; gym archived → "Pick a gym", Start disabled. Gym switcher sheet: gyms by
`lastUsedAt`, each with colour dot, "used Tue", available-exercise count for today's session
("12/13 exercises"), dumbbell-max warning icon; "New gym from preset ▸"; "Duplicate current gym".
Session-start sheet ("Anything different today?"): Spotter switch, "Missing today" chips, "Extra
today" search-add, session length chips (gym default highlighted), inline quick readiness if not
yet checked in, **Start session**.

### 8.7 Readiness (`ui/readiness`, WP-07) — route `ReadinessRoute(date)`

```kotlin
data class ReadinessUiState(val date: LocalDate, val quickMode: Boolean, val sleptBucket: Int?, val feel: Int?, val sore: Int?,
    val sleepHours: String, val sleepQuality: Int?, val soreness: Int?, val sorenessRegion: Muscle?, val stress: Int?, val energy: Int?,
    val motivation: Int?, val restingHr: String, val steps: String, val illness: Boolean, val alcohol: Int, val weightValue: String, val proteinHit: Boolean?,
    val mealsHeaviness: Int?, val preview: ReadinessCheckin?, val saved: Boolean, val error: String?)
```

Events: `onQuickSlept(bucket)`, `onQuickFeel(1..5)`, `onQuickSore(1..5)`, `onExpand`, `onField(...)`,
`onIllness(Boolean)`, `onAlcohol(Int)`, `onWeight(String)`, `onSteps(String)`, `onDone`,
`onSomethingWrong` (RED-01 sheet). Quick mode = three chip rows + Done; the preview line shows
score, band, the modifiers that will apply ("Amber 56: +1 RIR, −1 set after the first two exercises
— RDY-02") and "assumed: sleep quality, stress (tap to fill)". "More" expands the full RDY-01 form
plus resting HR, **yesterday's steps** (writes `BodyMetric.steps` for the date; STP-01/02, NUT-04
activity factor, CHK Q5), the nutrition chips, alcohol, "I'm ill" and the morning weight field.
Duplicate check-in for the same date → "Update today's check-in" (new row; the latest wins).

### 8.8 Session (`ui/session`, WP-07) — route `SessionRoute(sessionId)` — the product

```kotlin
enum class SessionPhase { LOADING, WARMUP, WORKING, FINISHING, DONE, RECOVERED, ERROR }
enum class RowStatus { PENDING, CURRENT, DONE, SKIPPED }
enum class SessionSheet { PLATES, SWAP, INFO, NOTES, RED_FLAG, FINISH, KEYPAD, CORRECT_SET, ADD_EXERCISE }
data class SetDraft(val loadValue: String, val loadUnit: WeightUnit, val reps: String, val rir: Int?, val seconds: String, val metres: String)
data class ExerciseRow(val block: ExerciseBlock, val name: String, val logged: List<SetLog>, val status: RowStatus,
    val lastTimeSummary: String?, val hint: Explanation?, val substitutedFrom: String?, val warnings: List<Explanation>, val findLoad: Boolean)
data class RestTimerState(val endsAtEpochMs: Long, val totalSec: Int, val forBlockId: String)
data class SessionUiState(val phase: SessionPhase, val record: SessionRecord?, val plan: SessionPlan?, val gymName: String,
    val elapsedSec: Int, val progressText: String, val warmupDone: Boolean, val rows: List<ExerciseRow>, val currentIndex: Int,
    val currentSetIndex: Int, val draft: SetDraft, val stepKg: Double, val unit: UnitSystem, val rest: RestTimerState?,
    val modifiers: List<Explanation>, val backoffChip: Pair<Double, Explanation>?, val sheet: SessionSheet?, val error: String?)
```

Events: `onLogSet()` (writes exactly the displayed values — `EnteredWeight(entered, unit, kg)`,
reps, RIR — via `WorkoutRepository.addSet`; haptic; starts the rest timer; advances),
`onLoadMinus/Plus`, `onRepsMinus/Plus`, `onRir(Int?)`, `onOpenKeypad`, `onOpenPlates`, `onUsePlateLoad(kg)`,
`onCorrectSet(setId)` → `onSaveCorrection(setId, draft)` (`supersedeSet`; the old row shows
struck-through with "edited"), `onSwap` → `onPickSubstitute(exerciseId, reason, scope)`,
`onInfo`, `onAddSet`, `onRemovePlannedSet`, `onSkipExercise`, `onJumpTo(index)`, `onWarmupDone`,
`onSkipWarmup(reason)`, `onLogRamp(setIndex)`, `onFindLoadAccept()` (§4.9.2), `onApplyBackoff()`
(PROG-04 chip → `Adjustment(AUTOREGULATION, USER)`), `onRestPlus30`, `onRestSkip`, `onSomethingWrong`,
`onPause`, `onAbandon`, `onFinish` → `onFinishConfirm(sessionRpe, note, doMobility)`.

Layout (portrait, one hand): top bar (‹ Pause, "Upper A · Hotel Ulm", elapsed, overflow: notes,
red flag, abandon); progress bar "7 of 13 sets"; collapsible warm-up block (Raise suggestion +
Done, drills with Done, ramp sets with Log → `RAMP` rows greyed; "Skip warm-up" needs a reason
chip and records an Adjustment); the **current exercise** pinned and expanded (info ⓘ and swap ⇄,
"last: 20 × 18 @1 (Tue)", "target 15–20 @1", load stepper (long-press → plates, tap → keypad),
reps stepper, RIR chips with the target highlighted, logged rows "✓ 1 · 20 kg × 17 @1" (tap →
correct), the next row "○ 2 · 20 × 15–20 @1 ← you are here"); upcoming exercises collapsed (tap
to jump); done exercises collapsed to one line; bottom: rest bar ("⏱ Rest 1:42 [+30 s] [skip]",
only while resting) and **LOG SET** (64 dp, always visible; enabled at all times, the warm-up never
blocks it). Nothing needs a keyboard. Draft prefill: target load, `repHi` for set 1 then the last
logged reps of this exercise this session, target RIR; the load stepper step =
`IncrementResolver.step` for the block (barbell 2.5 / 1.0 with microplates; dumbbell rack step;
stack step; bodyweight +2.5 external). Unilateral exercises show "per side"; one logged set = both
sides (UNI-02): the ViewModel writes **one `SetLog` row with `side = BOTH`** (per-side rows are
v1.1). Dumbbell pairs are logged per hand (`perHand = true`, "2×22 kg" shows 22 in the stepper);
single-implement holds (goblet, suitcase carry) log the implement (`perHand = false`). Time-based
sets show a seconds stepper with Start/Stop. In-session hints (chips
above Log set, never auto-applied): PROG-04 back-off; "All sets hit the top of the range: next
time +2 kg (PROG-01)" (informational); rack-max warning (from the planner); find-load "Use 60 kg as
your working load?". Rest timer in v1 = coroutine in the ViewModel while the app is in front
(`FLAG_KEEP_SCREEN_ON` when `keepScreenOnInSession`); background countdown, notification actions
and exact-alarm backup are v1.1 (§9.4).

Plate calculator sheet: target total, `barKg` (20; 15 selectable), plate inventory from
`smallestPlateKg` (1.25 → {25, 20, 15, 10, 5, 2.5, 1.25}; microplates add {0.5, 0.25}); greedy per
side; unreachable → nearest below/above with deltas and two "Use N" buttons; `PlateDiagram`.
Dumbbells: rounder ("22 → 22 kg is in your rack; max 30"); stacks: "pin 8 = 40 kg (+2.5 add-on
= 42.5)". Nothing applies until a button is tapped.

Swap sheet (3 taps): reason chips Busy · Not here · Pain · Prefer other; candidates from
`SubstitutionEngine.rank(...)` (§2.15) — `SessionViewModel.onSwap` calls
`container.planner(constants).normalize(container.planCoordinator.buildInputs(), today.date)` once
(§4.2, pure Stage 0) and passes its `excludedExerciseIds`, `penalizedExerciseIds` and `trainingAge`
together with `allowAdvanced = profile.allowAdvancedExercises`, `equipment = SessionEquipment(today)`,
`params = GymParams(kb, equipment)` and the stored states; the sheet shows the list (ranked, with one-line rationale from `content.substitutionRationale` or the
generated "same pattern; <equipment> not available", the load the planner would prescribe, flags
relevant to active limitations, the ledger effect); scope "This session" / "Rest of program";
"Pain" additionally offers "log a limitation" (→ `SetLimitation` override) and hides
flag-sensitive candidates. Confirm records `Adjustment(EQUIPMENT, USER, ruleIds = [TRV-04] or
[INJ-0x])`; the row header shows "⇄ was Barbell Row". Empty: "No alternative for this pattern with
today's equipment — skip exercise?" [Skip] [Keep].

Info sheet tabs: **How** (description, setup, cues), **Watch out** (mistakes, safety reflecting
today's flags: for a squat-family/SP lift on a rack without safeties "no safeties or spotter
today → stop at RIR 2 (RIR-03/R4 §5.5)"; for a bar-over-face lift the sheet is only reachable when
safeties or a spotter are present, otherwise the exercise was substituted — §3.1 decision),
**Numbers** (profile for the
goal, increment, muscles in both views, last 5 sessions best set, e1RM if applicable),
**Sources**; `content_status` badge; `content == null`/todo → "Description not written yet —
muscles, equipment and numbers are still exact".

Finish sheet: unlogged planned sets listed as "not done" (kept as-is, nothing fabricated); session
RPE chips 0–10 (RDY-06); note; post-session mobility block if a joint has a chronic dose; **Finish
session** → `finishSession`, `container.planCoordinator.onSessionClosed(sessionId)` (progression),
→ `SessionSummaryRoute`.

States: `RECOVERED` — the app was killed mid-session; on launch the router finds an IN_PROGRESS
session and opens it with "Session restored — 4 sets logged, last at 14:32"; the draft is restored
from `AppSettings.sessionDraftJson` (`SetDraft` + `currentIndex`/`currentSetIndex` as JSON, written
through `settingsRepository.update { }` on every stepper change and cleared on finish/abandon;
`SessionViewModelTest` covers the round trip). Sessions older than 6 h and still IN_PROGRESS are offered "Finish as-is"
or "Abandon" (never silently closed). `ERROR` — an exercise id unknown to this catalogue version
renders "Exercise unavailable in this version" with Swap; never a crash. Airplane mode: no effect.

### 8.9 Session summary (`ui/session/SessionSummaryScreen.kt`, WP-07) — `SessionSummaryRoute(sessionId)`

State: `SessionSummaryUiState(record, sets, volume: VolumeSnapshot, adjustments: List<Adjustment>,
progression: List<Explanation>, e1rmChanges: List<Pair<String, Double>>, durationMin, sessionRpe)`.
Shows totals (sets, tonnage kg, minutes), per-exercise outcome lines ("DB Bench: hit → next time
+2 kg PROG-01", "Row: held PROG-01") with `RuleTag`s, e1RM changes, the readiness/modifier recap,
every `Adjustment` of the session, and "Ask the coach about this session" (opens the conversation
with a prefilled quick prompt).

### 8.10 Plan (`ui/plan`, WP-07) — `PlanRoute`, `PlanSessionRoute(plannedSessionId)`

State: `PlanUiState(mesocycle: Mesocycle?, currentWeek: MesoWeek?, weekIndex, ledger: WeeklyLedgerView,
overrides: List<PlanOverride>, underTarget: List<Explanation>, proposals: List<PlanMutation>, loading, error)`;
`WeeklyLedgerView(rows: List<MuscleBandRow>, carryover: Map<Muscle, Double>, showDirect: Boolean)` is a
WP-07-owned UI type (`ui/plan/PlanViewModel.kt`) whose `rows` are the §2.14 `core.model.MuscleBandRow`
produced by `WeeklyLedger.rows(tiers, kb)` (§2.15); `proposals` are read from
`container.planCoordinator.plannerProposals(today)` (§7.6 — the same memoised PLANNER cards Today
shows; the reactive deload card is Today-only). Events: `onWeekSelect`, `onToggleDirect`,
`onRegenerate` (confirm; keeps progression), `onRevokeOverride(id)`, `onProposal(mutation, accept)`,
`onOpenSession(id)`. Layout: mesocycle timeline ("week 3 of 6 · deload week 7 · mode normal"),
week strip with sessions, cardio days and mobility, per-session rows → `PlanSessionRoute` (slots with
sets × reps @ RIR, rest, current load, alternates; slot overflow: swap (rest of program), sets ±,
rep-range zone, exclude — each an override with an Adjustment), weekly ledger card (targets vs
achieved per muscle, fractional/direct toggle, carryover, RP landmark markers in the direct view),
under-target report with option cards, "Changes" → `AdjustmentLogRoute`. Empty: no plan → same
EmptyState as Today. Error: regenerate failed → previous plan kept, error card.

### 8.11 Progress (`ui/progress`, WP-07) — `ProgressRoute`, `ExerciseProgressRoute(exerciseId)`

State: `ProgressUiState(range: Int /* days */, strength: List<LiftCard>, volume: List<MuscleBandRow>,
weight: ChartSeries, weightMa7: ChartSeries, readiness: List<Pair<LocalDate, ReadinessBand>>, sleepAvg: Double?,
rhr: ChartSeries?, cardioMinutesByWeek: List<Pair<LocalDate, Int>>, trainingLoad: List<Pair<LocalDate, Int>>,
spikeNote: Explanation?, consistency: List<Pair<LocalDate, String>>, mobilityMinutes: Map<Joint, Int>)`;
`LiftCard(exerciseId: String, name: String, bestE1rm14d: Double?, delta4w: Double?, spark: ChartSeries,
lowConfidence: Boolean, gymScoped: Boolean)` is a WP-07-owned UI type (`ui/progress/ProgressViewModel.kt`);
`ChartSeries`/`MuscleBandRow` are the §2.14 `core.model` types, computed by `ProgressSeries` (`:core`,
§2.15). `ExerciseProgressUiState(exercise: Exercise, series: ChartSeries, loadXReps: ChartSeries?,
bestSets: List<SetLog>, state: ProgressionState?, gymScopes: List<String>, loading: Boolean, error: String?)`
for `ExerciseProgressRoute` (`onGymScope(id)`, `onRange(days)`). Cards → details: Strength (e1RM per main lift, dashed when
low-confidence; machines show load × reps per gym), Volume (direct and fractional bars vs the
program band and RP MEV–MRV, last 8 weeks small multiples, explanation line "fractional = 1.0 /
0.5 / 0.25 DEF-05"), Body (dots + MA7 line, goal-rate band, creatine marker, travel-day shading,
14-day delta, next NUT-16 review), Readiness & sleep, Cardio (minutes/week vs CAR-01 floor),
Consistency (Q8), Training load (RDY-06 spike note), Mobility minutes. Empty: each card "Log N
sessions / weigh in 7 days to see a trend".

### 8.12 Library and exercise detail (`ui/library`, WP-08) — `LibraryRoute`, `ExerciseDetailRoute(id)`

State: `LibraryUiState(query, pattern: MovementPattern?, muscle: Muscle?, gymId: String?, availableOnly,
difficulty: Difficulty?, flagFilters: Set<String>, results: List<LibraryRow(exercise, availableInGym)>)`;
`ExerciseDetailUiState(exercise: Exercise?, availableInGym: Boolean, missing: List<String>, history: ChartSeries?,
content: ExerciseContent?, contentStatus: ContentStatus, state: ProgressionState?, canAddToday: Boolean, error: String?)`
(events `onAddToToday`, `onOpenProgress`). Search matches names and aliases (EN/DE); filter chips:
pattern, muscle, "available in [gym]", difficulty, flags (no spinal loading, knee-friendly …).
Detail = the info sheet content plus the e1RM chart and "Add to today's session" (inserts a block
with 2 sets, default profile, an `Adjustment(USER)`). Empty: "No exercise matches — clear filters".

### 8.13 Gyms (`ui/gyms`, WP-08) — `GymsRoute`, `GymEditRoute(gymId, fromPreset)`

```kotlin
data class GymsUiState(val gyms: List<GymSet>, val defaultId: String?, val activeId: String?, val loading: Boolean, val error: String?)
data class GymEditUiState(val gym: GymSet, val query: String, val expanded: Set<EquipmentCategory>, val availableCount: Int,
    val totalExercises: Int, val dirty: Boolean, val saving: Boolean, val error: String?, val isNew: Boolean)
```

Events: `onName`, `onColor`, `onStartFromPreset(id)` (confirm when items exist), `onQuery`,
`onToggleItem(id)`, `onParam(equipmentId, key, JsonElement)`, `onToggleTravelKit`, `onCopyFrom(gymId)`,
`onDefaultMinutes(Int?)`, `onSave`, `onArchive`, `onSetDefault`. Layout: name + 8 colour dots;
"Start from" preset chips; search field matching names and DE/EN aliases ("Latzug" finds
`lat_pulldown_machine`); 8 collapsible category sections with counts ("Free weights 6/8"); each row =
checkbox + name + inline parameter controls when checked (`dumbbells`: max kg stepper in 2-kg steps,
step chips 1/2/2.5, min; `plates`: smallest plate chips 1.25/2.5/5 + microplates switch;
`power_rack`: safeties and pull-up bar switches; cable machines: stack step chips 5/2.5 and
half-step switch; `kettlebells`: kg chips 8–32; `fixed_weight_barbells`, `plyo_box`,
`medicine_balls`, `resistance_bands`: list editors); sticky footer with **live availability** ("34
items · 96 of 156 exercises available · Save"); "Travel kit" switch merges the packable items;
"Same as another gym" copies items; long-press → description and aliases; gym-level default session
minutes (home 60 / hotel 45 prefilled from the preset). Empty: 0 items → "0 items · 41
bodyweight/band exercises available". Error: save failed → snackbar retry, draft kept. Gyms list:
rows ordered by last used, default badge, archive/duplicate via overflow.

### 8.14 Profile (`ui/profile`, WP-08) — `ProfileRoute`

State: `ProfileUiState(profile: Profile?, draft: ProfileDraft, trainingAge: TrainingAge?, dirty: Boolean,
planAffected: Boolean, saving: Boolean, error: String?)` (`ProfileDraft` from §8.5; events `onDraftChange`,
`onSave`, `onClearRedFlagRegion(region)`, `onRegenerate`). All onboarding fields editable (sex, birth year, height, goal, days, weekdays, minutes, training
months, layoff, limitations with pain/side/since/limited-range, medical flags, clearance date,
priority muscles, liked/disliked, known loads, advanced opt-in, body-fat %, physical job, HR
monitor + measured HRmax, wake/bed times, usual session time); shows the derived training age and
"Plan affected — regenerate?" on save; RED-01 region blocks are cleared here.

### 8.15 Weekly check-in (`ui/checkin`, WP-08) — `CheckinRoute`

State: `CheckinUiState(weekStart, questions: List<CheckinQuestion>, answers: Map<String, CheckinAnswer>,
autoAnswered: Set<String>, page: Int, triggered: List<Explanation>, done: Boolean)`. One question per
page with big chips or a stepper; auto-answered items pre-filled and editable; "Done" runs
`container.checkinEvaluator(constants).evaluate(checkin, history, inDeficit)`, saves the
`WeeklyCheckin` with `triggered = outcome.triggered` and `holds = outcome.holds.sorted()` (the
VOL-08 channel to the planner, §4.4), persists `outcome.proposals` as PLANNER cards, calls
`container.planCoordinator.recompute("checkin")`, and shows the
triggered actions as cards ("Sleep < 7 h → sets −10 % next week SLP-03 [OK] [Not this week]") and
the proposals they created. Empty: nothing due → "Next check-in Sunday". Reminder questions with a
due date appear as Today cards.

### 8.16 Nutrition and Knowledge (`ui/nutrition`, `ui/knowledge`, WP-08) — `NutritionRoute`, `KnowledgeRoute`, `RuleDetailRoute(ruleId)`

Nutrition state: `NutritionUiState(energy: EnergyModel?, targets: NutritionTargets?, trend: List<WeightTrendPoint>,
todayLog: NutritionLog?, weightValue: String, stepsValue: String, kcalValue: String, proteinDays7: Int, reviewCard: Explanation?,
supplements: List<SupplementAdvice>, medicalGate: Boolean, tips: List<NutritionTip>, activityFactor: Double,
bodyFatPct: String)` (`SupplementAdvice` = §2.14). Shows targets with `RuleTag`s and the energy
model's assumptions ("activity factor 1.55 — starting guess NUT-04"), the weigh-in row (weight +
**steps** for the date, both written to one `BodyMetric` via `onWeighIn(weight, steps)`) and trend,
the NUT-16 review card with trigger values (incl. the NUT-05 "> 1.5 %/week — professional
supervision" text when it applies), optional daily kcal/protein entry, "protein hit?" and meals
chips, alcohol, travel-day toggle, supplement tiers with the SUP-12 caveat pinned and the
priority-order sentence, travel heuristics. Knowledge: `KnowledgeUiState(query: String, section: String?,
sections: List<String>, rules: List<RuleEntry>)` and `RuleDetailUiState(rule: RuleEntry?, constants:
List<ConstantRow>)` with `ConstantRow(group: String, field: String, key: String, default: Double, current: Double,
adjustable: Boolean)` (from `PlannerConstants.leafFields()` filtered by `ruleIdOf(field) == rule.id`,
current values from `plannerConstants(settings.plannerOverridesJson)`): searchable list of every
`RuleEntry` (id, statement, confidence badge, citation link opening the browser when online)
grouped by section; `RuleDetailRoute` shows the statement, confidence, citation, constants (current
vs default) and "edit in Advanced" for adjustable rules; every `RuleTag` in the app deep-links here.

### 8.17 Coach (`ui/coach`, WP-08) — `CoachRoute` (conversation list), `ConversationRoute(id)`

```kotlin
// app/src/main/kotlin/com/gymcoach/app/ui/coach/CoachViewModel.kt (state types; copied verbatim)
import com.gymcoach.core.repo.BackendError

sealed class CoachPhase {
    object Idle : CoachPhase()
    object Sending : CoachPhase()
    data class Streaming(val partial: String) : CoachPhase()
    object AwaitingTool : CoachPhase()
    object ProposalPending : CoachPhase()
    data class Error(val error: BackendError, val retryable: Boolean) : CoachPhase()
    data class Offline(val draftQueued: Boolean) : CoachPhase()
    object NoBackend : CoachPhase()
    data class Refused(val category: String?) : CoachPhase()
    object Truncated : CoachPhase()
    object SpendCapReached : CoachPhase()
    object CompactionNeeded : CoachPhase()
}
data class CoachUiState(val conversation: ChatConversation?, val messages: List<ChatMessage>, val proposals: Map<String, Proposal>,
    val phase: CoachPhase, val draft: String, val contextPreview: String, val quickPrompts: List<String>, val monthSpendUsd: Double,
    val budgetUsd: Double, val model: String, val effort: Effort, val redFlagHit: String?)
data class CoachListUiState(val conversations: List<ChatConversation>, val messageCounts: Map<String, Int>,
    val spendByConversation: Map<String, Double>, val loading: Boolean, val error: String?)
```

(`when (phase)` is exhaustive over these twelve subclasses, HR-11; synthetic tool_result-only user
rows — `displayText == ""` — are filtered out of `messages` before rendering.)

Events: `onDraft(String)`, `onSend` (builds the §2.15 `CoachTurnInputs` and calls
`container.coachTurnService.send(draft, inputs)`: `today` = the persisted `TodayContext`
(`AppSettings.todayContextJson`, else `TodayContext(clock.today(), activeGymId)`); `todaySession` =
the open session's frozen plan, else `container.planCoordinator.realizeToday(today)`, else null;
`planInputs = container.planCoordinator.buildInputs()`; `constants =
container.planCoordinator.constants()`; `mesocycle = container.planRepository.getCurrent()`;
`trainingAge = container.planner(constants).normalize(planInputs, today.date).trainingAge`;
`ledger = WeeklyLedger.build(currentWeek, weekSets, weekSessions, todaySession, container.knowledge)`
(null without a mesocycle); `energy = container.energyEstimator(constants).model(...)`, `nutrition =
container.macroTargets(constants).compute(...)` (both null without a weigh-in), `weightMa7Kg` /
`trendKgPerWeek` from `container.weightTrend(constants).series(metrics)` (`ma7Kg` of the last point;
`delta14Kg / 2`) — §6.5 lists how the service turns this into `ContextInputs`), `onStop`, `onRetry`,
`onRetryOnSonnet` (→ `CoachTurnService.retryOnSonnet`: a new conversation on `claude-sonnet-5`
seeded with a fresh snapshot, the draft re-sent, the refused conversation kept readable but no
longer default; blocked with a dialog while that conversation has PENDING proposals — §6.7),
`onContinue`, `onApply(proposalId)`, `onReject(proposalId, reason)`, `onLater(proposalId)`,
`onUndo(proposalId)`, `onNewConversation`, `onCompactAndContinue`, `onShowContext`,
`onQuickPrompt(text)`, `onRedFlagSendAnyway` (re-sends the same draft with
`CoachTurnInputs.skipRedFlagScan = true`, §6.7 step 0), `onRedFlagCancel`. Layout: messages as bubbles (assistant text rendered
Markdown-lite: bold, lists, inline code; exercise ids turned into tappable names); proposal cards
inline after the assistant text (§6.8) with "Applied · Undo"; blockers disable Apply and say why
("Trap-bar not in Hotel Ulm"); context chip at the top ("Context: profile, Hotel Ulm, U/L wk 3,
last 14 days" → sheet with the exact JSON sent); quick prompts row above the composer ("Shorten
today to 30 min", "My shoulder hurts on pressing", "Hotel gym Tue–Thu", "Explain today's plan",
"Hotel breakfast: what do I eat?", "Why this deload?"); composer with send/stop; streaming text
token by token; footer "This conversation: 12 messages · $0.31 est. · Opus 5 low". Offline
(`container.networkMonitor`): `OfflineBanner` while `isOnline()` is false; `onSend` while offline
queues exactly one draft (`Offline(draftQueued = true)`); the ViewModel collects
`networkMonitor.observe()` and sends the queued draft on the first `true` (the user can cancel).
`NoBackend` → EmptyState "Set up the coach" → `BackendSettingsRoute`. The SUP-12 caveat block is
appended under any assistant text naming a supplement (§6.12). Conversation list
(`CoachListUiState`): date, message count, cost, status, "default" marker; "New conversation".

### 8.18 Settings (`ui/settings`, WP-08) — `SettingsRoute`, `BackendSettingsRoute`, `AdvancedConstantsRoute`

```kotlin
data class SettingsUiState(val settings: AppSettings, val catalogVersion: Int, val appVersion: String, val error: String?)
data class BackendSettingsUiState(val mode: BackendMode, val hasKey: Boolean, val keyDraft: String, val keyError: String?,
    val testing: Boolean, val testResult: String?, val bridgeUrl: String, val bridgeSecretSet: Boolean, val model: String,
    val effort: Effort, val monthlyBudgetUsd: Double, val showExperimental: Boolean, val openConversationWillClose: Boolean)
data class ConstantRow(val group: String, val field: String, val ruleId: String, val confidence: Confidence, val citation: String,
    val default: String, val current: String, val adjustable: Boolean, val kind: String /* "int" | "double" | "band" | "list" | "bool" */)
data class AdvancedConstantsUiState(val rows: List<ConstantRow>, val query: String, val dirty: Boolean, val error: String?)
```

Settings: Units; Week start; Profile link; Session (keep screen on); Reminders (v1: readiness
reminder time and weekly check-in weekday stored for v1.1; shown as "in-app cards until notifications
arrive"); Data (catalogue version; export/import are v1.1); Backend link; Advanced link; About
(versions, licences, the R3 reference list). Backend: the three policy blocks verbatim (§6.1); API
key entry (masked, paste button, prefix validation with `backend_key_rejected`), **Test** (calls
`container.backend(mode).test()` → `TestResult`: on success "OK · request-id …", on failure
`error.type`-derived copy from §6.10 plus the `request_id` when the response carried one — never
the key; the body is `RequestBuilder.buildTest`, one `max_tokens: 32` non-streamed request through
`ClaudeBackend.complete`), Remove key; Home bridge URL + secret behind "Show experimental" with the
R1 §2.2 conditions and the "cannot use the lookup tools" sentence (§6.13); model (Opus 5 / Sonnet 5),
effort (low / medium) with `backend_cost_line`; monthly budget. Changing model or effort closes the
open conversation (§6.9). Advanced (`AdvancedConstantsUiState`): every **leaf** of
`PlannerConstants` from `PlannerConstants.leafFields()` (no reflection) with its group, rule id
(`ruleIdOf`), confidence, citation, default and current value; only `user_adjustable` rules
editable; Band/list leaves edit as two/N number fields; reset per field and "reset all"; writes the
**nested** `plannerOverridesJson` (`{"vol":{"vol04Novice":[8,12]}}`, §4.1) through
`settingsRepository.update`.

### 8.19 Usage, Adjustment log, More (`ui/usage`, `ui/plan/AdjustmentLogScreen.kt`, `ui/more`)

```kotlin
data class UsageUiState(val monthSpendUsd: Double, val budgetUsd: Double, val perDay: List<Pair<LocalDate, Double>>,
    val perConversation: List<Pair<String, Double>>, val tokensIn: Long, val tokensOut: Long, val cacheHitRate: Double?, val error: String?)
data class AdjustmentLogUiState(val items: List<Adjustment>, val undoableIds: Set<String>, val expandedIds: Set<String>,
    val loading: Boolean, val error: String?)
```

Usage: spend per day and per conversation, tokens, cache hit rate, budget bar, the "estimated
from list prices (2026-09-04)" footnote. Adjustment log (`AdjustmentLogRoute`): reverse-chronological
`Adjustment`s with rule tags, source, before → after (pretty JSON on expand), Undo while valid
(5 minutes for user/coach overrides; planner adjustments are informational). More: links to Gyms,
Library, Profile, Check-in, Nutrition, Knowledge, Settings, Usage, About.

### 8.20 Empty and error states — master table

| Screen | Empty | Error |
|---|---|---|
| Today | no plan / rest day / no gym (§8.6) | realization failed → card "Could not build today's session (reason) — Rebuild / Ask the coach" |
| Gym switcher | no gyms → "Create your first gym" | — |
| Gym editor | 0 items (bodyweight count shown) | save failed → snackbar retry, draft kept |
| Session | no planned sets (free session) → "Add exercise" opens the library | unknown exercise id → row with Swap; plan missing → "Session data incomplete — finish as-is or abandon" |
| Plan | no plan | regenerate failed → previous plan kept, error card |
| Progress | per-card "need N more" | — |
| Coach | no backend / no conversations | offline, no gym configured (`InvalidRequest(0, …)` from §6.7 step 0 → card "Add a gym first" → `GymsRoute`, draft kept), 401, 402, 403, 404, 413, 429 (rate vs spend cap), 500/529, mid-stream error, refusal, max_tokens (§6.10) |
| Check-in | nothing due | — |
| Nutrition | < 7 weights | — |
| Settings → Backend | no key | Test failed: `error.type` + `request_id` (never the key) |
| Library | no match → "clear filters" | — |

---
## 9. Work breakdown — 14 packages with exclusive file ownership

Phasing: **Phase 0** = WP-01 (sequential, first; freezes every interface, type, route, shared
composable and build file the others compile against; tag `v0-interfaces`). **Phase 1** = WP-02 …
WP-14 in parallel. **Phase 2** = one integrator (§9.3). No file has two owners; cross-WP needs
are met by *reading* the Phase-0 files, never by editing them (change requests go to the WP-01
owner and are merged as small PRs before Phase 1 starts). Every WP's PR includes a **compile-risk
list** (every `:app` symbol used that is not in R1/R2/§0.3's verified set, with the reason it is
believed to exist) and adds tests only inside its own test packages. All paths are relative to
`C:\Users\User\Projects\GymCoach\`.

| WP | Name | Owns (exact paths) | Depends on | Acceptance criteria |
|---|---|---|---|---|
| **WP-01** | Scaffold + contracts (Phase 0) | `settings.gradle.kts`; `build.gradle.kts`; `gradle.properties`; `gradle/libs.versions.toml`; `gradle/wrapper/gradle-wrapper.properties`; `gradle/wrapper/gradle-wrapper.jar`; `gradlew`; `gradlew.bat`; `.gitignore`; `core/build.gradle.kts`; `app/build.gradle.kts`; `app/proguard-rules.pro`; `app/schemas/.gitkeep`; `app/src/main/AndroidManifest.xml`; `app/src/main/res/xml/{backup_rules,data_extraction_rules}.xml`; `app/src/main/res/values/{strings,themes}.xml` (`strings.xml` = `app_name` + the §8.4 shared-component strings only); `app/src/main/res/drawable/ic_launcher.xml`; `core/src/main/resources/gymcoach/knowledge/.gitkeep`; `core/src/main/kotlin/com/gymcoach/core/model/{Enums,Values,Knowledge,Profile,Gym,Readiness,Checkin,Plan,Session,Progression,Mutation,PlanInputs,Nutrition,Coach,Settings,Views}.kt`; `core/src/main/kotlin/com/gymcoach/core/json/{Serializers,CoreJson}.kt`; `core/src/main/kotlin/com/gymcoach/core/repo/{Repositories,ClaudeBackend,BackendError,NetworkMonitor,UnreadableDocs}.kt`; `core/src/main/kotlin/com/gymcoach/core/explain/{RuleIds,Explanations}.kt`; `core/src/main/kotlin/com/gymcoach/core/units/Units.kt`; `core/src/main/kotlin/com/gymcoach/core/time/{Clock,Weeks}.kt`; `core/src/main/kotlin/com/gymcoach/core/util/{IdGen,Hashing}.kt`; `core/src/main/kotlin/com/gymcoach/core/planner/PlannerConstants.kt` (§4.1 verbatim); `core/src/main/kotlin/com/gymcoach/core/progression/E1rm.kt` (§4.10 verbatim); `core/src/test/kotlin/com/gymcoach/core/{json,model,units,time,util}/*Test.kt`; `core/src/test/kotlin/com/gymcoach/core/planner/PlannerConstantsShapeTest.kt`; `core/src/test/kotlin/com/gymcoach/core/progression/E1rmTest.kt`; `core/src/test/kotlin/com/gymcoach/core/NoAndroidImportsTest.kt`; `core/src/test/kotlin/com/gymcoach/core/RuleCoverageTest.kt`; `app/src/main/kotlin/com/gymcoach/app/{GymCoachApp,MainActivity}.kt`; `app/src/main/kotlin/com/gymcoach/app/di/AppContainer.kt`; `app/src/main/kotlin/com/gymcoach/app/ui/theme/{Theme,Color,Type}.kt`; `app/src/main/kotlin/com/gymcoach/app/ui/nav/{Routes,AppNavHost,BottomBar}.kt`; `app/src/main/kotlin/com/gymcoach/app/ui/components/{NumberField,Stepper,RirChips,BigPrimaryButton,RuleTag,ExplanationChip,ProposalCard,LineChart,MuscleBandBars,EmptyState,ErrorBanner,OfflineBanner,ConfirmDialog,SectionHeader,MonthGrid,HourMinuteStepper,PlateDiagram,LoadingBox}.kt`; `app/src/test/kotlin/com/gymcoach/app/ui/nav/RoutesTest.kt`; `docs/build/FIRST_BUILD_CHECKLIST.md`; `docs/test/GYM_FLOOR_SCRIPT.md` (content = Appendix B) | — | Build files verbatim from §1.7; every type of §2 exactly (names, fields, defaults, `@SerialName`s), incl. §2.14 `Views.kt` and the §2.15 result types that live in WP-01 files; `PlannerConstants` and `E1rm` verbatim; `ModelRoundTripTest` (every `@Serializable` type — `Views.kt` and every `PlannerConstants` group included — encode → decode → encode identical); `PlannerConstantsShapeTest` (§4.1); `E1rmTest` rows (106.7, 125.4, 121.7, LOW above 10, interpolation at every table row); `SerializersTest`; `UnitsTest`; `NoAndroidImportsTest`; `RuleIds` contains every R3 §10 id and `RuleIds.ALL` lists every constant; `RuleCoverageTest` (§10.2); `AppContainer` exactly as §1.5; `AppNavHost` has a `composable<T>` for every route calling `XxxScreen(container, navController[, route])`; WP-01 ships each screen as a one-line placeholder composable of the frozen signature at the file path the owning WP-07/WP-08 will overwrite (§9.1) so the shell compiles; `:core:test` green |
| **WP-02** | Knowledge assets + equipment | `core/src/main/resources/gymcoach/knowledge/{exercises,equipment,templates,rules,mobility_drills,checkin_questions,supplements,cardio_modalities,nutrition_rules,rp_landmarks,load_transfer}.json`; `core/src/main/kotlin/com/gymcoach/core/knowledge/{KnowledgeBase,KnowledgeLoader,ClasspathKnowledgeSource,KnowledgeValidator,RuleBook,RpLandmarks,SessionTemplates}.kt`; `core/src/main/kotlin/com/gymcoach/core/equipment/{EquipmentResolver,GymParams,IncrementResolver,LoadRounder,Presets}.kt`; `core/src/test/kotlin/com/gymcoach/core/{knowledge,equipment}/*Test.kt` (incl. `ContentBatchesTest.kt`, §3.2); `core/src/test/resources/knowledge/ids_v1.txt`. **Not** the `exercise_content/batch_N.json` files (WP-09…WP-14 create their own) | WP-01 | All 156 exercises and 78 equipment ids transcribed exactly from R4 (muscle weights, options in printed order incl. `rack_or_spotter` on the barbell bench variants — §3.1 decision, flags, increments, substitutions, profiles); the 11 rep profiles with `sets` bands (§3.1); 19 groups; 4 presets with session minutes; `templates.json` per §3.4 incl. the per-split `_S` strength variants (`UPPER_A_S4`/`UPPER_A_S5`/`LOWER_A_S5` …, every `backoff_of` naming a template of the SAME split row — rule 10), the **separate strength split rows** (`for_goals: ["strength"]`; `ul_6` strength-only; the non-strength `upper_lower_4`/`ul_ppl_5`/`upper_lower_full_3` rows with the four-goal list; `ppl_6` incl. ENDURANCE_SUPPORT; the two `full_body_3` rows) and the TRAVEL_B side-delt slot; `rules.json` with every id of `RuleIds.ALL` (R3 §10 + planner conventions + the four prose-only R3 ids `POL-03`, `DEF-06`, `DEF-07`, `NUT-18` per §3.5), confidence tags **normalised** per §3.5 with the printed composite tag in `note` (also `SupplementEntry.evidence`, `NutritionTip.confidence`), constants equal to `PlannerConstants` defaults under the §3.5 key convention incl. `CAR-05.hiitBaseWeeks` (`RuleBookTest`, both directions, all groups; strict decode of every entry; every `"R3 tag: "` note carries the normalised value); MOB-07 drills, CHK-01 questions, supplements, modalities, tips, RP landmarks verbatim, load-transfer seeds; `KnowledgeValidationTest` rules 1–11 pass; `KnowledgeLoader` tolerates missing batch files; `ContentBatchesTest`; `EquipmentResolverTest` (rack without safeties fails `rack_or_spotter`, spotter flag satisfies it, `has_pullup_bar=false` fails `pullup_bar_any`, `all_of: []`, load-type override, bench unavailable without safeties/spotter, hotel ≥ 40, travel kit ≥ 25); `IncrementResolverTest` (incl. `implements` and both logging styles), `LoadRounderTest` (§3.9 table, `achievableMaxKg` × implements), `PresetsTest` |
| **WP-03** | Planner, realizer, substitution, ledger, mutations | `core/src/main/kotlin/com/gymcoach/core/planner/{Planner,Normalizer,MesocycleBuilder,SplitSelector,ExerciseSelector,SetAllocator,TimeModel,AgePolicy,PlanValidator}.kt` (**not** `PlannerConstants.kt`); `core/src/main/kotlin/com/gymcoach/core/planner/realize/{SessionRealizer,ReadinessModifiers,LayoffPolicy,TimeConcessions,WarmupGenerator,CardioFinisher,RirStack,LoadZones}.kt`; `core/src/main/kotlin/com/gymcoach/core/substitution/{SubstitutionEngine,LoadTransfer}.kt`; `core/src/main/kotlin/com/gymcoach/core/volume/{VolumeAccountant,WeeklyLedger,MuscleTiers,TargetPolicy}.kt`; `core/src/main/kotlin/com/gymcoach/core/mobility/MobilityPlanner.kt`; `core/src/main/kotlin/com/gymcoach/core/mutation/{MutationValidator,OverrideResolver,PlanDiffer}.kt`; `core/src/test/kotlin/com/gymcoach/core/{planner,substitution,volume,mobility,mutation}/**` (except `PlannerConstantsShapeTest.kt`); `core/src/test/resources/golden/plans/*.json`; `core/src/test/resources/golden/inputs/*.json` | WP-01, WP-02 (signatures §3.9; may be developed against a hand-built mini knowledge base until WP-02 lands). **Never WP-04**: DLD-02/04 factors come from `PlannerConstants`, e1RM from WP-01's `E1rm`, the PLAT-02 ladder is advanced inside `Planner.rollWeek` (§4.11) | §2.15 and §4.2 signatures exactly (incl. `Planner.normalize` and `Planner.trainingAgeOf`, the Stage-0 entry points WP-05/WP-07 call); §4.3–4.8, 4.12–4.16 implemented with rule-id explanations incl. the §4.6 heavy-slot pre-pass for `backoff_of`; `SplitSelector` selects rows by (days, goal ∈ `for_goals`, trainingAge ∈ `for_training_ages`) with no code-side template substitution and the §4.5 one-day-fewer FRQ-03 fallback (`Explanation(SPLIT-01)`); `Planner.rollWeek` returns the advanced states in `WeekRollResult.states` with one `Explanation(PLAT-02)` per advance in `ladderNotes` (G6 step 2); `TargetPolicy.weekTargets(..., deloadTriggersActive)` holds every ramp while `inputs.deloadTriggersActive` is non-empty (`Explanation([VOL-08, DLD-03])`); `PlanValidator` invariants 1–18 incl. the invariant-17 case "NOVICE + STRENGTH + 3 days (FULL_BODY_3) validates with the FRQ-02 note"; E1, E2, E3, G2 (Amber session), G5 (travel) reproduced to the stated numbers (`PlanGeneratorGoldenTest`, `SessionRealizerTest`, `SubstitutionEngineTest`, `WeeklyLedgerTest`); 12 named golden cases incl. `age 62` (`AgePolicyTest`); `PlanInvariantPropertyTest` (seeded `java.util.Random(42)`, 500 profile × gym × goal combinations → validator passes or the generator reports why); `DeterminismTest` (two runs → identical `inputsHash`, identical ids, byte-identical JSON; no `IdGen` call in the packages HR-18 names); `MutationValidatorTest` covers every row of §4.15; `PlanDifferTest`; `ExerciseSelectorTest` asserts the §4.6 walk order; `LayoffPolicyTest` (§4.7 step 2 cases); `RirStackTest` (profile targets, last-week values, cap, fixed endurance/deload); `VolumeAccountantTest` (LEFT/RIGHT pairing); `WarmupGeneratorTest` (WU-02 rows by `LoadZones`, WU-05/06 branches, drill selection); `TimeConcessionsTest` (E2 ladder log `[L2]` — L1 skipped and unlogged because WU-05 already applies; L2 drops a required isolation slot to 0) |
| **WP-04** | Progression, readiness, check-ins, cardio, nutrition | `core/src/main/kotlin/com/gymcoach/core/progression/{ProgressionEngine,PlateauDetector,DeloadPolicy,ProgressSeries}.kt` (**not** `E1rm.kt`); `core/src/main/kotlin/com/gymcoach/core/readiness/{ReadinessScorer,RedFlags,SleepRules}.kt`; `core/src/main/kotlin/com/gymcoach/core/checkin/{CheckinBank,CheckinEvaluator}.kt`; `core/src/main/kotlin/com/gymcoach/core/cardio/{CardioPlanner,HeartRateZones,HiitProtocols}.kt`; `core/src/main/kotlin/com/gymcoach/core/nutrition/{EnergyEstimator,TdeeAdapter,WeightTrend,MacroTargets,SupplementAdvisor}.kt`; tests under `core/src/test/kotlin/com/gymcoach/core/{progression,readiness,checkin,cardio,nutrition}/` (except `E1rmTest.kt`) | WP-01, WP-02. **Never WP-03** (no cross reference either way) | §2.15 signatures exactly; §4.9, §4.11 (signals/reactive/explanations only), §5.1–5.6 exactly; G1 (bench mesocycle incl. the deload/re-entry fairness assertions), G2 (scores 58/50/32/56/100/0 and band edges 44/45/69/70), G3 (181.4; 109–127; 107–131; 140–172; 163–172), G4 (1,780 / 2,759 / 3,035 / 151 / 84 / 419 / 42 / 3.7; −150 → 2,609; cut-offs 10:00 / 14:00; creatine 5 g), G6 (plateau verdict; ladder advance is WP-03's) reproduced; `ProgressionEngineTest` (top hit/below/over-RIR × training age cadence; coarse two-hit rule; > 10 % jump → rep extension in both logging styles; RIR-05 correction; Amber/deload/re-entry/travel/capped/novice-gate block increments; "hotel day at rack max does not lower workingLoadKg"; LEFT/RIGHT pairing; input `SetLog`s provably unmodified; every decision emits an `Adjustment`); `PlateauDetectorTest` (unfair sessions never count); `DeloadPolicyTest` (onset/resolved dates; fires once; stale triggers; ≥ 2 triggers **and** ≥ 5 days); `CheckinEvaluatorTest` (every Q trigger); `RedFlagsTest`; `MacroTargetsTest` (G4 both sexes, the 55-kg floor case: 1,436 / 165 g / 28 g, the > 1.5 %/week review, AGE-01 1.9 g/kg at 45 y); `ProgressSeriesTest` (weight MA7, e1RM incl. dashed LOW points, volume rows, readiness, load × reps) |
| **WP-05** | Coach core | `core/src/main/kotlin/com/gymcoach/core/coach/wire/{WireTypes,RequestBuilder,SseParser,StreamAccumulator}.kt`; `core/src/main/kotlin/com/gymcoach/core/coach/{SystemPrompt,CoachContextBuilder,ToolSchemas,ToolContract,ToolExecutor,ProposalApplier,TranscriptCompactor,CoachTurnService,TurnEvent,PricingTable,SpendMeter,TokenEstimator,RedFlagScanner,SupplementCaveat}.kt`; `core/src/main/resources/gymcoach/coach/{system_prompt_v1,compaction_prompt_v1}.txt`; `core/src/test/kotlin/com/gymcoach/core/coach/**`; `core/src/test/resources/sse/*.sse`; `core/src/test/resources/coach/system_prompt_v1.sha256` | **WP-01, WP-02, WP-03** (compile-time: `KnowledgeBase`/`RuleBook.digestLine` for `SystemPrompt`/`ToolExecutor`, `MutationValidator`/`ValidationResult`/`PlanDiffer` for `ToolContract`/`ProposalApplier`, `WeeklyLedger` as a `CoachTurnInputs` field; lands after WP-03, §9.2). **Not WP-04**: `NutritionTargets`, `EnergyModel`, `TrainingAge` reach the service pre-computed inside `CoachTurnInputs` (WP-01 model types, built by `CoachViewModel`, §8.17) — WP-05 never calls a WP-04 class or `PlanCoordinator` | §2.15 signatures exactly incl. `CoachTurnInputs`; §6.3–6.13 implemented incl. the §6.6 mapping table, the `send(userText, inputs: CoachTurnInputs)` / `retryOnSonnet(userText, inputs)` contract (`ContextInputs` derived from it per §6.5; `MutationValidator(knowledge, inputs.constants)` built per turn; `skipRedFlagScan`), the chain persistence of §6.7 and the HOME_BRIDGE reduction; R1 §3.6 fixtures parse byte-for-byte (`SseParserTest`, `SseParserFuzzTest` split-at-every-offset); `StreamAccumulatorTest` (text, tool_use with empty first delta, thinking signature-only, `redacted_thinking`, cumulative usage, mid-stream error, unknown type counted); `RequestBuilderTest` (tool_result first for every tool_use, echo unchanged, no trailing assistant, `cache_control` present, no sampling params, `metadata` omitted when blank, compaction/test bodies without tools); `SystemPromptTest` (SHA-256 matches, no volatile content, every digest rule id in `rules.json`, ≥ 512 tokens); `CoachContextBuilderTest` (budget/truncation order; byte-stable key order; delta contains only changes; `today_session` null vs given; `nutrition`/`ledger` rendered when given, `null` keys when absent); `ProposalSchemasTest` (20 schemas, keyword whitelist, `additionalProperties:false`, required ⊆ properties, one sample per tool through the mapping table); `ToolContractTest` (parse/validate each tool incl. every rejection path with `is_error`, `SetLimitation` wrapping with the clock date); `ToolExecutorTest` (gym_id default = today's gym; availability against `SessionEquipment(today)`); `ProposalApplierTest` (never touches sets; emits adjustments; diff; TODAY scope sets `expiresOn`); `CoachTurnServiceTest` with a fake backend replaying fixtures (end_turn; tool_use → pending → applied → next request carries tool_result first; mixed read-only + proposal turn; four read-only turns → budget error; chain persistence seq 1..6 with byte-identical prefix; refusal discards; failure persists nothing; offline fails fast; compaction trigger and seeding via `complete`; retryOnSonnet; "`ContextInputs` built from `CoachTurnInputs` carries the given ledger/nutrition/trainingAge"; "proposal validated with the `constants` of the inputs"; "red-flag hit → `Done(\"red_flag\", assistantMessageId = null)` and nothing sent; `skipRedFlagScan = true` sends"); `TranscriptCompactorTest`; `PricingTableTest` ($0.02175); `RedFlagScannerTest`; `SupplementCaveatTest` |
| **WP-06** | App data, network, secure storage | `app/src/main/kotlin/com/gymcoach/app/data/db/{AppDatabase,Entities,Daos,Migrations}.kt`; `app/src/main/kotlin/com/gymcoach/app/data/repo/{RoomProfileRepository,RoomGymRepository,RoomPlanRepository,RoomWorkoutRepository,RoomProgressionRepository,RoomMetricsRepository,RoomCoachRepository,Mappers,EnumNames,DocVersions,InMemoryUnreadableDocs}.kt`; `app/src/main/kotlin/com/gymcoach/app/coordinator/PlanCoordinator.kt`; `app/src/main/kotlin/com/gymcoach/app/data/prefs/{DataStores,SettingsDataStore}.kt`; `app/src/main/kotlin/com/gymcoach/app/data/secure/{KeystoreCipher,KeystoreApiKeyStore}.kt`; `app/src/main/kotlin/com/gymcoach/app/data/assets/AssetKnowledgeSource.kt`; `app/src/main/kotlin/com/gymcoach/app/network/AndroidNetworkMonitor.kt`; `app/src/main/kotlin/com/gymcoach/app/network/claude/{DirectApiBackend,RetryPolicy,HttpErrorMapper,SseBodyReader}.kt`; `app/src/main/kotlin/com/gymcoach/app/network/bridge/{BridgeBackend,NotConfiguredBackend}.kt`; `app/src/test/kotlin/com/gymcoach/app/data/{MappersTest,SettingsMappingTest}.kt`; `app/src/test/kotlin/com/gymcoach/app/network/{HttpErrorMapperTest,RetryPolicyTest}.kt`; `docs/bridge/PROTOCOL.md` | WP-01; **compile-time** WP-02 (`KnowledgeBase`), WP-03 (`Planner`, `Normalized`) and WP-04 (`ProgressionEngine`, `DeloadPolicy`) through `PlanCoordinator` (§7.6) — the data/network/secure files may be written from day one against the §2.15/§3.9/§4.2 signature contracts, but the WP merges (and `:app` compiles) only after WP-02/03/04 have landed (§9.2) | §7.1 entities/DAOs exactly (package + imports as printed; `nutrition_state` on `PlanDao`; `lastLoggedAtByExercise`; `tool_results_json`); entities primitive-only; `supersede` never updates values; every `core.repo` interface implemented with the constructor `AppContainer` expects (incl. `unreadableDocs`, `NetworkMonitor`, `UnreadableDocsRepository`); `DocVersions` policy per §7.2 incl. the `schema_version` **columns** of `gyms`, `overrides`, `chat_conversations`, `proposals` (compared with `KNOWN`, never reset on read) and the `is_default` column of `chat_conversations` (`CoachDao.open()` filters `is_default = 1`); `PlanCoordinator` per §7.6 with the explicit constructor (`deloadPolicyFactory` included; no `AppContainer` import): `realizeToday` pure (no DLD-03 evaluation, no writes); `rollWeekIfDue` persists `WeekRollResult.states` via `saveAll`, records one PLAT-02 `Adjustment` per `ladderNotes` entry, then runs the DLD-03 orchestration (`DeloadPolicy.signals`/`reactive`, `deloadTriggers`/`proposals` filled, onsets recorded) before `recompute`; `buildInputs` fills `PlanInputs.deloadTriggersActive` from `DeloadPolicy.signals(...).active(...)`; `deloadSuggestion(today)` a pure read; `recordDeloadDecision(today)` the only other writer of `deloadTriggersProposed`; `plannerProposals(today)` pure and memoised per `inputsHash`; `DataStores.kt` holds the only two `preferencesDataStore` delegates; `SettingsDataStore` per §7.3 (two keys, `coachUserId` generation); `KeystoreCipher` per §7.4 with the failure policy; `AndroidNetworkMonitor` (`NET_CAPABILITY_VALIDATED`, `registerDefaultNetworkCallback`); `DirectApiBackend`: headers/body per §6.2, `callbackFlow` with cancellation, `SseBodyReader` raw-chunk feeding of `:core` `SseParser` + `flush()`, `complete()` and `test()` → `TestResult`, request-id captured; `HttpErrorMapper` covers the full §6.10 table incl. 408/409; `RetryPolicy` exactly per the §6.10 table (`RetryPolicyTest` covers every row); `NotConfiguredBackend` yields `BackendError.NotConfigured`; `BridgeBackend` per §6.13 (reduced protocol); key never logged; `MappersTest` round-trips all 19 tables (incl. `ChatConversation.isDefault = false` and every columnar `schemaVersion`) and the newer-version → `Unreadable` path for JSON and columnar documents alike; compile-risk list names every Room/DataStore/OkHttp/Keystore/ConnectivityManager symbol |
| **WP-07** | Training UI | `app/src/main/kotlin/com/gymcoach/app/ui/today/{TodayScreen,TodaySections,TodayViewModel,GymSwitcherSheet,SessionStartSheet,CardioLogSheet}.kt`; `app/src/main/kotlin/com/gymcoach/app/ui/readiness/{ReadinessScreen,ReadinessViewModel}.kt`; `app/src/main/kotlin/com/gymcoach/app/ui/session/{SessionScreen,SessionSections,SessionViewModel,SetLogger,RestBar,PlateCalculatorSheet,SwapSheet,ExerciseInfoSheet,FinishSheet,RedFlagSheet,KeypadSheet,SessionSummaryScreen,SessionSummaryViewModel}.kt`; `app/src/main/kotlin/com/gymcoach/app/ui/plan/{PlanScreen,PlanSections,PlanViewModel,PlanSessionScreen,AdjustmentLogScreen,AdjustmentLogViewModel}.kt`; `app/src/main/kotlin/com/gymcoach/app/ui/progress/{ProgressScreen,ProgressSections,ProgressViewModel,ExerciseProgressScreen,ExerciseProgressViewModel}.kt`; `app/src/main/res/values/strings_training.xml`; `app/src/test/kotlin/com/gymcoach/app/ui/{TodayViewModelTest,SessionViewModelTest,PlanViewModelTest}.kt`; `app/src/test/kotlin/com/gymcoach/app/fakes/FakeRepositories.kt` (in-memory implementations of every `core.repo` interface incl. `FakeNetworkMonitor` and `FakeUnreadableDocs`) | WP-01; reads WP-03/04/06 signatures (§2.15) | §8.6–8.11 exactly; every UiState of §8.6–8.11 as declared; "Log set writes exactly the displayed values" (ViewModel test with `FakeRepositories` + `FixedClock`); one `side = BOTH` row per unilateral set; corrections supersede; every modifier/adjustment visible with `RuleTag`s; find-load and PROG-04 chips never auto-apply; RECOVERED flow via `AppSettings.sessionDraftJson`; `TodayContext` persisted via `AppSettings.todayContextJson`; steps entry; cardio-log sheet; all user-facing strings in `strings_training.xml`; `PlanCoordinator` only via `container.planCoordinator` (`realizeToday`, `deloadSuggestion`, `plannerProposals` → `TodayUiState.plannerProposals` / `PlanUiState.proposals`; `onAcceptDeload` = `ScheduleDeload` override → `recordDeloadDecision(date)` → `recompute("deload")`, `onDismissDeload` = `recordDeloadDecision(date)` only — `TodayViewModelTest` asserts both orders against a `PlanCoordinator` built on `FakeRepositories`); no `androidx.room`/`okhttp3` imports; `@file:OptIn` per HR-3; each file ≤ 400 lines; compile-risk list |
| **WP-08** | Setup, health, coach, settings UI | `app/src/main/kotlin/com/gymcoach/app/ui/onboarding/{OnboardingScreen,OnboardingPages,OnboardingViewModel}.kt`; `…/ui/profile/{ProfileScreen,ProfileViewModel}.kt`; `…/ui/gyms/{GymsScreen,GymEditScreen,EquipmentPicker,GymsViewModel,GymEditViewModel}.kt`; `…/ui/library/{LibraryScreen,ExerciseDetailScreen,LibraryViewModel}.kt`; `…/ui/checkin/{CheckinScreen,CheckinViewModel}.kt`; `…/ui/nutrition/{NutritionScreen,NutritionSections,NutritionViewModel}.kt`; `…/ui/knowledge/{KnowledgeScreen,RuleDetailScreen,KnowledgeViewModel}.kt`; `…/ui/coach/{CoachScreen,ConversationScreen,CoachViewModel,MessageBubble,ContextSheet,QuickPrompts}.kt`; `…/ui/settings/{SettingsScreen,BackendSettingsScreen,ApiKeyEntry,AdvancedConstantsScreen,SettingsViewModel}.kt`; `…/ui/usage/{UsageScreen,UsageViewModel}.kt`; `…/ui/more/MoreScreen.kt`; `app/src/main/res/values/{strings_setup,strings_backend_policy}.xml`; `app/src/test/kotlin/com/gymcoach/app/ui/{GymEditViewModelTest,CoachViewModelTest,OnboardingViewModelTest}.kt` | WP-01; reads WP-02/04/05/06 signatures (§2.15); uses WP-07's `FakeRepositories` in tests (read-only) | §8.5, §8.12–8.19 exactly incl. every UiState declared there and the §8.17 `CoachPhase` verbatim; onboarding persists the preset gym before `GymEditRoute` and ends with a generated plan (via `container.planCoordinator`); drafts in `AppSettings.onboardingDraftJson`; gym editor exposes every R4 param with live availability; DE/EN alias search; R1 §2.5 copy verbatim in `strings_backend_policy.xml`; all other user-facing strings in `strings_setup.xml`; key prefix rule; streaming render; `CoachViewModel.onSend` builds the §2.15 `CoachTurnInputs` exactly as §8.17 lists (`buildInputs()`, `constants()`, `getCurrent()`, `Planner.normalize(...).trainingAge`, `WeeklyLedger.build`, `MacroTargets`/`EnergyEstimator`/`WeightTrend`); check-in "Done" stores `WeeklyCheckin.holds` from `CheckinOutcome.holds` (§8.15); proposal cards wired to `ProposalApplier`; offline banner + queued draft via `container.networkMonitor`; "Test connection" via `ClaudeBackend.test()` showing `TestResult`; "Retry on Sonnet 5" flow; Advanced editor walks `PlannerConstants.leafFields()` and writes nested overrides JSON; Knowledge screen from `RuleBook`; `CoachViewModelTest` with a fake backend + `FakeNetworkMonitor` (card appears; Apply → next request carries "applied" first; Reject → "rejected"; read-only auto follow-up capped; offline queued draft sent on reconnect) |
| **WP-09** | Content batch 1 — chest & triceps (26) | `core/src/main/resources/gymcoach/knowledge/exercise_content/batch_1.json` (created by this WP from the §3.2 shape) | WP-01, WP-02 (for `ContentBatchesTest` and `exercises.json`; the file may be authored in parallel and merged after WP-02) | R4 §5.12–5.13 and §3.2 rules: every id of the batch authored; description 2–4 sentences; setup 2–4; cues 3–6; mistakes 3–5; safety 1–4 reflecting every set flag; substitution rationale per listed substitution; ≥ 1 source per family; `status: draft`; `KnowledgeValidationTest` and `ContentBatchesTest` green (strict JSON) |
| **WP-10** | Content batch 2 — shoulders, traps, rotator cuff, shoulder mobility (25) | `…/exercise_content/batch_2.json` (created by this WP) | WP-01, WP-02 | same |
| **WP-11** | Content batch 3 — back, biceps, forearms, thoracic mobility (25) | `…/exercise_content/batch_3.json` (created by this WP) | WP-01, WP-02 | same |
| **WP-12** | Content batch 4 — quads, squat/lunge, knee isolation, calves, hip mobility (26) | `…/exercise_content/batch_4.json` (created by this WP) | WP-01, WP-02 | same |
| **WP-13** | Content batch 5 — hinge, glutes, hamstrings, adductors/abductors, hip & ankle mobility (26) | `…/exercise_content/batch_5.json` (created by this WP) | WP-01, WP-02 | same |
| **WP-14** | Content batch 6 — core, carries, conditioning, cardio, general warm-up (28) | `…/exercise_content/batch_6.json` (created by this WP) | WP-01, WP-02 | same |

Batch id lists are R4 §5.12 verbatim (each content WP writes its own file with exactly those ids;
authors never change ids or numbers; disagreements go into `review_notes`; `ContentBatchesTest`
checks the id sets and that no id is in two files).

### 9.1 Placeholder screens (how the shell compiles before WP-07/08 land)

WP-01 creates every screen file listed for WP-07/WP-08 with a single placeholder composable of the
frozen signature (`@Composable fun TodayScreen(container: AppContainer, navController: NavHostController) { Text("Today") }`).
Those files are **owned by WP-07/WP-08** from Phase 1 on (they overwrite them); WP-01 does not touch
them after tagging `v0-interfaces`. This is the one deliberate hand-over of file ownership. (The
content batch files have **no** hand-over: WP-02 never creates them, §3.2.)

### 9.2 Merge order and gates

1. WP-01 → tag `v0-interfaces` (`:core:test` green with the model/serializer tests,
   `PlannerConstantsShapeTest` and `E1rmTest`).
2. Phase 1 parallel. Merge gate for every `:core` WP: `gradlew :core:test` green. Merge gate for
   `:app` WPs before the SDK exists: the grep checks of §1.4, the compile-risk list, and their JVM
   tests written (they run at first build).
3. Landing order (compile-time dependencies, §9 "Depends on"): WP-02 → (WP-03, WP-04 in
   parallel; they never reference each other) → WP-05 (needs WP-02 and WP-03 merged) and WP-06
   (its `PlanCoordinator` compiles against WP-02's `KnowledgeBase`, WP-03's `Planner` and WP-04's
   `ProgressionEngine`/`DeloadPolicy` — the WP is authored from day one against the signature
   contracts and merges once WP-02/03/04 are in) → (WP-07, WP-08) → WP-09…WP-14 continuous (each
   may be authored from day one; merged after WP-02).

### 9.3 Phase 2 — integration (one agent)

Replace nothing — `AppContainer` already names the real classes. Run `:core:test`; run the grep
checks (`import androidx.room` / `import okhttp3` under `app/src/main/kotlin/com/gymcoach/app/ui`
must be empty; `import android` under `core/src/main` must be empty); assemble every WP's
compile-risk list into `docs/build/COMPILE_RISK_LIST.md`; run `gradlew :app:assembleDebug` on the
first machine with the SDK (Appendix A); fix shallow compile errors starting from the list; commit
`app/schemas/…/1.json`; run the manual gym-floor script (Appendix B).

### 9.4 WP-V1.1 — session runtime module (after the first successful `:app` compile; not part of the v1 parallel run)

Owns `app/src/main/kotlin/com/gymcoach/app/session/{WorkoutSessionService,RestTimer,SessionNotification}.kt`,
`…/notify/{Channels,NotificationCenter,RestAlarmReceiver,BootReceiver,ExactAlarmHelper}.kt`,
`…/work/{ReadinessReminderWorker,WeeklyCheckinWorker,CardioReminderWorker,ReminderScheduler}.kt`,
`…/backup/{BackupJson,ExportImport}.kt`, the manifest additions and `app/build.gradle.kts`'s
`work-runtime` line. Scope (B graft, rules verified by proposal B on 2026-09-05): a foreground
service (`foregroundServiceType="specialUse"` with the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property;
`startForeground(id, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)` on API ≥ 34, two-argument
form below) running from Start to Finish with an ongoing notification whose rest countdown is
rendered by the system (`setUsesChronometer(true)`, `setChronometerCountDown(true)`,
`setWhen(restEndsAtMillis)`), a rest-over alert on a HIGH channel, "+30 s" / "Skip rest" actions
(no one-tap logging from the notification — a logged set must confirm actual reps), an optional
exact-alarm backup (`setExactAndAllowWhileIdle`, `SCHEDULE_EXACT_ALARM` special access, off by
default), `POST_NOTIFICATIONS` requested in context, WorkManager reminders (readiness on training
mornings at `readinessReminderTime`, default 07:30; weekly check-in; cardio slots; all inexact by
design, never used for the rest timer), and SAF export/import (`ACTION_CREATE_DOCUMENT` /
`ACTION_OPEN_DOCUMENT`, `BackupJson` v1 with every table's rows verbatim, never the API key;
import preview then Replace / Merge). Acceptance: manual script steps 5–6 (Appendix B).

---

## 10. Test plan

### 10.1 Gates

- `gradlew :core:test` green is the merge gate for every WP that touches `:core` (JDK 17 only).
- `gradlew :app:testDebugUnitTest` (plain JVM) gates mappers, error mapping, routes, ViewModels —
  written now, executed at first build.
- Instrumented tests are written after the first successful `:app:assembleDebug`.
- Golden fixtures regenerate only with `-Dgymcoach.regenerateGolden=true`; every diff is explained
  in the PR (canary discipline).

### 10.2 `:core` inventory (target ≥ 300 test methods)

| Area | Test classes | Pins down |
|---|---|---|
| Model (WP-01) | `ModelRoundTripTest`, `SerializersTest`, `UnitsTest`, `WeeksTest`, `NoAndroidImportsTest`, `PlannerConstantsShapeTest`, `E1rmTest` | every `@Serializable` type (§2.14 `Views.kt` and every `PlannerConstants` group included) round-trips byte-identically through `CoreJson.stored`; enum serial names; `[lo,hi]` bands; ISO dates/instants; kg↔lb at known points (135 lb → 61.235 kg; entered value kept); no `import android` under `core/src/main`; every `PlannerConstants` group descriptor < 200 JVM argument slots, nested `withOverrides` merge, `ruleIdOf`/`constantKeyOf` examples of §4.1; e1RM rows (106.7, 125.4, 121.7, LOW above 10, interpolation at every table row) |
| Knowledge | `KnowledgeValidationTest` (rules 1–11 as separate methods), `RuleBookTest`, `ContentBatchesTest`, `PresetsTest` | unique ids; every substitution / profile / equipment ref resolves; groups do not nest; every exercise available in `base_gym` or tagged `optional_equipment`; `hotel_gym` ≥ 40; travel kit + nothing ≥ 25; weights ∈ {1, 0.5, 0.25} with 1–2 prime movers; SP ⇒ `rack_or_spotter`/`rack_any`/smith option and the barbell bench variants name `rack_or_spotter` in every option (§3.1); compound ⇒ ≥ 2 muscles; strict decode → encode → decode equality; batch coverage = 156 (`ids_v1.txt`); no id removed without `renamed_ids`; templates reference existing patterns/muscles, every `backoff_of` points at a `heavy` slot and, per split row, resolves to a template listed in that SAME row (rule 10 — the 4-, 5-, 6- and 3-day strength rows each checked); `RuleIds.ALL ⊆ rules.json` incl. `POL-03`, `DEF-06`, `DEF-07`, `NUT-18` (rule 11); every `PlannerConstants` **leaf** ↔ rule entry (`ruleIdOf`) with a constant under `constantKeyOf` (Band `.lo/.hi`, List `.0/.1…`) of equal value, and every constant of a rule maps back to a leaf; weak/contested/unverified entries are `user_adjustable` (by the normalised tag); every `rules.json` entry decodes under `CoreJson.assets` and every entry whose `note` names a composite R3 tag (`"R3 tag: "`) carries the §3.5 normalised `confidence` (`RuleBookTest`); existing batch files have exactly the R4 §5.12 ids, no id in two files, loader tolerates missing files |
| Equipment | `EquipmentResolverTest`, `IncrementResolverTest`, `LoadRounderTest` | §3.9 semantics incl. bench unavailable without safeties/spotter; §4.9 increment table incl. novice/female/> 100 kg lower-barbell rule and microplates; `implements` (pair 2 / single 1) and the per-hand vs pair-total > 10 % rule in both logging styles; rounding to plates/dumbbells/stack/kettlebell/assist; `achievableMaxKg` × implements; > 5 % → `ROUNDING` explanation |
| Volume | `VolumeAccountantTest`, `WeeklyLedgerTest`, `MuscleTiersTest`, `TargetPolicyTest` | R4 §4.2 worked rows (bench, squat, deadlift, cable row, pulldown); LEFT/RIGHT pairing and the unpaired 0.5 rule (UNI-02); back aggregate counted once; E1 ledger table; carryover respects VOL-06 / per-exercise caps / budget; no cross-week carryover; tier assignment incl. priority promotion; VOL-08 hold against *prescribed* RIR with 0.5 tolerance and the all-muscle hold while `deloadTriggersActive` is non-empty; FRQ-03 single-day targets = VOL-01; AGE-02 novice band at 60 |
| Planner | `NormalizerTest`, `SplitSelectorTest`, `MesocycleBuilderTest`, `ExerciseSelectorTest`, `SetAllocatorTest`, `TimeModelTest`, `AgePolicyTest`, `PlanValidatorTest`, `PlanGeneratorGoldenTest`, `PlanInvariantPropertyTest`, `DeterminismTest` | DEF-01 incl. layoff recency (`Planner.trainingAgeOf`); `Planner.normalize` equals Stage 0 of `plan`; SPLIT-01 row selection by (days, `for_goals`, `for_training_ages`) incl. the exhaustive coverage check of §3.4 and the one-day-fewer FRQ-03 fallback with `Explanation(SPLIT-01)`, SPLIT-02 rotation and the STRENGTH per-split `_S` rows (`_S4`/`_S5`) with the §4.6 heavy-slot pre-pass (a Tuesday back-off resolves to Friday's heavy pick; `ul_6` repeats the same main lift); G6 step 2 state returned by `rollWeek` (`plateauStep` 2, `repZoneShift` 1 in `WeekRollResult.states` with its `ladderNotes` entry); every ramp held while `deloadTriggersActive` is non-empty (`Explanation([VOL-08, DLD-03])`); week modes/offsets incl. NOVICE first-vs-later mesocycle deload rows; pinned/excluded/difficulty gating; SEL scoring terms and the weekday/template walk order (`ledgerSoFar` order-dependence); allocation never exceeds minutes (E1: 50.0 / 48.37 / 50.0 / 48.17); soft RP cap never blocks reaching a target; REBAL moves stay inside the slot pattern list; under-target report; time costs incl. supersets and unilateral (hip thrust 2.5); AGE-02 sets/reps/rest/single-set start/power slot/balance block and AGE-03 gate; each invariant 1–18 with a hand-built violating plan (17: both branches — a strength row whose back-off slot holds another exercise fails, and "NOVICE + STRENGTH + 3 days (FULL_BODY_3) validates with the FRQ-02 note" passes while the same plan without the note fails; 18: duplicate `PlannedSession.id` in a `ppl_6` week); 12 golden cases (E1 home U/L 4; E3 commercial FB3 novice; advanced 6-day PPL; strength 4-day with back-off days (invariant 17); fat-loss 5-day with history; hotel-only; dumbbells-only; bodyweight + band; 30-min sessions; knee limitation pain 5; shoulder limitation pain 2; age 62: female 1964, INTERMEDIATE, GENERAL_FITNESS, 3 days × 60 min, `base_gym` — sets band [2,3] on every slot, compound reps 7–9, rest ≤ 120 s, one power slot, two balance cool-downs, no MAX_STRENGTH, protein 1.9 g/kg) |
| Realizer | `SessionRealizerTest`, `TimeConcessionsTest`, `ReadinessModifiersTest`, `LayoffPolicyTest`, `RirStackTest`, `LoadZonesTest`, `WarmupGeneratorTest`, `MobilityPlannerTest` | G2 Amber/Red outputs incl. the RIR cap only on Red-in-week-1; E2 ladder (44.7 → L2 → 39.5, required isolation slot dropped to 0); TODAY-scope swap override honoured today and ignored tomorrow; LAY-01 by *excess* days (E1 week-2 bench: none; 9-day gap on a twice-weekly pattern: row 1; 100-day pattern gap via `patternLastTrained`: row 4) and age; REENTRY adds no second +1; RIR floors on SP lifts on a rack without safeties; `profileTargetRir` = ceil(mid), last week = `rir_last_week.hi`, fixed endurance/deload values; `LoadZones.of` midpoint and %1RM branches (bench 5–10 → HEAVY); WU-02 percentages by zone, WU-05/06 switches, drill selection; chronic dose and MOB-03 credit |
| Substitution | `SubstitutionEngineTest`, `LoadTransferTest` | E2 score table (0.725 > 0.649 > 0.601 > 0.581); merge rule; ledger delta; seeds and bodyweight fractions |
| Mutation | `MutationValidatorTest`, `OverrideResolverTest`, `PlanDifferTest` | every row of §4.15 (reject / warn / accept) incl. the NUT-05/06 kcal bounds and the two-week deload guard; later-wins, exclude-beats-pin, revoke; diff content |
| Progression | `ProgressionEngineTest`, `PlateauDetectorTest`, `DeloadPolicyTest`, `ProgressSeriesTest` (`E1rmTest` is WP-01's, above) | G1 sequence incl. weeks 7–8 fairness assertions; cadence by training age; PROG-05 novice gate; coarse two-hit rule; PROG-04 both clauses; > 10 % rule per hand; RIR-05 audit; Amber/deload/re-entry/travel/capped block increments; "hotel day at rack max does not lower workingLoadKg"; PLAT-01/03 count only fair NORMAL-week sessions, `bestE1rm14d` excludes deload weeks; DLD-03 onset/resolved dates, ≥ 2 triggers ≥ 5 days, fires once, stale triggers ignored; input sets unchanged |
| Readiness / check-in | `ReadinessScorerTest`, `CheckinBankTest`, `CheckinEvaluatorTest`, `RedFlagsTest`, `SleepRulesTest` | G2 scores and band edges; renormalisation; RHR/illness/alcohol overrides; selection ≤ 8 with rotation guarantees; auto-answers incl. Q5 from `BodyMetric.steps`; every Q trigger; RED-01 vocabulary and hand-off; SLP-03 arithmetic |
| Cardio / nutrition | `HeartRateZonesTest`, `CardioPlannerTest`, `HiitProtocolsTest`, `EnergyEstimatorTest`, `TdeeAdapterTest`, `WeightTrendTest`, `MacroTargetsTest`, `SupplementAdvisorTest` | G3 zones; CAR-07 per goal; CAR-06 placement (no running before legs, ≥ 6 h); HIIT gating (base weeks, medical); G4 numbers for both sexes; the 55-kg floor case (kcal 1,436, carbs 165 g, fat 28 g, NUT-08 explanation); deficit ≤ 20 % TDEE and ≥ REE; > 1.5 %/week review → +200 kcal + supervision text; AGE-01 1.9 g/kg; clamp ±150; ≥ 10 kcal logs required; travel weeks skipped; MA7 and noise flag; NUT-01 branches incl. FFM and ≥ 65 floor; fat floor; fibre floors; caffeine cut-offs; kidney flag → refer; SUP-12 always attached; `SupplementAdvice` rows gated under RED-03 |
| Coach | `SseParserTest`, `SseParserFuzzTest`, `StreamAccumulatorTest`, `RequestBuilderTest`, `SystemPromptTest`, `CoachContextBuilderTest`, `ProposalSchemasTest`, `ToolContractTest`, `ToolExecutorTest`, `ProposalApplierTest`, `CoachTurnServiceTest`, `TranscriptCompactorTest`, `PricingTableTest`, `SpendMeterTest`, `TokenEstimatorTest`, `RedFlagScannerTest`, `SupplementCaveatTest` | as listed in §9 WP-05 (mixed read-only + proposal turn; budget-exhausted error after 3 follow-ups; chain persistence seq 1..6; tool mapping table; `SetLimitation` wrapping; offline fail-fast; `retryOnSonnet`; compaction via `complete`) |
| Coverage (WP-01) | `RuleCoverageTest` | reads `core/src/main` and `core/src/test`; every rule id referenced in main code appears in at least one test name or assertion message; every `RuleIds` constant referenced somewhere in main (`RuleIds.ALL` is the id list, not a constant reference, and is excluded from the `RuleIds.X` matches); `RuleIds.ALL` contains every constant declared in `RuleIds` |

### 10.3 `:app` JVM unit tests (written now, run at first build)

`MappersTest` (19 tables), `SettingsMappingTest` (Preferences → `AppSettings` defaults, corrupt
store → defaults), `HttpErrorMapperTest` (status × body → `BackendError`, spend-cap detection,
`retry-after` parsing), `RetryPolicyTest`, `RoutesTest` (routes serialise), `TodayViewModelTest`,
`SessionViewModelTest` (log → supersede → finish → progression call; displayed values == stored
values; PROG-04 chip not auto-applied), `PlanViewModelTest`, `GymEditViewModelTest` (live
availability count), `OnboardingViewModelTest` (draft persistence, validation clamps),
`CoachViewModelTest` (fake backend replaying `sse/*.sse`: card appears; Apply → "applied" first
in the next user message; Reject → "rejected"; read-only auto follow-up capped at 3; offline
queued draft sent when `FakeNetworkMonitor` flips to online) — all with `FakeRepositories`
(in-memory implementations of every `core.repo` interface incl. `FakeNetworkMonitor`,
`FakeUnreadableDocs` and an in-memory `SettingsRepository` holding the `AppSettings` drafts) and
`FixedClock`.

### 10.4 Golden files and fixtures

- `core/src/test/resources/golden/inputs/<case>.json` (`PlanInputs`) and
  `golden/plans/<case>.json` (`Mesocycle`) — the twelve cases of §10.2; E1/E2/E3 numbers are
  asserted explicitly in addition to the byte comparison.
- `core/src/test/resources/sse/*.sse` — R1 §3.6 verbatim examples (text turn, tool-use turn with an
  empty first delta, thinking with signature only, mid-stream overload, refusal, ping, unknown event,
  CRLF variant, multi-line data) plus one recorded real stream once the first live call has been
  made from Android Studio (key stripped).
- `core/src/test/resources/coach/system_prompt_v1.sha256` — updated only with an explicit
  `PROMPT_VERSION` bump.
- The production knowledge JSON is used directly by tests (no copies).

### 10.5 Instrumented tests (after the first compile)

`AppDatabaseTest` (create v1, insert/read each table), `MigrationTest` scaffold with
`MigrationTestHelper` (empty at v1), `KeystoreCipherTest` (round trip; tampered ciphertext → null;
missing alias → null), `SmokeComposeTest` (launch, onboarding renders), `SessionScreenTest`
(prefilled draft; stepper steps by the increment; Log writes the displayed values; swap sheet
ranking) with `ui-test-junit4` 1.12.0 (BOM). **Deliberately absent from §1.7 until these land**
(Appendix A step 7a): `androidTestImplementation` of `androidx.test.ext:junit 1.3.0`,
`androidx.test.espresso:espresso-core 3.7.0`, `androidx.room:room-testing 2.8.4`,
`androidx.compose.ui:ui-test-junit4` and `debugImplementation androidx.compose.ui:ui-test-manifest`
(BOM-managed) — the Phase-2 integrator adds them together with the first instrumented test so the
v1 build files stay exactly R2 §5.

### 10.6 Copy review (acceptance items, not automated)

POL-02: no diagnosis language in `strings.xml`, `strings_training.xml`, `strings_setup.xml`, the
system prompt, the RED/INJ texts or the Knowledge screen (screens hard-code no user-facing
literals — every string lives in one of the three files, so the review is complete); R1 §2.5
policy text verbatim; SUP-12 caveat verbatim; the NUT-05 "professional supervision" sentence is
advice, not a diagnosis; every weak constant shown with "app default, adjustable".

---

## 11. Requirement coverage matrix

| REQUIREMENTS.md item | Spec sections |
|---|---|
| 1 Training plans and progress tracking | §4 (planner), §4.9–4.11 (progression), §8.6–8.11 (Today/Session/Plan/Progress), §7 (persistence) |
| 2 Per-exercise explanation, form cues, mistakes, safety | §3.2 (content overlays), §8.8 info sheet, §8.12 detail, WP-09…WP-14 |
| 3 Equipment of the day; base set + additions | §2.5 (`GymSet`, `TodayContext`, `SessionEquipment`), §3.3, §3.9 (`EquipmentResolver`), §4.7 step 1, §8.6 gym switcher / session-start sheet |
| 4 Named gym presets | §3.3 presets, §3.9 `Presets`, §8.13 gym editor, §2.5 `GymSet.presetOrigin` |
| 5 Profile data and goals; plan adapts | §2.5 `Profile`, §4.3 (normalize), §4.4 (targets by goal/age), §8.5, §8.14 |
| 6 Chat with Claude, natural-language adaptation | §6 (coach), §8.17 |
| 7 Cardio, nutrition, supplements, check-ins, adaptation | §5.4 cardio, §5.5 nutrition, §5.6 supplements, §5.2 check-ins, §4.4 VOL-08, §5.2 triggers |
| 8 Warm-up and mobility every session | §5.7 warm-up, §5.8 mobility, §4.7 steps 8/10 |
| 9 Latest evidence; replace a trainer | §3.5 rules.json with confidence + citation, §4.1 constants provenance, §6.4 knowledge digest, §8.16 Knowledge screen |
| Platform: Android, Kotlin, Compose, M3, minSdk 26, target latest | §1.1, §1.7 |
| Offline-first; only chat needs network | §1.1, §8.1, §6.10 offline row, Appendix C matrix |
| Two modules `:core` / `:app` | §1.1–1.4 |
| Compile-conservative code | §1.6 house rules, §9 compile-risk lists, Appendix A |
| Pluggable backend; API key in Keystore; optional home bridge; honest policy UI | §6.1, §6.13, §7.4, §8.18 |
| Coach can ACT with structured, confirmed changes | §2.8 mutations, §2.15 service contracts, §4.15 validation, §6.6 tools + mapping table, §6.7–6.8 flow (mixed turns, TODAY-scope swaps), §8.17 cards |
| Units metric default, imperial toggle; English UI | §2.3 `EnteredWeight`, §2.12 `Units`, §8.2, HR-20 |
| Science citations in the knowledge base | §3.5, §3.8, §6.4, §8.16 |
| Sacred-content discipline | §2.7 `SetLog` (append-only, supersede), §2.7 `Adjustment`, §4 governance, §6.12, §7.1 (`superseded_by_id` the only updated column), §8.1 principle 4, §8.8 "Log set writes exactly the displayed values" |

---

## 12. Open questions for Johannes and unverified items

Only the user can answer (defaults chosen so implementation proceeds):

1. **Home gym inventory** (R4 §7.4): the `home_gym` preset is a generic garage set; E1 assumes it.
   Default: the preset; Johannes edits in the gym editor.
2. **Budget / model**: Opus 5 at `effort: low` (≈ $20–45/month) or Sonnet 5 economy (≈ $8–17)?
   Default: Opus 5 low.
3. **Home bridge**: build the PC side in v1.1, or drop it? Default: phone-side adapter only, off.
4. **Session-cap for time-tight weeks**: when 4 × 60 min cannot fill the intermediate band (E1
   under-target report), is "accept" the default, or 5th short day / 65-min sessions? Default:
   accept (quiet card).
5. **Advanced exercises** (front squat, good morning, Nordic, push press, sissy, pistol): opt-in
   at onboarding? Default: off.
6. **Body-fat estimate** available (drives the FFM protein branch, NUT-01/05)? Default: none.
7. **Imperial** ever needed (kept as a toggle)? German UI release? Default: metric; DE later
   (aliases already DE/EN).

Unverified items carried from research (all tagged in `rules.json`/`Confidence.UNVERIFIED` or
noted in code): VOL-06 per-session inflection (R3 §12.1); Fragala 2019 %1RM values (§12.2);
Kerksick 2018 category table (§12.3); Garber 2011 vigorous bands (§12.4); Thomas 2016 carb
mid-ranges (§12.5); IOM AMDR (§12.6); EFSA 400 mg caffeine (§12.7); Bickel 2011 (§12.8); Enes 2024
(§12.9); activity factors (§12.11); ACSM symptom list (§12.12); melatonin legal status (§12.13);
RP back landmarks read from an image (R4 §7.1); NSCA chapter numbers (R4 §7.2); increment defaults
(R4 §7.3); AGP 9.4.0 + KGP 2.4.10 + Gradle 9.6.1 triple (R2 U1) with the two-string fallback;
`compileSdk = 37` → `platforms;android-37.0` mapping (R2 U3); `material-icons-extended` 1.7.8 with
Compose 1.12 (R2 U9); class-path resource packaging of `:core` JSON into the APK (C App. D.3 —
assets fallback exists); `sourceSets.assets.srcDirs` under the AGP 9 DSL (compile-risk, deletable);
`viewModel { }` initializer runtime behaviour (C App. D.4); SSE `ping` cadence → 120 s read timeout
(R1 §4.4); Claude Code `-p` flags for the bridge (R1 §4.7); Anthropic OAuth token prefix (only the
API-key prefix is known; the app whitelists `sk-ant-api`).

---

## Appendix A — `docs/build/FIRST_BUILD_CHECKLIST.md` (content)

1. Install JDK 17 (`winget install --id EclipseAdoptium.Temurin.17.JDK -e`), set `JAVA_HOME`,
   `java -version` prints 17.
2. Unzip `commandlinetools-win-15859902_latest.zip` to `C:\Android\sdk\cmdline-tools\latest`;
   `sdkmanager --licenses`; install `platform-tools`, `platforms;android-37.0`, `build-tools;36.0.0`;
   set `ANDROID_HOME` or `local.properties` `sdk.dir` (R2 §7.2).
3. Generate the wrapper once with a local Gradle 9.6.1 (`gradle wrapper --gradle-version 9.6.1
   --distribution-type bin`) if `gradlew` is missing.
4. `gradlew :core:test` → green (JDK 17 only).
5. Open in Android Studio (Quail 4 | 2026.1.4 or newer); sync; if the AGP/KGP/Gradle triple
   complains apply the fallback (`agp = "9.3.2"`, `gradle-9.5.1-bin.zip` + its sha256).
6. If sync rejects the `sourceSets { … assets.srcDirs(...) }` block, delete it (class-path loading is
   primary).
7. `gradlew :app:assembleDebug`; fix compile errors starting from `docs/build/COMPILE_RISK_LIST.md`;
   expect shallow errors (symbol names, imports); `@OptIn` warnings are fine.
   7a. When the first instrumented test is written (§10.5), add the `androidTestImplementation`
   lines listed there to `app/build.gradle.kts` (and `androidx-test-*`/`room-testing` entries to
   the catalogue) — not before.
8. `gradlew :app:testDebugUnitTest` → green.
9. Run on a device; onboarding → plan → log one session → check the Adjustment log and the
   session summary; airplane mode: everything except Coach works.
10. Settings → Backend → paste a Console API key → Test → one real chat turn; on the second turn
    within 5 minutes verify `cache_read_input_tokens > 0` on the Usage screen.
11. Commit `app/schemas/com.gymcoach.app.data.db.AppDatabase/1.json`.
12. Run Appendix B; then start WP-V1.1.

## Appendix B — `docs/test/GYM_FLOOR_SCRIPT.md` (manual release gate)

1. Fresh install → onboarding with the hotel preset → "Start" available: stopwatch ≤ 3:00.
2. Today → gym chip → new gym from "Commercial gym" → untick 3 machines, set dumbbells max 40 →
   Save: ≤ 60 s; availability count changed live.
3. At a gym whose rack has no safeties (untick `has_safeties`) with "spotter" off: bench is
   substituted (dumbbell bench, TRV-04 chip visible) and the back squat block shows the "stop at
   RIR 2 — no safeties" note; toggle Spotter on → bench returns to the plan.
4. Log 3 sets with one thumb: targets prefilled; one set via stepper; one via keypad; correct one
   set → strike-through visible; the summary's progression line uses the correction.
5. (v1.1) Lock the phone during a 3-min rest → notification countdown → rest-over alert within 2 s.
6. (v1.1) Kill the app from Recents mid-session → reopen → session restored with the draft values.
   (v1: reopen restores the session and draft without the timer.)
7. Swap a busy exercise → 3 taps; reason logged; summary shows the swap.
8. Finish → summary → progression lines with rule tags; Plan → Adjustment log has every change;
   Undo one within 5 minutes.
9. Airplane mode → everything above still works; Coach shows the offline banner and queues one
   draft; back online → the draft is sent.
10. Coach: "Hotel gym Tue–Thu" → proposals appear → Apply one → Today re-realized → Undo.
11. Settings → Backend: enter an invalid key → Test shows 401 and no key text in the message;
    paste a `claude` OAuth-style token → rejected with `backend_key_rejected`.
12. Quick readiness with 6–7 h / feel 3 / sore 2 → "Amber 56"; expand → fill quality 3 and stress
    3 → "Amber 58".

## Appendix C — `load_transfer.json` seeds (find-load starting points only; weak)

| From → to | factor | From → to | factor |
|---|---|---|---|
| barbell_bench_press → dumbbell_bench_press (per hand) | 0.35 | back_squat → front_squat | 0.80 |
| barbell_bench_press → incline_barbell_bench_press | 0.85 | back_squat → goblet_squat (single implement) | 0.35 |
| barbell_bench_press → machine_chest_press (stack) | 0.90 | back_squat → leg_press_45 (sled load) | 2.0 |
| barbell_bench_press → close_grip_bench_press | 0.85 | back_squat → hack_squat (sled load) | 1.5 |
| overhead_press_barbell → seated_dumbbell_shoulder_press (per hand) | 0.35 | conventional_deadlift → trap_bar_deadlift | 1.05 |
| overhead_press_barbell → machine_shoulder_press | 0.90 | conventional_deadlift → romanian_deadlift | 0.70 |
| barbell_row → dumbbell_row (per hand) | 0.40 | trap_bar_deadlift → romanian_deadlift | 0.75 |
| lat_pulldown → pull_up | body weight | romanian_deadlift → good_morning | 0.50 |
| hip_thrust → glute_bridge (DB on hips) | 0.50 | any hinge → kettlebell_swing | 0.20 |
| hack_squat (sled) → back_squat equivalent | 0.65 | bulgarian_split_squat ↔ reverse_lunge / split_squat / walking_lunge | 1.0 |
| bilateral → unilateral variant (total load) | 0.50 (`unilateral_factor`) | any → same pattern & load type & laterality | 1.0 |

Body-weight-fraction seeds (male novice, ~10-rep set): SQUAT barbell 0.50, machine (sled) 1.0,
dumbbell 0.25 (single implement); HINGE barbell 0.50, dumbbell 0.15 per hand; HORIZONTAL_PUSH
barbell 0.40, dumbbell 0.15 per hand, machine 0.45; INCLINE_PUSH barbell 0.35, dumbbell 0.12 per
hand; VERTICAL_PUSH barbell 0.25, dumbbell 0.10 per hand, machine 0.30; HORIZONTAL_PULL barbell 0.40,
dumbbell 0.15 per hand, cable/machine 0.45; VERTICAL_PULL cable 0.45; LUNGE_SPLIT dumbbell 0.10 per
hand; HIP_EXTENSION_ISOLATION barbell 0.60; CALF machine 0.60; core cable 0.20; any isolation 0.08
(per hand for dumbbells). Factors: female × 0.6; intermediate × 1.4; advanced × 1.7. Always shown
as "starting guess — adjust freely".

## Appendix D — Offline behaviour matrix

| Feature | Offline | Notes |
|---|---|---|
| Onboarding, profile, gyms, library, knowledge | full | knowledge is on-device |
| Plan generation / regeneration | full | pure `:core` |
| Today, readiness, session logging, summary, progression | full | |
| Progress charts, check-ins, nutrition targets, supplements | full | |
| Coach chat | send disabled (one queued draft); transcript readable; pending proposals can be applied/rejected (local) | the decision is sent as `tool_result` with the next online message |
| Settings, API key entry | full; "Test connection" needs network | |

*End of SPEC.md.*
