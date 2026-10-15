// core/src/main/kotlin/com/gymcoach/core/readiness/ReadinessScorer.kt
package com.gymcoach.core.readiness

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.LimitationRegion
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.model.ReadinessCheckin
import com.gymcoach.core.planner.PlannerConstants
import java.time.Instant
import java.time.LocalDate

data class ReadinessInput(
    val date: LocalDate,
    val quickMode: Boolean,
    val sleepHours: Double?, val sleepQuality: Int?, val soreness: Int?, val sorenessRegion: Muscle?,
    val stress: Int?, val energy: Int?, val motivation: Int?,
    val restingHr: Int?, val restingHrBaseline7d: Double?,        // mean of the previous 7 morning RHR values
    val jointPain0to10: Int = 0, val jointRegion: LimitationRegion? = null, val redFlagReported: Boolean = false,
    val alcoholDrinksLastNight: Int = 0,
    val restingHrElevatedDays: Int = 0,                            // consecutive prior days with RHR ≥ baseline + 5 (RDY-03)
    val illness: Boolean = false,
)

/**
 * RDY-01 score (renormalised over answered items, the weights are conventions [weak]), RDY-02 band and the
 * RDY-03 / SLP-03 / NUT-13 / RED-01 overrides of §5.1. Quick mode leaves sleep quality and stress assumed.
 */
class ReadinessScorer(private val c: PlannerConstants) {

    fun score(input: ReadinessInput, id: String, now: Instant): ReadinessCheckin {
        val motivation = input.motivation ?: if (input.quickMode) input.energy else null
        val items = ArrayList<Triple<String, Int, Int>>()          // field, points, max
        val assumed = ArrayList<String>()
        fun add(field: String, value: Int?, points: (Int) -> Int) {
            if (value == null) assumed += field else items += Triple(field, points(value.coerceIn(1, 5)), ITEM_MAX)
        }
        if (input.sleepHours == null) assumed += F_SLEEP_HOURS else items += Triple(F_SLEEP_HOURS, sleepPoints(input.sleepHours), SLEEP_MAX)
        add(F_SLEEP_QUALITY, input.sleepQuality) { (it - 1) * 4 }
        add(F_SORENESS, input.soreness) { (5 - it) * 4 }
        add(F_STRESS, input.stress) { (5 - it) * 4 }
        add(F_ENERGY, input.energy) { (it - 1) * 4 }
        add(F_MOTIVATION, motivation) { (it - 1) * 4 }

        val got = items.sumOf { it.second }
        val max = items.sumOf { it.third }
        val base = if (max == 0) 0 else Math.round(100.0 * got / max).toInt()
        val rhrDelta = if (input.restingHr != null && input.restingHrBaseline7d != null) input.restingHr - input.restingHrBaseline7d else null
        val rhrAdj = when {
            rhrDelta == null -> 0
            rhrDelta <= 3.0 -> 0
            rhrDelta < 8.0 -> -8
            else -> -16
        }
        val score = (base + rhrAdj).coerceIn(0, 100)

        val inputs = LinkedHashMap<String, String>()
        for ((f, p, m) in items) inputs[f] = "$p/$m"
        if (rhrDelta != null) inputs["resting_hr_delta"] = String.format(java.util.Locale.ROOT, "%+.1f", rhrDelta)
        if (rhrAdj != 0) inputs["resting_hr_adjustment"] = rhrAdj.toString()
        if (assumed.isNotEmpty()) inputs["assumed"] = assumed.joinToString(",")

        var band = bandOf(score)
        val ids = linkedSetOf(RuleIds.RDY_01, RuleIds.RDY_02)
        val overrides = ArrayList<String>()
        fun atLeastAmber(rule: String, why: String) {
            if (band == ReadinessBand.GREEN) band = ReadinessBand.AMBER
            ids += rule
            overrides += why
        }
        if (input.sleepHours != null && input.sleepHours < c.rdy.slp03NightHours) atLeastAmber(RuleIds.SLP_03, "slept < ${num(c.rdy.slp03NightHours)} h → at least Amber")
        if (input.restingHrElevatedDays >= c.rdy.rdy03RhrDays) atLeastAmber(RuleIds.RDY_03, "resting HR ≥ +${c.rdy.rdy03RhrAmberDelta} bpm for ${input.restingHrElevatedDays} days → at least Amber")
        if (input.alcoholDrinksLastNight >= ALCOHOL_DRINKS) atLeastAmber(RuleIds.NUT_13, "${input.alcoholDrinksLastNight} drinks last night → at least Amber")
        if (rhrDelta != null && rhrDelta >= c.rdy.rdy03RhrRedDelta) {
            band = ReadinessBand.RED; ids += RuleIds.RDY_03; overrides += "resting HR ≥ +${c.rdy.rdy03RhrRedDelta} bpm → Red"
        }
        if (input.illness) {
            band = ReadinessBand.RED; ids += RuleIds.RDY_03; overrides += "illness symptoms → Red (no hard training)"
        }
        if (input.redFlagReported) {
            band = ReadinessBand.RED; ids += RuleIds.RED_01; overrides += "warning sign reported → stop and get checked; no session today"
        }
        if (input.jointPain0to10 > INJ_PAIN_OK) {
            ids += RuleIds.INJ_00
            overrides += "joint pain ${input.jointPain0to10}/10${input.jointRegion?.let { " (${it.name.lowercase()})" } ?: ""}: exercises loading that region are swapped"
        }
        val message = buildString {
            append("Readiness $score/100 → ${band.name.lowercase()}")
            append(" (")
            append(items.joinToString(", ") { "${label(it.first)} ${it.second}/${it.third}" })
            if (rhrAdj != 0) append(", resting HR $rhrAdj")
            append(")")
            if (assumed.isNotEmpty()) append("; not asked: ${assumed.joinToString(", ") { label(it) }}")
            if (overrides.isNotEmpty()) append("; ").append(overrides.joinToString("; "))
        }
        return ReadinessCheckin(
            id = id,
            date = input.date,
            quickMode = input.quickMode,
            sleepHours = input.sleepHours,
            sleepQuality = input.sleepQuality,
            soreness = input.soreness,
            sorenessRegion = input.sorenessRegion,
            stress = input.stress,
            energy = input.energy,
            motivation = motivation,
            restingHr = input.restingHr,
            jointPain0to10 = input.jointPain0to10,
            jointRegion = input.jointRegion,
            redFlagReported = input.redFlagReported,
            assumedFields = assumed,
            score = score,
            band = band,
            explanation = Explanations.of(ids.toList(), message, inputs, Confidence.WEAK),
            loggedAt = now,
        )
    }

