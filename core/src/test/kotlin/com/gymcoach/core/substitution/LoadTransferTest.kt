package com.gymcoach.core.substitution

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.ProgressionStatus
import com.gymcoach.core.model.Sex
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.planner.PlannerFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoadTransferTest {
    private val kb = PlannerFixtures.kb
    private val t = LoadTransfer(kb)
    private val p1 = PlannerFixtures.p1()
    private val p2 = PlannerFixtures.p2()

    private fun ex(id: String) = kb.requireExercise(id)

    @Test
    fun directSeedsFromAppendixC() {
        // barbell bench 80 → dumbbell bench 0.35 per hand = 28 per hand → pair total 56.
        val (kg, why) = checkNotNull(t.seed(ex("dumbbell_bench_press"), LoadType.DUMBBELL, emptyList(), p1, TrainingAge.INTERMEDIATE, 84.0))
        assertEquals(56.0, kg, 1e-9)
        assertEquals(listOf(RuleIds.TRV_04), why.ruleIds)
        // Bulgarian split squat 22 → reverse lunge 1.0.
        assertEquals(22.0, checkNotNull(t.seed(ex("reverse_lunge"), LoadType.DUMBBELL, emptyList(), p1, TrainingAge.INTERMEDIATE, 84.0)).first, 1e-9)
        // hip thrust 120 → glute bridge 0.5.
        assertEquals(60.0, checkNotNull(t.seed(ex("glute_bridge"), LoadType.DUMBBELL, emptyList(), p1, TrainingAge.INTERMEDIATE, 84.0)).first, 1e-9)
    }

    @Test
    fun fromTheReplacedExerciseViaTheClosestSibling() {
        // E2: deadlift 140 → RDL 0.70 = 98 for the dumbbell RDL; × 0.5 for the single-leg RDL; swing 0.20 × 140 = 28.
        assertEquals(98.0, checkNotNull(t.fromReplaced(ex("conventional_deadlift"), 140.0, ex("dumbbell_romanian_deadlift"), LoadType.DUMBBELL)).first, 1e-9)
        assertEquals(49.0, checkNotNull(t.fromReplaced(ex("conventional_deadlift"), 140.0, ex("single_leg_romanian_deadlift"), LoadType.DUMBBELL)).first, 1e-9)
        assertEquals(28.0, checkNotNull(t.fromReplaced(ex("conventional_deadlift"), 140.0, ex("kettlebell_swing"), LoadType.DUMBBELL)).first, 1e-9)
        assertEquals(147.0, checkNotNull(t.fromReplaced(ex("conventional_deadlift"), 140.0, ex("trap_bar_deadlift"), LoadType.BARBELL)).first, 1e-9)
    }

    @Test
    fun genericSamePatternRulesAndTheUnilateralFactor() {
        val st = ProgressionState(ProgressionKey("dumbbell_romanian_deadlift"), ProgressionStatus.PROGRESSING, workingLoadKg = 60.0, workingReps = PlannerFixtures.band(8, 12), repHiBase = 12, targetRir = 2, updatedAt = PlannerFixtures.NOW)
        val profile = p1.copy(knownLoadsKg = emptyMap())
        assertEquals(30.0, checkNotNull(t.seed(ex("single_leg_romanian_deadlift"), LoadType.DUMBBELL, listOf(st), profile, TrainingAge.INTERMEDIATE, 84.0)).first, 1e-9)
        assertEquals(0.5, kb.loadTransfer.unilateralFactor)
    }

    @Test
    fun bodyweightFractionSeedsWithSexAndTrainingAgeFactors() {
        // E3 (P2, 78 kg novice male): hack squat SQUAT/MACHINE 1.0 × 78; DB bench 0.15 × 78 per hand; seated cable row 0.45 × 78.
        assertEquals(78.0, checkNotNull(t.seed(ex("hack_squat"), LoadType.MACHINE, emptyList(), p2, TrainingAge.NOVICE, 78.0)).first, 1e-9)
        assertEquals(23.4, checkNotNull(t.seed(ex("dumbbell_bench_press"), LoadType.DUMBBELL, emptyList(), p2, TrainingAge.NOVICE, 78.0)).first, 1e-9)
        assertEquals(35.1, checkNotNull(t.seed(ex("seated_cable_row"), LoadType.CABLE, emptyList(), p2, TrainingAge.NOVICE, 78.0)).first, 1e-9)
        assertEquals(78.0 * 0.6 * 1.4, checkNotNull(t.seed(ex("hack_squat"), LoadType.MACHINE, emptyList(), p2.copy(sex = Sex.FEMALE), TrainingAge.INTERMEDIATE, 78.0)).first, 1e-3)
        assertEquals(78.0 * 1.7, checkNotNull(t.seed(ex("hack_squat"), LoadType.MACHINE, emptyList(), p2, TrainingAge.ADVANCED, 78.0)).first, 1e-3)
        assertNull(t.seed(ex("hack_squat"), LoadType.MACHINE, emptyList(), p2, TrainingAge.NOVICE, 0.0))
    }

    @Test
    fun noSeedForBodyweightBandOrCardio() {
        assertNull(t.seed(ex("pull_up"), LoadType.BODYWEIGHT, emptyList(), p1, TrainingAge.INTERMEDIATE, 84.0))
        assertNull(t.seed(ex("band_lateral_raise"), LoadType.BAND, emptyList(), p1, TrainingAge.INTERMEDIATE, 84.0))
        assertTrue(LoadTransfer.loadBearing(LoadType.SLED))
    }
}
