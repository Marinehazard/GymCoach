# GymCoach — Design Proposal C: "robustness-and-compile-safety-first"

Date: 2026-09-05. Inputs: `docs/REQUIREMENTS.md`, `docs/research/R1-claude-backend.md`,
`R2-android-toolchain.md`, `R3-training-science.md`, `R4-exercise-taxonomy.md` (all read in
full). Status: proposal for decision. Every version, API shape and policy fact below is either
inherited from R1–R4 (which verified them live on 2026-09-04) or was re-verified live on
2026-09-05; Appendix D lists which is which and what remains unverified.

---

## 0. Thesis and the risk register that drives every choice

The developer machine has no JDK, no Android SDK and no Android Studio. `:core` will be compiled
and unit-tested first (JDK + Gradle); `:app` will be compiled for the first time by Johannes in
Android Studio, possibly weeks after the code is written. That single fact shapes this proposal
more than any other:

**P1 — Logic lives in `:core`, `:app` only does I/O.** Not just the planner: the *entire* Claude
wire protocol (request DTOs, the SSE parser, the stream accumulator, context assembly, the
proposal contract, transcript compaction, cost accounting) is pure Kotlin in `:core` and is
unit-tested against recorded fixtures. `:app` contributes exactly: an OkHttp call that yields
bytes, Room, DataStore, Keystore, and Compose screens that render ViewModel state.

**P2 — Minimal `:app` dependency surface.** Exactly R2's verified catalogue minus WorkManager
(no background work in v1). No Hilt (Gradle plugin + annotation processor + AGP-9 interaction we
cannot compile-check; manual DI is ~120 lines), no Vico (R2 §6.2), no `security-crypto`
(deprecated, R2 §6.1), no `anthropic-java` (R1 §3), no Retrofit/Moshi/Coil/Accompanist.

**P3 — Compile-conservative house rules** (§1.6): stable APIs only, explicit imports, no
experimental-API gambles without `@OptIn`, no reflection, no custom Gradle plugins, no
`internal`-visibility tricks across modules.

**P4 — Data is sacred; every automatic change is a record.** Logged numbers are stored verbatim
(value *and* the unit the user typed it in). Anything the planner or the coach changes produces
an `Adjustment` row with the rule ID, the before/after values and the source (REQUIREMENTS
"sacred-content discipline"; R3 POL-01).

**P5 — Append-only, backend-agnostic coach transcript.** The transcript sent to Claude is never
edited after the fact; compaction starts a new conversation seeded with a summary. This is
required by Anthropic's "preserved thinking" history-editing check, which is enforced today for
new Console organisations on Claude Fable 5.1 and announced for future models (verified
2026-09-05, Appendix D); Johannes' Console organisation will be a new one.

**P6 — Failures surface, never hide.** Every planner output carries an `Explanation` (rule IDs +
inputs); every backend failure is a typed `BackendError` with user-facing copy and a retry
verdict; every silently-unknown SSE event is counted and logged.

### 0.1 Risk register

| # | Risk | Likelihood / impact | Mitigation (section) |
|---|---|---|---|
| R-1 | `:app` does not compile on first try in Android Studio (wrong symbol, experimental API, version clash) | High / High | P1 shrinks `:app` to boring code; §1.6 house rules; R2 fallback pairing AGP 9.3.2 + Gradle 9.5.1; §9 acceptance criteria demand a per-WP "compile-risk list" of every `:app` symbol used |
| R-2 | AGP 9.4.0 + KGP 2.4.10 + Gradle 9.6.1 triple has no single compatibility statement (R2 U1) | Medium / Medium | Two-string fallback in `libs.versions.toml` + wrapper (R2 §3); nothing in the build files depends on 9.4-only features |
| R-3 | Room migration or entity mistake destroys logs | Low / Critical | `exportSchema = true`, schemas committed, no destructive fallback in release, JSON-document columns for evolving structures (§7.1) |
| R-4 | Claude policy change kills the home bridge | Medium / Low | Bridge is optional, off by default, isolated behind `ClaudeBackend`; direct API is the default (§6.1) |
| R-5 | API cost surprises | Medium / Medium | Cached system prompt + tools, delta-only context, effort held low/medium, on-device spend meter with budget warning (§6.9) |
| R-6 | Preserved-thinking enforcement rejects an edited transcript | Medium (future) / Medium | Append-only transcript, simple compaction, one `system` + `tools` per conversation (§6.10) |
| R-7 | Planner produces an unsafe or absurd session (RIR 0 on a bench without safeties, 40 sets, no legs) | Medium / High | Invariant tests over randomised profiles/gyms (§8.2); every prescription passes `PlanValidator` before it is shown |
| R-8 | 156-exercise content (cues, mistakes, safety) is not written in time | High / Medium | Content lives in six separate overlay files; the app runs with `content_status: todo` and shows "description pending" (§7.3, §9) |
| R-9 | Material icons / experimental Material 3 APIs break the build | Medium / Low | Only `Icons.Filled.*` from the frozen 1.7.8 set; `@OptIn(ExperimentalMaterial3Api::class)` on every composable that uses `TopAppBar`, `ModalBottomSheet` (warning-only if unnecessary) |
| R-10 | Keystore unavailable on emulator / key lost on device change | Medium / Low | Decrypt failure = "no key stored", re-enter key; key never backed up (§7.5) |
| R-11 | R3 "weak" thresholds are wrong for Johannes | Certain / Low | All weak constants in one `PlannerConstants` object, user-adjustable in Settings, every use tagged with its rule ID (§3.1) |
| R-12 | Twelve agents step on each other's files | High / Medium | §9 ownership map: one directory per WP, interfaces frozen in Phase 0, no shared mutable files |

---

## 1. Module and package architecture

### 1.1 Gradle layout — R2 §5 verbatim, with five deltas

Use R2's `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`,
`gradle/wrapper/gradle-wrapper.properties`, `gradle/libs.versions.toml`, `core/build.gradle.kts`
and `app/build.gradle.kts` exactly as printed there (JDK 17, Gradle 9.6.1, AGP 9.4.0, Kotlin
2.4.10, KSP 2.3.11, Compose BOM 2026.08.00, Room 2.8.4, DataStore 1.2.1, OkHttp 5.5.0,
coroutines/serialization 1.11.0, compileSdk = targetSdk = 37, minSdk 26), with these deltas:

