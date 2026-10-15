# GymCoach — Design Proposal B: "gym-floor-UX-first"

Status: design proposal, 2026-09-05. Written against `docs/REQUIREMENTS.md` and the four research
files `docs/research/R1-claude-backend.md` (chat backend, policy, wire contract),
`R2-android-toolchain.md` (pinned toolchain), `R3-training-science.md` (rule book; rule IDs are
cited below exactly as there, e.g. `VOL-04`) and `R4-exercise-taxonomy.md` (equipment/exercise
catalogue and JSON schemas). Every numeric planner rule below carries its R3 ID; every version below
is one R2 verified; every API-policy statement is R1's. Facts I verified live myself are marked
"(verified 2026-09-05)". Everything I could not verify is collected in §11.3 and flagged inline.

This proposal makes concrete choices. Where an alternative was considered it is named once and
rejected in the same sentence.

---

## 0. One-screen summary

| Area | Decision |
|---|---|
| Design thesis | The app is built around five "moments" of a travelling technician (§1). The active-session screen is the product; everything else exists so that screen is prefilled and correct. Targets: onboarding ≤ 3 min, pick a gym ≤ 5 s, new gym from preset ≤ 60 s, logging a set = 1 tap in the common case, never a silent number change. |
| Modules | `:core` (pure Kotlin/JVM: model, catalogue, availability, planner, progression, stats, readiness, warm-up, cardio, nutrition, supplements, check-ins, coach context and proposals, Messages-API stream model + SSE parser) and `:app` (Compose UI, Room/DataStore/Keystore, foreground session service, notifications, OkHttp transport). No DI framework; a hand-written `AppGraph`. §2 |
| Domain model | Kotlin data classes and enums in §3; kg/cm/seconds in storage, imperial only at the UI edge. Progression state keyed per exercise; gym-scoped for machine/cable/smith loads, global for free weights and bodyweight. |
| Planner | Deterministic pipeline (§4): split (SPLIT-01, FRQ-03) → per-muscle volume targets (VOL-01/04/05) → pattern coverage (MIX-02) → exercise selection by availability, difficulty and limitations → set allocation under a time budget (SESS-01, VOL-06/07, TIME-01) → reps/RIR (LOAD-03, RIR-03/04) → loads (progress state or E1RM-03) → warm-up (WU-01/02/05/06) → cardio placement (CAR-06/07) → readiness modifiers (RDY-02). Double progression (PROG-01/02/04), deloads (DLD-01..04), plateaus (PLAT-01..03), layoffs (LAY-01/02). |
| Session UX | Foreground service for the whole workout (`specialUse` type; rules verified 2026-09-05) with an ongoing notification whose rest countdown is rendered by the system; one-thumb set logger with steppers, RIR chips, plate calculator, swap sheet ranked by available equipment, exercise info sheet, ramp sets, finish flow with session RPE. §6.4 |
| Check-ins | Daily readiness in 3 taps (quick mode; the full RDY-01 form is one tap away), weekly questionnaire from CHK-01 (≤ 8 rotating items, auto-answered where data exists), RED-01/02/03 stop-and-refer flows. §5.6 |
| Coach | Direct Anthropic Messages API with an on-device API key (R1 rank 1) as default; home bridge (R1 rank 2) behind an "experimental" switch with R1's honest policy copy; claude.ai/Max OAuth tokens never accepted, no field for them. One strict tool `propose_plan_change` (12 kinds) plus `lookup` and `set_checkin_question`; proposals become cards with Accept/Decline; the app applies them and answers with `tool_result` on the next turn. Byte-stable ~3.5 k-token system prompt cached; compact JSON context in the user turn; history under top-level `cache_control`. §7 |
| Persistence | Room 2.8.4 (19 tables, §8), DataStore Preferences for settings, Android Keystore AES-256/GCM wrapper for the key (R2 §6.1), JSON assets for the catalogue, JSON export/import through the Storage Access Framework. Logged numbers are immutable rows; corrections supersede, never overwrite. |
| Testing | `:core` JUnit 4 golden and property tests for catalogue, planner, progression, stats, SSE parser and proposal validation; `:app` JVM ViewModel tests with fakes; Room migration tests once the project compiles; a written gym-floor manual test script. §9 |
| Work breakdown | 9 parallel streams (A–I) with exclusive file ownership and four frozen contracts (§10). |

---

## 1. Design thesis — five moments of a travelling technician

The user is a service technician who trains in whatever gym is near the job site, and at home
between trips. He is technically literate, does not want to be sold to, and does not want to type
with chalky hands. Every screen below was designed by asking: what does he need in this moment,
with one thumb, in under five seconds?

| # | Moment | Where | What he needs | Screens |
|---|---|---|---|---|
| M1 | Sunday evening, planning the week | Home, sofa | See the week, mark travel days, tell the coach "hotel gym Tue–Thu" | Today, Plan, Coach |
| M2 | 06:40 Tuesday, hotel fitness room with a dumbbell rack to 20 kg and a bike | Unknown gym | Pick "Hotel gym" (or make one in a minute), get a session that only uses what is there, start | Today → Gym switcher → Gym editor → Session |
| M3 | Mid-session, between sets | Any gym floor | Log the set that just happened, see the next target, know how long to rest, swap a busy machine | Session (active) |
| M4 | Tuesday 21:30, hotel room | Hotel | Three taps of readiness for tomorrow, maybe the weekly check-in, one question to the coach | Readiness sheet, Weekly check-in, Coach |
| M5 | Saturday, home gym with a rack and adjustable dumbbells | Home | Same as M3 with his own equipment and heavier loads; look at trends | Session, Progress |

Principles derived from those moments:

1. **Prefilled, not empty.** Every input on the session screen starts with the planner's number.
   The user confirms or nudges; he never types unless he wants to.
2. **One thumb, bottom third.** Primary actions live in the bottom 40 % of the screen; 56–64 dp
   targets; steppers instead of keyboards; a keyboard only on an explicit tap on the number.
3. **Offline is the normal state.** Everything except the Coach tab works in airplane mode. The
   Coach tab degrades to read-only history plus a queued question.
4. **Never a silent change.** Every automatic modification (readiness, progression, deload, layoff,
   time trim, coach proposal) is shown with its rule ID and trigger before a set is logged and
   stored as a `PlanAdjustment` row the user can browse (POL-01).
5. **Gyms are first-class.** A gym is a named equipment set with parameters; switching gyms
   re-materialises the session with visible substitutions and reasons (TRV-04).
6. **The coach proposes; the app applies.** Chat never edits data directly. Proposals are cards
   with Accept/Decline; acceptance is one tap; undo is available for five minutes.
7. **Honesty in settings.** Backend, cost and policy status are stated plainly, in R1's words.

Quantified UX targets (checked by the manual test script, §9.5):

| Target | Value |
|---|---|
| Onboarding, first launch to "Start session" available | ≤ 3 min (7 screens, 2 skippable) |
| Pick an existing gym | 2 taps, ≤ 5 s |
| Create a new gym from a preset | ≤ 60 s (preset + tick 3–6 differences) |
| Log a set matching the target | 1 tap ("Log set") |
| Log a set with a different load or reps | ≤ 4 taps (stepper taps + Log) |
| Swap a busy exercise | 3 taps (Swap → pick → confirm) |
| Daily readiness | 3 taps + Done |
| Accept a coach proposal | 1 tap |

---

## 2. Module and package architecture

### 2.1 Modules

Two Gradle modules exactly as REQUIREMENTS.md and R2 §5 define them. The R2 skeleton files
(`gradle/libs.versions.toml`, `settings.gradle.kts`, root/`core`/`app` `build.gradle.kts`,
`gradle.properties`, `gradle-wrapper.properties`) are adopted verbatim; this proposal adds no plugin
and no dependency beyond R2's list. Pins: JDK 17, Gradle 9.6.1, AGP 9.4.0, Kotlin 2.4.10, KSP 2.3.11,
coroutines 1.11.0, serialization-json 1.11.0, Compose BOM 2026.08.00 (material3 1.4.0),
activity-compose 1.13.0, navigation-compose 2.10.0, lifecycle 2.11.0, Room 2.8.4, DataStore 1.2.1,
OkHttp 5.5.0, WorkManager 2.11.2 (`work-runtime`), core-ktx 1.19.0; compileSdk = targetSdk = 37,
minSdk 26.

Dependency direction: `:app` → `:core`. `:core` has no Android imports and no OkHttp; it owns the
Messages-API wire *model* (request/response/stream-event data classes and the SSE line parser) so
the protocol can be unit-tested on the JVM, while `:app` owns the OkHttp transport.

### 2.2 `:core` packages (`com.gymcoach.core`)

| Package | Contents | Depends on |
|---|---|---|
| `model` | All domain data classes and enums (§3), `@Serializable` where persisted as JSON or sent to the coach. | — |
| `units` | kg↔lb, cm↔in, display formatting (1 decimal, 0.25 kg resolution), `Rounding` to plate, dumbbell and stack steps. | model |
| `time` | `Clock` interface (`nowMillis()`, `today(): LocalDate`, `zoneId`), `SystemClock`, `FixedClock` for tests. | — |
| `catalog` | `EquipmentCatalog`, `ExerciseCatalog`, JSON loaders (`CatalogJson.parseEquipment(text)`, `parseExercises(text)`), `CatalogValidator` (the R4 §6.3 checks), alias index for search (DE/EN). | model |
| `availability` | `AvailabilityResolver`: gym set + session overrides → set of available exercise IDs, the `EquipmentOption` chosen per exercise, and a `LoadContext` (increment, dumbbell max/step, stack step, safeties present). Implements the R4 §2.2 algebra including the `spotter` session flag and `power_rack.has_pullup_bar`. | model, catalog |
| `rules` | `RuleId` string constants exactly as R3 §10, `Tunables` (every weak-tagged constant as a field with default and bounds), `RuleText` (one-line statement + citation per ID for the Knowledge screen and for the coach `lookup` tool). | — |
| `planner` | `SplitChooser`, `VolumeTargets`, `PatternCoverage`, `ExerciseSelector`, `SetAllocator`, `RepRirAssigner`, `LoadPrescriber`, `TimeTrimmer`, `SessionMaterializer` (→ `PlannedSession`), `ProgramBuilder` (→ `Program`). | model, catalog, availability, rules, progression, stats |
| `progression` | `DoubleProgression` (PROG-01..05), `DeloadPolicy` (DLD-01..04), `PlateauDetector` (PLAT-01..03), `LayoffPolicy` (LAY-01/02), `RirBias` (RIR-05). | model, rules, stats |
| `stats` | `E1rm` (E1RM-01..04), `VolumeAccounting` (direct and fractional sets per muscle per ISO week, DEF-05 / R4 §4.2), `MovingAverage` (NUT-15), `TrendSeries` → `ChartData` (pure numbers for the Canvas charts), `SessionLoad` (RDY-06). | model |
| `readiness` | `ReadinessScorer` (RDY-01), `ReadinessModifier` (RDY-02/03/04/05, SLP-03). | model, rules |
| `warmup` | `WarmupBuilder` (WU-01..06, MOB-01, MOB-07 drill selection), `RampSets` rounded to available plates/dumbbells. | model, rules, availability, plates |
| `cardio` | `CardioPlanner` (CAR-01..09), `HeartRateZones` (CAR-02/03/04). | model, rules |
| `nutrition` | `EnergyEstimator` (NUT-04: Mifflin–St Jeor, activity factor, adaptive TDEE), `MacroTargets` (NUT-01/08/09/10/11), `RateController` (NUT-05/06/07/16/17), `TravelHeuristics` text (NUT-14). | model, stats, rules |
| `supplements` | Tiered knowledge data (SUP-01..12) with the SUP-12 caveat and the RED-03 gate. | model, rules |
| `checkin` | `WeeklyQuestionBank` (CHK-01 Q1–Q15 with auto-answer sources and triggers), `RedFlags` (RED-01/02/03) text and gate logic. | model, rules |
| `plates` | `PlateCalculator` (per-side plate solution, nearest reachable loads), `DumbbellRounder`, `StackRounder`. | model, units |
| `coach` | `CoachContext` assembly (compact JSON with a character budget), `SystemPrompt` (byte-stable text), `ToolSchemas` (JSON strings), `Proposal` + `ProposalValidator` + `ProposalApplier` (pure functions returning new state plus a `PlanAdjustment`), `SummaryPolicy` (what goes into the rolling summary). | model, planner, availability, rules |
| `chat` | Messages-API wire model (`ApiRequest`, `ContentBlock`, `StreamEvent`, `Usage`, `ApiError`), `SseLineParser`, `StreamAccumulator`, `RetryPolicy`, `CostMeter` (R1 §2.3 prices). No I/O. | — |
| `export` | `BackupJson` (versioned export/import of all user data). | model |

### 2.3 `:app` packages (`com.gymcoach.app`)

| Package | Contents |
|---|---|
| `GymCoachApp` + `di.AppGraph` | Hand-written service locator created in `Application.onCreate()`: Room DB, DataStore, repositories, catalogue (loaded from assets on `Dispatchers.IO`), `Clock`, `NotificationCenter`, `ChatBackendFactory`. Rejected: Hilt (a second KSP processor whose AGP-9 status is unverified) and Koin (extra dependency for no gain in a single-developer app). |
| `data.db` | `GymCoachDatabase` (Room), entities, DAOs, `Converters`, migrations. |
| `data.repo` | `ProfileRepository`, `GymRepository`, `PlanRepository`, `LogRepository`, `CheckinRepository`, `ChatRepository`, `SettingsRepository`: `Flow`s and suspend functions; map entities ↔ `:core` models. |
| `data.prefs` | DataStore keys, `Settings` model, `KeystoreCrypto` (AES-256/GCM wrapper, R2 §6.1), `SecretStore`. |
| `data.assets` | `AssetCatalogLoader` (reads `equipment.json`, `exercises.json` from `assets/`, checks `schema_version`). |
| `session` | `WorkoutSessionService` (foreground service), `RestTimer`, `SessionNotification`, `SessionController` (single source of truth for the active session; survives configuration changes and process death because every logged set is already in Room). |
| `notify` | `Channels` (ids and importance), `NotificationCenter` (post/cancel), `ReminderScheduler` (WorkManager), `ExactAlarmHelper` (optional rest-end backup alarm). |
| `work` | `ReadinessReminderWorker`, `WeeklyCheckinWorker`, `CardioReminderWorker`, `NutritionReviewWorker` (14-day NUT-16 review). |
| `chat` | `ChatBackend` interface, `DirectAnthropicBackend` (OkHttp streaming + `:core` `SseLineParser`), `BridgeBackend` (Claude Code `stream-json` adapter; second release), `ChatEngine` (turn loop: send → stream → tool_use → proposal cards → tool_result on the next turn). |
| `ui.theme` | Material 3 theme (dynamic colour on API 31+, fixed palette below), a "gym" type scale (larger numerals), shapes. |
| `ui.nav` | `@Serializable` routes, `AppNavHost`, bottom bar, deep links from notifications. |
| `ui.components` | `Stepper`, `RirChips`, `BigPrimaryButton`, `PlateDiagram`, `LineChart` (Canvas), `MuscleBandBars`, `ProposalCard`, `RuleTag`, `EmptyState`, `ErrorState`, `OfflineBanner`, `MonthGrid`, `HourMinuteStepper`. |
| `ui.onboarding`, `ui.today`, `ui.session`, `ui.gyms`, `ui.plan`, `ui.progress`, `ui.coach`, `ui.checkin`, `ui.nutrition`, `ui.knowledge`, `ui.settings`, `ui.library` | One package per feature: `XScreen.kt`, `XViewModel.kt`, `XUiState.kt`. |

ViewModel rule: each `ViewModel` takes repositories and `:core` services as constructor parameters
and is created with a `viewModelFactory { initializer { ... } }` from `AppGraph`. UI state is one
immutable data class per screen exposed as `StateFlow`, collected with
`collectAsStateWithLifecycle()`.

### 2.4 Compile-conservative UI rules (because `:app` is first compiled by the user)

- Every UI file that uses sheets, chips or app bars carries
  `@file:OptIn(ExperimentalMaterial3Api::class)`. Verified 2026-09-05 on androidx-main's
  `material3/api/current.txt`: `ModalBottomSheet`, `rememberModalBottomSheetState` and
  `BottomSheetScaffold` are still `@ExperimentalMaterial3Api`; `TopAppBar`, `CenterAlignedTopAppBar`,
  `AssistChip`, `Badge`, `AlertDialog` are not. The file-level opt-in is harmless where not needed.
- No `TimePicker`, `DatePicker`, `SegmentedButton`, `SearchBar`, `PullToRefreshBox` (stability not
  verified, §11.3). Times are entered with two steppers (`HourMinuteStepper`), dates with our own
  `MonthGrid`, choices with `FilterChip` rows, search with a plain `OutlinedTextField`.
- Icons only from `Icons.Filled.*` / `Icons.AutoMirrored.Filled.*` (material-icons-extended 1.7.8,
  BOM-managed, R2 §6.3).
- Charts are hand-drawn `Canvas` (R2 §6.2); the numbers come from `:core` `stats.ChartData`.
- Navigation: `NavHost` + `composable<Route>` with `@Serializable` routes (navigation-compose 2.10.0;
  serialization plugin applied in `:app`, R2 G4).
- Lists: `LazyColumn` with stable `key`s. No experimental foundation APIs.
- Numeric entry: `OutlinedTextField` with `KeyboardOptions(keyboardType = KeyboardType.Decimal)`;
  parsing accepts both `.` and `,`.
- Haptics: `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress)` on
  "Log set" and on rest-end while the app is in front.
- Keep-screen-on during a session: `DisposableEffect` adding/clearing
  `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` on the Activity window.
- Portrait only (`android:screenOrientation="portrait"` on the single Activity); the session screen
  is designed for one hand and phone widths 360–430 dp; tablets get the same layout centred.

---

## 3. Domain data model (`:core` `model` package)

Conventions: all masses in kg (`Double`), lengths in cm, durations in seconds, dates as ISO-8601
`String` (`yyyy-MM-dd`) and instants as epoch milliseconds (`Long`) inside `@Serializable` classes
— this avoids custom serializers for `java.time` and keeps Room converters trivial; `java.time` is
used freely in logic through small extension helpers (`LocalDate.parse`, `Instant.ofEpochMilli`).
IDs are `Long` for Room-owned rows and `String` for catalogue-owned rows (exercise/equipment ids
from R4, pattern `^[a-z][a-z0-9_]{1,63}$`).

### 3.1 Enums

```kotlin
package com.gymcoach.core.model

import kotlinx.serialization.Serializable

@Serializable enum class Sex { MALE, FEMALE }

/** R3 DEF-06 goals; mapped to R4 rep-profile goal keys by [Goal.profileKey]. */
@Serializable enum class Goal(val profileKey: String) {
    STRENGTH("strength"),
    HYPERTROPHY("hypertrophy"),
    FAT_LOSS("hypertrophy"),          // hypertrophy-style training in a deficit (DEF-06)
    GENERAL_FITNESS("general_fitness"),
    ENDURANCE_SUPPORT("endurance")
}

/** R3 DEF-01. Derived, never entered directly (see Profile.trainingAge()). */
@Serializable enum class TrainingAge { NOVICE, INTERMEDIATE, ADVANCED }

@Serializable enum class UnitSystem { METRIC, IMPERIAL }

@Serializable enum class LimitationRegion { KNEE, SHOULDER, LOW_BACK, ELBOW_WRIST, HIP, ANKLE, NECK, OTHER }
@Serializable enum class Side { LEFT, RIGHT, BOTH }

/** RED-03 gate items; any true value gates HIIT, max-strength zone and supplement advice. */
@Serializable enum class MedicalFlag {
    HEART_DISEASE, KIDNEY_DISEASE, LIVER_DISEASE, PREGNANCY, DIABETES_ON_MEDICATION,
    UNCONTROLLED_HYPERTENSION, DOCTOR_RESTRICTION, OTHER
}

@Serializable enum class EquipmentCategory {
    FREE_WEIGHTS, RACKS_BENCHES, CABLES, MACHINES_UPPER, MACHINES_LOWER,
    BODYWEIGHT_STATIONS, CARDIO, ACCESSORIES
}

/** R4 §3, 29 patterns. */
@Serializable enum class MovementPattern {
    SQUAT, HINGE, LUNGE_SPLIT, HORIZONTAL_PUSH, INCLINE_PUSH, VERTICAL_PUSH, CHEST_FLY,
    HORIZONTAL_PULL, VERTICAL_PULL, ELBOW_FLEXION, ELBOW_EXTENSION, SHOULDER_ABDUCTION,
    SHOULDER_ER_REAR_DELT, SCAPULAR_ELEVATION, HIP_EXTENSION_ISOLATION, HIP_ABDUCTION,
    HIP_ADDUCTION, KNEE_FLEXION_ISOLATION, KNEE_EXTENSION_ISOLATION, CALF,
    CORE_ANTI_EXTENSION, CORE_ANTI_ROTATION, CORE_FLEXION, CORE_ROTATION, CARRY, GRIP,
    POWER, CARDIO, MOBILITY
}

/** R4 §4.1, 21 muscles. */
@Serializable enum class Muscle {
    CHEST, FRONT_DELTS, SIDE_DELTS, REAR_DELTS, ROTATOR_CUFF, LATS, UPPER_BACK, TRAPS_UPPER,
    LOWER_BACK, BICEPS, TRICEPS, FOREARMS, ABS, OBLIQUES, HIP_FLEXORS, QUADS, HAMSTRINGS,
    GLUTES, GLUTE_MED, ADDUCTORS, CALVES
}

@Serializable enum class LoadType {
    BARBELL, DUMBBELL, MACHINE, CABLE, BODYWEIGHT, BAND, KETTLEBELL, SMITH, SLED,
    MEDICINE_BALL, CARDIO, NONE;

    /** R3 DEF-07 increment/RIR class. Isolation is decided by the exercise, not the implement. */
    val isGymScoped: Boolean get() = this == MACHINE || this == CABLE || this == SMITH || this == SLED
}

@Serializable enum class Difficulty { BEGINNER, INTERMEDIATE, ADVANCED }

@Serializable enum class ProgressionKind {
    LINEAR_LOAD, DOUBLE_PROGRESSION, REP_PROGRESSION, TIME_PROGRESSION, BAND_PROGRESSION,
    DISTANCE_OR_TIME, NONE
}

@Serializable enum class RepUnit { REPS, SECONDS, METRES, MINUTES }

@Serializable enum class SplitType { FULL_BODY, UPPER_LOWER, PUSH_PULL_LEGS, UPPER_LOWER_PLUS_FULL, UL_PPL }

@Serializable enum class SetKind { WARMUP, WORKING, AMRAP, DROP, CALIBRATION }

@Serializable enum class SessionStatus { PLANNED, ACTIVE, PAUSED, COMPLETED, ABANDONED, SKIPPED }

@Serializable enum class ReadinessBand { GREEN, AMBER, RED }

@Serializable enum class CardioModality {
    TREADMILL_WALK, TREADMILL_RUN, BIKE, AIR_BIKE, ROWER, ELLIPTICAL, STAIR_CLIMBER, SKI_ERG,
    JUMP_ROPE, OUTDOOR_WALK, OUTDOOR_RUN, SWIM, OTHER
}
@Serializable enum class CardioKind { ZONE2, HIIT_4X4, HIIT_10X1, SIT, RECOVERY_WALK }

@Serializable enum class AdjustmentSource { PLANNER, READINESS, COACH, USER }

@Serializable enum class ProposalKind {
    SWAP_EXERCISE, CHANGE_SETS, CHANGE_REP_RANGE, CHANGE_LOAD, ADD_CARDIO_SESSION,
    REMOVE_SESSION, CHANGE_GOAL, CHANGE_SCHEDULE, SET_CHECKIN_QUESTION,
    UPDATE_NUTRITION_TARGETS, ADD_LIMITATION, SCHEDULE_DELOAD
}
@Serializable enum class ProposalStatus { PENDING, ACCEPTED, DECLINED, APPLIED, FAILED, EXPIRED }

@Serializable enum class ChatRole { USER, ASSISTANT }

@Serializable enum class BackendKind { DIRECT_API, HOME_BRIDGE }
@Serializable enum class ModelChoice(val modelId: String) {
    OPUS_5("claude-opus-5"), SONNET_5("claude-sonnet-5")
}
@Serializable enum class Effort { LOW, MEDIUM }   // R1 §2.4: held constant per conversation
```

