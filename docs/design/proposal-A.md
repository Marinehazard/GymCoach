# GymCoach — Design Proposal A: "Planner-first"

Status: design proposal, 2026-09-05. Written against `docs/REQUIREMENTS.md` and the four research
deliverables `docs/research/R1-claude-backend.md` (policy, backend, wire contract),
`R2-android-toolchain.md` (pinned toolchain, Gradle skeleton), `R3-training-science.md` (rule book,
~150 rule IDs) and `R4-exercise-taxonomy.md` (equipment/exercise taxonomy, JSON schemas). Every
numeric rule referenced below carries its R3 ID (e.g. `VOL-04`); every exercise or equipment ID is an
R4 ID. Nothing in this document introduces a new library, API shape or citation beyond what R1–R4
verified; the few planner conventions that the research did not cover are marked **[convention,
weak]** and live as user-adjustable constants in `rules.json` (Appendix A).

---

## 0. Thesis and decisions in one screen

**The plan is a pure function.** `Plan = plan(profile, gyms, library, history, overrides, today)`.
Nothing ever edits a plan in place. The UI appends *logs* (what the user did) and *overrides* (what
the user or the coach chat decided); the planner re-derives the mesocycle, the week and today's
session from those inputs, deterministically, and explains every automatic decision with a rule ID.
This is what makes the three hard requirements cheap:

- **Equipment-of-the-day** is just another input: the same planned session is *realized* against a
  different gym set, with substitutions scored and the weekly volume ledger re-accounted.
- **Chat that acts** is just another input: a confirmed proposal becomes an override row; the
  planner recomputes; the coach sees the resulting diff in its next tool result.
- **Sacred content** is structural: logs are never written by anything but the logging screen, and
  every derived or adjusted value carries an `Adjustment(ruleId, inputs, before, after)`.

| Area | Decision |
|---|---|
| Modules | `:core` (pure Kotlin/JVM: model, knowledge, planner, progression, readiness, cardio, nutrition, coach contracts, SSE parser, tests) and `:app` (Compose UI, Room/DataStore, Keystore, OkHttp backends, WorkManager). Toolchain exactly as R2 §5 (AGP 9.4.0, Kotlin 2.4.10, KSP 2.3.11, Compose BOM 2026.08.00, Room 2.8.4, OkHttp 5.5.0). |
| Domain model | `@Serializable` data classes and enums in `:core`; JSON assets (`exercises.json`, `equipment.json`, `rules.json`, `templates.json`, `drills.json`) parsed with kotlinx-serialization; `java.time.LocalDate` with a 10-line ISO serializer; no reflection, no Android imports. |
| Planner | Six-stage pipeline: normalize → mesocycle skeleton → split/templates → weekly targets → exercise selection + set allocation → daily realization. Greedy, deterministic, budget-driven (minutes and per-session set caps), volume accounted per muscle with Pelland-style fractional credit (R4 §4.2, `DEF-05`). |
| Progression | Per-exercise state machine (UNKNOWN → CALIBRATING → PROGRESSING ⇄ PLATEAU / REGRESSING → DELOADING → REENTRY; LAYOFF as an entry modifier), double progression (`PROG-01/02`), autoregulation (`PROG-04`), cadence by training age (`PROG-03`), plateau ladder (`PLAT-01..03`), proactive + reactive deloads (`DLD-01..04`). State is keyed per exercise, and additionally per gym for machine/cable/smith/sled loads (`E1RM-04`, `TRV-04`). |
| Substitution | Ranked-list first (R4 `substitutions[]`), then library search scored by pattern family, muscle-vector cosine similarity, load fit against gym parameters, continuity, difficulty and rep-profile compatibility; every swap re-runs the volume ledger and carries deficits to the remaining sessions of the week (never across weeks, never above `VOL-06`). |
| Travel | One-off hotel day = same planned session realized against the hotel set. ≥ 3 travel days (CHK Q10) = week mode `TRAVEL_MAINTENANCE` (`TRV-01/02`), mesocycle clock paused, no layoff regression because patterns stay trained. |
| Chat contract | Coach = Claude via Messages API tool use; read-only tools execute immediately on-device; proposal tools produce cards the user confirms; confirmed → override → recompute → `tool_result` with the plan diff. One backend-agnostic event model (Messages API stream JSON, R1 §3.12). |
| Backend | Default: direct Anthropic API, personal Console API key in an Android-Keystore-wrapped DataStore blob (R1 §1.10, R2 §6.1). Optional, off by default: home bridge running the unmodified `claude -p` under the user's own subscription over Tailscale (R1 §2.2 option 2, grey zone, revocable). **No claude.ai/Max OAuth token ever touches the phone; there is no UI field for one.** |
| Request profile | `claude-opus-5`, `thinking {type: adaptive}` (display omitted), `output_config.effort = "low"` held constant per thread, `tool_choice auto`, strict tools, system + tools cached, top-level `cache_control` for history, `max_tokens 2048`, never `temperature/top_p/top_k`, never prefill (R1 §2.4, §3.5). |
| Persistence | Room 2.8.4 (logs, sessions, gyms, overrides, progression states, chat), DataStore Preferences (settings + encrypted key blob), JSON assets in `:app/src/main/assets`, plan snapshots stored as JSON columns; JSON export/import for backup. |
| Testing | `:core` JUnit 4: rule tests by ID, asset validation (R4 §6.3), golden-scenario plan invariants, progression state-machine sequences, ledger carryover, time-budget bounds, SSE parser fixtures from R1 §3.6 verbatim events, proposal validator. `:app`: JVM unit tests for JSON mapping, Room schema export, Keystore wrapper contract; UI tests deferred until the project compiles in Android Studio. |

---

## 1. Module and package architecture

### 1.1 Dependency rules

```
:app  ──depends on──►  :core
:core ──depends on──►  kotlin-stdlib, kotlinx-coroutines-core, kotlinx-serialization-json   (nothing else)
```

- `:core` never imports `android.*` or `androidx.*` (R2 §5.4). It also never touches the wall clock,
  random numbers, the file system or the network directly; those come in through three interfaces
  (`Clock`, `AssetSource`, and — for the coach — a `CoachTransport` implemented in `:app`).
- `:app` owns everything that needs Android: persistence, crypto, HTTP, UI, background work.
- The planner is *called* by `:app` with fully materialized inputs; it returns fully materialized
  outputs. No callbacks, no lazy loading, no DAO access from `:core`. This keeps the planner
  unit-testable on a plain JVM with JSON fixtures.

### 1.2 `:core` packages (`core/src/main/kotlin/com/gymcoach/core/…`)

| Package | Responsibility | Key types |
|---|---|---|
| `model` | Domain types only (no logic beyond trivial accessors). | `Profile`, `GymSet`, `Exercise`, `Equipment`, `Mesocycle`, `SessionPlan`, `SetLog`, `ProgressionState`, `Override`, `PlanMutation`, `Adjustment`, enums |
| `model.serial` | Serializers for `LocalDate`, `Band`. | `LocalDateIsoSerializer`, `BandSerializer` |
| `knowledge` | Loading and indexing of the JSON assets; the rule book with IDs, values, confidence, citations. | `ExerciseLibrary`, `EquipmentCatalog`, `RuleBook`, `DrillLibrary`, `SplitTemplates`, `Citations` |
| `equipment` | Requirement algebra (R4 §2.2), availability resolution, gym parameters, load rounding to achievable increments. | `AvailabilityResolver`, `GymParams`, `LoadRounder` |
| `volume` | Fractional/direct volume accounting, muscle tiers, weekly targets, ledger with carryover. | `VolumeAccountant`, `MuscleTiers`, `WeeklyLedger`, `TargetPolicy` |
| `planner` | Stages 0–5 of the pipeline: normalization, mesocycle skeleton, split selection, exercise selection, set allocation. | `Planner` (facade), `MesocycleBuilder`, `SplitSelector`, `ExerciseSelector`, `SetAllocator`, `TimeModel` |
| `planner.realize` | Stage 6: today's session from a planned session + today's context. | `SessionRealizer`, `ReadinessModifiers`, `LayoffPolicy`, `TimeConcessions`, `WarmupGenerator`, `CardioFinisher`, `CooldownGenerator` |
| `substitution` | Ranked substitution with scoring and volume re-accounting. | `SubstitutionEngine`, `SubstitutionScore`, `LoadTransfer` |
| `progression` | State machine, e1RM, plateau detection, deload scheduling. | `ProgressionEngine`, `E1rm`, `PlateauDetector`, `DeloadScheduler` |
| `readiness` | Readiness score, bands, red flags, check-in bank and triggers. | `ReadinessScorer`, `RedFlags`, `CheckinBank`, `CheckinTriggers` |
| `cardio` | Cardio prescriptions, HR zones, modality choice, interference scheduling. | `CardioPlanner`, `HeartRateZones` |
| `nutrition` | Energy estimate, adaptive TDEE, macro targets, weight-trend smoothing, supplement advisor. | `EnergyModel`, `MacroTargets`, `WeightTrend`, `SupplementAdvisor` |
| `mutation` | Validation and application of `PlanMutation`s into overrides; plan diffing for tool results. | `MutationValidator`, `OverrideStore` (pure), `PlanDiff` |
| `coach` | Coach context assembly, tool definitions (JSON Schema as constants), proposal parsing, Messages-API stream event model, message accumulator, SSE line parser, bridge line adapter. | `CoachContextBuilder`, `CoachTools`, `ProposalParser`, `StreamEvent`, `MessageAccumulator`, `SseParser`, `BridgeAdapter` |
| `explain` | `Adjustment` construction helpers and rule references. | `Adjustments`, `RuleRef` |
| `units` | kg↔lb, cm↔in for display; rounding helpers. | `Units` |
| `time` | `Clock` interface, week arithmetic. | `Clock`, `Weeks` |

### 1.3 `:app` packages (`app/src/main/kotlin/com/gymcoach/app/…`)

| Package | Responsibility |
|---|---|
| `GymCoachApp`, `di/AppContainer` | Application class; manual dependency container (no Hilt/Dagger — avoids an extra annotation-processing plugin on a build we cannot verify today). |
| `data/db` | Room database, entities, DAOs, converters, exported schemas under `app/schemas/`. |
| `data/prefs` | DataStore Preferences: settings, encrypted API key blob, onboarding state. |
| `data/crypto` | `KeystoreCrypto` — Android Keystore AES-256/GCM wrapper (R2 §6.1). |
| `data/assets` | `AssetSourceImpl` reading `assets/*.json` into `:core` loaders. |
| `data/repo` | Repositories mapping Room rows ↔ `:core` models; `PlanRepository` orchestrates recompute and snapshots. |
| `chat` | `CoachBackend` interface, `AnthropicDirectBackend` (OkHttp + `:core` `SseParser`), `HomeBridgeBackend`, `RetryPolicy`, `UsageMeter`, `ChatController` (turn loop, confirmation flow). |
| `ui/nav` | Type-safe routes (`@Serializable` route classes, Navigation 2.10.0). |
| `ui/theme`, `ui/components` | Material 3 theme; shared components (`RuleChip`, `AdjustmentRow`, `ProposalCard`, `LineChart` on Canvas per R2 §6.2). |
| `ui/onboarding`, `ui/today`, `ui/session`, `ui/plan`, `ui/library`, `ui/gyms`, `ui/progress`, `ui/checkin`, `ui/nutrition`, `ui/coach`, `ui/settings` | One package per screen family, each with a `ViewModel` and composables. |
| `work` | WorkManager workers: weekly rollover, check-in reminder, readiness reminder. |

### 1.4 Data flow

```
assets/*.json ──► knowledge.* (indexes) ─┐
Room: logs, gyms, overrides, states ─────┼──► PlanInputs ──► Planner.plan() ──► Mesocycle + WeekPlan
DataStore: settings, profile ────────────┘                        │
                                                                   ▼
TodayContext (gym, extras, minutes, readiness) ──► SessionRealizer.realize() ──► SessionPlan (+Adjustments)
                                                                   │
Session screen logs SetLog rows ──► ProgressionEngine.onSessionClosed() ──► ProgressionState rows
Coach chat ──► PlanMutation ──(user confirms)──► Override row ──► PlanRepository.recompute()
```

Recompute triggers: profile saved, gym edited, override added/revoked, session closed, week
rollover (Monday 00:00 local or first app open of the week), travel answer, deload trigger. Recompute
is cheap (< 50 ms on a phone for a 6-week mesocycle in the planner's own micro-benchmark target) and
is the *only* way a plan changes.

---

## 2. Domain data model (Kotlin)

All types below are in `:core`, package `com.gymcoach.core.model` unless stated. They are written to
compile against Kotlin 2.4.10 + kotlinx-serialization 1.11.0 with explicit imports and no
experimental features. JSON field names follow the R4 schemas (snake_case via `@SerialName`).

### 2.1 Serializers

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/serial/LocalDateIsoSerializer.kt
package com.gymcoach.core.model.serial

import java.time.LocalDate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object LocalDateIsoSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.LocalDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDate) {
        encoder.encodeString(value.toString()) // ISO-8601 yyyy-MM-dd
    }

    override fun deserialize(decoder: Decoder): LocalDate {
        return LocalDate.parse(decoder.decodeString())
    }
}
```

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/serial/BandSerializer.kt
package com.gymcoach.core.model.serial

import com.gymcoach.core.model.Band
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Serializes Band(lo, hi) as the JSON array [lo, hi] used by the R4 schemas ("intRange"). */
object BandSerializer : KSerializer<Band> {
    private val delegate: KSerializer<List<Int>> = ListSerializer(Int.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Band) {
        delegate.serialize(encoder, listOf(value.lo, value.hi))
    }

    override fun deserialize(decoder: Decoder): Band {
        val list = delegate.deserialize(decoder)
        require(list.size == 2) { "Band must have exactly two integers, got $list" }
        return Band(list[0], list[1])
    }
}
```

### 2.2 Enums

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/Enums.kt
package com.gymcoach.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Sex { @SerialName("male") MALE, @SerialName("female") FEMALE }

/** DEF-06 goals. FAT_LOSS trains with the hypertrophy rep profile under VOL-09; ENDURANCE_SUPPORT uses the endurance profile. */
@Serializable
enum class Goal {
    @SerialName("hypertrophy") HYPERTROPHY,
    @SerialName("strength") STRENGTH,
    @SerialName("general_fitness") GENERAL_FITNESS,
    @SerialName("fat_loss") FAT_LOSS,
    @SerialName("endurance_support") ENDURANCE_SUPPORT
}

/** Key into RepProfile.byGoal (R4 §5.2). */
@Serializable
enum class ProfileGoal {
    @SerialName("strength") STRENGTH,
    @SerialName("hypertrophy") HYPERTROPHY,
    @SerialName("endurance") ENDURANCE,
    @SerialName("general_fitness") GENERAL_FITNESS
}

/** DEF-01. */
@Serializable
enum class TrainingAge { @SerialName("novice") NOVICE, @SerialName("intermediate") INTERMEDIATE, @SerialName("advanced") ADVANCED }

@Serializable
enum class UnitSystem { @SerialName("metric") METRIC, @SerialName("imperial") IMPERIAL }

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

@Serializable
enum class LoadType {
    @SerialName("barbell") BARBELL, @SerialName("dumbbell") DUMBBELL, @SerialName("machine") MACHINE,
    @SerialName("cable") CABLE, @SerialName("bodyweight") BODYWEIGHT, @SerialName("band") BAND,
    @SerialName("kettlebell") KETTLEBELL, @SerialName("smith") SMITH, @SerialName("sled") SLED,
    @SerialName("medicine_ball") MEDICINE_BALL, @SerialName("cardio") CARDIO, @SerialName("none") NONE
}

@Serializable
enum class Difficulty { @SerialName("beginner") BEGINNER, @SerialName("intermediate") INTERMEDIATE, @SerialName("advanced") ADVANCED }

@Serializable
enum class EquipmentCategory {
    @SerialName("free_weights") FREE_WEIGHTS, @SerialName("racks_benches") RACKS_BENCHES, @SerialName("cables") CABLES,
    @SerialName("machines_upper") MACHINES_UPPER, @SerialName("machines_lower") MACHINES_LOWER,
    @SerialName("bodyweight_stations") BODYWEIGHT_STATIONS, @SerialName("cardio") CARDIO, @SerialName("accessories") ACCESSORIES
}

@Serializable
enum class GroupKind { @SerialName("any_of") ANY_OF, @SerialName("all_of") ALL_OF }

@Serializable
enum class IncrementKind {
    @SerialName("kg") KG, @SerialName("band_level") BAND_LEVEL, @SerialName("reps") REPS, @SerialName("time") TIME,
    @SerialName("distance") DISTANCE, @SerialName("box_height") BOX_HEIGHT, @SerialName("none") NONE
}

@Serializable
enum class IncrementDirection { @SerialName("load") LOAD, @SerialName("assist") ASSIST }

@Serializable
enum class ProgressionKind {
    @SerialName("linear_load") LINEAR_LOAD, @SerialName("double_progression") DOUBLE_PROGRESSION,
    @SerialName("rep_progression") REP_PROGRESSION, @SerialName("time_progression") TIME_PROGRESSION,
    @SerialName("band_progression") BAND_PROGRESSION, @SerialName("distance_or_time") DISTANCE_OR_TIME,
    @SerialName("none") NONE
}

/** R3 §0.1 confidence tags; carried on every rule constant and every citation. */
@Serializable
enum class Confidence { @SerialName("strong") STRONG, @SerialName("moderate") MODERATE, @SerialName("weak") WEAK, @SerialName("contested") CONTESTED }

@Serializable
enum class LimitationRegion {
    @SerialName("knee") KNEE, @SerialName("shoulder") SHOULDER, @SerialName("low_back") LOW_BACK,
    @SerialName("elbow_wrist") ELBOW_WRIST, @SerialName("hip") HIP, @SerialName("ankle") ANKLE, @SerialName("other") OTHER
}

@Serializable
enum class Side { @SerialName("left") LEFT, @SerialName("right") RIGHT, @SerialName("both") BOTH }

/** Planner muscle tiers (§3.5). Tier assignment is a convention; a priority muscle is promoted to TIER_1. */
@Serializable
enum class MuscleTier { TIER_1, TIER_2, TIER_3 }

@Serializable
enum class SplitKind {
    @SerialName("full_body_1") FULL_BODY_1, @SerialName("full_body_2") FULL_BODY_2, @SerialName("full_body_3") FULL_BODY_3,
    @SerialName("upper_lower_4") UPPER_LOWER_4, @SerialName("ul_ppl_5") UL_PPL_5, @SerialName("ppl_6") PPL_6, @SerialName("ul_6") UL_6
}

@Serializable
enum class WeekMode {
    @SerialName("normal") NORMAL, @SerialName("deload") DELOAD, @SerialName("reentry") REENTRY,
    @SerialName("travel_maintenance") TRAVEL_MAINTENANCE
}

@Serializable
enum class SlotRole { PRIMARY, SECONDARY, ACCESSORY, ISOLATION, CORE, OPTIONAL }

@Serializable
enum class SessionMode { AS_PLANNED, AMBER, RED, ALTERNATIVE_Z2_MOBILITY, DELOAD, TRAVEL }

@Serializable
enum class SetKind { RAMP, WORKING, AMRAP, CALIBRATION, DROP }

@Serializable
enum class ReadinessBand { GREEN, AMBER, RED }

@Serializable
enum class ProgressionStatus { UNKNOWN, CALIBRATING, PROGRESSING, PLATEAU, REGRESSING, DELOADING, REENTRY }

@Serializable
enum class AdjustmentKind { READINESS, LAYOFF, EQUIPMENT, TIME_BUDGET, PROGRESSION, AUTOREGULATION, DELOAD, VOLUME, ROUNDING, SAFETY, OVERRIDE, NUTRITION }

@Serializable
enum class OverrideSource { USER, CHAT, PLANNER }

@Serializable
enum class MutationScope { @SerialName("today") TODAY, @SerialName("rest_of_mesocycle") REST_OF_MESOCYCLE, @SerialName("permanent") PERMANENT }