| Delta | Change | Reason |
|---|---|---|
| D1 | Remove `implementation(libs.androidx.work.runtime)` from `app/build.gradle.kts` (keep the catalogue alias) | No background work in v1; check-in reminders are in-app (§4.6). One less runtime initialiser (`androidx.startup`) to debug blind. |
| D2 | Knowledge JSON lives in `core/src/main/resources/gymcoach/knowledge/` and is loaded via the class loader, not from `app/src/main/assets/` | The same bytes are validated by `:core` tests and shipped in the APK (AGP merges Java resources of library modules — OkHttp's own `publicsuffixes.gz` is loaded the same way, R2 G10). No `AssetManager` code, no path duplication. |
| D3 | Add `app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml`; manifest gets `android:fullBackupContent` / `android:dataExtractionRules` | Exclude the API-key ciphertext from backup (R2 §6.1). |
| D4 | `isMinifyEnabled = false` stays for v1 (as R2), but `app/proguard-rules.pro` is written now (Appendix A) | Turning R8 on later must not be a research task. |
| D5 | OkHttp body access is written null-tolerant (`val body = response.body ?: throw IOException(...)`) | OkHttp 5 made `Response.body` non-null (verified: CHANGELOG 5.0.0-alpha.7); the safe form compiles on 5.5.0 (warning) *and* on 4.12.0, so the R1 fallback is a one-line catalogue edit. |

Add `app/schemas/.gitkeep` (R2 §5.5) and `core/src/main/resources/gymcoach/knowledge/.gitkeep`.

### 1.2 `:core` package map (`com.gymcoach.core.*`, pure Kotlin/JVM)

| Package | Contents | Owner (§9) |
|---|---|---|
| `model` | Every domain type and enum (§2). `@Serializable` where a type is persisted as a document or crosses the coach boundary. | WP-01 |
| `json` | `CoreJson` (the single configured `Json` instance), `LocalDateSerializer`, `InstantSerializer`, `RangeSerializer`. | WP-01 |
| `repo` | Repository **interfaces** (`ProfileRepository`, `GymRepository`, `PlanRepository`, `WorkoutRepository`, `ProgressionRepository`, `MetricsRepository`, `CoachRepository`, `SettingsRepository`, `ApiKeyStore`, `ClaudeBackend`). | WP-01 |
| `explain` | `Explanation`, `RuleId` constants (every R3 ID as a `const val`). | WP-01 |
| `units` | kg↔lb, cm↔in, display formatting; `EnteredValue`. | WP-01 |
| `time` | `Clock` interface (`today(): LocalDate`, `now(): Instant`), `SystemClock`, `FixedClock` for tests; ISO week helpers. | WP-01 |
| `knowledge` | `KnowledgeBase` (exercise catalogue, equipment catalogue, presets, rep profiles, RP landmarks, content overlays), `KnowledgeLoader` (class-path JSON), `KnowledgeValidator` (R4 §6.3 rules as runtime checks used by tests). | WP-02 |
| `equipment` | `EquipmentResolver` (R4 §2.2 requirement algebra), `GymParams` typed accessors, `IncrementResolver` (R4 §5.4). | WP-02 |
| `planner` | `PlanGenerator`, `SplitSelector`, `VolumeAllocator`, `ExerciseSelector`, `SessionBudgeter`, `WarmupBuilder`, `SessionPrescriber`, `PlanValidator`, `PlannerConstants`. | WP-03 |
| `progression` | `ProgressionEngine`, `E1rm`, `PlateauDetector`, `DeloadPolicy`, `LayoffPolicy`, `VolumeAccountant`. | WP-04 |
| `readiness` | `ReadinessScorer`, `ReadinessModifiers`, `SleepRules`, `RedFlags`. | WP-04 |
| `cardio` | `CardioPlanner`, `HeartRateZones`, `HiitProtocols`. | WP-05 |
| `nutrition` | `EnergyEstimator`, `TdeeAdapter`, `WeightTrend`, `MacroTargets`, `SupplementAdvisor`, `HydrationRules`. | WP-05 |
| `checkin` | `CheckInBank`, `CheckInEvaluator`. | WP-05 |
| `coach.wire` | Messages API DTOs (`MessagesRequest`, content blocks, tool definitions, stream events), `SseParser`, `StreamAccumulator`, `WireJson`. | WP-06 |
| `coach` | `SystemPrompt`, `CoachContextBuilder`, `ProposalContract` (tool definitions + payload types + validation), `ProposalApplier`, `TranscriptCompactor`, `CoachTurnService`, `PricingTable`, `SpendMeter`, `BackendError`. | WP-06 |

Rule: `:core` has zero `android.*`/`androidx.*` imports. Enforced by a unit test
(`NoAndroidImportsTest`) that walks `core/src/main/kotlin` and fails on any line matching
`^import android` — cheap, and it runs on the JVM we do have.

### 1.3 `:app` package map (`com.gymcoach.app.*`)

| Package | Contents | Owner |
|---|---|---|
| (root) | `GymCoachApp : Application` (holds `AppContainer`), `MainActivity` (single activity, `setContent { GymCoachTheme { AppNavHost() } }`). | WP-09 |
| `di` | `AppContainer` — manual DI, lazy singletons (§1.5). | WP-09 |
| `data.db` | `AppDatabase`, entities, DAOs, `Migrations` (empty list at v1). | WP-07 |
| `data.repo` | Room-backed implementations of every `core.repo` interface + `Mappers`. | WP-07 |
| `data.prefs` | `SettingsDataStore` (`SettingsRepository` impl over DataStore Preferences). | WP-07 |
| `data.secure` | `KeystoreCipher`, `KeystoreApiKeyStore` (`ApiKeyStore` impl). | WP-08 |
| `network.claude` | `DirectApiBackend` (OkHttp + `SseParser` from `:core`), `RetryPolicy`, `HttpErrorMapper`. | WP-08 |
| `network.bridge` | `BridgeBackend` (adapter for the home bridge; v1 = protocol + adapter, returns `BackendError.NotConfigured` until a URL is set). | WP-08 |
| `ui.theme` | `GymCoachTheme`, colour scheme (dynamic colour on 31+, fallback palette), typography. | WP-09 |
| `ui.nav` | `Routes.kt` (type-safe `@Serializable` routes), `AppNavHost.kt`, `BottomBar.kt`. | WP-09 |
| `ui.components` | Shared composables: `NumberField`, `UnitLabel`, `ExplanationChip`, `ErrorBanner`, `LoadingBox`, `ConfirmDialog`, `SectionHeader`, `LineChart` (Canvas). | WP-09 |
| `ui.today`, `ui.workout`, `ui.plan`, `ui.progress` | Screens + ViewModels for training. | WP-10 |
| `ui.onboarding`, `ui.profile`, `ui.gyms`, `ui.library`, `ui.checkin`, `ui.readiness`, `ui.nutrition` | Screens + ViewModels for setup and health. | WP-11 |
| `ui.coach`, `ui.settings`, `ui.usage` | Coach chat, proposal cards, settings/backend, spend. | WP-12 |

### 1.4 Dependency rules

```
ui.*  ──►  ViewModels  ──►  core.repo interfaces + core services
                       ──►  (never) data.db entities, OkHttp, Room types
data.repo  ──►  core.model + data.db          network.*  ──►  core.coach.wire + core.repo.ClaudeBackend
di.AppContainer  ──►  everything (it is the only place that knows concrete classes)
```

`:core` never depends on `:app`. Screens never import `androidx.room`, `okhttp3` or `data.db`.
ViewModels never import `android.content.Context` (they get repositories from the container).
These three rules are stated in each WP's acceptance criteria and are mechanically checkable by
grep before the first Android build.

### 1.5 Manual DI — one `AppContainer`, ViewModels via `viewModel { }`

Hilt was considered and rejected: it adds the Hilt Gradle plugin, a KSP processor, generated
`Hilt_*` classes, and an AGP-9 interaction that R2 did not verify; a single mistake in an
`@Module` is a build break we cannot see. The whole app has ~12 ViewModels and ~15 singletons.

```kotlin
// app/src/main/kotlin/com/gymcoach/app/di/AppContainer.kt
package com.gymcoach.app.di

import android.content.Context
import androidx.room.Room
import com.gymcoach.app.data.db.AppDatabase
import com.gymcoach.app.data.db.Migrations
import com.gymcoach.app.data.prefs.SettingsDataStore
import com.gymcoach.app.data.repo.RoomCoachRepository
import com.gymcoach.app.data.repo.RoomGymRepository
import com.gymcoach.app.data.repo.RoomMetricsRepository
import com.gymcoach.app.data.repo.RoomPlanRepository
import com.gymcoach.app.data.repo.RoomProfileRepository
import com.gymcoach.app.data.repo.RoomProgressionRepository
import com.gymcoach.app.data.repo.RoomWorkoutRepository
import com.gymcoach.app.data.secure.KeystoreApiKeyStore
import com.gymcoach.app.network.bridge.BridgeBackend
import com.gymcoach.app.network.claude.DirectApiBackend
import com.gymcoach.core.coach.CoachTurnService
import com.gymcoach.core.coach.SpendMeter
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.knowledge.KnowledgeLoader
import com.gymcoach.core.planner.PlanGenerator
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.planner.SessionPrescriber
import com.gymcoach.core.progression.ProgressionEngine
import com.gymcoach.core.readiness.ReadinessScorer
import com.gymcoach.core.repo.ApiKeyStore
import com.gymcoach.core.repo.ClaudeBackend
import com.gymcoach.core.repo.SettingsRepository
import com.gymcoach.core.time.Clock
import com.gymcoach.core.time.SystemClock
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Single composition root. Everything is `by lazy` so the Application start stays cheap and
 * a construction failure surfaces at first use (with a stack trace naming the member).
 */
class AppContainer(private val appContext: Context) {

    val clock: Clock = SystemClock()

    val knowledge: KnowledgeBase by lazy { KnowledgeLoader.loadDefault(CoreJson.instance) }

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "gymcoach.db")
            .addMigrations(*Migrations.all)
            .build()
    }

    val settingsRepository: SettingsRepository by lazy { SettingsDataStore(appContext) }
    val apiKeyStore: ApiKeyStore by lazy { KeystoreApiKeyStore(appContext, settingsRepository) }

    val profileRepository by lazy { RoomProfileRepository(database.profileDao()) }
    val gymRepository by lazy { RoomGymRepository(database.gymDao(), CoreJson.instance) }
    val planRepository by lazy { RoomPlanRepository(database.planDao(), CoreJson.instance) }
    val workoutRepository by lazy { RoomWorkoutRepository(database.workoutDao(), CoreJson.instance) }
    val progressionRepository by lazy { RoomProgressionRepository(database.progressionDao(), CoreJson.instance) }
    val metricsRepository by lazy { RoomMetricsRepository(database.metricsDao(), CoreJson.instance) }
    val coachRepository by lazy { RoomCoachRepository(database.coachDao(), CoreJson.instance) }

    val plannerConstants: PlannerConstants by lazy { PlannerConstants.DEFAULT }
    val planGenerator by lazy { PlanGenerator(knowledge, plannerConstants, clock) }
    val sessionPrescriber by lazy { SessionPrescriber(knowledge, plannerConstants) }
    val progressionEngine by lazy { ProgressionEngine(knowledge, plannerConstants) }
    val readinessScorer by lazy { ReadinessScorer(plannerConstants) }

    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)   // SSE ping cadence is undocumented (R1 §4.4)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    /** Backend is chosen per turn from settings so switching in Settings needs no restart. */
    fun backend(mode: com.gymcoach.core.model.BackendMode): ClaudeBackend = when (mode) {
        com.gymcoach.core.model.BackendMode.DIRECT_API -> DirectApiBackend(okHttp, apiKeyStore)
        com.gymcoach.core.model.BackendMode.HOME_BRIDGE -> BridgeBackend(okHttp, settingsRepository, apiKeyStore)
        com.gymcoach.core.model.BackendMode.NONE -> DirectApiBackend(okHttp, apiKeyStore)
    }

    val spendMeter by lazy { SpendMeter(coachRepository, clock) }
    val coachTurnService by lazy {
        CoachTurnService(
            knowledge = knowledge,
            profile = profileRepository,
            gyms = gymRepository,
            plans = planRepository,
            workouts = workoutRepository,
            metrics = metricsRepository,
            coach = coachRepository,
            settings = settingsRepository,
            backendFactory = ::backend,
            spend = spendMeter,
            clock = clock,
        )
    }
}
```

Access from Compose (no `CompositionLocal` of our own — one fewer thing to get wrong):

```kotlin
// in a screen file
val container = (LocalContext.current.applicationContext as GymCoachApp).container
val vm: TodayViewModel = viewModel { TodayViewModel(container.planRepository, container.sessionPrescriber, ...) }
```

`viewModel(initializer: CreationExtras.() -> VM)` from `lifecycle-viewmodel-compose` was
verified in the AndroidX sources on 2026-09-05 (Appendix D). No factories, no `SavedStateHandle`
in v1 (routes carry ids; ViewModels re-read from repositories).

### 1.6 Compile-conservative house rules (binding for every WP)

1. Explicit imports only; no wildcard imports, no `import ... as` aliases except where two types
   share a simple name.
2. Only APIs that exist in the pinned versions and have existed for ≥ 2 years, unless R1/R2 or
   Appendix D verified them today. Every `:app` symbol outside that set goes on the WP's
   "compile-risk list" in its PR description.
3. Every composable that calls `TopAppBar`, `ModalBottomSheet`, `DatePicker`, `SegmentedButton`
   carries `@OptIn(ExperimentalMaterial3Api::class)`. Over-opting is a warning; under-opting is
   an error.
4. Icons: `Icons.Filled.*` and `Icons.AutoMirrored.Filled.*` only, from the frozen
   `material-icons-extended` 1.7.8; no custom vector assets in v1 (R2 §6.3).
5. No `kotlin-reflect`, no `Class.forName`, no annotation processing except Room via KSP.
6. Kotlin language features: no context parameters, no `@JvmInline` value classes in Room
   entities, no `sealed interface` with `@Serializable` polymorphism across modules unless the
   `classDiscriminator` is set on the shared `Json` (it is, `CoreJson`).
7. Coroutines: `Dispatchers.IO` for DB/network, `viewModelScope` in ViewModels, `Flow` from
   DAOs, `StateFlow` to UI via `collectAsStateWithLifecycle()`.
8. Room entities use only `Long`, `Int`, `Double`, `String`, `Boolean` columns. No
   `@TypeConverters`. Dates are ISO-8601 strings; documents are JSON strings.
9. Serialization: `@Serializable` data classes with default values for every optional field;
   `ignoreUnknownKeys = true`, `encodeDefaults = true`, `explicitNulls = false`. Never a named
   companion object on a `@Serializable` class (the only case where kotlinx's bundled R8 rules do
   not cover you — verified README, Appendix D).
10. Never `!!` in `:app`; `checkNotNull(x) { "message naming the field" }` instead.
11. Every `when` over an enum is exhaustive (no `else`) so adding an enum value fails compilation
    in `:core` where we can see it.
12. No Gradle configuration cache, no KSP incremental flags, no build-script Kotlin beyond what R2
    prints (R2 §5.6, G13).
13. UI state is one `data class XxxUiState` per screen with a `sealed interface` for loading/
    error; screens are pure functions of state + callbacks.
14. No `LaunchedEffect(Unit)` that starts network work; network work starts only from explicit
    user actions (send, retry).
15. Every string the coach can show the user comes through `Explanation.message` or a chat text
    block; no string concatenation of rule IDs in UI code.
16. Tests in `:core` use JUnit 4 + `kotlin-test-junit` assertions only; `kotlinx-coroutines-test`
    `runTest` for suspend code. No mocking library — hand-written fakes for repositories.

### 1.7 Threading and state

- Repositories: `suspend` for one-shot, `Flow` for observation; implementations switch to
  `Dispatchers.IO` internally so callers never think about threads.
- Planner/progression: pure, synchronous, no I/O. ViewModels call them inside
  `withContext(Dispatchers.Default)`.
- Coach turn: `CoachTurnService.send()` returns `Flow<TurnEvent>`; the ViewModel collects it and
  updates `StateFlow<CoachUiState>`; cancellation cancels the OkHttp call (the backend wraps the
  call in `suspendCancellableCoroutine` and calls `call.cancel()` on cancellation).

---

## 2. Domain data model (`:core`, package `com.gymcoach.core.model`)

Conventions: kg/cm/seconds internally; `EnteredValue` preserves what the user typed; ids are
`Long` (Room rowid) for user data and `String` (slug) for knowledge. All files below start with
`@file:UseSerializers(LocalDateSerializer::class, InstantSerializer::class)` and
`package com.gymcoach.core.model` plus explicit imports (`java.time.Instant`,
`java.time.LocalDate`, `kotlinx.serialization.SerialName`, `kotlinx.serialization.Serializable`,
`kotlinx.serialization.UseSerializers`, `kotlinx.serialization.json.JsonElement`).

### 2.1 Serializers (`core/json/Serializers.kt`)

```kotlin
package com.gymcoach.core.json

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
import kotlinx.serialization.json.Json
import com.gymcoach.core.model.Range

object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/** R4 schemas encode ranges as `[lo, hi]`. */
object RangeSerializer : KSerializer<Range> {
    private val delegate = ListSerializer(Int.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Range) = delegate.serialize(encoder, listOf(value.lo, value.hi))
    override fun deserialize(decoder: Decoder): Range {
        val list = delegate.deserialize(decoder)
        require(list.size == 2) { "range must have exactly two elements, got $list" }
        return Range(list[0], list[1])
    }
}

object CoreJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
        prettyPrint = false
    }
}
```

### 2.2 Enums (`model/Enums.kt`)

```kotlin
@Serializable
enum class Goal {                       // R3 DEF-06
    @SerialName("strength") STRENGTH,
    @SerialName("hypertrophy") HYPERTROPHY,
    @SerialName("general_fitness") GENERAL_FITNESS,
    @SerialName("fat_loss") FAT_LOSS,
    @SerialName("endurance_support") ENDURANCE_SUPPORT;

    /** Key into exercises.json rep_profiles.by_goal (R4 §5.2 has four goals). */
    val repProfileKey: String
        get() = when (this) {
            STRENGTH -> "strength"
            HYPERTROPHY -> "hypertrophy"
            GENERAL_FITNESS, FAT_LOSS -> "general_fitness"
            ENDURANCE_SUPPORT -> "endurance"
        }
}

@Serializable enum class Sex { @SerialName("male") MALE, @SerialName("female") FEMALE }
@Serializable enum class TrainingAge { NOVICE, INTERMEDIATE, ADVANCED }        // DEF-01
@Serializable enum class UnitSystem { METRIC, IMPERIAL }
@Serializable enum class WeightUnit { KG, LB }
@Serializable enum class LengthUnit { CM, IN }

@Serializable
enum class MovementPattern {            // R4 §3, 29 values
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
enum class Muscle {                     // R4 §4.1, 21 values
    @SerialName("chest") CHEST, @SerialName("front_delts") FRONT_DELTS, @SerialName("side_delts") SIDE_DELTS,
    @SerialName("rear_delts") REAR_DELTS, @SerialName("rotator_cuff") ROTATOR_CUFF, @SerialName("lats") LATS,
    @SerialName("upper_back") UPPER_BACK, @SerialName("traps_upper") TRAPS_UPPER, @SerialName("lower_back") LOWER_BACK,
    @SerialName("biceps") BICEPS, @SerialName("triceps") TRICEPS, @SerialName("forearms") FOREARMS,
    @SerialName("abs") ABS, @SerialName("obliques") OBLIQUES, @SerialName("hip_flexors") HIP_FLEXORS,
    @SerialName("quads") QUADS, @SerialName("hamstrings") HAMSTRINGS, @SerialName("glutes") GLUTES,
    @SerialName("glute_med") GLUTE_MED, @SerialName("adductors") ADDUCTORS, @SerialName("calves") CALVES
}

@Serializable
enum class LoadType {                   // R4 §6.2 loadType
    @SerialName("barbell") BARBELL, @SerialName("dumbbell") DUMBBELL, @SerialName("machine") MACHINE,
    @SerialName("cable") CABLE, @SerialName("bodyweight") BODYWEIGHT, @SerialName("band") BAND,
    @SerialName("kettlebell") KETTLEBELL, @SerialName("smith") SMITH, @SerialName("sled") SLED,
    @SerialName("medicine_ball") MEDICINE_BALL, @SerialName("cardio") CARDIO, @SerialName("none") NONE
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
@Serializable enum class IncrementKind { @SerialName("kg") KG, @SerialName("band_level") BAND_LEVEL, @SerialName("reps") REPS, @SerialName("time") TIME, @SerialName("distance") DISTANCE, @SerialName("box_height") BOX_HEIGHT, @SerialName("none") NONE }
@Serializable enum class IncrementDirection { @SerialName("load") LOAD, @SerialName("assist") ASSIST }
@Serializable enum class ProgressionKind { @SerialName("linear_load") LINEAR_LOAD, @SerialName("double_progression") DOUBLE_PROGRESSION, @SerialName("rep_progression") REP_PROGRESSION, @SerialName("time_progression") TIME_PROGRESSION, @SerialName("band_progression") BAND_PROGRESSION, @SerialName("distance_or_time") DISTANCE_OR_TIME, @SerialName("none") NONE }
@Serializable enum class ContentStatus { @SerialName("todo") TODO, @SerialName("draft") DRAFT, @SerialName("reviewed") REVIEWED }

/** R3 DEF-07 exercise classes for increments and RIR; derived from load type + flags. */
@Serializable enum class ExerciseClass { LOWER_BARBELL, UPPER_BARBELL, DUMBBELL, MACHINE, CABLE, BODYWEIGHT, ISOLATION }
@Serializable enum class LoadZone { MAX_STRENGTH, HEAVY, MODERATE, LIGHT, POWER }     // LOAD-04
@Serializable enum class ReadinessBand { GREEN, AMBER, RED }                          // RDY-02
@Serializable enum class SetKind { WARMUP, WORKING, DROP, AMRAP }                     // DEF-04 / WU-03
@Serializable enum class AdjustmentSource { PLANNER, COACH, USER }
@Serializable enum class Limitation { KNEE, SHOULDER, LOW_BACK, ELBOW_WRIST, HIP, NECK, OTHER }   // INJ-01..04
@Serializable enum class MedicalFlag { HEART_DISEASE, KIDNEY_DISEASE, LIVER_DISEASE, PREGNANCY, DIABETES_ON_MEDICATION, UNCONTROLLED_HYPERTENSION, DOCTOR_RESTRICTION }  // RED-03
@Serializable enum class SplitKind { FULL_BODY, UPPER_LOWER, PUSH_PULL_LEGS, UPPER_LOWER_PPL, UPPER_LOWER_FULL }   // SPLIT-01
@Serializable enum class CardioKind { ZONE2, WALK, HIIT_4X4, HIIT_10X1, SIT }         // CAR-02/05
@Serializable enum class ProposalKind { SWAP_EXERCISE, CHANGE_VOLUME, ADD_CARDIO_DAY, CHANGE_GOAL, SET_CHECKIN_QUESTION, SCHEDULE_DELOAD }
@Serializable enum class ProposalStatus { PENDING, APPLIED, REJECTED, EXPIRED, FAILED }
@Serializable enum class BackendMode { NONE, DIRECT_API, HOME_BRIDGE }
@Serializable enum class Effort { LOW, MEDIUM }                                         // R1 §2.4: held per conversation
@Serializable enum class ChatRole { USER, ASSISTANT }
@Serializable enum class ConversationStatus { OPEN, AWAITING_TOOL_RESULTS, COMPACTED, CLOSED }
@Serializable enum class LoadSource { PROGRESSION_STATE, E1RM_ESTIMATE, LOAD_FIND, USER_OVERRIDE, NONE }
@Serializable enum class E1rmConfidence { HIGH, LOW, NONE }                            // E1RM-02
```

### 2.3 Values, explanations, units

```kotlin
@Serializable(with = RangeSerializer::class)
data class Range(val lo: Int, val hi: Int) {
    init { require(lo <= hi) { "lo=$lo > hi=$hi" } }
    operator fun contains(v: Int): Boolean = v in lo..hi
}

/** Every automatic decision carries one of these (R3 POL-01). ruleIds are R3 IDs, e.g. "PROG-01". */
@Serializable
data class Explanation(
    val ruleIds: List<String>,
    val message: String,
    val inputs: Map<String, String> = emptyMap(),
)

/** What the user typed, verbatim, plus the derived SI value. Display uses `entered` when the unit matches. */
@Serializable
data class EnteredWeight(val entered: Double, val unit: WeightUnit, val kg: Double)

@Serializable
data class EnteredLength(val entered: Double, val unit: LengthUnit, val cm: Double)
```

### 2.4 Profile and body metrics (`model/Profile.kt`)

```kotlin
@Serializable
data class UserProfile(
    val id: Long = 1L,                       // single row
    val displayName: String = "",
    val sex: Sex,
    val birthDate: LocalDate,
    val height: EnteredLength,
    val trainingMonthsConsistent: Int,      // DEF-01 input: months of >= 2 sessions/week
    val lastLayoffWeeks: Int = 0,            // DEF-01 / LAY-01 input
    val daysPerWeek: Int,                    // 1..6 (SPLIT-01)
    val sessionMinutes: Int,                 // 30..120 (SESS-01)
    val goal: Goal,
    val limitations: Set<Limitation> = emptySet(),
    val medicalFlags: Set<MedicalFlag> = emptySet(),
    val bodyFatPct: Double? = null,          // NUT-05 leanness gate
    val hasSpotterUsually: Boolean = false,
    val allowAdvancedExercises: Boolean = false,   // R4 §5.3 opt-in
    val preferredWakeTime: String? = null,   // "06:30" — WU-06 / SLP-03 morning preference
    val updatedAt: Instant,
) {
    fun ageOn(date: LocalDate): Int = java.time.Period.between(birthDate, date).years
}

@Serializable
data class BodyMetric(
    val id: Long = 0L,
    val date: LocalDate,
    val weight: EnteredWeight,
    val waistCm: Double? = null,
    val restingHr: Int? = null,               // RDY-01 optional input
    val note: String = "",
)
```

### 2.5 Equipment and gyms (`model/Equipment.kt`) — mirrors R4 §6.1

```kotlin
@Serializable
data class ParamSpec(
    val key: String,
    val type: String,                        // "number" | "integer" | "boolean" | "number_list"
    val unit: String? = null,
    val default: JsonElement? = null,
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
data class EquipmentPreset(val id: String, val name: String, val description: String? = null, val items: List<PresetItem>)

@Serializable
data class EquipmentCatalog(
    @SerialName("schema_version") val schemaVersion: Int,
    val equipment: List<EquipmentDef>,
    val groups: List<EquipmentGroup>,
    val presets: List<EquipmentPreset>,
)

/** User data: one line of a named gym set. Params override the catalogue defaults (R4 §2.6). */
@Serializable
data class GymItem(val equipmentId: String, val params: Map<String, JsonElement> = emptyMap())

@Serializable
data class GymSet(
    val id: Long = 0L,
    val name: String,
    val items: List<GymItem>,
    val basedOnPreset: String? = null,
    val notes: String = "",
    val isArchived: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** "Today's" equipment: the chosen gym plus session-only toggles (R4 §2.2 item 4, §2.5 travel_kit). */
data class SessionEquipment(
    val gym: GymSet,
    val travelKitPacked: Boolean = false,
    val spotterPresent: Boolean = false,
    val extras: List<GymItem> = emptyList(),
)
```

### 2.6 Exercise catalogue (`model/Exercise.kt`) — mirrors R4 §6.2

```kotlin
@Serializable data class MuscleWeight(val muscle: Muscle, val weight: Double)   // 1.0 | 0.5 | 0.25 (R4 §4.2)

@Serializable
data class EquipmentOption(
    @SerialName("all_of") val allOf: List<String>,
    @SerialName("load_type") val loadType: LoadType? = null,
    val note: String? = null,
    val preferred: Boolean = false,
)

@Serializable
data class GoalPrescription(
    val range: Range,
    val rir: Range,
    @SerialName("rir_last_week") val rirLastWeek: Range? = null,
    val sets: Range? = null,
    val note: String? = null,
)

@Serializable
data class RepProfile(
    val id: String,
    val unit: RepUnit,
    @SerialName("by_goal") val byGoal: Map<String, GoalPrescription>,   // keys: strength|hypertrophy|endurance|general_fitness
    val notes: String? = null,
    val sources: List<String> = emptyList(),
)

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
}

@Serializable
data class ExerciseCatalog(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("rep_profiles") val repProfiles: List<RepProfile>,
    val exercises: List<Exercise>,
)

/** One content-overlay file per authoring batch (R4 §5.12): `exercise_content/batch_N.json`. */
@Serializable
data class ContentOverlay(
    @SerialName("schema_version") val schemaVersion: Int,
    val batch: Int,
    val entries: Map<String, ExerciseContent>,          // exercise id -> content
    val status: Map<String, ContentStatus> = emptyMap(),
)
```

### 2.7 Plan documents (`model/Plan.kt`) — stored as one JSON document per plan

```kotlin
@Serializable
data class Plan(
    val id: Long = 0L,
    val name: String,
    val goal: Goal,
    val trainingAge: TrainingAge,
    val createdAt: Instant,
    val startDate: LocalDate,
    val weeks: Int,                          // mesocycle length (DLD-01 cadence)
    val deloadWeekIndex: Int?,               // 0-based; null = no scheduled deload (novice, DLD-01)
    val split: SplitKind,
    val daysPerWeek: Int,
    val sessionMinutes: Int,
    val sessions: List<SessionTemplate>,
    val cardio: List<CardioSlot>,
    val weeklyVolumeTargets: Map<Muscle, Range>,        // VOL-04 / VOL-05, fractional sets
    val explanations: List<Explanation>,
    val schemaVersion: Int = 1,
    val isActive: Boolean = true,
)

@Serializable
data class SessionTemplate(
    val id: String,                          // "s1".."s6", stable within a plan
    val dayIndex: Int,                       // 0..6 relative to the user's week start
    val name: String,                        // "Upper A"
    val focus: List<Muscle>,
    val blocks: List<ExerciseBlock>,
    val warmup: WarmupPlan,
    val estimatedMinutes: Int,
)

@Serializable
data class ExerciseBlock(
    val exerciseId: String,
    val order: Int,
    val sets: Int,
    val reps: Range,
    val rir: Range,                          // mid-mesocycle target; RIR-04 drift is applied per week
    val restSec: Int,
    val unit: RepUnit,
    val loadZone: LoadZone,
    val isPriority: Boolean,                 // ORD-01
    val supersetGroup: Int? = null,          // REST-03 / TIME-01 step 3
    val alternates: List<String>,            // ranked substitutions available in this gym
    val explanation: Explanation,
)

@Serializable
data class WarmupPlan(
    val raiseMinutes: Int,                   // WU-01 / WU-05 / WU-06
    val drills: List<WarmupDrill>,           // MOB-01 / MOB-07
    val rampSets: List<RampSet>,             // WU-02
    val explanation: Explanation,
)

@Serializable data class WarmupDrill(val exerciseId: String, val sets: Int, val reps: Int? = null, val seconds: Int? = null)
@Serializable data class RampSet(val exerciseId: String, val pctOfWorkingLoad: Int, val reps: Int)

@Serializable
data class CardioSlot(
    val dayIndex: Int,
    val kind: CardioKind,
    val minutes: Int,
    val modalityIds: List<String>,           // ranked cardio exercise ids available in the gym (CAR-08)
    val separateFromLifting: Boolean,        // CAR-06: >= 6 h or different day
    val explanation: Explanation,
)
```

### 2.8 Day prescription, logging, adjustments (`model/Workout.kt`)

```kotlin
@Serializable
data class WorkoutPrescription(
    val date: LocalDate,
    val planId: Long,
    val templateId: String,
    val weekIndex: Int,
    val gymId: Long,
    val readinessScore: Int? = null,
    val readinessBand: ReadinessBand? = null,
    val modifiers: List<Explanation>,        // RDY-02, SLP-03, DLD-02, LAY-01, TIME-01 ... applied today
    val warmup: WarmupPlan,
    val blocks: List<PrescribedBlock>,
    val estimatedMinutes: Int,
)

@Serializable
data class PrescribedBlock(
    val exerciseId: String,
    val substitutedFrom: String? = null,     // TRV-04 / INJ / equipment substitution
    val order: Int,
    val sets: Int,
    val reps: Range,
    val rir: Range,
    val restSec: Int,
    val unit: RepUnit,
    val targetLoadKg: Double? = null,
    val loadSource: LoadSource,
    val supersetGroup: Int? = null,
    val warnings: List<Explanation> = emptyList(),   // e.g. dumbbell max exceeded (R4 §2.6), no safeties (R4 §5.5)
    val explanation: Explanation,
)

@Serializable
data class WorkoutSession(
    val id: Long = 0L,
    val date: LocalDate,
    val gymId: Long? = null,
    val planId: Long? = null,
    val templateId: String? = null,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val readinessCheckId: Long? = null,
    val sessionRpe: Int? = null,             // RDY-06 (0-10)
    val notes: String = "",
    val prescription: WorkoutPrescription? = null,   // frozen copy for audit
)

/** Immutable once saved. Edits create a new row and mark the old one superseded (sacred content). */
@Serializable
data class SetLog(
    val id: Long = 0L,
    val sessionId: Long,
    val exerciseId: String,
    val setIndex: Int,
    val kind: SetKind,
    val weight: EnteredWeight? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val metres: Int? = null,
    val rir: Int? = null,
    val isUnilateral: Boolean = false,
    val loggedAt: Instant,
    val supersededById: Long? = null,
    val note: String = "",
)

@Serializable
data class Adjustment(
    val id: Long = 0L,
    val sessionId: Long? = null,
    val exerciseId: String? = null,
    val ruleIds: List<String>,
    val summary: String,                     // "Load 60 -> 62.5 kg (PROG-01: all sets hit 10 reps at <= 2 RIR)"
    val beforeJson: String,
    val afterJson: String,
    val source: AdjustmentSource,
    val createdAt: Instant,
)

@Serializable
data class CardioLog(val id: Long = 0L, val date: LocalDate, val exerciseId: String, val kind: CardioKind, val minutes: Int, val avgHr: Int? = null, val rpe: Int? = null, val note: String = "")

@Serializable
data class NutritionLog(
    val id: Long = 0L,
    val date: LocalDate,
    val kcal: Int? = null,
    val proteinG: Int? = null,
    val proteinTargetHit: Boolean? = null,   // NUT-14 (5) ordinal fallback
    val mealsHeaviness: Int? = null,         // 1 light .. 3 heavy
    val alcoholDrinks: Int? = null,          // NUT-13
    val steps: Int? = null,                  // STP-01
    val note: String = "",
)
```

### 2.9 Progression, readiness, check-ins (`model/Progression.kt`, `model/Readiness.kt`, `model/CheckIn.kt`)

```kotlin
@Serializable
data class ProgressionState(
    val exerciseId: String,
    val currentLoadKg: Double? = null,
    val bandLevel: Int? = null,              // for BAND_PROGRESSION
    val repRange: Range,
    val targetRir: Range,
    val consecutiveTopHits: Int = 0,         // PROG-01 counter
    val sessionsWithoutGain: Int = 0,        // PLAT-01 counter
    val lastSessionDate: LocalDate? = null,
    val lastIncrementDate: LocalDate? = null,
    val bestE1rm: E1rmEstimate? = null,
    val rolling14dBestE1rmKg: Double? = null,   // E1RM-04
    val loadFindPending: Boolean = true,     // TRV-04: unfamiliar exercise -> RIR-based load find
    val updatedAt: Instant,
)

@Serializable
data class E1rmEstimate(
    val kg: Double,
    val fromWeightKg: Double,
    val fromReps: Int,
    val fromRir: Int?,
    val confidence: E1rmConfidence,
    val date: LocalDate,
    val formulaId: String = "E1RM-01",
)

@Serializable
data class ReadinessCheck(
    val id: Long = 0L,
    val date: LocalDate,
    val sleepHours: Double,
    val sleepQuality: Int,                   // 1..5
    val soreness: Int,                       // 1..5 (5 severe)
    val stress: Int,                         // 1..5 (5 severe)
    val energy: Int,                         // 1..5 (5 full)
    val motivation: Int,                     // 1..5
    val restingHr: Int? = null,
    val score: Int,                          // 0..100 (RDY-01)
    val band: ReadinessBand,                 // RDY-02
    val explanation: Explanation,
)

@Serializable
data class CheckInQuestion(
    val id: String,                          // "Q1".."Q15" (CHK-01) or "C-<n>" for coach-added
    val text: String,
    val answerType: String,                  // "number" | "scale5" | "yesno" | "text"
    val triggerRuleIds: List<String>,
    val addedByCoach: Boolean = false,
    val activeFrom: LocalDate? = null,
    val activeUntil: LocalDate? = null,
)

@Serializable
data class CheckInAnswer(val questionId: String, val number: Double? = null, val yes: Boolean? = null, val text: String? = null)

@Serializable
data class WeeklyCheckIn(
    val id: Long = 0L,
    val weekStart: LocalDate,
    val answers: List<CheckInAnswer>,
    val triggered: List<Explanation>,        // what the evaluator concluded (RDY-05, SLP-03, NUT-16 ...)
    val completedAt: Instant? = null,
)
```

### 2.10 Nutrition (`model/Nutrition.kt`)

```kotlin
@Serializable
data class EnergyModel(
    val reeKcal: Int,                        // NUT-04 Mifflin-St Jeor
    val activityFactor: Double,              // NUT-04 (weak)
    val tdeeEstimateKcal: Int,               // after adaptive corrections
    val adjustments: List<TdeeAdjustment>,
    val explanation: Explanation,
)

@Serializable
data class TdeeAdjustment(val date: LocalDate, val observedRateKgPerWeek: Double, val expectedRateKgPerWeek: Double, val deltaKcal: Int, val explanation: Explanation)

@Serializable
data class NutritionTargets(
    val kcal: Int,
    val proteinG: Int,                       // NUT-01
    val carbsG: Int,                         // NUT-08
    val fatG: Int,                           // NUT-09
    val fibreG: Int,                         // NUT-10
    val waterL: Double,                      // NUT-11
    val energyDeltaKcal: Int,                // NUT-05 / NUT-06 / NUT-07 (negative = deficit)
    val goalRateBwPctPerWeek: Range?,        // expressed x10 (e.g. 5..10 = 0.5..1.0 %)
    val explanation: Explanation,
)

@Serializable
data class WeightTrendPoint(val date: LocalDate, val rawKg: Double? = null, val ma7Kg: Double? = null)   // NUT-15
```

### 2.11 Coach (`model/Coach.kt`)

```kotlin
@Serializable
data class ChatConversation(
    val id: Long = 0L,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val backend: BackendMode,
    val model: String,
    val effort: Effort,
    val systemPromptHash: String,            // sha-256 of the byte-stable prompt used (§6.3)
    val toolContractVersion: Int,
    val seedSummary: String? = null,         // set when this conversation was started by compaction (§6.10)
    val status: ConversationStatus,
)

/** One transcript entry. `contentJson` is the verbatim Messages-API content array (thinking blocks included). */
@Serializable
data class ChatMessage(
    val id: Long = 0L,
    val conversationId: Long,
    val role: ChatRole,
    val contentJson: String,
    val displayText: String,                 // what the UI renders (text blocks joined); never sent back
    val createdAt: Instant,
    val usage: TokenUsage? = null,
    val requestId: String? = null,
    val stopReason: String? = null,
)

@Serializable
data class TokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadInputTokens: Int = 0,
    val cacheCreationInputTokens: Int = 0,
    val thinkingTokens: Int? = null,
)

@Serializable
data class Proposal(
    val id: Long = 0L,
    val conversationId: Long,
    val assistantMessageId: Long,
    val toolUseId: String,
    val toolName: String,
    val kind: ProposalKind,
    val contractVersion: Int,
    val payloadJson: String,                 // verbatim tool_use.input
    val rationale: String,
    val status: ProposalStatus,
    val createdAt: Instant,
    val decidedAt: Instant? = null,
    val resultSummary: String? = null,       // human summary sent back as tool_result
)

@Serializable
data class SpendRecord(val id: Long = 0L, val at: Instant, val model: String, val usage: TokenUsage, val costUsd: Double, val backend: BackendMode)

@Serializable
data class AppSettings(
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val backendMode: BackendMode = BackendMode.NONE,
    val modelId: String = "claude-opus-5",
    val effort: Effort = Effort.LOW,
    val monthlyBudgetUsd: Double = 30.0,
    val bridgeUrl: String = "",
    val showExperimental: Boolean = false,
    val onboardingDone: Boolean = false,
    val weekStartsOnMonday: Boolean = true,
    val plannerOverridesJson: String = "{}",   // user-adjusted PlannerConstants (R-11)
)
```

### 2.12 Repository interfaces (`core/repo/*.kt`)

```kotlin
interface ProfileRepository {
    fun observe(): Flow<UserProfile?>
    suspend fun get(): UserProfile?
    suspend fun save(profile: UserProfile)
}

interface GymRepository {
    fun observeAll(includeArchived: Boolean = false): Flow<List<GymSet>>
    suspend fun get(id: Long): GymSet?
    suspend fun upsert(gym: GymSet): Long
    suspend fun setArchived(id: Long, archived: Boolean)
}

interface PlanRepository {
    fun observeActive(): Flow<Plan?>
    suspend fun getActive(): Plan?
    suspend fun get(id: Long): Plan?
    suspend fun save(plan: Plan): Long           // saving an active plan deactivates the others
    suspend fun listAll(): List<Plan>
}

interface WorkoutRepository {
    suspend fun startSession(session: WorkoutSession): Long
    suspend fun finishSession(id: Long, finishedAt: Instant, sessionRpe: Int?, notes: String)
    suspend fun getSession(id: Long): WorkoutSession?
    fun observeOpenSession(): Flow<WorkoutSession?>
    fun observeSets(sessionId: Long): Flow<List<SetLog>>
    suspend fun addSet(set: SetLog): Long
    suspend fun supersedeSet(oldId: Long, replacement: SetLog): Long   // never UPDATE in place
    suspend fun sessionsBetween(from: LocalDate, to: LocalDate): List<WorkoutSession>
    suspend fun setsForExercise(exerciseId: String, limitSessions: Int): List<SetLog>
    suspend fun setsForSessions(sessionIds: List<Long>): List<SetLog>
    suspend fun recordAdjustment(adjustment: Adjustment): Long
    fun observeAdjustments(sessionId: Long?): Flow<List<Adjustment>>
}

interface ProgressionRepository {
    suspend fun get(exerciseId: String): ProgressionState?
    suspend fun getAll(): List<ProgressionState>
    suspend fun save(state: ProgressionState)
}

interface MetricsRepository {
    fun observeBodyMetrics(days: Int): Flow<List<BodyMetric>>
    suspend fun bodyMetricsBetween(from: LocalDate, to: LocalDate): List<BodyMetric>
    suspend fun addBodyMetric(metric: BodyMetric): Long
    suspend fun addCardioLog(log: CardioLog): Long
    suspend fun cardioBetween(from: LocalDate, to: LocalDate): List<CardioLog>
    suspend fun addNutritionLog(log: NutritionLog): Long
    suspend fun nutritionBetween(from: LocalDate, to: LocalDate): List<NutritionLog>
    suspend fun addReadiness(check: ReadinessCheck): Long
    suspend fun readinessBetween(from: LocalDate, to: LocalDate): List<ReadinessCheck>
    suspend fun saveWeeklyCheckIn(checkIn: WeeklyCheckIn): Long
    suspend fun latestCheckIns(count: Int): List<WeeklyCheckIn>
    suspend fun activeQuestions(): List<CheckInQuestion>
    suspend fun saveQuestion(question: CheckInQuestion)
}

interface CoachRepository {
    suspend fun openConversation(): ChatConversation?
    suspend fun createConversation(conversation: ChatConversation): Long
    suspend fun updateConversationStatus(id: Long, status: ConversationStatus, endedAt: Instant?)
    fun observeMessages(conversationId: Long): Flow<List<ChatMessage>>
    suspend fun messages(conversationId: Long): List<ChatMessage>
    suspend fun addMessage(message: ChatMessage): Long
    fun observePendingProposals(): Flow<List<Proposal>>
    suspend fun proposals(conversationId: Long): List<Proposal>
    suspend fun addProposal(proposal: Proposal): Long
    suspend fun decideProposal(id: Long, status: ProposalStatus, decidedAt: Instant, resultSummary: String)
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
}

/**
 * Backend-agnostic and deliberately untyped at the boundary (R1 §3.12): the body is the exact
 * Messages-API request JSON; each emitted element is one Messages-API stream event as parsed
 * JSON (the `data:` payload of an SSE event, or the bridge's equivalent). Transport failures and
 * HTTP errors surface as [BackendException]. Typed parsing happens in `core.coach.wire`, so this
 * interface can be frozen in WP-01 without depending on WP-06.
 */
interface ClaudeBackend {
    val mode: BackendMode
    fun stream(body: JsonObject): Flow<JsonObject>
}

class BackendException(val error: BackendError) : Exception(error.toString())

/** Every failure class the UI must handle (R1 §3.8/§3.9); see §6.8 for copy and retry verdicts. */
sealed class BackendError {
    object NotConfigured : BackendError()
    object Offline : BackendError()
    data class Transport(val message: String, val partialOutput: Boolean) : BackendError()
    object Authentication : BackendError()                 // 401
    object Billing : BackendError()                        // 402
    data class Permission(val message: String) : BackendError()   // 403
    data class InvalidRequest(val message: String) : BackendError()   // 400 / 404 / 413
    data class RateLimited(val retryAfterSec: Int?) : BackendError()  // 429 with retry-after
    object SpendCapReached : BackendError()                // 429 enforced_spend_limit_reached
    data class ServerError(val status: Int, val message: String) : BackendError()   // 500 / 504
    data class Overloaded(val midStream: Boolean) : BackendError()    // 529 or event: error overloaded_error
    data class StreamError(val type: String, val message: String) : BackendError()  // other event: error
    data class BridgeUnavailable(val reason: String) : BackendError()
    data class Unknown(val message: String) : BackendError()
}
```

`MessagesRequest` (outbound builder) and the stream accumulator are defined in `core.coach.wire` (§6.7).

---

## 3. Planner and progression algorithm (`:core`)

Everything in this section is deterministic and pure: same inputs → same `Plan`, byte-identical
JSON (this is what makes golden-file tests possible, §8). Every numeric threshold that R3 tags
*weak* or *convention* lives in `PlannerConstants` (§3.1) and every use of one is tagged with its
rule ID in the resulting `Explanation`.

### 3.1 `PlannerConstants` — the single home of every adjustable threshold

```kotlin
package com.gymcoach.core.planner

import com.gymcoach.core.model.Range
import kotlinx.serialization.Serializable

/**
 * Every value below is an R3 planner default, not a study result (R3 §12 item 14). The user can
 * override any of them in Settings > Advanced; overrides are stored as JSON in AppSettings and
 * merged over DEFAULT. Field names carry the rule ID so the Settings screen can show it.
 */
@Serializable
data class PlannerConstants(
    // DEF-01 training-age classes
    val def01NoviceMaxMonths: Int = 6,
    val def01IntermediateMaxMonths: Int = 24,
    val def01LayoffResetMonths: Int = 12,
    // VOL-01/04/05/06/07/08/09 (fractional sets per muscle per week unless stated)
    val vol01Minimum: Int = 4,
    val vol04Novice: Range = Range(8, 12),
    val vol04Intermediate: Range = Range(12, 18),
    val vol04Advanced: Range = Range(15, 25),
    val vol05StrengthPerLift: Range = Range(4, 10),
    val vol06PerSessionDefault: Range = Range(6, 10),
    val vol06PerSessionCap: Int = 12,
    val vol07MinSetsPerAppearance: Int = 2,
    val vol08WeeklySetIncrement: Int = 1,
    val vol08WeeklySetIncrementMax: Int = 2,
    val vol08SorenessGate: Int = 3,                 // add sets only if soreness < 3/5
    val vol09DeficitFractionPct: Range = Range(60, 100),
    // SESS-01/02, WU-01/05/06, REST-01..04, MIX-03
    val sess01MinPerSetHeavyLower: Double = 4.0,
    val sess01MinPerSetHeavyUpper: Double = 3.5,
    val sess01MinPerSetModerateCompound: Double = 2.5,
    val sess01MinPerSetIsolation: Double = 1.75,
    val sess01MinPerSupersetPair: Double = 2.5,
    val sess02CapNovice: Int = 20,
    val sess02CapDefault: Int = 25,
    val sess02CapAdvanced: Int = 32,
    val wu01Minutes: Range = Range(8, 12),
    val wu05TrimmedMinutes: Int = 5,
    val wu06ExtendedRaiseMinutes: Range = Range(8, 10),
    val rest01HypertrophySec: Range = Range(90, 120),
    val rest02HeavySec: Range = Range(180, 300),
    val rest03IsolationSec: Range = Range(60, 90),
    val rest04MainLiftFloorSec: Int = 120,
    val mix03CompoundSharePct: Range = Range(60, 75),
    // RIR-04/05, PROG-02/03/04/05
    val rir04Week1Bonus: Int = 1,
    val rir04LastWeekPenalty: Int = 1,
    val rir05HighRepThreshold: Int = 12,
    val prog02MaxJumpPct: Int = 10,
    val prog02RepExtensionOnBigJump: Int = 2,
    val prog03IntermediateMinDaysBetweenIncrements: Int = 7,
    val prog04UpRirDelta: Int = 2,
    val prog04DownRirDelta: Int = 2,
    val prog04DownLoadPct: Int = 5,
    val prog05NoviceStartRir: Range = Range(3, 4),
    val prog05NoviceStartWeeks: Int = 2,
    // PLAT-01/03, DLD-01..04
    val plat01Sessions: Int = 3,
    val plat03RegressionPct: Double = 5.0,
    val plat03RegressionSessions: Int = 2,
    val dld01NoviceNoScheduledDeloadWeeks: Int = 12,
    val dld01IntermediateCadenceWeeks: Range = Range(6, 8),
    val dld01AdvancedCadenceWeeks: Range = Range(4, 6),
    val dld02SetsPct: Int = 50,
    val dld02LoadDropPct: Range = Range(10, 20),
    val dld02Rir: Range = Range(4, 5),
    val dld03MinTriggers: Int = 2,
    val dld04ReentryLoadPct: Int = 95,
    // RDY-02/03/04/05, SLP-03
    val rdy02GreenMin: Int = 70,
    val rdy02AmberMin: Int = 45,
    val rdy02RedSetsCutPct: Int = 40,
    val rdy03RhrAmberDeltaBpm: Int = 5,
    val rdy03RhrRedDeltaBpm: Int = 10,
    val rdy04SorenessPostponeLevel: Int = 4,
    val rdy05StressLevel: Int = 4,
    val rdy05SetsCutPct: Int = 20,
    val slp03WeeklyAvgFloorHours: Double = 7.0,
    val slp03NightFloorHours: Double = 6.0,
    val slp03SetsCutPct: Range = Range(10, 20),
    // E1RM-02/04
    val e1rm02MaxReps: Int = 10,
    val e1rm04RollingDays: Int = 14,
    // NUT-04/05/06/15/16/17, STP-01/02
    val nut04KcalPerKg: Int = 7700,
    val nut04ClampKcal: Int = 150,
    val nut05DeficitKcal: Range = Range(300, 500),
    val nut05RateBwPctPerWeekX10: Range = Range(5, 10),          // 0.5..1.0 %
    val nut05LeanRateBwPctPerWeekX10: Range = Range(5, 7),
    val nut06SurplusPct: Range = Range(10, 20),
    val nut06RateBwPctPerWeekX100: Range = Range(25, 50),        // 0.25..0.5 %
    val nut15MinDaysBeforeAdjust: Int = 14,
    val nut15NoiseSingleDayPct: Double = 1.0,
    val nut16StepKcal: Range = Range(100, 200),
    val nut17DietBreakAfterWeeks: Int = 8,
    val stp01Under60: Range = Range(7000, 8000),
    val stp01Over60: Range = Range(6000, 7000),
    val stp02FatLossBonus: Int = 2000,
    val stp02Cap: Int = 12000,
    // CAR-05/06/09, TRV-01
    val car05HiitBaseWeeks: Int = 4,
    val car06SeparationHours: Int = 6,
    val car06HypertrophyCapSessions: Int = 3,
    val car06HypertrophyCapMinutes: Int = 150,
    val car09WeeklyDurationIncreasePct: Int = 10,
    val trv01SessionMinutes: Range = Range(25, 40),
    val trv01Sessions: Int = 2,
) {
    companion object {
        val DEFAULT = PlannerConstants()
    }
}
```

### 3.2 Service signatures

```kotlin
data class PlanInput(
    val profile: UserProfile,
    val equipment: SessionEquipment,          // the gym the plan is built for (usually the default gym)
    val history: PlanHistory,
    val today: LocalDate,
    val weekStartsOnMonday: Boolean = true,
)
data class PlanHistory(
    val progression: List<ProgressionState>,
    val recentSessions: List<WorkoutSession>,        // last 8 weeks
    val recentSets: List<SetLog>,
    val previousPlan: Plan?,
    val accustomedWeeklySets: Map<Muscle, Double>?,  // VOL-09 base, from VolumeAccountant over the last 4 weeks
)
data class PlanResult(val plan: Plan, val warnings: List<Explanation>)

class PlanGenerator(kb: KnowledgeBase, c: PlannerConstants, clock: Clock) { fun generate(input: PlanInput): PlanResult }
class SessionPrescriber(kb: KnowledgeBase, c: PlannerConstants) { fun prescribe(input: PrescribeInput): WorkoutPrescription }
class ProgressionEngine(kb: KnowledgeBase, c: PlannerConstants) { fun afterSession(input: AfterSessionInput): ProgressionDecision }
class PlanValidator(kb: KnowledgeBase, c: PlannerConstants) { fun validate(plan: Plan, equipment: SessionEquipment, profile: UserProfile): List<Explanation> /* empty = valid */ }
```

### 3.3 `PlanGenerator.generate` — step by step

1. **Training age** (DEF-01): `NOVICE` if `trainingMonthsConsistent < def01NoviceMaxMonths` or
   `lastLayoffWeeks * 7 >= def01LayoffResetMonths * 30`; `INTERMEDIATE` if ≤ 24 months; else
   `ADVANCED`. The explanation records both inputs.
2. **Session budget and caps** (SESS-01/02): budget sets = f(sessionMinutes) using the per-set
   minute costs (30 min ≈ 8–10, 45 ≈ 14–18, 60 ≈ 20–26, 90 ≈ 30–36) minus warm-up minutes; hard
   cap by training age (20/25/32).
3. **Split** (SPLIT-01, FRQ-01/02/03): table below; each split defines which muscles appear in
   which session (the "muscle map"). A split is accepted only if every muscle with ≥ 10 target
   sets appears ≥ 2×/week (FRQ-01) and no session exceeds its budget (FRQ-03); otherwise fall
   to the next row. `daysPerWeek = 1` is allowed with a permanent warning (FRQ-01).

   | days | split | muscle frequency |
   |---|---|---|
   | 1 | FULL_BODY ×1 | 1× (warning) |
   | 2 | FULL_BODY ×2 | 2× |
   | 3 | FULL_BODY ×3 (novice) / UPPER_LOWER_FULL (intermediate+) | 2–3× |
   | 4 | UPPER_LOWER ×2 | 2× |
   | 5 | UPPER_LOWER_PPL | 2× |
   | 6 | PUSH_PULL_LEGS ×2 (advanced) / UPPER_LOWER ×3 | 2× / 3× |

4. **Weekly volume targets** (VOL-01/04/05/09, AGE-02): hypertrophy/general/fat_loss → lower
   bound of the training-age band for every muscle in the goal's priority list (chest, lats,
   upper_back, quads, hamstrings, glutes, side_delts, biceps, triceps, calves, abs; the remaining
   muscles are "covered by compounds" with a 2–6 direct-set cap, R4 §4.5); strength → 4–10 sets
   per main lift (squat, bench, deadlift, OHP patterns) plus novice-band accessories;
   fat_loss → `max(vol01Minimum, accustomed × vol09DeficitFraction)` when history exists;
   endurance_support → VOL-01/02 floors only; age ≥ 60 → 2–3 sets per exercise, 8–10 exercises.
