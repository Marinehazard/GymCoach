// core/src/main/kotlin/com/gymcoach/core/nutrition/SupplementAdvisor.kt
package com.gymcoach.core.nutrition

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.MedicalFlag
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.SupplementAdvice
import com.gymcoach.core.model.SupplementEntry
import com.gymcoach.core.planner.PlannerConstants
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * §5.6 (SUP-01..12): tiered rows from supplements.json in file order, numeric doses computed where the rule gives a
 * formula. Every row carries the SUP-12 caveat block in `notes`. RED-03 (`medicalGate`) or an entry's own
 * `gated_by_medical` flag hit replaces the dose text by the gate sentence (no numbers).
 */
class SupplementAdvisor(private val kb: KnowledgeBase, private val c: PlannerConstants) {

    fun advise(profile: Profile, weightKg: Double, goal: Goal, medicalGate: Boolean, fishServingsPerWeek: Int?, vitDRiskFactors: Int, travelling: Boolean): List<SupplementAdvice> {
        val file = kb.supplements
        val out = ArrayList<SupplementAdvice>()
        val (largeCutoff, smallCutoff) = caffeineCutoffs(profile.bedtimeLocal)
        for (e in file.entries) {
            if (!included(e, goal, fishServingsPerWeek, vitDRiskFactors, travelling)) continue
            val tierC = e.tier == "C"
            var dose = if (tierC) NOT_RECOMMENDED else doseText(e, weightKg)
            var timing = e.timing
            if (e.id == RuleIds.SUP_02) {
                timing = "60 min before the session; not after $largeCutoff for ≥ 200 mg, not after $smallCutoff for ≤ 100 mg (bedtime ${profile.bedtimeLocal})"
            }
            var gated = false
            val notes = ArrayList<String>(e.notes)
            if (!tierC) {
                val flagHit = e.gatedByMedical.any { it in profile.medicalFlags }
                if (e.id == RuleIds.SUP_01 && MedicalFlag.KIDNEY_DISEASE in profile.medicalFlags) {
                    dose = KIDNEY_REFER
                    gated = true
                } else if (medicalGate || flagHit) {
                    dose = GATE_TEXT
                    gated = true
                }
                if (e.id == RuleIds.SUP_01 && !gated) notes += "Loading is optional: 20 g/day (4 × 5 g) for 5–7 days; otherwise saturation takes about 3–4 weeks."
            }
            notes += file.caveatSup12
            out += SupplementAdvice(
                id = e.id,
                name = e.name,
                tier = e.tier,
                doseText = dose,
                timing = timing,
                evidence = e.evidence,
                citation = e.citation,
                gated = gated,
                notes = notes,
                legalNote = e.legalNote,
                doNotReason = e.doNotReason,
            )
        }
        return out
    }

    /** SUP-02: (cut-off for ≥ 200 mg = bedtime − sup02CutoffLargeHours, cut-off for ≤ 100 mg = bedtime − sup02CutoffSmallHours), "HH:mm". */
    fun caffeineCutoffs(bedtimeLocal: String): Pair<String, String> {
        val minutes = parseMinutes(bedtimeLocal) ?: DEFAULT_BEDTIME_MIN
        return hhmm(minutes - c.hab.sup02CutoffLargeHours * 60) to hhmm(minutes - c.hab.sup02CutoffSmallHours * 60)
    }

    private fun included(e: SupplementEntry, goal: Goal, fish: Int?, vitD: Int, travelling: Boolean): Boolean = when (e.id) {
        RuleIds.SUP_04 -> vitD >= VITD_MIN_RISK_FACTORS                     // only with ≥ 2 risk factors (or a low test)
        RuleIds.SUP_08 -> travelling                                          // melatonin: travel / jet lag only
        RuleIds.SUP_05 -> fish != null && fish < FISH_SERVINGS_MIN            // omega-3 only when fish < 2 servings/week
        RuleIds.SUP_10 -> goal == Goal.FAT_LOSS                               // multivitamin: energy restriction only
        else -> true
    }

    private fun doseText(e: SupplementEntry, weightKg: Double): String = when (e.id) {
        RuleIds.SUP_01 -> {
            val g = (CREATINE_G_PER_KG * weightKg).coerceIn(CREATINE_MIN_G, CREATINE_MAX_G)
            "${num(g)} g/day"
        }
        RuleIds.SUP_02 -> {
            val lo = round10(2.0 * weightKg)
            val hi = round10(3.0 * weightKg)
            "start $lo–$hi mg (2–3 mg/kg); up to 3–6 mg/kg; no more than ${c.hab.sup02DayMaxMg} mg per day"
        }
        else -> e.dose
    }

    private fun parseMinutes(hhmm: String): Int? {
        val parts = hhmm.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun hhmm(minutes: Int): String {
        val m = ((minutes % MIN_PER_DAY) + MIN_PER_DAY) % MIN_PER_DAY
        return String.format(java.util.Locale.ROOT, "%02d:%02d", m / 60, m % 60)
    }

    private fun round10(x: Double): Int = (Math.round(x / 10.0) * 10).toInt()

    private fun num(x: Double): String = BigDecimal(x).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    companion object {
        const val GATE_TEXT: String = "discuss with your physician/pharmacist first"
        const val KIDNEY_REFER: String = "refer: not with a kidney condition — discuss with your physician first"
        const val NOT_RECOMMENDED: String = "not recommended"
        private const val CREATINE_G_PER_KG: Double = 0.1
        private const val CREATINE_MIN_G: Double = 3.0
        private const val CREATINE_MAX_G: Double = 5.0
        private const val VITD_MIN_RISK_FACTORS: Int = 2
        private const val FISH_SERVINGS_MIN: Int = 2
        private const val DEFAULT_BEDTIME_MIN: Int = 23 * 60
        private const val MIN_PER_DAY: Int = 24 * 60
    }
}
