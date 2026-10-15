@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gymcoach.app.R
import com.gymcoach.app.ui.components.BigPrimaryButton
import com.gymcoach.app.ui.components.ExplanationChip
import com.gymcoach.core.units.Units
import androidx.lifecycle.ViewModel
import com.gymcoach.app.coordinator.PlanCoordinator
import com.gymcoach.core.coach.ProposalApplier
import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.equipment.GymParams
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.AdjustmentKind
import com.gymcoach.core.model.CardioKind
import com.gymcoach.core.model.CardioPrescription
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlannedSession
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.Proposal
import com.gymcoach.core.model.ReadinessCheckin
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.SessionPlan
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.mobility.MobilityPlanner
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.mutation.MutationValidator
import com.gymcoach.core.planner.Planner
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.nutrition.SupplementAdvisor
import com.gymcoach.core.progression.ProgressSeries
import com.gymcoach.core.progression.ProgressionEngine
import com.gymcoach.core.readiness.ReadinessScorer
import com.gymcoach.core.repo.CoachRepository
import com.gymcoach.core.repo.GymRepository
import com.gymcoach.core.repo.MetricsRepository
import com.gymcoach.core.repo.PlanRepository
import com.gymcoach.core.repo.ProfileRepository
import com.gymcoach.core.repo.ProgressionRepository
import com.gymcoach.core.repo.SettingsRepository
import com.gymcoach.core.repo.WorkoutRepository
import com.gymcoach.core.substitution.SubstitutionEngine
import com.gymcoach.core.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

// Pure types and helpers (JVM-testable) live above the UI marker; the composables below it.

/**
 * Everything the WP-07 ViewModels read, as plain constructor parameters (SPEC §1.4 allows the VM to take
 * the whole AppContainer; WP-07 takes this narrower holder instead so the VMs run on a plain JVM —
 * `trainingDepsOf(container)` in TodayScreen.kt builds it from the container).
 */
class TrainingDeps(
    val clock: Clock,
    val knowledge: KnowledgeBase,
    val settings: SettingsRepository,
    val profile: ProfileRepository,
    val gyms: GymRepository,
    val plans: PlanRepository,
    val workouts: WorkoutRepository,
    val progression: ProgressionRepository,
    val metrics: MetricsRepository,
    val coach: CoachRepository,
    val coordinator: PlanCoordinator,
    val proposalApplier: ProposalApplier,
    val progressSeries: ProgressSeries,
    val plannerFactory: (PlannerConstants) -> Planner,
    val engineFactory: (PlannerConstants) -> ProgressionEngine,
    val substitutionFactory: (PlannerConstants) -> SubstitutionEngine,
    val readinessFactory: (PlannerConstants) -> ReadinessScorer,
    val supplementFactory: (PlannerConstants) -> SupplementAdvisor,
    val validatorFactory: (PlannerConstants) -> MutationValidator,
    val mobilityFactory: (PlannerConstants) -> MobilityPlanner,
    val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
)

/** Common scope handling: a ViewModel-owned scope on an injectable dispatcher (tests pass a test dispatcher). */
abstract class ScopedViewModel(dispatcher: CoroutineDispatcher) : ViewModel() {
    protected val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
    override fun onCleared() { scope.cancel() }
    /** Test hook: cancels every coroutine of this ViewModel. */
    fun shutdown() { scope.cancel() }
}

internal fun uiDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate

/** Maps the rule ids of a planner modifier to the Adjustment kind recorded for it (POL-01). */
fun adjustmentKindFor(ruleIds: List<String>): AdjustmentKind {
    val first = ruleIds.firstOrNull().orEmpty()
    return when {
        first.startsWith("RDY") || first.startsWith("SLP") || first.startsWith("RED") -> AdjustmentKind.READINESS
        first.startsWith("LAY") -> AdjustmentKind.LAYOFF
        first.startsWith("TIME") -> AdjustmentKind.TIME_BUDGET
        first.startsWith("TRV") || first == RuleIds.SUB || first.startsWith("R4") -> AdjustmentKind.EQUIPMENT
        first.startsWith("DLD") -> AdjustmentKind.DELOAD
        first.startsWith("PROG") || first.startsWith("PLAT") -> AdjustmentKind.PROGRESSION
        first.startsWith("VOL") || first == RuleIds.REBAL -> AdjustmentKind.VOLUME
        first == RuleIds.ROUNDING -> AdjustmentKind.ROUNDING
        first.startsWith("INJ") || first.startsWith("RIR") -> AdjustmentKind.SAFETY
        else -> AdjustmentKind.OVERRIDE
    }
}

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