5. **Pattern coverage** (MIX-02): the week must contain ≥ 1 each of squat, hinge,
   horizontal_push, vertical_push (or incline_push), horizontal_pull, vertical_pull (or a second
   horizontal_pull when no vertical option exists in this gym). Slots are assigned to sessions by
   the split's muscle map (Upper: pushes/pulls; Lower: squat/hinge/lunge; Full: one of each).
6. **Exercise selection per slot** (§3.4) — filtered by equipment (R4 §2.2), difficulty
   (R4 §5.3, `allowAdvancedExercises`), limitations (INJ-01..04 → flags), then ranked.
7. **Isolation slots** (MIX-01/03): add isolation only for muscles under-covered after compounds
   (side/rear delts, biceps/triceps when pull/push volume is low, calves, hamstrings without a
   hinge, abs); keep the compound share within 60–75 % of weekly sets; unilateral chosen when the
   gym forces it or a limitation prefers it (UNI-01), costed at ×1.7 time (UNI-02).
8. **Set allocation** (VOL-06/07): greedy — for each muscle in priority order, spread the target
   over its sessions as evenly as possible (≥ 2 sets per appearance, ≤ 12 per session), crediting
   fractional weights so that a bench press set counts 0.5 to triceps (DEF-05). Stop adding when
   the session budget is hit; report muscles that could not be filled as warnings.
