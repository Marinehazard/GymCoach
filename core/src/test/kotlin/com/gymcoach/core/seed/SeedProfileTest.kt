package com.gymcoach.core.seed

import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.equipment.GymParams
import com.gymcoach.core.equipment.IncrementResolver
import com.gymcoach.core.equipment.LoadRounder
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.LimitationRegion
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.Sex
import com.gymcoach.core.model.Side
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.UnitSystem
import com.gymcoach.core.model.WeightUnit
import com.gymcoach.core.planner.Planner
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.planner.PlannerFixtures
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SPEC §3.11: the shipped seed profile joe_v1 (docs/USER_PROFILE.md) loads, validates and carries the user's facts. */
class SeedProfileTest {
    private val kb = PlannerFixtures.kb
    private val seed: SeedProfile = assertNotNull(SeedProfiles.classpath(SeedProfiles.JOE_V1))
    private val home = seed.gyms.first { it.name == "Home gym" }
    private val bigger = seed.gyms.first { it.name == "Bigger gym" }

    @Test
    fun loadsAndValidates() {
        assertEquals(emptyList(), SeedProfiles.validate(seed, kb))
        assertEquals(seed, SeedProfiles.decode(SeedProfiles.encode(seed)), "decode/encode/decode round trip")
        assertNull(SeedProfiles.classpath("nobody_v1"))
    }

    @Test
    fun profileFactsFromUserProfile() {
        val p = seed.profile
        assertEquals(Sex.MALE, p.sex)
        assertEquals(1978, p.birthYear)
        assertEquals(UnitSystem.METRIC, seed.units)
        assertEquals(Goal.HYPERTROPHY, p.goal)
        assertEquals(TrainingAge.ADVANCED, Planner(kb, PlannerConstants.DEFAULT).trainingAgeOf(p, LocalDate.of(2026, 10, 19)))
        assertEquals(5, p.daysPerWeek)
        assertEquals(listOf(1, 2, 4, 5, 6), p.preferredWeekdays)
        assertEquals("joe_5day", p.splitId)
        assertEquals(mapOf("overhead_press_barbell" to 60.0, "barbell_bench_press" to 100.0, "back_squat" to 140.0, "conventional_deadlift" to 180.0), p.strengthTargetsKg)
        assertEquals(2, p.barbellRirFloor, "barbell lifts capped at RPE 8 = RIR 2")
        assertTrue(p.physicalJob)
        val knee = p.limitations.first { it.region == LimitationRegion.KNEE }
        assertEquals(Side.LEFT, knee.side)
        assertEquals(0, knee.pain0to10)
        assertTrue(p.limitations.any { it.notes.contains("Hips rise first") })
        assertTrue(p.limitations.any { it.notes.contains("overshooting") })
        assertTrue(p.coachNotes.orEmpty().any { it.contains("drop the arms day first") })
        // Session lengths of the split templates (USER_PROFILE §8): 75/70/70/60/40 min.
        val row = assertNotNull(com.gymcoach.core.knowledge.SessionTemplates.personalSplit(kb, p.splitId))
        assertEquals(listOf(75, 70, 70, 60, 40), row.sessionTemplateIds.map { kb.templates.getValue(it).minutes })
        assertTrue(seed.placeholders.any { it.startsWith("profile.height") }, "height is not in USER_PROFILE.md and must be flagged")
    }

    @Test
    fun twoGymsWithTheRightEquipment() {
        assertEquals(listOf("Home gym", "Bigger gym"), seed.gyms.map { it.name })
        assertEquals(home.id, seed.defaultGymId)
        val resolver = EquipmentResolver(kb)
        fun available(gymId: String, id: String) = resolver.resolve(kb.requireExercise(id), SessionEquipment(seed.gyms.first { it.id == gymId })).available
        for (id in listOf("back_squat", "barbell_bench_press", "overhead_press_barbell", "conventional_deadlift", "smith_machine_squat", "machine_hip_thrust",
            "pec_deck_fly", "pull_up", "face_pull", "lying_triceps_extension", "incline_dumbbell_press", "band_lateral_walk")) {
            assertTrue(available(home.id, id), "home: $id")
            assertTrue(available(bigger.id, id), "bigger: $id")
        }
        for (id in listOf("hack_squat", "leg_press_45", "leg_extension", "lying_leg_curl", "hip_abduction_machine", "machine_shoulder_press", "machine_lateral_raise", "reverse_pec_deck")) {
            assertFalse(available(home.id, id), "home must not offer $id")
            assertTrue(available(bigger.id, id), "bigger: $id")
        }
        // The home fly machine has no rear-delt mode: the mode gate keeps the reverse pec-deck out (R4 §2.6 param).
        assertTrue(resolver.resolve(kb.requireExercise("pec_deck_fly"), SessionEquipment(home)).available)
    }

