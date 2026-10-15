package com.gymcoach.core.volume

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Band
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MuscleTier
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.planner.PlannerConstants
import kotlin.math.roundToInt

/** Lower and upper end of one muscle's weekly target band (fractional sets/week). */
data class TargetBand(val lo: Double, val hi: Double, val step: Double)

/**
 * §4.4 weekly volume targets (DEF-05 fractional sets): goal row × training age (VOL-04/05/09), the TIER_2 factor
 * (`round(0.6 × band)`, floor VOL-01), AGE-02 (age ≥ 60 → the novice band of the goal row), FRQ-03 (one day per week →
 * VOL-01 for every TIER_1/TIER_2 muscle, no ramp) and the VOL-08 ramp `min(lo + (k − 1) × step, hi)` with holds.
 * TIER_3 muscles carry no target and are absent from the result.
 */
class TargetPolicy(private val c: PlannerConstants) {

    fun weekTargets(
        goal: Goal,
        trainingAge: TrainingAge,
        tiers: Map<Muscle, MuscleTier>,
        weekIndex: Int,
        holds: Set<Muscle>,
        accustomed: Map<Muscle, Double>?,
        daysPerWeek: Int,
        age: Int,
        deloadTriggersActive: List<String> = emptyList(),
    ): Map<Muscle, Double> {
        val bands = bands(goal, trainingAge, tiers, accustomed, daysPerWeek, age)
        val holdAll = deloadTriggersActive.isNotEmpty()
        val out = LinkedHashMap<Muscle, Double>()
        for ((m, band) in bands) {
            val held = holdAll || m in holds
            val k = if (held) maxOf(1, weekIndex - 1) else maxOf(1, weekIndex)
            out[m] = VolumeAccountant.round2(minOf(band.lo + (k - 1) * band.step, band.hi))
        }
        return out
    }

    /** The band and ramp step per TIER_1/TIER_2 muscle (enum order). */
    fun bands(goal: Goal, trainingAge: TrainingAge, tiers: Map<Muscle, MuscleTier>, accustomed: Map<Muscle, Double>?, daysPerWeek: Int, age: Int): Map<Muscle, TargetBand> {
        val out = LinkedHashMap<Muscle, TargetBand>()
        val floor = c.vol.vol01Minimum.toDouble()
        for (m in Muscle.entries) {
            val tier = tiers[m] ?: MuscleTier.TIER_3
            if (tier == MuscleTier.TIER_3) continue
            if (daysPerWeek <= 1) {
                out[m] = TargetBand(floor, floor, 0.0)
                continue
            }
            val step = minOf(if (tier == MuscleTier.TIER_1) c.vol.vol08StepTier1 else c.vol.vol08StepTier2, c.vol.vol08StepMax.toDouble())
            val base = goalBand(goal, if (age >= c.age.age02Threshold) TrainingAge.NOVICE else trainingAge, tier)
            if (goal == Goal.FAT_LOSS) {
                val hiCap = if (tier == MuscleTier.TIER_1) volBand(trainingAge).hi.toDouble() else tier2(volBand(trainingAge)).hi.toDouble()
                val acc = accustomed?.get(m)
                if (acc == null || acc <= 0.0) {
                    val v = maxOf(floor, c.vol.vol09NoHistoryFactor * base.lo)
                    out[m] = TargetBand(VolumeAccountant.round2(v), VolumeAccountant.round2(v), 0.0)
                } else {
                    val lo = (c.vol.vol09DeficitFractionLo * acc).coerceIn(floor, hiCap)
                    val hi = (c.vol.vol09DeficitFractionHi * acc).coerceIn(floor, hiCap)
                    out[m] = TargetBand(VolumeAccountant.round2(lo), VolumeAccountant.round2(maxOf(lo, hi)), step)
                }
                continue
            }
            out[m] = TargetBand(base.lo.toDouble(), base.hi.toDouble(), step)
        }
        return out
    }

    /** The TIER_1 / TIER_2 band of the goal row (§4.4 table). */
    fun goalBand(goal: Goal, trainingAge: TrainingAge, tier: MuscleTier): Band {
        val tier1: Band = when (goal) {
            Goal.HYPERTROPHY, Goal.FAT_LOSS -> volBand(trainingAge)
            Goal.STRENGTH, Goal.GENERAL_FITNESS -> c.vol.vol04Novice
            Goal.ENDURANCE_SUPPORT -> ENDURANCE_TIER1
        }
        return when (tier) {
            MuscleTier.TIER_1 -> tier1
            MuscleTier.TIER_2 -> if (goal == Goal.ENDURANCE_SUPPORT) ENDURANCE_TIER2 else tier2(tier1)
            MuscleTier.TIER_3 -> Band(0, 0)
        }
    }

    fun volBand(trainingAge: TrainingAge): Band = when (trainingAge) {
        TrainingAge.NOVICE -> c.vol.vol04Novice
        TrainingAge.INTERMEDIATE -> c.vol.vol04Intermediate
        TrainingAge.ADVANCED -> c.vol.vol04Advanced
    }

    /** TIER_2 = round(tierTier2Factor × band), floor VOL-01 (novice 5–7, intermediate 7–11, advanced 9–15). */
    fun tier2(band: Band): Band {
        val f = c.tier.tierTier2Factor
        val lo = maxOf(c.vol.vol01Minimum, (f * band.lo).roundToInt())
        val hi = maxOf(lo, (f * band.hi).roundToInt())
        return Band(lo, hi)
    }

    /** The explanations of the holds applied to week [weekIndex] (VOL-08; [VOL-08, DLD-03] while DLD-03 triggers are active). */
    fun holdExplanations(holds: Map<Muscle, String>, deloadTriggersActive: List<String>, weekIndex: Int): List<Explanation> {
        val out = ArrayList<Explanation>()
        if (deloadTriggersActive.isNotEmpty()) {
            out.add(
                Explanations.of(
                    listOf(RuleIds.VOL_08, RuleIds.DLD_03),
                    "Volume ramp held this week for every muscle: deload triggers are active.",
                    mapOf("triggers" to deloadTriggersActive.joinToString(","), "week" to weekIndex.toString()),
                    Confidence.WEAK,
                ),
            )
        }
        for ((m, why) in holds) {
            out.add(
                Explanations.of(
                    listOf(RuleIds.VOL_08),
                    "Volume ramp held for ${muscleName(m)}: $why.",
                    mapOf("muscle" to muscleKey(m), "reason" to why, "week" to weekIndex.toString()),
                    Confidence.WEAK,
                ),
            )
        }
        return out
    }

    companion object {
        val ENDURANCE_TIER1: Band = Band(4, 6)
        val ENDURANCE_TIER2: Band = Band(2, 4)

        fun muscleKey(m: Muscle): String = m.name.lowercase()
        fun muscleName(m: Muscle): String = m.name.lowercase().replace('_', ' ')
    }
}
