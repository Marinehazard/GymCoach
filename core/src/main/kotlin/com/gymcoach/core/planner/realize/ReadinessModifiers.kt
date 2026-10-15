package com.gymcoach.core.planner.realize

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.model.ReadinessCheckin
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.planner.PlannerConstants

/** The readiness-derived modifiers of one day (§4.7 step 3). */
data class ReadinessEffect(
    val band: ReadinessBand,
    val score: Int?,
    val rirOffset: Int,                  // Amber +1 / Red +2 on every working set
    val setDropAfter: Int?,              // Amber: −1 set on every exercise after the first N
    val setFactor: Double?,              // Red: × 0.6 (round up)
    val replaceHeavyBarbell: Boolean,    // Red: HEAVY/MAX_STRENGTH barbell blocks → first available moderate machine/dumbbell substitute
    val alternativeOffered: Boolean,     // Red: 25–30 min zone 2 + mobility as a choice
    val noMaxStrength: Boolean,          // two consecutive nights < 6 h (SLP-03)
    val sleepHeavySetFactor: Double?,    // 7-day sleep average < 7 h: heavy compounds × 0.9 …
    val sleepHeavyRirOffset: Int,        // … and RIR +1 on them (SLP-03)
    val stressSetFactor: Double?,        // stress ≥ 4 on the last two check-ins: sets × 0.8 …
    val stressMainLiftRirOffset: Int,    // … main-lift RIR +1 (RDY-05)
    val soreMuscles: Set<Muscle>,        // soreness ≥ 4: Amber modifiers on those muscles' blocks (RDY-04)
    val explanations: List<Explanation>,
)

/**
 * §4.7 step 3 (RDY-01..05, SLP-03, NUT-13): today's band (from the stored check-in; none = Green/as planned), floored by
 * short sleep (< 6 h → at least Amber) and alcohol (≥ 3 drinks → at least Amber), plus SLP-03, RDY-05 and RDY-04 modifiers.
 * RDY-03 (resting HR) is already part of the scored band (WP-04's ReadinessScorer).
 */
class ReadinessModifiers(private val c: PlannerConstants) {

