package com.gymcoach.core.mutation

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.CardioKind
import com.gymcoach.core.model.CheckinAnswerType
import com.gymcoach.core.model.CheckinSchedule
import com.gymcoach.core.model.GymItem
import com.gymcoach.core.model.LimitationRegion
import com.gymcoach.core.model.MedicalFlag
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MutationScope
import com.gymcoach.core.model.NutritionTargets
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.ProgressionStatus
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.model.ReadinessCheckin
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.planner.Planner
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.planner.PlannerFixtures
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MutationValidatorTest {
    private val kb = PlannerFixtures.kb
    private val c = PlannerConstants.DEFAULT
    private val v = MutationValidator(kb, c)
    private val p1 = PlannerFixtures.p1()
    private val inputs: PlanInputs = PlannerFixtures.inputs(p1, gyms = listOf(PlannerFixtures.home, PlannerFixtures.hotel), defaultGymId = PlannerFixtures.HOME_ID,
        states = PlannerFixtures.knownStates(p1, PlannerFixtures.home))
    private val meso: Mesocycle = Planner(kb, c).plan(inputs, PlannerFixtures.MONDAY).mesocycle
    private val today = TodayContext(PlannerFixtures.MONDAY, PlannerFixtures.HOME_ID)

    private fun check(m: PlanMutation, inp: PlanInputs = inputs, mesocycle: Mesocycle? = meso, t: TodayContext? = today, n: NutritionTargets? = null) = v.validate(m, inp, mesocycle, t, n)
    private fun errors(m: PlanMutation, inp: PlanInputs = inputs, mesocycle: Mesocycle? = meso, t: TodayContext? = today, n: NutritionTargets? = null): List<String> =
        (check(m, inp, mesocycle, t, n) as? ValidationResult.Invalid)?.errors ?: emptyList()
    private fun warnings(m: PlanMutation, inp: PlanInputs = inputs, mesocycle: Mesocycle? = meso, t: TodayContext? = today, n: NutritionTargets? = null): List<String> =
        (check(m, inp, mesocycle, t, n) as ValidationResult.Valid).warnings
    private fun planned(): String = meso.weeks.first().sessions.first().slots.first().exerciseId

    @Test
    fun swapExerciseRow() {
        val from = planned()
        assertTrue(errors(PlanMutation.SwapExercise("nope", "dumbbell_bench_press", null, MutationScope.PERMANENT, "x")).isNotEmpty())
        assertTrue(errors(PlanMutation.SwapExercise(from, "machine_chest_press", null, MutationScope.PERMANENT, "x")).single().contains("not available"))
        assertTrue(errors(PlanMutation.SwapExercise("cable_fly", "dumbbell_bench_press", null, MutationScope.PERMANENT, "x")).single().contains("not in the plan"))
        // Bar-over-face lift without safeties or a spotter: "needs safeties or a spotter".
        val noSafeties = PlannerFixtures.home.copy(items = PlannerFixtures.home.items.map { if (it.equipmentId == "power_rack") GymItem("power_rack", mapOf("has_safeties" to JsonPrimitive(false))) else it })
        val inp2 = inputs.copy(gyms = listOf(noSafeties))
        assertTrue(errors(PlanMutation.SwapExercise(from, "incline_barbell_bench_press", null, MutationScope.PERMANENT, "x"), inp2).single().contains("safeties or a spotter"))
        // Limitation pain > 3 conflict.
        val knee = inputs.copy(profile = p1.copy(limitations = listOf(PlannerFixtures.lim(LimitationRegion.KNEE, 5))))
        assertTrue(errors(PlanMutation.SwapExercise(from, "goblet_squat", null, MutationScope.PERMANENT, "x"), knee).any { it.contains("limitation") })
        // Warnings: pattern family change; SP target without rack_or_spotter (good morning would be advanced — use back squat on a rack without safeties).
        assertTrue(warnings(PlanMutation.SwapExercise(from, "goblet_squat", null, MutationScope.PERMANENT, "x")).any { it.startsWith("Pattern change") })
        val squatNoSafeties = inputs.copy(gyms = listOf(noSafeties))
        assertTrue(warnings(PlanMutation.SwapExercise(from, "back_squat", null, MutationScope.PERMANENT, "x"), squatNoSafeties).any { it.contains("RIR floor 2") })
        val adv = warnings(PlanMutation.SwapExercise(from, "front_squat", null, MutationScope.PERMANENT, "x"))
        assertTrue(adv.any { it.contains("advanced") })
        // TODAY scope resolves against today's gym.
        assertTrue(errors(PlanMutation.SwapExercise(from, "barbell_row", null, MutationScope.TODAY, "x"), t = TodayContext(PlannerFixtures.MONDAY, PlannerFixtures.HOTEL_ID)).isNotEmpty())
        assertTrue(check(PlanMutation.SwapExercise(from, "dumbbell_bench_press", null, MutationScope.TODAY, "x"), t = TodayContext(PlannerFixtures.MONDAY, PlannerFixtures.HOTEL_ID)) is ValidationResult.Valid)
    }

    @Test
    fun changeVolumeRow() {
        assertTrue(errors(PlanMutation.ChangeVolume(Muscle.CHEST, 7, MutationScope.PERMANENT, "x")).isNotEmpty())
        assertTrue(errors(PlanMutation.ChangeVolume(Muscle.TRICEPS, -6, MutationScope.PERMANENT, "x")).any { it.contains("VOL-01") })
        assertTrue(warnings(PlanMutation.ChangeVolume(Muscle.CHEST, 6, MutationScope.PERMANENT, "x")).isEmpty())
        assertTrue(warnings(PlanMutation.ChangeVolume(Muscle.CHEST, 6, MutationScope.PERMANENT, "x"), mesocycle = meso.copy(weeks = meso.weeks.map { it.copy(targets = it.targets + (Muscle.CHEST to 16.0)) })).any { it.contains("VOL-04") })
        val oneSession = meso.copy(weeks = meso.weeks.map { w -> w.copy(targets = w.targets + (Muscle.CHEST to 18.0), sessions = w.sessions.take(1)) })
        assertTrue(warnings(PlanMutation.ChangeVolume(Muscle.CHEST, 5, MutationScope.PERMANENT, "x"), mesocycle = oneSession).any { it.contains("VOL-06") })
    }

    @Test
    fun changeRepRangeRow() {
        val ex = planned()
        assertTrue(errors(PlanMutation.ChangeRepRange(ex, 10, 8, MutationScope.PERMANENT, "x")).isNotEmpty())
        assertTrue(errors(PlanMutation.ChangeRepRange(ex, 0, 8, MutationScope.PERMANENT, "x")).isNotEmpty())
        assertTrue(errors(PlanMutation.ChangeRepRange(ex, 20, 31, MutationScope.PERMANENT, "x")).isNotEmpty())
        assertTrue(errors(PlanMutation.ChangeRepRange("cable_fly", 8, 12, MutationScope.PERMANENT, "x")).any { it.contains("not in the plan") })
        assertTrue(warnings(PlanMutation.ChangeRepRange("barbell_bench_press", 12, 15, MutationScope.PERMANENT, "x")).any { it.contains("LOAD-03") })
        assertTrue(warnings(PlanMutation.ChangeRepRange("barbell_bench_press", 10, 15, MutationScope.PERMANENT, "x")).any { it.contains("RIR-05") })
        assertTrue(warnings(PlanMutation.ChangeRepRange("barbell_bench_press", 6, 10, MutationScope.PERMANENT, "x")).isEmpty())
    }

    @Test
    fun addAndRemoveCardioRows() {
        assertTrue(errors(PlanMutation.AddCardio(8, 30, null, CardioKind.ZONE2, "x")).isNotEmpty())
        assertTrue(errors(PlanMutation.AddCardio(3, 5, null, CardioKind.ZONE2, "x")).isNotEmpty())
        assertTrue(errors(PlanMutation.AddCardio(3, 95, null, CardioKind.ZONE2, "x")).isNotEmpty())
        assertTrue(errors(PlanMutation.AddCardio(3, 24, null, CardioKind.HIIT_4X4, "x")).any { it.contains("CAR-05") })
        val gated = inputs.copy(profile = p1.copy(medicalFlags = setOf(MedicalFlag.HEART_DISEASE)))
        assertTrue(errors(PlanMutation.AddCardio(3, 24, null, CardioKind.HIIT_10X1, "x"), gated).any { it.contains("RED-03") })
        // With ≥ 4 weeks of zone-2 base (two logged cardio sessions a week), intervals the day before a heavy leg day only warn (CAR-06).
        val base = (0 until 4).flatMap { wk -> (0 until 2).map { k ->
            val d = PlannerFixtures.MONDAY.minusWeeks((wk + 1).toLong()).plusDays((k * 2).toLong())
            com.gymcoach.core.model.SetLog("c$wk$k", "cs$wk$k", null, "stationary_bike", 1, com.gymcoach.core.model.SetKind.WORKING, seconds = 1800, loggedAt = d.atTime(7, 0).toInstant(java.time.ZoneOffset.UTC))
        } }
        val withBase = inputs.copy(recentSets = base)
        val legDay = meso.weeks.first().sessions.first { s -> s.slots.any { it.heavy && (it.pattern.name == "SQUAT" || it.pattern.name == "HINGE") } }.weekday
        val before = if (legDay == 1) 7 else legDay - 1
        assertTrue(warnings(PlanMutation.AddCardio(before, 24, null, CardioKind.HIIT_4X4, "x"), withBase).any { it.contains("CAR-06") })
        assertTrue(errors(PlanMutation.AddCardio(before, 24, null, CardioKind.HIIT_4X4, "x")).any { it.contains("CAR-05") })
        val busy = meso.copy(weeks = meso.weeks.map { w -> w.copy(cardio = (1..3).map { d -> w.cardio.first().copy(weekday = d + 3, minutes = 40) }) })
        assertTrue(warnings(PlanMutation.AddCardio(1, 30, null, CardioKind.ZONE2, "x"), mesocycle = busy).any { it.contains("CAR-06") })
        val cardioDay = meso.weeks.first().cardio.first().weekday
        assertTrue(check(PlanMutation.RemoveCardio(cardioDay, "x")) is ValidationResult.Valid)
        val noCardio = (1..7).first { d -> meso.weeks.first().cardio.none { it.weekday == d } }
        assertTrue(errors(PlanMutation.RemoveCardio(noCardio, "x")).isNotEmpty())
    }

    @Test
    fun goalAndScheduleRows() {
        assertTrue(warnings(PlanMutation.ChangeGoal(com.gymcoach.core.model.Goal.STRENGTH, true, "x"), mesocycle = null).single().contains("next week"))
        assertTrue(errors(PlanMutation.ChangeSchedule(daysPerWeek = 7, rationale = "x")).isNotEmpty())
        assertTrue(errors(PlanMutation.ChangeSchedule(sessionMinutes = 15, rationale = "x")).isNotEmpty())
        assertTrue(errors(PlanMutation.ChangeSchedule(daysPerWeek = 3, preferredWeekdays = listOf(1, 2), rationale = "x")).isNotEmpty())
        assertTrue(warnings(PlanMutation.ChangeSchedule(daysPerWeek = 3, preferredWeekdays = listOf(1, 3, 5), rationale = "x")).any { it.contains("mid-mesocycle") })
        // Two full-body days back to back: heavy squat and heavy hinge (both legs) on consecutive days in either order.
        assertTrue(warnings(PlanMutation.ChangeSchedule(daysPerWeek = 2, preferredWeekdays = listOf(1, 2), rationale = "x")).any { it.contains("SPLIT-02") })
        assertTrue(check(PlanMutation.ChangeSchedule(daysPerWeek = 3, rationale = "x"), mesocycle = null) is ValidationResult.Valid)
    }

    @Test
    fun checkinQuestionRow() {
        assertTrue(errors(PlanMutation.SetCheckinQuestion("", CheckinAnswerType.TEXT, CheckinSchedule.WEEKLY, rationale = "x")).isNotEmpty())
        assertTrue(errors(PlanMutation.SetCheckinQuestion("x".repeat(201), CheckinAnswerType.TEXT, CheckinSchedule.WEEKLY, rationale = "x")).isNotEmpty())
        assertTrue(errors(PlanMutation.SetCheckinQuestion("How was the hike?", CheckinAnswerType.TEXT, CheckinSchedule.ONCE, rationale = "x")).isNotEmpty())
        assertTrue(errors(PlanMutation.SetCheckinQuestion("How was the hike?", CheckinAnswerType.TEXT, CheckinSchedule.ONCE, onDate = PlannerFixtures.MONDAY.minusDays(1), rationale = "x")).isNotEmpty())
        val five = (1..5).map { PlanOverride("q$it", PlannerFixtures.NOW, OverrideSource.CHAT, null, PlanMutation.SetCheckinQuestion("q$it", CheckinAnswerType.YESNO, CheckinSchedule.WEEKLY, rationale = "x")) }
        assertTrue(errors(PlanMutation.SetCheckinQuestion("sixth?", CheckinAnswerType.YESNO, CheckinSchedule.WEEKLY, rationale = "x"), inputs.copy(overrides = five)).isNotEmpty())
        assertTrue(check(PlanMutation.SetCheckinQuestion("Knee ok?", CheckinAnswerType.YESNO, CheckinSchedule.WEEKLY, rationale = "x")) is ValidationResult.Valid)
    }

    @Test
    fun excludeAndPinRows() {
        assertTrue(errors(PlanMutation.ExcludeExercise("nope", null, "x")).isNotEmpty())
        // A barbell-only gym: nothing else can take the barbell row's PRIMARY horizontal-pull slot.
        val bbOnly = PlannerFixtures.home.copy(id = "bb", items = listOf(GymItem("barbell"), GymItem("plates")))
        val rowSlot = PlannerFixtures.slot("s2", com.gymcoach.core.model.SlotRole.PRIMARY, "barbell_row", 3, gym = bbOnly)
        val bbMeso = meso.copy(gymId = "bb", weeks = meso.weeks.map { w -> w.copy(sessions = listOf(PlannerFixtures.session("x:w1:d1:UPPER_A", "UPPER_A", 1, listOf(rowSlot)))) })
        assertTrue(errors(PlanMutation.ExcludeExercise("barbell_row", null, "x"), inputs.copy(gyms = listOf(bbOnly), defaultGymId = "bb"), bbMeso).single().contains("no available substitute"))
        assertTrue(check(PlanMutation.ExcludeExercise("dumbbell_fly", null, "x")) is ValidationResult.Valid)
        assertTrue(errors(PlanMutation.PinExercise("nope", null, "x")).isNotEmpty())
        assertTrue(errors(PlanMutation.PinExercise("leg_extension", null, "x")).any { it.contains("not available") })
        assertTrue(errors(PlanMutation.PinExercise("farmers_carry", "UPPER_A", "x")).any { it.contains("No slot") })
        assertTrue(warnings(PlanMutation.PinExercise("front_squat", "LOWER_A", "x")).any { it.contains("advanced") })
        assertTrue(check(PlanMutation.PinExercise("dumbbell_bench_press", "UPPER_A", "x")) is ValidationResult.Valid)
    }

    @Test
    fun adjustWorkingLoadPriorityMusclesAndDeloadRows() {
        assertTrue(errors(PlanMutation.AdjustWorkingLoad("cable_fly", null, 20.0, "x")).isNotEmpty())
        val unknown = inputs.copy(progressionStates = inputs.progressionStates.map { if (it.key.exerciseId == "barbell_row") it.copy(status = ProgressionStatus.UNKNOWN) else it })
        assertTrue(errors(PlanMutation.AdjustWorkingLoad("barbell_row", null, 72.5, "x"), unknown).isNotEmpty())
        assertTrue(errors(PlanMutation.AdjustWorkingLoad("barbell_bench_press", null, 90.0, "x")).isNotEmpty())   // > +10 %
        assertTrue(errors(PlanMutation.AdjustWorkingLoad("barbell_bench_press", null, 60.0, "x")).isNotEmpty())   // < −20 %
        assertTrue(check(PlanMutation.AdjustWorkingLoad("barbell_bench_press", null, 85.0, "x")) is ValidationResult.Valid)
        assertTrue(errors(PlanMutation.SetPriorityMuscles(listOf(Muscle.CHEST, Muscle.BICEPS, Muscle.CALVES, Muscle.ABS), "x"), mesocycle = null).isNotEmpty())
        assertTrue(check(PlanMutation.SetPriorityMuscles(listOf(Muscle.CHEST), "x"), mesocycle = null) is ValidationResult.Valid)
        val recent = inputs.copy(lastDeloadWeekStart = PlannerFixtures.MONDAY.minusDays(7))
        assertTrue(errors(PlanMutation.ScheduleDeload(true, "x"), recent).single().contains("log a week first"))
        assertTrue(check(PlanMutation.ScheduleDeload(true, "x"), inputs.copy(lastDeloadWeekStart = PlannerFixtures.MONDAY.minusDays(28))) is ValidationResult.Valid)
        val travel = meso.copy(weeks = meso.weeks.map { if (it.startDate == PlannerFixtures.MONDAY) it.copy(mode = WeekMode.TRAVEL_MAINTENANCE) else it })
        assertTrue(errors(PlanMutation.ScheduleDeload(true, "x"), mesocycle = travel).any { it.contains("travel") })
        assertTrue(RuleIds.DLD_03 in RuleIds.ALL)
    }

    @Test
    fun limitationAndTravelRows() {
        val lim = PlannerFixtures.lim(LimitationRegion.SHOULDER, 2)
        assertTrue(errors(PlanMutation.SetLimitation(lim.copy(pain0to10 = 11), "x"), mesocycle = null).isNotEmpty())
        assertTrue(errors(PlanMutation.SetLimitation(lim.copy(pain0to10 = 7), "x"), mesocycle = null).single().contains("RED-01"))
        val flagged = ReadinessCheckin("r", PlannerFixtures.MONDAY, redFlagReported = true, score = 50, band = ReadinessBand.AMBER, explanation = Explanations.of(RuleIds.RDY_01, "x"), loggedAt = PlannerFixtures.NOW)
        assertTrue(errors(PlanMutation.SetLimitation(lim, "x"), mesocycle = null, t = today.copy(readiness = flagged)).isNotEmpty())
        assertTrue(check(PlanMutation.SetLimitation(lim, "x"), mesocycle = null) is ValidationResult.Valid)
        val d = PlannerFixtures.MONDAY
        assertTrue(errors(PlanMutation.SetTravelDays(d.plusDays(5), d.plusDays(2), null, "x")).isNotEmpty())
        assertTrue(errors(PlanMutation.SetTravelDays(d.plusDays(1), d.plusDays(30), null, "x")).isNotEmpty())
        assertTrue(errors(PlanMutation.SetTravelDays(d.minusDays(3), d.plusDays(2), null, "x")).isNotEmpty())
        assertTrue(check(PlanMutation.SetTravelDays(d.plusDays(1), d.plusDays(4), PlannerFixtures.HOTEL_ID, "x"), mesocycle = null) is ValidationResult.Valid)
    }

    @Test
    fun nutritionTargetsRowWithTheNut05Nut06KcalBounds() {
        val targets = NutritionTargets(kcal = 3035, proteinG = 151, carbsG = 419, fatG = 84, fibreG = 42, waterL = 3.7, energyDeltaKcal = 276,
            explanation = Explanations.of(RuleIds.NUT_04, "G4"))
        // TDEE = 3035 − 276 = 2759; REE (P1, 84 kg, 180 cm, 38 y) = 1780 → floor max(1780, 0.75 × 2759 = 2069); ceiling 1.3 × 2759 = 3587.
        assertTrue(errors(PlanMutation.UpdateNutritionTargets(proteinGPerKg = 0.8, rationale = "x"), mesocycle = null, n = targets).isNotEmpty())
        assertTrue(errors(PlanMutation.UpdateNutritionTargets(proteinGPerKg = 3.6, rationale = "x"), mesocycle = null, n = targets).isNotEmpty())
        assertTrue(errors(PlanMutation.UpdateNutritionTargets(kcalTarget = 2000, rationale = "x"), mesocycle = null, n = targets).single().contains("NUT-05/06"))
        assertTrue(errors(PlanMutation.UpdateNutritionTargets(kcalTarget = 3600, rationale = "x"), mesocycle = null, n = targets).isNotEmpty())
        assertTrue(errors(PlanMutation.UpdateNutritionTargets(kcalTarget = 2900, rationale = "x"), mesocycle = null, n = null).single().contains("weigh-in"))
        assertTrue(warnings(PlanMutation.UpdateNutritionTargets(kcalTarget = 2735, rationale = "x"), mesocycle = null, n = targets).single().contains("NUT-16"))
        assertTrue(warnings(PlanMutation.UpdateNutritionTargets(kcalTarget = 2900, rationale = "x"), mesocycle = null, n = targets).isEmpty())
    }

    @Test
    fun withoutAPlanOnlyTheProfileLevelMutationsCanBeValid() {
        assertTrue(errors(PlanMutation.ScheduleDeload(true, "x"), mesocycle = null).single().contains("No plan"))
        assertTrue(errors(PlanMutation.PinExercise("dumbbell_bench_press", null, "x"), mesocycle = null).isNotEmpty())
        assertEquals(ValidationResult.Valid(emptyList()), check(PlanMutation.SetPriorityMuscles(emptyList(), "x"), mesocycle = null))
    }
}