enum class TodaySheet { GYM_SWITCHER, SESSION_START, CARDIO_LOG, PREVIEW }

/** One row of the gym switcher: how many of today's exercises this gym can do (§8.6). */
data class GymAvailability(val available: Int, val total: Int, val dumbbellWarning: Boolean)

sealed class TodayNav {
    data class Session(val sessionId: String) : TodayNav()
    data class Readiness(val date: String) : TodayNav()
    data class GymEdit(val gymId: String) : TodayNav()
    data class Conversation(val conversationId: String) : TodayNav()
    data class SessionSummary(val sessionId: String) : TodayNav()
    object Onboarding : TodayNav()
    object Checkin : TodayNav()
    object Nutrition : TodayNav()
    object AdjustmentLog : TodayNav()
}


/** Pure computations behind TodayUiState (kept out of the ViewModel for the 400-line limit). */
object TodayCompute {
    fun weekOf(meso: Mesocycle?, date: LocalDate): MesoWeek? = meso?.weeks?.let { weeks ->
        weeks.firstOrNull { !date.isBefore(it.startDate) && date.isBefore(it.startDate.plusDays(7)) } ?: weeks.lastOrNull { !it.startDate.isAfter(date) } ?: weeks.firstOrNull()
    }

    private suspend fun <T> safely(f: suspend () -> T): T? =
        try { f() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    /** Modality chips: the day's prescription first, then the gym's cardio machines (§8.6). */
    fun cardioModalities(deps: TrainingDeps, gym: GymSet?, cardio: CardioPrescription?): List<Exercise> {
        val kb = deps.knowledge
        val available = if (gym == null) emptySet() else EquipmentResolver(kb).availableExerciseIds(SessionEquipment(gym))
        val ids = LinkedHashSet<String>()
        cardio?.let { ids.add(it.exerciseId) }
        for (id in kb.exerciseOrder) if (id in available && kb.exercises[id]?.pattern == MovementPattern.CARDIO) ids.add(id)
        return ids.mapNotNull { kb.exercise(it) }
    }

    fun availabilityByGym(deps: TrainingDeps, gyms: List<GymSet>, plan: SessionPlan?, planned: PlannedSession?): Map<String, GymAvailability> {
        val ids = plan?.blocks?.map { it.exerciseId } ?: planned?.slots?.map { it.exerciseId } ?: emptyList()
        if (ids.isEmpty()) return gyms.associate { it.id to GymAvailability(0, 0, false) }
        val kb = deps.knowledge
        val resolver = EquipmentResolver(kb)
        return gyms.associate { gym ->
            val eq = SessionEquipment(gym)
            val avail = ids.count { id -> kb.exercise(id)?.let { resolver.resolve(it, eq).available } ?: false }
            val max = GymParams(kb, eq).dumbbellMaxKg
            val warn = max != null && (plan?.blocks ?: emptyList()).any { b ->
                b.effectiveLoadType == LoadType.DUMBBELL && b.sets.any { (it.loadKg ?: 0.0) > max }
            }
            gym.id to GymAvailability(avail, ids.size, warn)
        }
    }

    /** RED-02: pain above 3/10 persisting more than 10 days → "see a physiotherapist/physician" card (no diagnosis, POL-02). */
    fun referral(profile: Profile?, today: LocalDate): Explanation? {
        val l = profile?.limitations?.firstOrNull { it.active && it.pain0to10 > 3 && !it.since.plusDays(10).isAfter(today) } ?: return null
        val region = l.region.name.lowercase().replace('_', ' ')
        return Explanation(listOf(RuleIds.RED_02), "Pain above 3/10 in the $region for more than 10 days: please see a physiotherapist or physician. Exercises for other regions continue.")
    }

    suspend fun nutritionStrip(deps: TrainingDeps, today: LocalDate, bedtime: String?): NutritionStrip {
        val metrics = deps.metrics.bodyMetricsBetween(today.minusDays(28), today)
        val ma7 = safely { deps.progressSeries.weight(metrics).second.points }
        val last = ma7?.lastOrNull()
        val earlier = last?.let { l -> ma7?.lastOrNull { it.dateEpochDay <= l.dateEpochDay - 14 } }
        val weightKg = metrics.lastOrNull { it.weight != null }?.weight?.kg
        val perKg = safely { deps.plans.nutritionState()?.proteinGPerKgOverride }
        val cutoff = if (bedtime == null) null else safely { deps.supplementFactory(deps.coordinator.constants()).caffeineCutoffs(bedtime).second }
        val days = deps.metrics.nutritionBetween(today.minusDays(6), today).count { it.proteinTargetHit == true }
        return NutritionStrip(
            weightMa7Kg = last?.value, delta14Kg = if (last != null && earlier != null) last.value - earlier.value else null,
            proteinG = if (perKg != null && weightKg != null) Math.round(perKg * weightKg).toInt() else null,
            caffeineCutoff = cutoff, proteinDays7 = days,
        )
    }
}

// ==== UI ====

private fun minutesText(m: Double): String = Math.round(m).toString()

@Composable
fun TodayHeader(state: TodayUiState, onGym: () -> Unit, onReadiness: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(state.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) + ", " + state.date, style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onGym, label = { Text((state.gym?.name ?: stringResource(R.string.today_pick_gym)) + " ▾") },
                leadingIcon = { Icon(Icons.Filled.FitnessCenter, contentDescription = null) })
            val r = state.readiness
            AssistChip(onClick = onReadiness, label = {
                Text(if (r == null) stringResource(R.string.today_check_in) else r.band.name.lowercase().replaceFirstChar { it.uppercase() } + " " + r.score)
            })
        }
    }
}

