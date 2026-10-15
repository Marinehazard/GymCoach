package com.gymcoach.core.planner.realize

import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.equipment.GymParams
import com.gymcoach.core.equipment.LoadRounder
import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.Band
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Drill
import com.gymcoach.core.model.DrillKind
import com.gymcoach.core.model.DrillPrescription
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.ExerciseBlock
import com.gymcoach.core.model.Limitation
import com.gymcoach.core.model.LoadSource
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.LoadZone
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetPrescription
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.Warmup
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.planner.TimeModel

/**
 * §5.7 warm-up (WU-01..06, MOB-01, MOB-07). Raise: 3–5 min easy cardio on the first available of bike > rower > treadmill
 * walk > jump rope > body-weight calisthenics, 0 min when the session is ≤ 45 min (WU-05), longer under WU-06 (age ≥ 40,
 * cold, < 60 min since waking). Activate: dynamic drills (2 × 8–10) chosen by the session's first two patterns in drill-file
 * order, no duplicates (4, or 2 under WU-05), plus one drill per active limitation joint. Potentiate: WU-02 ramp sets
 * ([rampSets]) — RAMP sets never count as volume (WU-03). Static stretching never precedes lifting (WU-04).
 */
class WarmupGenerator(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    private val resolver = EquipmentResolver(kb)
    private val time = TimeModel(c)
    private val rounder = LoadRounder(kb)

    /** WU-06: age ≥ wu06AgeThreshold, cold ambient or fewer than wu06MinutesSinceWakingThreshold minutes since waking. */
    fun wu06(today: TodayContext, age: Int): Boolean =
        age >= c.wu.wu06AgeThreshold || today.ambientCold || (today.minutesSinceWaking?.let { it < c.wu.wu06MinutesSinceWakingThreshold } ?: false)

    fun generate(blocks: List<ExerciseBlock>, sessionMinutes: Int, today: TodayContext, age: Int, limitations: List<Limitation>, equipment: SessionEquipment, params: GymParams, zones: Map<String, LoadZone>): Warmup {
        val wu06 = wu06(today, age)
        val total = time.warmupMinutes(sessionMinutes, wu06)
        val trimmed = sessionMinutes <= c.wu.wu05TrimAtOrBelowMinutes
        val raise = if (trimmed) 0 else total - ACTIVATE_MINUTES
        val raiseEx = if (raise > 0) raiseExercise(equipment) else null
        val patterns = blocks.sortedBy { it.order }.mapNotNull { b -> kb.exercise(b.exerciseId)?.pattern }.distinct().take(2)
        val drills = drills(patterns, if (trimmed) TRIMMED_DRILLS else FULL_DRILLS, limitations, equipment)
        val rules = ArrayList<String>()
        rules.add(RuleIds.WU_01)
        rules.add(RuleIds.MOB_01)
        rules.add(RuleIds.WU_04)
        if (trimmed) rules.add(RuleIds.WU_05)
        if (wu06) rules.add(RuleIds.WU_06)
        if (zones.isNotEmpty()) {
            rules.add(RuleIds.WU_02)
            rules.add(RuleIds.WU_03)
        }
        val rolling = kb.exercise(FOAM_ROLLING)?.let { resolver.resolve(it, equipment).available } == true
        if (rolling) rules.add(RuleIds.MOB_05)
        val msg = (if (trimmed) "Short session: no raise phase, ${drills.size} dynamic drills" else "${raise} min easy ${raiseEx?.let { kb.exercise(it)?.name } ?: "calisthenics (2 min brisk march + 20 squats + 10 push-ups)"}, then ${drills.size} dynamic drills") +
            "; ramp sets before the first exercise (they do not count as working sets). No static stretching before lifting." +
            (if (rolling) " Optional: 30–60 s foam rolling per tight muscle." else "")
        return Warmup(raise, raiseEx, drills, total,
            Explanations.of(rules, msg, mapOf("total_min" to total.toString(), "wu05" to trimmed.toString(), "wu06" to wu06.toString()), Confidence.MODERATE))
    }

    fun raiseExercise(equipment: SessionEquipment): String? {
        for (id in RAISE_ORDER) {
            val e = kb.exercise(id) ?: continue
            if (resolver.resolve(e, equipment).available) return id
        }
        return null
    }

    /** First matches in drill-file order (DYNAMIC, pattern in drill.patterns), the first two patterns' lists merged, no duplicates; + one per limitation joint. */
    fun drills(patterns: List<MovementPattern>, count: Int, limitations: List<Limitation>, equipment: SessionEquipment): List<DrillPrescription> {
        val chosen = ArrayList<Drill>()
        val lists = patterns.map { p -> kb.drills.filter { it.kind == DrillKind.DYNAMIC && p in it.patterns && usable(it, equipment) } }
        // Round-robin over the first two patterns' lists (each in drill-file order), deduplicated: a one-pattern session gets
        // exactly that pattern's §5.7 list; a two-pattern session alternates (full body: squat drill, push/pull drill, …).
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) for (l in lists) { val d = l.getOrNull(i) ?: continue; if (chosen.none { it.id == d.id }) chosen.add(d) }
        val pick = chosen.take(count).toMutableList()
        for (lim in limitations.filter { it.active }) {
            val extra = kb.drills.firstOrNull { it.kind == DrillKind.DYNAMIC && lim.region in it.limitationRegions && usable(it, equipment) && pick.none { p -> p.id == it.id } } ?: continue
            pick.add(extra)
        }
        return pick.map { d ->
            DrillPrescription(d.id, DRILL_SETS, d.doseReps ?: if (d.doseSeconds == null) DEFAULT_REPS else null, d.doseSeconds,
                Explanations.of(listOf(RuleIds.MOB_01, RuleIds.MOB_07), "${d.name}: $DRILL_SETS × ${d.doseReps?.let { "${it.lo}–${it.hi}" } ?: d.doseSeconds?.let { "${it.lo}–${it.hi} s" } ?: "8–10"}.",
                    mapOf("drill_id" to d.id, "joint" to d.joint.name.lowercase()), Confidence.WEAK))
        }
    }

    private fun usable(d: Drill, equipment: SessionEquipment): Boolean {
        val id = d.exerciseId ?: return true
        val e = kb.exercise(id) ?: return true
        return resolver.resolve(e, equipment).available
    }

    /**
     * WU-02 ramp sets for one block from its first WORKING load (already rounded): HEAVY/MAX_STRENGTH 40/55/70/82.5 % ×
     * 8/5/3/2; MODERATE 50/70 % × 8/5; LIGHT or a machine compound 60 % × 8; isolation 1 × 8 at 55 % unless the muscle is
     * already warm; later exercises of a warm session 1 set at 65 % × 5. One extra (lighter) set under WU-06. Rest 60 s.
     * Loads rounded by LoadRounder, never below the empty bar; no ramps without an external load.
     */
    fun rampSets(exercise: Exercise, loadType: LoadType, workingLoadKg: Double?, zone: LoadZone, first: Boolean, muscleWarm: Boolean, wu06: Boolean, params: GymParams): List<SetPrescription> {
        if (workingLoadKg == null || workingLoadKg <= 0.0) return emptyList()
        val w = c.wu
        val plan: List<Pair<Double, Int>> = when {
            !first && exercise.isIsolation -> if (muscleWarm) emptyList() else listOf(w.wu02IsolationPct to WU02_ISOLATION_REPS)
            !first -> listOf(w.wu02WarmPatternPct to WU02_WARM_REPS)
            exercise.isIsolation -> listOf(w.wu02IsolationPct to WU02_ISOLATION_REPS)
            else -> when (zone) {
                LoadZone.MAX_STRENGTH, LoadZone.HEAVY -> w.wu02HeavyPcts.zip(w.wu02HeavyReps)
                LoadZone.MODERATE -> if (loadType == LoadType.MACHINE || loadType == LoadType.CABLE) w.wu02LightPcts.zip(w.wu02LightReps) else w.wu02ModeratePcts.zip(w.wu02ModerateReps)
                LoadZone.LIGHT, LoadZone.POWER -> w.wu02LightPcts.zip(w.wu02LightReps)
            }
        }
        if (plan.isEmpty()) return emptyList()
        val withExtra = if (wu06 && first) listOf(plan.first().first * WU06_EXTRA_FACTOR to plan.first().second + WU06_EXTRA_REPS) + plan else plan
        val out = ArrayList<SetPrescription>()
        var lastKg = -1.0
        for ((pct, reps) in withExtra) {
            val r = rounder.round(workingLoadKg * pct, exercise, loadType, params)
            if (r.kg >= workingLoadKg - 1e-9) continue
            if (r.kg <= lastKg + 1e-9) continue
            lastKg = r.kg
            out.add(SetPrescription(0, SetKind.RAMP, Band(reps, reps), null, null, r.kg, r.label, r.perHand, null, w.wu02RampRestSec, LoadSource.PROGRESSION_STATE))
        }
        return out
    }

    companion object {
        const val FOAM_ROLLING: String = "foam_rolling"
        val RAISE_ORDER: List<String> = listOf("stationary_bike", "rowing_erg", "treadmill", "jump_rope")
        const val ACTIVATE_MINUTES: Int = 5
        const val FULL_DRILLS: Int = 4
        const val TRIMMED_DRILLS: Int = 2
        const val DRILL_SETS: Int = 2
        val DEFAULT_REPS: Band = Band(8, 10)
        const val WU02_ISOLATION_REPS: Int = 8
        const val WU02_WARM_REPS: Int = 5
        const val WU06_EXTRA_FACTOR: Double = 0.75
        const val WU06_EXTRA_REPS: Int = 2
    }
}
