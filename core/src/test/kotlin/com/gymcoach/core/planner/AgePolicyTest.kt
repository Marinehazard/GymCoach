package com.gymcoach.core.planner

import com.gymcoach.core.model.Band
import com.gymcoach.core.model.TrainingAge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §4.4 AgePolicy (AGE-01/02/03) — the age 62 golden case is in GoldenPlansTest. */
class AgePolicyTest {
    private val kb = PlannerFixtures.kb
    private val c = PlannerConstants.DEFAULT
    private val a = AgePolicy(c)

    @Test
    fun age02SetsRepsAndRest() {
        assertTrue(a.over60(60)); assertFalse(a.over60(59))
        assertEquals(Band(2, 3), a.setsBand(62, Band(3, 5)))
        assertEquals(Band(3, 5), a.setsBand(45, Band(3, 5)))
        assertEquals(Band(7, 9), a.repBand(62, kb.requireExercise("goblet_squat"), Band(8, 12)))
        assertEquals(Band(12, 20), a.repBand(62, kb.requireExercise("dumbbell_lateral_raise"), Band(12, 20)))   // isolation unchanged
        assertEquals(Band(5, 10), a.repBand(59, kb.requireExercise("barbell_bench_press"), Band(5, 10)))
        assertEquals(120, a.restSec(62, 180))
        assertEquals(60, a.restSec(62, 30))
        assertEquals(180, a.restSec(50, 180))
        assertTrue(a.singleSetWeek(62, TrainingAge.NOVICE, 2))
        assertFalse(a.singleSetWeek(62, TrainingAge.NOVICE, 3))
        assertFalse(a.singleSetWeek(62, TrainingAge.INTERMEDIATE, 1))
    }

    @Test
    fun age03MaxStrengthGateAndAge01Protein() {
        assertFalse(a.maxStrengthAllowed(62, TrainingAge.INTERMEDIATE, true))
        assertFalse(a.maxStrengthAllowed(62, TrainingAge.ADVANCED, false))
        assertTrue(a.maxStrengthAllowed(62, TrainingAge.ADVANCED, true))
        assertTrue(a.maxStrengthAllowed(45, TrainingAge.NOVICE, false))
        assertEquals(1.9, a.proteinFloorGPerKg(40))
        assertNull(a.proteinFloorGPerKg(39))
    }
}
