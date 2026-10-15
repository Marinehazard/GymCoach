package com.gymcoach.core.substitution

import com.gymcoach.core.equipment.GymParams
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.ExerciseBlock
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.LoadSource
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetPrescription
import com.gymcoach.core.model.SlotRole
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.planner.Planner
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.planner.PlannerFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubstitutionEngineTest {
    private val kb = PlannerFixtures.kb
    private val c = PlannerConstants.DEFAULT
    private val engine = SubstitutionEngine(kb, c)
    private val p1 = PlannerFixtures.p1()
    private val states = PlannerFixtures.knownStates(p1, PlannerFixtures.home)

    private fun block(exerciseId: String, sets: Int, lt: LoadType = LoadType.BARBELL): ExerciseBlock = ExerciseBlock(
        blockId = "s1:$exerciseId", slotId = "s1", exerciseId = exerciseId, optionIndex = 0, effectiveLoadType = lt, slotRole = SlotRole.PRIMARY, order = 0,
        sets = (1..sets).map { SetPrescription(it, SetKind.WORKING, restSeconds = 180, loadSource = LoadSource.PROGRESSION_STATE) },
        progressionKey = ProgressionKey(exerciseId), explanation = Explanation(listOf(RuleIds.LOAD_03), "fixture"),
    )

    private fun rankAtHotel(exerciseId: String, sets: Int = 3): List<SubstitutionCandidate> {
        val eq = SessionEquipment(PlannerFixtures.hotel)
        val n = Planner(kb, c).normalize(PlannerFixtures.inputs(p1), PlannerFixtures.MONDAY)
        return engine.rank(block(exerciseId, sets), eq, GymParams(kb, eq), states, n.goal, n.trainingAge, n.excludedExerciseIds, n.penalizedExerciseIds, false, p1)
    }

    @Test
    fun e2DeadliftAtTheHotelScoreTable() {
        val ranked = rankAtHotel("conventional_deadlift")
        val byId = ranked.associateBy { it.exerciseId }
        val dbRdl = byId.getValue("dumbbell_romanian_deadlift")
        val slRdl = byId.getValue("single_leg_romanian_deadlift")
        val swing = byId.getValue("kettlebell_swing")
        assertEquals(0.725, dbRdl.score, 0.0005)
        assertEquals(0.649, slRdl.score, 0.0005)
        assertEquals(0.581, swing.score, 0.0005)
        assertTrue(dbRdl.score > slRdl.score && slRdl.score > swing.score)
        assertEquals("dumbbell_romanian_deadlift", ranked.first().exerciseId)
        // Term breakdown of the winner (§4.17 E2): pattern 0.350, cosine 0.265, loadFit 0.060 (98 kg seed vs 2 × 20 kg), continuity 0, difficulty 0.05.
        assertEquals(0.35, dbRdl.terms.getValue("pattern"), 1e-9)
        assertEquals(0.265, dbRdl.terms.getValue("cosine"), 0.0005)
        assertEquals(0.06, dbRdl.terms.getValue("loadFit"), 1e-9)
        assertEquals(0.0, dbRdl.terms.getValue("continuity"), 1e-9)
        assertEquals(0.05, dbRdl.terms.getValue("difficulty"), 1e-9)
        assertEquals(40.0, dbRdl.prescribedLoadKg)
        assertEquals(LoadType.DUMBBELL, dbRdl.effectiveLoadType)
        // SUB profile penalty: a power drill never silently replaces a hypertrophy compound.
        assertEquals(-0.2, swing.terms.getValue("profilePenalty"), 1e-9)
        assertEquals(0.105, swing.terms.getValue("loadFit"), 1e-9)
        assertEquals(0.06, slRdl.terms.getValue("loadFit"), 1e-9)
        // The R4-ranked list is entirely unavailable at the hotel: those entries come last, flagged.
        val unavailable = ranked.filter { !it.available }.map { it.exerciseId }
        assertEquals(listOf("trap_bar_deadlift", "romanian_deadlift", "sumo_deadlift", "hip_thrust", "back_extension_45").filter { it in unavailable }, unavailable)
        assertTrue(ranked.none { it.exerciseId == "conventional_deadlift" })
    }

    @Test
    fun gluteBridgeScoredWithTheHipThrustSeed() {
        // §4.17 prints 0.601 for the glute bridge with a 30-kg desired load ("single-leg + DB"); glute_bridge is bilateral in
        // exercises.json, so the Appendix C seed hip_thrust 120 × 0.5 = 60 kg vs 20 kg (one dumbbell) → loadFit tier 0.1.
        val bridge = rankAtHotel("conventional_deadlift").first { it.exerciseId == "glute_bridge" }
        assertEquals(0.21, bridge.terms.getValue("pattern"), 1e-9)
        assertEquals(0.236, bridge.terms.getValue("cosine"), 0.0005)
        assertEquals(0.015, bridge.terms.getValue("loadFit"), 1e-9)
        assertEquals(0.511, bridge.score, 0.0005)
    }

    @Test
    fun rankBonusGoesToTheFirstAvailableRankedEntry() {
        val hip = rankAtHotel("hip_thrust", 2)
        assertEquals("glute_bridge", hip.first().exerciseId)
        assertEquals(0.10, hip.first().terms.getValue("rankBonus"), 1e-9)
        val pallof = rankAtHotel("pallof_press")
        assertEquals("side_plank", pallof.first().exerciseId)
    }

    @Test
    fun ledgerDeltaIsPerWeekForTheBlocksSets() {
        val d = rankAtHotel("conventional_deadlift").first { it.exerciseId == "dumbbell_romanian_deadlift" }.ledgerDelta
        assertEquals(1.5, d[Muscle.HAMSTRINGS])       // (1.0 − 0.5) × 3
        assertEquals(-1.5, d[Muscle.GLUTES])           // (0.5 − 1.0) × 3
        assertEquals(-1.5, d[Muscle.QUADS])
    }

    @Test
    fun familiesAndCosine() {
        assertTrue(SubstitutionEngine.sameFamily(MovementPattern.HINGE, MovementPattern.KNEE_FLEXION_ISOLATION))
        assertTrue(SubstitutionEngine.sameFamily(MovementPattern.INCLINE_PUSH, MovementPattern.VERTICAL_PUSH))
        assertTrue(SubstitutionEngine.sameFamily(MovementPattern.INCLINE_PUSH, MovementPattern.CHEST_FLY))
        assertFalse(SubstitutionEngine.sameFamily(MovementPattern.SQUAT, MovementPattern.HINGE))
        assertEquals(setOf(MovementPattern.GRIP), SubstitutionEngine.family(MovementPattern.GRIP))
        assertEquals(1.0, SubstitutionEngine.cosine(kb.requireExercise("romanian_deadlift"), kb.requireExercise("dumbbell_romanian_deadlift")), 1e-9)
        assertEquals(0.757, SubstitutionEngine.cosine(kb.requireExercise("conventional_deadlift"), kb.requireExercise("dumbbell_romanian_deadlift")), 0.0005)
        assertEquals(1.0, engine.loadFitOf(null))
        assertEquals(0.7, engine.loadFitOf(0.83))
        assertEquals(0.4, engine.loadFitOf(0.41))
        assertEquals(0.1, engine.loadFitOf(0.33))
        assertEquals(1, SubstitutionEngine.trainingLevel(TrainingAge.INTERMEDIATE))
    }

    @Test
    fun excludedAndPenalizedCandidates() {
        val eq = SessionEquipment(PlannerFixtures.hotel)
        val all = engine.rank(block("conventional_deadlift", 3), eq, GymParams(kb, eq), states, p1.goal, TrainingAge.INTERMEDIATE, setOf("dumbbell_romanian_deadlift"), setOf("single_leg_romanian_deadlift"), false, p1)
        assertTrue(all.none { it.exerciseId == "dumbbell_romanian_deadlift" })
        assertEquals(-0.3, all.first { it.exerciseId == "single_leg_romanian_deadlift" }.terms.getValue("limitationPenalty"), 1e-9)
    }
}