@Serializable
enum class CardioIntensity { @SerialName("zone2") ZONE2, @SerialName("hiit_4x4") HIIT_4X4, @SerialName("hiit_10x1") HIIT_10X1, @SerialName("sit") SIT }
```

### 2.3 Knowledge assets (mirrors R4 §6 schemas)

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/Knowledge.kt
@file:UseSerializers(BandSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.model.serial.BandSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class Band(val lo: Int, val hi: Int) {
    init { require(lo <= hi) { "Band lo > hi: $lo > $hi" } }
    fun contains(v: Int): Boolean = v in lo..hi
}

@Serializable
data class MuscleWeight(val muscle: Muscle, val weight: Double)

@Serializable
data class EquipmentOption(
    @SerialName("all_of") val allOf: List<String>,
    @SerialName("load_type") val loadType: LoadType? = null,
    val note: String? = null,
    val preferred: Boolean = false
)

@Serializable
data class GoalPrescription(
    val range: Band,
    val rir: Band,
    @SerialName("rir_last_week") val rirLastWeek: Band? = null,
    val sets: Band? = null,
    val note: String? = null
)

@Serializable
data class RepProfile(
    val id: String,
    val unit: String,                       // "reps" | "seconds" | "metres" | "minutes"
    @SerialName("by_goal") val byGoal: Map<ProfileGoal, GoalPrescription>,
    val notes: String? = null,
    val sources: List<String> = emptyList()
)

@Serializable
data class Increment(
    val kind: IncrementKind,
    @SerialName("default_kg") val defaultKg: Double? = null,
    @SerialName("min_kg") val minKg: Double? = null,
    @SerialName("per_hand") val perHand: Boolean = false,
    val direction: IncrementDirection = IncrementDirection.LOAD,
    val note: String? = null
)

@Serializable
data class ExerciseFlags(
    val compound: Boolean,
    @SerialName("spinal_loading") val spinalLoading: Boolean,
    @SerialName("shoulder_sensitive") val shoulderSensitive: Boolean,
    @SerialName("knee_sensitive") val kneeSensitive: Boolean,
    @SerialName("low_back_sensitive") val lowBackSensitive: Boolean,
    @SerialName("requires_spotter_or_safeties") val requiresSpotterOrSafeties: Boolean
)

@Serializable
data class ExerciseContent(
    val description: String? = null,
    val setup: List<String> = emptyList(),
    val cues: List<String> = emptyList(),
    val mistakes: List<String> = emptyList(),
    val safety: List<String> = emptyList(),
    @SerialName("substitution_rationale") val substitutionRationale: Map<String, String> = emptyMap(),
    @SerialName("review_notes") val reviewNotes: String? = null
)

@Serializable
data class Exercise(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val pattern: MovementPattern,
    val muscles: List<MuscleWeight>,
    @SerialName("equipment_options") val equipmentOptions: List<EquipmentOption>,
    @SerialName("load_type") val loadType: LoadType,
    val unilateral: Boolean,
    val difficulty: Difficulty,
    @SerialName("rep_profile") val repProfile: String,
    @SerialName("rep_overrides") val repOverrides: Map<ProfileGoal, GoalPrescription> = emptyMap(),
    val increment: Increment,
    val progression: ProgressionKind,
    val substitutions: List<String>,
    val flags: ExerciseFlags,
    val batch: Int,
    val tags: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val content: ExerciseContent? = null,
    @SerialName("content_status") val contentStatus: String = "todo"
) {
    /** Fractional weight of this exercise for [muscle]; 0.0 if not listed. */
    fun weightOf(muscle: Muscle): Double = muscles.firstOrNull { it.muscle == muscle }?.weight ?: 0.0
    val primaryMuscles: List<Muscle> get() = muscles.filter { it.weight >= 1.0 }.map { it.muscle }
}

@Serializable
data class ExerciseLibraryFile(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("rep_profiles") val repProfiles: List<RepProfile>,
    val exercises: List<Exercise>
)

@Serializable
data class ParamSpec(
    val key: String,
    val type: String,                      // "number" | "integer" | "boolean" | "number_list"
    val unit: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val description: String? = null,
    @SerialName("default") val defaultNumber: Double? = null,
    @SerialName("default_bool") val defaultBool: Boolean? = null,
    @SerialName("default_list") val defaultList: List<Double>? = null
)

@Serializable
data class Equipment(
    val id: String,
    val name: String,
    val category: EquipmentCategory,
    val aliases: List<String> = emptyList(),
    val description: String? = null,
    val params: List<ParamSpec> = emptyList(),
    @SerialName("session_flag") val sessionFlag: Boolean = false,
    val packable: Boolean = false,
    @SerialName("default_in") val defaultIn: List<String> = emptyList(),
    val notes: String? = null
)

@Serializable
data class EquipmentGroup(val id: String, val kind: GroupKind, val name: String? = null, val members: List<String>, val notes: String? = null)

@Serializable
data class PresetItem(
    @SerialName("equipment_id") val equipmentId: String,
    @SerialName("number_params") val numberParams: Map<String, Double> = emptyMap(),
    @SerialName("bool_params") val boolParams: Map<String, Boolean> = emptyMap(),
    @SerialName("list_params") val listParams: Map<String, List<Double>> = emptyMap()
)

@Serializable
data class EquipmentPreset(val id: String, val name: String, val description: String? = null, val items: List<PresetItem>)

@Serializable
data class EquipmentCatalogFile(
    @SerialName("schema_version") val schemaVersion: Int,
    val equipment: List<Equipment>,
    val groups: List<EquipmentGroup>,
    val presets: List<EquipmentPreset>
)

/** One numeric planner constant with provenance (Appendix A / rules.json). */
@Serializable
data class RuleConstant(
    val id: String,          // e.g. "VOL-04"
    val key: String,         // e.g. "VOL-04.intermediate.lo"
    val value: Double,
    val confidence: Confidence,
    val citation: String,    // author-year or position stand, as in R3 §11
    val note: String? = null,
    @SerialName("user_adjustable") val userAdjustable: Boolean = true
)

@Serializable
data class RulesFile(@SerialName("schema_version") val schemaVersion: Int, val constants: List<RuleConstant>)

/** Mobility drill (R3 MOB-07 library), shipped in drills.json. */
@Serializable
data class Drill(
    val id: String,
    val name: String,
    val joint: String,                       // "hips" | "t_spine" | "shoulders" | "ankles" | "hamstrings" | "wrists"
    val kind: String,                        // "dynamic" | "static" | "strength_through_rom"
    val patterns: List<MovementPattern>,     // which session patterns this drill prepares
    val doseReps: Band? = null,
    val doseSeconds: Band? = null,
    @SerialName("exercise_id") val exerciseId: String? = null, // when the drill is an R4 mobility exercise
    val sources: List<String> = emptyList()
)

/** Session template slot (Appendix B), shipped in templates.json. */
@Serializable
data class SlotTemplate(
    @SerialName("slot_id") val slotId: String,
    val role: SlotRole,
    val patterns: List<MovementPattern>,     // first is preferred; later ones are family fallbacks
    val heavy: Boolean = false,              // PRIMARY heavy compound → RIR/rest/cost of a heavy lift
    @SerialName("prefer_load_types") val preferLoadTypes: List<LoadType> = emptyList(),
    @SerialName("target_muscles") val targetMuscles: List<Muscle> = emptyList(), // for ISOLATION slots
    val required: Boolean = true,
    @SerialName("superset_with") val supersetWith: String? = null
)

@Serializable
data class SessionTemplate(val id: String, val name: String, val slots: List<SlotTemplate>)

@Serializable
data class SplitTemplate(
    val kind: SplitKind,
    @SerialName("days_per_week") val daysPerWeek: Int,
    @SerialName("session_template_ids") val sessionTemplateIds: List<String>,   // in weekly order
    @SerialName("default_weekdays") val defaultWeekdays: List<Int>              // ISO 1=Mon..7=Sun
)

@Serializable
data class TemplatesFile(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("session_templates") val sessionTemplates: List<SessionTemplate>,
    val splits: List<SplitTemplate>
)
```