### 3.2 Profile and gyms

```kotlin
@Serializable
data class Profile(
    val sex: Sex,
    val birthYear: Int,
    val heightCm: Double,
    val goal: Goal,
    val daysPerWeek: Int,                 // 1..6
    val sessionMinutes: Int,              // 30, 45, 60, 90
    val monthsConsistentTraining: Int,    // DEF-01 input
    val monthsSinceLastLayoffOver12Mo: Int?, // null = never had a >=12-month layoff
    val limitations: List<Limitation> = emptyList(),
    val medicalFlags: Set<MedicalFlag> = emptySet(),
    val priorityMuscles: List<Muscle> = emptyList(),   // max 2; drives split choice (SPLIT-01)
    val dislikedExerciseIds: Set<String> = emptySet(),
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val usualWakeTimeMinutes: Int = 6 * 60,            // for WU-06 "within 60 min of waking"
    val usualBedTimeMinutes: Int = 23 * 60,            // for SUP-02 caffeine cut-off
    val activityFactor: Double = 1.375,                // NUT-04 convention; user-adjustable
    val advancedExercisesOptIn: Boolean = false,       // R4 §5.3
    val updatedAtMillis: Long
) {
    fun ageOn(year: Int): Int = year - birthYear
    /** DEF-01: novice < 6 months consistent or any layoff >= 12 months; advanced > 24 months. */
    fun trainingAge(): TrainingAge = when {
        monthsSinceLastLayoffOver12Mo != null && monthsSinceLastLayoffOver12Mo < 6 -> TrainingAge.NOVICE
        monthsConsistentTraining < 6 -> TrainingAge.NOVICE
        monthsConsistentTraining <= 24 -> TrainingAge.INTERMEDIATE
        else -> TrainingAge.ADVANCED
    }
}

@Serializable
data class Limitation(
    val region: LimitationRegion,
    val side: Side = Side.BOTH,
    val painScore: Int,            // 0..10, INJ-00
    val sinceDate: String,         // ISO date
    val note: String = "",
    val active: Boolean = true
)

/** One-of by convention: exactly one field non-null (R4 paramSpec types number/boolean/number_list). */
@Serializable
data class ParamValue(val number: Double? = null, val bool: Boolean? = null, val numbers: List<Double>? = null)

@Serializable
data class GymItem(val equipmentId: String, val params: Map<String, ParamValue> = emptyMap())

@Serializable
data class GymSet(
    val id: Long,
    val name: String,
    val colorIndex: Int,                     // 0..7, palette in the theme
    val presetOrigin: String?,               // "base_gym" | "home_gym" | "hotel_gym" | null
    val items: List<GymItem>,
    val travelKitMerged: Boolean = false,
    val notes: String = "",
    val createdAtMillis: Long,
    val lastUsedAtMillis: Long?,
    val archived: Boolean = false
)

/** Today-only deviations chosen at session start (R4 §2.2 item 4 and §2.6). */
@Serializable
data class SessionOverrides(
    val spotter: Boolean = false,
    val missingEquipmentIds: Set<String> = emptySet(),
    val addedEquipmentIds: Set<String> = emptySet()
)
```

### 3.3 Catalogue (mirrors the R4 §6 schemas one-to-one)

```kotlin
@Serializable
data class ParamSpec(val key: String, val type: String, val unit: String? = null,
                     val default: ParamValue? = null, val min: Double? = null, val max: Double? = null,
                     val description: String? = null)

@Serializable
data class EquipmentDef(
    val id: String, val name: String, val category: EquipmentCategory,
    val aliases: List<String> = emptyList(), val description: String? = null,
    val params: List<ParamSpec> = emptyList(), val sessionFlag: Boolean = false,
    val packable: Boolean = false, val defaultIn: List<String> = emptyList(), val notes: String? = null
)

@Serializable
data class EquipmentGroup(val id: String, val kind: String /* any_of | all_of */, val name: String? = null,
                          val members: List<String>, val notes: String? = null)

@Serializable data class PresetItem(val equipmentId: String, val params: Map<String, ParamValue> = emptyMap())
@Serializable data class Preset(val id: String, val name: String, val description: String? = null, val items: List<PresetItem>)

@Serializable
data class EquipmentCatalogFile(val schemaVersion: Int, val equipment: List<EquipmentDef>,
                                val groups: List<EquipmentGroup>, val presets: List<Preset>)

@Serializable data class MuscleWeight(val muscle: Muscle, val weight: Double)   // 1.0 | 0.5 | 0.25

@Serializable
data class EquipmentOption(val allOf: List<String>, val loadType: LoadType? = null,
                           val note: String? = null, val preferred: Boolean = false)

@Serializable
data class GoalPrescription(val repLo: Int, val repHi: Int, val rirLo: Int, val rirHi: Int,
                            val rirLastWeekLo: Int? = null, val rirLastWeekHi: Int? = null,
                            val setsLo: Int? = null, val setsHi: Int? = null, val note: String? = null)

@Serializable
data class RepProfile(val id: String, val unit: RepUnit, val byGoal: Map<String, GoalPrescription>,
                      val notes: String? = null, val sources: List<String> = emptyList())

@Serializable
data class Increment(val kind: String, val defaultKg: Double? = null, val minKg: Double? = null,
                     val perHand: Boolean = false, val direction: String = "load", val note: String? = null)

@Serializable
data class ExerciseFlags(val compound: Boolean, val spinalLoading: Boolean, val shoulderSensitive: Boolean,
                         val kneeSensitive: Boolean, val lowBackSensitive: Boolean,
                         val requiresSpotterOrSafeties: Boolean)

@Serializable
data class ExerciseContent(val description: String = "", val setup: List<String> = emptyList(),
                           val cues: List<String> = emptyList(), val mistakes: List<String> = emptyList(),
                           val safety: List<String> = emptyList(),
                           val substitutionRationale: Map<String, String> = emptyMap(),
                           val reviewNotes: String? = null)

@Serializable
data class ExerciseDef(
    val id: String, val name: String, val aliases: List<String> = emptyList(),
    val pattern: MovementPattern, val muscles: List<MuscleWeight>,
    val equipmentOptions: List<EquipmentOption>, val loadType: LoadType, val unilateral: Boolean,
    val difficulty: Difficulty, val repProfile: String,
    val repOverrides: Map<String, GoalPrescription> = emptyMap(),
    val increment: Increment, val progression: ProgressionKind, val substitutions: List<String>,
    val flags: ExerciseFlags, val batch: Int, val tags: List<String> = emptyList(),
    val sources: List<String> = emptyList(), val content: ExerciseContent? = null,
    val contentStatus: String = "todo"
) {
    val isIsolation: Boolean get() = !flags.compound && pattern != MovementPattern.CARDIO && pattern != MovementPattern.MOBILITY
    val primaryMuscles: List<Muscle> get() = muscles.filter { it.weight == 1.0 }.map { it.muscle }
}

@Serializable
data class ExerciseCatalogFile(val schemaVersion: Int, val repProfiles: List<RepProfile>, val exercises: List<ExerciseDef>)
```

The JSON files use snake_case keys exactly as R4 §6; `Json { namingStrategy = JsonNamingStrategy.SnakeCase; ignoreUnknownKeys = false }`
maps them onto the camelCase fields above (`JsonNamingStrategy.SnakeCase` exists since
kotlinx-serialization 1.5; we pin 1.11.0). `ignoreUnknownKeys = false` on assets is deliberate: a typo
in the catalogue must fail the `:core` tests, not disappear silently.

### 3.4 Program, templates and materialised sessions

```kotlin
@Serializable
data class SlotTemplate(
    val slotId: String,                   // stable within the program, e.g. "d1s3"
    val pattern: MovementPattern,
    val exerciseId: String,               // chosen for the program's "home" gym
    val alternates: List<String>,         // ranked substitutions frozen at build time
    val sets: Int, val repLo: Int, val repHi: Int, val rirTarget: Int,
    val restSec: Int,
    val priority: Int,                    // 1 = first exercise of the day (ORD-01)
    val supersetGroup: String? = null,    // REST-03 antagonist pairs share a group id
    val targetMuscles: List<Muscle>       // the muscles this slot is credited to (DEF-05)
)

@Serializable
data class CardioTemplate(val kind: CardioKind, val minutes: Int, val preferredModalities: List<CardioModality>,
                          val separateFromLifting: Boolean /* CAR-06: >= 6 h or another day */)

@Serializable
data class DayTemplate(val index: Int, val label: String /* "Upper A" */, val focus: List<Muscle>,
                       val slots: List<SlotTemplate>, val cardio: CardioTemplate? = null,
                       val isHeavyLegDay: Boolean)

@Serializable
data class Program(
    val id: Long, val name: String, val goal: Goal, val split: SplitType,
    val daysPerWeek: Int, val sessionMinutes: Int, val startDate: String, val weeks: Int,
    val deloadWeekIndex: Int?,            // DLD-01; null = reactive only (novice)
    val homeGymId: Long,                  // gym the templates were built for
    val days: List<DayTemplate>,
    val weeklyVolumeTargets: Map<Muscle, VolumeBand>,   // fractional sets, VOL-04/05
    val createdBy: AdjustmentSource, val status: String, // ACTIVE | ARCHIVED
    val createdAtMillis: Long
)

@Serializable data class VolumeBand(val lo: Double, val hi: Double)

@Serializable
data class RampSet(val loadKg: Double?, val reps: Int, val percentOfWork: Int, val restSec: Int)

@Serializable
data class MobilityDrill(val exerciseId: String, val sets: Int, val repsOrSeconds: Int, val unit: RepUnit, val forJoint: String)

@Serializable
data class WarmupPlan(val raiseMinutes: Int, val raiseSuggestion: String,
                      val drills: List<MobilityDrill>, val rampByExerciseSlot: Map<String, List<RampSet>>,
                      val ruleIds: List<String>)

@Serializable
data class PlannedSet(val index: Int, val kind: SetKind, val targetLoadKg: Double?,
                      val targetRepLo: Int, val targetRepHi: Int, val targetRir: Int,
                      val targetSeconds: Int? = null, val targetMetres: Int? = null)

@Serializable
data class LoadContext(val incrementKg: Double, val minIncrementKg: Double, val perHand: Boolean,
                       val dumbbellMaxKg: Double?, val dumbbellStepKg: Double?, val stackStepKg: Double?,
                       val halfStepKg: Double?, val barKg: Double?, val smallestPlateKg: Double?,
                       val hasMicroplates: Boolean, val safetiesOrSpotter: Boolean)

@Serializable
data class PlannedExercise(
    val slotId: String, val exerciseId: String, val optionIndex: Int, val loadType: LoadType,
    val sets: List<PlannedSet>, val restSec: Int, val supersetGroup: String?,
    val loadContext: LoadContext, val substitutedFrom: String? = null, val substitutionReason: String? = null,
    val notes: List<String> = emptyList()      // e.g. "no safeties: RIR capped at 2 (R4 §5.5)"
)

@Serializable
data class AppliedModifier(val ruleId: String, val trigger: String, val description: String)

@Serializable
data class PlannedCardio(val kind: CardioKind, val minutes: Int, val modality: CardioModality,
                         val zoneText: String, val hrLo: Int?, val hrHi: Int?, val protocolText: String,
                         val afterLifting: Boolean)

@Serializable
data class PlannedSession(
    val id: Long, val programId: Long, val dayIndex: Int, val weekIndex: Int, val date: String,
    val gymSetId: Long, val overrides: SessionOverrides,
    val warmup: WarmupPlan, val exercises: List<PlannedExercise>, val cardio: PlannedCardio?,
    val estimatedMinutes: Int, val modifiers: List<AppliedModifier>,
    val readinessScore: Int?, val isDeloadWeek: Boolean, val status: SessionStatus
)
```

### 3.5 Logs (sacred content)

```kotlin
@Serializable
data class WorkoutSession(
    val id: Long, val plannedSessionId: Long?, val date: String, val gymSetId: Long,
    val startedAtMillis: Long, val endedAtMillis: Long?, val readinessScore: Int?,
    val sessionRpe: Int?, val notes: String = "", val status: SessionStatus
)

/**
 * One performed set. Immutable once written: a correction inserts a new row with
 * supersedesId pointing at the old one; the old row keeps status "superseded" (POL-01).
 */
@Serializable
data class SetLog(
    val id: Long, val sessionId: Long, val exerciseId: String, val slotId: String?,
    val setIndex: Int, val kind: SetKind,
    val targetLoadKg: Double?, val targetRepLo: Int?, val targetRepHi: Int?, val targetRir: Int?,
    val loadKg: Double?, val reps: Int?, val rir: Int?, val seconds: Int?, val metres: Int?,
    val side: Side = Side.BOTH, val completedAtMillis: Long,
    val supersedesId: Long? = null, val superseded: Boolean = false, val note: String = ""
)

@Serializable
data class CardioLog(val id: Long, val sessionId: Long?, val date: String, val modality: CardioModality,
                     val kind: CardioKind, val minutes: Int, val avgHr: Int?, val rpe: Int?, val note: String = "")

@Serializable
data class BodyMetric(val id: Long, val date: String, val weightKg: Double?, val waistCm: Double?,
                      val restingHr: Int?, val note: String = "")

@Serializable
data class ReadinessCheckin(
    val id: Long, val date: String, val quickMode: Boolean,
    val sleepHours: Double?, val sleepQuality: Int?, val soreness: Int?, val stress: Int?,
    val energy: Int?, val motivation: Int?, val restingHr: Int?,
    val score: Int, val band: ReadinessBand, val assumedFields: List<String>, val createdAtMillis: Long
)

@Serializable
data class WeeklyCheckin(val id: Long, val weekStart: String, val answers: Map<String, String>,
                         val autoAnswered: Set<String>, val triggeredRuleIds: List<String>,
                         val completedAtMillis: Long?)

@Serializable
data class StepsLog(val date: String, val steps: Int, val source: String /* manual */)
```

### 3.6 Progression state and adjustments

```kotlin
/** TRV-04: state per exercise; gym-scoped for gym-specific implements (LoadType.isGymScoped). */
@Serializable
data class ProgressKey(val exerciseId: String, val gymSetId: Long?) {
    val key: String get() = if (gymSetId == null) exerciseId else "$exerciseId@$gymSetId"
}

@Serializable
data class ExerciseProgress(
    val key: ProgressKey,
    val workingLoadKg: Double?,           // null = "find load" protocol next time (PROG-04, TRV-04)
    val repLo: Int, val repHi: Int, val rirTarget: Int,
    val consecutiveHits: Int,             // sessions where all sets hit repHi at <= target RIR
    val consecutiveMisses: Int,           // sessions with >= 2 sets below repLo
    val lastSessionDate: String?,
    val bestE1rm14dKg: Double?, val bestE1rmDate: String?,
    val lastOutcome: String?              // "hit" | "miss" | "hold" | "autoreg_up" | "autoreg_down"
)

@Serializable
data class PlanAdjustment(
    val id: Long, val atMillis: Long, val ruleId: String, val source: AdjustmentSource,
    val summary: String, val trigger: String, val beforeJson: String, val afterJson: String,
    val proposalId: Long? = null, val undoneAtMillis: Long? = null
)
```

### 3.7 Coach

```kotlin
@Serializable
data class ChatThread(val id: Long, val title: String, val createdAtMillis: Long,
                      val rollingSummary: String = "", val summarizedUpToMessageId: Long? = null,
                      val model: ModelChoice, val effort: Effort, val backend: BackendKind)

/** contentJson is the Messages-API content array, stored verbatim (thinking blocks included, R1 §3.5). */
@Serializable
data class ChatMessage(val id: Long, val threadId: Long, val role: ChatRole, val contentJson: String,
                       val createdAtMillis: Long, val usageJson: String? = null, val stopReason: String? = null,
                       val requestId: String? = null, val costUsd: Double? = null, val error: String? = null)

/** Flat payload; exactly the fields the strict tool schema (§7.3) declares. Unused fields are null. */
@Serializable
data class ProposalPayload(
    val exerciseId: String? = null, val replacementExerciseId: String? = null, val slotId: String? = null,
    val dayIndex: Int? = null, val scope: String? = null /* this_session | rest_of_program */,
    val sets: Int? = null, val repLo: Int? = null, val repHi: Int? = null, val rirTarget: Int? = null,
    val loadKg: Double? = null, val loadPercentChange: Double? = null,
    val cardioKind: CardioKind? = null, val cardioMinutes: Int? = null, val cardioModality: CardioModality? = null,
    val weekday: Int? = null /* 1=Mon..7=Sun */, val goal: Goal? = null, val daysPerWeek: Int? = null,
    val sessionMinutes: Int? = null, val questionText: String? = null, val dueDate: String? = null,
    val proteinGPerKg: Double? = null, val kcalTarget: Int? = null,
    val limitationRegion: LimitationRegion? = null, val limitationSide: Side? = null, val painScore: Int? = null,
    val deloadStartDate: String? = null
)

@Serializable
data class Proposal(
    val id: Long, val threadId: Long, val messageId: Long, val toolUseId: String,
    val kind: ProposalKind, val payload: ProposalPayload, val rationale: String, val ruleIds: List<String>,
    val status: ProposalStatus, val validationWarnings: List<String>, val validationBlockers: List<String>,
    val createdAtMillis: Long, val decidedAtMillis: Long?, val adjustmentId: Long?
)

@Serializable
data class ReminderQuestion(val id: Long, val text: String, val dueDate: String, val askedAtMillis: Long?,
                            val answer: String?, val source: AdjustmentSource, val proposalId: Long?)
```

### 3.8 Settings (DataStore-backed, one data class)

```kotlin
@Serializable
data class Settings(
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val backend: BackendKind = BackendKind.DIRECT_API,
    val model: ModelChoice = ModelChoice.OPUS_5,
    val effort: Effort = Effort.LOW,
    val hasApiKey: Boolean = false,                   // the key itself lives in SecretStore
    val bridgeUrl: String = "", val hasBridgeSecret: Boolean = false, val bridgeEnabled: Boolean = false,
    val readinessReminderMinutes: Int? = 21 * 60,     // local time of day; null = off
    val weeklyCheckinWeekday: Int = 7, val weeklyCheckinMinutes: Int = 19 * 60,
    val cardioReminderEnabled: Boolean = true,
    val restTimerSound: Boolean = true, val restTimerVibrate: Boolean = true,
    val restTimerExactAlarmBackup: Boolean = false,   // §6.4.6
    val keepScreenOnInSession: Boolean = true,
    val monthlySpendCapUsd: Double = 30.0,            // soft cap shown in Coach; not enforced server-side
    val tunablesJson: String = "{}",                  // overrides of :core Tunables
    val onboardingCompleted: Boolean = false,
    val catalogVersionSeen: Int = 0
)
```

### 3.9 Repository interfaces (contract between `:app` data layer and everything else)

Declared in `:core` (`com.gymcoach.core.model.repo`) so planner services and tests can use fakes;
implemented in `:app` over Room.

