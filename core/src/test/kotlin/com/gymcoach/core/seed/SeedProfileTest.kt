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
}
