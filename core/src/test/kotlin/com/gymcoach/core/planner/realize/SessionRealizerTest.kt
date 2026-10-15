package com.gymcoach.core.planner.realize

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.ExerciseBlock
import com.gymcoach.core.model.GymItem
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MutationScope
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.model.ReadinessCheckin
import com.gymcoach.core.model.SessionMode
import com.gymcoach.core.model.SessionPlan
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.planner.PlanValidator
import com.gymcoach.core.planner.Planner
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.planner.PlannerFixtures
import com.gymcoach.core.volume.VolumeAccountant
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionRealizerTest {
    private val kb = PlannerFixtures.kb
    private val c = PlannerConstants.DEFAULT
    private val planner = Planner(kb, c)
    private val p1 = PlannerFixtures.p1()
    private val states = PlannerFixtures.knownStates(p1, PlannerFixtures.home)
    private val week = PlannerFixtures.e1Week()

    private fun inputs(overrides: List<PlanOverride> = emptyList(), gyms: List<com.gymcoach.core.model.GymSet> = listOf(PlannerFixtures.home, PlannerFixtures.hotel)): PlanInputs =
        PlannerFixtures.inputs(p1, gyms = gyms, defaultGymId = PlannerFixtures.HOME_ID, states = states).copy(overrides = overrides)

    private fun realize(sessionIndex: Int, today: TodayContext, w: MesoWeek = week, inputs: PlanInputs = inputs()): SessionPlan =
        planner.realize(w.sessions[sessionIndex], w, inputs, today, states)

    private fun day(weekday: Int) = PlannerFixtures.MONDAY.plusDays((weekday - 1).toLong())
    private fun working(b: ExerciseBlock) = b.sets.filter { it.kind != SetKind.RAMP }
    private fun block(p: SessionPlan, exerciseId: String) = p.blocks.first { it.exerciseId == exerciseId }

    private fun readiness(band: ReadinessBand, score: Int, sleep: Double = 7.5): ReadinessCheckin =
        ReadinessCheckin("r-$band", day(1), sleepHours = sleep, score = score, band = band, explanation = Explanations.of(RuleIds.RDY_01, "fixture"), loggedAt = PlannerFixtures.NOW)

    @Test
    fun e1UpperAWeek1BenchAndRowPrescriptionsWithWu02Ramps() {
        val p = realize(0, TodayContext(day(1), PlannerFixtures.HOME_ID))
        assertEquals(SessionMode.AS_PLANNED, p.mode)
        assertEquals("meso:w1:d1:UPPER_A@2026-09-07", p.id)
        val bench = block(p, "barbell_bench_press")
        val w = working(bench)
        assertEquals(4, w.size)
        assertTrue(w.all { it.reps == PlannerFixtures.band(5, 10) && it.loadKg == 80.0 && it.rir == 3 && it.restSeconds == 180 })
        val ramps = bench.sets.filter { it.kind == SetKind.RAMP }
        assertEquals(listOf(32.5, 45.0, 55.0, 65.0), ramps.map { it.loadKg })
        assertEquals(listOf(8, 5, 3, 2), ramps.map { it.reps?.lo })
        assertTrue(ramps.all { it.restSeconds == c.wu.wu02RampRestSec && it.rir == null })
        assertEquals("heavy", bench.explanation.inputs["zone"])
        val row = block(p, "barbell_row")
        assertEquals(3, working(row).size)
        assertTrue(working(row).all { it.reps == PlannerFixtures.band(8, 12) && it.loadKg == 70.0 && it.rir == 3 && it.restSeconds == 120 })
        val rowRamp = row.sets.filter { it.kind == SetKind.RAMP }
        assertEquals(listOf(45.0), rowRamp.map { it.loadKg })   // 0.65 × 70 = 45.5 → 45 kg × 5 (pattern already warm)
        assertEquals(5, rowRamp.single().reps?.lo)
        assertEquals(10, p.warmup.totalMinutes)
        assertEquals(60.0, p.estimatedMinutes)                  // 50.0 lifting + 10 warm-up (§4.17)
        assertTrue(PlanValidator(kb, c).validateSession(p, planner.normalize(inputs(), day(1)), week).isEmpty())
    }

    @Test
    fun e2HotelDay45MinutesDumbbellsOnly() {
        val p = realize(3, TodayContext(day(5), PlannerFixtures.HOTEL_ID))
        assertEquals(PlannerFixtures.HOTEL_ID, p.gymId)
        // Substitutions (§4.12): deadlift → DB RDL 0.725; hip thrust → glute bridge; Pallof → side plank.
        val dl = block(p, "dumbbell_romanian_deadlift")
        assertEquals("conventional_deadlift", dl.substitutedFor)
        assertEquals("0.725", p.modifiers.first { it.inputs["from"] == "conventional_deadlift" }.inputs["score"])
        assertEquals("hip_thrust", block(p, "glute_bridge").substitutedFor)
        assertEquals("pallof_press", block(p, "side_plank").substitutedFor)
        // Time budget: 44.7 > 40 → L2 drops the slider leg curl; L1 is not logged because WU-05 already applies.
        assertEquals(listOf(RuleIds.TIME_L2), p.modifiers.flatMap { it.ruleIds }.filter { it.startsWith("TIME-L") })
        assertTrue(p.blocks.none { it.exerciseId == "slider_leg_curl" })
        assertEquals(5, p.warmup.totalMinutes)
        assertEquals(0, p.warmup.raiseMinutes)
        assertEquals(44.5, p.estimatedMinutes)
        assertEquals(16, p.blocks.sumOf { working(it).size })
        // RIR column: DB RDL fixed endurance RIR 1 at 2 × 20 kg, 15–30 reps; goblet 20 kg kept at max (24 kg state) 8–14 @ 2; lunge 8–14 @ 2.
        assertTrue(working(dl).all { it.rir == 1 && it.loadKg == 40.0 && it.reps == PlannerFixtures.band(15, 30) })
        val goblet = block(p, "goblet_squat")
        assertTrue(working(goblet).all { it.rir == 2 && it.loadKg == 20.0 && it.reps == PlannerFixtures.band(8, 14) })
        assertTrue(goblet.warnings.any { RuleIds.R4_2_6 in it.ruleIds })
        val lunge = block(p, "reverse_lunge")
        assertTrue(working(lunge).all { it.rir == 2 && it.loadKg == 20.0 && it.reps == PlannerFixtures.band(8, 14) })
        // Ramp for block 1: LIGHT zone → one ramp set at 60 % (2 × 12 kg × 8).
        val ramp = dl.sets.filter { it.kind == SetKind.RAMP }
        assertEquals(listOf(24.0), ramp.map { it.loadKg })
        assertEquals("2×12 kg", ramp.single().loadLabel)
        // Realized credits (§4.17 E2).
        assertEquals(5.0, p.volume.fractional[Muscle.QUADS]); assertEquals(6.0, p.volume.fractional[Muscle.GLUTES])
        assertEquals(4.0, p.volume.fractional[Muscle.HAMSTRINGS]); assertEquals(1.5, p.volume.fractional[Muscle.LOWER_BACK])
        assertEquals(2.0, p.volume.fractional[Muscle.ADDUCTORS]); assertEquals(0.75, p.volume.fractional[Muscle.ABS])
        assertEquals(3.0, p.volume.fractional[Muscle.CALVES]); assertEquals(3.0, p.volume.fractional[Muscle.OBLIQUES])
        assertEquals(0.75, p.volume.fractional[Muscle.GLUTE_MED])
        // Carryover: quads −1.0, hamstrings −0.5, abs −4.25 plus the pre-existing side_delts −1.0 and calves −2.0.
        assertEquals(mapOf(Muscle.SIDE_DELTS to -1.0, Muscle.ABS to -4.25, Muscle.QUADS to -1.0, Muscle.HAMSTRINGS to -0.5, Muscle.CALVES to -2.0), p.ledgerCarryover)
        assertEquals(VolumeAccountant.ofBlocks(p.blocks, kb), p.volume)
    }

    @Test
    fun g2AmberOnE1UpperA() {
        val p = realize(0, TodayContext(day(1), PlannerFixtures.HOME_ID, readiness = readiness(ReadinessBand.AMBER, 58)))
        assertEquals(SessionMode.AMBER, p.mode)
        assertEquals(listOf(4, 3, 2, 2, 3, 1, 1, 1), p.blocks.map { working(it).size })
        assertEquals(17, p.blocks.sumOf { working(it).size })
        assertTrue(working(block(p, "barbell_bench_press")).all { it.rir == 4 })      // week 1: 3 → 4, at the cap but not capped
        assertTrue(p.modifiers.none { RuleIds.RIR_CAP in it.ruleIds })
        val mid = realize(0, TodayContext(day(1), PlannerFixtures.HOME_ID, readiness = readiness(ReadinessBand.AMBER, 58)), week.copy(index = 3, rirOffset = 0))
        assertTrue(working(block(mid, "barbell_bench_press")).all { it.rir == 3 })   // mid-mesocycle 2 → 3
    }

    @Test
    fun g2RedInWeek1IsCappedAtFourWithTheRirCapExplanation() {
        val p = realize(0, TodayContext(day(1), PlannerFixtures.HOME_ID, readiness = readiness(ReadinessBand.RED, 32)))
        assertEquals(SessionMode.RED, p.mode)
        assertTrue(p.alternativeOffered)
        assertTrue(p.modifiers.any { RuleIds.RIR_CAP in it.ruleIds })
        // Heavy barbell bench → first available moderate dumbbell/machine substitute.
        val sub = p.blocks.first { it.substitutedFor == "barbell_bench_press" }
        assertEquals("dumbbell_bench_press", sub.exerciseId)
        assertEquals(3, working(sub).size)                                            // ceil(4 × 0.6)
        assertTrue(p.blocks.all { b -> working(b).all { (it.rir ?: 0) <= c.rir.rirCapMax } })
        val mid = realize(0, TodayContext(day(1), PlannerFixtures.HOME_ID, readiness = readiness(ReadinessBand.RED, 32)), week.copy(index = 3, rirOffset = 0))
        assertTrue(working(block(mid, "barbell_row")).all { it.rir == 4 })           // 2 + 2, no cap
        assertTrue(mid.modifiers.none { RuleIds.RIR_CAP in it.ruleIds })
    }

    @Test
    fun deloadWeekHalvesSetsAndTakes85PercentOnCompounds_DLD02() {
        val deload = week.copy(index = 7, mode = WeekMode.DELOAD, rirOffset = 0)
        val p = realize(0, TodayContext(day(1), PlannerFixtures.HOME_ID), deload)
        assertEquals(SessionMode.DELOAD, p.mode)
        val bench = working(block(p, "barbell_bench_press"))
        assertEquals(2, bench.size)                                                  // ceil(4 × 0.5)
        assertEquals(67.5, bench.first().loadKg)                                     // 0.85 × 80 = 68 → 67.5
        assertTrue(p.blocks.all { b -> working(b).all { it.rir == c.rir.rir04DeloadRir } })
    }

    @Test
    fun reentryTakes95PercentAndAddsNoSecondRirOffset_DLD04() {
        val reentry = week.copy(mode = WeekMode.REENTRY)
        val p = realize(0, TodayContext(day(1), PlannerFixtures.HOME_ID), reentry)
        val bench = working(block(p, "barbell_bench_press"))
        assertEquals(75.0, bench.first().loadKg)                                     // 0.95 × 80 = 76 → 75
        assertTrue(bench.all { it.rir == 3 })                                        // profile 2 + week-1 offset 1, nothing more
        assertEquals(3, bench.size)                                                  // lower-bound sets
        assertTrue(p.modifiers.any { RuleIds.DLD_04 in it.ruleIds })
    }

    @Test
    fun todayScopeSwapIsHonouredTodayAndIgnoredTomorrow() {
        val swap = PlanOverride("o1", PlannerFixtures.NOW, OverrideSource.CHAT, "tool-1",
            PlanMutation.SwapExercise("barbell_row", "chest_supported_dumbbell_row", null, MutationScope.TODAY, "elbow"), expiresOn = day(1))
        val today = realize(0, TodayContext(day(1), PlannerFixtures.HOME_ID), inputs = inputs(listOf(swap)))
        assertEquals("barbell_row", block(today, "chest_supported_dumbbell_row").substitutedFor)
        assertTrue(today.modifiers.any { RuleIds.OVERRIDE in it.ruleIds && it.inputs["source"] == "chat" })
        val tomorrowWeek = week.copy(sessions = week.sessions.map { it.copy(weekday = 2) })
        val tomorrow = planner.realize(tomorrowWeek.sessions[0], tomorrowWeek, inputs(listOf(swap)), TodayContext(day(2), PlannerFixtures.HOME_ID), states)
        assertTrue(tomorrow.blocks.any { it.exerciseId == "barbell_row" })
    }

    @Test
    fun spLiftOnARackWithoutSafetiesGetsTheRirFloorAndBenchIsSubstituted_R455() {
        val noSafeties = PlannerFixtures.home.copy(items = PlannerFixtures.home.items.map { if (it.equipmentId == "power_rack") GymItem("power_rack", mapOf("has_safeties" to JsonPrimitive(false), "has_pullup_bar" to JsonPrimitive(true))) else it })
        val inp = inputs(gyms = listOf(noSafeties))
        val lower = planner.realize(week.sessions[1], week, inp, TodayContext(day(2), PlannerFixtures.HOME_ID), states)
        val squat = block(lower, "back_squat")
        assertTrue(working(squat).all { (it.rir ?: 0) >= c.rir.rir03SpWithoutRackFloor })
        assertTrue(squat.warnings.any { RuleIds.R4_5_5 in it.ruleIds })
        val upper = planner.realize(week.sessions[0], week, inp, TodayContext(day(1), PlannerFixtures.HOME_ID), states)
        assertTrue(upper.blocks.none { it.exerciseId == "barbell_bench_press" })
        assertEquals("barbell_bench_press", upper.blocks.first { it.slotId == "s1" }.substitutedFor)
        // A spotter toggled for today makes the bench available again.
        val spotted = planner.realize(week.sessions[0], week, inp, TodayContext(day(1), PlannerFixtures.HOME_ID, spotter = true), states)
        assertTrue(spotted.blocks.any { it.exerciseId == "barbell_bench_press" })
    }

    @Test
    fun restDayAndIllness() {
        val rest = planner.realize(null, week, inputs(), TodayContext(day(3), PlannerFixtures.HOME_ID), states)
        assertTrue(rest.blocks.isEmpty())
        assertNull(rest.plannedSessionId)
        assertEquals("free@" + day(3), rest.id)
        assertEquals(SessionMode.AS_PLANNED, rest.mode)
        val ill = realize(0, TodayContext(day(1), PlannerFixtures.HOME_ID, illnessSymptoms = true))
        assertTrue(ill.blocks.isEmpty())
        assertEquals(SessionMode.ALTERNATIVE_Z2_MOBILITY, ill.mode)
        assertTrue(ill.modifiers.single().ruleIds.contains(RuleIds.LAY_02))
    }

    @Test
    fun realizationNeverAltersTheInputs() {
        val inp = inputs()
        val before = inp.copy()
        realize(3, TodayContext(day(5), PlannerFixtures.HOTEL_ID), inputs = inp)
        assertEquals(before, inp)
        assertFalse(states.isEmpty())
        assertNotNull(Explanations.of(listOf(RuleIds.SEL), "x", emptyMap(), Confidence.WEAK))
    }
}
