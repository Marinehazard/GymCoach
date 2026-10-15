package com.gymcoach.core.planner

import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.equipment.Presets
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.ClasspathKnowledgeSource
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.knowledge.KnowledgeLoader
import com.gymcoach.core.model.Band
import com.gymcoach.core.model.BodyMetric
import com.gymcoach.core.model.EnteredLength
import com.gymcoach.core.model.EnteredWeight
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.LengthUnit
import com.gymcoach.core.model.Limitation
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.PlannedSession
import com.gymcoach.core.model.PlannedSlot
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.ProgressionStatus
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.Sex
import com.gymcoach.core.model.SlotRole
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.model.WeightUnit
import com.gymcoach.core.planner.realize.RirStack
import com.gymcoach.core.volume.VolumeAccountant
import java.time.Instant
import java.time.LocalDate

/** Shared, deterministic fixtures of the WP-03 tests: profiles P1/P2 of §4.17, preset gyms, PlanInputs builders. */
object PlannerFixtures {
    val kb: KnowledgeBase by lazy { KnowledgeLoader(CoreJson.assets).load(ClasspathKnowledgeSource()) }
    val c: PlannerConstants = PlannerConstants.DEFAULT
    val NOW: Instant = Instant.parse("2026-09-07T05:00:00Z")
    /** Monday of the E1 week 1. */
    val MONDAY: LocalDate = LocalDate.of(2026, 9, 7)

    const val HOME_ID: String = "gym-home"
    const val HOTEL_ID: String = "gym-hotel"
    const val BASE_ID: String = "gym-base"

    fun gym(preset: String, id: String): GymSet = Presets.gymFromPreset(kb, preset, preset, id, NOW)
    val home: GymSet by lazy { gym(Presets.HOME_GYM, HOME_ID) }
    val hotel: GymSet by lazy { gym(Presets.HOTEL_GYM, HOTEL_ID) }
    val base: GymSet by lazy { gym(Presets.BASE_GYM, BASE_ID) }

    /** P1: male, born 1988, 84 kg, 180 cm, 18 months → INTERMEDIATE, HYPERTROPHY, 4 × 60 min, known loads of §4.17. */
    fun p1(): Profile = Profile(
        sex = Sex.MALE, birthYear = 1988, height = EnteredLength(180.0, LengthUnit.CM, 180.0), trainingMonthsConsistent = 18,
        goal = Goal.HYPERTROPHY, daysPerWeek = 4, sessionMinutes = 60,
        knownLoadsKg = linkedMapOf(
            "barbell_bench_press" to 80.0, "back_squat" to 110.0, "romanian_deadlift" to 100.0, "conventional_deadlift" to 140.0,
            "overhead_press_barbell" to 50.0, "barbell_row" to 70.0, "hip_thrust" to 120.0, "bulgarian_split_squat" to 22.0, "goblet_squat" to 24.0,
        ),
        bedtimeLocal = "23:00", updatedAt = NOW,
    )

    /** P2: male, born 1997, 78 kg, 176 cm, 2 months → NOVICE, GENERAL_FITNESS, 3 × 60 min (Mon/Wed/Fri), no known loads. */
    fun p2(): Profile = Profile(
        sex = Sex.MALE, birthYear = 1997, height = EnteredLength(176.0, LengthUnit.CM, 176.0), trainingMonthsConsistent = 2,
        goal = Goal.GENERAL_FITNESS, daysPerWeek = 3, sessionMinutes = 60, preferredWeekdays = listOf(1, 3, 5), updatedAt = NOW,
    )

    fun weight(kg: Double, date: LocalDate = MONDAY.minusDays(1)): BodyMetric =
        BodyMetric("bm-$date", date, EnteredWeight(kg, WeightUnit.KG, kg), loggedAt = NOW)

    fun inputs(
        profile: Profile,
        gyms: List<GymSet> = listOf(home),
        defaultGymId: String = gyms.first().id,
        states: List<ProgressionState> = emptyList(),
        anchor: LocalDate? = MONDAY,
        bodyWeightKg: Double? = 84.0,
        mesocyclesCompleted: Int = 0,
    ): PlanInputs = PlanInputs(
        profile = profile, gyms = gyms, defaultGymId = defaultGymId, overrides = emptyList(), progressionStates = states,
        recentSessions = emptyList(), recentSets = emptyList(), bodyMetrics = listOfNotNull(bodyWeightKg?.let { weight(it) }),
        weeklyCheckins = emptyList(), readinessHistory = emptyList(), mesocycleAnchor = anchor, mesocyclesCompleted = mesocyclesCompleted,
    )

    /** CALIBRATING states for every known load (what ProgressionEngine.initialState seeds, §4.9.1), keyed at [gym]. */
    fun knownStates(profile: Profile, gym: GymSet): List<ProgressionState> {
        val eq = SessionEquipment(gym)
        val resolver = EquipmentResolver(kb)
        return profile.knownLoadsKg.entries.sortedBy { it.key }.map { (id, kg) ->
            val e = kb.requireExercise(id)
            val lt = resolver.resolve(e, eq).effectiveLoadType ?: e.loadType
            val p = kb.prescription(e, profile.goal)
            ProgressionState(
                key = ProgressionKey.forExercise(id, lt, gym.id), status = ProgressionStatus.CALIBRATING, workingLoadKg = kg,
                workingReps = p.range, repHiBase = p.range.hi, targetRir = RirStack.profileTargetRir(p), updatedAt = NOW,
            )
        }
    }