```kotlin
interface ProfileRepository { fun profile(): Flow<Profile?>; suspend fun save(p: Profile) }
interface GymRepository {
    fun gyms(): Flow<List<GymSet>>; suspend fun get(id: Long): GymSet?
    suspend fun upsert(g: GymSet): Long; suspend fun touchLastUsed(id: Long, atMillis: Long)
    fun activeGymId(): Flow<Long?>; suspend fun setActiveGym(id: Long)
}
interface PlanRepository {
    fun activeProgram(): Flow<Program?>; suspend fun saveProgram(p: Program): Long
    suspend fun plannedSessionFor(date: String): PlannedSession?
    suspend fun savePlannedSession(s: PlannedSession): Long
    fun adjustments(limit: Int): Flow<List<PlanAdjustment>>; suspend fun addAdjustment(a: PlanAdjustment): Long
    suspend fun progress(key: ProgressKey): ExerciseProgress?; suspend fun saveProgress(p: ExerciseProgress)
    fun allProgress(): Flow<List<ExerciseProgress>>
}
interface LogRepository {
    suspend fun startSession(s: WorkoutSession): Long; suspend fun updateSession(s: WorkoutSession)
    fun activeSession(): Flow<WorkoutSession?>
    suspend fun logSet(s: SetLog): Long; suspend fun supersedeSet(oldId: Long, replacement: SetLog): Long
    fun setsForSession(sessionId: Long): Flow<List<SetLog>>
    suspend fun setsForExercise(exerciseId: String, sinceDate: String): List<SetLog>
    suspend fun sessionsBetween(from: String, to: String): List<WorkoutSession>
    suspend fun logCardio(c: CardioLog): Long; suspend fun cardioBetween(from: String, to: String): List<CardioLog>
    suspend fun upsertBodyMetric(m: BodyMetric); fun bodyMetrics(sinceDate: String): Flow<List<BodyMetric>>
}
interface CheckinRepository {
    suspend fun saveReadiness(r: ReadinessCheckin); fun readiness(sinceDate: String): Flow<List<ReadinessCheckin>>
    suspend fun readinessFor(date: String): ReadinessCheckin?
    suspend fun saveWeekly(w: WeeklyCheckin); suspend fun weeklyFor(weekStart: String): WeeklyCheckin?
    fun openReminderQuestions(): Flow<List<ReminderQuestion>>; suspend fun saveReminder(q: ReminderQuestion): Long
}
interface ChatRepository {
    fun threads(): Flow<List<ChatThread>>; suspend fun newThread(t: ChatThread): Long
    fun messages(threadId: Long): Flow<List<ChatMessage>>; suspend fun append(m: ChatMessage): Long
    suspend fun updateThreadSummary(threadId: Long, summary: String, uptoMessageId: Long)
    fun proposals(threadId: Long): Flow<List<Proposal>>; suspend fun saveProposal(p: Proposal): Long
    suspend fun updateProposal(p: Proposal); fun pendingProposals(): Flow<List<Proposal>>
    suspend fun monthSpendUsd(yearMonth: String): Double
}
interface SettingsRepository { fun settings(): Flow<Settings>; suspend fun update(f: (Settings) -> Settings) }
```

---

## 4. Planner and progression algorithm (`:core` `planner` + `progression`)

The planner is deterministic and pure: same inputs → same `Program`/`PlannedSession`. It never
reads the clock or the database directly; `:app` assembles a `PlannerInput` and stores the result.
Every modification the planner makes relative to the template is emitted as an `AppliedModifier`
with a rule ID and trigger, and shown on the session screen before the first set is logged.

### 4.1 Inputs

```kotlin
data class HistoryDigest(
    val lastSessionDateByPattern: Map<MovementPattern, String>,     // LAY-01
    val lastAnySessionDate: String?,
    val weeklyFractionalSets: Map<Muscle, List<Double>>,            // last 4 ISO weeks, VOL-09
    val zone2MinutesLast4Weeks: List<Int>,                          // CAR-05 gating
    val recentReadiness: List<ReadinessCheckin>,                    // last 7 days
    val lastWeeklyCheckin: WeeklyCheckin?,
    val illnessFlagDate: String?,                                   // LAY-02
    val bodyWeightKg7dAvg: Double?
)

data class PlannerInput(
    val today: String, val profile: Profile, val program: Program?, val gym: GymSet,
    val overrides: SessionOverrides, val equipment: EquipmentCatalog, val exercises: ExerciseCatalog,
    val progress: Map<String, ExerciseProgress>,      // by ProgressKey.key
    val history: HistoryDigest, val readiness: ReadinessCheckin?, val tunables: Tunables
)
```

### 4.2 Program build (`ProgramBuilder.build(input): Program`) — run once per mesocycle

Runs at onboarding, when goal/days/session-length/home-gym change, when a mesocycle ends
(after its deload week), and when the coach's `CHANGE_GOAL` / `CHANGE_SCHEDULE` proposals are
accepted. Mesocycle length: 6 weeks (intermediate, DLD-01 "6–8"), 5 weeks (advanced or volume at
the VOL-04 upper bound, DLD-01 "4–6"), novice: 12 weeks without a scheduled deload (DLD-01), reactive
deloads only.

**Step 1 — Split (SPLIT-01, FRQ-01/02/03, SPLIT-02).**

| daysPerWeek | Split | Day labels | Notes |
|---|---|---|---|
| 1 | FULL_BODY | Full | Below FRQ-01; VOL-01 floor only; UI says "2+ days recommended" |
| 2 | FULL_BODY | Full A, Full B | |
| 3 | FULL_BODY (novice) / UPPER_LOWER_PLUS_FULL (else) | Full A/B/C or Upper, Lower, Full | |
| 4 | UPPER_LOWER | Upper A, Lower A, Upper B, Lower B | first choice for intermediates |
| 5 | UL_PPL, or U/L/U/L + Lower-focus Full when a priority muscle is quads/hamstrings/glutes/calves | | "choose by priority muscle" |
| 6 | PUSH_PULL_LEGS ×2 (advanced) / UPPER_LOWER ×3 (else) | | |

Training weekdays come from onboarding ("which days?", default spread); heavy sessions of the same
muscle are placed ≥ 48 h apart (SPLIT-02); if the chosen weekdays make that impossible the builder
keeps the days and marks the closer session "moderate" (RIR +1, no heavy zone) with a modifier.
The split is valid only if FRQ-03 holds; otherwise the builder falls back one row (e.g. 6 → U/L ×3).

**Step 2 — Weekly volume targets per muscle (fractional sets, DEF-05).**

```
band(goal, age) =
  HYPERTROPHY, FAT_LOSS, GENERAL_FITNESS: VOL-04  novice 8–12 | intermediate 12–18 | advanced 15–25
  STRENGTH: per main lift 4–10 sets over >= 2 sessions (VOL-05) for {squat, hinge, horizontal_push,
            vertical_push or horizontal_pull} + accessories at the novice hypertrophy band
  ENDURANCE_SUPPORT: VOL-01 floor 4–6 per muscle, 2 full-body sessions
target(muscle) = band.lo at mesocycle start (VOL-08), +1/week (max +2) while RIR at target and soreness < 3/5,
                 never above band.hi or the VOL-06 per-session cap * sessions
FAT_LOSS: target = clamp(0.6..1.0 * median(weeklyFractionalSets last 4 wk), VOL-01 floor, band.hi)  (VOL-09)
priorityMuscles: target *= 1.25, capped at band.hi
```

Targets are set for the 12 "programmable" muscles (chest, front/side/rear delts, lats, upper_back,
biceps, triceps, quads, hamstrings, glutes, calves) plus abs. The rest (traps_upper, forearms,
lower_back, adductors, glute_med, rotator_cuff, hip_flexors, obliques) are "covered by compounds"
with an optional 2–6 direct-set cap (R4 §4.5) and never trigger alarms.

**Step 3 — Pattern coverage (MIX-02).** Every week must contain ≥ 1 slot each of SQUAT (or
LUNGE_SPLIT when no bilateral squat is available), HINGE, HORIZONTAL_PUSH, VERTICAL_PUSH or
INCLINE_PUSH, HORIZONTAL_PULL, VERTICAL_PULL (or a second HORIZONTAL_PULL if nothing vertical is
available), plus optional CARRY/CORE. Patterns are assigned to days by the split (Upper days: the
four upper patterns; Lower days: squat, hinge, lunge, calf; Full: all six with alternating emphasis).

**Step 4 — Exercise selection per slot (`ExerciseSelector`).**

```
candidates = exercises with slot.pattern
  ∩ available(gym = program.homeGymId, overrides = none)              // AvailabilityResolver
  ∩ difficulty allowed:  novice → BEGINNER only during the first two mesocycles;
                         intermediate → ≤ INTERMEDIATE; advanced → ADVANCED only if advancedExercisesOptIn (R4 §5.3)
  − dislikedExerciseIds
  − exercises whose sensitive flag matches an ACTIVE limitation with painScore >= 3
        (knee → kneeSensitive, shoulder → shoulderSensitive, low back → lowBackSensitive OR spinalLoading,
         elbow/wrist → ELBOW_FLEXION/EXTENSION volume halved instead of excluded, INJ-04)
score(e) = 3.0 * |primaryMuscles(e) ∩ slot.targetMuscles|
         + 1.0 if e.flags.compound
         + 1.0 if progress[e] has a workingLoad (familiarity)
         + 0.5 if the matching EquipmentOption is `preferred`
         + 0.5 if e.difficulty == BEGINNER and age == NOVICE
         − 1.5 if e already chosen for the same pattern on another day and goal != STRENGTH (variety)
         − 1.0 if e.flags.requiresSpotterOrSafeties and !loadContext.safetiesOrSpotter   // still allowed, RIR capped
pick argmax; alternates = e.substitutions filtered by availability, in catalogue order (TRV-04)
```

For STRENGTH the main-lift slots are forced to `back_squat`/`safety_bar_squat`, `conventional_deadlift`/`trap_bar_deadlift`,
`barbell_bench_press`, `overhead_press_barbell` when available (FRQ-02, same lift ≥ 2×/week);
otherwise the highest-scoring barbell compound of the pattern.

**Step 5 — Isolation fill (MIX-01/03).** After compounds are placed, compute the week's fractional
coverage per muscle. For each programmable muscle below `target.lo`, add the best available
isolation exercise for it (pattern lookup: side_delts → SHOULDER_ABDUCTION, rear_delts →
SHOULDER_ER_REAR_DELT, biceps → ELBOW_FLEXION, triceps → ELBOW_EXTENSION, hamstrings →
KNEE_FLEXION_ISOLATION, quads → KNEE_EXTENSION_ISOLATION, calves → CALF, chest → CHEST_FLY, glutes →
HIP_EXTENSION_ISOLATION, abs → CORE_*) on the day where that muscle already appears, with sets =
ceil(gap / appearances), then re-check MIX-03 (compound share 60–75 % of weekly sets; when the
session budget is tight, 100 % compounds is allowed by TIME-01).

**Step 6 — Set allocation and time budget (VOL-06/07, SESS-01/02, TIME-01/02).**

```
minutesPerSet(slot) = 4.0 heavy lower barbell (repHi <= 8) | 3.5 heavy upper barbell | 2.5 moderate compound
                    | 1.75 isolation or machine | 2.5 per superset pair (two sets)          // SESS-01
budgetSets(day) = floor((sessionMinutes − warmupMinutes) / weightedMeanMinutesPerSet)      // SESS-01: 30→8–10, 45→14–18, 60→20–26, 90→30–36
capSession = 25 (novice 20, advanced 32)                                                    // SESS-02
capMusclePerSession = 12 (default 6–10)                                                     // VOL-06
distribute target(muscle) evenly over its appearances; min 2 sets per appearance             // VOL-07
if any muscle needs > 12 sets in one session and daysPerWeek allows → add an appearance on another day (FRQ-01); else cap and emit modifier VOL-06
if sum(sets*minutes) > budget → TimeTrimmer in TIME-01 order:
   (1) warm-up to WU-05 form; (2) drop isolation sets for muscles already at VOL-01 from compounds;
   (3) pair non-competing exercises into antagonist supersets (REST-03); (4) rest on machine/isolation → 60 s (REST-04);
   (5) one exercise per pattern; (6) sets per exercise → 2 (never < 1 on the two priority slots);
   (7) move the lowest-priority pattern to another day. Weekly total stays >= VOL-01 for every muscle.
```

**Step 7 — Reps, RIR, rest (LOAD-03, RIR-03/04/05, REST-01..04, R4 §5.2 profiles).**

Rep range = `repProfile.byGoal[goal.profileKey]` with `repOverrides` applied. RIR target by class
(RIR-03): lower_barbell 2–3 (never 0), upper_barbell 1–3 (bench never 0 without safeties),
dumbbell/machine/cable compounds 1–2, isolation 0–2, bodyweight 0–2; strength phases: main lifts
2–4, accessories 1–3 (RIR-02). Week modifier (RIR-04): week 1 = target + 1, middle weeks = target,
last loading week = target − 1 with floor 1 on bar-over-body lifts and 0 on isolation
(`rirLastWeek` from the profile), deload week = 4–5 (DLD-02). Without `rack_or_spotter`, every
`requiresSpotterOrSafeties` exercise is capped at RIR ≥ 2 with a visible note (R4 §5.5). Novices:
weeks 1–2 at 3–4 RIR (PROG-05). Reported RIR on sets > 12 reps counts as +1 for progression audits
(RIR-05); one AMRAP calibration set on a machine/isolation exercise is scheduled every 3 weeks
(RIR-05), kind `CALIBRATION`, shown as "last set to failure (safe exercise)".

Rest (seconds): barbell compound with repHi ≤ 6 → 240; other barbell compound → 180 (REST-02);
other compound → 120; isolation/machine → 90 (REST-01/03); superset transition 30; TIME-01 step 4
lowers isolation/machine rest to 60; main-lift rest never below 120 (REST-04).

**Step 8 — Loads (`LoadPrescriber`, E1RM-01..04, PROG-04, TRV-04).**

```
load(slot, exercise, key) =
  progress[key].workingLoadKg  if present (plus the pending increment when DoubleProgression says so)
  else if e1rm(exercise) known from sets with 1 <= reps <= 10 in the last 14 days (E1RM-02/04):
       e1rm * pct(r_eff = repLo + rirTarget) rounded DOWN to the LoadContext step   // E1RM-03 table, linear interpolation
  else SeedLoad(exercise class, sex, trainingAge, bodyWeightKg) → marked "starting guess", and the first session
       uses the "find load" protocol: 3 sets, RIR reported each set, PROG-04 sets the next load     // TRV-04
```

`SeedLoad` is a small table of body-weight fractions for a 10-rep set (e.g. male novice: squat 0.50,
bench 0.40, row 0.40, OHP 0.25, RDL 0.50, leg press 1.0; dumbbell compounds 0.12 per hand;
isolation 0.08; female ×0.6; intermediate ×1.4; advanced ×1.7). It is a convention (weak), tagged as
such in the UI ("starting guess — adjust freely") and in `Tunables`.

Dumbbell prescriptions above `dumbbells.max_kg` are never silently reduced: the planner emits a
modifier "prescribed 24 kg > rack max 20 kg" and offers, in this order, (a) keep the exercise at
max load with the rep range shifted one zone up (LOAD-01: hypertrophy is load-agnostic 5–30 reps
near failure; RIR target −1), (b) the first available substitution (R4 §2.6). The default choice is
(a) for hypertrophy/fat-loss/general goals and (b) for strength.

**Step 9 — Warm-up (§5.1), Step 10 — Cardio (§5.3), Step 11 — Readiness/layoff/deload modifiers (§4.4).**

### 4.3 Session materialisation (`SessionMaterializer.materialize(input, dayTemplate): PlannedSession`)

Runs every time the Today screen shows a session for a date/gym combination and again on "Start"
(so the readiness score of the day is included). Steps: availability for the *active* gym +
overrides → per slot: keep `exerciseId` if available, else the first available alternate, else the
best-scoring available exercise of the pattern (Step 4 with the active gym), else drop the slot with
a modifier "no equipment for pattern X"; recompute the time budget for the active gym's session
length (the gym can carry a default session length: hotel 45, home 60) → TimeTrimmer; loads via
Step 8 with the gym-scoped `ProgressKey` for gym-scoped implements; warm-up; cardio placement;
modifiers (§4.4). Substitutions carry `substitutedFrom` + `substitutionReason` (from
`content.substitutionRationale` when authored, else "same pattern; <equipment> not available").

### 4.4 Day-of modifiers (all emitted as `AppliedModifier`, applied only to that session's targets)

| Trigger | Modifier | Rule |
|---|---|---|
| Readiness score ≥ 70 | none; pending PROG-01 increments apply | RDY-02 |
| 45–69 (Amber) | +1 RIR all sets; −1 set on every exercise after the first two; loads unchanged; no new increments | RDY-02 |
| < 45 (Red) | sets × 0.6 (round up); +2 RIR; heavy (repHi ≤ 8) barbell slots → the first available moderate-rep machine/dumbbell alternate; UI offers "25–30 min zone-2 + mobility instead" | RDY-02 |
| Sleep < 6 h last night | at least Amber; if session time is afternoon/evening, Today suggests moving it to next morning | RDY-02, SLP-02/03 |
| 7-day avg sleep < 7 h | weekly sets −10 %, RIR +1 on heavy compounds | SLP-03 |
| Resting HR ≥ +5 bpm vs 7-day mean on 2 consecutive days | Amber; ≥ +10 bpm or illness toggle → Red / skip | RDY-03 |
| Soreness ≥ 4/5 in a muscle of today's session | that muscle's heavy work moved: swap today's day with the next non-conflicting day if the template allows; else Amber modifiers on that muscle's slots | RDY-04 |
| Weekly stress ≥ 4/5 on 2 consecutive check-ins | weekly sets −20 %, main-lift RIR +1, VOL-08 ramp paused | RDY-05 |
| Days since last session of the day's pattern group 5–13 / 14–28 / 29–56 / > 56 | loads 95 % & −1 set / 85–90 % & −30 % sets & RIR +2 / 75–80 % & −50 % & RIR +2 / 60–70 % or novice start; ≥ 65 y one row more conservative | LAY-01 |
| Illness flag within 24 h | session blocked with LAY-02 text; after that, the 2–4-week row for one week; zone-2 only for 3 days | LAY-02 |
| Program week == deloadWeekIndex | sets −50 %, compounds load −10 % (machines/isolation keep load, halve sets), RIR 4–5, warm-up kept | DLD-02 |
| Week after deload | loads 95 %, sets at band.lo, RIR +1 | DLD-04 |
| Session minutes shorter than template (gym default or "I have 30 min" chip) | TIME-01 trim, TIME-02 presets | TIME-01/02 |
| Missing safeties/spotter | RIR ≥ 2 on flagged lifts, safety note | R4 §5.5 |

### 4.5 After-session progression (`DoubleProgression.update`)

```kotlin
data class SetOutcome(val reps: Int, val rir: Int, val loadKg: Double?)

fun update(p: ExerciseProgress, working: List<SetOutcome>, ctx: LoadContext, age: TrainingAge,
           loadType: LoadType, isolation: Boolean, t: Tunables, today: String): ProgressUpdate {
    if (working.isEmpty()) return ProgressUpdate.unchanged(p)
    // RIR-05: reports on >12-rep sets are optimistic by ~1 rep
    val audited = working.map { if (it.reps > 12) it.copy(rir = it.rir + 1) else it }
    val hit  = audited.all { it.reps >= p.repHi && it.rir <= p.rirTarget }          // PROG-01
    val miss = audited.count { it.reps < p.repLo } >= 2                                // PROG-01
    val autoUp = audited.all { it.rir >= p.rirTarget + 2 }                             // PROG-04
    val coarse = loadType == LoadType.DUMBBELL || loadType == LoadType.MACHINE || loadType == LoadType.CABLE
    val hitsNeeded = when {
        age == TrainingAge.ADVANCED -> 2                                               // PROG-03 (per-mesocycle, undulating)
        coarse -> 2                                                                    // R4 §5.4 two sessions for coarse jumps
        else -> 1                                                                      // PROG-03 novice per session, intermediate weekly (checked by date below)
    }
    val hits = if (hit) p.consecutiveHits + 1 else 0
    val misses = if (miss) p.consecutiveMisses + 1 else 0
    val cadenceOk = age != TrainingAge.INTERMEDIATE || daysBetween(p.lastIncrementDate, today) >= 7   // PROG-03
    val load = p.workingLoadKg
    val incrementNow = load != null && cadenceOk && (hits >= hitsNeeded || autoUp)
    return when {
        incrementNow -> {
            val inc = ctx.incrementKg
            if (load!! > 0 && inc / load > 0.10 && !p.repHiExtended)                    // PROG-02: >10 % jump → extend reps first
                ProgressUpdate(p.copy(repHi = p.repHi + 2, repHiExtended = true, consecutiveHits = 0, lastOutcome = "reps_extended"),
                               note = "Jump of ${inc} kg > 10 %: rep ceiling +2 first (PROG-02)")
            else
                ProgressUpdate(p.copy(workingLoadKg = roundToStep(load + inc, ctx), repHi = p.repHiBase, repHiExtended = false,
                                      consecutiveHits = 0, consecutiveMisses = 0, lastIncrementDate = today,
                                      lastOutcome = if (autoUp) "autoreg_up" else "hit"),
                               note = "+${inc} kg next session (${if (autoUp) "PROG-04" else "PROG-01"})")
        }
        miss -> ProgressUpdate(p.copy(consecutiveHits = 0, consecutiveMisses = misses, lastOutcome = "miss"),
                               note = "Reps below range on 2+ sets: load held (PROG-01)")
        else -> ProgressUpdate(p.copy(consecutiveHits = hits, consecutiveMisses = 0, lastOutcome = "hold"))
    }
}
```

