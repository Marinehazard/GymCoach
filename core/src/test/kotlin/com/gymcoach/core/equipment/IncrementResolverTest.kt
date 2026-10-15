package com.gymcoach.core.equipment

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.IncrementDirection
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.Sex
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.planner.PlannerConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** R4 §5.4 / PROG-02 increment table (SPEC §4.9.3) incl. implements() and both logging styles. */
class IncrementResolverTest {
    private val kb = Gyms.kb
    private val inc = IncrementResolver(kb)
    private val base = GymParams(kb, Gyms.eq(Gyms.preset("base_gym")))

    private fun step(id: String, lt: LoadType, params: GymParams = base, load: Double? = null, sex: Sex = Sex.MALE, age: TrainingAge = TrainingAge.INTERMEDIATE) =
        inc.step(kb.requireExercise(id), lt, params, load, sex, age)

    @Test
    fun prog02UpperBarbellIs2point5Or1WithMicroplates() {
        val s = step("barbell_bench_press", LoadType.BARBELL, load = 80.0)
        assertEquals(2.5, s.kg)
        assertTrue(!s.perHand && s.direction == IncrementDirection.LOAD)
        assertEquals(listOf(RuleIds.PROG_02, RuleIds.R4_5_4), s.explanation.ruleIds)
        val micro = GymParams(kb, Gyms.eq(Gyms.of("barbell" to emptyMap(), "plates" to mapOf("has_microplates" to Gyms.bool(true)))))
        assertEquals(PlannerConstants.DEFAULT.prog.prog02MicroplateKg, step("barbell_bench_press", LoadType.BARBELL, micro, 80.0).kg)
    }

    @Test
    fun prog02LowerBarbellRuleH22() {
        val c = PlannerConstants.DEFAULT.prog
        assertEquals(c.prog02LowerBarbellKg, step("back_squat", LoadType.BARBELL, load = 80.0).kg)
        assertEquals(c.prog02LowerBarbellSmallKg, step("back_squat", LoadType.BARBELL, load = 102.5).kg, "> 100 kg")
        assertEquals(c.prog02LowerBarbellSmallKg, step("back_squat", LoadType.BARBELL, load = 60.0, sex = Sex.FEMALE).kg)
        assertEquals(c.prog02LowerBarbellSmallKg, step("conventional_deadlift", LoadType.BARBELL, load = 60.0, age = TrainingAge.NOVICE).kg)
        // never below what the plates can load: smallest plate 2.5 → pair 5.0
        val coarse = GymParams(kb, Gyms.eq(Gyms.of("barbell" to emptyMap(), "plates" to mapOf("smallest_plate_kg" to Gyms.num(2.5)))))
        assertEquals(5.0, step("back_squat", LoadType.BARBELL, coarse, 60.0, Sex.FEMALE).kg)
        assertEquals(5.0, step("barbell_bench_press", LoadType.BARBELL, coarse, 60.0).kg)
    }

    @Test
    fun dumbbellRackStepPerHandAndKettlebellNextBell() {
        val s = step("dumbbell_bench_press", LoadType.DUMBBELL, load = 24.0)
        assertEquals(2.0, s.kg)
        assertTrue(s.perHand)
        val hotel = GymParams(kb, Gyms.eq(Gyms.of("dumbbells" to mapOf("step_kg" to Gyms.num(2.5)))))
        assertEquals(2.5, step("dumbbell_curl", LoadType.DUMBBELL, hotel).kg)
        val home = GymParams(kb, Gyms.eq(Gyms.preset("home_gym")))
        assertEquals(8.0, step("goblet_squat", LoadType.KETTLEBELL, home, 16.0).kg)
        val bells = GymParams(kb, Gyms.eq(Gyms.of("kettlebells" to mapOf("available_kg" to JsonArray(listOf(JsonPrimitive(12), JsonPrimitive(16), JsonPrimitive(20)))))))
        assertEquals(4.0, step("farmers_carry", LoadType.KETTLEBELL, bells, 32.0).kg, "pair total 32 = 2 × 16 → next bell 20")
        assertTrue(step("farmers_carry", LoadType.KETTLEBELL, bells, 16.0).perHand)
    }

