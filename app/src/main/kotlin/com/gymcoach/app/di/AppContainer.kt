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