/** Every SessionPlan.modifiers explanation with RuleTags + the "Train as planned" switch (§8.6). */
@Composable
fun ModifierSection(state: TodayUiState, onTrainAsPlanned: (Boolean) -> Unit, onRule: (String) -> Unit) {
    val mods = state.sessionPlan?.modifiers ?: emptyList()
    if (mods.isEmpty() && state.readiness?.band != com.gymcoach.core.model.ReadinessBand.AMBER && state.readiness?.band != com.gymcoach.core.model.ReadinessBand.RED) return
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in mods) ExplanationChip(explanation = m, onRule = onRule)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = state.trainAsPlanned, onCheckedChange = onTrainAsPlanned)
                Text(stringResource(R.string.today_train_as_planned))
            }
        }
    }
}

@Composable
fun SessionCard(
    state: TodayUiState, plan: SessionPlan, kb: KnowledgeBase, onStart: () -> Unit, onPreview: () -> Unit, on30: () -> Unit, onSkip: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(plan.name, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.today_session_meta, minutesText(plan.estimatedMinutes), plan.blocks.size), style = MaterialTheme.typography.bodyLarge)
            val muscles = plan.volume.fractional.entries.sortedByDescending { it.value }.take(4)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((m, v) in muscles) AssistChip(onClick = {}, label = { Text(m.name.lowercase().replace('_', ' ') + " " + Units.trim(v)) })
            }
            for (b in plan.blocks.take(8)) {
                val sub = if (b.substitutedFor != null) " ⇄" else ""
                Text("• " + (kb.exercise(b.exerciseId)?.name ?: b.exerciseId) + " ×" + b.sets.size + sub, style = MaterialTheme.typography.bodyMedium)
            }
            BigPrimaryButton(text = stringResource(R.string.today_start), onClick = onStart, enabled = state.gym != null)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPreview) { Text(stringResource(R.string.today_preview)) }
                FilterChip(selected = state.minutesToday == 30, onClick = on30, label = { Text(stringResource(R.string.today_30min)) })
                TextButton(onClick = onSkip) { Text(stringResource(R.string.today_skip)) }
            }
        }
    }
}