    @Test
    fun machineStackHalfStepPlateLoadedAndAssist() {
        assertEquals(5.0, step("leg_extension", LoadType.MACHINE).kg, "default stack step")
        val half = GymParams(kb, Gyms.eq(Gyms.of("lat_pulldown_machine" to mapOf("half_step_kg" to Gyms.num(2.5)))))
        assertEquals(2.5, step("lat_pulldown", LoadType.MACHINE, half).kg)
        assertEquals(2.5, step("leg_press_45", LoadType.MACHINE).kg, "plate-loaded: 2 × 1.25")
        assertEquals(5.0, step("seated_cable_row", LoadType.CABLE).kg)
        val assist = step("assisted_pull_up", LoadType.MACHINE)
        assertEquals(IncrementDirection.ASSIST, assist.direction)
        assertEquals(5.0, assist.kg)
        assertEquals(5.0, step("sled_push", LoadType.SLED, GymParams(kb, Gyms.eq(Gyms.ids("sled")))).kg, "no plates param → 2 × 2.5")
    }

    @Test
    fun bodyweightBandCardioAndIsolationNote() {
        assertEquals(PlannerConstants.DEFAULT.prog.prog02BodyweightExternalKg, step("pull_up", LoadType.BODYWEIGHT).kg)
        assertEquals(0.0, step("band_pull_apart", LoadType.BAND).kg)
        assertEquals(0.0, step("treadmill", LoadType.CARDIO).kg)
        assertTrue(step("dumbbell_curl", LoadType.DUMBBELL).explanation.message.contains("prefer +reps"))
        assertTrue(!step("dumbbell_bench_press", LoadType.DUMBBELL).explanation.message.contains("prefer +reps"))
    }

    @Test
    fun implementsPairVersusSingle() {
        assertEquals(2, inc.implements(kb.requireExercise("dumbbell_bench_press"), LoadType.DUMBBELL))
        assertEquals(2, inc.implements(kb.requireExercise("farmers_carry"), LoadType.KETTLEBELL))
        assertEquals(1, inc.implements(kb.requireExercise("dumbbell_row"), LoadType.DUMBBELL), "unilateral")
        assertEquals(1, inc.implements(kb.requireExercise("goblet_squat"), LoadType.DUMBBELL), "single implement")
        assertEquals(1, inc.implements(kb.requireExercise("goblet_squat"), LoadType.KETTLEBELL))
        assertEquals(1, inc.implements(kb.requireExercise("suitcase_carry"), LoadType.DUMBBELL))
        assertEquals(1, inc.implements(kb.requireExercise("barbell_bench_press"), LoadType.BARBELL))
        assertEquals(1, inc.implements(kb.requireExercise("preacher_curl"), LoadType.MACHINE))
    }

    /** §4.9.3 PROG-02 > 10 % rule: 2 kg on a 2×12 kg pair is 8.3 % of the pair total but 16.7 % per hand → rep extension in both styles. */
    @Test
    fun prog02JumpRuleInBothLoggingStyles() {
        val ex = kb.requireExercise("dumbbell_bench_press")
        val implements = inc.implements(ex, LoadType.DUMBBELL)
        val maxJump = PlannerConstants.DEFAULT.prog.prog02MaxJumpPct / 100.0
        // logged as the pair total (24 kg, perHand = false)
        val pairStep = inc.step(ex, LoadType.DUMBBELL, base, 24.0, Sex.MALE, TrainingAge.INTERMEDIATE)
        val perHandFromPair = 24.0 / implements
        assertTrue(pairStep.perHand)
        assertTrue(pairStep.kg / perHandFromPair > maxJump, "16.7 % per hand")
        assertTrue(pairStep.kg / 24.0 < maxJump, "the trap: 8.3 % of the pair")
        // logged per hand (12 kg, perHand = true)
        val handStep = inc.step(ex, LoadType.DUMBBELL, base, 12.0, Sex.MALE, TrainingAge.INTERMEDIATE)
        assertEquals(pairStep.kg, handStep.kg)
        assertTrue(handStep.kg / 12.0 > maxJump)
        // a barbell step is a total-load step: 2.5 / 80 = 3.1 %
        val bb = inc.step(kb.requireExercise("barbell_bench_press"), LoadType.BARBELL, base, 80.0, Sex.MALE, TrainingAge.INTERMEDIATE)
        assertTrue(!bb.perHand && bb.kg / 80.0 < maxJump)
    }
}