9. **Reps / RIR / rest / zone** (LOAD-03/04, RIR-03/04, REST-01..04, R4 §5.2): reps and RIR from
   the exercise's rep profile × `goal.repProfileKey` (with `rep_overrides`), then RIR-03 class
   floors (never 0 on `requiresSpotterOrSafeties` exercises unless `rack_or_spotter` is satisfied;
   even then floor 1 for bar-over-body lifts); rest by class; `loadZone` from reps (LOAD-04).
   Advanced + strength: alternate a heavy (3–6) and a moderate (6–10) day per main lift
   (PROG-03 undulation).
10. **Order** (ORD-01/02): priority lift first (the goal's main lift for the session), compounds
    before isolation, heaviest first; supersets only for non-competing isolation/machine pairs.
11. **Time fit** (SESS-01, TIME-01): estimate minutes; while over budget apply the TIME-01 ladder
    in order — trim warm-up (WU-05) → drop isolation for muscles already ≥ VOL-01 → convert
    non-competing pairs to supersets → rest 60 s on isolation/machine → one exercise per pattern
    → 2 sets per exercise (never < 1 on the two priority lifts) → move the lowest-priority
    pattern to another session. Each step taken is an Explanation on the plan.
12. **Warm-up** per session via `WarmupBuilder` (§4.1).
13. **Cardio slots** via `CardioPlanner` (§4.3).
14. **Mesocycle length and deload** (DLD-01): novice → `weeks = 6`, `deloadWeekIndex = null`
    (reactive only for the first 12 weeks); intermediate → `weeks = 7` (6–8 cadence, deload =
    last week); advanced or volume at the band's upper bound → `weeks = 5`.
15. **Validate** with `PlanValidator` (§3.10); a failing plan is never returned — the generator
    retries with the next split row, then fails loudly with the violated invariants.

### 3.4 Exercise selection and equipment resolution

`EquipmentResolver.isAvailable(exercise, equipment: SessionEquipment): Availability` implements
R4 §2.2 literally: an exercise is available if at least one `equipment_options[]` entry has every
`all_of` item satisfied, where an item is a concrete equipment id present in the gym, an `any_of`
group with ≥ 1 member present, an `all_of` bundle with all members present, or the `spotter`
session flag. `pullup_bar_any` honours `power_rack.has_pullup_bar`; `rack_or_spotter` is satisfied
by `power_rack`/`half_rack` only when `has_safeties` is true, or by `spotterPresent`. The result
carries the matched option (so the load-type override and note are known) and, when the exercise
is *not* available, the first ranked substitution that is.

Ranking of candidates for a slot (deterministic; ties broken by catalogue order):

| Term | Score | Source |
|---|---|---|
| Same exercise used for this pattern in the previous plan / has a `ProgressionState` | +100 | TRV-04 continuity |
| Matched option is `preferred` | +20 | R4 |
| Load type fits goal: strength → barbell +15; general/fat_loss → machine/dumbbell +5 | | LOAD-02, R4 §5.4 |
| ExRx-anchored muscle map (`†` rows carry tag `exrx_verified`) | +2 | R4 §4.4 |
| Unilateral while time-constrained | −5 | UNI-02 |
| Difficulty above allowed (beginner only for the first two mesocycles; advanced opt-in) | excluded | R4 §5.3 |
| A flag matching a logged limitation (knee → `knee_sensitive`, shoulder → `shoulder_sensitive`, low back → `low_back_sensitive`/`spinal_loading`; elbow/wrist → curl/pressdown volume −50 % instead of exclusion) | excluded / halved | INJ-01..04 |
| `requires_spotter_or_safeties` unsatisfied | allowed, RIR floor 2 + safety note | R4 §5.5 |

### 3.5 `SessionPrescriber.prescribe` — the day-of prescription

Inputs: template, plan, `weekIndex`, today's `SessionEquipment` (may differ from the plan's gym —
hotel), all `ProgressionState`s, today's `ReadinessCheck?`, the last 7 days of readiness/sleep, the
profile, today's date, `availableMinutesToday` (defaults to the plan's), the last session date.

1. **Re-resolve equipment** for every block against today's gym; unavailable → best available
   substitution (same pattern, ranked), `substitutedFrom` set, explanation TRV-04. Dumbbell
   prescriptions above `dumbbells.max_kg` → warning + substitution offered, never a silent load
   change (R4 §2.6).
2. **Layoff** (LAY-01/02): gap since the last session → table row → load %, set %, RIR bonus
   modifiers (age ≥ 65 one row more conservative).
3. **Deload week** (DLD-02) when `weekIndex == deloadWeekIndex`: sets −50 %, compound loads
   −10 % (isolation keeps load, halves sets), RIR 4–5, warm-up unchanged.
4. **Week drift** (RIR-04): week 0 → RIR +1; last non-deload week → RIR −1 (floor 1 on compounds,
   0 on isolation); PROG-05 novice first two weeks 3–4 RIR.
5. **Load** per block, in order of preference: `ProgressionState.currentLoadKg` →
   `E1rmEstimate` × %1RM for the target reps (E1RM-03 table; HIGH confidence only) → "load
   find" (3 sets, start at 50 % of a related exercise's e1RM or the empty bar, adjust by PROG-04)
   → none (bodyweight/band/time). Loads are rounded to the increment resolvable in this gym
   (`IncrementResolver`, R4 §5.4) and the rounding is stated in the explanation.
6. **Readiness** (RDY-02/03/04, SLP-03, RED-03, STR-02): Green → as planned; Amber → +1 RIR,
   −1 set on every exercise after the first two, no increments; Red → −40 % sets (round up), +2
   RIR, heavy barbell sets replaced by moderate machine/dumbbell variants, or the zone-2 +
   mobility alternative offered; sleep < 6 h → at least Amber; two nights < 6 h → no
   MAX_STRENGTH zone; localized soreness ≥ 4 → that muscle's heavy work swapped with another
   session's; stress ≥ 4 on two check-ins → sets −20 %, RIR +1; medical flags → no MAX_STRENGTH
   zone without clearance.
7. **Safety** (R4 §5.5): `requiresSpotterOrSafeties` without `rack_or_spotter` → RIR floor 2 and
   the exercise's safety note as a warning.
8. **Time fit** for `availableMinutesToday` via the TIME-01 ladder (same code as §3.3 step 11).
9. Every modifier is an `Explanation` in `modifiers`; the prescription is frozen into
   `WorkoutSession.prescription` when the session starts.

### 3.6 `ProgressionEngine.afterSession`

Input: the exercise, its `ProgressionState`, the session's working sets for it (WARMUP excluded,
DEF-04/WU-03; AMRAP counts as working), the day's readiness band, training age, gym params, and
`today`. Output: new state + `Adjustment`s + flags.

1. **Effective RIR** (RIR-05): `effRir = rir + 1` when `reps > rir05HighRepThreshold`.
2. **e1RM** (E1RM-01/02/03/04): for each set with `1 ≤ reps ≤ 10`, `rEff = min(reps + (rir ?: 0), 10)`,
   `epley = w × (1 + rEff/30)`, `brzycki = w × 36 / (37 − rEff)`, estimate = mean; confidence
   HIGH for 2–6 effective reps, LOW otherwise (> 10 reps → LOW and excluded from decisions). The
   best of the session updates `bestE1rm` and the 14-day rolling best. Machines/cables track
   `load × reps` only; deadlift estimates carry an "under-estimates" note.
3. **Top hit** (PROG-01): all working sets `reps ≥ repRange.hi` and `effRir ≤ targetRir.hi`.
   **Below**: ≥ 2 sets `reps < repRange.lo`. **Over-RIR** (PROG-04): every set `effRir ≥ targetRir.hi + 2`.
4. **Cadence** (PROG-03): NOVICE → increment on every top hit or over-RIR; INTERMEDIATE →
   top hit/over-RIR and ≥ 7 days since `lastIncrementDate` (or never incremented);
   ADVANCED → top hit on two consecutive sessions (`consecutiveTopHits ≥ 2`).
5. **Increment size** (PROG-02, R4 §5.4): `IncrementResolver.step(exercise, gymParams)` —
   barbell 2.5 kg (5 kg on lower-body barbell lifts while load < 60 kg; 1.0 kg only with
   microplates), dumbbell next pair (`step_kg`), machine one plate (`stack_step_kg`, half plate
   if present), bodyweight +1–2 reps then +2.5 kg, band next level, assisted −5 kg assistance.
   If the step exceeds `prog02MaxJumpPct` of the current load → extend `repRange.hi` by 2 for the
   next session instead and record why. On increment: `currentLoadKg += step`, the reps target
   resets toward `lo`, `consecutiveTopHits = 0`, `lastIncrementDate = today`.
6. **Hold** when below: keep load; `consecutiveTopHits = 0`.
7. **Plateau** (PLAT-01): no e1RM gain and no reps-at-load gain vs the previous session while
   readiness ≥ Amber → `sessionsWithoutGain++`, else reset; ≥ 3 → `PLATEAU` flag with the
   PLAT-02 ladder text (audit sleep/protein → shift rep zone → variation swap → +1–2 sets →
   deload). The ladder is advice for the coach/UI; nothing is applied automatically.
8. **Regression** (PLAT-03): rolling e1RM down ≥ 5 % on two consecutive sessions without an
   external cause → `REGRESSION` flag → feeds `DeloadPolicy.reactive` (DLD-03).
9. **Weekly volume step** (VOL-08) runs once per week from `WeeklyReview`, not here.
10. Every change produces an `Adjustment(before, after, ruleIds, source = PLANNER)`; logged
    numbers are never touched (POL-01).

### 3.7 `E1rm` — the formulas (E1RM-01..03)

```kotlin
object E1rm {
    fun epley(w: Double, r: Int): Double = w * (1.0 + r / 30.0)
    fun brzycki(w: Double, r: Int): Double = w * 36.0 / (37.0 - r)

    fun estimate(w: Double, reps: Int, rir: Int?, date: LocalDate, maxReps: Int = 10): E1rmEstimate? {
        if (reps < 1 || reps > maxReps || w <= 0.0) return null
        val rEff = minOf(reps + (rir ?: 0), maxReps)
        val kg = (epley(w, rEff) + brzycki(w, rEff)) / 2.0
        val confidence = if (rEff in 2..6) E1rmConfidence.HIGH else E1rmConfidence.LOW
        return E1rmEstimate(kg = kg, fromWeightKg = w, fromReps = reps, fromRir = rir, confidence = confidence, date = date)
    }

    /** E1RM-03 %1RM by reps: 1:100 2:94 3:91 4:88 5:86 6:83 8:79 10:75 12:71 15:67; linear between table rows. */
    fun pctOfMaxForReps(reps: Int): Double = TODO("table + linear interpolation; unit-tested at every table row")
}
```

### 3.8 Plateau, deload, layoff policies

- `DeloadPolicy.scheduled(plan, weekIndex)` → DLD-02 modifiers (used by §3.5 step 3).
- `DeloadPolicy.reactive(signals: DeloadSignals)` → true when ≥ 2 of: e1RM/reps down ≥ 5 % on two
  sessions; Red readiness ≥ 3 of the last 7 days; soreness ≥ 4 persisting > 72 h; sleep quality
  ≤ 2 on ≥ 4 nights; motivation ≤ 2 on ≥ 4 days; new joint ache ≥ 3/10 (DLD-03). The result is a
  **suggestion card** on Today ("Deload this week? 2 of 6 triggers: …") — one tap accepts, which
  writes an `Adjustment(source = USER)` and marks the current week as a deload. Automatic
  application was rejected: the user must see the reason first (REQUIREMENTS sacred-content).
- `DeloadPolicy.reentry(weekIndexAfterDeload)` → DLD-04: 95 % loads, lower-bound sets, RIR +1
  for one week.
- `LayoffPolicy.detect(lastSessionDate, today, age)` → LAY-01 row (5–13 d: 95 % / −1 set / RIR+1;
  2–4 wk: 85–90 % / −30 % / RIR+2; 4–8 wk: 75–80 % / −50 % / RIR+2; > 8 wk: 60–70 % or PROG-05
  restart; ≥ 65 y one row more conservative) plus the LAY-03 reassurance text for the UI;
  `LayoffPolicy.afterIllness()` → LAY-02.

### 3.9 `VolumeAccountant` — direct vs fractional (DEF-05, R4 §4.2)

`weekly(sets: List<SetLog>, kb): Map<Muscle, VolumeView>` where
`VolumeView(direct: Double, fractional: Double, sessions: Int)`; only WORKING/AMRAP/DROP sets
count; a unilateral set counts once (UNI-02); `direct` sums weight-1.0 muscles, `fractional`
sums 1.0/0.5/0.25. Aggregates `back = lats ∪ upper_back` (a set counts once at the larger weight),
`delts`, `arms`, `legs` are computed for the Progress screen. RP landmarks (R4 §4.5, verbatim
table in `knowledge/rp_landmarks.json`) are compared **only** with `direct`; the planner's own
bands (VOL-04) use `fractional`.

### 3.10 `PlanValidator` invariants (a failing plan never ships)

1. Every muscle in the goal's priority list has fractional weekly sets ≥ VOL-01 (4).
2. Every session's estimated minutes ≤ `sessionMinutes` + 5.
3. No session exceeds the SESS-02 cap; no muscle exceeds the VOL-06 cap in one session.
4. Every exercise in every block is available in the plan's gym (R4 algebra) and not excluded by
   a limitation.
5. No block prescribes RIR 0 on a `requiresSpotterOrSafeties` exercise; RIR ≥ 2 when
   `rack_or_spotter` is unsatisfied.
6. MIX-02 pattern coverage holds for the week.
7. No two sessions on consecutive days share a heavy (HEAVY/MAX_STRENGTH) lift for the same
   muscle (SPLIT-02) when `daysPerWeek ≤ 5`.
8. Warm-up sets are never counted in `sets`.
9. Cardio slots respect CAR-06 (no HIIT the day before a lower session; running not adjacent).
10. Every block and slot has a non-empty `Explanation.ruleIds`.
11. Rep ranges lie inside the rep profile's goal range (or an explicit override).
12. Plan JSON round-trips through `CoreJson` unchanged.

---

## 4. Warm-up, mobility, cardio, nutrition, supplements, check-ins

### 4.1 Warm-up (`WarmupBuilder`, WU-01..06, MOB-01)

```
raiseMinutes  = if (sessionMinutes <= 45) 0 (WU-05) else if (age >= 40 || earlyMorning || cold) 8..10 (WU-06) else 3..5
drills        = 3..5 from the MOB-07 library, 2 x 8-10 each, matched to the session's patterns
                (squat/hinge -> hips/ankles; pushes -> shoulders/T-spine; pulls -> T-spine/lats) and to
                logged limitations; <= 2-3 min when trimmed (WU-05)
rampSets      = by the first exercise's zone (WU-02):
                HEAVY/MAX (>= 80 %): 40 % x 8, 55 % x 5, 70 % x 3, 80-85 % x 1-2
                MODERATE:            50 % x 8, 70 % x 4-5
                LIGHT / machine:     60 % x 8
                isolation:           1 x 8-10 at 50-60 % (skipped when the muscle is already warm)
                later exercises of an already-warm pattern: 0-1 set at 60-70 %
                one extra ramp set on the first compound when WU-06 applies
rules         = ramp sets are SetKind.WARMUP and never counted (WU-03); no static stretch >= 60 s
                pre-lift (WU-04) - the drill library contains only dynamic drills for the pre-session slot
```

Percentages are of the block's `targetLoadKg`; when no target exists (load find) the ramp is
"empty bar / light" with reps only. Total warm-up time is stated on the Today card.

### 4.2 Mobility (MOB-02..07)

`MobilityPlanner.weekly(profile)` produces, per limited joint the user logged (or none), a chronic
dose: 5–10 min/week, 30–60 s holds, ≥ 5 days (MOB-02), scheduled **after** sessions or as a
separate slot (MOB-04); full-ROM working sets credit 30 s per set toward the joint's dose (MOB-03,
weak) and the Progress screen shows "mobility minutes: credited vs done". Foam rolling appears as
an optional 30–60 s pre-session item only when the user marks it helpful (MOB-05); cool-down is
an optional 5–10 min toggle (MOB-06). The drill library (`knowledge/mobility_drills.json`) maps
MOB-07 rows to exercise ids from R4 (`hip_90_90`, `couch_stretch`, `worlds_greatest_stretch`,
`ankle_dorsiflexion_rock`, `thoracic_rotation`, `cat_cow`, `shoulder_pass_through`, `wall_slide`,
`band_pull_apart`, `band_external_rotation`, `foam_rolling`).

### 4.3 Cardio (`CardioPlanner`, CAR-01..09)

- Weekly prescription by goal (CAR-07): general_fitness 2 × 30–45 min zone 2 + 1 HIIT;
  hypertrophy/strength 2 × 20–30 min zone 2 (HIIT optional, never before a leg day); fat_loss
  150–250 min/week moderate, rising toward > 250, plus the STP-02 step target; endurance_support
  → the user's own plan is primary and lifting is placed ≥ 6 h from key sessions.
- Placement (CAR-06, ORD-03): a different day from lifting when `daysPerWeek ≤ 4`; otherwise the
  same day after lifting with ≥ 6 h separation flagged as "better"; no hard running within 24 h
  before a lower session; cycling/rowing/elliptical preferred adjacent to leg days (CAR-08
  adjacency table in `knowledge/cardio_modalities.json`); cap ≤ 3 sessions / ≤ 150 min beyond
  the health floor for hypertrophy/strength unless the user overrides.
- Intensity (CAR-02/03/04): `HRmax = 208 − 0.7 × age` (Tanaka), Karvonen with the 7-day resting
  HR mean when available; zone 2 = 60–70 % HRmax / 40–59 % HRR / RPE 11–13 + talk test; vigorous
  77–95 % / 60–89 % / RPE 14–17. The session card shows two of the three criteria and the talk
  test sentence.
