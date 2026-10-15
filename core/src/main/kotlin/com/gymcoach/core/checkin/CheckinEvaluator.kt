// core/src/main/kotlin/com/gymcoach/core/checkin/CheckinEvaluator.kt
package com.gymcoach.core.checkin

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.CheckinAnswer
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Limitation
import com.gymcoach.core.model.LimitationRegion
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.WeeklyCheckin
import com.gymcoach.core.planner.PlannerConstants
import java.util.Locale

data class CheckinOutcome(val triggered: List<Explanation>, val proposals: List<PlanMutation>, val pendingModifiers: List<Explanation>, val holds: Set<Muscle>)

/**
 * §5.2 trigger table. `history` = earlier check-ins, newest first ("two consecutive" rules compare with history[0]).
 * Every trigger yields one Explanation in `triggered`; modifiers that act on next week's plan are repeated in
 * `pendingModifiers`; VOL-08 holds (Q6 twice, Q12 twice) are returned in `holds` (enum order when persisted).
 */
class CheckinEvaluator(private val kb: KnowledgeBase, private val c: PlannerConstants) {

    fun evaluate(checkin: WeeklyCheckin, history: List<WeeklyCheckin>, inDeficit: Boolean): CheckinOutcome {
        val triggered = ArrayList<Explanation>()
        val proposals = ArrayList<PlanMutation>()
        val pending = ArrayList<Explanation>()
        val holds = LinkedHashSet<Muscle>()
        val prev = history.firstOrNull()
        fun num(id: String, w: WeeklyCheckin? = checkin): Double? = w?.answers?.lastOrNull { it.questionId == id }?.number
        fun ans(id: String, w: WeeklyCheckin? = checkin): CheckinAnswer? = w?.answers?.lastOrNull { it.questionId == id }
        fun ex(ids: List<String>, msg: String, vararg inputs: Pair<String, String>, conf: Confidence = Confidence.MODERATE): Explanation =
            Explanations.of(ids, msg, inputs.toMap(), conf)

        // Q1 / Q2 → SLP-03.
        val q1 = num(Q1)
        val q2 = num(Q2)
        if ((q1 != null && q1 < c.rdy.slp03WeeklyAvgHours) || (q2 != null && q2 <= LOW_SCALE)) {
            val e = ex(listOf(RuleIds.SLP_03),
                "Sleep ${q1?.let { "average ${fmt(it)} h" } ?: "quality ${fmt(q2 ?: 0.0)}/5"} → sets −${pct(1.0 - c.rdy.slp03SetFactor)} % and RIR +${c.rdy.slp03RirOffset} on heavy compounds next week (Craven 2022)",
                "Q1" to (q1?.let { fmt(it) } ?: ""), "Q2" to (q2?.let { fmt(it) } ?: ""))
            triggered += e; pending += e
        }
        // Q3 → NUT-01.
        num(Q3)?.let { if (it <= PROTEIN_DAYS_LOW) triggered += ex(listOf(RuleIds.NUT_01), "Protein target hit on ${fmt(it)}/7 days: protein coaching", "Q3" to fmt(it)) }
        // Q4 → NUT-16 (the kcal proposal itself comes from MacroTargets.review, which knows the goal band).
        num(Q4)?.let { kg ->
            if (inDeficit && kg >= 0.0) {
                triggered += ex(listOf(RuleIds.NUT_16), "In a deficit but the 14-day weight trend is ${signed(kg)} kg: energy targets are reviewed (±100–200 kcal)", "Q4" to signed(kg))
            }
        }
        // Q5 → STP-02.
        num(Q5)?.let { steps ->
            val target = c.hab.stp01Under60.lo
            if (steps < target) triggered += ex(listOf(RuleIds.STP_02, RuleIds.STP_01), "Steps ${Math.round(steps)} per day (< $target): step target card", "Q5" to Math.round(steps).toString())
        }
        // Q6 ≥ 4 twice → RDY-05 (+ VOL-08 hold on every muscle, STR-02 no new HIIT).
        val q6 = num(Q6)
        val q6prev = num(Q6, prev)
        if (q6 != null && q6prev != null && q6 >= c.rdy.rdy05StressLevel && q6prev >= c.rdy.rdy05StressLevel) {
            val e = ex(listOf(RuleIds.RDY_05, RuleIds.STR_02, RuleIds.VOL_08),
                "Stress ≥ ${c.rdy.rdy05StressLevel} on two check-ins: sets × ${c.rdy.rdy05SetFactor}, main-lift RIR +${c.rdy.rdy05RirOffset}, volume ramp held, zone-2 walks instead of new HIIT",
                "Q6" to fmt(q6), "Q6_previous" to fmt(q6prev))
            triggered += e; pending += e
            holds += Muscle.values()
        }
        // Q7 → INJ-00 limitation card; same region twice → RED-02.
        val q7 = ans(Q7)
        if (q7 != null && q7Yes(q7)) {
            val region = regionOf(q7.text)
            triggered += ex(listOf(RuleIds.INJ_00), "Joint pain above 3/10 reported (${q7.text?.trim().orEmpty().ifEmpty { "region not named" }}): log it as a limitation?",
                "Q7" to q7.text.orEmpty(), "region" to region.name.lowercase())
            proposals += PlanMutation.SetLimitation(
                limitation = Limitation(id = "lim-${checkin.id}-${region.name.lowercase()}", region = region, pain0to10 = REPORTED_PAIN, since = checkin.weekStart),
                rationale = "CHK Q7: joint pain above 3/10 during training (${region.name.lowercase()})",
            )
            val p7 = ans(Q7, prev)
            if (p7 != null && q7Yes(p7) && (regionOf(p7.text) == region)) {
                triggered += ex(listOf(RuleIds.RED_02), "Pain in the same area two check-ins in a row: please see a physiotherapist or physician before loading it again",
                    "region" to region.name.lowercase(), conf = Confidence.STRONG)
            }
        }
        // Q8 < 70 % → TIME-01 / SPLIT-01.
        num(Q8)?.let { pctDone ->
            if (pctDone < SESSIONS_DONE_PCT) triggered += ex(listOf(RuleIds.TIME_01, RuleIds.SPLIT_01),
                "${Math.round(pctDone)} % of planned sessions done: shorter sessions or fewer days?", "Q8" to Math.round(pctDone).toString())
        }
        // Q9 > 7 drinks → NUT-13.
        num(Q9)?.let { if (it > ALCOHOL_WEEK) triggered += ex(listOf(RuleIds.NUT_13), "${fmt(it)} drinks this week: alcohol affects recovery and sleep", "Q9" to fmt(it)) }
        // Q10 ≥ 3 → TRV-01 travel-week proposal.
        num(Q10)?.let { days ->
            if (days >= c.trv.trv01TravelDaysThreshold) {
                val from = checkin.weekStart.plusDays(7)
                val n = Math.round(days).toInt().coerceIn(1, 7)
                triggered += ex(listOf(RuleIds.TRV_01), "${n} travel days next week: switch to the 2-session travel plan?", "Q10" to n.toString())
                proposals += PlanMutation.SetTravelDays(from = from, to = from.plusDays((n - 1).toLong()), rationale = "CHK Q10: $n travel / hotel-gym days next week")
            }
        }
        // Q11 ≤ 2 → DLD-03 trigger (counted by DeloadPolicy from the stored check-in).
        num(Q11)?.let { if (it <= LOW_SCALE) triggered += ex(listOf(RuleIds.DLD_03), "Motivation ${fmt(it)}/5: counts as a deload trigger", "Q11" to fmt(it), conf = Confidence.WEAK) }
        // Q12 yes twice → VOL-08 hold for the muscles named (none named → all).
        val q12 = ans(Q12)
        val p12 = ans(Q12, prev)
        if (q12?.yes == true && p12?.yes == true) {
            val named = musclesIn(q12.text) + musclesIn(p12.text)
            val held = if (named.isEmpty()) Muscle.values().toSet() else named
            holds += held
            val e = ex(listOf(RuleIds.VOL_08), "Soreness > 48 h on two check-ins: volume ramp paused for ${if (named.isEmpty()) "all muscles" else held.joinToString(", ") { it.name.lowercase() }}",
                "Q12" to "yes", conf = Confidence.WEAK)
            triggered += e; pending += e
        }
        // Q13 yes → SUP-02 caffeine cut-off card.
        if (ans(Q13)?.yes == true) triggered += ex(listOf(RuleIds.SUP_02), "Caffeine after 15:00 on ≥ 3 days: caffeine cut-off card", "Q13" to "yes")
        // Q14 ≤ 2 → PROG-04 audit + calibration set next week.
        num(Q14)?.let {
            if (it <= LOW_SCALE) {
                val e = ex(listOf(RuleIds.PROG_04, RuleIds.RIR_05), "Planned sets rarely at the target RIR (${fmt(it)}/5): calibration set scheduled next week", "Q14" to fmt(it))
                triggered += e; pending += e
            }
        }
        // Q15 ≥ 4 two weeks in a deficit → NUT-17 diet break.
        val q15 = num(Q15)
        val q15prev = num(Q15, prev)
        if (inDeficit && q15 != null && q15prev != null && q15 >= HUNGER_HIGH && q15prev >= HUNGER_HIGH) {
            triggered += ex(listOf(RuleIds.NUT_17), "High hunger two weeks running in a deficit: one week at maintenance (diet break)?", "Q15" to fmt(q15), "Q15_previous" to fmt(q15prev))
        }
        return CheckinOutcome(triggered, proposals, pending, holds)
    }