    fun evaluate(today: TodayContext, history: List<ReadinessCheckin>): ReadinessEffect {
        val r = c.rdy
        val notes = ArrayList<Explanation>()
        val checkin = today.readiness
        var band = checkin?.band ?: ReadinessBand.GREEN
        if (checkin != null) {
            notes.add(Explanations.of(listOf(RuleIds.RDY_01, RuleIds.RDY_02), "Readiness ${checkin.score} (${band.name.lowercase()}).", mapOf("score" to checkin.score.toString(), "band" to band.name.lowercase()), Confidence.WEAK))
        }
        val sleep = checkin?.sleepHours
        if (sleep != null && sleep < r.slp03NightHours && band == ReadinessBand.GREEN) {
            band = ReadinessBand.AMBER
            notes.add(Explanations.of(listOf(RuleIds.SLP_03, RuleIds.SLP_02, RuleIds.RDY_02), "Slept ${fmt(sleep)} h (< ${fmt(r.slp03NightHours)} h): at least Amber today.", mapOf("sleep_hours" to fmt(sleep)), Confidence.MODERATE))
        }
        if (today.alcoholDrinksLastNight >= ALCOHOL_DRINKS && band == ReadinessBand.GREEN) {
            band = ReadinessBand.AMBER
            notes.add(Explanations.of(listOf(RuleIds.NUT_13, RuleIds.RDY_02), "${today.alcoholDrinksLastNight} drinks last night: at least Amber today.", mapOf("drinks" to today.alcoholDrinksLastNight.toString()), Confidence.MODERATE))
        }
        val past = history.filter { it.date.isBefore(today.date) }.sortedBy { it.date }
        val yesterday = past.lastOrNull { it.date == today.date.minusDays(1) }
        val noMax = sleep != null && sleep < r.slp03NightHours && (yesterday?.sleepHours ?: Double.MAX_VALUE) < r.slp03NightHours
        if (noMax) notes.add(Explanations.of(listOf(RuleIds.SLP_03, RuleIds.LOAD_04), "Two nights under ${fmt(r.slp03NightHours)} h: no max-strength sets today.", mapOf("nights" to r.slp03TwoNightsNoMaxStrength.toString()), Confidence.MODERATE))

        val week = (past.filter { !it.date.isBefore(today.date.minusDays(7)) } + listOfNotNull(checkin)).mapNotNull { it.sleepHours }
        val avg = if (week.size >= MIN_SLEEP_NIGHTS) week.average() else null
        val sleepFactor = if (avg != null && avg < r.slp03WeeklyAvgHours) r.slp03SetFactor else null
        if (sleepFactor != null) notes.add(Explanations.of(listOf(RuleIds.SLP_03, RuleIds.SLP_01), "7-day sleep average ${fmt(avg ?: 0.0)} h (< ${fmt(r.slp03WeeklyAvgHours)} h): heavy compounds sets × ${fmt(r.slp03SetFactor)}, RIR +${r.slp03RirOffset}.", mapOf("avg_hours" to fmt(avg ?: 0.0)), Confidence.MODERATE))

        val stressRecent = (past + listOfNotNull(checkin)).filter { it.stress != null }.takeLast(r.rdy05Checkins)
        val stressed = stressRecent.size >= r.rdy05Checkins && stressRecent.all { (it.stress ?: 0) >= r.rdy05StressLevel }
        if (stressed) notes.add(Explanations.of(listOf(RuleIds.RDY_05, RuleIds.STR_01), "Stress ≥ ${r.rdy05StressLevel} on the last ${r.rdy05Checkins} check-ins: sets × ${fmt(r.rdy05SetFactor)}, main-lift RIR +${r.rdy05RirOffset}.", mapOf("checkins" to r.rdy05Checkins.toString()), Confidence.WEAK))

        val sore = HashSet<Muscle>()
        val region = checkin?.sorenessRegion
        if (region != null && (checkin.soreness ?: 0) >= r.rdy04SorenessLevel) {
            sore.add(region)
            notes.add(Explanations.of(listOf(RuleIds.RDY_04), "Soreness ${checkin.soreness}/5 in ${region.name.lowercase().replace('_', ' ')}: Amber modifiers on those exercises.", mapOf("muscle" to region.name.lowercase()), Confidence.WEAK))
        }

        return when (band) {
            ReadinessBand.GREEN -> ReadinessEffect(band, checkin?.score, 0, null, null, false, false, noMax, sleepFactor, if (sleepFactor != null) r.slp03RirOffset else 0,
                if (stressed) r.rdy05SetFactor else null, if (stressed) r.rdy05RirOffset else 0, sore, notes)
            ReadinessBand.AMBER -> {
                notes.add(Explanations.of(listOf(RuleIds.RDY_02), "Amber day: +${r.rdy02AmberRirOffset} RIR on all sets, −1 set on every exercise after the first ${r.rdy02AmberSetDropAfter}, loads unchanged, no increments.", mapOf("band" to "amber"), Confidence.WEAK))
                ReadinessEffect(band, checkin?.score, r.rdy02AmberRirOffset, r.rdy02AmberSetDropAfter, null, false, false, noMax, sleepFactor, if (sleepFactor != null) r.slp03RirOffset else 0,
                    if (stressed) r.rdy05SetFactor else null, if (stressed) r.rdy05RirOffset else 0, sore, notes)
            }
            ReadinessBand.RED -> {
                notes.add(Explanations.of(listOf(RuleIds.RDY_02), "Red day: sets × ${fmt(r.rdy02RedSetFactor)}, +${r.rdy02RedRirOffset} RIR, heavy barbell work swapped for a moderate machine/dumbbell variant; a 25–30 min zone-2 + mobility session is offered instead.", mapOf("band" to "red"), Confidence.WEAK))
                ReadinessEffect(band, checkin?.score, r.rdy02RedRirOffset, null, r.rdy02RedSetFactor, true, true, noMax, sleepFactor, if (sleepFactor != null) r.slp03RirOffset else 0,
                    if (stressed) r.rdy05SetFactor else null, if (stressed) r.rdy05RirOffset else 0, sore, notes)
            }
        }
    }

    companion object {
        /** NUT-13: ≥ 3 standard drinks the night before → at least Amber. */
        const val ALCOHOL_DRINKS: Int = 3
        /** Minimum nights with a sleep entry for the SLP-03 7-day average. */
        const val MIN_SLEEP_NIGHTS: Int = 3

        fun fmt(x: Double): String = java.math.BigDecimal(x).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}