- HIIT menu (CAR-05): 4×4 @ 90–95 % HRmax with 3 min @ 70 %; 10×1 min @ 90 % with 60–75 s easy;
  SIT 4–6 × 30 s all-out / 4 min (advanced, healthy only). Gated by ≥ 4 weeks of zone-2 base
  (`car05HiitBaseWeeks`) and by RED-03 (medical flags → "clearance required" instead of a
  prescription).
- Progression (CAR-09): +10 % duration/week toward the target; HIIT adds one interval before
  raising intensity.
- Modality resolution uses the same `EquipmentResolver` (treadmill, bike, rower, … are exercises
  in R4 batch 6); `outdoor_run_walk` and `jump_rope` need no gym.

### 4.4 Nutrition (`EnergyEstimator`, `TdeeAdapter`, `WeightTrend`, `MacroTargets`, NUT-01..17)

- **REE** (NUT-04): Mifflin–St Jeor from the latest body weight, height, age, sex.
  **Activity factor**: 1.2 / 1.375 / 1.55 / 1.725 / 1.9 chosen from `daysPerWeek`, the 14-day
  step average and a "physical job" toggle (weak, shown as an assumption).
- **Adaptive TDEE** (NUT-04): every 14 days, when ≥ 10 daily weigh-ins and ≥ 10 kcal logs exist
  in the window, `observedRate = Δ(MA7) per week`, `expectedRate = (meanIntake − TDEE) × 7 / 7700`,
  `TDEE += clamp((expectedRate − observedRate) × 7700 / 7, ±150)`; each step is a
  `TdeeAdjustment` with its inputs. Without kcal logs the ordinal `mealsHeaviness`/`proteinTargetHit`
  inputs are shown but no correction is computed (NUT-14 (5)).
- **Targets** (NUT-01/05/06/07/08/09/10/11): protein 1.6–2.2 g/kg (deficit: 2.3–3.1 g/kg FFM when
  body-fat % is known, else 2.0–2.6 g/kg BW lean / 1.8–2.2 otherwise; ≥ 65 y floor 1.2; recomp
  ≥ 2.2); energy delta by goal: fat_loss −300…−500 kcal targeting 0.5–1.0 %BW/week (0.5–0.7 when
  lean or strength must be preserved), hypertrophy/strength +10–20 % (advanced +200–300 kcal;
  rate 0.25–0.5 %/week, advanced 0.1–0.25), recomp (novice / returning / high BF with a
  hypertrophy goal) maintenance to −200; carbs ≥ 3 g/kg (3–5 for ~1 h/day, 5–7 with ≥ 4 cardio
  sessions, 6–10 endurance_support); fat 0.5–1.5 g/kg with a 0.5 floor, remaining energy to carbs;
  fibre 14 g/1,000 kcal (38/25 g floors); water 3.7/2.7 L.
- **Trend** (NUT-15/16/17): MA7 of morning weigh-ins; a single-day change ≥ 1 % flagged as noise;
  no adjustment before 14 days of data; every 14 days the 14-day change in MA7 is compared with
  the goal band → ±100–200 kcal step, shown with the trigger values; deficits > 8 weeks or hunger
  ≥ 4/5 for 2 weeks (Q15) → diet-break suggestion. Alcohol ≥ 3 drinks in an evening (Q9 /
  `alcoholDrinks`) → next-day readiness at least Amber (NUT-13).
- Peri-workout, meal distribution and travel heuristics (NUT-02/03/12/14) are coach knowledge:
  they live in the system prompt's knowledge digest and in `knowledge/nutrition_tips.json` for the
  Nutrition screen; nothing is computed from them.

### 4.5 Supplements (`SupplementAdvisor`, SUP-01..12)

`advise(profile, goal, bedtime, sessionTime, bodyWeightKg, medicalFlags, fishServingsPerWeek?,
vitDRiskFactors)` returns tiered `SupplementAdvice` items, each with dose text, timing, evidence
tag and the SUP-12 caveat attached: Tier A creatine 3–5 g/day (0.1 g/kg; loading optional;
+1–2 kg water flagged into NUT-15; kidney flag → "refer" instead of a dose), caffeine 3–6 mg/kg
60 min pre-session with a computed cut-off `bedtime − 9 h` (≤ 100 mg) / `bedtime − 13 h`
(≥ 200 mg) and a 400 mg/day ceiling (EFSA value not verified live in R3 — flagged in the copy),
protein powder as food, vitamin D only with ≥ 2 risk factors or a low test, electrolytes only for
> 1–2 h/heat; Tier B beta-alanine, citrulline, melatonin (travel only, legal caveat by locale),
omega-3 (only when fish < 2 servings/week), multivitamin (restriction only); Tier C the do-not
list verbatim from R3 SUP-11 with reasons. The Nutrition screen shows the priority-order sentence
(protein → calories → sleep → consistency → creatine/caffeine) above the list.

### 4.6 Check-ins, readiness, red flags (`CheckInBank`, `CheckInEvaluator`, `ReadinessScorer`, `RedFlags`)

- **Daily readiness** (RDY-01): six 1–5 items + sleep hours + optional resting HR, ≤ 30 s, scored
  exactly per the R3 table; missing optional inputs renormalise; Green ≥ 70 / Amber 45–69 / Red < 45
  (RDY-02); resting HR ≥ +5 bpm over the 7-day mean on 2 days → Amber, ≥ +10 or illness → Red
  (RDY-03). The score, band and contributing items are the `Explanation`.
- **Weekly check-in** (CHK-01): the 15-question bank; questions with data sources (Q4 weight
  trend, Q5 steps, Q8 sessions completed) are pre-answered from logs; at most 8 shown, rotating
  by "not asked in the last 2 weeks" then by trigger relevance (e.g. Q10 travel days always in a
  travel-heavy month); coach-added questions (`SET_CHECKIN_QUESTION` proposals) are appended with
  an expiry. `CheckInEvaluator` maps answers to explanations and *pending modifiers* consumed by
  the next `SessionPrescriber` run (RDY-05, SLP-03, VOL-08 pause, TIME/SPLIT re-plan suggestion,
  TRV-01 plan suggestion, NUT-16, SUP-02 timing).
- **Reminders**: no notifications in v1 (no WorkManager, no `POST_NOTIFICATIONS`); the Today
  screen shows "Weekly check-in due" from Sunday until answered, and the coach sees
  `pending_checkin: true` in its context.
- **Red flags** (RED-01/02/03): the fixed symptom list is shown once during onboarding and behind a
  persistent "I have symptoms" button on the workout screen; any selection ends the session,
  records the event, and shows the refer/emergency text — no training advice. Pain > 3/10
  persisting > 7–10 days (Q7) → RED-02 text. Medical flags gate HIIT, MAX_STRENGTH sets and
  supplement doses behind a "clearance confirmed" toggle (RED-03). The app never diagnoses
  (POL-02): copy is reviewed against that rule in §8.

### 4.7 Sleep, steps, stress (SLP-01..03, STP-01/02, STR-01/02)

A weekly sleep average < 7 h → sets −10–20 % and RIR +1 on heavy compounds until ≥ 7 h; last
night < 6 h → at least Amber and the Today card suggests moving the session to the morning; two
consecutive nights < 6 h → no MAX_STRENGTH zone. Step target 7,000–8,000 (< 60 y) or 6,000–7,000
(≥ 60 y); in fat_loss `max(target, baseline + 2,000)` capped at 12,000. Stress ≥ 4/5 for two
consecutive check-ins → RDY-05 modifiers, zone-2 walks offered, no new HIIT (STR-02).

---

## 5. UX — screens, navigation, key interactions

### 5.1 Navigation

Single `ComponentActivity`, one `NavHost`, a bottom bar with five destinations (Today, Plan,
Coach, Progress, More). Routes are `@Serializable` objects/classes (Navigation 2.10 type-safe
API — `composable<T>`, `navController.navigate(T)`, `toRoute<T>()`, verified 2026-09-05):

```kotlin
package com.gymcoach.app.ui.nav

import kotlinx.serialization.Serializable

@Serializable object OnboardingRoute
@Serializable object TodayRoute
@Serializable data class ReadinessRoute(val templateId: String)
@Serializable data class WorkoutRoute(val sessionId: Long)
@Serializable data class WorkoutSummaryRoute(val sessionId: Long)
@Serializable object PlanRoute
@Serializable data class PlanSessionRoute(val templateId: String)
@Serializable object CoachRoute
@Serializable object ProgressRoute
@Serializable data class ExerciseProgressRoute(val exerciseId: String)
@Serializable object MoreRoute
@Serializable object ProfileRoute
@Serializable object GymsRoute
@Serializable data class GymEditRoute(val gymId: Long)          // 0 = new gym
@Serializable object LibraryRoute
@Serializable data class ExerciseDetailRoute(val exerciseId: String)
@Serializable object CheckInRoute
@Serializable object NutritionRoute
@Serializable object SettingsRoute
@Serializable object BackendSettingsRoute
@Serializable object UsageRoute
@Serializable object AdvancedConstantsRoute
```

Start destination: `OnboardingRoute` until `AppSettings.onboardingDone`, then `TodayRoute`.
Deep links: none in v1.

### 5.2 Screen list

| Screen (route) | Purpose | Key interactions | ViewModel inputs |
|---|---|---|---|
| Onboarding | Profile essentials, first gym from a preset, goal, days/minutes, limitations, medical flags, red-flag list acknowledgement; ends with "Generate plan" | Stepper; every number field has a unit label; medical flags explain what they gate | Profile/Gym/Plan repos, `PlanGenerator` |
| Today | The hub: today's session card (template, est. minutes, warm-up summary, modifiers preview), gym chip ("Home gym ▾", travel-kit and spotter toggles), readiness prompt, cardio slot, pending coach proposals, deload suggestion card, check-in due badge, last-session adjustments | Start session → Readiness → Workout; switch gym; skip readiness; accept deload | Plan, Gyms, Metrics, `SessionPrescriber`, `DeloadPolicy`, `LayoffPolicy` |
| Readiness | 6 sliders + sleep hours + optional resting HR; shows score, band and the modifiers that will apply | "Use" / "Skip today" | `ReadinessScorer` |
| Workout | Prescription blocks with target load/reps/RIR/rest; per-set rows: weight (unit label), reps, RIR chips 0–5, tick; "as prescribed" fills targets; rest timer auto-starts; swap sheet (ranked substitutions available in this gym); add set; notes; "I have symptoms" button; finish | Logging never edits a saved set — editing creates a superseding row (history visible via long-press) | Workout repo, `ProgressionEngine` on finish |
| Workout summary | Sets logged, volume per muscle (direct + fractional), e1RM changes, every `Adjustment` made with rule IDs, session RPE input, next-session preview | Session RPE 0–10 | Workout/Progression repos |
| Plan | Mesocycle overview (week n of N, deload week marked), sessions list, weekly volume vs targets per muscle, cardio slots, explanations; "Regenerate" with a diff preview | Tap session → Plan session detail; regenerate → confirm | Plan repo, `PlanGenerator`, `VolumeAccountant` |
| Plan session | Blocks with alternates, warm-up plan, rationale per block | Swap alternate (writes an `Adjustment(source = USER)`) | Plan repo |
| Coach | Chat transcript, streaming text, proposal cards (Apply / Reject / Later), "context sent" sheet (what the model received), backend/model/effort badge, spend this month, offline banner | Send; apply/reject; new conversation; view old conversations (read-only) | `CoachTurnService`, Coach repo, `ProposalApplier` |
| Progress | Body-weight MA7 chart, e1RM per lift, weekly volume per muscle (direct vs fractional vs RP landmark), readiness trend, cardio minutes vs floor, steps, mobility minutes | Time range chips; tap lift → exercise progress | Metrics/Workout repos, `ProgressSeries` |
| Exercise progress | Load × reps history, e1RM trend, progression state, plateau flag and ladder text | — | Progression repo |
| More | Links: Profile, Gyms, Library, Check-in, Nutrition, Settings, Usage | — | — |
| Profile | Edit profile; shows derived training age and what changed in the plan as a consequence | Save → "Plan affected — regenerate?" | Profile repo |
| Gyms / Gym edit | Named gym sets; start from preset; equipment picker grouped by category with search (aliases DE/EN); per-item params (dumbbell max/step, plate min, rack safeties); archive | Duplicate gym; set default | Gym repo, knowledge |
| Library / Exercise detail | 156 exercises, filter by pattern/muscle/equipment/available-in-gym; detail: description, setup, cues, mistakes, safety (flag-driven), muscles with weights, substitutions with rationale, sources; "content pending" when status is `todo` | — | Knowledge |
| Check-in | Weekly questions (≤ 8), pre-filled auto answers, shows what each answer triggers | Submit → explanations list | Metrics repo, `CheckInEvaluator` |
| Nutrition | Targets (kcal/protein/carbs/fat/fibre/water) with the energy model and its assumptions, weigh-in entry, weight trend, kcal/protein quick log, "protein hit?" toggle, supplement tiers with the SUP-12 caveat, travel tips | Log; view adjustments | Metrics repo, `EnergyEstimator`, `MacroTargets`, `SupplementAdvisor` |
| Settings | Units, week start, theme; links to Backend, Advanced constants | — | Settings repo |
| Backend settings | Backend choice with the R1 §2.5 copy verbatim; API key entry (masked, validated, "stored in this device's keystore"), model, effort, monthly budget; bridge URL + secret (behind "Show experimental"); "Test connection" | Save key → keystore; test → one `max_tokens: 32` request | Settings repo, `ApiKeyStore`, backend |
| Usage | Spend per day/conversation, tokens, cache hit rate, budget bar | — | Coach repo, `SpendMeter` |
| Advanced constants | Every `PlannerConstants` field with its rule ID, default and current value; reset | Edit → stored as overrides JSON | Settings repo |

### 5.3 Key flows

1. **First run**: Onboarding → plan generated → Today shows session 1 with the warm-up and
   "load find" markers on every exercise (no history yet).
2. **Training day**: Today → (Readiness) → prescription preview lists every modifier with its rule
   ID ("Amber: +1 RIR, −1 set after the first two — RDY-02") → Workout → summary → the
   progression decisions and adjustments are listed; nothing is applied silently.
3. **New gym on the road**: Today → gym chip → "New gym from Hotel preset" → tick what exists →
   the session is re-prescribed with substitutions marked "was: barbell_bench_press → now:
   dumbbell_bench_press (TRV-04)"; dumbbell-max conflicts show as warnings with a one-tap
   substitution.
4. **Coach change**: "Swap Bulgarian split squats for leg press, my knee hurts" → proposal card
   (from/to, scope, rationale, rule IDs) → Apply → `Adjustment(source = COACH)` → Today updates;
   the next message to Claude carries `tool_result: applied`.
5. **Weekly**: Sunday badge → Check-in → triggered modifiers explained → they show up on the next
   prescription preview.

### 5.4 Sacred-content display rules

- A logged value is rendered from `EnteredWeight.entered` + `unit` exactly as typed; the kg
  conversion is shown only when the display unit differs, prefixed "≈".
- Derived numbers (e1RM, TDEE, MA7, targets) carry an info chip → formula ID and inputs.
- Adjustments are listed wherever they applied (summary, plan, Today) with rule IDs; the
  `Adjustment` table is browsable from Progress → "Changes".
- Editing a logged set never overwrites: the old row gets `supersededById`; the UI shows the
  current row and offers history.

### 5.5 Units

Metric default; the imperial toggle changes display and *input* units. Input parsing keeps the
typed number and unit (`EnteredWeight`), so 135 lb stays 135 lb forever and is 61.23 kg only for
computation. Height similarly (`EnteredLength`).

### 5.6 Offline and error states

Everything except the Coach works with airplane mode on. The Coach screen shows a persistent
"Offline — training features work; chat needs a connection" banner and disables Send when
`ConnectivityManager` reports no validated network (checked at send time, not observed
continuously — one less callback API to get wrong). Errors render through one `ErrorBanner`
composable fed by `BackendError` copy (§6.8).

### 5.7 Visual system

Material 3 with dynamic colour on Android 12+ and a fixed fallback palette; large numeric fields
(min 48 dp touch targets, `KeyboardType.Decimal`); dark theme follows the system. Charts are a
single `LineChart` composable on `Canvas` (`drawLine`, `drawPath`, `drawCircle`,
`rememberTextMeasurer` + `drawText`) fed by `ChartSeries` computed in `:core` (R2 §6.2).

---

## 6. Claude coach integration

### 6.1 Backend options — honest to R1

| Option | Status | Ships in v1 |
|---|---|---|
| **Direct Anthropic Messages API with a Console API key** held in the device keystore | Compliant (R1 §1.10 item 2); ≈ $20/month Opus 5 or ≈ $8 Sonnet 5 at 30 turns/day before thinking overhead; realistic band $20–45 / $8–17 (R1 §2.4) | **Yes — default** |
| **Home bridge**: unmodified `claude -p` on Johannes' PC under his own subscription login, reached over Tailscale | Tolerated grey zone, revocable without notice (R1 §1.10 item 3); Anthropic cut off third-party harnesses once already (2026-04-04) | Adapter + protocol + settings copy ship; the PC-side server is a separate later deliverable; off by default, behind "Show experimental" |
| Any claude.ai / Claude Code OAuth token on the phone (`/login`, `claude setup-token`, `CLAUDE_CODE_OAUTH_TOKEN`) | **Prohibited** (R1 §1.10 item 1) | **Never.** No input field; a pasted key that does not start with `sk-ant-api` is rejected with: "Only Claude Console API keys (sk-ant-api…) can be used. GymCoach cannot use claude.ai or Claude Code login tokens." |
| Remote Control / MCP escape hatch | Compliant but outside GymCoach | Documented only |

