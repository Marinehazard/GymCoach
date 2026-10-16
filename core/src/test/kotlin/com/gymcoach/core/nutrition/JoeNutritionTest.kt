package com.gymcoach.core.nutrition

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.planner.PlannerFixtures
import com.gymcoach.core.seed.SeedProfiles
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Phase 2, item 8: USER_PROFILE §12 — protein 1.6–2.0 g/kg is what MacroTargets gives Joe; his peri-workout routine is shown as weak user notes. */
class JoeNutritionTest {
    private val seed = assertNotNull(SeedProfiles.classpath())
    private val c = NF.c
    private val today = LocalDate.of(2026, 10, 19)

    @Test
    fun proteinTargetIsInsideHisBand() {
        val p = seed.profile
        val age = p.ageOn(today)
        // Body weight is not in USER_PROFILE.md (a seed placeholder): the band must hold for any plausible weight.
        for (kg in listOf(75.0, 85.0, 95.0, 105.0)) {
            val e = EnergyEstimator(c).model(p, kg, age, p.daysPerWeek, null, null)
            val t = MacroTargets(c).compute(p, kg, age, p.goal, TrainingAge.ADVANCED, e, null, 0, today)
            val gPerKg = t.proteinG / kg
            assertTrue(gPerKg in 1.6..2.0, "$kg kg → ${t.proteinG} g = $gPerKg g/kg")
            assertEquals(1.9, t.explanation.inputs["protein_g_per_kg"]!!.toDouble(), "AGE-01: from 40 on the upper half of NUT-01")
            assertTrue(RuleIds.AGE_01 in t.explanation.ruleIds)
        }
    }

    @Test
    fun periWorkoutRoutineIsAWeakUserNote() {
        val tips = PlannerFixtures.kb.nutritionTips.filter { it.id.startsWith("user_") }
        assertEquals(listOf("user_pre_workout_coffee", "user_pre_workout_carbs", "user_post_workout_protein"), tips.map { it.id })
        assertTrue(tips.all { it.confidence == Confidence.WEAK && it.ruleId == RuleIds.NUT_12 && it.citation.contains("USER_PROFILE") })
        assertTrue(tips.first().body.contains("2 cups"))
    }
}
