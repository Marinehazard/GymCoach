package com.gymcoach.core.mobility

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.Band
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Drill
import com.gymcoach.core.model.DrillKind
import com.gymcoach.core.model.DrillPrescription
import com.gymcoach.core.model.Joint
import com.gymcoach.core.model.Limitation
import com.gymcoach.core.model.LimitationRegion
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.planner.PlannerConstants
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * §5.8 mobility program (MOB-02..07): for each joint the user marked `limitedRange` (active limitation), a chronic dose of
 * 30–60 s static holds (2–4 per day, ≥ 5 days/week, 5–10 min/week, MOB-02) scheduled as the cool-down's static holds
 * (MOB-04) and as the rest-day card; weekly minutes per joint include the MOB-03 credit of 30 s per full-ROM working set of
 * the joint's strength-through-ROM exercises. MOB-06 easy cool-down movement is an optional toggle, on by default only after HIIT.
 */
class MobilityPlanner(private val kb: KnowledgeBase, @Suppress("unused") private val c: PlannerConstants) {

    fun cooldown(limitations: List<Limitation>, sessionPatterns: List<MovementPattern>, afterHiit: Boolean): List<DrillPrescription> {
        val out = ArrayList<DrillPrescription>()
        for (joint in chronicJoints(limitations)) {
            val holds = staticDrills(joint).take(HOLDS_PER_SESSION)
            for (d in holds) out.add(hold(d, joint, cooldown = true))
        }
        if (afterHiit) {
            val easy = kb.drills.firstOrNull { it.kind == DrillKind.DYNAMIC && (sessionPatterns.isEmpty() || it.patterns.any { p -> p in sessionPatterns }) }
            if (easy != null && out.none { it.drillId == easy.id }) {
                out.add(DrillPrescription(easy.id, 1, easy.doseReps, easy.doseSeconds,
                    Explanations.of(listOf(RuleIds.MOB_06), "Optional easy cool-down after intervals (5–10 min).", mapOf("drill_id" to easy.id), Confidence.WEAK)))
            }
        }
        return out
    }

    /** §3.4.1 personal template stretches (after the session, so static holds are fine — MOB-04). Unknown drill ids are skipped. */
    fun personalCooldown(doses: List<com.gymcoach.core.model.DrillDose>): List<DrillPrescription> = doses.mapNotNull { dose ->
        val d = kb.drills.firstOrNull { it.id == dose.drillId } ?: return@mapNotNull null
        val seconds = dose.seconds ?: if (dose.reps == null) d.doseSeconds ?: HOLD_SECONDS else null
        DrillPrescription(d.id, dose.sets, dose.reps, seconds,
            Explanations.of(listOf(RuleIds.MOB_04, RuleIds.OVERRIDE), "${d.name}: ${dose.sets} × " + (seconds?.let { if (it.lo == it.hi) "${it.lo} s" else "${it.lo}–${it.hi} s" } ?: dose.reps?.let { "${it.lo}–${it.hi}" } ?: "") +
                (dose.note?.let { " ($it)" } ?: "") + " — your post-workout stretch.", mapOf("drill_id" to d.id), Confidence.WEAK))
    }

    fun restDayCard(limitations: List<Limitation>): List<DrillPrescription> {
        val out = ArrayList<DrillPrescription>()
        for (joint in chronicJoints(limitations)) {
            for (d in staticDrills(joint).take(HOLDS_PER_SESSION)) out.add(hold(d, joint, cooldown = false))
        }
        return out
    }

    /** joint → minutes this week incl. MOB-03 credit (30 s per full-ROM working set of the mapped exercises). */
    fun weeklyMinutes(limitations: List<Limitation>, sets: List<SetLog>, weekStart: LocalDate): Map<Joint, Int> {
        val joints = chronicJoints(limitations)
        val seconds = LinkedHashMap<Joint, Double>()
        for (j in joints) seconds[j] = 0.0
        val end = weekStart.plusDays(DAYS_PER_WEEK)
        for (s in sets) {
            if (s.supersededById != null) continue
            val d = s.loggedAt.atZone(ZoneOffset.UTC).toLocalDate()
            if (d.isBefore(weekStart) || !d.isBefore(end)) continue
            // Mobility drill completions (exercise id of a drill-backed mobility exercise, `seconds`).
            for (j in joints) {
                if (kb.drills.any { it.joint == j && (it.exerciseId == s.exerciseId || it.id == s.exerciseId) }) {
                    seconds[j] = (seconds[j] ?: 0.0) + (s.seconds ?: 0).toDouble()
                }
                if ((MOB03_CREDIT[j] ?: emptySet()).contains(s.exerciseId) && s.kind != SetKind.RAMP) {
                    seconds[j] = (seconds[j] ?: 0.0) + MOB03_SECONDS_PER_SET
                }
            }
        }
        val out = LinkedHashMap<Joint, Int>()
        for ((j, sec) in seconds) out[j] = Math.round(sec / 60.0).toInt()
        return out
    }

    /** Joints with a chronic dose: active limitations marked limitedRange, mapped region → joint (§3.6). */
    fun chronicJoints(limitations: List<Limitation>): List<Joint> =
        limitations.filter { it.active && it.limitedRange }.mapNotNull { jointOf(it.region) }.distinct().sortedBy { it.ordinal }

    private fun staticDrills(joint: Joint): List<Drill> = kb.drills.filter { it.joint == joint && it.kind == DrillKind.STATIC }

    private fun hold(d: Drill, joint: Joint, cooldown: Boolean): DrillPrescription =
        DrillPrescription(d.id, HOLD_SETS, null, d.doseSeconds ?: HOLD_SECONDS,
            Explanations.of(
                if (cooldown) listOf(RuleIds.MOB_02, RuleIds.MOB_04) else listOf(RuleIds.MOB_02, RuleIds.MOB_03),
                "${joint.name.lowercase().replace('_', ' ')}: ${d.name}, $HOLD_SETS × 30–60 s (chronic mobility dose, ≥ 5 days/week)" + (if (cooldown) "." else "; full-range strength sets count too (≈ 30 s each)."),
                mapOf("joint" to joint.name.lowercase(), "drill_id" to d.id), Confidence.MODERATE,
            ))

    companion object {
        const val HOLDS_PER_SESSION: Int = 2
        const val HOLD_SETS: Int = 2
        val HOLD_SECONDS: Band = Band(30, 60)
        const val MOB03_SECONDS_PER_SET: Double = 30.0
        private const val DAYS_PER_WEEK: Long = 7

        fun jointOf(region: LimitationRegion): Joint? = when (region) {
            LimitationRegion.HIP -> Joint.HIPS
            LimitationRegion.SHOULDER -> Joint.SHOULDERS
            LimitationRegion.LOW_BACK -> Joint.HAMSTRINGS
            LimitationRegion.KNEE, LimitationRegion.ANKLE -> Joint.ANKLES
            LimitationRegion.NECK -> Joint.T_SPINE
            LimitationRegion.ELBOW_WRIST -> Joint.WRISTS
            LimitationRegion.OTHER -> null
        }

        /** MOB-03 strength-through-ROM credit map (§5.8). */
        val MOB03_CREDIT: Map<Joint, Set<String>> = mapOf(
            Joint.HIPS to setOf("goblet_squat", "back_squat", "split_squat", "romanian_deadlift"),
            Joint.T_SPINE to setOf("overhead_press_barbell", "pull_up", "front_squat"),
            Joint.SHOULDERS to setOf("landmine_press", "face_pull"),
            Joint.ANKLES to setOf("standing_calf_raise", "seated_calf_raise", "single_leg_calf_raise"),
        )
    }
}