The Backend settings screen shows the R1 §2.5 copy verbatim (three paragraphs: API key
recommended; Home bridge experimental with the quoted policy sentences; the "GymCoach will never
ask for … a claude.ai login" line). The copy is a string resource reviewed word-for-word against
R1 in §8.

### 6.2 Request profile (R1 §2.4, §3.5; cross-checked against the claude-api reference on 2026-09-05)

| Field | Value | Note |
|---|---|---|
| endpoint | `POST https://api.anthropic.com/v1/messages` | |
| headers | `x-api-key`, `anthropic-version: 2023-06-01`, `content-type: application/json`, `accept: text/event-stream` | no `anthropic-beta` in v1 |
| `model` | `claude-opus-5` default; `claude-sonnet-5` selectable | ids without date suffix |
| `max_tokens` | 4096 | thinking counts toward it |
| `stream` | `true` always | |
| `system` | `[ {type: text, text: <byte-stable prompt>, cache_control: {type: ephemeral}} ]` | ≥ 512 tokens so it caches on Opus 5 (1,024 on Sonnet 5) |
| `tools` | the six contract tools, `strict: true` | rendered before `system` in the cache prefix |
| `tool_choice` | `{type: auto}` | never changed within a conversation (cache invalidator) |
| `thinking` | `{type: adaptive}` | display omitted; never `enabled`/`budget_tokens` (400) |
| `output_config` | `{effort: low}` or `medium` | frozen per conversation (cache + preserved-thinking rules) |
| top-level `cache_control` | `{type: ephemeral}` | automatic breakpoints for the growing history |
| `metadata.user_id` | opaque UUID generated once, stored in settings | |
| never sent | `temperature`, `top_p`, `top_k`, assistant prefill, `stop_sequences` | 400 on Opus 5 / Sonnet 5 |

### 6.3 System prompt — byte-stable, versioned, cached

Stored as `core/src/main/resources/gymcoach/coach/system_prompt_v1.txt` (~3,000 tokens). Its
SHA-256 is recorded on every conversation; a changed hash (app update) forces a new conversation
(never a mid-conversation `system` change — §6.10). A unit test asserts the file contains no date,
time, name or number that varies per user (all of that goes into the user turn).

Outline (section → approximate tokens):

1. **Role and scope** (150) — evidence-based strength & conditioning coach inside an offline
   training app; the app, not the coach, is the system of record.
2. **Hard rules** (350) — never state or "correct" a logged number; changes only via the proposal
   tools and only after user confirmation; every numeric recommendation names its rule ID from
   the digest; no diagnosis — RED-01 symptoms → stop and refer, verbatim hand-off sentence;
   supplement mentions carry the SUP-12 caveat; say when something is unknown; phone-length
   answers (≤ 120 words unless asked); metric unless `context.units = imperial`; do not repeat
   the context back; do not invent exercise ids — use only `available_exercises`.
3. **How context arrives** (200) — `<context schema="1">` JSON in the first user message
   (fields listed), `<delta schema="1">` in later ones (fields listed), `<summary>` when a
   conversation was compacted; `today` is in the context, not in this prompt.
4. **Tool rules** (300) — one tool call per distinct change; `rationale` must cite rule IDs; do
   not re-propose a pending proposal; sets stay within VOL-06/SESS-02; swaps must keep the
   pattern unless the user asks otherwise; a `tool_result` of `rejected` means do not retry the
   same change; `pending` means the user has not decided.
5. **Knowledge digest** (1,800) — the R3 §10 table condensed (rule ID → statement, one line
   each), the R4 counting convention (fractional 1.0/0.5/0.25; RP landmarks are direct-count only),
   the TRV/TIME/INJ/LAY tables, readiness bands, nutrition numbers, supplement tiers.
6. **Style** (100) — direct, numbers first, one question at a time when clarifying.

### 6.4 Context assembly (`CoachContextBuilder`) — append-only

- **Snapshot** (first user message of a conversation, target 2,000–2,500 tokens, ~4 chars/token
  estimate): `today`, `units`, profile (age, sex, height, weight MA7, training age, goal,
  days/minutes, limitations, medical flags), active gym (name + equipment ids + key params),
  `available_exercises` (ids available in the active gym), plan summary (split, week n/N, deload
  week, per session: exercise id, sets × reps @ RIR, current load), last 14 days of sessions
  (per session: date, per exercise best set `w×r@RIR`, adjustments), readiness last 7 days,
  weight trend (14-day MA7 delta), nutrition targets, open check-in answers, pending proposals,
  `pending_checkin`. Oldest sessions are dropped first when over budget and the truncation is
  stated in the JSON.
- **Delta** (every later user message, as a text block placed after any `tool_result` blocks and
  before the user's text): sessions logged since the last delta, proposals applied/rejected,
  profile/gym/plan changes, new readiness, `today` if it changed. Deltas are small (often < 100
  tokens) and are never removed later — the transcript stays append-only.
- **Compacted start**: `<summary>` (≤ 600 tokens, produced by §6.10) followed by a fresh snapshot.

### 6.5 Tool / proposal contract v1 (`ProposalContract`)

Six strict tools, one per proposal kind (flat schemas — no `anyOf` needed). Common fields:
`contract_version` (integer, `enum: [1]`) and `rationale` (string; must cite rule IDs). The
schemas use only the keywords the strict subset allows (verified 2026-09-05: types, `enum` with
strings/integers, `required` as a subset, `additionalProperties: false`, `format: date`; no
`minimum`/`maximum`/`pattern`/`minLength`) — numeric bounds are enforced in `ProposalContract.validate`
and returned as `is_error` tool results when violated.

| Tool | Input fields (required in bold) | Applies to |
|---|---|---|
| `propose_swap_exercise` | **contract_version**, **session_id**, **from_exercise_id**, **to_exercise_id**, **scope** `this_session|rest_of_plan`, **rationale** | plan document; `Adjustment(COACH)` |
| `propose_change_volume` | **contract_version**, **target_type** `exercise|muscle`, **target_id**, session_id, **sets_delta** (integer; validated −3..+3 and caps), new_reps_lo, new_reps_hi, new_rir_lo, new_rir_hi, **scope**, **rationale** | plan document |
| `propose_add_cardio_day` | **contract_version**, **day_index** (integer enum 0–6), **kind** `zone2|walk|hiit_4x4|hiit_10x1|sit`, **minutes** (integer; validated 10–90), modality_id, **rationale** | plan cardio slots; CAR-06 checked |
| `propose_change_goal` | **contract_version**, **new_goal** (enum of the five goals), **regenerate_plan** (boolean), **rationale** | profile + optional plan regeneration (shown as a diff before apply) |
| `propose_set_checkin_question` | **contract_version**, **question_text**, **answer_type** `number|scale5|yesno|text`, **weeks_active** (integer; validated 1–8), **rationale** | check-in question bank |
| `propose_schedule_deload` | **contract_version**, **week** `this_week|next_week`, **rationale** | plan (marks the deload week; DLD-02 applies) |

Full JSON for all six is in Appendix B; the Kotlin constants live in `ProposalSchemas.kt` and a
unit test parses each, checks the strict-subset rule list above, and round-trips a sample input
through `ProposalContract.parse`.

Versioning rules: any breaking change to a schema or to `ProposalApplier` semantics bumps
`contract_version` (and the file name `ProposalSchemas.kt` keeps the old schema for reading old
proposals); tool names never carry a version; an input with an unknown version is answered with
`is_error` and the message "unsupported contract_version"; the app never applies a proposal
whose payload fails `validate` (exercise unknown or unavailable in the active gym, session id not
in the active plan, caps exceeded, goal enum unknown, question longer than 200 chars).

`ProposalApplier.apply(state: ApplyState, proposal: ParsedProposal): ApplyResult` is a pure
function over `(plan, profile, questions, activeGym, knowledge)` returning the new documents plus
`Adjustment`s and a one-line `resultSummary`; the ViewModel persists the result and then marks the
proposal APPLIED. Proposals never touch `SetLog`s (POL-01).

### 6.6 Turn orchestration (`CoachTurnService`)

```
send(userText):
  1. conv = coach.openConversation() ?: newConversation()          // §6.10 lifecycle
  2. content = []
     if conv.status == AWAITING_TOOL_RESULTS:
         for each proposal of the last assistant message (in tool_use order):
             content += tool_result(tool_use_id, text = "applied: <summary>" | "rejected by user" |
                                    "pending: the user has not decided yet; do not re-propose unless asked",
                                    is_error = status == FAILED)
     content += text(<context> snapshot | <delta>)
     content += text(userText)
  3. body = RequestBuilder.build(settings, systemPrompt, tools, history = committed messages, newUser = content)
  4. events = backend(mode).stream(body)  ->  StreamAccumulator  ->  Flow<TurnEvent>
       TextDelta(text) | ToolUseStarted(name) | ToolUseComplete(parsedProposalOrError) | Usage(usage) |
       Done(stopReason, stopDetails) | Failed(BackendError)
  5. on Done(end_turn | tool_use | max_tokens):
       persist user ChatMessage(contentJson = content) and assistant ChatMessage(contentJson = accumulated
       content array VERBATIM, thinking/signature/redacted blocks included); create Proposal rows (PENDING)
       for tool_use blocks; conv.status = AWAITING_TOOL_RESULTS if any, else OPEN; SpendRecord from usage.
     on Done(refusal): discard partial output; persist nothing; surface category + "retry on Sonnet 5?".
     on Failed: persist nothing (the model never saw this turn); keep the draft text for retry.
  6. Proposal decisions (Apply/Reject/Later) are independent of sending: Apply -> ProposalApplier ->
     repositories -> status APPLIED (or FAILED with the validation message); Reject -> REJECTED;
     nothing is sent until the next user message (R1 §3.5 pattern 1).
```

Rules enforced by the builder and covered by tests: `tool_result` blocks come first and one per
`tool_use`; the assistant content array is echoed unchanged; the request never ends with an
assistant message; `max_tokens` → the UI shows "truncated" and offers "continue" (which is just
a normal user message "continue"); consecutive user messages are allowed (a failed turn leaves no
trace, so this happens only when the user sends twice while offline detection lagged).

### 6.7 Streaming: DTOs, SSE parser, accumulator (all in `:core`, all unit-tested)

**Outbound** — typed builder (`@Serializable`, explicit nulls off):

```kotlin
@Serializable data class CacheControl(val type: String = "ephemeral")
@Serializable data class SystemBlock(val type: String = "text", val text: String, @SerialName("cache_control") val cacheControl: CacheControl? = null)
@Serializable data class ToolDefinition(val name: String, val description: String, val strict: Boolean = true, @SerialName("input_schema") val inputSchema: JsonObject)
@Serializable data class MessageParam(val role: String, val content: JsonArray)     // content echoed verbatim
@Serializable data class MessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val stream: Boolean = true,
    val system: List<SystemBlock>,
    val tools: List<ToolDefinition>,
    @SerialName("tool_choice") val toolChoice: JsonObject,
    val thinking: JsonObject,
    @SerialName("output_config") val outputConfig: JsonObject,
    @SerialName("cache_control") val cacheControl: CacheControl = CacheControl(),
    val metadata: JsonObject? = null,
    val messages: List<MessageParam>,
)
```

**Inbound** — deliberately *not* typed with polymorphic serialization (an unknown `type` would
throw). Each SSE `data:` payload is parsed to `JsonObject`; `StreamAccumulator.accept(event)`
dispatches on `event["type"].jsonPrimitive.content`:

| type | action |
|---|---|
| `message_start` | record `message.id`, `model`, initial `usage` |
| `content_block_start` | open block at `index`; keep the raw `content_block` object |
| `content_block_delta` → `text_delta` | append to the text block; emit `TextDelta` |
| `content_block_delta` → `input_json_delta` | append `partial_json` to the tool block's buffer |
| `content_block_delta` → `thinking_delta` / `signature_delta` | append to the thinking block / set `signature` |
| `content_block_stop` | close block; for `tool_use` parse the buffer (empty buffer → `{}`) into `input`; emit `ToolUseComplete` |
| `message_delta` | merge `stop_reason`, `stop_details`, cumulative `usage` |
| `message_stop` | finish; build the verbatim content array (thinking blocks with their signatures, `redacted_thinking` untouched) |
| `ping` | ignore |
| `error` | terminal → `BackendError.Overloaded(midStream = true)` for `overloaded_error`, else `StreamError` |
| anything else | count + ignore (the docs promise new event types) |

`SseParser.feed(chunk: String): List<SseEvent>` is incremental (holds a partial line across
chunks), handles `\n` and `\r\n`, joins multi-line `data:`, ignores `:` comments and `id:`/`retry:`,
dispatches on a blank line. `DirectApiBackend` reads the OkHttp body with `source.readUtf8Line()`
in a loop and feeds the parser; the bridge backend feeds the same parser. A fuzz test splits every
R1 fixture at every byte offset and asserts identical event lists.

**Bridge mapping** (R1 §3.12): the PC-side server converts Claude Code `stream-json` lines
(`{"type":"stream_event","event":{...}}` → inner event; final `result` → synthesized
`message_delta` + `message_stop`; `structured_output` → synthesized `tool_use` block) into the
same SSE stream, so the phone-side adapter is transport only.

### 6.8 Error handling, retry, offline (R1 §3.9 mapped to `BackendError`)

| Condition | `BackendError` | Retry (automatic) | UI copy / action |
|---|---|---|---|
| no key stored | NotConfigured | no | "Add an API key in Settings → Backend" (link) |
| no validated network | Offline | no | banner; Send disabled |
| connect/read timeout, IOException before `message_stop` | Transport(partialOutput) | 2× exponential (1 s, 4 s) when no output was received; otherwise no | "Connection lost. Retry?" — draft kept |
| 400 (incl. self-set spend limit) | InvalidRequest(message) | no | show message; "Report" copies request-id |
| 401 | Authentication | no | "Key rejected or expired — enter a new key" |
| 402 | Billing | no | "Console credits exhausted — top up at platform.claude.com" |
| 403 | Permission | no | show message |
| 404 | InvalidRequest | no | "Model not available for this key" |
| 413 | InvalidRequest | no | "Message too large" (should never happen with the 2.5k-token context) |
| 429 with `retry-after` | RateLimited(sec) | wait `retry-after` (≤ 30 s) once | "Rate limited, retrying in n s" |
| 429 with `error.details.error_code == enforced_spend_limit_reached` | SpendCapReached | no | "Console spend cap reached" |
| 500 / 504 | ServerError | 2× backoff | "Anthropic error, retrying…" |
| 529 or mid-stream `overloaded_error` | Overloaded | 2× backoff (only if no output yet) | "Overloaded — try again in a minute" |
| other `event: error` | StreamError | no | show type + message |
| `stop_reason: refusal` | (not an error; `TurnEvent.Done`) | no | "Claude declined (category). Retry with Sonnet 5?" |
| bridge unreachable / 401 / 503 with reason | BridgeUnavailable(reason) | no | "PC offline / secret wrong / login expired / usage limit / disabled by policy" |

`RetryPolicy` never retries 400/401/402/403/404/413 or the spend-cap 429, and never retries a
request that has already produced output (the user would see duplicated text). `request-id` from
the response headers is stored on the `ChatMessage`.

### 6.9 Cost and usage (`PricingTable`, `SpendMeter`)

`PricingTable` holds R1 §2.3 (Opus 5: $5 / $6.25 / $10 / $0.50 / $25 per MTok for input / 5-min
write / 1-h write / cache read / output; Sonnet 5: $2 / $2.50 / $4 / $0.20 / $10) with the date
they were read (2026-09-04) and a "prices may have changed" footnote in the Usage screen.
`costUsd = input×p_in + cacheCreation×p_w5 + cacheRead×p_r + output×p_out` (thinking tokens are
inside `output_tokens`). `SpendMeter.monthToDate()` sums `SpendRecord`s; the Coach screen shows
"$x.xx this month of $budget"; at 80 % a banner appears; at 100 % Send asks for explicit
confirmation. Cache effectiveness (`cache_read_input_tokens / total input`) is shown on Usage so a
broken cache (byte-unstable prompt) is visible immediately (R1 §3.10).

### 6.10 Conversation lifecycle and compaction (preserved-thinking safe)

- One conversation = one frozen `(system prompt hash, tool contract version, model, effort)`.
  Changing any of them in Settings closes the current conversation and starts a new one.
- Within a conversation the transcript is strictly append-only: no turn is edited, reordered or
  removed; thinking blocks are echoed verbatim; `system` and `tools` are byte-identical on every
  request; only request parameters outside `system`/`tools`/`messages` may vary (and we vary
  none).
- **Compaction trigger**: the last response's total input tokens (`input + cache_read +
  cache_creation`) > 40,000, or > 40 assistant turns, or the conversation is older than 7 days.
- **Compaction method** ("simple compaction", the shape Anthropic recommends): a separate request
  (no tools, `effort: low`, fixed `compaction_prompt_v1.txt`, the full transcript as one user
  message) produces a ≤ 600-token summary of standing decisions, user preferences, open questions
  and pending proposals; the old conversation is marked COMPACTED (read-only in the UI); a new
  conversation starts whose first user message is `<summary>` + a fresh `<context>` snapshot.
  Nothing else is carried over — no earlier turns, no thinking blocks (keep-tail compaction is
  explicitly rejected: it fails the history-editing check).
- Never compact while proposals are undecided (an assistant turn whose `tool_use` awaits its
  `tool_result`): the UI asks the user to decide first.
- The user can start a new conversation manually at any time; old ones stay readable.

### 6.11 Home bridge protocol (phone side ships in v1; PC side later)

`POST {bridgeUrl}/v1/messages` with `Authorization: Bearer <bridge secret>` and the *same JSON
body* the direct backend would send; the bridge answers with the same SSE stream (it drives the
unmodified `claude -p --output-format stream-json --verbose --include-partial-messages
--system-prompt … --permission-mode dontAsk --json-schema …` and maps lines as in §6.7). The
bridge secret is stored through the same `KeystoreCipher`. Failure JSON from the bridge
(`{"type":"error","error":{"type":"bridge_unavailable","message":"login expired"}}`) maps to
`BridgeUnavailable`. Everything R1 §2.2 lists as conditions (unmodified binary, own account,
Tailscale-only, no `--bare`, no resale, low volume) is printed on the settings screen, together
with the "may stop working without notice" sentence. `docs/bridge/PROTOCOL.md` (WP-08) is the
normative spec for whoever writes the PC side.

---

## 7. Persistence (`:app`)

### 7.1 Room 2.8.4 schema — version 1

Design rule (house rule 8): entities carry only `Long/Int/Double/String/Boolean` columns; dates
are ISO-8601 strings; structures that will evolve (plan, prescription, gym items, check-in answers,
proposal payloads, chat content, usage) are JSON documents with their own `schemaVersion`, decoded
with `ignoreUnknownKeys` and defaults — so most future changes need **no SQL migration**.

| Table | Columns (type) | Indices / notes |
|---|---|---|
| `profile` | `id` PK (=1), `json` TEXT, `updated_at` TEXT | single row document |
| `gym_sets` | `id` PK auto, `name`, `items_json`, `based_on_preset`?, `notes`, `is_archived` INTEGER, `created_at`, `updated_at` | |
| `plans` | `id` PK auto, `name`, `goal`, `is_active` INTEGER, `created_at`, `start_date`, `plan_json` | index `is_active` |
| `workout_sessions` | `id` PK auto, `date`, `gym_id`?, `plan_id`?, `template_id`?, `started_at`, `finished_at`?, `readiness_check_id`?, `session_rpe`?, `notes`, `prescription_json`? | index `date` |
| `set_logs` | `id` PK auto, `session_id`, `exercise_id`, `set_index`, `kind`, `entered_weight` REAL?, `entered_weight_unit`?, `weight_kg` REAL?, `reps`?, `seconds`?, `metres`?, `rir`?, `is_unilateral`, `logged_at`, `superseded_by_id`?, `note` | indices `session_id`, `exercise_id`; rows are never updated except to set `superseded_by_id` |
| `adjustments` | `id`, `session_id`?, `exercise_id`?, `rule_ids` (comma-joined), `summary`, `before_json`, `after_json`, `source`, `created_at` | index `session_id` |
| `progression_state` | `exercise_id` PK TEXT, `state_json`, `updated_at` | document |
| `body_metrics` | `id`, `date`, `entered_weight`, `entered_weight_unit`, `weight_kg`, `waist_cm`?, `resting_hr`?, `note` | unique index `date` (one weigh-in per day; a second entry supersedes with a note) |
| `cardio_logs` | `id`, `date`, `exercise_id`, `kind`, `minutes`, `avg_hr`?, `rpe`?, `note` | index `date` |
| `nutrition_logs` | `id`, `date`, `kcal`?, `protein_g`?, `protein_hit`?, `meals_heaviness`?, `alcohol_drinks`?, `steps`?, `note` | unique index `date` |
| `readiness_checks` | `id`, `date`, `sleep_hours` REAL, `sleep_quality`, `soreness`, `stress`, `energy`, `motivation`, `resting_hr`?, `score`, `band`, `explanation_json` | index `date` |
| `weekly_checkins` | `id`, `week_start`, `answers_json`, `triggered_json`, `completed_at`? | unique index `week_start` |
| `checkin_questions` | `id` PK TEXT, `json` | |
| `chat_conversations` | `id`, `started_at`, `ended_at`?, `backend`, `model`, `effort`, `system_prompt_hash`, `tool_contract_version`, `seed_summary`?, `status` | index `status` |
| `chat_messages` | `id`, `conversation_id`, `role`, `content_json`, `display_text`, `created_at`, `usage_json`?, `request_id`?, `stop_reason`? | index `conversation_id` |
| `proposals` | `id`, `conversation_id`, `assistant_message_id`, `tool_use_id`, `tool_name`, `kind`, `contract_version`, `payload_json`, `rationale`, `status`, `created_at`, `decided_at`?, `result_summary`? | indices `conversation_id`, `status` |
| `spend_records` | `id`, `at`, `model`, `usage_json`, `cost_usd` REAL, `backend` | index `at` |

Representative entity + DAO (the pattern every other table follows; every annotation here has
existed since Room 1.0):

```kotlin
@Entity(tableName = "set_logs", indices = [Index("session_id"), Index("exercise_id")])
data class SetLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "set_index") val setIndex: Int,
    val kind: String,
    @ColumnInfo(name = "entered_weight") val enteredWeight: Double?,
    @ColumnInfo(name = "entered_weight_unit") val enteredWeightUnit: String?,
    @ColumnInfo(name = "weight_kg") val weightKg: Double?,
    val reps: Int?,
    val seconds: Int?,
    val metres: Int?,
    val rir: Int?,
    @ColumnInfo(name = "is_unilateral") val isUnilateral: Boolean,
    @ColumnInfo(name = "logged_at") val loggedAt: String,
    @ColumnInfo(name = "superseded_by_id") val supersededById: Long?,
    val note: String,
)

