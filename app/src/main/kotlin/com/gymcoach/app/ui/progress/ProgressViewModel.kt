package com.gymcoach.app.ui.progress

import com.gymcoach.app.ui.today.ScopedViewModel
import com.gymcoach.app.ui.today.TrainingDeps
import com.gymcoach.app.ui.today.uiDispatcher
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.ChartSeries
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Joint
import com.gymcoach.core.model.MuscleBandRow
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.model.SessionStatus
import com.gymcoach.core.time.Weeks
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One main-lift card (§8.11). `spark` is the e1RM series (load × reps for gym-scoped machine lifts). */
data class LiftCard(
    val exerciseId: String, val name: String, val bestE1rm14d: Double?, val delta4w: Double?, val spark: ChartSeries,
    val lowConfidence: Boolean, val gymScoped: Boolean,
)

data class ProgressUiState(
    val range: Int, val strength: List<LiftCard>, val volume: List<MuscleBandRow>, val weight: ChartSeries, val weightMa7: ChartSeries,
    val readiness: List<Pair<LocalDate, ReadinessBand>>, val sleepAvg: Double?, val rhr: ChartSeries?, val cardioMinutesByWeek: List<Pair<LocalDate, Int>>,
    val trainingLoad: List<Pair<LocalDate, Int>>, val spikeNote: Explanation?, val consistency: List<Pair<LocalDate, String>>, val mobilityMinutes: Map<Joint, Int>,
    val loading: Boolean = true, val error: String? = null,
)

class ProgressViewModel(
    private val deps: TrainingDeps,
    dispatcher: CoroutineDispatcher = uiDispatcher(),
) : ScopedViewModel(dispatcher) {
    private val empty = ChartSeries("", emptyList())
    private val _state = MutableStateFlow(ProgressUiState(90, emptyList(), emptyList(), empty, empty, emptyList(), null, null, emptyList(), emptyList(), null, emptyList(), emptyMap()))
    val state: StateFlow<ProgressUiState> = _state

    init { scope.launch { load(90) } }

    fun onRange(days: Int) { scope.launch { load(days) } }

    private suspend fun load(days: Int) {
        try {
            val today = deps.clock.today()
            val from = today.minusDays(days.toLong())
            val kb = deps.knowledge
            val series = deps.progressSeries
            val profile = deps.profile.get()
            val states = deps.progression.getAll()
            val metrics = deps.metrics.bodyMetricsBetween(from, today)
            val readinessAll = deps.metrics.readinessBetween(from, today)
            val sessions = deps.workouts.sessionsBetween(from, today)
            val weekStart = Weeks.weekStart(today, deps.settings.get().weekStartsOnMonday)
            val sets = deps.workouts.setsForSessions(sessions.map { it.id }).filter { it.supersededById == null }
            val meso = deps.plans.getCurrent()
            val week = meso?.weeks?.firstOrNull { it.startDate == weekStart }
            val c = deps.coordinator.constants()
            val mobility = if (profile == null) emptyMap() else deps.mobilityFactory(c).weeklyMinutes(profile.limitations, sets, weekStart)
            val (raw, ma7) = series.weight(metrics)
            val volume = if (week == null || profile == null) emptyList() else
                series.volume(sets.filter { !it.loggedAt.atZone(deps.clock.zone()).toLocalDate().isBefore(weekStart) }, weekStart, week.targets, com.gymcoach.core.volume.MuscleTiers.assign(profile.priorityMuscles))
            val load = withContext(deps.computeDispatcher) { series.trainingLoad(sessions, 8, today) }
            val cardio = deps.metrics.cardioBetween(from, today).groupBy { Weeks.weekStart(it.date) }.map { (w, logs) -> w to logs.sumOf { it.minutes } }.sortedBy { it.first }
            _state.value = ProgressUiState(
                range = days, strength = liftCards(states, today), volume = volume, weight = raw, weightMa7 = ma7,
                readiness = readinessAll.groupBy { it.date }.map { (d, l) -> d to l.maxBy { it.loggedAt }.band }.sortedBy { it.first },
                sleepAvg = readinessAll.mapNotNull { it.sleepHours }.takeIf { it.isNotEmpty() }?.average(),
                rhr = series.restingHr(metrics), cardioMinutesByWeek = cardio, trainingLoad = load, spikeNote = spike(load, c.rdy.rdy06SpikeFactor),
                consistency = consistency(sessions, meso, from, today), mobilityMinutes = mobility, loading = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    /** Main lifts = exercises with an e1RM; dashed (lowConfidence) when the newest point is above 10 reps; machines show load × reps per gym. */
    private fun liftCards(states: List<ProgressionState>, today: LocalDate): List<LiftCard> {
        val byExercise = states.groupBy { it.key.exerciseId }
        return byExercise.entries.mapNotNull { (id, list) ->
            val ex = deps.knowledge.exercise(id) ?: return@mapNotNull null
            val scoped = list.any { it.key.gymScope != null }
            val spark = if (scoped) deps.progressSeries.loadXReps(list.maxBy { it.updatedAt }) else deps.progressSeries.e1rm(list, id)
            val best = list.mapNotNull { it.bestE1rm14d }.maxOrNull()
            if (spark.points.isEmpty() && best == null) return@mapNotNull null
            val cutoff = today.minusDays(28).toEpochDay()
            val last = spark.points.lastOrNull()
            val earlier = spark.points.lastOrNull { it.dateEpochDay <= cutoff }
            LiftCard(id, ex.name, best, if (last != null && earlier != null) Math.round((last.value - earlier.value) * 10.0) / 10.0 else null, spark,
                lowConfidence = last?.lowConfidence ?: false, gymScoped = scoped)
        }.sortedByDescending { it.spark.points.size }.take(6)
    }

    /** RDY-06: a week above spikeFactor × the mean of the previous 4 weeks. */
    private fun spike(load: List<Pair<LocalDate, Int>>, factor: Double): Explanation? {
        if (load.size < 2) return null
        val last = load.last().second
        val prior = load.dropLast(1).takeLast(4).map { it.second }
        val avg = prior.average()
        if (prior.isEmpty() || avg <= 0.0 || last <= factor * avg) return null
        return Explanation(listOf(RuleIds.RDY_06), "Training load this week is ${Math.round(100.0 * last / avg)} % of your previous 4-week average (spike limit ${Math.round(factor * 100)} %): consider an easier week.")
    }

    private fun consistency(sessions: List<com.gymcoach.core.model.SessionRecord>, meso: com.gymcoach.core.model.Mesocycle?, from: LocalDate, today: LocalDate): List<Pair<LocalDate, String>> {
        val weeks = ArrayList<LocalDate>()
        var w = Weeks.weekStart(from)
        while (!w.isAfter(today)) { weeks.add(w); w = w.plusDays(7) }
        return weeks.map { start ->
            val done = sessions.count { it.status == SessionStatus.COMPLETED && !it.date.isBefore(start) && it.date.isBefore(start.plusDays(7)) }
            val planned = meso?.weeks?.firstOrNull { it.startDate == start }?.sessions?.size
            start to (if (planned == null) "$done" else "$done/$planned")
        }
    }
}