    private fun q7Yes(a: CheckinAnswer): Boolean {
        if (a.yes != null) return a.yes
        val t = a.text?.trim()?.lowercase(Locale.ROOT) ?: return false
        return t.isNotEmpty() && t !in NO_WORDS
    }

    /** Region named in the Q7 text (EN/DE keywords); OTHER when none is recognised. */
    private fun regionOf(text: String?): LimitationRegion {
        val t = text?.lowercase(Locale.ROOT) ?: return LimitationRegion.OTHER
        for ((region, words) in REGION_WORDS) if (words.any { t.contains(it) }) return region
        return LimitationRegion.OTHER
    }

    /** Muscles named in a free-text answer (serial names or plain words). */
    private fun musclesIn(text: String?): Set<Muscle> {
        val t = text?.lowercase(Locale.ROOT)?.replace('_', ' ') ?: return emptySet()
        return Muscle.values().filter { m -> t.contains(m.name.lowercase(Locale.ROOT).replace('_', ' ')) }.toSet()
    }

    private fun fmt(x: Double): String = if (x == Math.floor(x) && !x.isInfinite()) x.toLong().toString() else String.format(Locale.ROOT, "%.1f", x)
    private fun signed(x: Double): String = (if (x >= 0) "+" else "") + String.format(Locale.ROOT, "%.1f", x)
    private fun pct(x: Double): Long = Math.round(x * 100.0)