    @Test
    fun barbellJumpsAreFiveKiloAtHomeAndTwoAndAHalfAtTheBiggerGym() {
        val inc = IncrementResolver(kb)
        fun step(gym: com.gymcoach.core.model.GymSet, id: String, kg: Double) =
            inc.step(kb.requireExercise(id), LoadType.BARBELL, GymParams(kb, SessionEquipment(gym)), kg, Sex.MALE, TrainingAge.ADVANCED).kg
        // Home: smallest plate 2.5 kg → one plate pair = 5 kg on every barbell lift.
        assertEquals(5.0, step(home, "barbell_bench_press", 80.0))
        assertEquals(5.0, step(home, "overhead_press_barbell", 45.0))
        assertEquals(5.0, step(home, "back_squat", 90.0))
        assertEquals(5.0, step(home, "conventional_deadlift", 140.0))
        // Bigger gym: 1.25 kg plates → 2.5 kg jumps (PROG-02 keeps 5 kg for a lower-body barbell lift at ≤ 100 kg).
        assertEquals(2.5, step(bigger, "barbell_bench_press", 80.0))
        assertEquals(2.5, step(bigger, "overhead_press_barbell", 45.0))
        assertEquals(2.5, step(bigger, "conventional_deadlift", 140.0))
        assertEquals(2.5, step(bigger, "back_squat", 105.0))
        assertEquals(5.0, step(bigger, "back_squat", 90.0))
        // LoadRounder: 92.5 kg cannot be loaded at home, it can at the bigger gym.
        val rounder = LoadRounder(kb)
        val bench = kb.requireExercise("barbell_bench_press")
        assertEquals(0.0, rounder.round(92.5, bench, LoadType.BARBELL, GymParams(kb, SessionEquipment(bigger))).kg % 2.5, 1e-9)
        assertEquals(92.5, rounder.round(92.5, bench, LoadType.BARBELL, GymParams(kb, SessionEquipment(bigger))).kg, 1e-9)
        assertEquals(0.0, rounder.round(92.5, bench, LoadType.BARBELL, GymParams(kb, SessionEquipment(home))).kg % 5.0, 1e-9)
    }

    @Test
    fun historyIsTheLogWithRpeAsRir() {
        val sets = seed.history.sets
        assertEquals(11, seed.history.sessions.size)
        // 12 Oct: squat 90×3 at RPE 8 → RIR 2; back-offs 80×5/5/5.
        val oct12 = sets.filter { it.sessionId == "seed-2026-10-12" && it.exerciseId == "back_squat" }
        assertEquals(listOf(90.0 to 3, 80.0 to 5, 80.0 to 5, 80.0 to 5), oct12.map { it.weight!!.kg to it.reps })
        assertEquals(2, oct12.first().rir)
        assertEquals("RPE 8", oct12.first().note)
        // Logged numbers are verbatim: entered value == kg, unit kg.
        assertTrue(sets.all { it.weight == null || (it.weight!!.unit == WeightUnit.KG && it.weight!!.entered == it.weight!!.kg) })
        val bench13 = sets.filter { it.sessionId == "seed-2026-10-13" && it.exerciseId == "barbell_bench_press" }.map { it.weight!!.kg to it.reps }
        assertEquals(listOf(80.0 to 3, 70.0 to 6, 70.0 to 6, 70.0 to 6), bench13)
        val ohp15 = sets.filter { it.sessionId == "seed-2026-10-15" }.map { it.weight!!.kg to it.reps }
        assertEquals(listOf(45.0 to 6, 40.0 to 6, 45.0 to 6, 40.0 to 6), ohp15)
        assertEquals(listOf(160.0 to 2), sets.filter { it.exerciseId == "conventional_deadlift" }.map { it.weight!!.kg to it.reps })
    }

    @Test
    fun seededHistoryYieldsTheEstimatedMaxesOfSection2() {
        val best = SeedProfiles.bestE1rm(seed)
        fun near(id: String, expected: Double, pct: Double = 3.0) {
            val kg = assertNotNull(best[id], id).kg
            assertTrue(kotlin.math.abs(kg - expected) <= expected * pct / 100.0, "$id e1RM $kg vs ~$expected")
        }
        near("overhead_press_barbell", 52.0)
        near("barbell_bench_press", 92.0)
        near("back_squat", 103.5)                 // USER_PROFILE §2: ~102–105
        assertTrue(best.getValue("back_squat").kg in 102.0..105.0)
        near("conventional_deadlift", 170.0)
    }

    @Test
    fun progressionStartsFromRealityNotFindLoad() {
        val states = SeedProfiles.progressionStates(seed, kb)
        fun state(id: String, variant: String? = null) = assertNotNull(states.firstOrNull { it.key == ProgressionKey(id, null, variant) }, "$id#$variant")
        assertEquals(90.0, state("back_squat").workingLoadKg)
        assertEquals(80.0, state("back_squat", "backoff").workingLoadKg)
        assertEquals(80.0, state("barbell_bench_press").workingLoadKg)
        assertEquals(70.0, state("barbell_bench_press", "backoff").workingLoadKg)
        assertEquals(45.0, state("overhead_press_barbell").workingLoadKg)
        assertEquals(140.0, state("conventional_deadlift").workingLoadKg)
        assertEquals(com.gymcoach.core.model.Band(5, 7), state("back_squat", "backoff").workingReps)
        assertEquals(com.gymcoach.core.model.Band(3, 3), state("back_squat").workingReps)
        assertTrue(states.all { it.targetRir == 2 && it.status == com.gymcoach.core.model.ProgressionStatus.PROGRESSING })
        // History summaries come from the log (newest first); the squat main track's best e1RM is the 12 Oct triple at RPE 8.
        val squat = state("back_squat")
        assertEquals(LocalDate.of(2026, 10, 12), squat.history.first().date)
        assertEquals(103.1, squat.history.first().e1rm!!.kg)
        assertEquals("back_squat#backoff", state("back_squat", "backoff").key.id)
        assertEquals(LocalDate.of(2026, 10, 12), state("back_squat", "backoff").history.first().date)
    }
}
