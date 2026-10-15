package com.gymcoach.core.nutrition

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.EnteredLength
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.LengthUnit
import com.gymcoach.core.model.NutritionState
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.Sex
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.planner.PlannerConstants
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

object NF {
    val c: PlannerConstants = PlannerConstants.DEFAULT
    val t0: Instant = Instant.parse("2026-09-01T08:00:00Z")
    val today: LocalDate = LocalDate.of(2026, 9, 21)
    val p1 = Profile(Sex.MALE, 1988, EnteredLength(180.0, LengthUnit.CM, 180.0), 18, goal = Goal.HYPERTROPHY, daysPerWeek = 4, sessionMinutes = 60, updatedAt = t0)
    /** §5.5 worked floor case: woman 55 kg, 160 cm, 45 y, sedentary, FAT_LOSS, no body-fat estimate. */
    val floorWoman = Profile(Sex.FEMALE, 1981, EnteredLength(160.0, LengthUnit.CM, 160.0), 6, goal = Goal.FAT_LOSS, daysPerWeek = 1, sessionMinutes = 45, updatedAt = t0)
}

/** G4 (P1), both sexes, the 55-kg floor case, the > 1.5 %/week review, AGE-01 1.9 g/kg, NUT-01 branches, fibre/water. */
class MacroTargetsTest {
    private val energy = EnergyEstimator(NF.c)
    private val macros = MacroTargets(NF.c)

    private fun p1Targets() = macros.compute(NF.p1, 84.0, 38, Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE,
        energy.model(NF.p1, 84.0, 38, 4, null, null), null, 2, NF.today)

    @Test fun g4P1Numbers() {
        val e = energy.model(NF.p1, 84.0, 38, 4, null, null)
        assertEquals(1780, e.reeKcal)
        assertEquals(1.55, e.activityFactor)
        assertEquals(2759, e.tdeeEstimateKcal)
        val t = p1Targets()
        assertEquals(3035, t.kcal)
        assertEquals(151, t.proteinG)
        assertEquals(84, t.fatG)
        assertEquals(419, t.carbsG)
        assertTrue(t.carbsG / 84.0 >= 3.0)
        assertEquals(42, t.fibreG)
        assertEquals(3.7, t.waterL)
        assertEquals(276, t.energyDeltaKcal)
        assertEquals(0.25, t.goalRateLo)
        assertEquals(0.5, t.goalRateHi)
        assertEquals(NF.today.plusDays(14), t.nextReviewDate)
        assertTrue(RuleIds.NUT_09 in t.explanation.ruleIds && RuleIds.NUT_08 in t.explanation.ruleIds)
        assertTrue(RuleIds.NUT_06 in t.explanation.ruleIds && RuleIds.NUT_10 in t.explanation.ruleIds && RuleIds.NUT_11 in t.explanation.ruleIds)
    }

    @Test fun g4WomanSameInputs() {
        val woman = NF.p1.copy(sex = Sex.FEMALE)
        val e = energy.model(woman, 84.0, 38, 4, null, null)
        assertEquals(1614, e.reeKcal)                       // 1,780 − 166
        val t = macros.compute(woman, 84.0, 38, Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE, e, null, 2, NF.today)
        assertEquals(Math.round(Math.round(1614 * 1.55) * 1.1).toInt(), t.kcal)
        assertEquals(2.7, t.waterL)
        assertTrue(t.fibreG >= 25)
    }

    @Test fun floorCase1436() {
        val e = energy.model(NF.floorWoman, 55.0, 45, 1, 3000, null)
        assertEquals(1164, e.reeKcal)
        assertEquals(1.2, e.activityFactor)
        assertEquals(1397, e.tdeeEstimateKcal)
        val t = macros.compute(NF.floorWoman, 55.0, 45, Goal.FAT_LOSS, TrainingAge.NOVICE, e, null, 0, NF.today)
        assertEquals(1436, t.kcal)
        assertEquals(132, t.proteinG)
        assertEquals(165, t.carbsG)
        assertEquals(28, t.fatG)
        assertEquals(39, t.energyDeltaKcal)
        assertEquals(RuleIds.NUT_08, t.explanation.ruleIds.first())
        assertTrue(t.explanation.message.contains("floors bind"), t.explanation.message)
        assertEquals(25, t.fibreG)
    }