(`repHiBase`, `repHiExtended`, `lastIncrementDate` are three more fields on `ExerciseProgress`;
omitted from §3.6 for brevity but part of the contract.) The in-session PROG-04 down-rule ("first
set RIR ≤ target − 2 → −5 % for the remaining sets") is a chip the user taps; it never rewrites a
logged number and it is recorded as a `PlanAdjustment(source = USER)`.

Bodyweight progression (`REP_PROGRESSION`): hit → +1–2 target reps until `repHi + 4`, then +2.5 kg
external load (or the next-harder variant from `substitutions` for exercises without a loadable
option), PROG-02 bodyweight row. Band progression: next band level when hit twice. Time-based
(`core_time`): +10 s per hit up to 60 s, then lever/load (R4 profile note).

### 4.6 Plateau, reactive deload and volume ramp (weekly job, Sunday night)

- **PLAT-01**: for each exercise with ≥ 3 sessions in the window, no improvement in best-set e1RM
  (sets ≤ 10 reps) nor in reps-at-load across the last 3 sessions, and readiness ≥ Amber on those
  days → plateau flag. **PLAT-02** ladder, one step per week, each surfaced as a Today card /
  coach prompt: (1) audit — the weekly check-in triggers for sleep (Q1/Q2), protein (Q3), weight
  trend (Q4) are shown together; (2) `CHANGE_REP_RANGE` proposal one zone (LOAD-04); (3)
  `SWAP_EXERCISE` proposal to a same-pattern variation; (4) `CHANGE_SETS` +1–2/week if below
  `band.hi`; (5) `SCHEDULE_DELOAD` when DLD-03 markers are present.
- **PLAT-03**: main-lift e1RM −5 % on two consecutive sessions without an external cause (illness
  flag, travel days from Q10 ≥ 3, sleep < 6 h on those days) → `SCHEDULE_DELOAD` proposal.
- **DLD-03**: ≥ 2 of {e1RM/reps −5 % ×2, Red ≥ 3 of 7 days, soreness ≥ 4 persisting > 72 h,
  sleep quality ≤ 2 on ≥ 4 nights, motivation ≤ 2 on ≥ 4 days, joint ache ≥ 3/10 (Q7)} for ≥ 5 days
  → `SCHEDULE_DELOAD` proposal (never auto-applied).
- **VOL-08**: per muscle, if all its working sets this week reported RIR ≤ target and soreness for
  that region < 3/5 → next week's target +1 set (max +2), until `band.hi` or the VOL-06 cap; when
  every programmable muscle is at its cap for a week, the mesocycle ends early and the deload week
  is scheduled (shown as a proposal with rule IDs VOL-08/DLD-01).

### 4.7 e1RM and volume statistics (`stats`)

```kotlin
object E1rm {
    fun epley(w: Double, r: Int) = w * (1 + r / 30.0)
    fun brzycki(w: Double, r: Int) = w * 36.0 / (37.0 - r)
    /** E1RM-01/02/03: mean of both for r_eff = reps + rir, capped at 10; null when reps > 10 (low confidence). */
    fun estimate(w: Double, reps: Int, rir: Int): E1rmEstimate? {
        val rEff = minOf(reps + rir, 10)
        if (reps < 1 || reps > 10) return null
        return E1rmEstimate(kg = (epley(w, rEff) + brzycki(w, rEff)) / 2, confidence = if (reps <= 6) "high" else "medium")
    }
    /** E1RM-03 %1RM table with linear interpolation between the listed points. */
    fun pct(rEff: Int): Double = interpolate(rEff, listOf(1 to 100.0, 2 to 94.0, 3 to 91.0, 4 to 88.0, 5 to 86.0, 6 to 83.0, 8 to 79.0, 10 to 75.0, 12 to 71.0, 15 to 67.0)) / 100.0
}
```

Progress uses the 14-day rolling best (E1RM-04); deadlift variants show the note "equations
under-estimate deadlift 1RM"; machines/cables/smith show best load × reps instead of e1RM.
`VolumeAccounting.weekly(setLogs, catalogue)` returns per muscle per ISO week both `direct`
(weight-1.0 sets) and `fractional` (Σ weight; warm-ups 0) and the `back` aggregate (lats ∪
upper_back counted once, R4 §4.1); the Progress screen compares `direct` to RP landmarks and
`fractional` to the program's own `VolumeBand` (R4 §4.2).

### 4.8 Tunables (weak-tagged constants exposed in Settings → Advanced)

| Field | Default | Bounds | Rule |
|---|---|---|---|
| `hypertrophyBand[novice/intermediate/advanced]` | 8–12 / 12–18 / 15–25 | 4..30 | VOL-04 |
| `strengthSetsPerLift` | 4–10 | 2..14 | VOL-05 |
| `perSessionMuscleCap` | 12 | 6..16 | VOL-06 |
| `sessionSetCap[novice/intermediate/advanced]` | 20 / 25 / 32 | 10..40 | SESS-02 |
| `minutesPerSet[heavyLower/heavyUpper/compound/isolation/supersetPair]` | 4.0 / 3.5 / 2.5 / 1.75 / 2.5 | 1..6 | SESS-01 |
| `restSec[heavyBarbell/barbell/compound/isolation/superset]` | 240 / 180 / 120 / 90 / 30 | 30..360 | REST-01..04 |
| `volumeRampPerWeek` | +1 (max +2) | 0..2 | VOL-08 |
| `deloadWeeks[intermediate/advanced]` | 6 / 5 | 3..10 | DLD-01 |
| `deloadSetsFactor`, `deloadLoadFactor` | 0.5, 0.85 | | DLD-02 |
| `plateauSessions` | 3 | 2..5 | PLAT-01 |
| `regressionPct` | 5 | 3..10 | PLAT-03 |
| `readinessBands` | Green ≥ 70, Amber ≥ 45 | | RDY-02 |
| `readinessWeights` | as RDY-01 table | | RDY-01 |
| `rhrAmberDelta`, `rhrRedDelta` | +5, +10 bpm | | RDY-03 |
| `layoffRows` | as LAY-01 | | LAY-01 |
| `seedLoadFractions` | as §4.2 step 8 | | convention |
| `activityFactor` | 1.375 | 1.2..1.9 | NUT-04 |
| `kcalPerKg` | 7700 | 6000..8000 | NUT-04 |
| `adjustStepKcal` | 150 | 100..200 | NUT-16 |
| `stepsTarget` | 7,500 (< 60 y) / 6,500 | | STP-01 |

### 4.9 Worked example — Tuesday at the hotel (moment M2/M3)

Profile: male, born 1988, 180 cm, 84 kg, intermediate (18 months), hypertrophy, 4 days/week, 60 min
at home. Program: UPPER_LOWER, week 3 of 6. Home template "Upper A" (18 sets, 60 min):
barbell_bench_press 3×5–10 @RIR 2, barbell_row 3×8–12 @2, seated_dumbbell_shoulder_press 3×8–12 @2,
lat_pulldown 3×8–12 @2, cable_lateral_raise 2×12–20 @1, cable_triceps_pushdown 2×10–15 @1,
dumbbell_curl 2×10–15 @1.

Today: gym "Hotel Ulm" = hotel_gym preset + functional_trainer (with rope) + cable_rope; dumbbells
max 20 / step 2; default session length 45 min. Readiness quick check-in: 6.5 h sleep, feel 3/5,
soreness 2 → score 58 → Amber.

| Slot | Home | Hotel Ulm result | Why (modifier) |
|---|---|---|---|
| 1 | barbell_bench_press 3×5–10 @2, 80 kg | dumbbell_bench_press 2×15–20 @RIR 1 at 20 kg/hand | bb_loaded unavailable → alternate 1 (TRV-04); progress says DB bench 26 kg > rack max 20 → option (a): rep range shifted up (LOAD-01), RIR −1; Amber: sets −1 does not apply to the first two exercises |
| 2 | barbell_row 3×8–12 | chest_supported_dumbbell_row 3×8–12 @3, 20 kg/hand | bb_loaded unavailable → alternate; Amber +1 RIR |
| 3 | seated_dumbbell_shoulder_press 3×8–12 | same, 2×8–12 @3, 16 kg | Amber: −1 set after the first two |
| 4 | lat_pulldown 3×8–12 | lat_pulldown (option `cable_any`, half-kneeling at the functional trainer) 2×8–12 @3, gym-scoped load unknown → "find load" | machine absent → second option of the same exercise; Amber −1 set; TRV-04 find-load |
| 5 | cable_lateral_raise 2×12–20 | dumbbell_lateral_raise 2×12–20 @2, 8 kg | cable option exists but dumbbells preferred (familiar load); superset with slot 6 (TIME-01 step 3) |
| 6 | cable_triceps_pushdown 2×10–15 | same at the functional trainer, 1×10–15 @2 | Amber −1 set; supersetted with 5 |
| 7 | dumbbell_curl 2×10–15 | dumbbell_curl 1×10–15 @2, 12 kg | Amber −1 set; time budget |

Warm-up (WU-05, ≤ 45 min): no Raise phase; drills band_pull_apart 2×10 + wall_slide 2×8 (3 min);
ramp for slot 1: 10 kg × 8, 14 kg × 5 (WU-02 moderate row, rounded to the 2-kg rack). Total 13
working sets, estimated 44 min. Session card shows the Amber banner with "RDY-02: +1 RIR, −1 set
after the first two exercises (score 58: sleep 6.5 h, energy 3/5)" and a "Train as planned" switch.
Every substitution row shows its reason; nothing was changed silently.

---

## 5. Warm-up, mobility, cardio, nutrition, supplements, check-ins

### 5.1 Warm-up builder (`warmup.WarmupBuilder`)

Structure (WU-01, RAMP): **Raise** 3–5 min → **Activate/Mobilise** 3–5 min of dynamic drills →
**Potentiate** = ramp sets of the first exercise. Total 8–12 min; the session card shows the warm-up
as a collapsible block with a "Skip warm-up" that requires a reason chip (so the skip is a logged
decision, not an accident).

- **Raise**: picks from available cardio equipment (bike/rower/elliptical preferred, treadmill walk
  otherwise) or "2 min brisk march + 20 bodyweight squats + 10 push-ups" without equipment.
  8–10 min plus one extra ramp set when WU-06 applies: age ≥ 40, session start within 60 min of
  `usualWakeTimeMinutes`, or the user tapped the "Cold" chip (no temperature sensor is used).
- **Drills** (MOB-01: 2 × 8–10, 3–5 drills), chosen by the session's first two patterns from the
  MOB-07 table, expressed with R4 mobility exercise ids:

| Session patterns | Drills (in order) |
|---|---|
| SQUAT / LUNGE_SPLIT | bodyweight_squat (pause at bottom), hip_90_90, ankle_dorsiflexion_rock, worlds_greatest_stretch |
| HINGE | cat_cow, glute_bridge, band_lateral_walk, worlds_greatest_stretch |
| HORIZONTAL_PUSH / INCLINE_PUSH | band_pull_apart, shoulder_pass_through, wall_slide |
| VERTICAL_PUSH | shoulder_pass_through, wall_slide, thoracic_rotation |
| HORIZONTAL_PULL / VERTICAL_PULL | band_pull_apart, thoracic_rotation, dead_hang (20 s) |
| Full body | bodyweight_squat, band_pull_apart, worlds_greatest_stretch, cat_cow |

Active limitations add their MOB-07 row (e.g. shoulder → band_external_rotation 2 × 15).

- **Ramp sets** (WU-02) computed from the day's first working load and rounded to the
  `LoadContext` (barbell: to `2 × smallestPlate`, never below the empty bar; dumbbells: to the rack
  step; stacks: to the stack step):

| First exercise | Ramp |
|---|---|
| heavy compound (working ≥ 80 % 1RM, repHi ≤ 8) | 40 % × 8, 55 % × 5, 70 % × 3, 85 % × 1–2 |
| moderate compound (8–15 reps) | 50 % × 8, 70 % × 4–5 |
| light compound / machine compound | 60 % × 8 |
| isolation | 1 × 8–10 at 55 % (skipped if the muscle is already warm from a prior exercise) |
| later exercises of an already-warm pattern | 0–1 set at 65 % |

Rest between ramp sets 45–90 s (shown in the timer as a short rest). Ramp sets are logged with
`SetKind.WARMUP` and never count toward volume (WU-03, DEF-04).
- **WU-05** (session ≤ 45 min): no Raise, two drills (≈ 2–3 min), ramp sets only.
- **Static stretching before lifting**: never ≥ 60 s per muscle (WU-04); the pre-session block
  contains no static holds at all; the chronic ROM work goes to the post-session block (MOB-04).

### 5.2 Mobility (chronic dose)

- A limitation marked "limited range" (hips, thoracic spine, shoulders, ankles, hamstrings, wrists)
  gets a **weekly ROM dose**: 5–10 min per joint per week, 30–60 s holds, 2–4 holds per day on ≥ 5
  days (MOB-02), scheduled as a post-session block and a rest-day card ("Hip mobility · 4 min").
- Full-ROM strength work counts: 1 full-ROM working set of a mapped exercise ≈ 30 s of stretch
  credit for that joint (MOB-03; map: hips ← goblet_squat/back_squat/split_squat/romanian_deadlift;
  T-spine ← overhead_press_barbell/pull_up/front_squat; shoulders ← landmine_press/face_pull;
  ankles ← standing_calf_raise/seated_calf_raise). The Progress screen shows "mobility minutes /
  joint / week" with the credit shown separately from the hold time.
- Foam rolling (MOB-05) and cool-down (MOB-06) are optional toggles per gym set (foam_roller
  present) and never scheduled by default.
- Mobility completions are logged as `SetLog` rows with the mobility exercise id and `seconds`;
  because mobility exercises carry no muscle weights, volume accounting ignores them by construction.

### 5.3 Cardio (`cardio.CardioPlanner`)

Weekly prescription by goal (CAR-07), placed by CAR-06, expressed in the equipment the active gym has
(CAR-08):

| Goal | Prescription |
|---|---|
| GENERAL_FITNESS | 2 × 30–45 min zone 2 + 1 HIIT (once eligible) → meets CAR-01 |
| HYPERTROPHY / STRENGTH | 2 × 20–30 min zone 2 (health floor); HIIT optional 1×, never on or the day before a heavy leg day |
| FAT_LOSS | 150–250 min/week moderate (ramping to > 250 if progress stalls, NUT-16 loop); STP-02 step target; HIIT not favoured for weight (Jakicic 2024) |
| ENDURANCE_SUPPORT | the user's own endurance plan is primary; RT 2×/week at VOL-01/02; lifting ≥ 6 h from key cardio |

Placement rules (CAR-06): (1) non-lifting days first; (2) if a lifting day, after lifting (ORD-03)
or a separate slot ≥ 6 h away (the user picks "morning" or "evening" for the separate slot;
24 h separation is preferred when the week allows); (3) no hard running within 24 h before a heavy
leg day; bike/rower/elliptical/ski-erg are ranked above treadmill running next to leg days (CAR-08);
(4) hypertrophy/strength: ≤ 3 sessions and ≤ 150 min/week beyond the floor unless the user asks.

Intensity (CAR-02/03/04): `HRmax = 208 − 0.7·age` unless a measured max is entered; zone 2 =
60–70 % HRmax, or 40–59 % HRR when a resting HR exists (Karvonen); shown alongside the talk test
("full sentences yes, singing no") and RPE 3–4/10 so a session without a HR strap is still
prescribable. HIIT menu (CAR-05): 4 × 4 min @ 90–95 % HRmax / 3 min @ 70 % (default), 10 × 1 min
@ ~90 % / 60–75 s easy, SIT 4–6 × 30 s all-out / 4 min (advanced only). Gating: ≥ 4 weeks with ≥ 2
zone-2 sessions/week logged, and no RED-03 medical flag without a recorded clearance. Progression
(CAR-09): +10 % duration per week to the target; add one interval before raising intensity (4 × 4 →
up to 6; 10 × 1 → up to 12).

UI: cardio is its own card on Today ("Zone 2 · Bike · 30 min · HR 112–131 · full sentences OK") with
Start (elapsed timer + optional avg-HR and RPE at the end), Log (manual minutes/modality), Skip
(reason chip). Planned cardio on a non-lifting day gets a reminder notification at the user's slot
time (§6.9).

### 5.4 Nutrition (`nutrition`)

Decision: **no food database and no calorie logging in v1.** The app computes targets, tracks body
weight, and adjusts targets from the weight trend (NUT-16); intake is captured only as two ordinal
chips on the expanded readiness sheet ("protein target hit? yes/no", "meals: light/normal/heavy")
and an optional single daily kcal number. That is what a travelling technician can actually do,
and NUT-15/16 only need the weight trend.

- **Energy** (NUT-04): REE Mifflin–St Jeor (men `10W + 6.25H − 5A + 5`, women `… − 161`);
  TDEE = REE × `activityFactor` (default 1.375; the onboarding maps "desk/driving < 5k steps" → 1.2,
  "1–3 sessions or 5–8k steps" → 1.375, "3–5 sessions or 8–12k or physical job" → 1.55, "6–7 sessions
  or physical job + training" → 1.725). Adaptive TDEE correction runs every 14 days only when
  ≥ 10 of the last 14 days have a kcal entry; otherwise the rate-based loop (below) adjusts the
  target directly. Both show "estimated (NUT-04)" next to the number.
- **Targets**: protein 1.6–2.2 g/kg (hypertrophy/strength), 2.0–2.6 g/kg BW for lean users in a
  deficit (2.3–3.1 g/kg FFM when body-fat % is entered), 1.4–2.0 general, ≥ 1.2 at ≥ 65 y, ≥ 2.2 in
  recomposition (NUT-01); carbs ≥ 3 g/kg with the NUT-08 bands by training hours; fat 20–35 % E,
  floor 0.5 g/kg (NUT-09); fibre 14 g/1,000 kcal (NUT-10); water 3.7/2.7 L (NUT-11). Displayed as
  ranges with rule IDs, never as a diet plan.
- **Rate control** (NUT-05/06/07/15/16/17): daily AM weight prompt inside the readiness sheet;
  7-day simple moving average; day-to-day noise ≈ 0.4–0.6 kg and a single-day change ≥ 1 % is
  labelled noise (NUT-15); every 14 days the change of the 7-day average is compared with the goal
  band (FAT_LOSS 0.5–1.0 %/week, 0.5–0.7 when lean; lean gain 0.25–0.5 %/week, advanced
  0.1–0.25; recomp: maintenance to −200 kcal) and the kcal target is moved by ±100–200 kcal as an
  `UPDATE_NUTRITION_TARGETS` proposal card that shows the trigger values (NUT-16). Deficits longer
  than 8 weeks or hunger ≥ 4/5 on two weekly check-ins (Q15) → "1 week at maintenance" proposal
  (NUT-17). Creatine start → an expected +1–2 kg water note on the trend chart (SUP-01).
- **Travel** (NUT-14): a Knowledge card and a Coach quick prompt ("Hotel breakfast: what do I
  eat?") carry the protein-anchor, plate-rule and petrol-station-kit heuristics; the weight trend
  suppresses adjustments in a week that contains ≥ 3 travel days (Q10) or restaurant/sodium days
  when the user marks them.
- **Alcohol** (NUT-13): "drinks last night" chip on the expanded readiness sheet; ≥ 3 → Amber floor
  for that day; weekly Q9 > 7 → coach prompt.

### 5.5 Supplements (`supplements`)

Static knowledge with tiers exactly as R3 §7 (A: creatine 3–5 g/day, caffeine 3–6 mg/kg with the
Gardiner cut-offs, protein powder as food, conditional vitamin D and electrolytes; B: beta-alanine,
citrulline malate, melatonin for travel, omega-3, low-dose multivitamin; C: the do-not list). The
SUP-12 caveat is rendered at the top of every supplement card and every coach answer that mentions
a supplement (the system prompt requires it). RED-03: when any `MedicalFlag` is set, dosing numbers
are hidden behind "discuss with your physician/pharmacist first" until a "clearance obtained" date
is entered. Caffeine cut-off (SUP-02): computed from `usualBedTimeMinutes` — Today shows "Caffeine
cut-off 10:00 for a 200 mg pre-workout, 14:00 for a coffee" on training days with an afternoon or
evening session. Melatonin shows the locale note ("prescription-only in Germany at ≥ 2 mg" —
NOT VERIFIED per R3 §12.13; wording says "check local rules").

### 5.6 Check-ins (`checkin`, `readiness`)

**Daily readiness — quick mode (3 taps + Done).** One sheet, three chip rows, opened from Today's
readiness chip or from the reminder notification:

| Row | Chips | Maps to RDY-01 items |
|---|---|---|
| Slept | < 5 · 5–6 · 6–7 · 7–7.5 · 7.5+ h | sleepHours (bucket midpoint) |
| Feel | 1 · 2 · 3 · 4 · 5 (faces) | energy AND motivation (same value) |
| Sore | 1 · 2 · 3 · 4 · 5 | soreness (worst region) |

Quick mode leaves `sleepQuality` and `stress` unset; the score is renormalised over the answered
items (RDY-01 "missing optional inputs are ignored") and the sheet says "assumed: sleep quality,
stress (tap to fill)". "More" expands the full RDY-01 form: sleep quality 1–5, stress 1–5, energy
and motivation separately, resting HR (number), plus the nutrition chips of §5.4, "drinks last
night", an "I'm ill" toggle (LAY-02) and the morning weight field. Score → band → the RDY-02
modifiers, displayed on Today immediately with rule IDs, and the user may still tap "Train as
planned" (recorded as a `PlanAdjustment(source = USER)`).

**Weekly check-in (Sunday evening by default, ≤ 8 items, CHK-01).** Selection: always Q8 and Q4
(auto-computed, shown for confirmation), Q1 (or Q2 on alternate weeks), Q3, Q7; then rotate
Q5/Q6/Q9/Q10/Q11/Q12/Q13/Q14/Q15 so each appears at least every third week, Q15 only in a deficit,
Q10 always in the week before a travel week if the user marked travel days; plus any open
`ReminderQuestion` from the coach. Every answer maps to a trigger (CHK-01 table) that produces
either a planner flag (RDY-05, SLP-03, VOL-08 pause, TIME-01 re-plan), a proposal card (deload,
TRV-01 travel plan, NUT-16/17), or a coach quick prompt with the answers pre-attached.

**Red flags.** RED-01: a persistent "Something's wrong" entry in the session overflow menu opens
the stop-and-refer list; selecting any item ends the session, blocks training advice for that
region, shows the hand-off text (emergency services for the first four items), and logs a
`PlanAdjustment` with rule RED-01. RED-02: Q7 "yes" for the same region on two consecutive weeks or
pain > 3/10 for > 10 days → "see a physiotherapist/physician" card; INJ substitutions continue for
other regions only. RED-03: medical flags collected at onboarding; the gate is re-checked before
HIIT, before the max-strength zone (LOAD-04) and before any supplement dosing; clearance date
stored on the profile.

---

## 6. UX — screens, navigation, interactions, states

### 6.1 Navigation graph

Single `MainActivity`, one `NavHost`, `@Serializable` routes. Five bottom-bar tabs; everything
else is a full-screen destination pushed on top (bottom bar hidden) or a `ModalBottomSheet` owned
by the screen that opens it.

```kotlin
package com.gymcoach.app.ui.nav
import kotlinx.serialization.Serializable

// Tabs (bottom bar visible)
@Serializable object TodayRoute
@Serializable object PlanRoute
@Serializable object ProgressRoute
@Serializable object CoachRoute
@Serializable object MoreRoute

// Full-screen destinations (bottom bar hidden)
@Serializable object OnboardingRoute
@Serializable data class SessionRoute(val sessionId: Long)
@Serializable data class SessionSummaryRoute(val sessionId: Long)
@Serializable object GymListRoute
@Serializable data class GymEditorRoute(val gymId: Long?, val fromPreset: String? = null)
@Serializable data class ExerciseDetailRoute(val exerciseId: String)
@Serializable object ExerciseLibraryRoute
@Serializable data class DayTemplateRoute(val programId: Long, val dayIndex: Int)
@Serializable data class E1rmTrendRoute(val exerciseId: String)
@Serializable object VolumeRoute
@Serializable object BodyWeightRoute
@Serializable object WeeklyCheckinRoute
@Serializable object NutritionRoute
@Serializable object KnowledgeRoute
@Serializable data class RuleDetailRoute(val ruleId: String)
@Serializable object SupplementsRoute
@Serializable object AdjustmentLogRoute
@Serializable data class ChatThreadRoute(val threadId: Long)
@Serializable object SettingsRoute
@Serializable object SettingsProfileRoute
@Serializable object SettingsNotificationsRoute
@Serializable object SettingsCoachRoute
@Serializable object SettingsDataRoute
@Serializable object SettingsAdvancedRoute
@Serializable object AboutRoute
```

Edges (→ push, ⇢ sheet):

- Start → `OnboardingRoute` if `!settings.onboardingCompleted`, else `TodayRoute`; if a
  `WorkoutSession` with status ACTIVE/PAUSED exists → straight to `SessionRoute`.
- Today → ⇢ Gym switcher, ⇢ Readiness sheet, ⇢ Session-start sheet → `SessionRoute`; →
  `GymEditorRoute`, `WeeklyCheckinRoute`, `NutritionRoute`, `ChatThreadRoute` (from a proposal
  card), `SessionSummaryRoute` (yesterday's summary), `AdjustmentLogRoute`.
- Session → ⇢ plates, swap, info, notes, red-flag, finish sheets; `SessionSummaryRoute` on finish
  (Session popped); back button = "Pause & minimise" (session stays active; FGS keeps running);
  "Abandon" is inside the overflow menu with confirmation.
- Plan → `DayTemplateRoute` → ⇢ slot editor; `AdjustmentLogRoute`; `ExerciseDetailRoute`.
- Progress → `E1rmTrendRoute`, `VolumeRoute`, `BodyWeightRoute`.
- Coach → `ChatThreadRoute` (thread list is the tab root; the latest thread opens directly if it
  was used in the last 24 h).
- More → `GymListRoute`, `ExerciseLibraryRoute`, `KnowledgeRoute`, `SupplementsRoute`,
  `NutritionRoute`, `SettingsRoute` (+ sub-routes), `AboutRoute`.
- Notification deep links (extra `nav` on the launch intent): `readiness` (Today + readiness
  sheet open), `session` (active session), `weekly` (`WeeklyCheckinRoute`), `cardio` (Today with
  the cardio card expanded), `coach` (`ChatThreadRoute` of the thread with the pending proposal).

### 6.2 Global conventions

- **Type scale**: numbers on the session screen use `displayMedium` (45 sp) for the current load
  and reps and `titleLarge` for targets; everything else Material defaults. Minimum body 16 sp.
- **Targets**: 56 dp for steppers and chips on the session screen, 64 dp for "Log set"; 48 dp
  elsewhere.
- **Readiness colours**: Green/Amber/Red use the theme's tertiary/secondary/error containers with
  the band word always printed (never colour alone).
- **`RuleTag`**: a small outlined chip with the rule ID (e.g. `RDY-02`); tap → `RuleDetailRoute`
  with statement, confidence tag and citation from `RuleText`. Appears wherever a number was
  derived or changed (POL-01).
- **Undo**: every apply (proposal, swap "rest of program", readiness override, set correction) shows
  a snackbar with Undo for 5 minutes; undo reverts using `PlanAdjustment.beforeJson` and marks the
  adjustment `undoneAtMillis`.
- **Offline banner**: only on Coach screens ("Offline — you can read history and draft one
  question; it is sent when you are back online"). No other screen mentions the network.
- **Units**: storage metric; `UnitSystem.IMPERIAL` converts at display and parses input back to kg
  with 0.25 kg resolution; the stored value is always the kg the user saw converted, shown with
  both units on the log row for one second after logging ("36 kg / 80 lb").
- **Empty and error components**: `EmptyState(icon, title, body, primaryAction)` and
  `ErrorState(title, body, retry, details)`; every list screen has both.
- **Loading**: skeleton rows, never a blocking spinner, except the catalogue load on first start
  (< 1 s expected; 156 exercises + 77 equipment items).

### 6.3 Screen catalogue

Each screen: purpose · layout · state · interactions · empty/error · navigation.

#### S1 Onboarding (7 pages, ≤ 3 min)

Progress dots on top, one primary button at the bottom, "Skip" on pages 4 and 6 only. Values are
saved to DataStore as the user goes (rotation/kill-safe); returning to the app resumes on the same
page.

| Page | Fields (defaults) | Notes |
|---|---|---|
| 1 Welcome | "Start" / "Restore from backup" | Restore opens the SAF file picker (§8.6). |
| 2 About you | Sex (2 chips), birth year (stepper, default 1990), height cm (numeric, 178), weight kg (numeric, 80), unit toggle | 4 fields; keyboard opens only on the numeric fields. |
| 3 Goal & schedule | Goal (5 cards with one-line meaning, default GENERAL_FITNESS), days/week (chips 1–6, default 3), which weekdays (7 toggles, default Mon/Wed/Fri), session length (chips 30/45/60/90, default 60) | The card copy is R3 DEF-06. |
| 4 Experience & limits (skippable) | Training age cards (novice/intermediate/advanced with DEF-01 wording) or "months consistent" stepper; limitations chips (knee/shoulder/low back/elbow-wrist/hip/ankle/none) each with side + pain 0–10 slider; medical flags checklist (RED-03) default none | Skipping = novice, no limitations, no flags — stated on the page. |
| 5 Your gym | Preset cards: "Commercial gym", "Home gym", "Hotel gym", "Bands + bodyweight only"; name field prefilled ("Home gym"); "Review equipment" secondary button opens the Gym editor prefilled | The preset becomes the first `GymSet` and the program's home gym. |
| 6 Reminders (skippable) | Readiness reminder time (hour/minute steppers, 21:00), weekly check-in day+time (Sun 19:00), cardio reminders toggle; on API 33+ the `POST_NOTIFICATIONS` prompt is requested here after one sentence of rationale (verified 2026-09-05: off by default for apps targeting 33+; requesting in context is the documented practice) | If denied, page shows "Reminders off — you can enable them in Settings"; nothing else changes. |
| 7 Your week | The generated `Program` as a 7-day strip with day labels and set counts, plus "Coach chat needs an API key — set up later in Settings" one-liner | "Start" → Today. The program is built here (`ProgramBuilder`), on `Dispatchers.Default`, < 200 ms expected. |

State: `OnboardingUiState(page: Int, draft: ProfileDraft, gymDraft: GymSet?, notificationGranted: Boolean?, building: Boolean, error: String?)`.
Errors: invalid numbers are clamped with an inline message (height 120–230 cm, weight 30–250 kg,
birth year 1920–2015); the build failing (should not happen) shows `ErrorState` with "Retry" and
"Continue without a plan".

#### S2 Today (tab root)

Layout (top → bottom, `LazyColumn`):
1. Header row: date; **Gym chip** "Harry's gym ▾" (tap ⇢ Gym switcher); **Readiness chip** "Check in"
   or "Amber 58" (tap ⇢ Readiness sheet).
2. **Modifier banner** (only if modifiers exist): "Amber (58): +1 RIR, −1 set after the first two
   exercises `RDY-02`" with a "Train as planned" switch; layoff/deload/time-trim lines likewise.
3. **Session card**: day label, estimated minutes, N exercises, muscle chips; below, the exercise
   list compact ("DB bench 2×15–20 @1 · 20 kg" with a substitution icon where applicable); buttons
   **Start** (primary, full width) · Preview · "I have 30 min" chip (TIME-02 re-plan) · "Skip today"
   (reason chip; moves the day if the week allows, else marks skipped).
4. **Cardio card** (if planned today): §5.3.
5. **Pending cards**: coach proposals awaiting decision (compact `ProposalCard`), weekly check-in
   due, plateau/deload ladder card, NUT-16 adjustment, RED-02 referral, reminder questions.
6. **Nutrition strip**: weight quick entry (numeric + "Save"), 7-day average and 14-day delta,
   protein target, caffeine cut-off on training days.
7. **Yesterday**: one-line summary link to `SessionSummaryRoute`.

State:

```kotlin
data class TodayUiState(
    val date: String, val gym: GymSet?, val readiness: ReadinessCheckin?,
    val planned: PlannedSession?, val activeSession: WorkoutSession?,
    val modifiers: List<AppliedModifier>, val trainAsPlanned: Boolean,
    val cardio: PlannedCardio?, val cardioLoggedToday: Boolean,
    val pendingProposals: List<Proposal>, val weeklyCheckinDue: Boolean,
    val ladderCard: LadderCard?, val nutrition: NutritionStrip, val yesterday: SessionDigest?,
    val restDay: Boolean, val noProgram: Boolean, val loading: Boolean
)
```

Empty/alternate states: `noProgram` → `EmptyState("No plan yet", "Answer four questions and get a week", "Create plan")` (re-opens onboarding pages 3–5 as a sheet); `restDay` → card "Rest day · Mobility 8 min / Zone-2 walk 30 min" with optional Start; `activeSession != null` → the session card becomes "Resume session · 23 min elapsed"; gym missing (archived) → gym chip reads "Pick a gym" and Start is disabled until one is picked.

#### S3 Gym switcher (sheet)

List of gyms ordered by `lastUsedAtMillis` desc, each row: colour dot, name, "used Tue", number of
available exercises for today's session ("12/13 exercises"), a warning icon if any prescribed
dumbbell load exceeds the rack max. Rows: **Recent gyms** (max 5) · **All gyms** · "New gym from
preset ▸" (Commercial / Home / Hotel / Bands only) → `GymEditorRoute(null, preset)` · "Duplicate
current gym" (→ editor prefilled, name "Copy of …"). Selecting a gym re-materialises today's
session; a toast summarises "3 substitutions" with a "Show" action that scrolls to the session card.

#### S4 Gym editor (full screen, ≤ 60 s from a preset)

Layout: name field + 8 colour dots; "Start from" preset chips (replaces the whole list after a
confirmation when items already exist); **search field** matching names and DE/EN aliases across
all categories ("Latzug" finds `lat_pulldown_machine`); 8 collapsible category sections with counts
("Free weights 6/8"); each row = checkbox + name + inline parameter controls when checked
(`dumbbells`: max kg stepper (2-kg steps) and step chips 1/2/2.5; `plates`: smallest plate chips
1.25/2.5/5 + microplates switch; `power_rack`: safeties switch, pull-up bar switch; cable
machines: stack step chips 5/2.5 and half-step add-on switch; `kettlebells`: kg chips 8–32); a
sticky footer with **live availability**: "34 items · 96 exercises available · Save". "Travel kit"
switch merges the packable items (R4 §2.5). "Same as another gym" copies items from a chosen gym
(then edit). Long-press on a row shows the equipment description and aliases.

State: `GymEditorUiState(gym: GymSet, expandedCategories: Set<EquipmentCategory>, query: String, availableExercises: Int, dirty: Boolean, saving: Boolean, error: String?)`.
Empty: no items ticked → footer reads "0 items · 41 bodyweight/band exercises available" (never
zero — the `—` exercises are always available). Error: save failure → snackbar with retry; the
draft stays.

#### S5 Session-start sheet ("Anything different today?")

Opened by **Start**. Rows: "Spotter available" switch (satisfies `rack_or_spotter`); "Missing
today" multi-select chips of the gym's equipment (busy/broken); "Extra today" (search-add);
"Session length" chips (gym default highlighted); readiness reminder if not yet checked in
("Quick check-in — 3 taps" inline, same three rows as the readiness sheet). **Start session** →
materialise with `SessionOverrides` → `WorkoutSession` row (status ACTIVE) → start the
foreground service → `SessionRoute`.

### 6.4 S6 Session (active workout) — the product

#### 6.4.1 Layout (portrait, one hand)

```
┌──────────────────────────────────────────────┐
│ ‹ Pause   Upper A · Hotel Ulm   23:14   ⋮    │  top bar: elapsed, overflow (notes, red flag, abandon)
│ ▭▭▭▭▭▭▭ progress: 7 of 13 sets              │
├──────────────────────────────────────────────┤
│ ▸ Warm-up (done)                              │  collapsible block
│ ● DB Bench Press          set 2 of 2   ⓘ ⇄   │  current exercise header: info, swap
│   last: 20 × 18 @1 (Tue)   target 15–20 @1   │
│   ┌────────┐   ┌────────┐   RIR              │
│   │ −  20  + │   │ −  18  + │   ⓪ ① ② ③ ④+     │  load stepper (long-press → plates), reps stepper, RIR chips
│   └────────┘   └────────┘                    │
│   ✓ 1  20 kg × 17 @1                          │  logged sets (tap → correct)
│   ○ 2  20 × 15–20 @1  ← you are here          │
│ ○ Chest-supported DB row   3×8–12 @3   ⇄     │  upcoming exercises (tap to jump)
│ ○ DB shoulder press         2×8–12 @3         │
│ …                                             │
├──────────────────────────────────────────────┤
│ ⏱ Rest 1:42  [ +30s ] [ skip ]                │  rest bar (only while resting)
│ ┌──────────────────────────────────────────┐ │
│ │               LOG SET                    │ │  64 dp primary, always visible
│ └──────────────────────────────────────────┘ │
└──────────────────────────────────────────────┘
```

Rules: the current exercise is always expanded and pinned under the warm-up block; upcoming
exercises are collapsed rows; done exercises collapse to one line with a check. The bottom 40 %
holds the steppers, RIR chips, rest bar and Log button. Nothing on this screen needs a keyboard;
tapping a number opens a numeric keypad sheet.

State:

```kotlin
data class SetDraft(val loadKg: Double?, val reps: Int?, val rir: Int?, val seconds: Int?, val metres: Int?)

data class ExerciseRow(
    val slotId: String, val exerciseId: String, val name: String, val loadType: LoadType,
    val planned: PlannedExercise, val logged: List<SetLog>, val status: RowStatus /* PENDING, CURRENT, DONE, SKIPPED */,
    val lastTimeSummary: String?, val hint: String?, val substitutedFrom: String?, val notes: List<String>
)

data class RestTimerState(val endsAtMillis: Long, val totalSec: Int, val forSlotId: String, val paused: Boolean)

data class SessionUiState(
    val phase: SessionPhase /* LOADING, WARMUP, WORKING, FINISHING, DONE, RECOVERED, ERROR */,
    val session: WorkoutSession?, val planned: PlannedSession?, val gymName: String,
    val elapsedSec: Int, val progressText: String,
    val warmup: WarmupPlan?, val warmupDone: Boolean,
    val rows: List<ExerciseRow>, val currentIndex: Int, val currentSetIndex: Int,
    val draft: SetDraft, val stepKg: Double, val unit: UnitSystem,
    val rest: RestTimerState?, val modifiers: List<AppliedModifier>,
    val inSessionHints: List<InSessionHint> /* PROG-04 down, "next time +2.5" */,
    val sheet: SessionSheet? /* PLATES, SWAP, INFO, NOTES, RED_FLAG, FINISH, KEYPAD, CORRECT_SET */,
    val error: String?
)
```

#### 6.4.2 Set logger

- The draft is prefilled from `PlannedSet` (target load, `repHi` for the first set, then the last
  logged reps of the same exercise this session, target RIR). Prefill never rounds the user's
  previous entry.
- **Load stepper** step = `LoadContext.incrementKg` (barbell 2.5 → 1.0 with microplates; dumbbell
  = rack step; stack = stack step, half-step if present; bodyweight = 2.5 external, shows "+kg");
  long-press the number → plate calculator sheet; tap → keypad sheet.
- **Reps stepper** ±1; tap → keypad. **RIR chips** 0 · 1 · 2 · 3 · 4+ (target highlighted); unset
  is allowed (logged as null) but the chip row nudges with a subtle outline after logging twice
  without RIR (RIR reports drive PROG-04).
- Unilateral exercises show "per side" under the reps; one logged set = both sides (UNI-02); the
  stepper pair is the same. Time-based sets show a seconds stepper and a Start/Stop that fills the
  seconds; distance sets a metres stepper.
- **Log set** writes exactly the displayed values (POL-01), plays a short haptic, starts the rest
  timer, advances to the next set/exercise, and shows the row "✓ 2 · 20 kg × 18 @1". Tapping a
  logged row opens **Correct set** (same steppers, "Save correction" → `supersedeSet`, the old row
  shows struck-through with "edited").
- **In-session hints** (chips above the Log button, never auto-applied): PROG-04 down — "First set
  RIR 0 vs target 2: use −5 % (19 kg) for the remaining sets?" [Apply]; at exercise completion —
  "All sets hit the top of the range: next time +2 kg `PROG-01`" (informational); dumbbell max —
  "Rack max 20 kg — prescribed 24: rep range shifted to 15–20 `LOAD-01`" (came from the planner).
- **Add set** / **Remove last planned set** buttons in the exercise header overflow; the planner's
  count is kept as the target and the deviation is recorded.

#### 6.4.3 Plate calculator (sheet)

Input: target total, `barKg` (20 default; 15 selectable), plate inventory derived from the gym
(`smallestPlateKg` 1.25 → {25, 20, 15, 10, 5, 2.5, 1.25}; `hasMicroplates` adds {0.5, 0.25}).
Algorithm: `perSide = (target − bar) / 2`; greedy descending; if the remainder is not zero the
sheet shows the nearest achievable loads below and above with their deltas and two buttons "Use
82.5" / "Use 85"; the diagram draws the bar and per-side plates as proportional rectangles with kg
labels (`PlateDiagram`, Canvas). For dumbbells the sheet becomes a rounder ("22 → 22 kg is in your
rack; max 30"), for stacks "pin 8 = 40 kg (+2.5 add-on = 42.5)". Nothing is applied until a
button is tapped; the draft load then equals the chosen achievable load.

#### 6.4.4 Rest timer, foreground service and notifications

Design (all rules verified 2026-09-05 on developer.android.com):

- A **foreground service** `WorkoutSessionService` runs from Start until Finish/Abandon. Manifest:
  `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` permissions;
  `<service android:name=".session.WorkoutSessionService" android:exported="false" android:foregroundServiceType="specialUse">`
  with `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="Workout session in progress: rest-interval countdown between sets and one-tap set logging while the screen is off"/>`.
  Since Android 14 a type is mandatory and an undeclared type throws (verified). Why not
  `health`: it requires a *granted* runtime permission (`ACTIVITY_RECOGNITION`, `BODY_SENSORS` ≤ 35,
  or a Health Connect read permission) or declaring `HIGH_SAMPLING_RATE_SENSORS`; we use no
  sensors and refuse to declare a permission we do not use. Why not `shortService`: capped at
  about 3 minutes; rests reach 4–5 minutes. If the app is ever published on Play, the
  `specialUse` justification is entered in the Play Console; for a sideloaded personal build it
  is simply the manifest property. Runtime call: on API ≥ 34
  `startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`; on API 26–33
  the two-argument `startForeground(id, notification)` (types were optional before 34 and the
  `specialUse` attribute value is unknown to older frameworks — see §11.3).
- **Ongoing notification** (channel `session`, `IMPORTANCE_LOW`, silent): title "Upper A · 23 min",
  text "DB Bench Press · set 2 of 2 · next 20 kg × 15–20 @1"; actions **Log set** (logs the
  prefilled draft exactly as shown on the phone, then advances — the notification text is
  rebuilt so the user sees what will be logged), **+30 s**, **Skip rest**. While resting it sets
  `setUsesChronometer(true)`, `setChronometerCountDown(true)`, `setWhen(restEndsAtMillis)` so the
  system renders the countdown even if the process is frozen (both methods exist on
  `NotificationCompat.Builder`; `setChronometerCountDown` is API 24+, we are minSdk 26). Tapping
  opens the session. `setOnlyAlertOnce(true)`, `setOngoing(true)`.
- **Rest-over alert** (channel `rest_timer`, `IMPORTANCE_HIGH`, sound + vibration per Settings):
  one-shot notification "Rest over · DB Bench Press set 2 · 20 kg × 15–20 @1", auto-cancelled by
  the next Log. In the foreground the app additionally shows a full-width banner and a double
  haptic; sound obeys the channel, which the user controls in system settings.
- The timer itself is a coroutine in the service scope (`delay` to the end instant, re-derived
  from wall-clock on every tick; ticks every second only while the app is visible). The
  foreground service keeps the process alive between sets; the system-rendered chronometer covers
  the notification even when the process is frozen.
- **Exact-alarm backup** (Settings → Notifications → "Rest-timer backup alarm", off by default).
  When on, `ExactAlarmHelper` checks `alarmManager.canScheduleExactAlarms()`; if false it opens
  `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` with an explanation (the permission is not
  pre-granted to fresh installs targeting 33+, verified); when granted, each rest schedules
  `setExactAndAllowWhileIdle(RTC_WAKEUP, endMillis, pi)` to a receiver that posts the rest-over
  notification unless it was already posted. `USE_EXACT_ALARM` is not used (reserved for
  alarm/calendar apps by Play policy, verified) and `setWindow` is useless here (windows under 10
  minutes are clipped to 10 minutes since Android 12, verified).
- Default rest per slot from §4.2 step 7; the "+30 s" and "Skip" taps are not recorded as
  adjustments (rest is guidance, not sacred content).

#### 6.4.5 Swap sheet (3 taps)

Header: current exercise; **Reason** chips: Busy · Not here · Pain · Prefer other. List: ranked
substitutions from `ExerciseDef.substitutions` filtered by today's availability (`AvailabilityResolver`
with `SessionOverrides`), then other available exercises of the same pattern; each row shows name,
one-line rationale (from `content.substitutionRationale` or the generated "same pattern; no
spinal loading" text), the load the planner would prescribe (progress state, e1RM-derived, or
"find load"), and flags relevant to active limitations. **Scope** switch: "This session" (default)
/ "Rest of program". "Pain" as reason additionally offers "log a limitation" (INJ-00) and hides
flag-sensitive candidates. Confirm applies, records `PlanAdjustment(source = USER, ruleId = "TRV-04"
or "INJ-0x")`, and the row header shows "⇄ was Barbell Row". Empty: no candidate available → "No
alternative for this pattern with today's equipment — skip exercise?" [Skip] [Keep].

#### 6.4.6 Exercise info sheet (ⓘ)

Tabs: **How** (description, setup bullets, 3–6 cues), **Watch out** (mistakes, safety notes that
reflect the flags: e.g. "no safeties or spotter today → stop at RIR 2"), **Numbers** (rep profile
for the current goal, increment, muscles with weights in both views, last 5 sessions best set,
e1RM if applicable), **Sources** (ExRx page, RP guide, NSCA chapter as authored; `content_status`
badge "draft" until reviewed). A "Substitutes" row links to the swap sheet. If `content == null`
the How tab shows "Description not written yet — muscles, equipment and numbers are still exact"
(catalogue data is complete even before the authoring batches land).

#### 6.4.7 Warm-up block

Collapsed by default when `WU-05` trims it, expanded otherwise. Rows: Raise (suggestion +
minutes, "Done"), drills (name × sets × reps, "Done" per drill, tap → info sheet), ramp sets
(load × reps with "Log" — logged as `WARMUP`, they show in the exercise's set list greyed).
"Skip warm-up" requires a reason chip (Already warm · Short on time · Other) and is recorded as a
`PlanAdjustment` (WU-01). The block never blocks the working sets: "Log set" is enabled at all
times.

#### 6.4.8 Finish flow

**Finish** appears in the top bar once ≥ 1 working set is logged (and always in the overflow).
Sheet: unlogged planned sets are listed as "not done" (kept as-is — nothing is fabricated);
**Session RPE** chips 0–10 (Foster, RDY-06); optional note; **Post-session mobility** (MOB-02 block
if any joint has a chronic dose; "Do it" / "Later"); **Finish session** → `endedAtMillis`, status
COMPLETED, progression update per exercise (`DoubleProgression.update`), FGS stopped →
`SessionSummaryRoute`: totals (sets, volume kg, minutes), per-exercise outcome lines ("DB Bench:
hit → next time +2 kg `PROG-01`", "Row: held `PROG-01`"), e1RM changes, the readiness/modifier
recap, "Ask the coach about this session" quick prompt.

#### 6.4.9 Session states and recovery

- `RECOVERED`: the app was killed or the phone rebooted mid-session; on next launch the router
  finds an ACTIVE session and opens it with a banner "Session restored — 4 sets logged, last at
  14:32"; the draft (unlogged stepper values) is restored from DataStore; the FGS is restarted.
- Sessions older than 6 h and still ACTIVE are offered "Finish as-is" or "Abandon" on launch
  (never silently closed).
- `ERROR`: catalogue missing an exercise id referenced by an old plan → row shows "Exercise
  unavailable in this version" with Swap; never crashes the session.
- Airplane mode: no effect; the screen never references the network.

#### S7 Exercise detail (full screen) — same content as the info sheet plus the e1RM chart and a
"Add to today's session" action (inserts a slot with 2 sets, default profile). Empty: exercise
with no logs → the Numbers tab shows the profile and "No sets logged yet".

#### S8 Exercise library

Search field (names + aliases DE/EN), filter chips: pattern, muscle, equipment "available in
[gym]", difficulty, flags (no spinal loading, knee-friendly …). Rows: name, pattern, primary
muscles, availability dot for the active gym. Empty: "No exercise matches — clear filters".

#### S9 Plan

Program overview: name, goal, split, "week 3 of 6 · deload in week 6", 7-column week strip with the
training days and set counts; per-day rows → `DayTemplateRoute` (slots with sets × reps @ RIR, rest,
load, alternates; slot overflow: swap (same sheet as §6.4.5, scope "Rest of program"), sets ±,
rep range zone, remove); weekly volume targets per muscle (band and current allocation) with
`RuleTag VOL-04`; "Rebuild plan" (confirm; keeps progression state) and "Change goal/schedule"
(→ onboarding pages 3–4 as a sheet, then rebuild); **Adjustment log** entry (`AdjustmentLogRoute`:
reverse-chronological list of `PlanAdjustment`, each with rule tag, source, before → after, Undo
while valid). Empty: no program → same `EmptyState` as Today.

#### S10 Progress (tab root)

Overview cards → detail screens:
- **Strength**: for each of the user's main lifts, 14-day best e1RM with the delta vs 4 weeks ago
  and a sparkline; → `E1rmTrendRoute`: line chart of best-set e1RM per session (E1RM-01..03,
  points coloured by confidence, machines show load × reps), markers for deloads and layoffs,
  PLAT flags.
- **Volume**: `VolumeRoute`: per muscle, this ISO week's `direct` and `fractional` sets as two
  bars against the program band (fractional) and against RP MEV–MRV (direct, R4 §4.5; not for
  muscles without RP guides), last 8 weeks as small multiples; explanation line "fractional =
  prime mover 1.0, synergist 0.5, minor 0.25 `DEF-05`".
- **Body**: `BodyWeightRoute`: daily weights as dots, 7-day average as the line, goal-rate band
  (NUT-05/06), creatine marker, travel-day shading, 14-day delta text and the next NUT-16 review
  date.
- **Readiness & sleep**: 4-week strip of bands, average sleep hours, resting HR line if entered.
- **Cardio**: minutes per week by zone against the CAR-01 floor and the goal prescription.
- **Consistency**: sessions done/planned per week (Q8).
Empty: each card shows "Log N sessions / weigh in 7 days to see a trend" with the count remaining.

#### S11 Coach (tab root = thread list; `ChatThreadRoute` = conversation)

Conversation layout: messages as plain bubbles (assistant text rendered as Markdown-lite: bold,
lists, inline code for exercise ids turned into tappable names); **proposal cards** inline after
the assistant text (title by kind, rationale, before → after lines, validation warnings/blockers,
`RuleTag`s, **Decline** / **Accept**; after Accept: "Applied · Undo"; blockers disable Accept and
say why, e.g. "Trap-bar not in Hotel Ulm"); **context chip** at the top ("Context: profile, Hotel
Ulm, Upper/Lower wk 3, last 14 days" → tap shows the exact JSON sent); **quick prompts** row
above the composer, contextual: "Shorten today to 30 min", "My shoulder hurts on pressing",
"Hotel gym Tue–Thu", "Explain today's plan", "Hotel breakfast: what do I eat?", "Why this
deload?"; composer with send; streaming text appears token by token with a stop button; footer
"This thread: 12 messages · $0.31 est. · Opus 5 low" (§7.8).
States: `Idle`, `Sending`, `Streaming(partial)`, `AwaitingToolResult` (lookup executing),
`ProposalPending`, `Error(kind, message, retryable)`, `Offline(draftQueued)`,
`NoBackend` (→ `EmptyState("Set up the coach", "Add an Anthropic API key in Settings — a few cents per message", "Open settings")`),
`Refused` ("Claude declined this request (category: …) — retry with Sonnet 5?"),
`Truncated` ("Answer cut at the token cap — Continue"), `SpendCapReached` (soft cap, "Continue anyway").
Thread list: title, last message time, cost; "New thread" (starts with the context chip and the
quick prompts); long-press → delete (confirm).

#### S12 Readiness sheet — §5.6. Reached from Today, the session-start sheet, or the reminder notification. Error: none possible offline; duplicate check-in for the same date → "Update today's check-in" (supersedes; the score history keeps the latest).

#### S13 Weekly check-in — one question per page with big chips or a stepper, auto-answered items pre-filled and editable, "Done" shows the triggered actions as cards ("Sleep < 7 h → sets −10 % next week `SLP-03` [OK] [Not this week]"). Empty: nothing due → "Next check-in Sunday 19:00".

#### S14 Red-flag flow — §5.6; reachable from the session overflow and from Settings → Health.

#### S15 Nutrition — targets (with `RuleTag`s), weight entry and trend, the NUT-16 review card with its trigger values, travel heuristics card, optional daily kcal entry, activity factor picker, body-fat % optional. Empty: no weights → "Weigh in on 7 mornings to start the trend".

#### S16 Knowledge — searchable list of the R3 rule table (ID, statement, confidence badge, citation link), grouped by section; Supplements sub-screen with tiers and the SUP-12 caveat pinned; "Why this number?" deep links from every `RuleTag`. Offline; citations open the browser when online.

#### S17 Settings

- **Profile** (all onboarding fields, editable; wake/bed times; activity factor; advanced
  exercises opt-in; clearance date for RED-03).
- **Units** (metric/imperial).
- **Notifications**: readiness time, weekly day/time, cardio reminders, rest-timer sound/vibration,
  rest-timer backup alarm (§6.4.4), keep screen on; a status row "System notification permission:
  granted/denied — Open system settings".
- **Coach backend** (copy from R1 §2.5, rendered verbatim as three blocks):
  - ○ **Anthropic API key (recommended).** "GymCoach calls Anthropic directly with a key from your
    Claude Console account. Billed per token at API rates (typically a few cents per message — see
    Usage). This is the only method Anthropic documents for third-party apps. Your key is stored in
    this device's hardware-backed keystore and never leaves the device except to api.anthropic.com."
    Key field (masked, paste button), **Test** (one `max_tokens: 1` request; shows the HTTP result),
    Remove key.
  - ○ **Home bridge (experimental).** "GymCoach talks to a small server on your own PC, which runs
    Anthropic's unmodified Claude Code signed in with *your* Claude subscription. Nothing is billed
    per token; usage counts against your plan's limits. Anthropic currently states that `claude -p`
    and third-party-app usage "still draw from your subscription's usage limits", but its policy
    restricts subscription logins to "ordinary use of Claude Code and other native Anthropic
    applications" and it may enforce that "without prior notice" (it did so in April 2026). Use
    this only for yourself, keep the PC reachable only via your VPN, and expect it to stop working
    someday. Requires the PC to be on and online." Bridge URL, bridge secret (masked), Test.
    Disabled (greyed, "coming in a later version") until `BridgeBackend` ships.
  - ✗ "GymCoach will never ask for, store, or use a claude.ai login or Claude Code OAuth token on
    this phone; Anthropic prohibits apps from handling those credentials." (No field exists.)
  - Model (Opus 5 / Sonnet 5) and effort (low / medium) with the R1 §2.4 estimate line: "≈ $20–45
    per month on Opus 5, ≈ $8–17 on Sonnet 5 at 30 messages/day; changing effort starts a new
    thread (cache)"; monthly soft cap; **Usage** (this month's estimated spend from token counts).
- **Data**: Export backup (JSON via `ACTION_CREATE_DOCUMENT`), Import backup (`ACTION_OPEN_DOCUMENT`,
  preview counts, then replace/merge), "Backups never contain the API key"; catalogue version.
- **Advanced**: the §4.8 tunables with reset-to-default per field and the confidence tag shown.
- **About**: versions, licences, the R3 reference list.

### 6.5 Notifications and permissions

| Channel id | Importance | Used for | Sound |
|---|---|---|---|
| `session` | LOW | ongoing workout notification with countdown and actions | none |
| `rest_timer` | HIGH | rest-over alert | user setting (default on) |
| `checkins` | DEFAULT | daily readiness reminder, weekly check-in due, reminder questions | default |
| `cardio` | DEFAULT | planned cardio at the chosen slot | default |
| `coach` | DEFAULT | "proposal waiting for your decision" when a stream finished in the background; backend errors | default |

Permissions: `POST_NOTIFICATIONS` (API 33+, requested on onboarding page 6 and again from
Settings; denial leaves the app fully functional — the FGS notification then shows only in the
Task Manager, verified), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `INTERNET`
(Coach only), `SCHEDULE_EXACT_ALARM` only when the backup alarm is switched on (special-access
screen), `VIBRATE`. No location, no sensors, no contacts.

Scheduling: `ReminderScheduler` uses WorkManager `PeriodicWorkRequest` (24 h, flex 30 min,
initial delay to the chosen local time, unique name `readiness_reminder`) and a 7-day periodic for
the weekly check-in; cardio reminders are `OneTimeWorkRequest`s enqueued per planned slot when a
week is materialised (unique name `cardio_<date>`); all workers check the current state before
posting (no reminder if already checked in / logged). WorkManager is inexact by design (minutes
late is normal, Doze can defer further); that is acceptable for reminders and is why the rest
timer does not use it.

### 6.6 Empty and error states — master table

| Screen | Empty | Error |
|---|---|---|
| Today | no program / rest day / no gym (§S2) | plan materialisation failed → card "Could not build today's session (reason) — Rebuild / Ask the coach" |
| Gym switcher | no gyms → "Create your first gym" | — |
| Gym editor | 0 items (bodyweight count shown) | save failed → snackbar retry, draft kept |
| Session | no planned sets (free session) → "Add exercise" button opens the library | exercise id unknown → row with Swap; FGS start failure (should not happen with declared type) → session continues without notification, banner "Background timer unavailable" |
| Plan | no program | rebuild failed → previous program kept, error card |
| Progress | per-card "need N more" | — |
| Coach | no backend / no threads | offline, 401, 402, 403, 404, 413, 429 (rate vs spend cap), 500/529, mid-stream `error`, refusal, max_tokens (§7.7) |
| Weekly check-in | nothing due | — |
| Nutrition | < 7 weights | — |
| Settings → Coach | no key | Test failed: shows `error.type` and `request_id` (never the key) |
| Import | — | schema mismatch → "Backup from a newer version (schema 3 > 2)" |

### 6.7 Accessibility and gym conditions

Large numerals, high-contrast readiness labels with words, `contentDescription` on every icon
button, TalkBack order = visual order, no time-based UI except the rest countdown (which is also
announced at 10 s), all sheets dismissible by swipe and by a close button, haptics optional,
dynamic colour respected, font scaling up to 1.5× tested on the session screen (the steppers wrap
to two rows above 1.3×).

---

## 7. Claude coach integration

### 7.1 Backends and policy (R1, adopted without change)

| Backend | Status | Ships in |
|---|---|---|
| Direct Anthropic Messages API, personal Console API key held on-device | compliant (R1 §1.10, rank 1) | v1, default |
| Home bridge: PC runs the unmodified `claude -p` under the user's own subscription login, reached over Tailscale with a bridge secret | tolerated grey zone, revocable without notice (R1 §1.10 item 3) | v2, off by default, "experimental" |
| Any claude.ai / Max OAuth token on the phone (`/login`, `claude setup-token`, `CLAUDE_CODE_OAUTH_TOKEN`) | prohibited (R1 §1.4/§1.5) | never; no UI field exists |
| Remote Control, `ant auth login`, Agent SDK with a key | not embeddable / no advantage | not built |

The Settings copy is R1 §2.5 verbatim (§6.3 S17). The key lives only in `SecretStore` (Keystore
AES-256/GCM wrapper, ciphertext in DataStore, excluded from backup via `dataExtractionRules`;
decrypt failure = "no key stored", R2 §6.1). It is sent only as the `x-api-key` header to
`https://api.anthropic.com/v1/messages`, never logged, never exported, never shown after entry
(masked, "Remove" only).

### 7.2 Request profile (R1 §2.4, §3.5)

```
model:          settings.model.modelId          ("claude-opus-5" default, "claude-sonnet-5" economy)
max_tokens:     3072                              (thinking counts toward it)
stream:         true
system:         [ {type:"text", text: SystemPrompt.STABLE, cache_control:{type:"ephemeral"}} ]   // byte-identical every turn
tools:          [ propose_plan_change, lookup, set_checkin_question ]  (strict: true)
tool_choice:    {type:"auto"}
thinking:       {type:"adaptive"}                 (display omitted → default)
output_config:  {effort: thread.effort}           ("low" default; constant for the life of a thread)
cache_control:  {type:"ephemeral"}                (top-level → automatic breakpoints over the history)
metadata:       {user_id: sha256(installId)}      (opaque, ≤ 512 chars)
messages:       [ …history…, {role:"user", content:[ {type:"text", text: contextJson}, {type:"text", text: userText} ]} ]
```

Never sent: `temperature`, `top_p`, `top_k`, assistant prefill, `thinking.type: "enabled"`. Changing
model or effort creates a new thread (the old one keeps its setting) so the cache prefix stays
valid within a thread (R1 §3.10 invalidators). Today's date, local time and timezone are in the
user turn's context JSON, never in the system prompt.

### 7.3 System prompt outline (~3,500 tokens, byte-stable, English)

1. **Identity and scope** (≈ 150 tokens): "You are GymCoach's coach inside an offline-first
   Android app. The app owns all data; you advise and *propose*; the user confirms." Who the user
   is (travelling technician, gyms vary; details arrive in the context block).
2. **Hard rules** (≈ 350): POL-01 never restate a logged number differently or ask the app to
   change it; POL-02 no diagnosis — patterns are "a reason to see a professional"; RED-01 list: if
   the user reports any item, respond with the stop-and-refer text and nothing else; SUP-12
   caveat sentence must accompany any supplement mention; when `medical_flags` is non-empty, no
   dosing numbers; no claims beyond the rule digest — say "not in my rules" and offer `lookup`.
3. **How to act** (≈ 400): use `propose_plan_change` for any change (one tool call per change;
   several calls in one turn are fine); never describe a change in prose only; keep answers ≤ 150
   words unless asked; cite rule IDs in backticks; for equipment questions call `lookup
   exercise_options`; for "why" questions call `lookup rule`; assume the app applied nothing
   until a `tool_result` says "applied".
4. **Rule digest** (≈ 1,600): ~45 one-line rules with IDs, chosen for chat relevance: DEF-01/03,
   VOL-01/04/05/06/09, FRQ-01/02, LOAD-01/02/03, RIR-01/02/03/05, REST-01/02/03, ORD-01/03,
   MIX-01/02, UNI-01, ROM-01/02, PROG-01/02/04, PLAT-02, DLD-01/02/03, E1RM-02/04, RDY-02/05,
   WU-01/04, MOB-02, CAR-05/06/07, NUT-01/05/06/13/14/15/16, SUP-01/02/11/12, SLP-03, STP-01,
   LAY-01/03, TRV-01, TIME-01, INJ-00/01/02/03, RED-02/03. Confidence tags included
   ("weak = app default, adjustable").
5. **Catalogue conventions** (≈ 300): exercise and equipment ids are snake_case as in the app;
   patterns list; muscles list; "available" means the app checked the active gym; weights
   1.0/0.5/0.25 meaning; goal ids.
6. **Context block schema** (≈ 400): field-by-field description of the JSON in §7.4 so the model
   reads it reliably.
7. **Style** (≈ 150): direct, no emojis, metric unless `unit_system` says imperial, short
   sentences, numbers with units, German terms in parentheses when helpful (the user is German;
   the UI is English).
8. **Refusal/limits** (≈ 100): if a request needs data not in context, ask for `lookup logs`
   rather than guessing; if unsure whether a change is safe, propose the conservative option and
   say why.

The text is stored as a Kotlin raw string in `:core` `coach/SystemPrompt.kt` with a unit test
asserting its SHA-256 (any edit is a deliberate cache-invalidating change and bumps a
`PROMPT_VERSION` constant stored on the thread).

### 7.4 Context assembly (`CoachContext.build(...)`, ≤ 6,000 characters ≈ 1,500 tokens)

```json
{
  "date": "2026-09-08", "local_time": "21:34", "tz": "Europe/Berlin", "unit_system": "metric",
  "profile": {"sex":"male","age":38,"height_cm":180,"weight_kg_7d":83.6,"goal":"hypertrophy",
              "training_age":"intermediate","days_per_week":4,"session_minutes":60,
              "limitations":[{"region":"shoulder","side":"right","pain":3,"since":"2026-08-20"}],
              "medical_flags":false,"priority_muscles":["side_delts"]},
  "gym": {"id":7,"name":"Hotel Ulm","origin":"hotel_gym","session_minutes":45,
          "equipment":["dumbbells(max 20, step 2)","adjustable_bench","functional_trainer(stack 5)","cable_rope","treadmill","stationary_bike","elliptical","mat"]},
  "program": {"split":"upper_lower","week":3,"weeks":6,"deload_week":6,
              "days":[{"i":0,"label":"Upper A","slots":[{"id":"d0s1","ex":"barbell_bench_press","sets":3,"reps":"5-10","rir":2,"load":80.0}, "..."]}, "..."],
              "volume_targets":{"chest":[12,18],"side_delts":[15,18], "...":"..."}},
  "today": {"planned_day":"Upper A","readiness":{"score":58,"band":"amber"},"modifiers":["RDY-02: +1 RIR, -1 set after first two","TRV-04: 3 substitutions"],
            "session_status":"planned"},
  "recent_sessions":[{"date":"2026-09-06","day":"Lower B","sets":"14/15","rpe":7,"best":{"back_squat":"100x8@2","romanian_deadlift":"90x10@2"}}, "..."],
  "readiness_7d":[58,71,66,80,45,73,69],
  "weight":{"avg7":83.6,"delta14":-0.4,"goal_rate_pct_wk":"0.5-1.0","next_review":"2026-09-14"},
  "triggers":["Q1: sleep 6.4 h avg -> SLP-03 active","PLAT-01: overhead_press_barbell 3 sessions flat"],
  "pending_proposals":[{"id":12,"kind":"swap_exercise","status":"pending"}],
  "open_questions":[{"id":3,"text":"Did the neutral-grip press feel better on the shoulder?","due":"2026-09-10"}]
}
```

Truncation order when over budget: `recent_sessions` (14 → 7 → 3 days), `program.days` (keep
today + tomorrow), `readiness_7d`, `triggers` (keep 5), `gym.equipment` (collapse to category
counts). Byte-stable ordering (keys sorted as above) so identical states produce identical text
(cache-friendly for repeated questions within 5 minutes).

Rolling summary: after 12 turns or when the history exceeds 24,000 characters, the app sends one
non-streamed request (same thread, same effort, no tools) asking for a ≤ 600-character summary of
facts and decisions; `ChatThread.rollingSummary` is stored and subsequent requests send
`[summary]` as the first user text block followed by the last 6 turns verbatim (thinking blocks
echoed unchanged, R1 §3.5). The summary request is itself cached history (top-level
`cache_control`).

### 7.5 Tools (strict JSON schemas, `:core` `coach/ToolSchemas.kt`)

Design rule for strict mode: flat object, every property listed in `required`, unused fields
`null` (nullable types), `additionalProperties: false` (R1 §3.5).

**`propose_plan_change`** — description: "Propose one structured change to the user's plan,
schedule, nutrition targets, limitations or check-in questions. The app validates it against the
active gym and the rule book, shows it as a card, and applies it only after the user confirms. Use
this instead of describing changes in prose."

```json
{ "type":"object", "additionalProperties":false,
  "required":["kind","rationale","rule_ids","exercise_id","replacement_exercise_id","slot_id","day_index","scope",
              "sets","rep_lo","rep_hi","rir_target","load_kg","load_percent_change","cardio_kind","cardio_minutes",
              "cardio_modality","weekday","goal","days_per_week","session_minutes","question_text","due_date",
              "protein_g_per_kg","kcal_target","limitation_region","limitation_side","pain_score","deload_start_date"],
  "properties":{
    "kind":{"type":"string","enum":["swap_exercise","change_sets","change_rep_range","change_load","add_cardio_session",
             "remove_session","change_goal","change_schedule","set_checkin_question","update_nutrition_targets",
             "add_limitation","schedule_deload"]},
    "rationale":{"type":"string","maxLength":400},
    "rule_ids":{"type":"array","items":{"type":"string"},"maxItems":6},
    "exercise_id":{"type":["string","null"]}, "replacement_exercise_id":{"type":["string","null"]},
    "slot_id":{"type":["string","null"]}, "day_index":{"type":["integer","null"]},
    "scope":{"type":["string","null"],"enum":["this_session","rest_of_program",null]},
    "sets":{"type":["integer","null"]}, "rep_lo":{"type":["integer","null"]}, "rep_hi":{"type":["integer","null"]},
    "rir_target":{"type":["integer","null"]}, "load_kg":{"type":["number","null"]}, "load_percent_change":{"type":["number","null"]},
    "cardio_kind":{"type":["string","null"],"enum":["zone2","hiit_4x4","hiit_10x1","sit","recovery_walk",null]},
    "cardio_minutes":{"type":["integer","null"]},
    "cardio_modality":{"type":["string","null"],"enum":["treadmill_walk","treadmill_run","bike","air_bike","rower","elliptical","stair_climber","ski_erg","jump_rope","outdoor_walk","outdoor_run","swim","other",null]},
    "weekday":{"type":["integer","null"]}, "goal":{"type":["string","null"],"enum":["strength","hypertrophy","fat_loss","general_fitness","endurance_support",null]},
    "days_per_week":{"type":["integer","null"]}, "session_minutes":{"type":["integer","null"]},
    "question_text":{"type":["string","null"]}, "due_date":{"type":["string","null"]},
    "protein_g_per_kg":{"type":["number","null"]}, "kcal_target":{"type":["integer","null"]},
    "limitation_region":{"type":["string","null"],"enum":["knee","shoulder","low_back","elbow_wrist","hip","ankle","neck","other",null]},
    "limitation_side":{"type":["string","null"],"enum":["left","right","both",null]}, "pain_score":{"type":["integer","null"]},
    "deload_start_date":{"type":["string","null"]}
  } }
```

**`lookup`** — description: "Read app data. Executed immediately on the phone; the result comes
back as a tool_result in the same conversation."

```json
{ "type":"object","additionalProperties":false,"required":["what","exercise_id","pattern","rule_id","days"],
  "properties":{
    "what":{"type":"string","enum":["exercise_options","exercise_detail","rule","logs","gym_equipment"]},
    "exercise_id":{"type":["string","null"]}, "pattern":{"type":["string","null"]},
    "rule_id":{"type":["string","null"]}, "days":{"type":["integer","null"]} } }
```

Results (compact JSON, ≤ 2,000 chars): `exercise_options` → available exercises of the pattern in
the active gym with substitution rank and prescribed load; `exercise_detail` → muscles, options,
profile, flags, cues; `rule` → statement, confidence, citation; `logs` → per-exercise best sets
for the last N days; `gym_equipment` → the full item list with params.

**`set_checkin_question`** — a convenience alias that the applier maps onto
`propose_plan_change(kind = set_checkin_question)`; schema `{question_text, due_date, rationale}`,
all required. It exists so the model reaches for it naturally after giving advice ("I'll ask you
on Thursday whether the neutral-grip press felt better").

### 7.6 Turn loop (`ChatEngine`) and the confirmation protocol

1. Build request (history + rolling summary + context block + user text). Stream. Accumulate
   blocks per index (`StreamAccumulator`); text deltas render live; `input_json_delta` fragments
   are concatenated and parsed at `content_block_stop`.
2. On `message_stop`:
   - `stop_reason == "end_turn"` → store the assistant message (content array verbatim, incl.
     thinking/redacted_thinking blocks) with usage and cost. Done.
   - `stop_reason == "tool_use"` → for each `tool_use` block:
     - `lookup` → execute on device (`:core` `coach/LookupExecutor` over the repositories), collect
       `tool_result`; if *all* tool uses in this turn were lookups, immediately send a follow-up
       request whose user message is exactly the `tool_result` blocks (first in the content array,
       R1 §3.5) and continue streaming; at most 3 automatic follow-ups per user turn.
     - `propose_plan_change` / `set_checkin_question` → parse into `Proposal`, run
       `ProposalValidator` (§7.7), persist with status PENDING, render the card. The turn ends
       here (the assistant already produced its text). The pending `tool_use` ids are stored on
       the thread as `awaitingToolResults`.
   - `stop_reason == "max_tokens"` → mark truncated; "Continue" sends the assistant content
     unchanged followed by a user text "continue" (R1 §3.8).
   - `stop_reason == "refusal"` → discard partial text, show the refusal state (R1 §3.8).
3. Next user turn: if `awaitingToolResults` is non-empty, the user message *starts* with one
   `tool_result` per pending id: `"applied: <summary of what changed>"` for ACCEPTED/APPLIED,
   `"rejected by user: <reason chip or 'no reason'>"` for DECLINED, `"pending: user has not decided
   yet; do not re-propose"` for still-PENDING; then the context block and the user's text (all in
   one user message; results first). Decisions made after that point are reported as plain text in
   the following user turn ("[proposal 12 accepted]"). This keeps Claude's view of the plan
   consistent (R1 §3.5 pattern 1) while never blocking the user from typing.
4. Proposals not decided within 7 days become EXPIRED (card greys out; tool_result "expired").

### 7.7 Proposal validation and application (`:core` `coach/ProposalValidator`, `ProposalApplier`)

| Kind | Blockers (Accept disabled) | Warnings (shown, Accept allowed) | Applier effect and `PlanAdjustment` |
|---|---|---|---|
| swap_exercise | replacement unknown; not available in the active gym (for `this_session`) or in the home gym (for `rest_of_program`); flag-sensitive for an active limitation with pain ≥ 3 | pattern differs; difficulty above the allowed class; `requiresSpotterOrSafeties` without safeties (RIR cap) | slot exercise replaced; progress state for the new exercise looked up; `substitutedFrom` set; rule `TRV-04`/`INJ-0x` |
| change_sets | sets < 1 or > 8; would exceed VOL-06 per-session cap or SESS-02 | pushes muscle above `band.hi` (VOL-04) or below VOL-01 | slot sets updated; program `weeklyVolumeTargets` re-derived |
| change_rep_range | rep_lo ≥ rep_hi; outside 1–30 | outside the goal's LOAD-03 table; > 12 reps on a barbell compound (Halperin note) | slot range; progress state rep targets; loads re-derived via E1RM-03 next session |
| change_load | load_kg ≤ 0; > 1.25 × current e1RM | > +10 % (PROG-02) | `ExerciseProgress.workingLoadKg`; rounding to the LoadContext shown |
| add_cardio_session | > 3 cardio sessions/week for hypertrophy/strength without user override (CAR-06); HIIT without base/clearance (CAR-05, RED-03) | running the day before a heavy leg day (CAR-06) | `CardioTemplate` added to the weekday; reminders scheduled |
| remove_session | would leave 0 sessions | breaks FRQ-01 (< 2×/muscle) | day removed for the rest of the mesocycle; volume redistributed |
| change_goal / change_schedule | days_per_week outside 1–6; minutes outside 20–120 | mid-mesocycle rebuild | `Profile` updated → `ProgramBuilder` rebuild (progress state kept) |
| set_checkin_question | empty text; due date in the past | — | `ReminderQuestion` row; appears in the next weekly check-in and as a Today card on the due date |
| update_nutrition_targets | protein outside 1.0–3.5 g/kg; kcal outside 0.6–1.5 × TDEE | step > 200 kcal (NUT-16) | nutrition targets; NUT-16 review date reset |
| add_limitation | unknown region | — | `Profile.limitations` +1; planner re-materialises today with INJ substitutions |
| schedule_deload | date before today | deload < 3 weeks after the previous one | `Program.deloadWeekIndex` or an ad-hoc deload week inserted; DLD-02 modifiers |

Application is atomic per proposal (one repository transaction), records `PlanAdjustment(source =
COACH, proposalId)` with before/after JSON, and re-materialises today's session if affected.

### 7.8 Streaming, errors and retries (`:core` `chat` + `:app` `DirectAnthropicBackend`)

- OkHttp client: connect 15 s, read 120 s (ping cadence undocumented, R1 §4.4), call timeout
  10 min, no body-logging interceptor; headers `x-api-key`, `anthropic-version: 2023-06-01`,
  `content-type: application/json`, `accept: text/event-stream`.
- `SseLineParser` follows R1 §3.6 exactly (blank line terminates; `event:`/`data:` accumulation;
  dispatch on the JSON `type`; ignore `ping` and unknown types; `error` aborts). Unit-tested
  against the R1 verbatim event fixtures.
- Retry (R1 §3.9): connection errors, 408, 409, 429 with `retry-after`, 500, 504, 529 → 2 retries
  with exponential backoff (1 s, 4 s) — only if no content has been rendered yet; never 400, 401,
  402, 403, 404, 413, or a 429 whose `error.details.error_code == "enforced_spend_limit_reached"`.
- UI mapping: 401 → "Key rejected or expired — update it in Settings" (link); 402 → "Console
  credits exhausted"; 403 → "Key has no access to this model"; 404 → "Model id not found (update
  the app?)"; 413 → context too large (the app halves `recent_sessions` and retries once); 429
  rate → "Busy, retrying…"; 429 spend cap → "Your Console spend limit is reached"; 500/504/529 →
  "Anthropic is overloaded — retry"; mid-stream `error` → partial text kept, "Retry"; refusal →
  discard + "Retry with Sonnet 5?"; `model_context_window_exceeded` → summarise + retry.
- Every response's `request-id` is stored on the message for support.
- Offline: the composer allows one queued draft; it is sent automatically when connectivity
  returns (a `ConnectivityManager` callback), and the Coach tab shows the offline banner.

### 7.9 Cost meter (`:core` `chat/CostMeter`)

From each final `message_delta` usage (cumulative): cost = `cache_read × 0.5/M + cache_creation ×
6.25/M + input × 5/M + output × 25/M` for Opus 5 and `0.2 / 2.5 / 2 / 10` for Sonnet 5 (R1 §2.3;
thinking tokens are inside `output_tokens`). Stored per message; month total shown in Settings →
Usage and in the thread footer; the soft cap (`monthlySpendCapUsd`) shows a warning at 80 % and a
confirm dialog at 100 % ("Continue anyway"). The number is labelled "estimated from token counts
and list prices" because Console billing is authoritative.

### 7.10 Home bridge adapter (second release; interface fixed now)

```kotlin
interface ChatBackend {
    val kind: BackendKind
    /** Emits Messages-API stream events (the same JSON model for both backends, R1 §3.12). */
    fun stream(request: ApiRequest): Flow<StreamEvent>
    suspend fun test(): BackendTestResult
}
```

`BridgeBackend` will POST the same `ApiRequest` to `https://<tailscale-host>:<port>/turn` with
`Authorization: Bearer <bridge secret>`; the bridge runs `claude -p --output-format stream-json
--verbose --include-partial-messages --system-prompt … --permission-mode dontAsk --json-schema …
[--resume <session_id>]` and streams `{"type":"stream_event","event":{…}}` lines whose `event` is
the raw Messages event; the adapter maps the final `result` line to `message_delta`/`message_stop`
and synthesises a `tool_use` event from `structured_output` (R1 §3.12). Failure modes to surface
(R1 §2.2): PC offline, Tailscale down, login expired, Max limit hit, policy enforcement. Flags are
re-checked against the Claude Code docs when the bridge is built (R1 §4.7).

---

## 8. Persistence (`:app` `data.*`)

### 8.1 Room schema (version 1; `androidx.room:room-runtime` 2.8.4, KSP; schema JSON exported to `app/schemas/`)

Design choices: hot, query-heavy data (sets, sessions, metrics, check-ins, progress) is
normalised; hierarchical, read-as-a-whole data (program templates, planned sessions, proposals,
chat content) is stored as kotlinx-serialization JSON strings in a single column. All dates are
ISO `TEXT`, instants `INTEGER` epoch millis. No foreign keys with cascade delete (deletion is
rare and explicit); indices as listed.

| Table | Columns (type) | Keys / indices |
|---|---|---|
| `profile` | `id INTEGER PK (always 1)`, `sex TEXT`, `birth_year INTEGER`, `height_cm REAL`, `goal TEXT`, `days_per_week INTEGER`, `session_minutes INTEGER`, `months_consistent INTEGER`, `months_since_layoff INTEGER NULL`, `limitations_json TEXT`, `medical_flags_json TEXT`, `priority_muscles_json TEXT`, `disliked_json TEXT`, `unit_system TEXT`, `wake_min INTEGER`, `bed_min INTEGER`, `activity_factor REAL`, `advanced_opt_in INTEGER`, `training_weekdays_json TEXT`, `clearance_date TEXT NULL`, `updated_at INTEGER` | PK |
| `gym_set` | `id PK AUTO`, `name`, `color_index`, `preset_origin NULL`, `travel_kit INTEGER`, `session_minutes INTEGER NULL`, `notes`, `created_at`, `last_used_at NULL`, `archived INTEGER` | index(last_used_at) |
| `gym_item` | `gym_set_id`, `equipment_id`, `params_json` | PK(gym_set_id, equipment_id) |
| `program` | `id PK AUTO`, `name`, `goal`, `split`, `days_per_week`, `session_minutes`, `start_date`, `weeks`, `deload_week INTEGER NULL`, `home_gym_id`, `days_json TEXT`, `volume_targets_json TEXT`, `created_by`, `status`, `created_at` | index(status) |
| `planned_session` | `id PK AUTO`, `program_id`, `day_index`, `week_index`, `date`, `gym_set_id`, `plan_json TEXT`, `status`, `created_at` | unique(date) (re-materialisation replaces), index(program_id) |
| `workout_session` | `id PK AUTO`, `planned_session_id NULL`, `date`, `gym_set_id`, `started_at`, `ended_at NULL`, `readiness_score NULL`, `session_rpe NULL`, `notes`, `status` | index(status), index(date) |
| `set_log` | `id PK AUTO`, `session_id`, `exercise_id`, `slot_id NULL`, `set_index`, `kind`, `target_load_kg NULL`, `target_rep_lo NULL`, `target_rep_hi NULL`, `target_rir NULL`, `load_kg NULL`, `reps NULL`, `rir NULL`, `seconds NULL`, `metres NULL`, `side`, `completed_at`, `supersedes_id NULL`, `superseded INTEGER`, `note` | index(session_id), index(exercise_id, completed_at), index(superseded) |
| `cardio_log` | `id PK AUTO`, `session_id NULL`, `date`, `modality`, `kind`, `minutes`, `avg_hr NULL`, `rpe NULL`, `note` | index(date) |
| `body_metric` | `id PK AUTO`, `date`, `weight_kg NULL`, `waist_cm NULL`, `resting_hr NULL`, `note` | unique(date) |
| `readiness_checkin` | `id PK AUTO`, `date`, `quick INTEGER`, `sleep_h REAL NULL`, `sleep_q NULL`, `soreness NULL`, `stress NULL`, `energy NULL`, `motivation NULL`, `resting_hr NULL`, `score`, `band`, `assumed_json`, `created_at` | index(date, created_at) — several rows per date allowed; the latest wins, older kept |
| `weekly_checkin` | `id PK AUTO`, `week_start`, `answers_json`, `auto_json`, `triggers_json`, `completed_at NULL` | unique(week_start) |
| `steps_log` | `date PK`, `steps`, `source` | PK |
| `exercise_progress` | `key TEXT PK`, `exercise_id`, `gym_set_id NULL`, `working_load_kg NULL`, `rep_lo`, `rep_hi`, `rep_hi_base`, `rep_hi_extended INTEGER`, `rir_target`, `hits`, `misses`, `last_session_date NULL`, `last_increment_date NULL`, `best_e1rm_14d NULL`, `best_e1rm_date NULL`, `last_outcome NULL` | index(exercise_id) |
| `plan_adjustment` | `id PK AUTO`, `at`, `rule_id`, `source`, `summary`, `trigger`, `before_json`, `after_json`, `proposal_id NULL`, `undone_at NULL` | index(at) |
| `chat_thread` | `id PK AUTO`, `title`, `created_at`, `rolling_summary`, `summarized_upto NULL`, `model`, `effort`, `backend`, `prompt_version`, `awaiting_tool_results_json` | index(created_at) |
| `chat_message` | `id PK AUTO`, `thread_id`, `role`, `content_json`, `created_at`, `usage_json NULL`, `stop_reason NULL`, `request_id NULL`, `cost_usd NULL`, `error NULL` | index(thread_id, created_at) |
| `proposal` | `id PK AUTO`, `thread_id`, `message_id`, `tool_use_id`, `kind`, `payload_json`, `rationale`, `rule_ids_json`, `status`, `warnings_json`, `blockers_json`, `created_at`, `decided_at NULL`, `adjustment_id NULL` | index(status), index(thread_id) |
| `reminder_question` | `id PK AUTO`, `text`, `due_date`, `asked_at NULL`, `answer NULL`, `source`, `proposal_id NULL` | index(due_date) |
| `catalog_meta` | `key TEXT PK`, `value TEXT` | PK — `equipment_schema_version`, `exercises_schema_version`, `content_hash` |

Nineteen tables. `Converters` handle only enums (stored as their `name`) — everything else is a
primitive or a JSON string. All DAOs are `suspend`/`Flow`; writes that touch several tables
(`applyProposal`, `finishSession`) are `@Transaction` methods on the database.

Migration discipline: schema export is on (`room.schemaLocation`, R2 §5.5, upgraded to the
`CommandLineArgumentProvider` form per R2 G8 once the project compiles); every version bump ships a
`Migration` object and a `MigrationTestHelper` test (instrumented, `room-testing` 2.8.4); never
`fallbackToDestructiveMigration` — user logs are sacred.

### 8.2 DataStore Preferences (`settings.preferences_pb`, plus `secrets.preferences_pb`)

- `settings`: the `Settings` data class serialised as one JSON string under key `settings_json`
  (simpler than 20 typed keys; versioned with `settings_version`), `onboarding_draft_json`,
  `session_draft_json` (the unlogged stepper state, written on every change), `active_gym_id`,
  `last_catalog_version`.
- `secrets` (separate file, excluded from backup): `api_key_iv`, `api_key_ct`, `bridge_secret_iv`,
  `bridge_secret_ct` as Base64.

### 8.3 Keystore wrapper (`data.prefs.KeystoreCrypto`, ≈ 60 lines; R2 §6.1 pattern)

```kotlin
class KeystoreCrypto(private val alias: String = "gymcoach_secrets_v1") {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }
    fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> {          // iv, ciphertext
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv to c.doFinal(plain)
    }
    /** Returns null on any failure: AEADBadTagException, KeyPermanentlyInvalidatedException, missing alias → "no key stored". */
    fun decrypt(iv: ByteArray, ct: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)); c.doFinal(ct)
    } catch (e: GeneralSecurityException) { null }
}
```

No `setUserAuthenticationRequired`, no StrongBox (R2 §6.1). `SecretStore` wraps it with
`suspend fun apiKey(): String?` / `setApiKey(String?)` and zeroes the plaintext array after use.

### 8.4 Catalogue assets and versioning

`app/src/main/assets/equipment.json` and `exercises.json` (R4 §6 schemas, `schema_version: 1`).
`AssetCatalogLoader` parses on first use (Dispatchers.IO, ≈ 300 KB), caches in memory for the
process life, and writes `schema_version`s and a content hash to `catalog_meta`. Content updates
ship with app updates; user data references exercises by id, so removed ids degrade to "Exercise
unavailable" rows (§6.4.9) and a `:core` test forbids id removal without a `renamed_ids` map in the
asset (`{"old_id": "new_id"}` applied at load time to plans and progress keys).

### 8.5 Backup and transfer

- Android Auto Backup on (`android:allowBackup="true"`, `dataExtractionRules` exclude
  `secrets.preferences_pb`); the Room DB and settings are included. Keystore keys never transfer,
  so a restored install shows "no API key" (R2 §6.1).
- Manual export: `BackupJson` v1 = `{version, exported_at, profile, gyms, programs, planned_sessions,
  sessions, sets, cardio, metrics, readiness, weekly, steps, progress, adjustments, threads,
  messages, proposals, reminders, settings_without_secrets}` written through
  `Intent.ACTION_CREATE_DOCUMENT` (`application/json`); import through `ACTION_OPEN_DOCUMENT`
  with a preview ("312 sessions, 4 gyms, 2 programs") and Replace / Merge (merge = insert rows
  whose ids do not exist; sets are matched by `(session started_at, exercise_id, set_index)`).
  Exports never contain the API key or the bridge secret.

### 8.6 Sacred-content rules enforced in the data layer

1. `set_log` rows are never updated in place; `supersedeSet` inserts a replacement and flips
   `superseded` on the old row in one transaction.
2. `plan_adjustment` is append-only; undo appends `undone_at`, never deletes.
3. `readiness_checkin` and `body_metric` corrections keep history (readiness: new row; metric:
   the `note` field records "corrected from 84.1").
4. Derived numbers (e1RM, TDEE, 7-day average, targets) are never stored as if logged; they are
   recomputed from logs and labelled with their rule ID in the UI.
5. Repositories reject writes with NaN/negative loads or reps > 100 with an exception surfaced as
   an inline error, never by clamping silently.

---

## 9. Testing

### 9.1 `:core` unit tests (JUnit 4.13.2 + kotlin-test-junit 2.4.10 + kotlinx-coroutines-test 1.11.0; run with `gradlew :core:test`)

| Test class | What it asserts |
|---|---|
| `CatalogValidatorTest` | the eight R4 §6.3 checks on the shipped assets (copied into `core/src/test/resources/`): unique ids, resolvable substitutions/profiles/equipment refs, group members resolve and do not nest, every exercise available in `base_gym` or tagged `optional_equipment`, `hotel_gym` ≥ 40 and `travel_kit` ≥ 25 available, weights ∈ {1, 0.5, 0.25} with one or two 1.0s, flag consistency, JSON round-trip equality, batch coverage = 156 ids |
| `AvailabilityResolverTest` | table-driven: bench press with/without rack, `spotter` flag, `squat_rack` not satisfying `rack_or_spotter`, `power_rack.has_pullup_bar=false`, `all_of: []`, load-type override per option, hotel preset counts, missing/added overrides |
| `SplitChooserTest` | days 1–6 × training age → expected split and FRQ-03 validity; SPLIT-02 spacing with adverse weekday choices |
| `VolumeTargetsTest` | bands by goal × age (VOL-04/05), FAT_LOSS 60–100 % of accustomed (VOL-09), priority ×1.25 capped, VOL-08 ramp and stop |
| `ExerciseSelectorTest` | limitation filtering (knee pain 3 removes `kneeSensitive`; pain 2 keeps with note), difficulty gating, dislike exclusion, strength main-lift forcing, variety penalty |
| `SetAllocatorTest` | per-session caps (VOL-06/SESS-02), min 2 sets per appearance (VOL-07), time budget per SESS-01 for 30/45/60/90 min, TIME-01 trim order applied step by step |
| `RepRirAssignerTest` | profile lookup by goal, RIR-03 classes, RIR-04 week modifiers, safeties cap, PROG-05 novice weeks, rest seconds |
| `LoadPrescriberTest` | progress state → load; e1RM → load via E1RM-03 with rounding to step; seed guess path; dumbbell max handling (option a vs b by goal) |
| `PlannerGoldenTest` | six frozen `PlannerInput` fixtures (novice/intermediate/advanced × commercial/home/hotel) → `PlannedSession` JSON compared with `golden/*.json`; regeneration only with `-Dgolden.update=true` |
| `WorkedExampleTest` | §4.9 reproduced exactly (13 sets, the listed substitutions and modifiers) |
| `DoubleProgressionTest` | scenario table: hit ×1/×2 by class, miss, autoreg up, > 10 % jump → reps extended, intermediate 7-day cadence, RIR-05 +1 on > 12 reps, bodyweight/band/time paths |
| `DeloadAndPlateauTest` | DLD-01 cadence by age, DLD-02 arithmetic, DLD-03 trigger counting over 7 days, PLAT-01 three flat sessions with readiness ≥ Amber, PLAT-03 −5 % ×2 with/without external cause, LAY-01 rows by days |
| `E1rmTest` | 100 kg × 5 @ RIR 2 → r_eff 7 → mean(Epley 123.33, Brzycki 120.00) = 121.67; reps > 10 → null; pct table interpolation; 14-day rolling best |
| `VolumeAccountingTest` | direct vs fractional for bench/squat/row/pulldown per R4 §4.2 examples; back aggregate counted once; warm-ups 0 |
| `ReadinessScorerTest` | full form examples (all top → 100), quick-mode renormalisation, RHR adjustment, band thresholds, RDY-02 modifier arithmetic (Red: sets × 0.6 rounded up) |
| `EnergyEstimatorTest` | Mifflin male 84 kg/180 cm/38 y = 1,780 kcal; × 1.375 = 2,447.5; adaptive clamp ± 150; NUT-16 band decisions; 7-day MA and noise labelling |
| `PlateCalculatorTest` | 100 kg with 20 kg bar → per side 20+20; 82.5 → 20+10+1.25; unreachable 81 → below 80 / above 82.5; microplates; dumbbell rounder at rack max; stack half-step |
| `WarmupBuilderTest` | ramp percentages per WU-02 with rounding to plates/dumbbells and empty-bar floor; WU-05 trim; WU-06 extension; drill selection by pattern |
| `CardioPlannerTest` | CAR-07 by goal, placement rules (no running before leg day, ≥ 6 h separation), HIIT gating, HR zones (HRmax 208 − 0.7·38 = 181.4 → zone 2 109–127) |
| `WeeklyQuestionBankTest` | selection ≤ 8, rotation guarantees, auto-answers, trigger mapping |
| `SseLineParserTest` | R1 §3.6 verbatim fixtures: text stream, tool-use with `input_json_delta` fragments incl. empty first delta, thinking with signature-only, ping, mid-stream error, unknown event ignored, multi-line `data:` |
| `StreamAccumulatorTest` | final content array equals the R1 §3.7 shape; cumulative usage from `message_delta`; stop_reason/stop_details for refusal |
| `RetryPolicyTest` | status table (R1 §3.9) incl. spend-cap 429 no-retry and `retry-after` honouring |
| `CostMeterTest` | R1 §2.4 per-turn arithmetic: 3,500 cached + 2,000 input + 400 output on Opus 5 = $0.02175 |
| `ProposalValidatorTest` | blockers/warnings per kind (§7.7 table); applier produces the expected `PlanAdjustment` before/after and is undoable |
| `CoachContextTest` | budget ≤ 6,000 chars with the truncation order; byte-stable ordering; medical flags reduced to boolean |
| `SystemPromptTest` | SHA-256 matches `PROMPT_VERSION`; contains every rule id listed in §7.3 |
| `BackupJsonTest` | export → import round-trip equality; unknown newer version rejected |
| `UnitsTest` | kg↔lb round-trips at 0.25 kg resolution; display formatting |

### 9.2 `:app` JVM unit tests (no Android runtime, R2 G14)

`TodayViewModelTest`, `SessionViewModelTest` (log → supersede → finish → progression call),
`GymEditorViewModelTest` (live availability count), `CoachViewModelTest` with a `FakeChatBackend`
that replays SSE fixture files (proposal card appears, Accept sends `applied:` first in the next
user message, Decline sends `rejected by user:`, lookup auto-follow-up capped at 3), using
`FakeRepositories` implementing the §3.9 interfaces in memory and `FixedClock`.

### 9.3 Instrumented tests (after the first Android Studio build)

`MigrationTest` (MigrationTestHelper, every version), `SetLogDaoTest` (supersede transaction),
`KeystoreCryptoTest` (round trip, tamper → null), `SessionScreenTest` (Compose `ui-test-junit4`:
prefilled draft, stepper steps, Log writes the displayed values, swap sheet ranking).

### 9.4 Golden plans as canaries

The six golden `PlannedSession`s of `PlannerGoldenTest` are the planner's canary set (the same
discipline as the user's other projects): any diff must be explained in the commit message, and
the golden files are regenerated only with the explicit flag.

### 9.5 Manual gym-floor test script (run on a phone before each release)

1. Fresh install → onboarding with the hotel preset → "Start" available: stopwatch ≤ 3:00.
2. Today → gym chip → new gym from "Commercial gym" → untick 3 machines, set dumbbells max 40 →
   Save: ≤ 60 s; availability count changed live.
3. Start session with "spotter" off and bench in the plan → RIR cap note visible.
4. Log 3 sets with one thumb: targets prefilled; one set via stepper; one via keypad; correct one
   set → strike-through visible, progression uses the correction.
5. Lock the phone during a 3-min rest → notification shows the countdown → rest-over alert
   arrives within 2 s of zero; "Log set" from the notification logs exactly the shown numbers.
6. Kill the app from Recents mid-session → reopen → session restored with the draft values.
7. Swap a busy exercise → 3 taps; reason logged; summary shows the swap.
8. Finish → summary → progression lines with rule tags; Plan → adjustment log has every change.
9. Airplane mode → everything above still works; Coach shows the offline banner and queues one
   draft; back online → the draft is sent.
10. Coach: "Hotel gym Tue–Thu" → proposals appear → Accept one → today re-materialised → Undo.
11. Settings → Coach: enter an invalid key → Test shows 401 and no key text in the message.

### 9.6 CI

`gradlew :core:test` on every push (JDK 17 only); `gradlew :app:assembleDebug` and `:app:test`
once the SDK is installed (R2 §7); lint with `-x lint` fallback if the JDK-17 lint crash (R2 U4)
reappears.

---

## 10. Work breakdown by file ownership (parallel implementation)

### 10.1 Contracts frozen first (day 1–2, one owner, reviewed by all)

| Contract | File(s) | Owner |
|---|---|---|
| C1 Domain model + repository interfaces | `core/src/main/kotlin/com/gymcoach/core/model/*.kt` (§3), `model/repo/Repositories.kt` (§3.9), `time/Clock.kt`, `rules/RuleId.kt`, `rules/Tunables.kt` | Stream A |
| C2 Planner output shapes | `PlannedSession`, `Program`, `AppliedModifier` (already in C1) + `core/.../planner/PlannerInput.kt` (§4.1) | Stream B |
| C3 Coach contracts | `core/.../coach/ToolSchemas.kt` (§7.5), `coach/Proposal*.kt` (§3.7, §7.7 signatures), `chat/WireModel.kt` (`ApiRequest`, `ContentBlock`, `StreamEvent`, `Usage`, `ApiError`), `app/.../chat/ChatBackend.kt` (§7.10 interface) | Stream D |
| C4 UI contracts | `app/.../ui/nav/Routes.kt` (§6.1), every `*UiState.kt` (§6.3/§6.4 state classes), `ui/components/*` signatures | Stream G |

### 10.2 Streams and owned files

| Stream | Scope | Owned paths (exclusive) | Depends on |
|---|---|---|---|
| **A — core model & catalogue** | §3 model, JSON loaders, `CatalogValidator`, `AvailabilityResolver`, `units`, `plates`, `export`, test resources (assets copy), `RuleText` | `core/.../model/**`, `catalog/**`, `availability/**`, `units/**`, `plates/**`, `export/**`, `rules/RuleText.kt`, `core/src/test/.../{Catalog,Availability,Plate,Units,Backup}*Test.kt`, `core/src/test/resources/catalog/**` | R4 assets |
| **B — planner** | §4.2–4.4, §5.1–5.3 builders: `SplitChooser`, `VolumeTargets`, `PatternCoverage`, `ExerciseSelector`, `SetAllocator`, `RepRirAssigner`, `LoadPrescriber`, `TimeTrimmer`, `SessionMaterializer`, `ProgramBuilder`, `WarmupBuilder`, `CardioPlanner`, golden fixtures | `core/.../planner/**`, `warmup/**`, `cardio/**`, `core/src/test/.../{Split,Volume,ExerciseSelector,SetAllocator,RepRir,LoadPrescriber,PlannerGolden,WorkedExample,Warmup,CardioPlanner}*Test.kt`, `core/src/test/resources/golden/**` | A, C |
| **C — progression, stats, readiness, nutrition, check-ins** | §4.5–4.8, §5.4–5.6 logic: `DoubleProgression`, `DeloadPolicy`, `PlateauDetector`, `LayoffPolicy`, `RirBias`, `E1rm`, `VolumeAccounting`, `MovingAverage`, `ChartData`, `ReadinessScorer/Modifier`, `EnergyEstimator`, `MacroTargets`, `RateController`, `WeeklyQuestionBank`, `RedFlags`, `supplements` data | `core/.../progression/**`, `stats/**`, `readiness/**`, `nutrition/**`, `checkin/**`, `supplements/**`, matching tests | A |
| **D — coach core** | §7.3–7.9 in `:core`: `SystemPrompt`, `CoachContext`, `ToolSchemas`, `ProposalValidator/Applier`, `LookupExecutor`, `SummaryPolicy`, wire model, `SseLineParser`, `StreamAccumulator`, `RetryPolicy`, `CostMeter`, fixtures | `core/.../coach/**`, `chat/**`, `core/src/test/.../{Sse,StreamAccumulator,Retry,CostMeter,ProposalValidator,CoachContext,SystemPrompt}*Test.kt`, `core/src/test/resources/sse/**` | A, B (for validation of availability) |
| **E — app data layer** | §8: Room entities/DAOs/DB/migrations, repositories, DataStore, `KeystoreCrypto`, `SecretStore`, `AssetCatalogLoader`, backup import/export, `AppGraph` | `app/.../data/**`, `app/.../di/**`, `app/schemas/**`, `app/src/androidTest/.../data/**` | A |
| **F — session runtime & notifications** | §6.4.4, §6.5: `WorkoutSessionService`, `RestTimer`, `SessionNotification`, `SessionController`, `Channels`, `NotificationCenter`, `ReminderScheduler`, `ExactAlarmHelper`, workers, manifest | `app/.../session/**`, `notify/**`, `work/**`, `app/src/main/AndroidManifest.xml` | E, C4 |
| **G — UI 1 (floor)** | theme, navigation, components, Onboarding, Today, Gym switcher/editor, Session (all sheets), Session summary, Exercise detail/library | `app/.../ui/theme/**`, `ui/nav/**`, `ui/components/**`, `ui/onboarding/**`, `ui/today/**`, `ui/gyms/**`, `ui/session/**`, `ui/library/**`, `app/src/test/.../ui/{Today,Session,GymEditor}*Test.kt` | E, F, B |
| **H — UI 2 (desk)** | Plan, Progress (+ `LineChart`, `MuscleBandBars`), Coach chat UI, Check-ins, Nutrition, Knowledge, Settings, `DirectAnthropicBackend`, `ChatEngine`, `BridgeBackend` stub | `app/.../ui/plan/**`, `ui/progress/**`, `ui/coach/**`, `ui/checkin/**`, `ui/nutrition/**`, `ui/knowledge/**`, `ui/settings/**`, `app/.../chat/**`, `app/src/test/.../ui/Coach*Test.kt` | D, E, G (components) |
| **I — content** | the six R4 §5.12 authoring batches (`content` fields, sources, `content_status`), `RuleText` citations proofreading, Settings/Knowledge copy | `app/src/main/assets/exercises.json` (content fields only, per batch), `assets/equipment.json` (descriptions), `docs/content/**` | R4 |

Shared files with a single owner and pull-request review from the others: `gradle/libs.versions.toml`,
`build.gradle.kts` files (Stream E), `strings.xml` (Stream G; H appends only).

### 10.3 Sequencing

- Week 1: C1–C4 contracts merged; A delivers loaders + validator + availability with the real
  assets; E delivers Room + repositories + fakes; D delivers the wire model + SSE parser (fixture
  tests green); G delivers theme, nav, components, onboarding shell against fakes.
- Week 2: B delivers `ProgramBuilder`/`SessionMaterializer` with golden tests; C delivers
  progression/stats/readiness; F delivers the service + notifications; G delivers Today and
  Session end-to-end on fakes, then on Room.
- Week 3: H delivers Plan/Progress/Coach/Settings; D delivers proposals + context; I lands
  content batches; integration on device; manual script §9.5.
- `:core` compiles and tests from week 1 (JDK 17 only); `:app` first compiled by the user in
  Android Studio at the end of week 2 (R2 §7.3), with the two-string fallback to AGP 9.3.2 +
  Gradle 9.5.1 ready (R2 §3).

---

## 11. Risks, open questions, unverified items

### 11.1 Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| The toolchain triple AGP 9.4.0 + KGP 2.4.10 + Gradle 9.6.1 misbehaves in Android Studio (R2 U1) | low–medium | blocks `:app` build | R2 fallback (AGP 9.3.2 + Gradle 9.5.1) is two strings; `:core` unaffected |
| `:app` written blind does not compile first time | high | hours of fixes | compile-conservative rules (§2.4), file-level OptIn, only long-stable APIs, small files per screen, fakes so `:app` unit tests run on the JVM |
| `specialUse` foreground service on API 29–33 (attribute unknown to older frameworks) | medium | no ongoing notification on old devices | two-argument `startForeground` below 34; the session works without the service (banner) |
| Doze/App Standby delays the rest-end alert during long rests with the screen off | low (Doze needs long stationary + screen off) | late alert | system-rendered countdown in the notification; optional exact-alarm backup (§6.4.4) |
| WorkManager reminders late by minutes | certain | minor | wording "around 21:00"; never used for the rest timer |
| Coach cost creeps (thinking tokens, long histories) | medium | $10–30/month extra | effort low, rolling summary, cached history, soft cap with warning, Sonnet 5 switch (R1 §2.4) |
| Anthropic changes the home-bridge tolerance (R1 §1.8) | medium | bridge stops | bridge is v2, off by default, labelled revocable; direct API is always available |
| Model id `claude-opus-5` retired or renamed | low | 404 | model id is a setting; error mapping tells the user to update |
| Catalogue content still "draft" at launch | high initially | weaker cues | catalogue data (muscles, equipment, numbers) is complete; the info sheet says "description not written yet"; `content_status` badge |
| Planner is too rigid for real gyms (busy machines, odd equipment) | medium | user distrust | swap is 3 taps, today-only overrides, "Train as planned" switch, coach can re-plan; every change is visible |
| RIR self-reports unreliable | certain | progression noise | RIR-05 correction, calibration sets, PROG-04 asymmetry (up needs all sets, down needs one) |
| e1RM used where invalid (machines, > 10 reps) | medium | misleading trends | E1RM-02/04 enforced: null above 10 reps, machines show load × reps |
| Notification permission denied | medium | no reminders | app fully functional; status row in Settings |
| Room migration mistakes | low | data loss | schema export, migration tests, never destructive |
| Keystore key invalidated (device transfer, security reset) | low | key re-entry | decrypt failure = "no key stored"; nothing else lost |
| Unit conversion rounding surprises | low | wrong kg | 0.25 kg resolution, both units shown after logging, tests |
| Halperin RIR-bias direction applied wrongly (+1) | low | slightly stricter progression | tunable; documented in `RirBias` with the citation |
| Seed loads far off for a given user | medium | first-session annoyance | labelled "starting guess", find-load protocol, one session to correct |

### 11.2 Open questions for Johannes

1. Real home-gym inventory (R4 §7.4) — needed to replace the generic `home_gym` preset and to set
   plate/dumbbell parameters.
2. Usual training weekdays at home and on the road; default session length per gym (60 home / 45
   hotel assumed).
3. Does he want optional daily kcal entry at all, or only the weight-trend loop (§5.4)?
4. Imperial ever needed (kept as a toggle, minimal test surface)? German UI: which release?
5. Default model: Opus 5 low effort (assumed) or Sonnet 5 economy?
6. Bridge priority: v2 as proposed, or drop entirely?
7. Opt-in to advanced exercises (front squat, good morning, Nordic, push press)?
8. Body-fat estimate available (drives the deficit protein band, NUT-01)? Wake/bed times?
9. Does he own a HR strap/watch (unlocks HR-based zones; otherwise talk test + RPE)?
10. Should skipped sessions auto-shift the week (assumed yes when the week has room) or stay skipped?
11. Should the weekly check-in default to Sunday 19:00, and the readiness reminder to 21:00 the
    evening before (assumed) or the morning of the session?

### 11.3 Not verified / inferred (explicit)

1. Material 3 1.4.0 experimental status was checked on androidx-main's API file (which reflects
   1.5.0-alpha); the 1.4.0 release notes page did not render in the fetch tool. The file-level
   OptIn rule makes this harmless; `TimePicker`, `DatePicker`, `SegmentedButton`, `SearchBar`,
   `PullToRefreshBox`, `NavigationBar`, `ListItem`, `FilterChip`, `Slider`, `Switch`, `Card`,
   `OutlinedTextField`, `LinearProgressIndicator`, `SnackbarHost` were "not found" in the truncated
   file, i.e. unknown status — the first five are avoided, the rest are used with the OptIn.
2. Behaviour of the `specialUse` manifest attribute on API 29–33 (constant introduced in API 34):
   inferred that older frameworks ignore the unknown value; the design avoids the three-argument
   `startForeground` below 34 for that reason.
3. `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` is API 34 and the three-argument
   `startForeground(int, Notification, int)` is API 29 — from memory, consistent with the verified
   page but not re-checked in the reference.
4. Doze interaction with a foreground-service coroutine timer during a 3–5-minute screen-off rest:
   not measurable without a device; hence the optional exact-alarm backup.
5. `JsonNamingStrategy.SnakeCase` in kotlinx-serialization (believed added in 1.5.0; we use 1.11.0)
   — if absent, `@SerialName` per field is the fallback.
6. SSE `ping` cadence and therefore the 120 s read timeout (R1 §4.4).
7. WorkManager flex/periodic timing precision on Android 17 — assumed "minutes late", not measured.
8. The seed-load fraction table (§4.2 step 8), the readiness quick-mode mapping (energy =
   motivation), the 6,000-character context budget and the 12-turn summary trigger are this
   proposal's conventions, not evidence-derived; all are tunables.
9. The strict-tool-use rule "every property must be listed in `required`" is applied as the safe
   superset of R1's statement (`additionalProperties: false` and `required` mandatory); if the API
   accepts optional properties in strict mode the schema still validates.
10. Token estimates (≈ 4 characters/token) used for the context budget; actual counts come from
    `usage` after the first call and the budget is adjusted in a later version if needed.
11. All items in R1 §4, R2 §12, R3 §12 and R4 §7 remain unverified as stated there; this proposal
    inherits them (notably VOL-06 per-session numbers, Fragala 2019 loads, melatonin legal status,
    RP back landmarks read from an image, cmdline-tools SHA-256).

---

## Appendix A — `AndroidManifest.xml` skeleton (the parts this design depends on)

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET"/>
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
    <uses-permission android:name="android.permission.VIBRATE"/>
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM"/>   <!-- only used when the backup alarm is on -->
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>  <!-- re-arm reminders after reboot -->

    <application
        android:name=".GymCoachApp"
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:label="@string/app_name"
        android:theme="@style/Theme.GymCoach">

        <activity android:name=".MainActivity" android:exported="true"
                  android:screenOrientation="portrait" android:launchMode="singleTask"
                  android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>

        <service android:name=".session.WorkoutSessionService"
                 android:exported="false"
                 android:foregroundServiceType="specialUse">
            <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                      android:value="Workout session in progress: rest-interval countdown between sets and one-tap set logging while the screen is off"/>
        </service>

        <receiver android:name=".notify.RestAlarmReceiver" android:exported="false"/>
        <receiver android:name=".notify.BootReceiver" android:exported="false">
            <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED"/></intent-filter>
        </receiver>
    </application>
</manifest>
```

`res/xml/data_extraction_rules.xml` excludes `secrets.preferences_pb` from both cloud backup and
device transfer.

## Appendix B — Sacred-content checklist for every screen (POL-01)

1. A number the user typed or tapped is stored exactly as displayed (unit-converted once, at
   0.25 kg resolution, and echoed back in both units).
2. Any number the app changed shows a `RuleTag` and a one-line trigger before it is used.
3. Corrections supersede; nothing is overwritten.
4. Coach output never changes data without an Accept.
5. Derived values (e1RM, TDEE, averages, targets) are labelled "estimated" with their formula ID.
6. Every automatic adjustment is in the Adjustment log with before/after and Undo.