### 2.4 Profile, gyms, today's context

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/Profile.kt
@file:UseSerializers(LocalDateIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.model.serial.LocalDateIsoSerializer
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class Limitation(
    val id: String,
    val region: LimitationRegion,
    val side: Side = Side.BOTH,
    @SerialName("pain_0_10") val pain0to10: Int,       // INJ-00: ≤ 3 acceptable, > 3 regress
    @SerialName("since") val since: LocalDate,
    val active: Boolean = true,
    val notes: String? = null
)

/** RED-03 gate inputs. Any true value gates HIIT, max-strength zone and supplement advice behind clearance. */
@Serializable
data class MedicalFlags(
    @SerialName("known_cardiovascular_metabolic_renal_disease") val knownDisease: Boolean = false,
    val pregnancy: Boolean = false,
    @SerialName("medication_affecting_exercise") val medication: Boolean = false,
    @SerialName("doctor_restriction") val doctorRestriction: Boolean = false,
    @SerialName("clearance_confirmed_on") val clearanceConfirmedOn: LocalDate? = null
)

@Serializable
data class Profile(
    val sex: Sex,
    @SerialName("birth_year") val birthYear: Int,
    @SerialName("height_cm") val heightCm: Double,
    @SerialName("training_months_consistent") val trainingMonthsConsistent: Int,
    @SerialName("layoff_months") val layoffMonths: Int = 0,
    val goal: Goal,
    @SerialName("days_per_week") val daysPerWeek: Int,
    @SerialName("session_minutes") val sessionMinutes: Int,
    @SerialName("preferred_weekdays") val preferredWeekdays: List<Int> = emptyList(),   // ISO 1..7
    val limitations: List<Limitation> = emptyList(),
    @SerialName("medical") val medical: MedicalFlags = MedicalFlags(),
    @SerialName("priority_muscles") val priorityMuscles: List<Muscle> = emptyList(),
    @SerialName("liked_exercises") val likedExercises: List<String> = emptyList(),
    @SerialName("disliked_exercises") val dislikedExercises: List<String> = emptyList(),
    @SerialName("known_loads_kg") val knownLoadsKg: Map<String, Double> = emptyMap(),  // exerciseId → working load (onboarding)
    @SerialName("allow_advanced_exercises") val allowAdvancedExercises: Boolean = false,  // R4 §5.3
    val units: UnitSystem = UnitSystem.METRIC,
    @SerialName("cardio_inside_session_minutes") val cardioInsideSessionMinutes: Boolean = false,
    @SerialName("bedtime_local") val bedtimeLocal: String? = null,    // "HH:mm" for SUP-02 cut-offs
    @SerialName("resting_hr_known") val restingHrKnown: Boolean = false
)

@Serializable
data class BodyMetric(
    val date: LocalDate,
    @SerialName("weight_kg") val weightKg: Double? = null,
    @SerialName("entered_value") val enteredValue: Double? = null,      // sacred: exactly what the user typed
    @SerialName("entered_unit") val enteredUnit: String? = null,        // "kg" | "lb"
    @SerialName("waist_cm") val waistCm: Double? = null,
    @SerialName("resting_hr") val restingHr: Int? = null,
    val steps: Int? = null,
    @SerialName("sleep_hours") val sleepHours: Double? = null
)

@Serializable
data class GymItem(
    @SerialName("equipment_id") val equipmentId: String,
    @SerialName("number_params") val numberParams: Map<String, Double> = emptyMap(),
    @SerialName("bool_params") val boolParams: Map<String, Boolean> = emptyMap(),
    @SerialName("list_params") val listParams: Map<String, List<Double>> = emptyMap()
)

@Serializable
data class GymSet(
    val id: String,
    val name: String,
    @SerialName("preset_origin") val presetOrigin: String? = null,   // "base_gym" | "home_gym" | "hotel_gym" | null
    val items: List<GymItem>,
    val notes: String? = null
)

/** Everything that varies day to day; the realizer never reads anything else about "today". */
@Serializable
data class TodayContext(
    val date: LocalDate,
    @SerialName("gym_id") val gymId: String,
    @SerialName("extra_items") val extraItems: List<GymItem> = emptyList(),          // "travel kit" or one-off additions
    @SerialName("removed_equipment_ids") val removedEquipmentIds: List<String> = emptyList(), // "the leg press is broken today"
    val spotter: Boolean = false,
    @SerialName("minutes_override") val minutesOverride: Int? = null,
    val readiness: ReadinessCheckin? = null,
    @SerialName("ambient_cold") val ambientCold: Boolean = false,       // WU-06
    @SerialName("minutes_since_waking") val minutesSinceWaking: Int? = null,   // WU-06
    @SerialName("illness_symptoms") val illnessSymptoms: Boolean = false       // RDY-03 / LAY-02
)

/** RDY-01 inputs. All 1–5 scales as defined in R3; sleep in hours; RHR delta optional. */
@Serializable
data class ReadinessCheckin(
    val date: LocalDate,
    @SerialName("sleep_hours") val sleepHours: Double,
    @SerialName("sleep_quality") val sleepQuality: Int,
    val soreness: Int,
    @SerialName("soreness_region") val sorenessRegion: Muscle? = null,
    val stress: Int,
    val energy: Int,
    val motivation: Int,
    @SerialName("resting_hr") val restingHr: Int? = null,
    @SerialName("joint_pain_0_10") val jointPain0to10: Int = 0,
    @SerialName("joint_region") val jointRegion: LimitationRegion? = null,
    @SerialName("red_flag_reported") val redFlagReported: Boolean = false
)
```

### 2.5 Plan, session, logs

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/Plan.kt
@file:UseSerializers(LocalDateIsoSerializer::class, BandSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.model.serial.BandSerializer
import com.gymcoach.core.model.serial.LocalDateIsoSerializer
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** Why something in the plan is the way it is. Shown in the UI; never hidden (POL-01). */
@Serializable
data class Adjustment(
    @SerialName("rule_id") val ruleId: String,
    val kind: AdjustmentKind,
    val summary: String,
    val inputs: Map<String, String> = emptyMap(),
    val before: String? = null,
    val after: String? = null
)

@Serializable
data class PlannedSlot(
    @SerialName("slot_id") val slotId: String,
    val role: SlotRole,
    val pattern: MovementPattern,
    @SerialName("exercise_id") val exerciseId: String,
    val sets: Int,
    val heavy: Boolean,
    @SerialName("superset_group") val supersetGroup: String? = null,
    @SerialName("selection_notes") val selectionNotes: List<String> = emptyList()
)

@Serializable
data class CardioPrescription(
    val modality: String,                     // R4 cardio exercise id, e.g. "stationary_bike"
    val minutes: Int,
    val intensity: CardioIntensity,
    @SerialName("hr_low") val hrLow: Int? = null,
    @SerialName("hr_high") val hrHigh: Int? = null,
    val intervals: Int? = null,
    @SerialName("rule_ids") val ruleIds: List<String> = emptyList(),
    val placement: String                     // "finisher" | "separate_day" | "off_day"
)

@Serializable
data class PlannedSession(
    val id: String,
    @SerialName("template_id") val templateId: String,
    val weekday: Int,                          // ISO 1..7
    val slots: List<PlannedSlot>,
    val cardio: CardioPrescription? = null,
    @SerialName("estimated_minutes") val estimatedMinutes: Double,
    @SerialName("volume_fractional") val volumeFractional: Map<Muscle, Double>,
    @SerialName("volume_direct") val volumeDirect: Map<Muscle, Int>
)

@Serializable
data class MesoWeek(
    val index: Int,                            // 1-based
    @SerialName("start_date") val startDate: LocalDate,
    val mode: WeekMode,
    @SerialName("rir_offset") val rirOffset: Int,    // RIR-04: +1 week 1, 0 middle, −1 last, deload uses DLD-02 fixed values
    val targets: Map<Muscle, Double>,          // fractional sets/week (§3.5)
    val sessions: List<PlannedSession>,
    val notes: List<Adjustment> = emptyList()
)

@Serializable
data class Mesocycle(
    val id: String,
    @SerialName("start_date") val startDate: LocalDate,
    val goal: Goal,
    @SerialName("training_age") val trainingAge: TrainingAge,
    val split: SplitKind,
    val weeks: List<MesoWeek>,
    @SerialName("deload_week_index") val deloadWeekIndex: Int?,
    @SerialName("inputs_hash") val inputsHash: String,
    @SerialName("under_target_report") val underTargetReport: List<Adjustment> = emptyList()
)

@Serializable
data class DrillPrescription(
    @SerialName("drill_id") val drillId: String,
    val sets: Int,
    val reps: Band? = null,
    val seconds: Band? = null,
    @SerialName("rule_ids") val ruleIds: List<String> = emptyList()
)

@Serializable
data class Warmup(
    @SerialName("raise_minutes") val raiseMinutes: Int,        // WU-01 general phase; 0 when trimmed (WU-05)
    @SerialName("raise_modality") val raiseModality: String? = null,
    val drills: List<DrillPrescription>,                        // MOB-01 dynamic drills, 2×8–10
    @SerialName("total_minutes") val totalMinutes: Int
)

@Serializable
data class SetPrescription(
    val index: Int,
    val kind: SetKind,
    val reps: Band? = null,
    val seconds: Band? = null,
    val metres: Band? = null,
    @SerialName("load_kg") val loadKg: Double? = null,           // rounded to achievable (LoadRounder)
    @SerialName("load_label") val loadLabel: String? = null,     // "2×22 kg", "band: heavy", "BW+10 kg", "stack 45"
    val rir: Int? = null,
    @SerialName("rest_seconds") val restSeconds: Int
)

@Serializable
data class ExerciseBlock(
    @SerialName("block_id") val blockId: String,
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("substituted_for") val substitutedFor: String? = null,
    @SerialName("slot_role") val slotRole: SlotRole,
    val sets: List<SetPrescription>,
    @SerialName("superset_group") val supersetGroup: String? = null,
    @SerialName("progression_key") val progressionKey: ProgressionKey,
    val notes: List<String> = emptyList()
)

@Serializable
data class VolumeSnapshot(
    val fractional: Map<Muscle, Double>,
    val direct: Map<Muscle, Int>
)

@Serializable
data class SessionPlan(
    val id: String,
    @SerialName("planned_session_id") val plannedSessionId: String,
    val date: LocalDate,
    @SerialName("gym_id") val gymId: String,
    val mode: SessionMode,
    @SerialName("readiness_score") val readinessScore: Int? = null,
    val warmup: Warmup,
    val blocks: List<ExerciseBlock>,
    val cardio: CardioPrescription? = null,
    val cooldown: List<DrillPrescription> = emptyList(),
    val adjustments: List<Adjustment>,
    @SerialName("estimated_minutes") val estimatedMinutes: Double,
    val volume: VolumeSnapshot,
    @SerialName("ledger_carryover") val ledgerCarryover: Map<Muscle, Double> = emptyMap()
)

/** A logged set. Sacred: written only by the logging screen, never by the planner or the coach. */
@Serializable
data class SetLog(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("block_id") val blockId: String? = null,
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("set_index") val setIndex: Int,
    val kind: SetKind,
    @SerialName("load_kg") val loadKg: Double? = null,
    @SerialName("entered_load") val enteredLoad: Double? = null,
    @SerialName("entered_unit") val enteredUnit: String? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val metres: Int? = null,
    val rir: Int? = null,
    @SerialName("prescribed_load_kg") val prescribedLoadKg: Double? = null,
    @SerialName("prescribed_reps") val prescribedReps: Band? = null,
    @SerialName("prescribed_rir") val prescribedRir: Int? = null,
    @SerialName("logged_at_epoch_ms") val loggedAtEpochMs: Long,
    val note: String? = null
)

@Serializable
data class SessionRecord(
    val id: String,
    val date: LocalDate,
    @SerialName("gym_id") val gymId: String,
    @SerialName("planned_session_id") val plannedSessionId: String?,
    val plan: SessionPlan?,                       // frozen at start
    val status: String,                           // "planned" | "in_progress" | "completed" | "skipped"
    @SerialName("session_rpe") val sessionRpe: Int? = null,   // RDY-06
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
    val sets: List<SetLog> = emptyList()
)
```

### 2.6 Progression state

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/Progression.kt
@file:UseSerializers(LocalDateIsoSerializer::class, BandSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.model.serial.BandSerializer
import com.gymcoach.core.model.serial.LocalDateIsoSerializer
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * Progression is tracked per exercise; for machine/cable/smith/sled loads additionally per gym,
 * because stacks and sleds are not comparable between gyms (E1RM-04, TRV-04).
 */
@Serializable
data class ProgressionKey(
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("gym_scope") val gymScope: String? = null
) {
    val id: String get() = if (gymScope == null) exerciseId else "$exerciseId@$gymScope"
}

@Serializable
data class SessionSummary(
    val date: LocalDate,
    @SerialName("load_kg") val loadKg: Double?,
    @SerialName("best_reps") val bestReps: Int,
    @SerialName("min_rir") val minRir: Int?,
    @SerialName("all_sets_at_hi") val allSetsAtHi: Boolean,
    @SerialName("e1rm") val e1rm: Double?,            // null when reps > 10 or load-less (E1RM-02)
    @SerialName("e1rm_low_confidence") val e1rmLowConfidence: Boolean,
    @SerialName("readiness_band") val readinessBand: ReadinessBand?
)

@Serializable
data class ProgressionState(
    val key: ProgressionKey,
    val status: ProgressionStatus,
    @SerialName("working_load_kg") val workingLoadKg: Double? = null,
    @SerialName("working_reps") val workingReps: Band,
    @SerialName("target_rir") val targetRir: Int,
    @SerialName("last_session_date") val lastSessionDate: LocalDate? = null,
    @SerialName("last_increment_date") val lastIncrementDate: LocalDate? = null,
    @SerialName("sessions_since_increment") val sessionsSinceIncrement: Int = 0,
    @SerialName("no_progress_count") val noProgressCount: Int = 0,
    @SerialName("best_e1rm_14d") val bestE1rm14d: Double? = null,
    @SerialName("best_e1rm_14d_date") val bestE1rm14dDate: LocalDate? = null,
    @SerialName("plateau_step") val plateauStep: Int = 0,
    @SerialName("calibration_sessions") val calibrationSessions: Int = 0,
    @SerialName("rep_zone_shift") val repZoneShift: Int = 0,     // PLAT-02 step 2: +1 = one zone lighter
    val history: List<SessionSummary> = emptyList()              // last 8 sessions, newest first
)
```

### 2.7 Overrides and plan mutations (the chat/user contract)

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/Mutation.kt
@file:UseSerializers(LocalDateIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.model.serial.LocalDateIsoSerializer
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** Closed set of things the user or the coach can change. Each maps 1:1 onto a coach tool (§6.4). */
@Serializable
sealed class PlanMutation {
    abstract val rationale: String

    @Serializable @SerialName("swap_exercise")
    data class SwapExercise(
        @SerialName("from_exercise_id") val fromExerciseId: String,
        @SerialName("to_exercise_id") val toExerciseId: String,
        val scope: MutationScope,
        override val rationale: String
    ) : PlanMutation()

    @Serializable @SerialName("change_volume")
    data class ChangeVolume(
        val muscle: Muscle,
        @SerialName("delta_sets_per_week") val deltaSetsPerWeek: Int,   // −6..+6
        val scope: MutationScope,
        override val rationale: String
    ) : PlanMutation()

    @Serializable @SerialName("add_cardio")
    data class AddCardio(
        val weekday: Int,                          // ISO 1..7
        val minutes: Int,
        val modality: String?,                     // R4 cardio exercise id or null = planner picks (CAR-08)
        val intensity: CardioIntensity,
        override val rationale: String
    ) : PlanMutation()

    @Serializable @SerialName("remove_cardio")
    data class RemoveCardio(val weekday: Int, override val rationale: String) : PlanMutation()

    @Serializable @SerialName("change_goal")
    data class ChangeGoal(val goal: Goal, @SerialName("start_next_week") val startNextWeek: Boolean, override val rationale: String) : PlanMutation()

    @Serializable @SerialName("change_schedule")
    data class ChangeSchedule(
        @SerialName("days_per_week") val daysPerWeek: Int?,
        @SerialName("session_minutes") val sessionMinutes: Int?,
        @SerialName("preferred_weekdays") val preferredWeekdays: List<Int>?,
        override val rationale: String
    ) : PlanMutation()

    @Serializable @SerialName("set_checkin_question")
    data class SetCheckinQuestion(
        val question: String,
        val schedule: String,                      // "daily" | "weekly" | "once"
        @SerialName("on_date") val onDate: LocalDate? = null,
        override val rationale: String
    ) : PlanMutation()

    @Serializable @SerialName("exclude_exercise")
    data class ExcludeExercise(
        @SerialName("exercise_id") val exerciseId: String,
        @SerialName("until") val until: LocalDate?,
        override val rationale: String
    ) : PlanMutation()

    @Serializable @SerialName("pin_exercise")
    data class PinExercise(
        @SerialName("exercise_id") val exerciseId: String,
        @SerialName("session_template_id") val sessionTemplateId: String?,
        override val rationale: String
    ) : PlanMutation()

    @Serializable @SerialName("adjust_working_load")
    data class AdjustWorkingLoad(
        @SerialName("exercise_id") val exerciseId: String,
        @SerialName("gym_scope") val gymScope: String?,
        @SerialName("new_working_load_kg") val newWorkingLoadKg: Double,
        override val rationale: String
    ) : PlanMutation()

    @Serializable @SerialName("set_priority_muscles")
    data class SetPriorityMuscles(val muscles: List<Muscle>, override val rationale: String) : PlanMutation()

    @Serializable @SerialName("schedule_deload")
    data class ScheduleDeload(@SerialName("start_this_week") val startThisWeek: Boolean, override val rationale: String) : PlanMutation()

    @Serializable @SerialName("set_limitation")
    data class SetLimitation(val limitation: Limitation, override val rationale: String) : PlanMutation()

    @Serializable @SerialName("set_travel_days")
    data class SetTravelDays(@SerialName("from") val from: LocalDate, @SerialName("to") val to: LocalDate, @SerialName("gym_id") val gymId: String?, override val rationale: String) : PlanMutation()
}

@Serializable
data class Override(
    val id: String,
    @SerialName("created_at_epoch_ms") val createdAtEpochMs: Long,
    val source: OverrideSource,
    @SerialName("proposal_id") val proposalId: String? = null,     // coach tool_use id when source == CHAT
    val mutation: PlanMutation,
    @SerialName("revoked_at_epoch_ms") val revokedAtEpochMs: Long? = null,
    @SerialName("expires_on") val expiresOn: LocalDate? = null
)
```

### 2.8 Planner inputs and outputs

```kotlin
// core/src/main/kotlin/com/gymcoach/core/model/PlanInputs.kt
@file:UseSerializers(LocalDateIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.model.serial.LocalDateIsoSerializer
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class WeeklyCheckin(
    val weekStart: LocalDate,
    val answers: Map<String, String>            // "Q1".."Q15" and custom question ids → raw answer text/number
)

/** Fully materialized planner input. `:app` builds it from Room/DataStore; tests build it from JSON fixtures. */
@Serializable
data class PlanInputs(
    val profile: Profile,
    val gyms: List<GymSet>,
    @SerialName("default_gym_id") val defaultGymId: String,
    val overrides: List<Override>,
    @SerialName("progression_states") val progressionStates: List<ProgressionState>,
    @SerialName("recent_sessions") val recentSessions: List<SessionRecord>,     // last 12 weeks
    @SerialName("body_metrics") val bodyMetrics: List<BodyMetric>,              // last 12 weeks
    @SerialName("weekly_checkins") val weeklyCheckins: List<WeeklyCheckin>,     // last 8
    @SerialName("readiness_history") val readinessHistory: List<ReadinessCheckin>, // last 14 days
    @SerialName("mesocycle_anchor") val mesocycleAnchor: LocalDate?,            // start of the current mesocycle, null = start today
    @SerialName("week_of_mesocycle") val weekOfMesocycle: Int?                   // paused during TRAVEL_MAINTENANCE
)

@Serializable
data class PlanOutput(
    val mesocycle: Mesocycle,
    @SerialName("current_week_index") val currentWeekIndex: Int,
    val adjustments: List<Adjustment>
)
```

`kotlinx.serialization.json.Json` configuration used everywhere in `:core`:
`Json { encodeDefaults = true; ignoreUnknownKeys = false; classDiscriminator = "type"; prettyPrint = false }`.
`ignoreUnknownKeys = false` is deliberate: an unknown key in our own assets or snapshots is a bug we
want to fail on in tests, not a value we silently drop (sacred-content discipline applied to data
files, R4 §6.3 test 7).

---

## 3. Planner and progression engine

### 3.1 Contract

```kotlin
// core/src/main/kotlin/com/gymcoach/core/planner/Planner.kt   (signatures; bodies described below)
class Planner(
    private val library: ExerciseLibrary,
    private val catalog: EquipmentCatalog,
    private val rules: RuleBook,
    private val templates: SplitTemplates,
    private val drills: DrillLibrary
) {
    /** Stages 0–5. Pure; same inputs → same output (inputsHash is a SHA-256 of the serialized inputs). */
    fun plan(inputs: PlanInputs, today: LocalDate): PlanOutput

    /** Stage 6. Pure. */
    fun realize(planned: PlannedSession, week: MesoWeek, inputs: PlanInputs, today: TodayContext): SessionPlan

    /** Progression bookkeeping after a session is closed. Pure: returns the new states + adjustments. */
    fun onSessionClosed(record: SessionRecord, states: List<ProgressionState>, inputs: PlanInputs): SessionCloseResult

    /** Week rollover: VOL-08 gating, DLD-03 evaluation, travel mode. Pure. */
    fun rollWeek(inputs: PlanInputs, weekStart: LocalDate): WeekRollResult
}
```

Determinism rules: no randomness anywhere; ties are broken by library order (`batch`, then `id`);
all floating point is rounded to 2 decimals at ledger boundaries; `inputsHash` lets `:app` skip a
recompute when nothing changed. `Adjustment`s are produced at every stage and are part of the output,
not a log side channel.

### 3.2 Stage 0 — normalize

| Derived value | Rule | Computation |
|---|---|---|
| `age` | — | `today.year − profile.birthYear` (birth year only; month not needed for any rule) |
| `trainingAge` | `DEF-01` | `layoffMonths ≥ 12 → NOVICE`; else `< 6 → NOVICE`, `6..24 → INTERMEDIATE`, `> 24 → ADVANCED` |
| `profileGoal` | `DEF-06`, R4 §5.2 | `HYPERTROPHY, FAT_LOSS → hypertrophy`; `STRENGTH → strength`; `GENERAL_FITNESS → general_fitness`; `ENDURANCE_SUPPORT → endurance` |
| exclusions | `INJ-00..04`, R4 §5.5 | active limitation with `pain0to10 > 3`: exclude exercises whose matching sensitivity flag is set (knee→`knee_sensitive`, shoulder→`shoulder_sensitive`, low_back→`low_back_sensitive` **and** `spinal_loading`); `pain ≤ 3`: −0.3 selection penalty and a form note; elbow/wrist: `elbow_flexion`/`elbow_extension` weekly sets ×0.5 (`INJ-04`) |
| medical gate | `RED-03` | any `MedicalFlags` true and `clearanceConfirmedOn == null` → no HIIT (`CAR-05`), no max-strength zone (`LOAD-04`), supplement cards behind a clearance banner |
| difficulty gate | R4 §5.3 | `NOVICE` with < 8 weeks of logged sessions → `beginner` only; `advanced` exercises only if `allowAdvancedExercises` |
| muscle tiers | §3.5 **[convention, weak]** | TIER_1 = chest, lats, upper_back, quads, hamstrings, glutes, side_delts, ∪ `priorityMuscles`; TIER_2 = biceps, triceps, rear_delts, calves, abs; TIER_3 = everything else (no target, advisory cap 6 direct sets/week, R4 §4.5 note) |
| active overrides | — | `overrides.filter { revokedAt == null && (expiresOn == null || expiresOn ≥ today) }`; later overrides win over earlier ones for the same target |

### 3.3 Stage 1 — mesocycle skeleton

| Training age | Accumulation weeks | Scheduled deload | Source |
|---|---|---|---|
| NOVICE | 12 | none; reactive only (`DLD-03`) | `DLD-01` |
| INTERMEDIATE | 6 | week 7 | `DLD-01` (6–8) |
| ADVANCED, or any age whose targets sit at the `VOL-04` upper bound | 5 | week 6 | `DLD-01` (4–6) |

Week modes and RIR offsets (`RIR-04`, `DLD-02`, `DLD-04`):

| Week | Mode | RIR offset applied to the profile's target RIR | Set policy |
|---|---|---|---|
| 1 | `NORMAL` (or `REENTRY` if it follows a deload) | +1 | `VOL-04` lower bound; re-entry additionally prescribes 95 % of pre-deload loads |
| 2 … N−1 | `NORMAL` | 0 | ramp per `VOL-08` |
| N (last accumulation week) | `NORMAL` | −1, floors: isolation 0, compounds 1, `requires_spotter_or_safeties` without `rack_or_spotter` 2 | hold |
| N+1 | `DELOAD` | fixed RIR 4 | sets `ceil(n/2)`; compound loads ×0.85; machine/isolation loads unchanged |
| any | `TRAVEL_MAINTENANCE` | 0 (`TRV-01`: 0–2 RIR) | `TRV-01/02`; mesocycle week counter paused |

Volume ramp (`VOL-08`): `target(m, k) = min(lo(m) + (k−1)·step(m), hi(m))` with `step = 1` for
TIER_1 and `0.5` for TIER_2 (rounded at allocation), evaluated at each rollover and **held** for a
muscle when any of: mean reported RIR on that muscle's working sets last week was below the week's
target RIR (the user was working harder than planned), any readiness `soreness ≥ 3` with
`sorenessRegion` in that muscle's group, any `DLD-03` trigger active, or `PLAT-03` regression on an
exercise crediting that muscle with weight 1.0. Every hold is an `Adjustment(VOL-08, VOLUME, …)`.

Deload placement: proactive at week N+1; reactive whenever `rollWeek`/daily evaluation finds ≥ 2
`DLD-03` triggers active for ≥ 5 days — the planner then **proposes** a deload (a `PLANNER`-source
`ScheduleDeload` card) rather than imposing it, because `DLD-03` thresholds are weak-confidence
conventions. Amber/Red day modifiers (`RDY-02`), which are moderate-confidence and session-scoped,
are applied automatically. That is the general policy: **automatic = per-session, ≥ moderate
confidence; proposed = structural or weak-confidence.**

### 3.4 Stage 2 — split and session templates

`SPLIT-01` mapping, validated by `FRQ-03` (every TIER_1 muscle ≥ 2×/week when its target ≥ 10,
per-session sets within budget, per-muscle ≤ `VOL-06`):

| `daysPerWeek` | `SplitKind` | Session templates (Appendix B) in weekly order | Default weekdays |
|---|---|---|---|
| 1 | `FULL_BODY_1` | FULL_A | Wed — plus an `Adjustment(FRQ-01)` telling the user ≥ 2 days is recommended |
| 2 | `FULL_BODY_2` | FULL_A, FULL_B | Mon, Thu |
| 3 | `FULL_BODY_3` | FULL_A, FULL_B, FULL_C | Mon, Wed, Fri |
| 4 | `UPPER_LOWER_4` | UPPER_A, LOWER_A, UPPER_B, LOWER_B | Mon, Tue, Thu, Fri |
| 5 | `UL_PPL_5` | UPPER_A, LOWER_A, PUSH, PULL, LEGS | Mon, Tue, Wed, Fri, Sat |
| 6 | `PPL_6` (hypertrophy/general) or `UL_6` (strength) | PUSH, PULL, LEGS ×2 or UPPER/LOWER ×3 | Mon–Sat |

User-preferred weekdays replace the defaults; `SPLIT-02` then checks that no two sessions with a
heavy slot for the same muscle group fall on consecutive days and, if they do, swaps template order
(e.g. 4 days on Mon/Tue/Wed/Fri → UPPER_A Mon, LOWER_A Tue, UPPER_B Wed, LOWER_B Fri). If no order
satisfies `SPLIT-02` the plan is still produced with an `Adjustment(SPLIT-02, …)` note.

Strength goal variant (`FRQ-02`, `PROG-03`): the main lifts appear twice per week; the first
appearance is the heavy day (`barbell_compound` strength profile 3–6 @ RIR 2–3, top set 1–2), the
second is a moderate day (6–10 reps) whose load is derived as 85 % of the heavy-day working load
**[convention, weak]** — for `ADVANCED` this is the undulating scheme `PROG-03` asks for; for
`INTERMEDIATE` it keeps two heavy exposures without two maximal ones.

### 3.5 Stage 3 — weekly volume targets

Fractional sets per muscle per week (`DEF-05`, R4 §4.2: prime mover 1.0, secondary 0.5, minor
0.25; warm-ups 0, `WU-03`):

| Goal | TIER_1 band | TIER_2 band | Notes |
|---|---|---|---|
| HYPERTROPHY | `VOL-04` by training age: novice 8–12, intermediate 12–18, advanced 15–25 | 0.6 × TIER_1 band, rounded, floor 4 (`VOL-01`) → novice 5–7, intermediate 7–11, advanced 9–15 | tier factor **[convention, weak]** |
| STRENGTH | main lifts: 4–10 sets per lift per week over ≥ 2 sessions (`VOL-05`); muscles: novice band 8–12 regardless of age | 4–7 | strength volume counted per *lift* (squat/bench/deadlift/OHP families) on top of muscle accounting |
| GENERAL_FITNESS | 8–12 | 4–7 | plus cardio per `CAR-07` |
| FAT_LOSS | 0.6–1.0 × accustomed (mean of the last completed mesocycle), never < 4 (`VOL-09`); if no history: 0.8 × hypertrophy lower bound | same rule | prefer trimming sets over load |
| ENDURANCE_SUPPORT | 4–6 (`VOL-01/02` floors) | 2–4 | 2 full-body sessions |

Caps: per session per muscle `VOL-06` = 12 fractional (default fill stops at 10 and only exceeds it
when no other session can take the set); per exercise per session from the profile's `sets` band
(rules.json defaults: `barbell_compound` 3–5, `compound` 3–4, `isolation`/`isolation_light` 2–4,
`bodyweight_progression` 3–4, `core_*` 2–3, `carry` 2–3, `power` 3–5); session total `SESS-02` (25;
novice 20; advanced 32). Advisory direct-count cap: when a muscle's **direct** count reaches the RP
MRV lower bound (R4 §4.5, intermediate landmarks) the allocator stops adding direct sets to it and
notes `Adjustment(R4-4.5, VOLUME, "direct sets at RP MRV edge")` — the two counting views are shown
side by side and never mixed (R4 §4.2).

### 3.6 Stage 4 — exercise selection

For each session template slot, candidates are library exercises whose `pattern` is in
`slot.patterns`, available in the mesocycle's default gym (R4 §2.2 requirement algebra), not
excluded (Stage 0), and not difficulty-gated. A pinned exercise (`PinExercise` or a permanent
`SwapExercise` override for this template) takes the slot outright if it is available. Otherwise:

```
score(e, slot, session, week) =
    0.35 · patternPref      (1.0 if e.pattern == slot.patterns[0]; 0.6 if a later family member)
  + 0.25 · coverage         (Σ_m e.weight(m) · deficit(m) / maxCoverage, deficit = max(0, target − ledgerSoFar) · tierWeight)
  + 0.15 · continuity       (1.0: PROGRESSING/PLATEAU state used in the last 8 weeks; 0.5: any state; 0: none)
  + 0.10 · preference       (1.0 liked; 0 neutral; disliked are excluded)
  + 0.10 · loadTypePref     (1.0 if e.loadType ∈ slot.preferLoadTypes, else 0.5)
  + 0.05 · difficultyFit    (1.0 if difficulty ≤ training-age level, 0.5 otherwise)
  − 0.5  if e already selected in this session
  − 0.2  if e selected in another session this week and slot.role ∉ {PRIMARY} (variety; main lifts repeat on purpose, FRQ-02)
  − 0.3  if a flag conflicts with an active limitation with pain ≤ 3 (INJ-00)
  − 0.3  if e.flags.requiresSpotterOrSafeties and the gym lacks rack_or_spotter (still selectable; RIR capped at 2 and safety note, R4 §5.5)
```

Weights are `rules.json` constants (`SEL-*`, **[convention, weak]**). Tie-break: library order.
`coverage` is what makes the selection volume-aware: on UPPER_B the `chest_fly|elbow_extension`
accessory slot picks a fly when chest is behind target and a triceps extension when triceps is.

### 3.7 Stage 5 — set allocation and the time model

Time model (`SESS-01`, minutes per working set including rest): heavy lower barbell 4.0, heavy upper
barbell 3.5, moderate compound 2.5, isolation/machine 1.75; unilateral ×1.7 (`UNI-02`); superset
pair `max(2.5, 0.7·(cost_a + cost_b))` per pair of sets (`REST-03`); warm-up 10 min (`WU-01`),
5 min when the session is ≤ 45 min (`WU-05`), +2 min and one extra ramp set when `WU-06` applies.
"Heavy" is a slot property (PRIMARY with `heavy = true`); "lower barbell" = pattern ∈ {squat, hinge}
with load type barbell/smith. Rest-cut concession (§3.8 level 4) prices isolation at 1.5
**[convention, weak]**.

```
allocate(week):
  for each session s:  sets[s][slot] = profile.sets.lo for required slots, 0 for OPTIONAL slots
  ledger = Σ fractional credits over the week
  budget[s] = minutes(s) − warmup(s) − (cardioInsideSession ? cardioMinutes(s) + 3 : 0)
  loop:
    best = null
    for each (s, slot) with sets < profile.sets.hi
                          and time(s) + cost(s, slot) ≤ budget[s]
                          and sessionFractional(s, m) + w_m ≤ VOL-06 for every m credited by the exercise
                          and (direct(m) < RP_MRV_lo(m) for every m with w_m == 1.0)          -- advisory cap
                          and sessionSets(s) < SESS-02:
        gain = Σ_m w_m · max(0, target_m − ledger_m) / target_m · tierWeight(m)   (tier 1 → 1.0, tier 2 → 0.6, tier 3 → 0)
        keep the largest gain; ties → earlier session, earlier slot
    if best == null or gain ≤ 0: break
    sets[best] += 1; update ledger and time
  rebalance (REBAL, convention):
    while ∃ tier-1 muscle with deficit ≥ 1 and ∃ tier-2 muscle with surplus ≥ 2 credited by an ISOLATION/ACCESSORY slot in a session
          that has an available exercise for the deficit muscle at ≤ the same time cost:
        move one set (swap the exercise in that slot if needed, keeping the superset pairing)
  optional slots: open an OPTIONAL isolation slot only if a deficit ≥ 2 remains and the budget allows
  report: remaining deficits → Mesocycle.underTargetReport; any tier-1/2 muscle < VOL-01 (4) → escalate
          with a PLANNER proposal ("+1 training day" / "+10 min per session" / "accept")
```

The allocator fills *the week*, not one session at a time, so a muscle that cannot fit on Monday is
placed on Thursday. It never exceeds the session's minute budget: exactness matters because the
user's session length is a hard real-world constraint on the road.

### 3.8 Stage 6 — daily realization (`SessionRealizer`)

Applied in this order; each step appends `Adjustment`s:

1. **Layoff** (`LAY-01/02`). Days since the *pattern* was last trained (any exercise of that
   pattern, in any gym): 5–13 → loads ×0.95, −1 set, RIR +1; 14–28 → ×0.875, sets ×0.7, RIR +2;
   29–56 → ×0.775, sets ×0.5, RIR +2; > 56 → ×0.65 with `VOL-04` lower-bound sets and RIR 3–4
   (novice restart `PROG-05` if the user is novice); age ≥ 65 one row more conservative. If the
   pattern was trained but this particular exercise was not for ≥ 14 days (typical after a
   substitution-heavy trip), apply the 5–13-day row only **[convention, weak]**. Illness: `LAY-02`.
2. **Readiness** (`RDY-01..04`, `SLP-03`). Score → band → `SessionMode` and modifiers (§4.6). Red
   offers the 25–30 min zone-2 + mobility alternative as a choice, not a default.
3. **Equipment.** Availability against `today.gymId + extraItems − removedEquipmentIds + spotter`.
   Unavailable exercise → `SubstitutionEngine` (§3.11). Available but load-infeasible
   (e.g. `dumbbells.max_kg` < working load) → explicit warning + ranked alternatives
   (never a silent change, R4 §2.6): keep the exercise at the max load with the rep band extended by
   +2 at the top and RIR −1 (floor per `RIR-03`), or take the top substitution; the user picks in the
   substitution sheet; the default pre-selected choice is "keep at max load, extend reps" when the
   max load is ≥ 60 % of the working load, else the substitution.
4. **Time budget** (`TIME-01`). Concession levels 0–7 applied in order until a *minimum viable
   session* (≥ 2 sets on every required slot, ≥ 1 on the two priority lifts) fits the budget, then a
   greedy **refill** adds sets back in deficit order while the budget allows:
   0 none · 1 warm-up trim (`WU-05`) · 2 drop isolation sets for muscles already ≥ `VOL-01` from
   compounds this week · 3 antagonist/non-competing supersets for ISOLATION/ACCESSORY slots
   (`REST-03`) · 4 rest cut on machine/isolation to 60 s (never main-lift rest < 2 min, `REST-04`) ·
   5 one exercise per pattern · 6 two sets per exercise (never < 1 on the two priority lifts) ·
   7 move the lowest-priority pattern's slot to another session this week (ledger carryover, §3.12).
5. **Prescription per set** from the exercise's `ProgressionState`: load (rounded by `LoadRounder`
   to what the gym can actually load — bar + plate pairs from `plates.smallest_plate_kg`, dumbbell
   rack step, stack step/half step, `IncrementDirection.ASSIST` for assisted machines; a rounding
   error > 5 % of the target produces `Adjustment(ROUNDING)`), rep band from the profile ± zone
   shift, RIR = profile target + week offset + readiness offset (+ layoff offset), capped at ≥ 2 on
   `requires_spotter_or_safeties` lifts without `rack_or_spotter`; rest: heavy compounds 180 s
   (`REST-02` 2–5 min; 240 s for ≤ 6-rep top sets), moderate compounds 120 s (`REST-01`),
   isolation 90 s (`REST-03`), 60 s under concession level 4. `UNKNOWN` state → find-load protocol
   (§3.9). AMRAP calibration set (`RIR-05`): every 2–3 weeks the last set of one machine/isolation
   exercise is marked `AMRAP` to recalibrate RIR reporting.
6. **Warm-up** (§4.1): `WU-01/02/05/06` + `MOB-01` drills chosen by the session's patterns and the
   user's limitations.
7. **Cardio finisher** if the week plan places cardio on this day (`ORD-03`, `CAR-06/07/08`).
8. **Cool-down**: optional 5 min easy movement (`MOB-06`); static holds only for joints in the
   chronic mobility program (`MOB-02/04`).
9. **Volume snapshot**, ledger carryover, adjustments.

### 3.9 Progression state machine (per `ProgressionKey`)

```
UNKNOWN ──first session (find-load)──► CALIBRATING ──2 sessions──► PROGRESSING
PROGRESSING ──noProgressCount ≥ 3 (PLAT-01)──► PLATEAU ──improvement──► PROGRESSING
PROGRESSING ──e1RM ≤ 0.95·best on 2 consecutive sessions, no external cause (PLAT-03)──► REGRESSING
PLATEAU/REGRESSING ──deload week starts (proactive or accepted reactive)──► DELOADING ──week ends──► REENTRY ──1 week──► PROGRESSING
any ──deload week starts──► DELOADING            (all keys move together on a deload week)
LAYOFF is not a state: it is a prescription modifier computed from dates (§3.8 step 1).
```

Find-load protocol (`UNKNOWN`, `TRV-04` "RIR-based load find"): start load `L0` = `profile.knownLoadsKg[exerciseId]`
if present, else a transfer estimate from a sibling exercise with a state (Appendix C, seed only),
else the floor (empty bar 20 kg, lightest dumbbell, one stack plate, body weight). Ramp sets of
`lo + 2` reps at `L0`, `1.15·L0`, `1.30·L0`, `1.45·L0` (max 4, rest 60–90 s) until the reported RIR
≤ target + 2; that set becomes working set 1 and the remaining working sets use its load; state →
`CALIBRATING`, `workingLoadKg` = that load.

`onSessionClosed` for a `PROGRESSING` key (pseudo-Kotlin):

```kotlin
val working = record.sets.filter { it.exerciseId == key.exerciseId && it.kind == SetKind.WORKING }
val allAtHi = working.isNotEmpty() && working.all { (it.reps ?: 0) >= state.workingReps.hi && (it.rir ?: 99) <= state.targetRir }
val allEasy = working.all { (it.rir ?: 0) >= state.targetRir + 2 }                    // PROG-04 first clause
val cadenceOk = when (trainingAge) {
    NOVICE -> true                                                                     // PROG-03 linear
    INTERMEDIATE -> state.lastIncrementDate == null || daysBetween(state.lastIncrementDate, today) >= 7
    ADVANCED -> week.index == 1 || isHeavyDayFor(key) && daysBetween(state.lastIncrementDate, today) >= 7
}
if ((allAtHi || allEasy) && cadenceOk) {
    newLoad = rounder.round(state.workingLoadKg + increment(exercise, gymParams), exercise.loadType, gymParams)
    // PROG-02: if the smallest achievable jump exceeds 10 % (dumbbells/machines), extend the rep band by +2 instead and try again next session
    adjustments += Adjustment("PROG-01", PROGRESSION, "all sets at ${hi} reps ≤ RIR ${target} → +${increment} kg", before = "${old} kg", after = "${newLoad} kg")
    state = state.copy(workingLoadKg = newLoad, lastIncrementDate = today, sessionsSinceIncrement = 0, noProgressCount = 0)
} else {
    val improved = summary.e1rm?.let { it > (state.bestE1rm14d ?: 0.0) * 1.005 } ?: false ||
                   summary.bestReps > previousBestRepsAtSameLoad
    val readinessOk = summary.readinessBand != ReadinessBand.RED
    state = state.copy(noProgressCount = if (improved || !readinessOk) 0 else state.noProgressCount + 1)  // PLAT-01 counts only "fair" sessions
    if (state.noProgressCount >= 3) state = state.copy(status = PLATEAU, plateauStep = 0)
}
// PLAT-03 regression check (main lifts, e1RM valid only when reps + RIR ≤ 10, E1RM-02/03)
if (summary.e1rm != null && !summary.e1rmLowConfidence && state.history.take(1).any { it.e1rm != null && it.e1rm <= best * 0.95 } && summary.e1rm <= best * 0.95 && noExternalCause)
    state = state.copy(status = REGRESSING)
```

In-session autoregulation (`PROG-04`, second clause): when the logged RIR of working set 1 is
≤ target − 2, the session screen re-prescribes sets 2..n at 95 % of the load (rounded) and shows
`Adjustment(PROG-04, AUTOREGULATION, "set 1 RIR 0 vs target 2 → remaining sets 75 kg", before = "80 kg", after = "75 kg")`.
The logged set 1 stays exactly as entered.

Plateau ladder (`PLAT-02`, one step per week while in `PLATEAU`):

| `plateauStep` | Action | Who acts |
|---|---|---|
| 1 | Audit: push CHK Q1–Q3 (sleep, sleep quality, protein days) to the next check-in; coach context flags the plateau; `SLP-03`/`NUT-01` modifiers apply if the answers trigger them | planner + coach message |
| 2 | Rep-zone shift (`LOAD-04` one zone): hypertrophy → one zone lighter (`repZoneShift = +1`, e.g. barbell 5–10 → 8–12); strength → one zone lighter too (3–6 → 5–8) for two weeks | planner (automatic, per-exercise, `Adjustment`) |
| 3 | Variation swap to the first available `substitutions[]` entry for the rest of the mesocycle; new key starts `CALIBRATING` with a transfer seed | planner **proposal** (`SwapExercise`, scope `REST_OF_MESOCYCLE`) |
| 4 | +1–2 weekly sets for the muscle if under the `VOL-04` upper bound | planner (automatic, `VOLUME` adjustment) |
| 5 | Reactive deload | planner **proposal** (`ScheduleDeload`) |

`REGRESSING` feeds the `DLD-03` trigger set ("e1RM down ≥ 5 % on two sessions"); the other triggers
are computed from readiness history (Red ≥ 3 of last 7 days; soreness ≥ 4 on check-ins spanning
≥ 72 h; sleep quality ≤ 2 on ≥ 4 nights; motivation ≤ 2 on ≥ 4 days; new joint pain ≥ 3/10).
≥ 2 triggers for ≥ 5 days → reactive deload proposal. Deload week (`DLD-02`): `sets = ceil(n/2)`,
compound loads ×0.85, machine/isolation loads unchanged, RIR 4, frequency unchanged, warm-up and
mobility kept. Re-entry (`DLD-04`): 95 % loads, `VOL-04` lower-bound sets, RIR +1 for one week; the
state's `workingLoadKg` is *not* lowered — the 95 % is a prescription modifier, so the following
week resumes at the pre-deload working load.

### 3.10 e1RM and what counts as progress

`E1rm.estimate(loadKg, reps, rir)`: `r_eff = min(10, reps + rir)` (`E1RM-03`); if `reps > 10` the
estimate is flagged `lowConfidence` and never used for decisions (`E1RM-02`); otherwise the mean of
Epley `w·(1 + r/30)` and Brzycki `w·36/(37 − r)` (`E1RM-01`). Because `r_eff` is capped at 10, any
set with `reps + RIR ≥ 10` yields the same e1RM (80 kg × 8 @ RIR 2 and 80 kg × 10 @ RIR 0 both give
106.7 kg) — so for hypertrophy rep ranges the primary progress signal is **reps at load** and e1RM is
the tie-breaker; for strength ranges it is the reverse. Machines/cables track best `load × reps at
RIR ≤ 2` per gym scope instead (`E1RM-04`). The 14-day rolling best is what plateau/regression logic
reads; the Progress screen shows both the rolling best and the per-session values with the formula ID.

### 3.11 Equipment-driven substitution with volume accounting

```
substitute(block, todayGym, ledgerContext):
  1. ranked = exercise.substitutions.filter { available(it, todayGym) && !excluded(it) }
  2. candidates = ranked (kept in R4 rank order, +0.10 rank bonus for position 0) ∪
                  library.filter { it.pattern ∈ family(exercise.pattern) && available && !excluded }
  3. score(c) = 0.35·patternMatch          (1.0 same pattern; 0.6 same family: {squat, lunge_split}, {hinge, hip_extension_isolation},
                                            {horizontal_push, incline_push}, {horizontal_pull}, {vertical_pull}, {chest_fly, horizontal_push}, …)
              + 0.35·cosine(muscleVector(exercise), muscleVector(c))
              + 0.15·loadFit(c)            (achievable / desired working load: ≥ 0.85 → 1.0; 0.60–0.85 → 0.7 (top of rep band);
                                            0.40–0.60 → 0.4 (endurance band 15–30 @ 0–1 RIR, TRV-01/LOAD-01); < 0.40 → 0.1)
              + 0.10·continuity            (1.0 if c has a PROGRESSING state in the same gym scope; 0.5 any state; 0 none)
              + 0.05·difficultyFit
              − 0.20 if c.repProfile is power/carry/cardio while the slot is a strength/hypertrophy compound slot
              − 0.30 flag conflict with a mild limitation; excluded if pain > 3
  4. pick the max; if the winner is already in the session, MERGE (sets add up to the per-exercise cap)
  5. re-run VolumeAccountant for the session; deficit(m) = plannedFractional(m) − realizedFractional(m)
  6. carry deficits to the remaining sessions of this week (§3.12); write Adjustment(EQUIPMENT, "TRV-04", …) with the score table
```

`desired working load` for a candidate = its own state's `workingLoadKg` if it has one, else the
Appendix C transfer seed from the replaced exercise's working load; `achievable` from gym parameters
(dumbbell `max_kg` × 2 for bilateral, unilateral candidates count 2 × per-hand against a desired load
halved by the 0.5 unilateral factor of Appendix C; stack maximum; plate inventory is assumed
unlimited unless the gym set says otherwise). The `−0.20` profile penalty exists because muscle
similarity alone ranks a 20 kg dumbbell swing above a single-leg RDL as a trap-bar-deadlift
substitute (E4 below shows the numbers); a hypertrophy compound slot should not silently become a
power drill.

### 3.12 Missing equipment and the weekly ledger

`WeeklyLedger(weekStart, targets, achieved, plannedRemaining, carryover)` is rebuilt from logs and
the realized sessions on every `realize()`:

1. `deficit(m)` after a substituted or trimmed session = planned − realized fractional credit.
2. For each remaining session of the week in weekday order: add sets to an exercise already
   crediting `m` (respecting per-exercise cap, `VOL-06` = 12, `SESS-02`, and that session's minute
   budget); if none can take a set, open an OPTIONAL isolation slot for `m` if the budget allows.
3. Whatever is left is reported (`SessionPlan.ledgerCarryover` and the Plan screen's "this week"
   card). If a TIER_1/2 muscle would end the week below `VOL-01` (4 sets), the planner proposes a
   20-minute micro-session (bodyweight/band, no gym) as a `PLANNER` card.
4. Carryover never crosses a week boundary — next week's targets are what `VOL-08` says they are.
5. Surplus from a substitute that credits an already-full muscle is fine unless it breaks `VOL-06`
   for that session, in which case the substitute's sets are reduced and noted.

### 3.13 One-off hotel day vs travel week

- **One-off day.** The user opens Today, picks the gym ("Hotel gym" preset, or "Quick set: dumbbells
  up to 20 kg + bench" which creates a temporary gym set), optionally sets today's minutes. The
  planned session for that day is realized against that set (§3.8). Progression keys for the
  substitutes are separate (`dumbbell_*` exercises have their own state; machine/cable keys are per
  gym), so the barbell lifts' states are untouched; the pattern stays trained, so no `LAY-01`
  regression applies when the user is back. The week's ledger absorbs what it can.
- **Travel week** (CHK Q10 ≥ 3 days, or a `SetTravelDays` override, or two consecutive hotel-set
  sessions): the week is re-planned in `TRAVEL_MAINTENANCE` mode: two full-body sessions of 25–40
  minutes built from the TRV-02 template that matches the equipment (dumbbells-only / bodyweight +
  band / hotel gym with cables and 1–2 machines), 4–6 exercises covering `MIX-02` patterns as far as
  the equipment allows, 2–3 sets each, 6–15 reps (15–30 to 0–1 RIR when only light loads exist),
  rest 60–90 s or antagonist supersets (`TRV-01`). Targets are the `VOL-01` floors. The mesocycle
  week counter and the RIR schedule pause; the deload cadence, measured in mesocycle weeks, pauses
  with them. On return the next week resumes the paused week index; exercises not performed for
  ≥ 14 days get the 5–13-day `LAY-01` row (95 %, RIR +1) because their patterns were maintained.
  `TRV-03` (≈ ⅓ of accustomed volume maintains for weeks) is shown as reassurance text, tagged weak.

### 3.14 Chat proposals as plan mutations

Every coach tool call parses into a `PlanMutation` (§2.7) and passes `MutationValidator` before a
card is shown:

| Mutation | Validation (reject → `is_error` tool result with the reason; warn → card shows the warning) |
|---|---|
| `SwapExercise` | both IDs exist; `to` available in the mesocycle's default gym (or today's gym for scope `TODAY`); pattern family compatible (else warn "pattern change: X → Y, weekly coverage for m drops by n"); flag conflict with active limitation → reject if pain > 3; `advanced` difficulty → warn unless allowed; `requires_spotter_or_safeties` without rack → warn "RIR capped at 2" |
| `ChangeVolume` | `−6 ≤ delta ≤ +6`; resulting weekly target ≥ `VOL-01` (else reject) and ≤ `VOL-04` upper bound (else warn "above the evidence band; sets beyond hi are user-owned"); resulting per-session sets ≤ `VOL-06` (else warn "will need a session added") |
| `AddCardio` | weekday 1–7; HIIT requires ≥ 4 weeks of zone-2 base in logs and `RED-03` clearance (else reject with the rule); `CAR-06`: hard cardio < 24 h before a heavy leg session → warn + planner switches modality to bike/rower/elliptical (`CAR-08`); hypertrophy/strength goals: > 3 sessions or > 150 min beyond the health floor → warn |
| `ChangeGoal` | any → applies at next week (default) or now; triggers a new mesocycle skeleton; warn that progression states keep their loads but rep bands change |
| `ChangeSchedule` | 1–7 days; 20–150 minutes; `SPLIT-02` check; re-plan at next week |
| `SetCheckinQuestion` | text ≤ 200 chars; schedule valid; max 5 custom questions active |
| `ExcludeExercise` | ID exists; if it is a PRIMARY slot exercise with no available substitute → reject |
| `AdjustWorkingLoad` | ≤ +10 % / −20 % of the current working load (else reject: "log a set instead"); must be `PROGRESSING`/`CALIBRATING`; creates an `Adjustment(OVERRIDE)` — the logged history is untouched |
| `SetPriorityMuscles` | ≤ 3 muscles |
| `ScheduleDeload` | not during `TRAVEL_MAINTENANCE`; not within 2 weeks of the last deload (warn) |
| `SetLimitation` | region valid; pain 0–10; `pain ≥ 7` or `redFlagReported` → reject planning and show `RED-01/02` hand-off text |
| `SetTravelDays` | `from ≤ to`, ≤ 21 days |

Application: user taps **Apply** → `Override(source = CHAT, proposalId = tool_use.id)` is appended →
`PlanRepository.recompute()` → `PlanDiff` (sessions changed, sets per muscle before/after, new
under-target report) → `tool_result` content `{"status":"applied","diff":{…}}`. **Reject** →
`{"status":"rejected","reason":"<user's optional text>"}`. The coach never writes logs, never edits
an override, and never sees a "success" it did not earn: if the recompute produces a warning, the
warning is in the diff. Overrides can be revoked from the Plan screen ("undo this change"), which
appends `revokedAt` — history is append-only.

### 3.15 Worked examples

Assumptions used throughout (the real values come from onboarding; these are placeholders, marked
so the numbers can be re-run): male, born 1988 (38 y), 84.0 kg, 180 cm, 18 months consistent
training, no layoff → `INTERMEDIATE`; goal `HYPERTROPHY`; 4 days/week; 60 minutes; no limitations;
default gym = R4 `base_gym` preset (dumbbells max 50 kg step 2, plates 1.25 kg smallest, power rack
with safeties); onboarding known loads: bench 80 kg, back squat 110 kg, RDL 100 kg, trap-bar
deadlift 140 kg, OHP 50 kg, hip thrust 120 kg, hack squat 160 kg sled load, Bulgarian split squat
2×22 kg; resting HR 58;
bedtime 23:00. All figures below were computed with the same formulas the code will use.

#### E1 — the base week (Stages 0–5)

Stage 0: `INTERMEDIATE`, profile goal `hypertrophy`, tiers as in §3.2. Stage 1: 6 accumulation
weeks + deload week 7; week 1 RIR offset +1. Stage 2: `UPPER_LOWER_4`, Mon UPPER_A, Tue LOWER_A,
Thu UPPER_B, Fri LOWER_B (`SPLIT-02` satisfied). Stage 3 week-1 targets: TIER_1 12 fractional sets
(chest, lats, upper_back, quads, hamstrings, glutes, side_delts), TIER_2 7 (biceps, triceps,
rear_delts, calves, abs). Stage 4/5 output (sets after fill and rebalance; minutes from the `SESS-01`
model):

**UPPER_A (Mon) — 23 working sets, 50.0 + 10 warm-up = 60.0 min**

| Slot | Exercise | Sets | min/set | Fractional credit per set |
|---|---|---|---|---|
| PRIMARY heavy | `barbell_bench_press` | 4 | 3.5 | chest 1.0, front_delts 0.5, triceps 0.5 |
| PRIMARY | `seated_cable_row` | 3 | 2.5 | lats 1.0, upper_back 1.0, biceps 0.5, rear_delts 0.5, lower_back 0.25 |
| SECONDARY | `seated_dumbbell_shoulder_press` | 3 | 2.5 | front_delts 1.0, side_delts 0.5, triceps 0.5 |
| SECONDARY | `lat_pulldown` | 3 | 2.5 | lats 1.0, upper_back 0.5, biceps 0.5, rear_delts 0.25 |
| ISOLATION (superset A) | `dumbbell_lateral_raise` | 4 | 1.75 | side_delts 1.0, traps_upper 0.25 |
| ISOLATION (superset A) | `face_pull` | 2 | 1.75 | rear_delts 1.0, upper_back 0.5, rotator_cuff 0.5 |
| ACCESSORY (superset B) | `pec_deck_fly` | 2 | 1.75 | chest 1.0, front_delts 0.25 |
| ISOLATION (superset B) | `incline_dumbbell_curl` | 2 | 1.75 | biceps 1.0 |

Time: 14 + 7.5 + 7.5 + 7.5 + (2 pairs × 2.5 + 2 singles × 1.75 = 8.5) + (2 pairs × 2.5 = 5.0) = 50.0.

**LOWER_A (Tue) — 18 sets, 49.5 + 10 = 59.5 min:** `back_squat` 4 (4.0), `romanian_deadlift` 3
(4.0), `leg_press_45` 3 (2.5), `seated_leg_curl` 3 (1.75), `standing_calf_raise` 3 (1.75),
`cable_crunch` 2 (1.75).

**UPPER_B (Thu) — 22 sets, 50.0 + 10 = 60.0 min:** `pull_up` 4 (2.5), `incline_dumbbell_press` 4
(2.5), `overhead_press_barbell` 3 (3.5, heavy), `chest_supported_dumbbell_row` 3 (2.5),
`cable_lateral_raise` 4 + `dumbbell_rear_delt_fly` 2 as superset (8.5), `cable_fly` 2 (3.5).

**LOWER_B (Fri) — 19 sets, 49.5 + 10 = 59.5 min:** `trap_bar_deadlift` 3 (4.0), `hack_squat` 3
(2.5), `hip_thrust` 3 (2.5), `bulgarian_split_squat` 2 (2.5 × 1.7 = 4.25, unilateral),
`lying_leg_curl` 3 (1.75), `seated_calf_raise` 3 (1.75), `hanging_leg_raise` 2 (1.75).

How the allocator got there. The greedy fill (all slots at their lower bound, then marginal-gain
adds until each session's 50 lifting minutes were used) first produced UPPER_A with
`cable_triceps_pushdown` 2 in the accessory slot and UPPER_B with an `overhead_cable_triceps_extension`
2 + `barbell_curl` 2 superset. That week's ledger read: chest **8.0** (target 12), side_delts **10.0**
(12), abs **4.0** (7), but triceps **11.0** (target 7) and biceps **10.5** (7). The REBAL pass moved
sets from the tier-2 surpluses to the tier-1 deficits at equal time cost: UPPER_A pushdown → pec deck
(+2 chest, −2 triceps); UPPER_B triceps/curl superset → cable fly 2 + one more cable lateral raise
(+2 chest, +1 side delts, −2 triceps, −2 biceps). Final week-1 ledger (82 working sets):

| Muscle | Tier | Target | Fractional | Direct | Δ | RP direct landmark (R4 §4.5) |
|---|---|---|---|---|---|---|
| chest | 1 | 12 | **12.00** | 12 | 0 | MAV 6–16 ✓ |
| lats | 1 | 12 | 13.00 | 13 | +1 | back MAV 8–20 ✓ |
| upper_back | 1 | 12 | 13.00 | 6 | +1 | (back aggregate) |
| quads | 1 | 12 | 14.25 | 12 | +2.25 | MAV 6–14 ✓ (at the top) |
| hamstrings | 1 | 12 | 12.75 | 9 | +0.75 | direct 9 ≥ MRV lower bound 8 → advisory cap engaged |
| glutes | 1 | 12 | 13.50 | 6 | +1.5 | MEV 6–8 — direct view under MAV, fractional view on target (why both views are shown) |
| side_delts | 1 | 12 | 11.00 | 8 | −1 | MAV 8–24 ✓; within the ±1 tolerance |
| biceps | 2 | 7 | 8.50 | 2 | +1.5 | MV 6–8 direct: "covered by pulls" |
| triceps | 2 | 7 | 7.00 | 0 | 0 | MV 0–4 |
| rear_delts | 2 | 7 | 8.75 | 4 | +1.75 | MAV 4–12 ✓ |
| calves | 2 | 7 | 7.50 | 6 | +0.5 | MAV 6–16 ✓ |
| abs | 2 | 7 | 4.00 | 4 | **−3** | MEV 0–4 |
| front_delts / lower_back / adductors / forearms / … | 3 | — | 11.0 / 4.75 / 4.75 / 2.0 | 6 / 0 / 0 / 0 | — | no target; direct ≤ 6 cap ✓ |

Under-target report: `abs −3` (budget exhausted at 60.0/59.5/60.0/59.5 min). Planner card: "Abs are
3 sets/week under the tier-2 target. Options: two 5-minute core blocks at home (plank 2×30–60 s +
dead bug 2×10–20, no equipment) on Wed/Sat — or accept; RP's direct landmark for abs (MEV 0–4) says
4 direct sets is the minimum effective, so 'accept' is defensible." No `VOL-01` floor is threatened.

Week-1 prescriptions for the first two UPPER_A blocks (RIR offset +1 → `barbell_compound`
hypertrophy 5–10 @ RIR 2–3 becomes target RIR 3; `compound` 8–12 @ RIR 3→1 becomes 3):

| Block | Sets | Reps | Load | RIR | Rest | Warm-up ramp (`WU-02`, moderate compound row: 50 % × 8 → 70 % × 4–5) |
|---|---|---|---|---|---|---|
| `barbell_bench_press` (80 kg, `PROGRESSING`) | 4 | 5–10 | 80 kg | 3 | 180 s | 40 kg × 8, 55 kg × 5 (0.7 × 80 = 56 → nearest loadable 55) |
| `seated_cable_row` (state `UNKNOWN`, no known load) | 3 | 8–12 | find-load: 40 → 46 → 52 → 60 kg (stack steps) until RIR ≤ 5 | 3 | 120 s | the ramp *is* the warm-up |

#### E2 — progression on the bench across the mesocycle (`PROG-01/02/03`, `E1RM-*`)

| Week | Target RIR | Prescribed | Logged (reps @ RIR) | Decision |
|---|---|---|---|---|
| 1 | 3 | 4 × 5–10 @ 80 | 8, 8, 7, 7 @ 3 | hold (not all sets at 10) |
| 2 | 2 | 4 × 5–10 @ 80 | 9, 9, 8, 8 @ 2 | hold; reps-at-load improved → `noProgressCount = 0` |
| 3 | 2 | 4 × 5–10 @ 80 | 10, 10, 9, 9 @ 2 | hold (2 sets short of `hi`) |
| 4 | 2 | 4 × 5–10 @ 80 | 10, 10, 10, 10 @ 2 | `PROG-01`: all sets at 10 ≤ RIR 2, cadence ok (intermediate, ≥ 7 days) → **+2.5 kg** (upper barbell `PROG-02`) → 82.5 kg |
| 5 | 2 | 4 × 5–10 @ 82.5 | 8, 8, 7, 7 @ 2 | hold |
| 6 | 1 | 4 × 5–10 @ 82.5 | 9, 9, 8, 8 @ 1 | hold |
| 7 deload | 4 | `ceil(4/2)` = 2 × 5–10 @ 0.85 × 82.5 = 70.1 → **70 kg** | — | `DLD-02` |
| 8 (meso 2, week 1, `REENTRY`) | 3 | 3 × 5–10 @ 0.95 × 82.5 = 78.4 → **77.5 kg** | — | `DLD-04`; state keeps 82.5 kg as working load |

e1RM along the way: week 1 best set 80 × 8 @ 3 → `r_eff = 11 → 10` → 106.7 kg (Epley = Brzycki at
10 reps). Week 4: 80 × 10 @ 2 → again 106.7 — the cap makes e1RM blind inside a 5–10 @ RIR 2–3
scheme, so the Progress chart draws it as a dashed "low-confidence" line and the plateau detector
uses reps-at-load (§3.10). A strength-phase set 100 × 6 @ RIR 2 → `r_eff = 8` → Epley 126.7,
Brzycki 124.1, mean **125.4 kg**, full confidence.

Autoregulation inside a session (`PROG-04`): week 3, set 1 logged 80 × 7 @ **RIR 0** (target 2, so
≤ target − 2) → sets 2–4 re-prescribed at 0.95 × 80 = 76 → rounded to the nearest loadable
(1.25 kg plates → 2.5 kg steps): **75 kg**; adjustment shown; set 1 stays "80 × 7 @ 0" in the log.
Dumbbell increments: 22 → 24 kg is +9.1 % (≤ 10 %, `PROG-02` ok); 12 → 14 kg is +16.7 % → the
planner extends the rep band by +2 first.

#### E3 — Amber and Red days (`RDY-01/02`)

Readiness inputs → points: sleep 6.5 h → 10; quality 3 → 8; soreness 2 → 12; stress 3 → 8;
energy 3 → 8; motivation 4 → 12; total **58 → Amber**. UPPER_A becomes: bench 4 (unchanged), row 3
(unchanged), then −1 set on every later exercise: DB shoulder press 2, pulldown 2, lateral raise 3,
face pull 1, pec deck 1, curl 1 → 17 sets, all RIR +1 (bench 4), no load increases even if
`PROG-01` is due (`Adjustment(RDY-02, READINESS, …)` on each block). With a resting HR +6 bpm over
the 7-day baseline the same inputs score 50 (−8), still Amber; +6 bpm on two consecutive mornings
would also be Amber by `RDY-03`.

Red: sleep 5.5 h → 4; quality 2 → 4; soreness 3 → 8; stress 4 → 4; energy 2 → 4; motivation 3 → 8;
total **32 → Red**. Choice offered: (a) reduced session — required slots at `ceil(0.6·n)` sets,
optional/isolation slots beyond the first two dropped, RIR +2, heavy barbell lifts replaced by their
moderate machine/dumbbell substitution: `machine_chest_press` 3 × 8–12 @ RIR 5, `seated_cable_row` 2,
`seated_dumbbell_shoulder_press` 2, `lat_pulldown` 2, `dumbbell_lateral_raise` 3 → 12 sets, ≈ 35 min;
or (b) 25–30 min zone-2 (bike, 109–127 bpm, §4.3) + the hip/T-spine drill set. Either way the
readiness record feeds `DLD-03` ("Red ≥ 3 of 7 days").

#### E4 — Friday LOWER_B in a hotel gym (one-off day, 45 minutes)

Today: gym = `hotel_gym` preset (dumbbells max 20 kg step 2, adjustable bench, mat, treadmill, bike,
elliptical), `minutesOverride = 45`. Availability: `trap_bar_deadlift`, `hack_squat`, `hip_thrust`,
`lying_leg_curl`, `seated_calf_raise`, `hanging_leg_raise` are unavailable; `bulgarian_split_squat`
is available (dumbbells + `step_any` via the bench).

Substitution scoring for the `trap_bar_deadlift` slot (desired working load 140 kg; cosine
similarities computed from the R4 muscle vectors):

| Candidate | pattern | cosine | loadFit (desired → achievable) | continuity | difficulty | penalty | **score** |
|---|---|---|---|---|---|---|---|
| `single_leg_romanian_deadlift` | hinge 1.0 → 0.35 | 0.637 → 0.223 | RDL seed 0.75 × 140 = 105, unilateral × 0.5 = 52 vs 2 × 20 = 40 → 0.77 → 0.7 → 0.105 | 0 | 0.05 | — | **0.728** |
| `dumbbell_romanian_deadlift` | 0.35 | 0.752 → 0.263 | 105 vs 40 → 0.38 → 0.1 → 0.015 | 0 | 0.05 | — | 0.678 |
| `glute_bridge` (DB, single-leg) | family 0.6 → 0.21 | 0.797 → 0.279 | hip-thrust seed 0.5 × 120 = 60, single-leg → 40/60 = 0.67 → 0.7 → 0.105 | 0 | 0.05 | — | 0.644 |
| `kettlebell_swing` (dumbbell option) | 0.35 | 0.856 → 0.300 | 0.2 × 140 = 28 vs 20 → 0.71 → 0.7 → 0.105 | 0 | 0.05 | power profile in a hypertrophy compound slot −0.20 | 0.605 |

Winner: `single_leg_romanian_deadlift`. For the `hack_squat` slot: `bulgarian_split_squat` 0.807
(family 0.6 → 0.21; cosine 0.977 → 0.342; seed 0.5 × back-squat-equivalent 104 = 52 vs 40 → 0.77 →
0.7 → 0.105; continuity 1.0 → 0.10; 0.05) beats `goblet_squat` 0.802 (same pattern 0.35; 0.342;
seed 0.35 × 104 = 36 vs 20 → 0.55 → 0.4 → 0.06; 0.05) and `step_up` 0.698 — so it **merges** with
the planned split-squat slot. `hip_thrust` → `glute_bridge` (cosine 0.972, in its ranked list);
`lying_leg_curl` → `slider_leg_curl` (ranked list, towel on the floor, no equipment);
`seated_calf_raise` → `standing_calf_raise` (dumbbell single-leg option on the bench step);
`hanging_leg_raise` → `lying_leg_raise`.

Time budget: full realization = 52.7 + 5 (trimmed warm-up) = **57.7 min** > 45. Concessions:
level 3 supersets (calf + leg raise, non-competing) → 54.8; level 4 rest cut → 52.0; level 6 two
sets each → **33.7**; refill in deficit order: +1 split squat (37.9), +1 single-leg RDL (42.2),
+1 glute bridge (**43.7**), +1 slider curl would be 45.2 → stop. Final session:

| Block | Sets | Reps | Load | RIR | Note |
|---|---|---|---|---|---|
| `single_leg_romanian_deadlift` (sub for trap-bar DL) | 3 | 12–20 | find-load ramp 2×12 → 2×16 → 2×20 kg, work at 2×20 | 1–2 | loadFit 0.7 → top of band (`TRV-01`) |
| `bulgarian_split_squat` (merged) | 3 | 10–14 | **2×20 kg** (working load 2×22 kg exceeds the rack: "dumbbells max 20 kg — prescribed 22 kg", rep band +2, RIR −1) | 1 | R4 §2.6 warning shown, user chose "keep at max" |
| `glute_bridge` single-leg + DB (sub for hip thrust) | 3 | 6–12 (AMRAP−2) | 20 kg | 1 | `bodyweight_progression` |
| `slider_leg_curl` (sub for lying leg curl) | 2 | 8–15 | — | 1 | — |
| `standing_calf_raise` single-leg (sub) ⟂ `lying_leg_raise` (sub) | 2 + 2 | 12–20 / 10–20 | 20 kg / — | 1 | superset, 60 s |

Volume re-accounting: planned LOWER_B credited quads 7.25 / hamstrings 5.75 / glutes 8.5 / calves
3.75 / abs 2.0 / lower_back 1.5; realized: quads 3.0 (**−4.25**), hamstrings 6.5 (+0.75), glutes 7.0
(−1.5), calves 2.0 (−1.75), abs 2.0, glute_med +0.75, lower_back 0 (−1.5). Week totals become
quads 10.0 (target 12, still ≥ `VOL-01`), glutes 12.0, hamstrings 13.5, calves 5.75 (tier-2 target
7). Friday is the last session of the week, so nothing can absorb the carryover; the Plan screen
shows "quads −2, calves −1.25 this week (hotel day)". No `LAY-01` regression next week: the squat
and hinge patterns were trained.

#### E5 — a five-day trip (`TRAVEL_MAINTENANCE`)

CHK Q10 answered "5" for the coming week (or the user enters travel days) → the whole week is
replanned as TRV-02 "dumbbells only (≤ 25 kg)" sessions A/B on Tue and Thu, 35 minutes each, meso
week counter paused at week 3:

| Session A | Sets × reps | Session B | Sets × reps |
|---|---|---|---|
| `bulgarian_split_squat` (replaces goblet squat: loadFit) | 3 × 10–14 @ 2×20 | `split_squat` | 3 × 12–15 per leg |
| `single_leg_romanian_deadlift` | 3 × 12–15 | `dumbbell_romanian_deadlift` at 2×20, 20–30 reps | 3 × 20–30 @ 0–1 RIR (`LOAD-01`) |
| `dumbbell_bench_press` 2×20 (working 2×30 → warning, reps 15–25) | 3 × 15–25 | `seated_dumbbell_shoulder_press` 2×20 | 3 × 10–15 |
| `dumbbell_row` | 3 × 10–14 per side | `chest_supported_dumbbell_row` | 3 × 10–14 |
| `dumbbell_lateral_raise` ⟂ `push_up` AMRAP | 2 × 15–20 / 2 × AMRAP | `incline_dumbbell_curl` ⟂ `dumbbell_overhead_triceps_extension` | 2 × 12 each |

Ledger check against `VOL-01` floors: chest 3 + 2 (push-up) = 5 ≥ 4 ✓, lats/upper_back 6 ✓, quads
6 ✓, hamstrings 6 ✓, glutes 3 + 3 + 1.5 + 1.5 ≥ 4 ✓, side_delts 2 + 1.5 + 1.5 = 5 ✓. The
`TRV-03` reassurance line is shown once. Week 3 resumes on return with `LAY-01`'s 5–13-day row on
barbell lifts only if an individual lift was last performed ≥ 14 days earlier.

#### E6 — chat proposals (`SwapExercise`, `AddCardio`, `ChangeGoal`)

1. "Swap RDL for good mornings." Tool call `propose_swap_exercise{from: romanian_deadlift, to:
   good_morning, scope: rest_of_mesocycle}` → validator: available (rack with safeties ✓), same
   pattern, `advanced` difficulty with `allowAdvancedExercises = false` → **warn**, `SP` flag with
   rack ✓. Card shows the warning; on Apply the LOWER_A hinge slot becomes `good_morning`, state
   `UNKNOWN` → find-load seeded at 0.5 × RDL working load (Appendix C) → 50 kg ramp. Diff returned to
   the coach: `{"sessions_changed":["LOWER_A"],"hamstrings_weekly":"12.75→12.75","warnings":["advanced exercise enabled for this slot"]}`.
2. "Add a cardio day on Wednesday." `propose_add_cardio{weekday: 3, minutes: 30, modality: null,
   intensity: zone2}` → `CAR-06`: Wednesday is ≥ 24 h from Tuesday's LOWER_A and 48 h before
   Friday's LOWER_B ✓; modality auto → `stationary_bike` (`CAR-08`: bike adjacent to leg days);
   HR 109–127 bpm (§4.3); counts toward `CAR-07` hypertrophy prescription (2 × 20–30 min); applied
   as an `AddCardio` override → Wednesday appears on the Plan screen as a cardio day.
3. "Let's do a strength block next." `propose_change_goal{goal: strength, start_next_week: true}`
   → on Monday the skeleton rebuilds: bench/squat/deadlift/OHP each twice per week, heavy day 3–6
   @ RIR 2–3 (bench week-1 load from the state's 82.5 kg at 5–10 reps → the strength band's first
   session is a find-load at 0.90 × 82.5 → 75 kg ramp to 3–6 reps, `CALIBRATING`), moderate day
   6–10 @ 85 %; accessories at the novice band; rest 180–240 s.

#### E7 — plateau ladder on the bench

Weeks 3–5 of a later mesocycle: 82.5 × (8, 8, 7, 7), (8, 8, 8, 7), (8, 8, 7, 7) @ RIR 2 with
readiness ≥ Amber → `noProgressCount = 3` → `PLATEAU`. Week 6 step 1: CHK Q1 answers 6.2 h average
sleep → `SLP-03`: weekly sets −10 %, RIR +1 on heavy compounds until the average is ≥ 7 h, and a coach
message citing Craven 2022. If sleep recovers and reps still stall, step 2 the following week shifts
the bench band to 8–12 (`repZoneShift = 1`) with the load re-seeded from the e1RM table (`E1RM-03`:
10 reps ≈ 75 % → 106.7 × 0.75 = 80 → 80 kg); step 3 proposes `incline_barbell_bench_press` or
`dumbbell_bench_press` (first available in the ranked list) for the rest of the block.

### 3.16 Explainability contract

Every automatic change has exactly one `Adjustment` with a rule ID from R3 §10 (or an R4 section
reference, or a `SEL-*`/`REBAL`/`TIME-*` planner convention), the triggering inputs as strings, and
before/after values. The UI shows them as chips on the affected block ("RDY-02 · Amber · −1 set")
and as a list on the session summary. Logged numbers are never touched (`POL-01`): the planner reads
`SetLog` rows, it never writes them; the coach can only propose; overrides are append-only. Unit
conversion for display never alters stored kg/cm; the `enteredValue`/`enteredUnit` pair preserves
exactly what the user typed when the imperial toggle is on.

---

## 4. Warm-up, mobility, cardio, nutrition, supplements, check-ins

### 4.1 Warm-up generator (`WarmupGenerator`)

Inputs: the realized blocks (patterns, first exercise's load zone), `TodayContext` (cold, minutes
since waking), age, active limitations, session minutes.

| Phase | Rule | Prescription |
|---|---|---|
| Raise | `WU-01` | 3–5 min easy cardio on whatever is available (`stationary_bike` > `rower` > `treadmill` walk > `jump_rope` > bodyweight calisthenics); 8–10 min when `WU-06` (age ≥ 40, ambient < 15 °C, or < 60 min after waking); **0 min** when the session is ≤ 45 min (`WU-05`) |
| Activate + mobilise | `MOB-01`, `MOB-07` | 3–5 dynamic drills × 2 × 8–10 reps chosen from `drills.json` by the session's patterns (squat/hinge → leg swings, deep squat with support, 90/90; push/pull → band pull-aparts, pass-throughs, scapular push-ups; overhead → wall slides, open-book) plus one drill per active limitation joint; 2–3 min under `WU-05` |
| Potentiate | `WU-02` | ramp sets of the first exercise from the day's first working load: heavy compound (≥ 80 % 1RM): 40 % × 8, 55 % × 5, 70 % × 3, 80–85 % × 1–2; moderate compound (8–15 RM): 50 % × 8, 70 % × 4–5; light/machine compound: 60 % × 8; isolation: 1 × 8–10 at 50–60 %; later exercises of an already-warm pattern: 0–1 ramp set at 60–70 %; rest 45–90 s; one extra ramp set under `WU-06` |
| Static stretching | `WU-04` | never ≥ 60 s per muscle before lifting; ≤ 30 s allowed inside the drill set for a limited joint |

Ramp sets are `SetKind.RAMP`, never counted as volume (`WU-03`). Loads are rounded by `LoadRounder`.
Warm-up total is what the time model charges (10 / 5 / 12 min).

### 4.2 Mobility program

- Pre-session: §4.1.
- Chronic ROM program (`MOB-02`): for each joint the user marked as limited (or that a limitation
  implies), 5–10 min/week of 30–60 s holds, 2–4 holds per day, ≥ 5 days/week, scheduled as a
  "mobility" reminder block on rest days and as the cool-down's static holds (`MOB-04`); the program
  tracks weekly minutes per joint and credits 30 s per full-ROM working set of the joint's
  strength-through-ROM exercises (`MOB-03`: deep squats, RDLs, overhead press, pull-ups, split squats).
- Foam rolling: optional, 30–60 s per muscle pre-session only if the user reports it helps them
  reach position (`MOB-05`); shown as an optional line, never counted.
- Cool-down (`MOB-06`): optional 5–10 min easy movement, on by default only after HIIT.

### 4.3 Cardio planner (`CardioPlanner`)

| Goal | Weekly prescription (`CAR-07`) | Placement (`CAR-06`, `ORD-03`) |
|---|---|---|
| GENERAL_FITNESS | 2 × 30–45 min zone 2 + 1 HIIT | zone 2 as finishers after UPPER sessions or on off days; HIIT on an off day ≥ 24 h before a leg day |
| HYPERTROPHY / STRENGTH | 2 × 20–30 min zone 2 (health floor); HIIT optional 1×, not on/before leg day | finishers after UPPER sessions, or off days; cap ≤ 3 sessions / ≤ 150 min beyond the floor unless the user asks |
| FAT_LOSS | 150–250 min/week moderate (> 250 for clinically significant loss); HIIT not superior | spread over off days and finishers; `STP-02` steps |
| ENDURANCE_SUPPORT | user's own plan is primary | lifting ≥ 6 h from key cardio sessions |

Intensity control: zone 2 = two of {talk test positive, 60–70 % HRmax, 40–59 % HRR, RPE 11–13}
(`CAR-02`); HRmax = 208 − 0.7 × age (`CAR-03`, Tanaka) unless a measured max is entered; Karvonen
HRR with the logged resting HR. Example (38 y, rest 58): HRmax 181.4 → zone 2 **109–127 bpm**; HRR
40–59 % → 107–131 bpm; vigorous 77–95 % → 140–172 bpm; 4×4 work bouts at 90–95 % → 163–172 bpm.
HIIT menu (`CAR-05`): Norwegian 4×4 (4 × 4 min @ 90–95 % HRmax, 3 min @ ~70 %), 10 × 1 min @ ~90 %,
SIT 4–6 × 30 s (advanced/healthy only) — all gated by ≥ 4 weeks of zone-2 base in the logs and
`RED-03` clearance. Modality by equipment and leg-day adjacency from `CAR-08` (bike/elliptical/ski
erg next to leg days; running never within 24 h before LOWER sessions; jump rope not with knee
limitations). Progression `CAR-09`: +10 % duration per week up to target; add intervals before
intensity. Session time: a finisher is charged on top of `sessionMinutes` unless the profile says
`cardioInsideSessionMinutes`, in which case the lifting budget shrinks by `cardioMinutes + 3`.

### 4.4 Nutrition engine (`EnergyModel`, `MacroTargets`, `WeightTrend`)

- REE by Mifflin–St Jeor (`NUT-04`): men `10W + 6.25H − 5A + 5`; women `− 161`. Example: 84 kg,
  180 cm, 38 y → **1,780 kcal**.
- TDEE = REE × activity factor (1.2 / 1.375 / 1.55 / 1.725 / 1.9, chosen from steps and sessions;
  **[convention, weak]**, shown as "starting guess"). Example: 1.55 → **2,759 kcal**.
- Goal intake: HYPERTROPHY +10–20 % (`NUT-06`; default +10 % → **3,035 kcal**); FAT_LOSS −300 to
  −500 kcal, rate 0.5–1.0 % BW/week, 0.5–0.7 % when lean (`NUT-05`); recomposition case (`NUT-07`)
  offered when novice, returning from ≥ 3 months layoff, or above ≈ 20 % (men) / 30 % (women) body fat
  with a hypertrophy goal.
- Macros: protein 1.6–2.2 g/kg (`NUT-01`; default 1.8 → **151 g**; deficits 2.3–3.1 g/kg FFM;
  ≥ 65 y floor 1.2), fat 0.5–1.5 g/kg (`NUT-09`; default 1.0 → 84 g), carbohydrate = remainder
  (→ 419 g = 5.0 g/kg, ≥ 3 g/kg floor `NUT-08` ✓), fibre 14 g/1,000 kcal (`NUT-10`), water AI
  3.7/2.7 L (`NUT-11`). Distribution guidance 0.4 g/kg per meal × ≥ 4 (`NUT-02`), meal frequency
  free (`NUT-03`), peri-workout `NUT-12`, alcohol `NUT-13` (≥ 3 drinks in an evening → next day at
  least Amber).
- Weight trend (`NUT-15`): daily morning weigh-in, 7-day simple moving average; single-day change
  ≥ 1 % flagged as noise; ≥ 14 days of data before any adjustment.
- Adaptive TDEE (`NUT-04`, every 14 days): `expected = (intake − TDEE) × 7 / 7700` kg/week;
  `TDEE += (expected − observed) × 7700 / 7`, clamped ±150. Example: intake 3,000, observed +0.6
  kg/week → expected +0.219 → raw correction −419 → clamped **−150 → TDEE 2,609**, shown with the
  formula and the trigger values. Rate-based intake adjustment `NUT-16` (±100–200 kcal when outside
  the goal band); diet breaks `NUT-17`.
- Travel heuristics (`NUT-14`) are coach content: protein anchor 30–40 g/meal, plate rule,
  petrol-station kit, expect +0.5–1.5 kg after high-sodium/weekend days (do not react), ordinal
  logging ("protein hit? y/n", "meals light/normal/heavy") when calorie logging is impossible.

Calorie/macro logging is deliberately **not** a v1 feature; the app tracks weight, protein-target
days (CHK Q3) and the ordinal travel inputs. Sacred content: the user's weigh-ins are stored as
typed; the moving average and TDEE are displayed as derived values with their formula IDs.

### 4.5 Supplements advisor (`SupplementAdvisor`)

Tier cards (R3 §7), each carrying the `SUP-12` caveat block verbatim (not medical advice; third-party
tested products; country legal note; priority order protein → calories → sleep → consistency →
creatine/caffeine). Tier A by default: creatine monohydrate 3–5 g/day (0.1 g/kg → 8.4 g for 84 kg
capped to the 3–5 g band → **5 g**; optional 20 g/day loading; expect +1–2 kg water, flagged in the
weight trend), caffeine 2–3 mg/kg to start (**170–250 mg**) 60 min pre-session with the sleep cut-off
from the profile's bedtime (23:00 → no ≥ 200 mg dose after **10:00**, no ≤ 100 mg dose after 14:00,
`SUP-02`, Gardiner 2023), protein powder as food (`SUP-03`), vitamin D and electrolytes conditional
(`SUP-04`, `SUP-09`). Tier B situational (beta-alanine, citrulline, melatonin for time-zone shifts
with the legal caveat, omega-3, multivitamin). Tier C "do not" list with reasons (`SUP-11`).
`RED-03` medical flags put every card behind a clearance banner.

### 4.6 Readiness, check-ins, red flags

- Daily readiness (`RDY-01`, ≤ 30 s, six sliders + optional resting HR) → score 0–100 → band
  (`RDY-02`: Green ≥ 70, Amber 45–69, Red < 45; sleep < 6 h forces at least Amber; resting HR ≥ +5
  bpm over the 7-day mean on 2 consecutive days → Amber, ≥ +10 or illness → Red/skip, `RDY-03`).
  Localized soreness ≥ 4/5 → that muscle's heavy work is moved 24–48 h within the week (`RDY-04`),
  i.e. the realizer swaps today's template with the next session's when both are still open.
- Weekly check-in (`CHK-01`): ≤ 8 questions rotated from the 15-item bank plus custom coach
  questions; each answer maps to its trigger (Q1/Q2 → `SLP-03`; Q3 → protein coaching; Q4 →
  `NUT-16`; Q5 → `STP-02`; Q6 → `RDY-05` after 2 weeks; Q7 → INJ rules / `RED-02`; Q8 → `TIME-01` /
  split re-plan; Q9 → `NUT-13`; Q10 → `TRV-01`; Q11 → `DLD-03`; Q12 → `VOL-08` pause; Q13 →
  `SUP-02`; Q14 → `PROG-04` audit; Q15 → `NUT-17`). Answers are stored raw; derived triggers are
  `Adjustment`s.
- Weekly training load (`RDY-06`): session RPE × minutes, shown as a trend; > 150 % of the 4-week
  mean is an advisory "spike" note only.
- Red flags (`RED-01`): the readiness screen and the session screen carry a "something feels wrong"
  entry that lists the stop-and-refer symptoms; any selection ends training advice for that
  region/session and shows the hand-off text (emergency services for the first four items). Pain
  > 3/10 persisting > 7–10 days (`RED-02`) → professional referral text; `RED-03` gates.
  `POL-02`: no diagnosis language anywhere, including the coach's system prompt.

### 4.7 Sleep, steps, stress

`SLP-01/02/03` (7-day average < 7 h → weekly sets −10–20 %, RIR +1 on heavy compounds; last night
< 6 h → Amber, prefer morning; two nights < 6 h → no max-strength zone), `STP-01/02` (7,000–8,000
steps < 60 y; fat loss: max(target, baseline + 2,000) ≤ 12,000; steps from manual entry in v1 —
no Health Connect integration until the app compiles), `STR-01/02` (two weeks of stress ≥ 4/5 →
sets −20 %, RIR +1, no new HIIT, zone-2 walks offered). Masters rules `AGE-01/02/03` switch on by
age (≥ 40: `WU-06`, 48 h between heavy same-muscle sessions, shorter deload cadence, upper-half
protein; ≥ 60: the Borde 2015 prescription set).

---

## 5. UX: screens, navigation, key interactions

### 5.1 Screen list

| Route | Screen | Purpose / key elements |
|---|---|---|
| `Onboarding` (5 steps) | Profile → Goal & schedule → Limitations & medical → Gym setup → Known loads | Collects `Profile`, seeds gyms from presets (`base_gym`, `home_gym`, `hotel_gym`, `travel_kit`), optional working loads; ends by showing the first mesocycle summary and the under-target report |
| `Today` (tab 1) | Readiness card (6 sliders + RHR, 30 s) → Gym-of-the-day chip (default gym; tap to switch/create quick set; "+ travel kit") → Minutes chip → Session preview (blocks, estimated minutes, adjustments chips) → **Start** | Also: cardio-day view, rest-day view with mobility block and micro-session cards, Red-day choice dialog |
| `Session` | Active logging: one block at a time; each set row prefilled with the prescription (load, reps band, RIR) but **logged only when the user taps ✓**; rest timer; per-set RIR picker; autoregulation banner (`PROG-04`); substitution sheet (ranked candidates with scores and warnings); "something feels wrong" (RED-01); finish → summary (sets, volume by muscle direct/fractional, adjustments, session RPE) | Sacred: edits after ✓ keep an edit history; nothing is auto-filled as logged |
| `Plan` (tab 2) | Week strip (sessions, cardio days, mobility), mesocycle timeline (week k of N, mode, deload date), per-session detail, weekly ledger card (targets vs achieved per muscle, direct and fractional toggle, carryover), adjustments log, "undo change" on overrides, under-target report with option cards | |
| `ExerciseDetail` | Description, setup, cues, mistakes, safety (flag-aware), muscles (weights), equipment options, substitutions with rationale, sources; history chart (e1RM / reps-at-load), progression state with explanation | Opened from Session, Plan, Library, Coach cards |
| `Library` | Search by name/alias (EN/DE aliases), filter by pattern, muscle, equipment, availability in a chosen gym; difficulty badges | |
| `Gyms` | List of gym sets; editor with categories (R4 §2.1), per-item parameters (dumbbell max/step, plates smallest/microplates, stack step/half step, rack safeties/pull-up bar), "start from preset", duplicate; availability preview ("X of 156 exercises available") | |
| `Progress` (tab 3) | Body-weight chart with 7-day MA (Canvas line chart, R2 §6.2), e1RM per main lift (dashed when low-confidence), weekly fractional/direct volume per muscle, readiness trend, steps, training load (`RDY-06`) | |
| `Checkin` | Weekly questions (≤ 8), custom coach questions, red-flag entry | Reminder via WorkManager |
| `Nutrition` | Targets (kcal, protein, fat, carbs, fibre, water) with formula IDs, adaptive TDEE history, weigh-in entry, protein-day tally, supplement cards (with `SUP-12`), travel heuristics | |
| `Coach` (tab 4) | Streaming chat; proposal cards (Apply / Reject / Edit-then-apply) inline; "new thread" (daily by default); context indicator ("the coach sees: profile, today's gym, week plan, last 14 days"); offline banner | |
| `Settings` (More) | Units; backend selection with the R1 §2.5 policy text verbatim; API key entry (masked, Keystore-backed, "test key" button); home bridge URL + secret (experimental, off); model (`claude-opus-5` default, `claude-sonnet-5`), effort (low/medium); usage this month (tokens, est. USD from R1 §2.3 prices); rule constants editor (Appendix A, weak-tagged values only, with reset); export/import JSON; about/citations | |

### 5.2 Navigation

Bottom bar: **Today · Plan · Progress · Coach · More**. `More` opens Library, Gyms, Check-in,
Nutrition, Profile, Settings. Routes are `@Serializable` classes in `ui/nav/Routes.kt`
(Navigation 2.10.0 type-safe API): `Today`, `Session(sessionId)`, `Plan`, `SessionDetail(plannedId)`,
`Exercise(id)`, `Library`, `Gyms`, `GymEdit(gymId?)`, `Progress`, `Checkin`, `Nutrition`, `Coach`,
`Settings`, `Onboarding(step)`. Deep links: a check-in reminder opens `Checkin`; a session reminder
opens `Today`.

### 5.3 Key interactions

1. **Gym of the day.** Today → gym chip → picker (gyms, "+ Quick set", "+ Travel kit merge",
   "Spotter today" toggle, "Something's out of order" to remove an item for today). Changing it
   re-realizes the session in place; every substitution appears as a chip with "why" (score table
   on tap) and a one-tap alternative.
2. **Logging a set.** Prefilled row (load label like "2×22 kg", reps band, RIR). The user edits
   weight/reps/RIR and taps ✓. Only then does a `SetLog` row exist. If set 1's RIR triggers
   `PROG-04`, a banner proposes the reduced load for the next sets with "apply / keep"; either way
   the logged row is what the user typed.
3. **Substitution sheet.** Ranked candidates with availability, muscle overlap, load fit, and the
   ledger effect ("quads this week 12 → 10"). Choosing one records `substitutedFor` on the block.
4. **Proposal card.** Title (mutation kind), the coach's rationale, validator warnings, the planned
   diff preview ("Wednesday: +30 min bike, zone 2 109–127 bpm"), Apply/Reject/Edit. Cards remain
   actionable until the thread ends; the coach receives the outcome on the next turn.
5. **Adjustments log.** Every chip opens the rule text with confidence tag and citation, e.g.
   "RDY-02 (moderate/weak) — Craven 2022, Knowles 2018" and, for weak constants, a link into the
   Settings rule editor.
6. **Units.** Imperial toggle converts display only; entry fields accept the display unit and store
   both the entered value and the kg/cm canonical value.
7. **Red-day and red-flag flows** as in §4.6; the red-flag flow ends with the hand-off text and no
   further training advice for that region until the user clears it in Profile.

### 5.4 Compose/Material 3 notes

Material 3 1.4.0 components only (Scaffold, NavigationBar, Card, ListItem, Slider, AssistChip,
FilterChip, ModalBottomSheet, AlertDialog, TextField). Icons from `material-icons-extended` 1.7.8
(R2 §6.3). Charts are hand-drawn `Canvas` composables fed by a `ChartData` model computed in
`:core` (R2 §6.2). ViewModels use `StateFlow`; screens collect with `collectAsStateWithLifecycle`.
No third-party UI libraries.

---

## 6. Claude coach integration

### 6.1 Division of labour

The **planner decides**; the **coach explains, asks and proposes**. The coach never computes a
plan, never writes a log, and never edits an override; it reads a compact snapshot and calls tools.
This keeps the science in unit-tested code with rule IDs and keeps the model's job to language:
answering "why", asking the check-in questions conversationally, turning a request into a validated
`PlanMutation`, and explaining an exercise. It also keeps the chat entirely optional — everything
else works offline (REQUIREMENTS).

### 6.2 System prompt (byte-stable, cached; ≈ 3,000–3,500 tokens)

Sections, in order (no timestamps, no user data — those go in the user turn, R1 §3.10):

1. Identity and scope: an evidence-based strength & conditioning coach inside the GymCoach app;
   English; metric unless the context says imperial; concise (the user is a mechanical engineer who
   wants numbers and reasons).
2. Hard policies: `POL-01` (never claim to have changed anything; only tools change things and only
   after the user confirms), `POL-02` (no diagnosis; `RED-01` symptoms → stop and refer; `RED-02`
   persistent pain → professional), `SUP-12` caveat whenever a supplement is named, `RED-03` gates.
3. How the app works: the planner, the rule IDs, what the context block contains, what "direct" vs
   "fractional" volume means (R4 §4.2), what an `Adjustment` is.
4. Rule digest: one line per key rule with its ID and confidence (VOL-01/04/06/08, FRQ-01/02,
   LOAD-01/02/03, RIR-01/03/04/05, REST-01/02/03, ORD-01/03, MIX-01/02, PROG-01/02/03/04,
   PLAT-01/02/03, DLD-01/02/03/04, E1RM-02/03, RDY-02/03/05, WU-01/02/04, CAR-02/05/06/07,
   NUT-01/04/05/06/15/16, SUP-01/02, SLP-03, LAY-01, TRV-01, TIME-01, INJ-01/02/03) so the coach cites
   the same IDs the UI shows; a citation list (author-year) for the strong/moderate ones.
5. Tool protocol: which tool for which request; one tool call per distinct change; never invent an
   exercise ID — use `lookup_exercises`; put rationale in the tool input; after a tool result,
   explain what was applied or why it was rejected; if a proposal is deferred, do not nag.
6. Exercise ID vocabulary: the 156 R4 IDs with names (≈ 700 tokens) so swaps use real IDs.
7. Style: short paragraphs, numbers with units, no motivational filler, no emoji.

### 6.3 Context assembly (`CoachContextBuilder`, ≈ 900–1,400 tokens per turn)

The latest user message carries two text blocks: the context JSON, then the user's text. Older
turns are left byte-identical (their old context blocks included) so the cached prefix holds
(R1 §3.10). Context content (compact JSON, stable key order, numbers rounded):

```json
{
  "today": "2026-09-05", "units": "metric",
  "profile": {"sex":"male","age":38,"weight_kg":84.0,"height_cm":180,"training_age":"intermediate","goal":"hypertrophy",
              "days":4,"minutes":60,"limitations":[],"medical_gate":false,"priority_muscles":[]},
  "gym_today": {"id":"hotel_1","name":"Hotel gym","dumbbells_max_kg":20,"has_rack":false,"cables":false,"machines":[],"cardio":["treadmill","stationary_bike","elliptical"]},
  "mesocycle": {"week":3,"of":6,"mode":"normal","rir_offset":0,"deload_week":7,"split":"upper_lower_4"},
  "week": {"sessions":[{"day":"Mon","template":"UPPER_A","status":"completed","sets":23},{"day":"Tue","template":"LOWER_A","status":"completed","sets":18},
           {"day":"Thu","template":"UPPER_B","status":"planned","sets":22},{"day":"Fri","template":"LOWER_B","status":"planned_today","sets":19,"gym":"hotel_1"}],
           "cardio":[{"day":"Wed","modality":"stationary_bike","minutes":30,"intensity":"zone2"}]},
  "ledger": {"targets":{"chest":14,"quads":14,"...":0},"achieved":{"chest":8,"quads":7.25},"planned_remaining":{"chest":6,"quads":3},
             "under_target_report":["abs -3"]},
  "today_session": {"template":"LOWER_B","blocks":[{"ex":"single_leg_romanian_deadlift","sub_for":"trap_bar_deadlift","sets":3,"reps":"12-20","load":"2x20 kg","rir":1}, "..."],
                    "adjustments":["TRV-04: trap_bar_deadlift→single_leg_romanian_deadlift (score 0.73)","TIME-01: level 6 + refill, 43.7 min"]},
  "readiness": {"today":58,"band":"amber","last7":[80,74,58,66,71,69,58]},
  "progression_flags": [{"ex":"barbell_bench_press","status":"plateau","step":1,"working_kg":82.5,"best_e1rm_14d":106.7}],
  "recent": [{"date":"2026-09-01","template":"UPPER_A","top_sets":[{"ex":"barbell_bench_press","kg":82.5,"reps":8,"rir":2}]}, "..."],
  "checkin": {"open_questions":["Q1","Q3","Q10"],"last_answers":{"Q1":"6.2","Q6":"3"}},
  "nutrition": {"tdee_est":2609,"target_kcal":2870,"protein_g":151,"weight_7d_avg":84.4,"trend_kg_per_week":0.35},
  "proposals": {"pending":[],"applied_since_last_turn":["add_cardio Wed 30 min zone2"]}
}
```

Token budget rules: last 14 days of sessions with top sets only; ledger only for tier-1/2 muscles;
`recent` capped at 8 sessions; exercise content is never inlined (the coach uses `lookup_exercises`).

### 6.4 Tool contract

All tools are `strict: true` with `additionalProperties: false` and full `required` lists
(R1 §3.5). Read-only tools execute immediately on-device; proposal tools produce cards.

| Tool | Kind | Input (all required unless `?`) | Result content |
|---|---|---|---|
| `lookup_exercises` | read-only | `query: string`, `pattern?: string`, `available_only: boolean` | ≤ 10 matches: id, name, pattern, muscles, availability today, substitutions, flags |
| `get_exercise_history` | read-only | `exercise_id: string`, `days: integer` | sessions with best set, e1RM (with confidence), progression state |
| `get_rule` | read-only | `rule_id: string` | rule statement, confidence, citation (from `rules.json` + R3 digest) |
| `propose_swap_exercise` | proposal | `from_exercise_id`, `to_exercise_id`, `scope` (`today\|rest_of_mesocycle\|permanent`), `rationale` | `{"status":"applied","diff":{…}}` / `rejected` / `invalid` |
| `propose_change_volume` | proposal | `muscle`, `delta_sets_per_week: integer`, `scope`, `rationale` | same |
| `propose_add_cardio` | proposal | `weekday: integer`, `minutes: integer`, `modality: string` (`"auto"` allowed), `intensity` (`zone2\|hiit_4x4\|hiit_10x1\|sit`), `rationale` | same |
| `propose_change_goal` | proposal | `goal`, `start_next_week: boolean`, `rationale` | same |
| `propose_change_schedule` | proposal | `days_per_week: integer` (0 = unchanged), `session_minutes: integer` (0 = unchanged), `rationale` | same |
| `propose_checkin_question` | proposal | `question`, `schedule` (`daily\|weekly\|once`), `on_date: string` ("" if none), `rationale` | same |
| `propose_exclude_exercise` | proposal | `exercise_id`, `until: string` ("" = indefinitely), `reason` | same |
| `propose_adjust_working_load` | proposal | `exercise_id`, `new_working_load_kg: number`, `rationale` | same |
| `propose_deload` | proposal | `start_this_week: boolean`, `rationale` | same |
| `propose_travel_days` | proposal | `from: string`, `to: string`, `rationale` | same |

Strict-mode note: optional fields are expressed as required fields with sentinel values ("" / 0 /
"auto") so that every schema has a complete `required` list without `oneOf`; the validator treats
the sentinels as "unset". Example definition (the JSON that goes in `tools[]`):

```json
{
  "name": "propose_swap_exercise",
  "description": "Propose replacing one exercise in the user's plan with another. The app validates the proposal against equipment, injuries and the rule book, shows the user a card with the resulting plan change, and applies it only after the user confirms. Use real exercise ids from the vocabulary or from lookup_exercises. One call per swap.",
  "strict": true,
  "input_schema": {
    "type": "object",
    "properties": {
      "from_exercise_id": {"type": "string", "description": "Exercise id currently in the plan."},
      "to_exercise_id": {"type": "string", "description": "Replacement exercise id."},
      "scope": {"type": "string", "enum": ["today", "rest_of_mesocycle", "permanent"]},
      "rationale": {"type": "string", "description": "One or two sentences for the user, citing a rule id where relevant."}
    },
    "required": ["from_exercise_id", "to_exercise_id", "scope", "rationale"],
    "additionalProperties": false
  }
}
```

```json
{
  "name": "propose_change_volume",
  "description": "Propose changing the weekly working-set target for one muscle. Positive delta adds sets, negative removes. The app checks VOL-01 (floor 4), VOL-04 (evidence band) and VOL-06 (per-session cap) and shows warnings on the card.",
  "strict": true,
  "input_schema": {
    "type": "object",
    "properties": {
      "muscle": {"type": "string", "enum": ["chest","front_delts","side_delts","rear_delts","rotator_cuff","lats","upper_back","traps_upper","lower_back","biceps","triceps","forearms","abs","obliques","hip_flexors","quads","hamstrings","glutes","glute_med","adductors","calves"]},
      "delta_sets_per_week": {"type": "integer", "minimum": -6, "maximum": 6},
      "scope": {"type": "string", "enum": ["rest_of_mesocycle", "permanent"]},
      "rationale": {"type": "string"}
    },
    "required": ["muscle", "delta_sets_per_week", "scope", "rationale"],
    "additionalProperties": false
  }
}
```

Tool results (all in **one** user message, `tool_result` blocks first, then any user text, R1 §3.5):
`{"status":"applied","diff":{"sessions_changed":[…],"weekly_sets_before":{…},"weekly_sets_after":{…},"warnings":[…]}}`;
`{"status":"rejected","reason":"…"}`; `{"status":"deferred","note":"card still open in the app"}`
(sent when the user types before deciding); invalid input → `is_error: true` with
`{"status":"invalid","errors":["unknown exercise id 'db_rdl'; use lookup_exercises"]}` so the model
can correct itself in the same turn without user involvement.

### 6.5 Turn loop (`ChatController`)

```
send(userText):
  messages += user[context JSON, userText]                          (first message of a thread must be user)
  stream request (system cached, tools cached, top-level cache_control)
  render text_delta live; accumulate blocks per index; parse tool_use input JSON on content_block_stop
  on message_stop:
    store the assistant message with its full content (thinking blocks with signatures included, R1 §3.5)
    if stop_reason == tool_use:
        for each tool_use: read-only → execute now; proposal → validate → invalid ? error result : card(pending)
        if no card is pending → send follow-up request immediately with all tool_results
        else wait; when every card is decided (or the user types) → next request carries all tool_results first
    if stop_reason == max_tokens → show "truncated", offer "continue" (append the assistant content unchanged + user "continue")
    if stop_reason == refusal → discard partial output, show the stop_details category, offer retry on claude-sonnet-5
    if stop_reason == model_context_window_exceeded → force a new thread with the applied-proposals summary
```

Threads: one thread per day by default (or "new thread"); history is never truncated inside a
thread (keeps the cached prefix stable); hard cap 40 turns → forced new thread whose first context
block includes `applied_since_last_thread`. Effort is fixed per thread (changing it invalidates the
cache, R1 §3.10). Thinking blocks are stored and echoed unchanged; partial streams that end in an
`IOException` before `message_stop` are shown as "interrupted" and **not** stored (they cannot be
echoed safely).

### 6.6 Backends

`CoachBackend` (in `:app`, because it needs OkHttp):

```kotlin
interface CoachBackend {
    /** Emits Messages-API stream events (the `data:` JSON of each SSE event), backend-agnostic. */
    fun stream(request: CoachRequest): Flow<StreamEvent>
    suspend fun testConnection(): BackendStatus
}
```

- **`AnthropicDirectBackend` (default).** `POST https://api.anthropic.com/v1/messages` (R1 §3.4)
  with headers `x-api-key`, `anthropic-version: 2023-06-01`,
  `content-type: application/json`, `accept: text/event-stream`; body per R1 §3.5 (`model`,
  `max_tokens 2048`, `stream true`, `system` array with `cache_control`, `tools` (strict),
  `tool_choice {type: auto}`, `thinking {type: adaptive}`, `output_config {effort: low}`,
  top-level `cache_control {type: ephemeral}`, `messages`). Never `temperature/top_p/top_k`, never a
  trailing assistant message. OkHttp 5.5.0 client with call timeout 300 s, read timeout 120 s (ping
  cadence is undocumented, R1 §4.4), no body-level logging interceptor. SSE parsing by `:core`
  `SseParser` (blank line terminates an event; `data:` lines joined with `\n`; dispatch on the JSON
  `type`; ignore `ping`/unknown; `error` → abort). Retry per R1 §3.9: connection errors, 408, 409,
  429 with `retry-after`, 5xx/529 — 2 retries, exponential backoff; never 400/401/402/403/404/413 or
  the spend-cap 429 (`error.details.error_code == "enforced_spend_limit_reached"`). `request-id`
  logged for every call. Usage from `message_start` + final `message_delta` feeds `UsageMeter`
  (USD estimate with R1 §2.3 prices: Opus 5 $5/$25 per MTok, cache read $0.50, 5-min write $6.25;
  Sonnet 5 $2/$10, $0.20, $2.50).
- **`HomeBridgeBackend` (experimental, off by default, not in the first release).** Phone talks
  only to `https://<tailscale-host>:<port>/v1/chat` with a random bearer secret; the bridge runs the
  unmodified `claude -p` under the user's own login (`--system-prompt`, `--output-format stream-json
  --verbose --include-partial-messages`, `--permission-mode dontAsk`, empty `--allowedTools`,
  `--resume <session_id>`; never `--bare`; R1 §2.2). `BridgeAdapter` (in `:core`) maps
  `{"type":"stream_event","event":{…}}` lines onto the same `StreamEvent`s and synthesizes a
  `tool_use` block from `structured_output` when the bridge is run with `--json-schema` whose schema
  is the union of the proposal tools; confirmation results go back as text in the next prompt.
  Failure modes surfaced verbatim in the UI: PC offline, Tailscale down, login expired, usage limit
  hit, and policy enforcement ("this path may stop working without notice; it did in April 2026").
- **Never**: a claude.ai / Max OAuth token (`/login` credential, `claude setup-token`,
  `CLAUDE_CODE_OAUTH_TOKEN`) on the phone — prohibited by the Claude Code legal page
  ("developers may not collect, store, or intermediate Claude.ai credentials or session tokens",
  https://code.claude.com/docs/en/legal-and-compliance) and by the Consumer Terms §3 (automated
  access only "via an Anthropic API Key", https://www.anthropic.com/legal/consumer-terms), R1 §1.10.
  There is no field for it and the settings screen says so.

Settings copy uses R1 §2.5 verbatim (API key recommended; home bridge experimental with the quoted
policy sentences; the "never" line). The cost line shows "≈ $20–45/month on Opus 5, ≈ $8–17 on
Sonnet 5 at 30 turns/day, in addition to your Max subscription" (R1 §2.4).

### 6.7 Offline and error behaviour

No connectivity → Coach tab shows an offline banner; everything else works. 401 → "key rejected or
expired — paste a new key" (Keystore blob replaced; decrypt failure = "no key stored", R2 §6.1).
402 → "top up credits". 429 spend cap → "your Console spend limit is reached" (no retry). 529 /
mid-stream `error` event → retry with backoff, then "Anthropic is overloaded, try later". Refusal →
category shown, retry on Sonnet offered (fitness content is far from the refusal categories,
R1 §3.8).

### 6.8 Safety behaviours

The coach's system prompt forbids diagnosis and instructs the `RED-01` hand-off; the app additionally
scans the user's message for the red-flag vocabulary and shows the hand-off card *before* sending
anything (on-device, no model needed). Supplement answers are post-checked: if the assistant text
names a supplement and does not include the caveat, the UI appends the `SUP-12` block beneath the
message.

---

## 7. Persistence

### 7.1 Room (`androidx.room:room-runtime:2.8.4`, KSP compiler, schema export to `app/schemas/`)

Complex payloads are stored as JSON `TEXT` columns produced by `:core`'s `Json` (they are plan
snapshots and sealed-class mutations that no query needs to decompose); queryable fields are real
columns with indices.

```kotlin
// app/src/main/kotlin/com/gymcoach/app/data/db/Entities.kt
package com.gymcoach.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = 1,                       // single row
    @ColumnInfo(name = "json") val json: String,        // core Profile
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long
)

