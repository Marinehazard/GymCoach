package com.gymcoach.core.seed

import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.knowledge.SessionTemplates
import com.gymcoach.core.model.E1rmConfidence
import com.gymcoach.core.model.E1rmEstimate
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.ProgressionStatus
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.SessionSummary
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.progression.E1rm
import java.time.LocalDate

/**
 * Loader, validator and derived views of a [SeedProfile] (SPEC §3.11). Pure; the only I/O is [classpath]. :app calls
 * [classpath] on first run to prefill onboarding; tests and the joe_home_5day golden case build [planInputs] from it.
 */
object SeedProfiles {
    const val RESOURCE_DIR: String = "/gymcoach/profiles/"
    const val JOE_V1: String = "joe_v1"
    const val SCHEMA_VERSION: Int = 1
    const val TOP_SUFFIX: String = ":top"
    const val BACKOFF_SUFFIX: String = ":backoff"
    private const val HISTORY_SIZE: Int = 8

    fun decode(text: String): SeedProfile = CoreJson.assets.decodeFromString(SeedProfile.serializer(), text)

    fun encode(seed: SeedProfile): String = CoreJson.assets.encodeToString(SeedProfile.serializer(), seed)

    /** The shipped seed `<id>.json` from the class path (the :core jar inside the APK), or null when it is not shipped. */
    fun classpath(id: String = JOE_V1): SeedProfile? {
        val stream = SeedProfiles::class.java.getResourceAsStream("$RESOURCE_DIR$id.json") ?: return null
        return decode(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    /** Violations of the seed against the knowledge base; empty = valid. */
    fun validate(seed: SeedProfile, kb: KnowledgeBase): List<String> {
        val out = ArrayList<String>()
        val p = seed.profile
        if (seed.schemaVersion != SCHEMA_VERSION) out += "schema_version ${seed.schemaVersion} != $SCHEMA_VERSION"
        if (p.daysPerWeek !in 1..6) out += "days_per_week ${p.daysPerWeek} outside 1..6"
        if (p.sessionMinutes !in 30..120) out += "session_minutes ${p.sessionMinutes} outside 30..120"
        if (p.priorityMuscles.size > 3) out += "more than 3 priority muscles"
        if (p.preferredWeekdays.any { it !in 1..7 }) out += "preferred_weekdays outside 1..7"
        p.splitId?.let { id ->
            val row = SessionTemplates.personalSplit(kb, id)
            if (row == null) out += "split_id $id is not a custom row of templates.json"
            else if (p.preferredWeekdays.isNotEmpty() && p.preferredWeekdays.size != row.daysPerWeek) out += "preferred_weekdays ${p.preferredWeekdays} do not match $id (${row.daysPerWeek} days)"
        }
        p.barbellRirFloor?.let { if (it !in 0..4) out += "barbell_rir_floor $it outside 0..4" }
        val exerciseRefs = p.knownLoadsKg.keys + (p.strengthTargetsKg?.keys ?: emptySet()) + p.likedExerciseIds + p.dislikedExerciseIds +
            seed.history.sets.map { it.exerciseId } + seed.progressionSeeds.map { it.exerciseId }
        for (id in exerciseRefs.toSortedSet()) if (kb.exercise(id) == null) out += "unknown exercise $id"
        for (l in p.limitations) if (l.pain0to10 !in 0..10) out += "limitation ${l.id}: pain ${l.pain0to10} outside 0..10"
        if (p.limitations.map { it.id }.toSet().size != p.limitations.size) out += "duplicate limitation ids"

        val gymIds = seed.gyms.map { it.id }
        if (gymIds.toSet().size != gymIds.size) out += "duplicate gym ids"
        if (seed.defaultGymId !in gymIds) out += "default_gym_id ${seed.defaultGymId} is not a seed gym"
        if (seed.gyms.count { it.isDefault } != 1) out += "exactly one seed gym must be is_default"
        for (g in seed.gyms) {
            for (item in g.items) {
                val def = kb.equipment[item.equipmentId]
                if (def == null) { out += "gym ${g.id}: unknown equipment ${item.equipmentId}"; continue }
                val declared = def.params.map { it.key }.toSet()
                for (k in item.params.keys) if (k !in declared) out += "gym ${g.id}: ${item.equipmentId}.$k is not a declared param"
            }
            if (g.items.map { it.equipmentId }.toSet().size != g.items.size) out += "gym ${g.id}: duplicate equipment"
        }

        val sessionIds = seed.history.sessions.map { it.id }
        if (sessionIds.toSet().size != sessionIds.size) out += "duplicate history session ids"
        for (r in seed.history.sessions) if (r.gymId !in gymIds) out += "history session ${r.id}: unknown gym ${r.gymId}"
        val setIds = seed.history.sets.map { it.id }
        if (setIds.toSet().size != setIds.size) out += "duplicate history set ids"
        for (s in seed.history.sets) {
            if (s.sessionId !in sessionIds) out += "set ${s.id}: unknown session ${s.sessionId}"
            s.rir?.let { if (it !in 0..10) out += "set ${s.id}: rir $it outside 0..10" }
            if (s.weight != null && s.weight.unit == com.gymcoach.core.model.WeightUnit.KG && kotlin.math.abs(s.weight.entered - s.weight.kg) > 1e-9) out += "set ${s.id}: kg value differs from the entered kg"
        }
        for (ps in seed.progressionSeeds) {
            if (ps.workingLoadKg <= 0.0) out += "progression seed ${ps.exerciseId}: load ${ps.workingLoadKg}"
            if (ps.targetRir !in 0..4) out += "progression seed ${ps.exerciseId}: target_rir ${ps.targetRir}"
        }
        val trackKeys = seed.progressionSeeds.map { it.exerciseId + "#" + it.variant }
        if (trackKeys.toSet().size != trackKeys.size) out += "duplicate progression seed tracks"
        return out
    }

    /** Best e1RM per exercise over the whole seeded history (E1RM-01 at reps + RIR; HIGH confidence preferred). */
    fun bestE1rm(seed: SeedProfile, c: PlannerConstants = PlannerConstants.DEFAULT): Map<String, E1rmEstimate> {
        val dates = seed.history.sessions.associate { it.id to it.date }
        val out = sortedMapOf<String, E1rmEstimate>()
        for (s in seed.history.sets) {
            val e = estimate(s, dates, c) ?: continue
            val cur = out[s.exerciseId]
            if (cur == null || better(e, cur)) out[s.exerciseId] = e
        }
        return out
    }

    /**
     * ProgressionStates of the seeded tracks (PROGRESSING): the working load is the seed's (the user's own next target), the
     * history holds one SessionSummary per logged session of that track (newest first, ≤ 8) and bestE1rm14d is the best
     * HIGH estimate of the 14 days up to the track's last session. Keyed for [gymId]'s equipment (gym-scoped load types).
     */
    fun progressionStates(seed: SeedProfile, kb: KnowledgeBase, gymId: String = seed.defaultGymId, c: PlannerConstants = PlannerConstants.DEFAULT): List<ProgressionState> {
        val gym = seed.gyms.firstOrNull { it.id == gymId } ?: seed.gyms.first()
        val resolver = EquipmentResolver(kb)
        val dates = seed.history.sessions.associate { it.id to it.date }
        val out = ArrayList<ProgressionState>()
        for (ps in seed.progressionSeeds) {
            val ex = kb.exercise(ps.exerciseId) ?: continue
            val lt = resolver.resolve(ex, SessionEquipment(gym)).effectiveLoadType ?: ex.loadType
            val key = ProgressionKey.forExercise(ex.id, lt, gym.id).copy(variant = ps.variant)
            val sets = seed.history.sets.filter { it.exerciseId == ex.id && it.supersededById == null && it.kind != SetKind.RAMP && belongs(it, ps.variant) }
            val bySession = sets.groupBy { it.sessionId }.entries.sortedByDescending { dates[it.key] ?: LocalDate.MIN }
            val summaries = bySession.mapNotNull { (sid, rows) -> summary(rows, dates[sid] ?: return@mapNotNull null, ps.reps.hi, ps.targetRir, c) }.take(HISTORY_SIZE)
            val last = summaries.firstOrNull()?.date
            val best = last?.let { l ->
                summaries.filter { it.e1rm?.confidence == E1rmConfidence.HIGH && !it.date.isBefore(l.minusDays((c.prog.e1rm04RollingDays - 1).toLong())) }.maxByOrNull { it.e1rm?.kg ?: 0.0 }
            }
            out += ProgressionState(
                key = key, status = ProgressionStatus.PROGRESSING, workingLoadKg = ps.workingLoadKg, workingReps = ps.reps, repHiBase = ps.reps.hi,
                targetRir = ps.targetRir, lastSessionDate = last, bestE1rm14d = best?.e1rm?.kg, bestE1rm14dDate = best?.date,
                history = summaries, updatedAt = seed.profile.updatedAt,
            )
        }
        return out.sortedBy { it.key.id }
    }

    /**
     * PlanInputs of the seed for a plan built on [today] at [gymId] (default: the seed's default gym): profile, gyms,
     * seeded states, the history of the last 12 weeks, body metrics; the mesocycle anchored at [anchor].
     */
    fun planInputs(seed: SeedProfile, kb: KnowledgeBase, today: LocalDate, anchor: LocalDate? = today, gymId: String = seed.defaultGymId): PlanInputs {
        val from = today.minusWeeks(12)
        val sessions = seed.history.sessions.filter { !it.date.isBefore(from) && it.date.isBefore(today) }.sortedBy { it.date }
        val ids = sessions.map { it.id }.toSet()
        return PlanInputs(
            profile = seed.profile, gyms = seed.gyms, defaultGymId = gymId, overrides = emptyList(),
            progressionStates = progressionStates(seed, kb, gymId), recentSessions = sessions,
            recentSets = seed.history.sets.filter { it.sessionId in ids && it.supersededById == null },
            bodyMetrics = seed.bodyMetrics.filter { !it.date.isBefore(from) }, weeklyCheckins = emptyList(), readinessHistory = emptyList(),
            mesocycleAnchor = anchor,
        )
    }

    private fun belongs(s: SetLog, variant: String?): Boolean {
        val block = s.blockId ?: return variant == null
        return when (variant) {
            null -> block.endsWith(TOP_SUFFIX) || (!block.endsWith(BACKOFF_SUFFIX))
            else -> block.endsWith(":$variant")
        }
    }

    private fun summary(rows: List<SetLog>, date: LocalDate, repHi: Int, targetRir: Int, c: PlannerConstants): SessionSummary? {
        val working = rows.filter { it.reps != null && it.reps > 0 && it.weight != null }
        if (working.isEmpty()) return null
        val heaviest = working.maxWith(compareBy<SetLog>({ it.weight?.kg ?: 0.0 }, { it.reps ?: 0 }))
        val estimates = working.mapNotNull { estimate(it, mapOf(it.sessionId to date), c) }
        val best = estimates.filter { it.confidence == E1rmConfidence.HIGH }.maxByOrNull { it.kg } ?: estimates.maxByOrNull { it.kg }
        return SessionSummary(
            date = date, loadKg = heaviest.weight?.kg, bestReps = working.maxOf { it.reps ?: 0 }, minRir = working.mapNotNull { it.rir }.minOrNull(),
            allSetsAtHi = working.all { (it.reps ?: 0) >= repHi && (it.rir ?: targetRir) <= targetRir }, e1rm = best,
        )
    }

    private fun estimate(s: SetLog, dates: Map<String, LocalDate>, c: PlannerConstants): E1rmEstimate? {
        if (s.supersededById != null || s.kind == SetKind.RAMP) return null
        val w = s.weight?.kg ?: return null
        val reps = s.reps ?: return null
        val date = dates[s.sessionId] ?: return null
        return E1rm.estimate(if (s.perHand) w * 2 else w, reps, s.rir, date, c.prog.e1rm02MaxReps)
    }

    private fun better(a: E1rmEstimate, b: E1rmEstimate): Boolean =
        if (a.confidence != b.confidence) a.confidence == E1rmConfidence.HIGH else a.kg > b.kg
}