    @Test fun fatLossDeficitNeverAbove20PercentAndNeverBelowRee() {
        val big = NF.p1.copy(goal = Goal.FAT_LOSS)
        val e = energy.model(big, 84.0, 38, 4, null, null)
        val t = macros.compute(big, 84.0, 38, Goal.FAT_LOSS, TrainingAge.INTERMEDIATE, e, null, 2, NF.today)
        assertEquals(2759 - 500, t.kcal)                    // min(500, 552)
        assertEquals(202, t.proteinG)                       // 2.4 g/kg in a deficit without a body-fat estimate
        assertEquals(-1.0, t.goalRateLo)
        assertEquals(-0.5, t.goalRateHi)
        val lean = big.copy(bodyFatPct = 12.0)
        val tl = macros.compute(lean, 84.0, 38, Goal.FAT_LOSS, TrainingAge.INTERMEDIATE, e, null, 2, NF.today)
        assertEquals(Math.round(2.7 * 84.0 * 0.88).toInt(), tl.proteinG)   // per kg fat-free mass
        assertEquals(-0.7, tl.goalRateLo)
    }

    @Test fun age01ProteinAt45() {
        val p = NF.p1.copy(birthYear = 1981)
        val e = energy.model(p, 84.0, 45, 4, null, null)
        val t = macros.compute(p, 84.0, 45, Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE, e, null, 2, NF.today)
        assertEquals(Math.round(1.9 * 84.0).toInt(), t.proteinG)
        assertTrue(RuleIds.AGE_01 in t.explanation.ruleIds)
        val g = macros.compute(p, 84.0, 70, Goal.GENERAL_FITNESS, TrainingAge.INTERMEDIATE, e, null, 2, NF.today)
        assertEquals(Math.round(1.9 * 84.0).toInt(), g.proteinG)
    }

    @Test fun userOverridesReplaceKcalAndProtein() {
        val st = NutritionState(kcalTargetOverride = 2900, proteinGPerKgOverride = 2.0, updatedAt = NF.t0)
        val t = macros.compute(NF.p1, 84.0, 38, Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE, energy.model(NF.p1, 84.0, 38, 4, null, st), st, 2, NF.today)
        assertEquals(2900, t.kcal)
        assertEquals(168, t.proteinG)
        assertTrue(t.explanation.message.contains("user-set"))
    }

    @Test fun g4Nut16ReviewAboveBandStepsDown150() {
        val t = p1Targets()
        val rate = 0.6 / 84.0 * 100.0
        val r = macros.review(t, rate, NF.today.plusDays(14))
        assertNotNull(r)
        val m = r.first as PlanMutation.UpdateNutritionTargets
        assertEquals(3035 - 150, m.kcalTarget)
        assertEquals(listOf(RuleIds.NUT_16), r.second.ruleIds)
        assertNull(macros.review(t, rate, NF.today.plusDays(13)), "not due yet")
        val inBand = macros.review(t, 0.4, NF.today.plusDays(14))
        assertNotNull(inBand)
        assertNull(inBand.first)
        val below = macros.review(t, 0.1, NF.today.plusDays(14))
        assertEquals(3035 + 150, (below?.first as PlanMutation.UpdateNutritionTargets).kcalTarget)
    }

    @Test fun nut05FastLossReviewAdds200WithSupervisionSentence() {
        val p = NF.p1.copy(goal = Goal.FAT_LOSS)
        val t = macros.compute(p, 84.0, 38, Goal.FAT_LOSS, TrainingAge.INTERMEDIATE, energy.model(p, 84.0, 38, 4, null, null), null, 2, NF.today)
        val r = macros.review(t, -1.8, NF.today.plusDays(14))
        assertNotNull(r)
        assertEquals(t.kcal + 200, (r.first as PlanMutation.UpdateNutritionTargets).kcalTarget)
        assertTrue(r.second.message.contains("consider professional supervision"))
        assertTrue(RuleIds.NUT_05 in r.second.ruleIds)
        assertNull(macros.review(t, null, NF.today.plusDays(14)))
    }

    @Test fun nut07RecompositionOfferedForNoviceLayoffOrHighBodyFat() {
        fun hyp(p: Profile, age: TrainingAge) = macros.compute(p, 84.0, 38, Goal.HYPERTROPHY, age, energy.model(p, 84.0, 38, 4, null, null), null, 2, NF.today)
        assertTrue(RuleIds.NUT_07 in hyp(NF.p1, TrainingAge.NOVICE).explanation.ruleIds)
        assertTrue(RuleIds.NUT_07 !in hyp(NF.p1, TrainingAge.INTERMEDIATE).explanation.ruleIds)
        assertTrue(RuleIds.NUT_07 in hyp(NF.p1.copy(bodyFatPct = 24.0), TrainingAge.INTERMEDIATE).explanation.ruleIds)
        assertTrue(RuleIds.NUT_07 in hyp(NF.p1.copy(layoffMonths = 6, monthsRetrainedSinceLayoff = 1), TrainingAge.INTERMEDIATE).explanation.ruleIds)
        val strength = macros.compute(NF.p1, 84.0, 38, Goal.STRENGTH, TrainingAge.NOVICE, energy.model(NF.p1, 84.0, 38, 4, null, null), null, 2, NF.today)
        assertTrue(RuleIds.NUT_07 !in strength.explanation.ruleIds)
    }

}