@Entity(tableName = "body_metrics", indices = [Index(value = ["date"], unique = true)])
data class BodyMetricEntity(
    @PrimaryKey val date: String,                       // ISO yyyy-MM-dd
    @ColumnInfo(name = "weight_kg") val weightKg: Double?,
    @ColumnInfo(name = "entered_value") val enteredValue: Double?,
    @ColumnInfo(name = "entered_unit") val enteredUnit: String?,
    @ColumnInfo(name = "waist_cm") val waistCm: Double?,
    @ColumnInfo(name = "resting_hr") val restingHr: Int?,
    val steps: Int?,
    @ColumnInfo(name = "sleep_hours") val sleepHours: Double?
)

@Entity(tableName = "gyms")
data class GymEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "preset_origin") val presetOrigin: String?,
    @ColumnInfo(name = "items_json") val itemsJson: String,   // List<GymItem>
    val notes: String?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

@Entity(tableName = "sessions", indices = [Index(value = ["date"]), Index(value = ["status"])])
data class SessionEntity(
    @PrimaryKey val id: String,
    val date: String,
    @ColumnInfo(name = "gym_id") val gymId: String,
    @ColumnInfo(name = "planned_session_id") val plannedSessionId: String?,
    @ColumnInfo(name = "plan_json") val planJson: String?,     // SessionPlan frozen at start
    val status: String,                                          // planned | in_progress | completed | skipped
    @ColumnInfo(name = "session_rpe") val sessionRpe: Int?,
    @ColumnInfo(name = "duration_minutes") val durationMinutes: Int?,
    @ColumnInfo(name = "readiness_json") val readinessJson: String?
)

