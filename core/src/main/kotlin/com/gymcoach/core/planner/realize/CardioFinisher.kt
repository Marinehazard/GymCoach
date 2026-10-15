package com.gymcoach.core.planner.realize

import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.CardioKind
import com.gymcoach.core.model.CardioPlacement
import com.gymcoach.core.model.CardioPrescription
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.LimitationRegion
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.planner.PlannerConstants
import kotlin.math.roundToInt

/**
 * §4.8 / §5.4 (CAR-01/02/06/07/08, ORD-03): the planner-side weekly cardio floor written into MesoWeek.cardio. WP-03 never
 * calls WP-04's CardioPlanner (no cross reference, §9), so this is the planner's own, deliberately minimal CAR-07 placement:
 * zone-2 sessions by goal on off days first (CAR-06: never the day before a heavy leg day for running modalities), else as
 * finishers after upper-body sessions (ORD-03); AddCardio / RemoveCardio overrides applied last. HIIT is never scheduled
 * automatically (CAR-05 gating lives in the coach/cardio flow).
 */
class CardioFinisher(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    private val resolver = EquipmentResolver(kb)

    fun weekly(
        goal: Goal, profile: Profile, age: Int, liftingWeekdays: List<Int>, heavyLegWeekdays: List<Int>, upperWeekdays: List<Int>,
        overrides: List<PlanOverride>, equipment: SessionEquipment, medicalGate: Boolean,
    ): List<CardioPrescription> {
        val (count, minutes) = floorFor(goal)
        val out = ArrayList<CardioPrescription>()
        val kneeLimited = profile.limitations.any { it.active && (it.region == LimitationRegion.KNEE || it.region == LimitationRegion.ANKLE) }
        val hrMax = profile.measuredHrMax?.toDouble() ?: (TANAKA_A - TANAKA_B * age)
        val hrLow = (hrMax * ZONE2_LO).roundToInt()
        val hrHigh = (hrMax * ZONE2_HI).roundToInt()
        val offDays = (1..7).filter { it !in liftingWeekdays }
        val days = ArrayList<Pair<Int, CardioPlacement>>()
        for (d in offDays) if (days.size < count) days.add(d to CardioPlacement.OFF_DAY)
        for (d in upperWeekdays) if (days.size < count && days.none { it.first == d }) days.add(d to CardioPlacement.FINISHER)
        for ((day, placement) in days.sortedBy { it.first }) {
            val beforeLegs = heavyLegWeekdays.contains(if (day == 7) 1 else day + 1)
            val ex = modality(equipment, allowRunning = !beforeLegs && !kneeLimited) ?: continue
            out.add(
                CardioPrescription(
                    exerciseId = ex, minutes = minutes, kind = CardioKind.ZONE2, hrLow = hrLow, hrHigh = hrHigh, rpeLow = RPE_LO, rpeHigh = RPE_HI,
                    placement = placement, weekday = day,
                    explanation = Explanations.of(
                        listOf(RuleIds.CAR_07, RuleIds.CAR_02, RuleIds.CAR_03, RuleIds.CAR_06) + (if (placement == CardioPlacement.FINISHER) listOf(RuleIds.ORD_03) else emptyList()),
                        "Zone 2, $minutes min ($hrLow–$hrHigh bpm, talk test, RPE 3–4/10)" + (if (placement == CardioPlacement.FINISHER) " after lifting." else " on an off day."),
                        mapOf("goal" to goal.name.lowercase(), "hr_max" to hrMax.roundToInt().toString(), "weekday" to day.toString()),
                        Confidence.MODERATE,
                    ),
                ),
            )
        }
        // Overrides (§4.15 AddCardio / RemoveCardio; later wins per weekday, resolved by OverrideResolver upstream).
        for (o in overrides) {
            when (val m = o.mutation) {
                is PlanMutation.RemoveCardio -> out.removeAll { it.weekday == m.weekday }
                is PlanMutation.AddCardio -> {
                    if (medicalGate && m.kind != CardioKind.ZONE2 && m.kind != CardioKind.WALK) continue
                    out.removeAll { it.weekday == m.weekday }
                    val ex = m.exerciseId ?: modality(equipment, allowRunning = !heavyLegWeekdays.contains(if (m.weekday == 7) 1 else m.weekday + 1) && !kneeLimited) ?: continue
                    out.add(
                        CardioPrescription(
                            exerciseId = ex, minutes = m.minutes, kind = m.kind, hrLow = hrLow, hrHigh = hrHigh, rpeLow = RPE_LO, rpeHigh = RPE_HI,
                            placement = if (m.weekday in liftingWeekdays) CardioPlacement.FINISHER else CardioPlacement.OFF_DAY, weekday = m.weekday,
                            explanation = Explanations.of(listOf(RuleIds.CAR_07, RuleIds.OVERRIDE) + (if (m.kind == CardioKind.ZONE2 || m.kind == CardioKind.WALK) emptyList() else listOf(RuleIds.CAR_04, RuleIds.CAR_05)), "Cardio added by you: ${m.minutes} min.", mapOf("weekday" to m.weekday.toString()), Confidence.MODERATE),
                        ),
                    )
                }
                else -> Unit
            }
        }
        return out.sortedBy { it.weekday }
    }

    /** CAR-07 floor by goal: (sessions, minutes). ENDURANCE_SUPPORT keeps the user's own plan (none scheduled here). */
    fun floorFor(goal: Goal): Pair<Int, Int> = when (goal) {
        Goal.HYPERTROPHY, Goal.STRENGTH -> 2 to 20
        Goal.GENERAL_FITNESS -> 2 to 30
        Goal.FAT_LOSS -> 3 to 50
        Goal.ENDURANCE_SUPPORT -> 0 to 0
    }

    /** CAR-08 ranking: bike > rower > elliptical > treadmill walk > outdoor walk/run (running excluded next to leg days or with a knee limitation). */
    fun modality(equipment: SessionEquipment, allowRunning: Boolean): String? {
        val ranked = listOf("stationary_bike", "rowing_erg", "elliptical", "air_bike", "treadmill", "outdoor_run_walk")
        for (id in ranked) {
            val e = kb.exercise(id) ?: continue
            if (!allowRunning && kb.cardioModality(id, false)?.legDayAdjacency == POOR) continue
            if (resolver.resolve(e, equipment).available) return id
        }
        return null
    }

    companion object {
        const val POOR: String = "poor"
        const val TANAKA_A: Double = 208.0
        const val TANAKA_B: Double = 0.7
        const val ZONE2_LO: Double = 0.60
        const val ZONE2_HI: Double = 0.70
        const val RPE_LO: Int = 11
        const val RPE_HI: Int = 13
    }
}