@Dao
interface WorkoutDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSet(set: SetLogEntity): Long

    @Query("UPDATE set_logs SET superseded_by_id = :newId WHERE id = :oldId AND superseded_by_id IS NULL")
    suspend fun markSuperseded(oldId: Long, newId: Long): Int

    @Query("SELECT * FROM set_logs WHERE session_id = :sessionId AND superseded_by_id IS NULL ORDER BY exercise_id, set_index")
    fun observeSets(sessionId: Long): Flow<List<SetLogEntity>>

    @Query("SELECT * FROM set_logs WHERE exercise_id = :exerciseId AND superseded_by_id IS NULL ORDER BY logged_at DESC LIMIT :limit")
    suspend fun recentSetsForExercise(exerciseId: String, limit: Int): List<SetLogEntity>
}

@Database(entities = [ /* all 17 entities */ ], version = 1, exportSchema = true)
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

**Migrations strategy**

- `exportSchema = true`; `app/schemas/com.gymcoach.app.data.db.AppDatabase/1.json` is committed
  after the first Android Studio build and reviewed on every change.
- `object Migrations { val all: Array<Migration> = arrayOf() }` at v1. Every later schema change:
  `version++`, a hand-written migration, a review of the exported schema diff, and an instrumented
  `MigrationTestHelper` test (post-first-compile). The Room 2.8.4 shape, verified from the
  published sources jar on 2026-09-05:

  ```kotlin
  val MIGRATION_1_2: Migration = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE set_logs ADD COLUMN tempo TEXT")
      }
  }
  ```
  (`migrate(db: SupportSQLiteDatabase)` is `open` in 2.8.4 and is the variant Room calls when no
  `SQLiteDriver` is set — which is our configuration; `Room.databaseBuilder(context, klass, name)`
  and `addMigrations(vararg)` verified in the same sources.)
- Never `fallbackToDestructiveMigration()` in any build type: a missing migration must crash
  loudly at startup rather than delete logs. Debug builds may use `fallbackToDestructiveMigrationOnDowngrade()`
  only if Johannes asks for it.
- Document columns evolve by `schemaVersion` in the JSON + defaults; a reader that meets a newer
  document version than it knows shows a "data from a newer app version" error instead of guessing.

### 7.2 Repositories and mappers

`data/repo/Mappers.kt` holds pure `toEntity()` / `toModel()` functions; documents are
encoded/decoded with `CoreJson.instance`. Repository implementations wrap DAO calls in
`withContext(Dispatchers.IO)` (Room's `suspend` DAOs already switch threads, but stating it keeps
callers uniform) and map `Flow`s with `.map { list -> list.map { it.toModel() } }`. Mappers are
unit-tested on the JVM (entity classes are plain Kotlin — Room annotations do not stop that).

### 7.3 Knowledge loading (class-path resources in `:core`)

`KnowledgeLoader.loadDefault(json)` reads, via `KnowledgeLoader::class.java.getResourceAsStream(path)`,
`/gymcoach/knowledge/equipment.json`, `exercises.json`, `exercise_content/batch_1..6.json`,
`rp_landmarks.json`, `mobility_drills.json`, `cardio_modalities.json`, `nutrition_tips.json`,
`checkin_questions.json`, `supplements.json`, and builds `KnowledgeBase` (maps by id, groups,
presets, rep profiles, content overlays merged by id). Missing resource → `IllegalStateException`
naming the path (fail fast at first use in `AppContainer`, not silently empty). The same call runs
in `:core` tests, which is what makes the R4 §6.3 validation rules tests rather than hopes. A
startup smoke check logs the counts (156 exercises, 77 equipment ids, 19 groups, 4 presets).

### 7.4 DataStore Preferences (1.2.1)

Two stores: `settings` (backed up) and `secrets` (excluded from backup, §7.5).

```kotlin
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
private val Context.secretsStore: DataStore<Preferences> by preferencesDataStore(name = "secrets")
```

Keys (`settings`): `unit_system`, `backend_mode`, `model_id`, `effort`, `monthly_budget_usd`
(double), `bridge_url`, `show_experimental`, `onboarding_done`, `week_starts_monday`,
`planner_overrides_json`, `coach_user_id`. Keys (`secrets`): `api_key_iv`, `api_key_ct`,
`bridge_secret_iv`, `bridge_secret_ct` (Base64 strings). `SettingsDataStore` implements
`SettingsRepository` with `observe() = settingsStore.data.map { it.toAppSettings() }` and
`update` via `settingsStore.edit { }`; a corrupt store is handled by DataStore's default (throws
`IOException` on read) → caught once and mapped to defaults with a log line.

### 7.5 Secure key storage (`KeystoreCipher`, `KeystoreApiKeyStore`) — R2 §6.1 verbatim design

- Key: `KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")` initialised
  with `KeyGenParameterSpec.Builder("gymcoach_master", PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
  .setBlockModes(BLOCK_MODE_GCM).setEncryptionPaddings(ENCRYPTION_PADDING_NONE).setKeySize(256)`;
  created on first use if the alias is absent; no user-authentication requirement, no StrongBox.
- Encrypt: `Cipher.getInstance("AES/GCM/NoPadding")`, `init(ENCRYPT_MODE, key)`, IV from
  `cipher.iv` (never supplied), `doFinal(plaintextUtf8)`; store `Base64(iv)` and `Base64(ct)`
  (`java.util.Base64`, available at API 26) in the `secrets` DataStore.
- Decrypt: `init(DECRYPT_MODE, key, GCMParameterSpec(128, iv))`. Any exception
  (`AEADBadTagException`, `KeyPermanentlyInvalidatedException`, `UnrecoverableKeyException`,
  missing alias, missing preference) → `read()` returns `null`, the stored blob is cleared, and
  the settings screen shows "The stored key could not be read on this device — enter it again".
  Never retry with a new key over old data.
- Backup exclusion (`res/xml/backup_rules.xml` for API ≤ 30, `data_extraction_rules.xml` for
  API 31+) excludes `datastore/secrets.preferences_pb` from cloud backup and device transfer; the
  database and `settings` are backed up (the user's data survives a phone change; the key does
  not, by design).
- The key is only ever placed in the `x-api-key` header of requests to `https://api.anthropic.com`
  (or the `Authorization` header to the user's own bridge URL); it is never logged, never put in a
  URL, never included in error text (errors carry `request-id`, never the key).

### 7.6 Deferred to v1.1

JSON export/import of all user data through the Storage Access Framework
(`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`) — the document design above makes it a
serialisation exercise, but it adds an Intent-result API surface we would rather compile once
before using.

---

## 8. Testing

### 8.1 Gates

- `gradlew :core:test` green is the merge gate for every WP that touches `:core`.
- `gradlew :app:testDebugUnitTest` (plain JVM) is the gate for mappers, error mapping, routes —
  it cannot run until the SDK is installed, so those tests are written now and executed at
  first build.
- Instrumented tests are written after the first successful `:app:assembleDebug`.

### 8.2 `:core` test inventory

| Package | Test classes | What they assert |
|---|---|---|
| `json`, `model` | `SerializersTest`, `ModelRoundTripTest`, `NoAndroidImportsTest` | ISO round-trips; every `@Serializable` model encodes→decodes→encodes to identical JSON; no `import android` under `core/src/main` |
| `units` | `UnitsTest` | kg↔lb, cm↔in at known points; `EnteredWeight` keeps the typed value |
| `knowledge`, `equipment` | `KnowledgeValidationTest` (R4 §6.3 rules 1–8 as separate methods), `EquipmentResolverTest`, `IncrementResolverTest`, `PresetsTest` | ids unique/resolving, hotel preset ≥ 40 exercises, travel kit ≥ 25, rack without safeties fails `rack_or_spotter`, spotter flag satisfies it, `power_rack.has_pullup_bar=false` fails `pullup_bar_any`, microplates → 1.0 kg step, dumbbell max warning path |
| `planner` | `PlanGeneratorGoldenTest` (≥ 12 named cases: novice/3-day/base gym; intermediate/4-day/home gym; advanced/6-day; hotel-only; dumbbells-only; bodyweight+band; 30-min sessions; knee limitation; shoulder limitation; low-back; age 62; fat_loss with history), `PlanValidatorTest` (each invariant with a hand-built violating plan), `SplitSelectorTest`, `VolumeAllocatorTest`, `TimeTrimmerTest` (ladder order), `WarmupBuilderTest` (WU-02 table rows, WU-05/06 branches), `SessionPrescriberTest` (each modifier in §3.5 in isolation and combined), `PlanInvariantPropertyTest` (seeded `java.util.Random`, 500 random profile × gym × goal combinations → validator passes or the generator reports why) |
| `progression`, `readiness` | `E1rmTest` (formula rows, cap at 10, LOW/HIGH confidence, table interpolation), `ProgressionEngineTest` (top hit / below / over-RIR × training age cadence; big-jump rep extension; RIR-05 correction; adjustments always emitted; `SetLog` inputs unchanged — asserted by comparing the input list before/after), `PlateauDetectorTest`, `DeloadPolicyTest` (DLD-02 modifiers; DLD-03 needs ≥ 2 triggers), `LayoffPolicyTest` (each LAY-01 row boundary), `VolumeAccountantTest` (worked examples from R4 §4.2 verbatim), `ReadinessScorerTest` (RDY-01 table points, renormalisation, RHR deltas, band edges 44/45/69/70), `SleepRulesTest`, `RedFlagsTest` |
| `cardio`, `nutrition`, `checkin` | `HeartRateZonesTest` (Tanaka, Karvonen), `CardioPlannerTest` (CAR-07 per goal; CAR-06 placement; HIIT gating by base weeks and medical flags), `EnergyEstimatorTest` (Mifflin worked examples for both sexes), `TdeeAdapterTest` (clamp ±150, 14-day cadence, insufficient data → no change), `WeightTrendTest` (MA7, noise flag), `MacroTargetsTest` (NUT-01 rows incl. FFM path and ≥ 65 floor; fat floor; fibre), `SupplementAdvisorTest` (kidney flag → refer; caffeine cut-off arithmetic; tiers), `CheckInBankTest` (rotation ≤ 8, auto-answers), `CheckInEvaluatorTest` (each Q trigger) |
| `coach.wire` | `SseParserTest` (R1 §3.6 fixtures verbatim; CRLF; multi-line data; comments; `SseParserFuzzTest` split-at-every-offset), `StreamAccumulatorTest` (text, tool_use with empty first delta, thinking with signature only, redacted_thinking, cumulative usage, mid-stream error, unknown event type counted), `RequestBuilderTest` (tool_result first, echo unchanged, no trailing assistant, cache_control present, no temperature) |
| `coach` | `SystemPromptStabilityTest` (no volatile content; ≥ 512-token estimate), `CoachContextBuilderTest` (snapshot fields; budget truncation oldest-first with note; delta only includes changes), `ProposalSchemasTest` (each schema parses; only allowed keywords; `additionalProperties=false` everywhere; every required key exists in properties), `ProposalContractTest` (parse/validate each tool incl. every rejection path), `ProposalApplierTest` (each kind; never touches sets; emits adjustments), `CoachTurnServiceTest` (fake backend replaying fixtures: end_turn, tool_use → pending → applied → next request carries tool_result first; refusal discards; failure persists nothing; compaction trigger and summary seeding), `PricingTableTest`, `SpendMeterTest` |

Target: ~250 test methods in `:core` at the end of Phase 1; every rule ID referenced by code
appears in at least one test name (`grep -c "PROG-01"` style audit in `RuleCoverageTest`, which
reads the source tree and the test tree and fails on rule IDs present in main but absent in test).

### 8.3 Golden files and fixtures

- `core/src/test/resources/golden/plans/<case>.json` — regenerated only with
  `-Dgymcoach.regenerateGolden=true`; any diff must be explained in the PR.
- `core/src/test/resources/sse/*.sse` — R1's verbatim examples (text turn, tool-use turn,
  thinking-with-signature-only, mid-stream overload, refusal) plus one long recorded real stream
  once the first live call has been made from Android Studio (saved with the key stripped).
- `core/src/test/resources/knowledge/` — the production JSON is used directly (D2); no copies.

### 8.4 `:app` JVM unit tests (written now, run at first build)

`MappersTest` (entity↔model round-trips for all 17 tables), `HttpErrorMapperTest` (status ×
body → `BackendError`, spend-cap detection, retry-after parsing), `RetryPolicyTest`,
`SettingsMappingTest` (Preferences → AppSettings defaults), `RoutesTest` (routes serialise).

### 8.5 Instrumented tests (after first compile)

`AppDatabaseTest` (create v1, insert/read each table), `MigrationTest` scaffold with
`MigrationTestHelper` (empty at v1), `KeystoreCipherTest` (round-trip, wrong-IV → null path),
`SmokeComposeTest` (launch, onboarding renders).

### 8.6 First-build acceptance script for Johannes (`docs/build/FIRST_BUILD_CHECKLIST.md`)

1. Install JDK 17 and cmdline-tools per R2 §7; `sdkmanager --licenses`; install
   `platforms;android-37.0`, `build-tools;36.0.0`, `platform-tools`.
2. `gradlew :core:test` → green.
3. Open in Android Studio (Quail 4 | 2026.1.4 or newer), sync; if the AGP/KGP/Gradle triple
   complains, apply the R2 fallback (`agp = "9.3.2"`, `gradle-9.5.1-bin.zip`).
4. `gradlew :app:assembleDebug`; fix compile errors starting with each WP's compile-risk list.
5. Run on a device/emulator; complete onboarding; generate a plan; log one session; confirm the
   adjustments list.
6. Settings → Backend → paste a Console API key → "Test connection" → then one real chat turn;
   verify `cache_read_input_tokens > 0` on the second turn (Usage screen).
7. Airplane mode: everything except Coach keeps working.
8. Commit `app/schemas/.../1.json`.

---

## 9. Work breakdown — 12 packages with exclusive file ownership

Phasing: **Phase 0** (sequential, first) = WP-01 and WP-09, which freeze every interface, type,
route and build file the others compile against (tag `v0-interfaces`). **Phase 1** (parallel) =
WP-02…WP-08 and WP-10…WP-12, plus six content batches C1–C6 that own one overlay file each.
**Phase 2** (one integrator) = wire the real classes into `AppContainer`, run `:core:test`, write
the first-build checklist deltas. No file has two owners; cross-WP needs are met by *reading* the
frozen Phase-0 files, never by editing them (change requests go to the WP-01/WP-09 owner).

Every WP's PR must include a **compile-risk list**: each `:app` symbol used that is not in
R1/R2/Appendix D's verified set, with the reason it is believed to exist.