@Entity(
    tableName = "set_logs",
    indices = [Index(value = ["session_id"]), Index(value = ["exercise_id", "logged_at_epoch_ms"])]
)
data class SetLogEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "block_id") val blockId: String?,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "set_index") val setIndex: Int,
    val kind: String,
    @ColumnInfo(name = "load_kg") val loadKg: Double?,
    @ColumnInfo(name = "entered_load") val enteredLoad: Double?,
    @ColumnInfo(name = "entered_unit") val enteredUnit: String?,
    val reps: Int?,
    val seconds: Int?,
    val metres: Int?,
    val rir: Int?,
    @ColumnInfo(name = "prescribed_load_kg") val prescribedLoadKg: Double?,
    @ColumnInfo(name = "prescribed_reps_lo") val prescribedRepsLo: Int?,
    @ColumnInfo(name = "prescribed_reps_hi") val prescribedRepsHi: Int?,
    @ColumnInfo(name = "prescribed_rir") val prescribedRir: Int?,
    @ColumnInfo(name = "logged_at_epoch_ms") val loggedAtEpochMs: Long,
    @ColumnInfo(name = "edited_from_id") val editedFromId: String?,   // edit history: a corrected row points to the original; the original is kept
    val note: String?
)

@Entity(tableName = "progression_states")
data class ProgressionStateEntity(
    @PrimaryKey val key: String,                       // ProgressionKey.id
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "gym_scope") val gymScope: String?,
    val status: String,
    val json: String,                                   // core ProgressionState
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long
)