    /** A planned slot of a hand-built session (the E1 allocation of §4.17), option resolved at [gym]. */
    fun slot(slotId: String, role: SlotRole, exerciseId: String, sets: Int, heavy: Boolean = false, group: String? = null, gym: GymSet = home): PlannedSlot {
        val e = kb.requireExercise(exerciseId)
        val a = EquipmentResolver(kb).resolve(e, SessionEquipment(gym))
        return PlannedSlot(slotId, role, e.pattern, exerciseId, a.optionIndex ?: 0, sets, heavy, group, Explanation(listOf("SEL", "VOL-04", "VOL-06"), "fixture"))
    }

    fun session(id: String, templateId: String, weekday: Int, slots: List<PlannedSlot>, minutes: Double = 0.0): PlannedSession {
        val snap = VolumeAccountant.snapshotOf(slots.map { it.exerciseId to it.sets.toDouble() }, kb)
        return PlannedSession(id, templateId, templateId, weekday, slots, minutes, snap.fractional, snap.direct)
    }

    /** The E1 week-1 allocation (§4.17 tables), hand-built so that the Stage-6 fixtures (E1 prescriptions, E2, G2) are independent of Stage 3–5. */
    fun e1Week(): MesoWeek {
        val upperA = session("meso:w1:d1:UPPER_A", "UPPER_A", 1, listOf(
            slot("s1", SlotRole.PRIMARY, "barbell_bench_press", 4, heavy = true),
            slot("s2", SlotRole.PRIMARY, "barbell_row", 3),
            slot("s3", SlotRole.SECONDARY, "seated_dumbbell_shoulder_press", 3),
            slot("s4", SlotRole.SECONDARY, "pull_up", 3),
            slot("s5", SlotRole.ISOLATION, "dumbbell_lateral_raise", 4, group = "s5+s6"),
            slot("s6", SlotRole.ISOLATION, "face_pull", 2, group = "s5+s6"),
            slot("s7", SlotRole.ACCESSORY, "dumbbell_fly", 2, group = "s7+s8"),
            slot("s8", SlotRole.ISOLATION, "incline_dumbbell_curl", 2, group = "s7+s8"),
        ))
        val lowerA = session("meso:w1:d2:LOWER_A", "LOWER_A", 2, listOf(
            slot("s1", SlotRole.PRIMARY, "back_squat", 4, heavy = true),
            slot("s2", SlotRole.SECONDARY, "romanian_deadlift", 3),
            slot("s3", SlotRole.ACCESSORY, "bulgarian_split_squat", 2),
            slot("s4", SlotRole.ISOLATION, "slider_leg_curl", 3),
            slot("s5", SlotRole.ISOLATION, "single_leg_calf_raise", 2, group = "s5+s6"),
            slot("s6", SlotRole.CORE, "dead_bug", 2, group = "s5+s6"),
        ))
        val upperB = session("meso:w1:d4:UPPER_B", "UPPER_B", 4, listOf(
            slot("s1", SlotRole.PRIMARY, "pull_up", 4),
            slot("s2", SlotRole.PRIMARY, "incline_dumbbell_press", 4),
            slot("s3", SlotRole.SECONDARY, "overhead_press_barbell", 3, heavy = true),
            slot("s4", SlotRole.SECONDARY, "chest_supported_dumbbell_row", 3),
            slot("s5", SlotRole.ISOLATION, "dumbbell_lateral_raise", 4, group = "s5+s6"),
            slot("s6", SlotRole.ISOLATION, "dumbbell_rear_delt_fly", 2, group = "s5+s6"),
            slot("s7", SlotRole.ACCESSORY, "dumbbell_fly", 2),
        ))
        val lowerB = session("meso:w1:d5:LOWER_B", "LOWER_B", 5, listOf(
            slot("s1", SlotRole.PRIMARY, "conventional_deadlift", 3, heavy = true),
            slot("s2", SlotRole.SECONDARY, "goblet_squat", 3),
            slot("s3", SlotRole.ACCESSORY, "hip_thrust", 2),
            slot("s4", SlotRole.ACCESSORY, "reverse_lunge", 2),
            slot("s5", SlotRole.ISOLATION, "slider_leg_curl", 3),
            slot("s6", SlotRole.ISOLATION, "single_leg_calf_raise", 3, group = "s6+s7"),
            slot("s7", SlotRole.CORE, "pallof_press", 3, group = "s6+s7"),
        ))
        val targets = LinkedHashMap<Muscle, Double>()
        for (m in listOf(Muscle.CHEST, Muscle.SIDE_DELTS, Muscle.LATS, Muscle.UPPER_BACK, Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.GLUTES)) targets[m] = 12.0
        for (m in listOf(Muscle.REAR_DELTS, Muscle.BICEPS, Muscle.TRICEPS, Muscle.ABS, Muscle.CALVES)) targets[m] = 7.0
        return MesoWeek(1, MONDAY, WeekMode.NORMAL, 1, VolumeAccountant.ordered(targets), listOf(upperA, lowerA, upperB, lowerB))
    }

    fun band(lo: Int, hi: Int): Band = Band(lo, hi)
    fun lim(region: com.gymcoach.core.model.LimitationRegion, pain: Int, limitedRange: Boolean = false): Limitation =
        Limitation("lim-${region.name.lowercase()}", region, pain0to10 = pain, since = MONDAY.minusDays(30), limitedRange = limitedRange)
}