    /** RDY-02: Green ≥ rdy02GreenMin (70), Amber ≥ rdy02AmberMin (45), else Red. */
    fun bandOf(score: Int): ReadinessBand = when {
        score >= c.rdy.rdy02GreenMin -> ReadinessBand.GREEN
        score >= c.rdy.rdy02AmberMin -> ReadinessBand.AMBER
        else -> ReadinessBand.RED
    }

    /** RDY-01 sleep-hours points: ≥ 7.5 → 20; 7.0–7.4 → 16; 6.0–6.9 → 10; 5.0–5.9 → 4; < 5 → 0. */
    fun sleepPoints(hours: Double): Int = when {
        hours >= 7.5 -> 20
        hours >= 7.0 -> 16
        hours >= 6.0 -> 10
        hours >= 5.0 -> 4
        else -> 0
    }

    private fun label(field: String): String = field.replace('_', ' ')

    private fun num(x: Double): String = if (x == Math.floor(x)) x.toLong().toString() else x.toString()

    companion object {
        const val F_SLEEP_HOURS: String = "sleep_hours"
        const val F_SLEEP_QUALITY: String = "sleep_quality"
        const val F_SORENESS: String = "soreness"
        const val F_STRESS: String = "stress"
        const val F_ENERGY: String = "energy"
        const val F_MOTIVATION: String = "motivation"

        /** Quick-mode "Slept" chips (< 5 · 5–6 · 6–7 · 7–7.5 · 7.5+) → midpoint hours. */
        val QUICK_SLEEP_MIDPOINTS: List<Double> = listOf(4.5, 5.5, 6.5, 7.25, 8.0)

        private const val SLEEP_MAX: Int = 20
        private const val ITEM_MAX: Int = 16
        private const val ALCOHOL_DRINKS: Int = 3
        private const val INJ_PAIN_OK: Int = 3
    }
}