@Entity(tableName = "overrides", indices = [Index(value = ["created_at_epoch_ms"])])
data class OverrideEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    val source: String,
    @ColumnInfo(name = "proposal_id") val proposalId: String?,
    @ColumnInfo(name = "mutation_type") val mutationType: String,     // PlanMutation discriminator
    @ColumnInfo(name = "mutation_json") val mutationJson: String,
    @ColumnInfo(name = "revoked_at_epoch_ms") val revokedAtEpochMs: Long?,
    @ColumnInfo(name = "expires_on") val expiresOn: String?
)

@Entity(tableName = "plan_snapshots")
data class PlanSnapshotEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "inputs_hash") val inputsHash: String,
    @ColumnInfo(name = "mesocycle_json") val mesocycleJson: String,
    @ColumnInfo(name = "computed_at_epoch_ms") val computedAtEpochMs: Long,
    @ColumnInfo(name = "is_current") val isCurrent: Boolean
)

@Entity(tableName = "checkins", indices = [Index(value = ["week_start"])])
data class CheckinEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "week_start") val weekStart: String,
    @ColumnInfo(name = "answers_json") val answersJson: String,
    @ColumnInfo(name = "answered_at_epoch_ms") val answeredAtEpochMs: Long
)

@Entity(tableName = "readiness", indices = [Index(value = ["date"], unique = true)])
data class ReadinessEntity(
    @PrimaryKey val date: String,
    val json: String,                                   // core ReadinessCheckin
    val score: Int,
    val band: String
)