    companion object {
        const val Q1: String = "Q1"; const val Q2: String = "Q2"; const val Q3: String = "Q3"; const val Q4: String = "Q4"
        const val Q5: String = "Q5"; const val Q6: String = "Q6"; const val Q7: String = "Q7"; const val Q8: String = "Q8"
        const val Q9: String = "Q9"; const val Q10: String = "Q10"; const val Q11: String = "Q11"; const val Q12: String = "Q12"
        const val Q13: String = "Q13"; const val Q14: String = "Q14"; const val Q15: String = "Q15"
        private const val LOW_SCALE: Double = 2.0
        private const val PROTEIN_DAYS_LOW: Double = 4.0
        private const val SESSIONS_DONE_PCT: Double = 70.0
        private const val ALCOHOL_WEEK: Double = 7.0
        private const val HUNGER_HIGH: Double = 4.0
        private const val REPORTED_PAIN: Int = 4                  // "above 3/10"; the user edits it on the card
        private val NO_WORDS: Set<String> = setOf("no", "none", "nein", "-", "n", "0")
        private val REGION_WORDS: List<Pair<LimitationRegion, List<String>>> = listOf(
            LimitationRegion.KNEE to listOf("knee", "knie"),
            LimitationRegion.SHOULDER to listOf("shoulder", "schulter"),
            LimitationRegion.LOW_BACK to listOf("low back", "lower back", "back", "rücken", "ruecken"),
            LimitationRegion.ELBOW_WRIST to listOf("elbow", "wrist", "ellbogen", "handgelenk"),
            LimitationRegion.HIP to listOf("hip", "hüfte", "huefte"),
            LimitationRegion.ANKLE to listOf("ankle", "knöchel", "sprunggelenk"),
            LimitationRegion.NECK to listOf("neck", "nacken"),
        )
    }
}