@Composable
fun ResumeCard(record: SessionRecord, nowEpochMs: Long, onResume: () -> Unit) {
    val elapsed = record.startedAt?.let { ((nowEpochMs - it.toEpochMilli()) / 60_000L).coerceAtLeast(0) } ?: 0L
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.today_resume, elapsed), style = MaterialTheme.typography.titleLarge)
            BigPrimaryButton(text = stringResource(R.string.today_resume_button), onClick = onResume)
        }
    }
}

@Composable
fun RestDayCard(state: TodayUiState, onLogCardio: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.today_rest_day), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.today_rest_body), style = MaterialTheme.typography.bodyLarge)
            if (state.cardio == null) OutlinedButton(onClick = onLogCardio) { Text(stringResource(R.string.today_log_walk)) }
        }
    }
}

@Composable
fun CardioCard(state: TodayUiState, kb: KnowledgeBase, onLog: () -> Unit, onRule: (String) -> Unit) {
    val c = state.cardio ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.today_cardio, c.minutes, kb.exercise(c.exerciseId)?.name ?: c.exerciseId), style = MaterialTheme.typography.titleMedium)
            val hrLo = c.hrLow
            val hrHi = c.hrHigh
            if (hrLo != null && hrHi != null) Text(stringResource(R.string.today_cardio_hr, hrLo, hrHi))
            ExplanationChip(explanation = c.explanation, onRule = onRule)
            if (state.cardioLoggedToday) Text(stringResource(R.string.today_cardio_logged), color = MaterialTheme.colorScheme.primary)
            else OutlinedButton(onClick = onLog) { Text(stringResource(R.string.today_cardio_log)) }
        }
    }
}

@Composable
fun DeloadCard(e: Explanation, onAccept: () -> Unit, onDismiss: () -> Unit, onRule: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.today_deload_title), style = MaterialTheme.typography.titleMedium)
            ExplanationChip(explanation = e, onRule = onRule)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) { Text(stringResource(R.string.today_deload_accept)) }
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.training_action_dismiss)) }
            }
        }
    }
}

/** A PLANNER proposal card (under-target "+1 day / +10 min", PLAT-02 swap …): accept writes an override, never automatic. */
@Composable
fun PlannerProposalCard(m: PlanMutation, onAccept: () -> Unit, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(m.rationale, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) { Text(stringResource(R.string.action_apply)) }
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_later)) }
            }
        }
    }
}

@Composable
fun NutritionStripCard(n: NutritionStrip, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.today_nutrition), style = MaterialTheme.typography.titleMedium)
            n.weightMa7Kg?.let { Text(stringResource(R.string.today_weight_ma7, Units.trim(Math.round(it * 10.0) / 10.0)) + (n.delta14Kg?.let { d -> "  (" + (if (d >= 0) "+" else "") + Units.trim(Math.round(d * 10.0) / 10.0) + " / 14 d)" } ?: "")) }
            n.proteinG?.let { Text(stringResource(R.string.today_protein, it)) }
            n.proteinDays7?.let { Text(stringResource(R.string.today_protein_days, it)) }
            n.caffeineCutoff?.let { Text(stringResource(R.string.today_caffeine, it)) }
        }
    }
}

/** Compact preview: every block with sets × reps @ RIR and the load (sheet content). */
@Composable
fun PreviewContent(plan: SessionPlan, kb: KnowledgeBase) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(plan.name, style = MaterialTheme.typography.titleLarge)
        for (b in plan.blocks) {
            val p = b.sets.firstOrNull()
            val reps = p?.reps?.let { "${it.lo}–${it.hi}" } ?: p?.seconds?.let { "${it.lo}–${it.hi} s" } ?: ""
            val load = p?.loadLabel ?: p?.loadKg?.let { Units.trim(it) + " kg" } ?: ""
            Text("${kb.exercise(b.exerciseId)?.name ?: b.exerciseId} — ${b.sets.size} × $reps @${p?.rir ?: "–"} $load", style = MaterialTheme.typography.bodyLarge)
        }
    }
}