@Entity(tableName = "chat_threads")
data class ChatThreadEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "started_at_epoch_ms") val startedAtEpochMs: Long,
    val model: String,
    val effort: String,
    val backend: String,
    @ColumnInfo(name = "turn_count") val turnCount: Int
)

@Entity(tableName = "chat_messages", indices = [Index(value = ["thread_id", "seq"], unique = true)])
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "thread_id") val threadId: String,
    val seq: Int,
    val role: String,                                   // user | assistant
    @ColumnInfo(name = "content_json") val contentJson: String,   // full content-block array, verbatim
    @ColumnInfo(name = "stop_reason") val stopReason: String?,
    @ColumnInfo(name = "usage_json") val usageJson: String?,
    @ColumnInfo(name = "request_id") val requestId: String?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

@Entity(tableName = "proposals", indices = [Index(value = ["thread_id"]), Index(value = ["status"])])
data class ProposalEntity(
    @PrimaryKey val id: String,                          // tool_use id
    @ColumnInfo(name = "thread_id") val threadId: String,
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "tool_name") val toolName: String,
    @ColumnInfo(name = "mutation_json") val mutationJson: String?,   // null when invalid
    @ColumnInfo(name = "validation_json") val validationJson: String,
    val status: String,                                  // pending | applied | rejected | deferred | invalid
    @ColumnInfo(name = "override_id") val overrideId: String?,
    @ColumnInfo(name = "decided_at_epoch_ms") val decidedAtEpochMs: Long?
)
```

DAOs (`suspend` functions and `Flow` queries, all in `room-runtime` since 2.8): `ProfileDao`,
`BodyMetricDao`, `GymDao`, `SessionDao` (with `@Transaction` session + sets), `SetLogDao`,
`ProgressionStateDao`, `OverrideDao`, `PlanSnapshotDao`, `CheckinDao`, `ReadinessDao`, `ChatDao`,
`ProposalDao`. Database `GymCoachDatabase` version 1, `exportSchema = true`; migrations start from
version 2 with `room-testing` `MigrationTestHelper` once the project compiles. Deleting a logged
set is a soft operation (`edited_from_id` chain + a `deleted` marker row) so history is never lost.

### 7.2 DataStore Preferences (`androidx.datastore:datastore-preferences:1.2.1`)

Keys: `units`, `backend` (`api_key` | `bridge` | `none`), `model_id` (`claude-opus-5`), `effort`
(`low`), `bridge_url`, `bridge_secret_ciphertext`, `api_key_ciphertext`, `api_key_iv`,
`onboarding_done`, `default_gym_id`, `checkin_weekday`, `checkin_hour`, `readiness_reminder_minutes`,
`rule_overrides_json` (user-adjusted weak constants, key → value), `last_week_rollover`,
`usage_month`, `usage_input_tokens`, `usage_cache_read_tokens`, `usage_cache_write_tokens`,
`usage_output_tokens`. The DataStore file is excluded from Auto Backup and device transfer
(`android:dataExtractionRules`), because the ciphertext is useless without the device-bound key
(R2 §6.1).

### 7.3 Keystore wrapper (`KeystoreCrypto`, `:app`)

Exactly R2 §6.1: `KeyGenerator` AES-256 under `AndroidKeyStore` with `BLOCK_MODE_GCM` +
`ENCRYPTION_PADDING_NONE`, `Cipher "AES/GCM/NoPadding"`, Keystore-generated 12-byte IV stored
alongside the ciphertext, `GCMParameterSpec(128, iv)` on decrypt, no user-authentication requirement,
no StrongBox. Any decrypt failure (`AEADBadTagException`, `KeyPermanentlyInvalidatedException`,
missing alias) = "no key stored" → the settings screen asks again; never a silent retry with a new key.

### 7.4 Assets

`app/src/main/assets/`: `exercises.json` (156 exercises + 11 rep profiles, R4 §5–6),
`equipment.json` (77 items, 19 groups/bundles, 4 presets, R4 §2), `rules.json` (Appendix A),
`templates.json` (Appendix B), `drills.json` (`MOB-07`), `citations.json` (R3 §11 list keyed by
author-year). Loaded once at start by `AssetSourceImpl` into `:core` indexes; the `:core` test suite
validates the same files from `core/src/test/resources/` (a Gradle copy task keeps them identical;
until then the files live in `:core` test resources and are copied into `:app` assets by hand).

### 7.5 Plan snapshots, recompute, export

`PlanRepository.recompute()` builds `PlanInputs` from Room/DataStore, hashes them, and if the hash
differs from the current snapshot's, runs `Planner.plan()` and stores a new `PlanSnapshotEntity`
(`is_current = true`, previous set to false — kept for the adjustments log). Today's realization is
computed on demand and frozen into `SessionEntity.plan_json` when the session starts. Export = one
JSON file with every table's rows (verbatim) plus the current snapshot; import replaces tables after
a schema-version check. This is also the migration path to Room 3 later (R2 §6.4).

---

## 8. Testing

### 8.1 `:core` (JUnit 4 + kotlin-test-junit 2.4.10 + kotlinx-coroutines-test 1.11.0; R2 §5.4)

| Area | Test class | What it pins down |
|---|---|---|
| Assets | `ExerciseLibraryValidationTest`, `EquipmentCatalogValidationTest` | R4 §6.3 items 1–8 verbatim: unique IDs, substitutions/profiles/equipment refs resolve, every exercise available in `base_gym` or tagged `optional_equipment`, `hotel_gym` ≥ 40 available, `travel_kit` + nothing ≥ 25, weights ∈ {1, 0.5, 0.25}, 1–2 prime movers, `SP` ⇒ rack/smith option, `compound` ⇒ ≥ 2 muscles, decode→encode→decode round trip, batch coverage = 156 |
| Rules | `RuleBookTest` | every rule ID referenced in code exists in `rules.json`; every constant has a confidence and citation; weak constants are `userAdjustable` |
| Equipment | `AvailabilityResolverTest`, `LoadRounderTest` | requirement algebra (any-of/all-of/bundles/session flag; `squat_rack` does not satisfy `rack_or_spotter`; `power_rack.has_pullup_bar=false` breaks `pullup_bar_any`); rounding to plates/dumbbell steps/stack steps; assist direction; > 5 % rounding produces an `Adjustment` |
| Volume | `VolumeAccountantTest`, `WeeklyLedgerTest` | the E1 ledger table exactly (82 sets → chest 12.0, side_delts 11.0, abs 4.0 …); carryover placement respects `VOL-06`, per-exercise caps, budget; no cross-week carryover |
| Planner | `SplitSelectorTest`, `ExerciseSelectorTest`, `SetAllocatorTest`, `TimeModelTest` | SPLIT-01 mapping and SPLIT-02 reordering; pinned/excluded/difficulty gating; allocation never exceeds minutes (E1: 60.0/59.5/60.0/59.5), REBAL moves, under-target report; time costs incl. supersets and unilateral |
| Realizer | `SessionRealizerTest`, `TimeConcessionsTest`, `ReadinessModifiersTest`, `LayoffPolicyTest`, `WarmupGeneratorTest` | E3 Amber/Red outputs; E4 concession ladder (57.7 → 54.8 → 52.0 → 33.7 → 43.7); LAY-01 rows by days; WU-02 ramp percentages and WU-05/06 switches |
| Substitution | `SubstitutionEngineTest`, `LoadTransferTest` | E4 score table (SL RDL 0.728 > DB RDL 0.678 > glute bridge 0.644 > swing 0.605); merge rule; ledger delta |
| Progression | `ProgressionEngineTest`, `E1rmTest`, `PlateauDetectorTest`, `DeloadSchedulerTest` | E2 sequence (increment at week 4, hold otherwise), cadence gating by training age, PROG-04 both clauses, PROG-02 > 10 % rule; e1RM values (106.7 / 125.4 / 103.5) and low-confidence flag; PLAT-01 counts only non-Red sessions; DLD-03 trigger arithmetic; DLD-02/04 prescriptions |
| Readiness | `ReadinessScorerTest`, `CheckinTriggersTest`, `RedFlagsTest` | scores 80 / 58 / 32 / 50 (with RHR); band edges; SLP-03 forcing; every CHK question maps to its trigger; red-flag vocabulary |
| Cardio | `CardioPlannerTest`, `HeartRateZonesTest` | 181.4 HRmax, 109–127 zone 2, 163–172 4×4; CAR-06 placement/modality; HIIT gates |
| Nutrition | `EnergyModelTest`, `WeightTrendTest`, `SupplementAdvisorTest` | 1,780 / 2,759 / 3,035 kcal; macros 151/84/419; adaptive clamp −150 → 2,609; noise rule; caffeine cut-offs 10:00/14:00; creatine 5 g; SUP-12 always attached |
| Mutation | `MutationValidatorTest`, `PlanDiffTest` | every row of the §3.14 table (accept / warn / reject); diff content |
| Coach | `SseParserTest`, `MessageAccumulatorTest`, `BridgeAdapterTest`, `CoachContextBuilderTest`, `CoachToolsTest` | R1 §3.6 verbatim event fixtures (text, tool_use with partial JSON incl. empty first delta, thinking with omitted display, mid-stream error, ping, unknown type); accumulator output equals R1 §3.7 shape; bridge line mapping; context ≤ 1,400 tokens by a char-count proxy and stable key order; every tool schema has `additionalProperties:false` + full `required` + only allowed enum values |
| Golden scenarios | `GoldenScenariosTest` | JSON fixtures: novice 3-day/45 min home gym; intermediate 4-day base gym (E1); advanced 6-day PPL strength; fat-loss 5-day; hotel one-off (E4); travel week (E5); shoulder limitation; masters 62 y. Assertions are invariants, not exact plans: budgets, FRQ-01, VOL-01 floors, VOL-06, RIR floors on SP lifts, no excluded exercise, every adjustment has a rule ID present in `rules.json` |
| Determinism | `DeterminismTest` | same inputs → identical `inputsHash` and byte-identical serialized plan, twice |

Target: ≥ 300 tests, all runnable with `gradlew :core:test` on JDK 17 (R2 §7.3), no Android SDK.

### 8.2 `:app` (JVM unit tests only until the project compiles in Android Studio)

`EntityMappingTest` (Room entity ↔ core model round trips), `KeystoreCryptoContractTest`
(interface-level, fake cipher), `AnthropicRequestBuilderTest` (request JSON equals R1 §3.5 shape,
no forbidden fields, cache_control placement, tool_result ordering), `RetryPolicyTest` (status →
retry decision table from R1 §3.9), `UsageMeterTest` (price arithmetic). Instrumented tests
(`room-testing` migrations, Compose UI tests) are listed in the work breakdown as post-compile tasks.

### 8.3 CI order

1. `gradlew :core:test` (must be green before any `:app` work is merged).
2. `gradlew :app:assembleDebug` — first run by the user in Android Studio (R2 §7.3), then in CI.
3. Golden fixtures regenerate only through an explicit `-PupdateGolden` task with a human diff review.

---

## 9. Work breakdown for parallel implementation (file ownership)

Contracts first: workstream **W0** lands the `model` package and the asset schemas (§2), plus
interface stubs for `Planner`, `SessionRealizer`, `ProgressionEngine`, `CoachBackend`; every other
stream codes against those files and does not edit them (changes go through W0 as small PRs).
No two streams own the same file.

| # | Workstream | Owns (create/edit only these) | Depends on | Deliverable / tests |
|---|---|---|---|---|
| W0 | Contracts & build | `settings.gradle.kts`, root `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `gradle/wrapper/*`, `core/build.gradle.kts`, `app/build.gradle.kts` (all verbatim from R2 §5); `core/.../model/**`, `core/.../time/**`, `core/.../units/**`; interface stubs listed above | — | compiles `:core` with an empty test; `ModelSerializationTest` |
| W1 | Knowledge & assets | `core/.../knowledge/**`; `core/src/test/resources/{exercises,equipment,rules,templates,drills,citations}.json`; `app/src/main/assets/*` (copies) | W0 | asset validation tests (§8.1 row 1–2), `RuleBook`, Appendix A/B as data |
| W2 | Equipment & volume | `core/.../equipment/**`, `core/.../volume/**` | W0, W1 | `AvailabilityResolverTest`, `LoadRounderTest`, `VolumeAccountantTest`, `WeeklyLedgerTest` |
| W3 | Planner stages 0–5 | `core/.../planner/{Planner,Normalizer,MesocycleBuilder,SplitSelector,ExerciseSelector,SetAllocator,TimeModel}.kt` | W0–W2 | E1 reproduction, `SetAllocatorTest`, golden invariants |
| W4 | Realizer & substitution | `core/.../planner/realize/**`, `core/.../substitution/**` | W0–W3 | E3/E4/E5 reproduction, concession ladder, substitution scores |
| W5 | Progression | `core/.../progression/**` | W0–W2 | E2/E7 sequences, e1RM, plateau, deload scheduler |
| W6 | Readiness, cardio, nutrition | `core/.../readiness/**`, `core/.../cardio/**`, `core/.../nutrition/**` | W0, W1 | scores, zones, energy/macros, check-in triggers, supplements |
| W7 | Mutations & coach contracts | `core/.../mutation/**`, `core/.../coach/**` | W0–W3 | validator table, tool schemas, context builder, `SseParser`, `MessageAccumulator`, `BridgeAdapter` with R1 fixtures |
| W8 | Persistence | `app/.../data/db/**`, `app/.../data/prefs/**`, `app/.../data/crypto/**`, `app/.../data/assets/**`, `app/.../data/repo/**`, `app/schemas/.gitkeep`, `AndroidManifest.xml` backup rules | W0 | entity mapping tests, `PlanRepository` recompute orchestration |
| W9 | Chat client | `app/.../chat/**` | W0, W7 | `AnthropicDirectBackend`, retry, usage meter, `ChatController` turn loop; request-builder tests |
| W10 | UI: onboarding, gyms, library, settings | `app/.../ui/{onboarding,gyms,library,settings,theme,components,nav}/**`, `GymCoachApp.kt`, `di/**`, `MainActivity.kt` | W0, W8 | screens compile-conservatively; settings policy text; Keystore flow |
| W11 | UI: today, session, plan, progress, check-in, nutrition | `app/.../ui/{today,session,plan,progress,checkin,nutrition}/**`, `app/.../work/**` | W0, W8, W3–W6 | logging flow with sacred-content guarantees; adjustments chips; Canvas chart |
| W12 | UI: coach | `app/.../ui/coach/**` | W9, W10 | streaming view, proposal cards, deferred handling |
| W13 | Content batches 1–6 | `exercises.json` `content` objects only (per R4 §5.12 ownership; six sub-owners, one batch each) | W1 | `content_status: draft`, citations per family, safety notes reflecting flags |