| WP | Name | Owns (exact paths, all under `C:\Users\User\Projects\GymCoach\`) | Depends on | Acceptance criteria |
|---|---|---|---|---|
| **WP-01** | core foundation | `core/build.gradle.kts`; `core/src/main/kotlin/com/gymcoach/core/model/{Enums,Values,Profile,Equipment,Exercise,Plan,Workout,Progression,Readiness,CheckIn,Nutrition,Coach,Settings}.kt`; `core/src/main/kotlin/com/gymcoach/core/json/{Serializers,CoreJson}.kt`; `core/src/main/kotlin/com/gymcoach/core/repo/{ProfileRepository,GymRepository,PlanRepository,WorkoutRepository,ProgressionRepository,MetricsRepository,CoachRepository,SettingsRepository,ApiKeyStore,ClaudeBackend,BackendError}.kt`; `core/src/main/kotlin/com/gymcoach/core/explain/RuleIds.kt`; `core/src/main/kotlin/com/gymcoach/core/units/Units.kt`; `core/src/main/kotlin/com/gymcoach/core/time/Clock.kt`; `core/src/test/kotlin/com/gymcoach/core/{json,model,units,time}/*Test.kt`; `core/src/test/kotlin/com/gymcoach/core/NoAndroidImportsTest.kt` | — | Exactly the types in §2 (names, fields, defaults); every model round-trips; `RuleIds` contains every R3 §10 ID as `const val`; `NoAndroidImportsTest` passes; `:core:test` green; no other package touched |
| **WP-02** | knowledge assets + resolver | `core/src/main/resources/gymcoach/knowledge/{equipment,exercises,rp_landmarks,mobility_drills,cardio_modalities,checkin_questions,supplements,nutrition_tips}.json`; `core/src/main/resources/gymcoach/knowledge/exercise_content/batch_{1..6}.json` (skeletons only: every id with `content_status: todo`); `core/src/main/kotlin/com/gymcoach/core/knowledge/{KnowledgeBase,KnowledgeLoader,KnowledgeValidator,RpLandmarks}.kt`; `core/src/main/kotlin/com/gymcoach/core/equipment/{EquipmentResolver,GymParams,IncrementResolver,Presets}.kt`; `core/src/test/kotlin/com/gymcoach/core/{knowledge,equipment}/*Test.kt` | WP-01 | All 156 exercises and 77 equipment ids from R4 transcribed exactly (muscle weights, options, flags, increments, substitutions, profiles); R4 §6.3 tests 1–8 pass; §3.4 availability semantics incl. `has_safeties`/`has_pullup_bar`/spotter; presets match R4 §2.5; RP landmarks verbatim from R4 §4.5 |
| **WP-03** | planner | `core/src/main/kotlin/com/gymcoach/core/planner/{PlannerConstants,PlanGenerator,SplitSelector,VolumeAllocator,ExerciseSelector,SessionBudgeter,TimeTrimmer,WarmupBuilder,SessionPrescriber,PlanValidator,PrescribeInput}.kt`; `core/src/test/kotlin/com/gymcoach/core/planner/*Test.kt`; `core/src/test/resources/golden/plans/*.json` | WP-01, WP-02 | §3.3 steps 1–15 implemented with rule-ID explanations; `PlanValidator` invariants 1–12; 12 golden cases; property test over 500 seeds passes; §3.5 modifiers each covered; output deterministic (two runs → identical JSON) |
| **WP-04** | progression + readiness | `core/src/main/kotlin/com/gymcoach/core/progression/{ProgressionEngine,E1rm,PlateauDetector,DeloadPolicy,LayoffPolicy,VolumeAccountant,WeeklyReview,ProgressSeries}.kt`; `core/src/main/kotlin/com/gymcoach/core/readiness/{ReadinessScorer,ReadinessModifiers,SleepRules,RedFlags}.kt`; tests under `core/src/test/kotlin/com/gymcoach/core/{progression,readiness}/` | WP-01, WP-02 | §3.6–3.9 exactly; input `SetLog`s provably unmodified; every decision emits `Adjustment`s; RDY-01 table reproduced; `ProgressSeries` produces chart series (dates → values) for weight MA7, e1RM, volume, readiness |
| **WP-05** | cardio, nutrition, mobility, check-ins | `core/src/main/kotlin/com/gymcoach/core/cardio/{CardioPlanner,HeartRateZones,HiitProtocols}.kt`; `core/src/main/kotlin/com/gymcoach/core/nutrition/{EnergyEstimator,TdeeAdapter,WeightTrend,MacroTargets,SupplementAdvisor,HydrationRules}.kt`; `core/src/main/kotlin/com/gymcoach/core/mobility/MobilityPlanner.kt`; `core/src/main/kotlin/com/gymcoach/core/checkin/{CheckInBank,CheckInEvaluator}.kt`; tests under the matching test packages | WP-01, WP-02 | §4.2–4.7 numbers reproduced with rule IDs; SUP-12 caveat attached to every advice item; CHK-01 15 questions with triggers; NUT-04 adaptive step clamped; all "not verified live" values from R3 §12 carry `confidence = "unverified"` in their explanations |
| **WP-06** | coach core | `core/src/main/kotlin/com/gymcoach/core/coach/wire/{WireTypes,RequestBuilder,SseParser,StreamAccumulator}.kt`; `core/src/main/kotlin/com/gymcoach/core/coach/{SystemPrompt,CoachContextBuilder,ProposalSchemas,ProposalContract,ProposalApplier,TranscriptCompactor,CoachTurnService,TurnEvent,PricingTable,SpendMeter,TokenEstimator}.kt`; `core/src/main/resources/gymcoach/coach/{system_prompt_v1,compaction_prompt_v1}.txt`; `core/src/test/kotlin/com/gymcoach/core/coach/**`; `core/src/test/resources/sse/*.sse` | WP-01 (also reads WP-02 ids for context tests) | §6.3–6.10 implemented; fixtures from R1 §3.6 parse byte-for-byte; fuzz test; append-only transcript proven by `CoachTurnServiceTest`; schemas pass the strict-subset test; system prompt ≥ 512-token estimate, no volatile content; contract v1 frozen |
| **WP-07** | app persistence | `app/src/main/kotlin/com/gymcoach/app/data/db/{AppDatabase,Entities,ProfileDao,GymDao,PlanDao,WorkoutDao,ProgressionDao,MetricsDao,CoachDao,Migrations}.kt`; `app/src/main/kotlin/com/gymcoach/app/data/repo/{RoomProfileRepository,RoomGymRepository,RoomPlanRepository,RoomWorkoutRepository,RoomProgressionRepository,RoomMetricsRepository,RoomCoachRepository,Mappers}.kt`; `app/src/main/kotlin/com/gymcoach/app/data/prefs/SettingsDataStore.kt`; `app/schemas/.gitkeep`; `app/src/test/kotlin/com/gymcoach/app/data/{MappersTest,SettingsMappingTest}.kt` | WP-01, WP-09 | Tables per §7.1; entities primitive-only; DAOs `suspend`/`Flow`; `supersedeSet` never updates values; class names exactly as `AppContainer` expects; mappers round-trip; compile-risk list lists every Room/DataStore symbol |
| **WP-08** | network + secure storage | `app/src/main/kotlin/com/gymcoach/app/network/claude/{DirectApiBackend,RetryPolicy,HttpErrorMapper,SseBodyReader}.kt`; `app/src/main/kotlin/com/gymcoach/app/network/bridge/BridgeBackend.kt`; `app/src/main/kotlin/com/gymcoach/app/data/secure/{KeystoreCipher,KeystoreApiKeyStore}.kt`; `app/src/test/kotlin/com/gymcoach/app/network/{HttpErrorMapperTest,RetryPolicyTest}.kt`; `docs/bridge/PROTOCOL.md` | WP-01, WP-09 | §6.2 headers/body; SSE read loop feeds `:core` `SseParser`; §6.8 mapping table complete; retries per R1 §3.9 only; cancellation cancels the call; key never logged; §7.5 failure policy; bridge adapter returns `NotConfigured`/`BridgeUnavailable` correctly; PROTOCOL.md normative |
| **WP-09** | app foundation (Phase 0) | `settings.gradle.kts`; `build.gradle.kts`; `gradle.properties`; `gradle/libs.versions.toml`; `gradle/wrapper/gradle-wrapper.properties`; `app/build.gradle.kts`; `app/proguard-rules.pro`; `app/src/main/AndroidManifest.xml`; `app/src/main/res/xml/{backup_rules,data_extraction_rules}.xml`; `app/src/main/res/values/{strings,themes}.xml`; `app/src/main/res/drawable/ic_launcher.xml`; `app/src/main/kotlin/com/gymcoach/app/{GymCoachApp,MainActivity}.kt`; `app/src/main/kotlin/com/gymcoach/app/di/AppContainer.kt`; `app/src/main/kotlin/com/gymcoach/app/ui/theme/{Theme,Color,Type}.kt`; `app/src/main/kotlin/com/gymcoach/app/ui/nav/{Routes,AppNavHost,BottomBar}.kt`; `app/src/main/kotlin/com/gymcoach/app/ui/components/{NumberField,UnitLabel,ExplanationChip,ErrorBanner,LoadingBox,ConfirmDialog,SectionHeader,LineChart}.kt`; `app/src/test/kotlin/com/gymcoach/app/ui/nav/RoutesTest.kt`; `docs/build/FIRST_BUILD_CHECKLIST.md`; `.gitignore` | WP-01 (types only) | R2 files verbatim + §1.1 deltas; `AppContainer` names every class the other WPs must provide; routes per §5.1; `AppNavHost` has a `composable<T>` entry for every route calling a screen function whose signature is fixed here (`XxxScreen(container, navController)`); components compile-conservative; manifest/backup XML per §7.5 |
| **WP-10** | training UI | `app/src/main/kotlin/com/gymcoach/app/ui/today/{TodayScreen,TodayViewModel}.kt`; `…/ui/readiness/{ReadinessScreen,ReadinessViewModel}.kt`; `…/ui/workout/{WorkoutScreen,WorkoutViewModel,SetRow,RestTimer,SubstitutionSheet,WorkoutSummaryScreen,WorkoutSummaryViewModel}.kt`; `…/ui/plan/{PlanScreen,PlanViewModel,PlanSessionScreen}.kt`; `…/ui/progress/{ProgressScreen,ProgressViewModel,ExerciseProgressScreen,ExerciseProgressViewModel}.kt` | WP-01, WP-09; reads WP-03/04 signatures | Flows §5.3 (1)–(3), (5); §5.4 display rules; set editing supersedes; every modifier/adjustment visible with rule IDs; no Room/OkHttp imports; `@OptIn` on experimental M3 APIs; compile-risk list |
| **WP-11** | setup + health UI | `…/ui/onboarding/{OnboardingScreen,OnboardingViewModel}.kt`; `…/ui/profile/{ProfileScreen,ProfileViewModel}.kt`; `…/ui/gyms/{GymsScreen,GymEditScreen,EquipmentPicker,GymsViewModel}.kt`; `…/ui/library/{LibraryScreen,ExerciseDetailScreen,LibraryViewModel}.kt`; `…/ui/checkin/{CheckInScreen,CheckInViewModel}.kt`; `…/ui/nutrition/{NutritionScreen,NutritionViewModel}.kt`; `…/ui/more/MoreScreen.kt` | WP-01, WP-09; reads WP-02/05 signatures | Onboarding ends with a generated plan; gym editor exposes every R4 param; library filters incl. "available in current gym"; red-flag list and medical gates per §4.6; nutrition screen shows the energy model assumptions and SUP-12 caveat |
| **WP-12** | coach + settings UI | `…/ui/coach/{CoachScreen,CoachViewModel,MessageBubble,ProposalCard,ContextSheet}.kt`; `…/ui/settings/{SettingsScreen,BackendSettingsScreen,ApiKeyEntry,AdvancedConstantsScreen,SettingsViewModel}.kt`; `…/ui/usage/{UsageScreen,UsageViewModel}.kt`; `app/src/main/res/values/strings_backend_policy.xml` | WP-01, WP-09; reads WP-06/08 signatures | R1 §2.5 copy verbatim in `strings_backend_policy.xml`; key validation rule from §6.1; streaming render; proposal cards with Apply/Reject/Later wired to `ProposalApplier` through the ViewModel; offline banner; spend and budget; "Test connection" sends a 32-token request; advanced constants editor writes overrides JSON |
| **C1–C6** | exercise content | `core/src/main/resources/gymcoach/knowledge/exercise_content/batch_N.json` (one file each) | WP-02 skeleton | R4 §5.12–5.13 rules: description, setup, cues 3–6, mistakes 3–5, safety 1–4 reflecting flags, substitution rationale, sources; `content_status: draft`; never change numbers (they cannot — numbers live in `exercises.json`, which they do not own) |

Integration (Phase 2, one agent): replace nothing — `AppContainer` already names the real
classes; run `:core:test`, run the grep checks from §1.4, assemble the compile-risk lists into
`docs/build/COMPILE_RISK_LIST.md`, and hand over.

Estimated size: `:core` ≈ 9–11k lines incl. tests; `:app` ≈ 6–8k lines; knowledge JSON ≈ 400 KB.

---

## 10. Risks and open questions

### 10.1 Risks (beyond §0.1)

1. **First `:app` compile will fail somewhere.** Certain. The bet is that failures are shallow
   (a symbol name, an import) because every `:app` file is short and boring; the compile-risk
   lists are the map for fixing them in one sitting.
2. **Room 2.8.4 + KSP 2.3.11 + AGP 9.4** codegen: verified by Google's own compose-samples
   (R2), not by us. Fallback: R2's AGP 9.3.2 pairing.
3. **`viewModel { }` initializer with `viewModelScope` inside** is standard, but the
   `lifecycle-viewmodel-compose` 2.11 artifact is KMP — the Android variant resolves through
   Gradle module metadata; if resolution misbehaves, the fallback is `viewModelFactory { initializer { } }`
   (also verified) passed as `factory =`.
4. **Preserved-thinking enforcement** may reach Opus 5 on new orgs at any time; our transcript is
   already compliant, but a *bug* that edits history would only be caught when it is enforced.
   `CoachTurnServiceTest` asserts byte-identical prefixes between consecutive requests to close
   that gap early.
5. **Policy drift on the bridge**: isolated; the settings copy says so.
6. **Cost drift**: the pricing table is dated; Usage shows measured tokens, so a price change is
   visible as a mismatch with the Console bill, not as a silent overspend.
7. **R3 numbers**: every weak threshold is adjustable; a wrong default costs one edit.
8. **Content backlog**: 156 exercise texts; the app is usable with "description pending".
9. **`material-icons-extended` 1.7.8 with Compose 1.12**: asserted only by its presence in BOM
   2026.08.00 (R2 U9); fallback is a handful of `ImageVector`s drawn by hand.
10. **Large screens / foldables**: single-column layouts only; acceptable for a phone-first
    personal app.

### 10.2 Open questions for Johannes

1. Home gym inventory (R4 §7 item 4) — needed to replace the generic `home_gym` preset.
2. Console account and budget: is ~$20–45/month on Opus 5 acceptable, or should Sonnet 5 be the
   default (~$8–17)? The design defaults to Opus 5 with `effort: low`.
3. Should the bridge be built at all in the next iteration, given its revocability?
4. "Set a reminder question": in-app (v1, no notifications) or a real notification (v1.1,
   WorkManager + `POST_NOTIFICATIONS`)?
5. Age and any current limitations/medical flags (they change the defaults: WU-06, AGE-01/02).
6. Imperial needed at all? (kept, but it costs UI surface.)
7. Body-fat estimate method (affects NUT-01/05 branches).
8. Week start (Monday assumed) and typical training time of day (SLP-03 morning preference).
9. Appetite for the "Advanced constants" screen in v1 vs hiding it behind a flag.
10. R4 open item 1: a second glance at the RP back-landmark image before it is hard-coded.

---

## Appendix A — build and manifest deltas

### A.1 `app/build.gradle.kts` — only the delta from R2 §5.5

```kotlin
// remove:  implementation(libs.androidx.work.runtime)     // D1: no background work in v1
```

### A.2 `app/src/main/AndroidManifest.xml`

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
(platform theme; no AppCompat/Material-Components dependency). `ic_launcher.xml` is a vector
drawable (text, no binary assets).

### A.3 Backup rules

`res/xml/backup_rules.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <exclude domain="file" path="datastore/secrets.preferences_pb" />
</full-backup-content>
```
`res/xml/data_extraction_rules.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="file" path="datastore/secrets.preferences_pb" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="file" path="datastore/secrets.preferences_pb" />
    </device-transfer>
</data-extraction-rules>
```

### A.4 `app/proguard-rules.pro` (inactive while `isMinifyEnabled = false`)

```
# kotlinx.serialization ships its own consumer rules (README, verified 2026-09-05). The rules
# below are redundant belt-and-braces for our own packages and cost nothing.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.gymcoach.**$$serializer { *; }
-keepclassmembers class com.gymcoach.** { *** Companion; }
-keepclasseswithmembers class com.gymcoach.** { kotlinx.serialization.KSerializer serializer(...); }
# Room, OkHttp, Compose, DataStore ship consumer rules; nothing to add.
# OkHttp optional platform integrations (already in OkHttp's own rules; harmless duplicates)
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
```

---

## Appendix B — proposal tool schemas (contract v1, strict subset only)

```json
[
  {
    "name": "propose_swap_exercise",
    "description": "Propose replacing one exercise in the user's plan with another. The app shows the proposal and applies it only after the user confirms. Use exercise ids from context.available_exercises only.",
    "strict": true,
    "input_schema": {
      "type": "object",
      "properties": {
        "contract_version": { "type": "integer", "enum": [1] },
        "session_id": { "type": "string", "description": "Session template id from the plan, e.g. s2" },
        "from_exercise_id": { "type": "string" },
        "to_exercise_id": { "type": "string" },
        "scope": { "type": "string", "enum": ["this_session", "rest_of_plan"] },
        "rationale": { "type": "string", "description": "Why, citing rule ids such as INJ-01 or TRV-04" }
      },
      "required": ["contract_version", "session_id", "from_exercise_id", "to_exercise_id", "scope", "rationale"],
      "additionalProperties": false
    }
  },
  {
    "name": "propose_change_volume",
    "description": "Propose changing sets, rep range or RIR for an exercise or a muscle. Sets stay within VOL-06 (12 per muscle per session) and SESS-02 caps; the app validates.",
    "strict": true,
    "input_schema": {
      "type": "object",
      "properties": {
        "contract_version": { "type": "integer", "enum": [1] },
        "target_type": { "type": "string", "enum": ["exercise", "muscle"] },
        "target_id": { "type": "string" },
        "session_id": { "type": "string", "description": "Omit to apply to every session where the target appears" },
        "sets_delta": { "type": "integer", "description": "Between -3 and +3" },
        "new_reps_lo": { "type": "integer" },
        "new_reps_hi": { "type": "integer" },
        "new_rir_lo": { "type": "integer" },
        "new_rir_hi": { "type": "integer" },
        "scope": { "type": "string", "enum": ["this_week", "rest_of_plan"] },
        "rationale": { "type": "string" }
      },
      "required": ["contract_version", "target_type", "target_id", "sets_delta", "scope", "rationale"],
      "additionalProperties": false
    }
  },
  {
    "name": "propose_add_cardio_day",
    "description": "Propose adding a cardio slot on a weekday (0 = first day of the user's week). The app checks CAR-06 placement rules.",
    "strict": true,
    "input_schema": {
      "type": "object",
      "properties": {
        "contract_version": { "type": "integer", "enum": [1] },
        "day_index": { "type": "integer", "enum": [0, 1, 2, 3, 4, 5, 6] },
        "kind": { "type": "string", "enum": ["zone2", "walk", "hiit_4x4", "hiit_10x1", "sit"] },
        "minutes": { "type": "integer", "description": "10 to 90" },
        "modality_id": { "type": "string", "description": "Cardio exercise id available in the gym, e.g. stationary_bike" },
        "rationale": { "type": "string" }
      },
      "required": ["contract_version", "day_index", "kind", "minutes", "rationale"],
      "additionalProperties": false
    }
  },
  {
    "name": "propose_change_goal",
    "description": "Propose changing the user's primary training goal, optionally regenerating the plan (the app shows a diff before applying).",
    "strict": true,
    "input_schema": {
      "type": "object",
      "properties": {
        "contract_version": { "type": "integer", "enum": [1] },
        "new_goal": { "type": "string", "enum": ["strength", "hypertrophy", "general_fitness", "fat_loss", "endurance_support"] },
        "regenerate_plan": { "type": "boolean" },
        "rationale": { "type": "string" }
      },
      "required": ["contract_version", "new_goal", "regenerate_plan", "rationale"],
      "additionalProperties": false
    }
  },
  {
    "name": "propose_set_checkin_question",
    "description": "Propose a question the app asks in the weekly check-in for a number of weeks, to find where nutrition, sleep, stress or steps can be improved.",
    "strict": true,
    "input_schema": {
      "type": "object",
      "properties": {
        "contract_version": { "type": "integer", "enum": [1] },
        "question_text": { "type": "string", "description": "At most 200 characters" },
        "answer_type": { "type": "string", "enum": ["number", "scale5", "yesno", "text"] },
        "weeks_active": { "type": "integer", "description": "1 to 8" },
        "rationale": { "type": "string" }
      },
      "required": ["contract_version", "question_text", "answer_type", "weeks_active", "rationale"],
      "additionalProperties": false
    }
  },
  {
    "name": "propose_schedule_deload",
    "description": "Propose a deload week (DLD-02: sets -50 %, compound loads -10 to -20 %, RIR 4-5). Cite the DLD-03 triggers observed.",
    "strict": true,
    "input_schema": {
      "type": "object",
      "properties": {
        "contract_version": { "type": "integer", "enum": [1] },
        "week": { "type": "string", "enum": ["this_week", "next_week"] },
        "rationale": { "type": "string" }
      },
      "required": ["contract_version", "week", "rationale"],
      "additionalProperties": false
    }
  }
]
```

---

## Appendix C — offline behaviour matrix

| Feature | Offline | Notes |
|---|---|---|
| Onboarding, profile, gyms, library | full | knowledge is on-device |
| Plan generation / regeneration | full | pure `:core` |
| Today, readiness, workout logging, summary, progression | full | |
| Progress charts, check-ins, nutrition targets, supplements | full | |
| Coach chat | send disabled; transcript readable; pending proposals can still be applied/rejected (they are local) | the decision is sent as `tool_result` with the next online message |
| Settings, API key entry | full (key stored locally); "Test connection" needs network | |

---

## Appendix D — verification ledger for this proposal

Verified live on 2026-09-05 (in addition to everything R1–R4 verified on 2026-09-04):

| Item | Source | Result |
|---|---|---|
| Room 2.8.4 `Migration` shape | `room-runtime-android-2.8.4-sources.jar` from dl.google.com (`androidMain/androidx/room/migration/Migration.android.kt`) | `abstract class Migration(startVersion, endVersion)`; `open fun migrate(db: SupportSQLiteDatabase)` is called when no driver is set; `open fun migrate(connection: SQLiteConnection)` delegates in compatibility mode; a `Migration(1, 2) { db -> … }` factory function also exists |
| Room 2.8.4 `Room.databaseBuilder(context, klass: Class<T>, name)` and `RoomDatabase.Builder.addMigrations(vararg)`, `fallbackToDestructiveMigration*()` | same jar, `Room.android.kt`, `RoomDatabase.android.kt` | present |
| `viewModel(viewModelStoreOwner, key, initializer: CreationExtras.() -> VM)` | AndroidX sources, `lifecycle-viewmodel-compose/src/commonMain/.../ViewModel.kt` | present (reified inline overload) |
| `viewModelFactory { initializer { } }` | AndroidX sources, `lifecycle-viewmodel/src/commonMain/.../InitializerViewModelFactory.kt` | present |
| OkHttp 5 `Response.body` non-null | OkHttp CHANGELOG, 5.0.0-alpha.7 entry | non-null since 5.0.0-alpha.7; `okhttp3.coroutines` moved to `okhttp-coroutines` (5.0.0-alpha.14) |
| kotlinx.serialization R8 rules | kotlinx.serialization README | rules bundled with the library; extra rules needed only for named companion objects |
| Navigation type-safe routes (`@Serializable` routes, `composable<T>`, `navigate(T)`, `toRoute<T>()`) | developer.android.com type-safety guide | available since Navigation 2.8.0 |
| Strict tool use JSON-Schema subset | platform.claude.com strict-tool-use + structured-outputs "JSON Schema limitations" | supported: all basic types incl. null, `enum` (strings/numbers/bools/nulls), `const`, `anyOf`/`allOf` (limits), `$ref`/`$defs`, `default`, `required` (subset allowed), `additionalProperties: false` (mandatory), string formats, `minItems` 0/1; unsupported: recursion, numeric constraints, string length constraints, other array constraints |
| Preserved thinking / history-editing check | claude-api skill `shared/model-migration.md` (2026-09 build) | enforced for organisations created on/after 2026-08-31 on Claude Fable 5.1 / Mythos 5.1; planned for future models; "simple compaction" recommended; keep-tail compaction breaks |
| Opus 5 request rules (adaptive thinking default, `disabled` only ≤ high effort, no sampling params, no prefill, `output_config.effort`, `stop_details`) | claude-api skill quick reference | consistent with R1 |

Not verified (explicit):

1. `material-icons-extended` 1.7.8 binary compatibility with Compose 1.12 (R2 U9) — inherited.
2. Whether `Room.databaseBuilder` with KSP-generated `AppDatabase_Impl` compiles under AGP 9.4's
   built-in Kotlin with KSP 2.3.11 — inherited from R2's compose-samples evidence (AGP 9.3.1).
3. AGP packaging of `:core` class-path resources into the APK — inferred from OkHttp's own
   `publicsuffixes.gz` being loaded by `getResourceAsStream` on Android (R2 G10); a one-line
   `assets/` fallback exists if it fails (`KnowledgeSource` interface).
4. The exact `CreationExtras` receiver type parameter of the `viewModel` initializer when used
   without `key` — signature verified; behaviour at runtime not.
5. `TopAppBar`'s experimental status in Material 3 1.4.0 — handled by unconditional `@OptIn`.
6. The Anthropic OAuth token *prefix* (only the API-key prefix `sk-ant-api` is known from R1);
   the app therefore whitelists API keys rather than blacklisting OAuth tokens.
7. SSE `ping` cadence and hence the 120 s read timeout (R1 §4.4) — engineering default.
8. Claude Code `-p` flags for the bridge (R1 §4.7) — the PC side is not in v1.
9. Everything R3 §12 lists (numbers tagged weak/unverified) — carried into `PlannerConstants`
   and `confidence = "unverified"` explanations.
