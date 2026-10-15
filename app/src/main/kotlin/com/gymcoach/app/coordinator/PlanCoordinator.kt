package com.gymcoach.app.coordinator

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.Adjustment
import com.gymcoach.core.model.AdjustmentKind
import com.gymcoach.core.model.AppSettings
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.model.SessionCloseResult
import com.gymcoach.core.model.SessionMode
import com.gymcoach.core.model.SessionPlan
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.model.WeekRollResult
import com.gymcoach.core.model.WeeklyCheckin
import com.gymcoach.core.planner.Planner
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.progression.AfterSessionInput
import com.gymcoach.core.progression.DeloadPolicy
import com.gymcoach.core.progression.DeloadSignals
import com.gymcoach.core.progression.DeloadTrigger
import com.gymcoach.core.progression.ProgressionEngine
import com.gymcoach.core.repo.GymRepository
import com.gymcoach.core.repo.MetricsRepository
import com.gymcoach.core.repo.PlanRepository
import com.gymcoach.core.repo.ProfileRepository
import com.gymcoach.core.repo.ProgressionRepository
import com.gymcoach.core.repo.SettingsRepository
import com.gymcoach.core.repo.WorkoutRepository
import com.gymcoach.core.time.Clock
import com.gymcoach.core.time.Weeks
import com.gymcoach.core.util.Hashing
import com.gymcoach.core.util.IdGen
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The single composition point of WP-03's Planner and WP-04's ProgressionEngine / DeloadPolicy
 * (§7.6). Depends only on core.repo interfaces and :core services — never on AppContainer, data.db
 * or a ViewModel. Instantiated once by AppContainer.planCoordinator.
 *
 * Writers (recompute, rollWeekIfDue, onSessionClosed, recordDeloadDecision) are serialised by one
 * mutex; the reads (constants, buildInputs, realizeToday, deloadSuggestion, plannerProposals) write
 * nothing.
 */
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
    private val writeLock = Mutex()

    /** plannerProposals memo: (memo key, proposals). The key covers the inputs hash, the constants and the day. */
    @Volatile
    private var proposalsMemo: Pair<String, List<PlanMutation>>? = null

    /** PlannerConstants.withOverrides(DEFAULT, settings.plannerOverridesJson, CoreJson.stored). */
    suspend fun constants(): PlannerConstants = constantsOf(settings.get())

    /** Builds PlanInputs from repositories, hashes them, reuses the snapshot with the same hash, else runs Planner.plan and stores it. */
    suspend fun recompute(reason: String): Mesocycle = writeLock.withLock { recomputeLocked(reason) }

    /** Today's SessionPlan for the given TodayContext (never stored until the session starts). PURE: Planner.realize over buildInputs() — no DLD-03 evaluation, writes nothing. */
    suspend fun realizeToday(today: TodayContext): SessionPlan {
        val s = settings.get()
        val c = constantsOf(s)
        val inputs = build(s, c, clock.today()).inputs
        val planner = plannerFactory(c)
        val mesocycle = plans.getCurrent() ?: planner.plan(inputs, today.date).mesocycle
        val week = weekOrNearest(mesocycle, today.date) ?: throw IllegalStateException("the current plan has no weeks")
        val planned = week.sessions.firstOrNull { it.weekday == Weeks.isoWeekday(today.date) }
        return planner.realize(planned, week, inputs, today, inputs.progressionStates)
    }

    /**
     * Week rollover on first app open in a new week (settings.lastWeekRollover): Planner.rollWeek →
     * progression.saveAll(result.states) + one Adjustment per result.ladderNotes entry (PLAT-02) →
     * DLD-03 evaluation (appends the ScheduleDeload card to result.proposals, fills
     * result.deloadTriggers and records the onsets) → mesocycle clock → recompute("week_roll").
     * Returns null when no week boundary was crossed (also on the very first open and before onboarding).
     */
    suspend fun rollWeekIfDue(): WeekRollResult? {
        return writeLock.withLock { rollWeekLocked() }
    }

    private suspend fun rollWeekLocked(): WeekRollResult? {
        val s = settings.get()
        if (profile.get() == null) return null
        val today = clock.today()
        val weekStart = Weeks.weekStart(today, s.weekStartsOnMonday)
        val last = s.lastWeekRollover.toLocalDateOrNull()
        if (last == null) {
            settings.update { it.copy(lastWeekRollover = weekStart.toString()) }
            return null
        }
        if (!weekStart.isAfter(last)) return null

        val c = constantsOf(s)
        val inputs = build(s, c, today).inputs
        val rolled = plannerFactory(c).rollWeek(inputs, weekStart)

        // 1. PLAT-02 ladder persistence — before the recompute, so it already sees the advanced states.
        if (rolled.states.isNotEmpty()) progression.saveAll(rolled.states)
        val before = inputs.progressionStates.associateBy { it.key.id }
        for (note in rolled.ladderNotes) {
            workouts.recordAdjustment(ladderAdjustment(note, rolled.states, before))
        }

        // 2. DLD-03 orchestration over fresh states (WP-04's DeloadPolicy; Planner.rollWeek never sees it).
        val freshStates = progression.getAll().sortedBy { it.key.id }
        val freshInputs = inputs.copy(progressionStates = freshStates)
        val policy = deloadPolicyFactory(c)
        val signals = policy.signals(freshInputs, freshStates, today)
        val hit = policy.reactive(signals, today, freshInputs.deloadTriggersProposed.toSet())
        var result = rolled
        if (hit != null) {
            val active = signals.active(today, c.plat.dld03MinDays)
            val card = PlanMutation.ScheduleDeload(startThisWeek = true, rationale = hit.message)
            result = rolled.copy(
                proposals = rolled.proposals + card,
                deloadTriggers = active.map { triggerExplanation(it) },
            )
            recordOnsets(active)
        }

        // 3. Mesocycle clock (anchor, completed count, last deload week, travel pause).
        updateMesocycleClock(weekStart, rolled.nextMode, plans.getCurrent())
        settings.update { it.copy(lastWeekRollover = weekStart.toString()) }

        recomputeLocked("week_roll")
        return result
    }

    /** After a session is closed: ProgressionEngine.afterSession → save states + adjustments → recompute. */
    suspend fun onSessionClosed(sessionId: String): SessionCloseResult {
        return writeLock.withLock { sessionClosedLocked(sessionId) }
    }

    private suspend fun sessionClosedLocked(sessionId: String): SessionCloseResult {
        val record = workouts.getSession(sessionId) ?: throw IllegalArgumentException("unknown session $sessionId")
        val s = settings.get()
        val c = constantsOf(s)
        val today = clock.today()
        val inputs = build(s, c, today).inputs
        val planner = plannerFactory(c)
        val engine = engineFactory(c)
        val normalized = planner.normalize(inputs, record.date)
        val sets = workouts.setsForSession(sessionId)

        val keys: List<ProgressionKey> = record.plan?.blocks?.map { it.progressionKey }?.distinctBy { it.id }
            ?: sets.map { it.exerciseId }.distinct().mapNotNull { id ->
                knowledge.exercise(id)?.let { ex -> ProgressionKey.forExercise(ex.id, ex.loadType, record.gymId) }
            }
        val stored = inputs.progressionStates.associateBy { it.key.id }
        val now = clock.now()
        val states = keys.mapNotNull { key ->
            stored[key.id] ?: knowledge.exercise(key.exerciseId)?.let { ex ->
                engine.initialState(key, ex, normalized.goal, inputs.profile.knownLoadsKg[key.exerciseId], now)
            }
        }

        val readiness = record.readinessCheckinId?.let { id -> inputs.readinessHistory.firstOrNull { it.id == id } }
            ?: metrics.readinessFor(record.date)
        val mesocycle = plans.getCurrent()
        val weekIndex = record.plan?.weekIndex ?: mesocycle?.let { weekFor(it, record.date)?.index } ?: 1
        val weekMode = mesocycle?.weeks?.firstOrNull { it.index == weekIndex }?.mode ?: WeekMode.NORMAL
        val gym = gyms.get(record.gymId)
            ?: throw IllegalStateException("gym ${record.gymId} of session $sessionId not found")

        val travelWeek = weekMode == WeekMode.TRAVEL_MAINTENANCE ||
            Weeks.weekStart(record.date, s.weekStartsOnMonday) in inputs.travelWeeks
        val shortSleep = readiness?.sleepHours?.let { it < c.rdy.slp03NightHours } ?: false
        val red = readiness?.band == ReadinessBand.RED || record.plan?.mode == SessionMode.RED
        val illness = todayContextOf(s)?.takeIf { it.date == record.date }?.illnessSymptoms ?: false

        val result = engine.afterSession(
            AfterSessionInput(
                record = record,
                sets = sets,
                states = states,
                profile = inputs.profile,
                trainingAge = normalized.trainingAge,
                readinessBand = readiness?.band,
                weekMode = weekMode,
                weekIndex = weekIndex,
                gym = gym,
                today = today,
                externalCause = illness || travelWeek || shortSleep || red,
                firstMesocycle = normalized.firstMesocycle,
            )
        )
        if (result.states.isNotEmpty()) progression.saveAll(result.states)
        for (adjustment in result.adjustments) workouts.recordAdjustment(adjustment)
        recomputeLocked("session_closed")
        return result
    }

    /** PlanInputs from the repositories (windows of §7.6), incl. deloadTriggersActive from DeloadPolicy.signals (§4.4 VOL-08 hold). Pure read. */
    suspend fun buildInputs(): PlanInputs {
        val s = settings.get()
        return build(s, constantsOf(s), clock.today()).inputs
    }

    /** The current reactive-deload suggestion for Today (§4.11): DeloadPolicy.reactive(...) over fresh signals, or null. Pure read. */
    suspend fun deloadSuggestion(today: LocalDate): Explanation? {
        if (profile.get() == null) return null
        val s = settings.get()
        val c = constantsOf(s)
        val built = build(s, c, today)
        return deloadPolicyFactory(c).reactive(built.signals, today, built.inputs.deloadTriggersProposed.toSet())
    }

    /**
     * Records the user's decision on the Today card: AppSettings.deloadTriggersProposed += every ACTIVE
     * trigger's "<kind>@<onset>". Idempotent; a no-op when nothing is active (§4.11, §8.6).
     */
    suspend fun recordDeloadDecision(today: LocalDate) {
        writeLock.withLock {
            if (profile.get() != null) {
                val s = settings.get()
                val c = constantsOf(s)
                val active = build(s, c, today).signals.active(today, c.plat.dld03MinDays)
                if (active.isNotEmpty()) recordOnsets(active)
            }
        }
    }

    /**
     * PLANNER proposal cards of the current plan (§2.9 PlanOutput.proposals): plannerFactory(constants())
     * .plan(buildInputs(), today).proposals, memoised per inputsHash (+ constants + day). PURE — persists nothing.
     */
    suspend fun plannerProposals(today: LocalDate): List<PlanMutation> {
        if (profile.get() == null) return emptyList()
        val s = settings.get()
        val c = constantsOf(s)
        val inputs = build(s, c, today).inputs
        val key = memoKey(inputsHash(inputs), c, today)
        proposalsMemo?.let { (k, v) -> if (k == key) return v }
        val proposals = plannerFactory(c).plan(inputs, today).proposals
        proposalsMemo = key to proposals
        return proposals
    }

    // ---- internals -----------------------------------------------------------------------------------

    private class Built(val inputs: PlanInputs, val signals: DeloadSignals)

    private fun constantsOf(s: AppSettings): PlannerConstants =
        PlannerConstants.withOverrides(PlannerConstants.DEFAULT, s.plannerOverridesJson, CoreJson.stored)

    /**
     * Must run inside [writeLock]. Reuse rules: the stored snapshot whose Mesocycle.inputsHash equals
     * SHA-256(CoreJson.stored.encodeToString(PlanInputs)) is reused only while the constants are the
     * defaults (the hash does not cover Settings ▸ Advanced overrides); otherwise the planner runs and the
     * result is stored unless it equals the current plan.
     */
    private suspend fun recomputeLocked(@Suppress("UNUSED_PARAMETER") reason: String): Mesocycle {
        val today = clock.today()
        val s0 = settings.get()
        // The mesocycle clock starts with the first plan: an empty anchor becomes "today" BEFORE the
        // inputs are built, so the stored inputs (and their hash) are stable from the first plan on.
        if (s0.mesocycleAnchor.isBlank()) {
            settings.update { if (it.mesocycleAnchor.isBlank()) it.copy(mesocycleAnchor = today.toString()) else it }
        }
        val s = settings.get()
        val c = constantsOf(s)
        val inputs = build(s, c, today).inputs
        val hash = inputsHash(inputs)
        val defaults = c == PlannerConstants.DEFAULT
        val current = plans.getCurrent()
        if (defaults) {
            if (current != null && current.inputsHash == hash) return current
            val snapshot = plans.snapshotByInputsHash(hash)
            if (snapshot != null) {
                plans.saveSnapshot(snapshot, clock.now())
                return snapshot
            }
        }
        val output = plannerFactory(c).plan(inputs, today)
        proposalsMemo = memoKey(hash, c, today) to output.proposals
        if (current != null && output.mesocycle == current) return current
        plans.saveSnapshot(output.mesocycle, clock.now())
        return output.mesocycle
    }

    private suspend fun build(s: AppSettings, c: PlannerConstants, today: LocalDate): Built {
        val p = profile.get() ?: throw IllegalStateException("no profile stored yet — onboarding must save one before planning")
        val allGyms = gyms.getAll(includeArchived = false).sortedBy { it.id }
        val defaultGymId = allGyms.firstOrNull { it.isDefault }?.id ?: allGyms.firstOrNull()?.id ?: ""
        val overrides = plans.overrides()
        val states = progression.getAll().sortedBy { it.key.id }
        val from12w = today.minusWeeks(WINDOW_WEEKS)
        val sessions = workouts.sessionsBetween(from12w, today)
        val sets = workouts.setsForSessions(sessions.map { it.id })
        val bodyMetrics = metrics.bodyMetricsBetween(from12w, today)
        val weekly = metrics.latestWeeklyCheckins(WEEKLY_CHECKINS)
        val readiness = metrics.readinessBetween(today.minusDays(READINESS_DAYS - 1L), today)
        val base = PlanInputs(
            profile = p,
            gyms = allGyms,
            defaultGymId = defaultGymId,
            overrides = overrides,
            progressionStates = states,
            recentSessions = sessions,
            recentSets = sets,
            bodyMetrics = bodyMetrics,
            weeklyCheckins = weekly,
            readinessHistory = readiness,
            mesocycleAnchor = s.mesocycleAnchor.toLocalDateOrNull(),
            weekOfMesocycle = s.mesocycleWeekPausedAt.takeIf { it > 0 },
            travelWeeks = travelWeeks(overrides, weekly, today, s.weekStartsOnMonday, c),
            weekStartsOnMonday = s.weekStartsOnMonday,
            mesocyclesCompleted = s.mesocyclesCompleted,
            lastDeloadWeekStart = s.lastDeloadWeekStart.toLocalDateOrNull(),
            deloadTriggersProposed = decodeProposed(s.deloadTriggersProposed),
            deloadTriggersActive = emptyList(),
            patternLastTrained = patternLastTrained(),
        )
        // deloadTriggersActive is computed over the otherwise-complete inputs (§7.6).
        val signals = deloadPolicyFactory(c).signals(base, states, today)
        val active = signals.active(today, c.plat.dld03MinDays).map { triggerKey(it) }.distinct().sorted()
        return Built(base.copy(deloadTriggersActive = active), signals)
    }

    /** Newest logged date per movement pattern over ALL history (LAY-01, §4.7 step 2). */
    private suspend fun patternLastTrained(): Map<MovementPattern, LocalDate> {
        val zone = clock.zone()
        val newest = HashMap<MovementPattern, LocalDate>()
        for ((exerciseId, at) in workouts.lastLoggedAtByExercise()) {
            val pattern = knowledge.exercise(exerciseId)?.pattern ?: continue
            val date = at.atZone(zone).toLocalDate()
            val prev = newest[pattern]
            if (prev == null || date.isAfter(prev)) newest[pattern] = date
        }
        return newest.entries.sortedBy { it.key.ordinal }.associate { it.key to it.value }
    }

    /** Week starts in TRAVEL_MAINTENANCE: active SetTravelDays overrides + CHK Q10 ≥ trv01TravelDaysThreshold (the week after the check-in). */
    private fun travelWeeks(overrides: List<PlanOverride>, weekly: List<WeeklyCheckin>, today: LocalDate, mondayStart: Boolean, c: PlannerConstants): List<LocalDate> {
        val weeks = sortedSetOf<LocalDate>()
        for (o in overrides) {
            if (o.revokedAt != null) continue
            val exp = o.expiresOn
            if (exp != null && exp.isBefore(today)) continue
            val m = o.mutation as? PlanMutation.SetTravelDays ?: continue
            if (m.to.isBefore(m.from)) continue
            var d = m.from
            while (!d.isAfter(m.to)) {
                weeks.add(Weeks.weekStart(d, mondayStart))
                d = d.plusDays(1)
            }
        }
        for (wc in weekly) {
            val days = wc.answers.firstOrNull { it.questionId == Q10 }?.number ?: continue
            if (days >= c.trv.trv01TravelDaysThreshold) weeks.add(Weeks.weekStart(wc.weekStart, mondayStart).plusWeeks(1))
        }
        return weeks.toList()
    }

    private suspend fun recordOnsets(active: List<DeloadTrigger>) {
        val add = active.map { triggerKey(it) }
        if (add.isEmpty()) return
        settings.update { s ->
            val current = decodeProposed(s.deloadTriggersProposed)
            val next = (current + add).distinct()
            if (next == current) s else s.copy(deloadTriggersProposed = encodeProposed(next))
        }
    }

    /**
     * Mesocycle clock (§7.6), run at every week rollover BEFORE recompute:
     * - travel: a TRAVEL_MAINTENANCE week freezes the week index (mesocycleWeekPausedAt); when travel ends
     *   the anchor is shifted so the paused index resumes;
     * - end: the mesocycle ends after its last week, or after a DELOAD week that just finished (scheduled
     *   or an accepted reactive ScheduleDeload) → anchor = this week's start, mesocyclesCompleted + 1;
     * - deload: lastDeloadWeekStart is written when a DELOAD week starts (or, at the latest, when it ends).
     */
    private suspend fun updateMesocycleClock(weekStart: LocalDate, nextMode: WeekMode, current: Mesocycle?) {
        val prevWeekStart = weekStart.minusWeeks(1)
        val prevWeek = current?.let { weekFor(it, prevWeekStart) }
        val thisWeek = current?.let { weekFor(it, weekStart) }
        val mesoEnd = current?.weeks?.maxOfOrNull { it.startDate }?.plusWeeks(1)
        settings.update { s ->
            var next = s
            val travelling = nextMode == WeekMode.TRAVEL_MAINTENANCE
            if (travelling) {
                if (next.mesocycleWeekPausedAt == 0) {
                    val index = thisWeek?.index ?: prevWeek?.index?.plus(1) ?: 1
                    next = next.copy(mesocycleWeekPausedAt = index)
                }
            } else {
                if (next.mesocycleWeekPausedAt > 0) {
                    val paused = next.mesocycleWeekPausedAt
                    next = next.copy(
                        mesocycleAnchor = weekStart.minusWeeks((paused - 1).toLong()).toString(),
                        mesocycleWeekPausedAt = 0,
                    )
                } else {
                    val deloadJustEnded = prevWeek?.mode == WeekMode.DELOAD
                    val pastEnd = mesoEnd != null && !weekStart.isBefore(mesoEnd)
                    if (deloadJustEnded || pastEnd) {
                        next = next.copy(
                            mesocycleAnchor = weekStart.toString(),
                            mesocyclesCompleted = next.mesocyclesCompleted + 1,
                        )
                    }
                }
                val lastDeload = next.lastDeloadWeekStart.toLocalDateOrNull()
                if (nextMode == WeekMode.DELOAD || thisWeek?.mode == WeekMode.DELOAD) {
                    next = next.copy(lastDeloadWeekStart = weekStart.toString())
                } else if (prevWeek?.mode == WeekMode.DELOAD && (lastDeload == null || lastDeload.isBefore(prevWeekStart))) {
                    next = next.copy(lastDeloadWeekStart = prevWeekStart.toString())
                }
            }
            next
        }
    }

    private fun ladderAdjustment(note: Explanation, advanced: List<ProgressionState>, before: Map<String, ProgressionState>): Adjustment {
        val exerciseId = note.inputs["exercise_id"]
        val step = note.inputs["step"]?.trim()?.toIntOrNull()
        val after = advanced.firstOrNull { it.key.exerciseId == exerciseId }
        val prior = after?.let { before[it.key.id] }
        return Adjustment(
            id = IdGen.newId(),
            sessionId = null,
            exerciseId = exerciseId,
            kind = if (step == 4) AdjustmentKind.VOLUME else AdjustmentKind.PROGRESSION,
            ruleIds = listOf(RuleIds.PLAT_02),
            summary = note.message,
            inputs = note.inputs,
            beforeJson = prior?.let { CoreJson.stored.encodeToString(ProgressionState.serializer(), it) } ?: "{}",
            afterJson = after?.let { CoreJson.stored.encodeToString(ProgressionState.serializer(), it) } ?: "{}",
            source = OverrideSource.PLANNER,
            createdAt = clock.now(),
        )
    }

    private fun todayContextOf(s: AppSettings): TodayContext? {
        if (s.todayContextJson.isBlank()) return null
        return try {
            CoreJson.stored.decodeFromString(TodayContext.serializer(), s.todayContextJson)
        } catch (e: IllegalArgumentException) {   // SerializationException included
            null
        }
    }

    private companion object {
        const val WINDOW_WEEKS: Long = 12L
        const val WEEKLY_CHECKINS: Int = 8
        const val READINESS_DAYS: Int = 14
        const val Q10: String = "Q10"

        private val STRING_LIST = ListSerializer(String.serializer())

        fun inputsHash(inputs: PlanInputs): String =
            Hashing.sha256Hex(CoreJson.stored.encodeToString(PlanInputs.serializer(), inputs))

        fun memoKey(hash: String, c: PlannerConstants, today: LocalDate): String {
            val constantsPart = if (c == PlannerConstants.DEFAULT) "default"
            else Hashing.sha256Hex(CoreJson.stored.encodeToString(PlannerConstants.serializer(), c))
            return "$hash|$constantsPart|$today"
        }

        fun triggerKey(t: DeloadTrigger): String = "${t.kind}@${t.onset}"

        fun triggerExplanation(t: DeloadTrigger): Explanation = Explanation(
            ruleIds = listOf(t.ruleId),
            message = t.detail,
            inputs = mapOf("kind" to t.kind, "onset" to t.onset.toString()),
        )

        fun decodeProposed(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            return try {
                CoreJson.stored.decodeFromString(STRING_LIST, text)
            } catch (e: IllegalArgumentException) {   // SerializationException included
                emptyList()
            }
        }

        fun encodeProposed(list: List<String>): String = CoreJson.stored.encodeToString(STRING_LIST, list)

        fun weekFor(mesocycle: Mesocycle, date: LocalDate): MesoWeek? =
            mesocycle.weeks.firstOrNull { !date.isBefore(it.startDate) && date.isBefore(it.startDate.plusWeeks(1)) }

        fun weekOrNearest(mesocycle: Mesocycle, date: LocalDate): MesoWeek? {
            weekFor(mesocycle, date)?.let { return it }
            if (mesocycle.weeks.isEmpty()) return null
            val first = mesocycle.weeks.minByOrNull { it.startDate }
            val last = mesocycle.weeks.maxByOrNull { it.startDate }
            return if (first != null && date.isBefore(first.startDate)) first else last
        }

        fun String.toLocalDateOrNull(): LocalDate? {
            if (isBlank()) return null
            return try {
                LocalDate.parse(trim())
            } catch (e: DateTimeParseException) {
                null
            }
        }
    }
}