Merge order: W0 → W1 → (W2, W5, W6, W8 in parallel) → (W3, W7, W9, W10) → (W4, W11) → W12 → W13
continuous. Every stream adds tests in its own package only; golden fixtures are owned by W3.

---

## 10. Risks and open questions

1. **Compile-blind `:app`.** The Android module is written without a compiler on the machine
   (REQUIREMENTS). Mitigation: only stable APIs (Material 3 1.4, Navigation 2.10 type-safe routes,
   Room 2.8 annotations, OkHttp `Call`/`Response` + `BufferedSource`), explicit imports, no DSL
   tricks; the R2 fallback pairing (AGP 9.3.2 + Gradle 9.5.1) if 9.4.0 misbehaves (R2 §12 U1).
2. **Time budgets are tight for evidence-band volume.** E1 shows that 4 × 60 min cannot reach the
   intermediate lower bound for every muscle plus abs; the allocator reports rather than silently
   shortening rest or dropping warm-ups. Open question for Johannes: are 65–70-minute sessions or a
   5th short day acceptable, or is "accept" the default?
3. **`VOL-06` per-session cap is unverified** (R3 §12 item 1: the ≈ 11-set inflection is from
   secondary summaries). It is a `rules.json` constant tagged weak; the allocator's default fill
   stops at 10.
4. **Fractional vs direct counting confusion.** Both are displayed; only the direct view is compared
   with RP landmarks (R4 §4.2). The advisory MRV cap and the glutes/hamstrings example show how the
   two views disagree; the UI wording must make the difference obvious.
5. **RIR self-report bias** (`RIR-05`, Halperin 2022). Progression audits treat > 12-rep reports as
   +1 RIR and schedule AMRAP calibration sets; still, double progression is only as good as the
   reported RIR — the coach should ask about it when plateaus appear.
6. **Load-transfer seeds and unilateral factor** (Appendix C) are unverified conventions used only
   to start a find-load ramp; wrong seeds cost one ramp set, never a wrong prescription.
7. **Progression keys per gym for machines** means a new gym starts every machine at
   find-load; acceptable for a traveller (fast: one ramp), but the UI must say so up front.
8. **Cache economics.** Threads must not slide; a day-long thread with ≥ 40 turns is forced to
   restart. If real usage is many short threads, cache writes dominate (R1 §2.4 sensitivity) — the
   usage meter will show it; a 1-hour TTL is the documented remedy.
9. **Home bridge policy risk.** Tolerated grey zone, revocable without notice (R1 §1.10 item 3;
   enforcement happened 2026-04-04). It ships off by default, after the first release, with the
   quoted policy text; the app never depends on it.
10. **Refusal handling and strict tools** rely on the Opus 5 behaviours documented in R1
    (`stop_reason: refusal`, forced `tool_choice` still allowed on Opus 5). Model behaviour changes
    are absorbed by the backend layer; the tool schemas use only `type/enum/minimum/maximum`.
11. **Unknown profile facts**: Johannes' real age, weight, training months, home-gym inventory,
    known loads, preferred weekdays (R4 §7 item 4). Every example above is parameterized on them.
12. **Open corpus questions carried from research**: Pelland 2025 inflection points, Fragala 2019
    %1RM figures, Garber 2011 vigorous bands, Thomas 2016 carbohydrate mid-ranges, IOM AMDR, EFSA
    caffeine value, Bickel 2011, Enes 2024 (R3 §12) — all tagged weak/unverified in `rules.json`.
13. **Health Connect / wearables** (steps, sleep, resting HR) are manual entry in v1; integration is
    a post-compile task because the API surface cannot be verified blind.
14. **German UI** later: alias lists in R4 already carry DE names; strings are in resources from
    day one.
15. **Room 3 migration** later is mechanical (R2 §6.4); JSON columns keep it cheap.

---

## Appendix A — `rules.json` excerpt (planner constants with provenance)

| key | value | confidence | rule / source |
|---|---|---|---|
| `VOL-01.floor` | 4 | strong | Iversen 2021 |
| `VOL-04.novice.lo/hi` | 8 / 12 | moderate/weak | Schoenfeld 2017; Pelland 2025 |
| `VOL-04.intermediate.lo/hi` | 12 / 18 | moderate/weak | same |
| `VOL-04.advanced.lo/hi` | 15 / 25 | moderate/weak | same |
| `VOL-05.lift.lo/hi` | 4 / 10 | moderate | Pelland 2025 |
| `VOL-06.session_cap` / `VOL-06.default_fill_stop` | 12 / 10 | weak | secondary summaries (R3 §12.1) |
| `VOL-08.step.tier1` / `tier2` / `max` | 1 / 0.5 / 2 | weak | Enes 2024 (secondary) |
| `VOL-09.deficit.lo/hi` | 0.6 / 1.0 | weak | convention |
| `TIER.tier2_factor` | 0.6 | weak | planner convention |
| `TIER.tier3_direct_cap` | 6 | weak | R4 §4.5 note |
| `SESS-01.heavy_lower/heavy_upper/compound/isolation/superset_pair/unilateral` | 4.0 / 3.5 / 2.5 / 1.75 / 2.5 / 1.7 | weak | derived (R3) |
| `SESS-01.isolation_rest_cut` | 1.5 | weak | planner convention |
| `SESS-02.cap.default/novice/advanced` | 25 / 20 / 32 | weak | convention |
| `WU-01.minutes/short/cold_extra` | 10 / 5 / 2 | strong/moderate/weak | Fradkin 2010; Iversen 2021 |
| `WU-02.heavy` | 0.40×8, 0.55×5, 0.70×3, 0.825×1–2 | moderate | Abad 2011; Ribeiro 2020 |
| `WU-02.moderate` / `light` | 0.50×8, 0.70×4–5 / 0.60×8 | moderate | same |
| `RIR-04.week1/last/deload` | +1 / −1 / 4 | weak/moderate | convention; Rogerson 2024 |
| `RIR-03.sp_without_rack_floor` | 2 | moderate | R4 §5.5 |
| `RIR-05.high_rep_bias` | +1 above 12 reps | moderate | Halperin 2022 |
| `REST.heavy/topset/moderate/isolation/cut` | 180 / 240 / 120 / 90 / 60 s | strong/moderate | ACSM 2009; Singer 2024; Iversen 2021 |
| `PROG-02.lower_bb/upper_bb/db_max_pct/machine_max_pct` | 2.5 (5.0 early) / 2.5 (1.25 micro) / 10 % / 10 % | moderate | ACSM 2009 |
| `PROG-03.intermediate_days` | 7 | moderate | ACSM 2009 |
| `PROG-04.easy_offset/hard_offset/backoff` | +2 / −2 / 0.95 | moderate | Graham & Cleather 2021 |
| `PROG-05.novice_rir` | 3–4 | weak | convention |
| `PLAT-01.sessions` | 3 | weak | convention |
| `PLAT-03.drop/sessions` | 0.95 / 2 | weak | Bell 2025 |
| `DLD-01.weeks.intermediate/advanced/novice` | 6 / 5 / none | moderate | Bell 2025; Rogerson 2024 |
| `DLD-02.sets/load/rir` | 0.5 / 0.85 / 4 | moderate | Rogerson 2024 |
| `DLD-03.min_triggers/min_days` | 2 / 5 | weak | Bell 2025 |
| `DLD-04.load/rir_offset` | 0.95 / +1 | weak | convention |
| `E1RM.max_reff` | 10 | strong | LeSuer 1997 |
| `RDY-02.green/amber` | 70 / 45 | weak | convention |
| `RDY-02.amber.rir/amber.set_drop/red.set_factor/red.rir` | +1 / 1 / 0.6 / +2 | moderate/weak | Craven 2022; Knowles 2018 |
| `RDY-03.rhr_amber/red` | +5 / +10 bpm | weak | Buchheit 2014 |
| `LAY-01.rows` | 5–13 d: 0.95/−1 set/+1 · 14–28: 0.875/×0.7/+2 · 29–56: 0.775/×0.5/+2 · > 56: 0.65 | moderate/weak | Halonen 2024; Bosquet 2013 |
| `LAY-01.exercise_gap_days` | 14 | weak | planner convention |
| `TRV-01.sessions/minutes/exercises/sets` | 2 / 25–40 / 4–6 / 2–3 | moderate | Iversen 2021 |
| `TIME-01.levels` | 0–7 as §3.8 | moderate | Iversen 2021 |
| `CAR-03.hrmax` | 208 − 0.7·age | strong | Tanaka 2001 |
| `CAR-02.zone2_hrmax` / `hrr` | 0.60–0.70 / 0.40–0.59 | moderate | Garber 2011 (secondary) |
| `CAR-06.separation_h/run_before_legs_h` | 6 / 24 | moderate | Robineau 2016; Wilson 2012 |
| `NUT-01.default_g_per_kg` | 1.8 (band 1.6–2.2) | strong | Morton 2018 |
| `NUT-04.activity_factors` | 1.2 / 1.375 / 1.55 / 1.725 / 1.9 | weak | convention |
| `NUT-04.kcal_per_kg/clamp/window_days` | 7700 / 150 / 14 | moderate | Hall 2011 |
| `NUT-05.deficit_pct/rate` | 10–20 % / 0.5–1.0 %BW | strong | Helms 2014; Garthe 2011 |
| `NUT-06.surplus_pct` | 10–20 % | moderate | Iraki 2019 |
| `NUT-09.fat_g_per_kg.default/floor` | 1.0 / 0.5 | moderate | Iraki 2019 |
| `NUT-15.noise_pct/min_days` | 1 % / 14 | moderate | Kutáč 2015 |
| `SUP-01.g_per_day` | 3–5 | strong | Kreider 2017 |
| `SUP-02.mg_per_kg.start/max/cutoff_100/cutoff_200/day_max` | 2–3 / 6 / 9 h / 13 h / 400 mg | strong (EFSA value unverified) | Guest 2021; Gardiner 2023 |
| `SLP-03.avg_threshold/set_reduction/night_threshold` | 7 h / 10–20 % / 6 h | moderate/weak | Craven 2022 |
| `STP-01.target_lt60/ge60` | 7000–8000 / 6000–7000 | strong | Ding 2025 |
| `SEL.weights` | 0.35/0.25/0.15/0.10/0.10/0.05 | weak | planner convention |
| `SUB.weights` | 0.35/0.35/0.15/0.10/0.05, profile penalty 0.20 | weak | planner convention |
| `SUB.loadfit.tiers` | ≥ 0.85 → 1.0; 0.60 → 0.7; 0.40 → 0.4; else 0.1 | weak | planner convention (LOAD-01, TRV-01) |
| `REBAL.deficit_min/surplus_min/tolerance` | 1 / 2 / 1 | weak | planner convention |

## Appendix B — session templates (`templates.json`)

Slot notation: `role:pattern[/family fallback] (heavy)`; `⟂` = superset pair; `*` = required.

- **UPPER_A**: `PRIMARY:horizontal_push (heavy, prefer barbell/machine)*` · `PRIMARY:horizontal_pull*` ·
  `SECONDARY:vertical_push*` · `SECONDARY:vertical_pull*` · `ISOLATION:shoulder_abduction (side_delts)` ⟂
  `ISOLATION:shoulder_er_rear_delt (rear_delts)` · `ACCESSORY:chest_fly|elbow_extension (chest, triceps)` ⟂
  `ISOLATION:elbow_flexion (biceps)`
- **UPPER_B**: `PRIMARY:vertical_pull*` · `PRIMARY:incline_push*` · `SECONDARY:vertical_push (heavy, prefer barbell)*` ·
  `SECONDARY:horizontal_pull (prefer chest-supported)*` · `ISOLATION:shoulder_abduction` ⟂ `ISOLATION:shoulder_er_rear_delt` ·
  `ACCESSORY:chest_fly|elbow_extension` ⟂ `ISOLATION:elbow_flexion` · `OPTIONAL:scapular_elevation|grip`
- **LOWER_A**: `PRIMARY:squat (heavy, prefer barbell)*` · `SECONDARY:hinge (RDL-type)*` · `ACCESSORY:squat/lunge_split (prefer machine)` ·
  `ISOLATION:knee_flexion_isolation (hamstrings)*` · `ISOLATION:calf*` · `CORE:core_flexion|core_anti_extension`
- **LOWER_B**: `PRIMARY:hinge (heavy, deadlift-type)*` · `SECONDARY:squat (prefer machine)*` · `ACCESSORY:hip_extension_isolation (glutes)` ·
  `ACCESSORY:lunge_split` · `ISOLATION:knee_flexion_isolation*` · `ISOLATION:calf*` · `CORE:core_flexion|core_anti_rotation`
- **FULL_A**: `PRIMARY:squat (heavy)*` · `PRIMARY:horizontal_push*` · `PRIMARY:horizontal_pull*` · `SECONDARY:hinge (light)*` ·
  `SECONDARY:vertical_push` · `ISOLATION:priority-1` ⟂ `ISOLATION:priority-2` · `CORE`
- **FULL_B**: `PRIMARY:hinge (heavy)*` · `PRIMARY:vertical_pull*` · `PRIMARY:incline_push|vertical_push*` · `SECONDARY:lunge_split*` ·
  `SECONDARY:horizontal_pull` · `ISOLATION:priority-1` ⟂ `ISOLATION:priority-2` · `CORE`
- **FULL_C** (3-day): `PRIMARY:squat (machine/goblet)*` · `PRIMARY:horizontal_push (dumbbell/machine)*` · `PRIMARY:horizontal_pull*` ·
  `SECONDARY:hip_extension_isolation*` · `ISOLATION:shoulder_abduction` ⟂ `ISOLATION:shoulder_er_rear_delt` · `ISOLATION:calf` · `CORE`
- **PUSH**: `PRIMARY:horizontal_push (heavy)*` · `PRIMARY:vertical_push*` · `SECONDARY:incline_push|chest_fly*` ·
  `ISOLATION:shoulder_abduction*` · `ISOLATION:elbow_extension` ⟂ `ISOLATION:elbow_extension (variant)`
- **PULL**: `PRIMARY:vertical_pull*` · `PRIMARY:horizontal_pull (heavy)*` · `SECONDARY:horizontal_pull (variant)*` ·
  `ISOLATION:shoulder_er_rear_delt*` · `ISOLATION:elbow_flexion` ⟂ `ISOLATION:elbow_flexion (variant)` · `OPTIONAL:grip|carry`
- **LEGS**: `PRIMARY:squat (heavy)*` · `PRIMARY:hinge*` · `SECONDARY:lunge_split*` · `ISOLATION:knee_flexion_isolation*` ·
  `ACCESSORY:knee_extension_isolation|hip_extension_isolation` · `ISOLATION:calf*` · `CORE`
- **TRAVEL_DB_A / TRAVEL_DB_B / TRAVEL_BW_A / TRAVEL_BW_B / TRAVEL_HOTEL**: the `TRV-02` templates verbatim, with the
  same slot roles so the allocator and realizer treat them like any other session.

Strength-goal variants of UPPER_A/B and LOWER_A/B add the second weekly exposure of each main lift
(heavy day / moderate day) in the PRIMARY and SECONDARY slots.

## Appendix C — load-transfer seeds (find-load starting points only; **[convention, weak]**)

Used exclusively to choose the first ramp load of a find-load protocol or to estimate `desired
working load` in substitution scoring; never as a prescription.

| From → to | factor | From → to | factor |
|---|---|---|---|
| barbell bench → dumbbell bench (per hand) | 0.35 | back squat → front squat | 0.80 |
| barbell bench → incline barbell | 0.85 | back squat → goblet (single DB) | 0.35 |
| barbell bench → machine chest press (stack) | 0.90 | back squat → leg press (sled load) | 2.0 (machine-dependent) |
| barbell bench → close-grip | 0.85 | back squat → hack squat (sled load) | 1.5 (machine-dependent) |
| OHP → DB shoulder press (per hand) | 0.35 | conventional deadlift → trap bar | 1.05 |
| OHP → machine shoulder press | 0.90 | deadlift → RDL | 0.70 |
| barbell row → DB row (per hand) | 0.40 | trap bar → RDL | 0.75 |
| pulldown → pull-up | body weight | RDL → good morning | 0.50 |
| hip thrust → glute bridge (DB on hips) | 0.50 | hinge (any) → kettlebell/DB swing | 0.20 |
| bilateral → unilateral variant (total load) | 0.50 | hack squat (sled load) → back-squat equivalent | 0.65 |
| any exercise → same pattern, unknown | find-load from the floor | | |

---

*End of Proposal A.*
