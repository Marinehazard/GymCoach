package com.gymcoach.core.planner

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.Band
import com.gymcoach.core.model.CardioKind
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.PlannedSession
import com.gymcoach.core.model.SessionPlan
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SplitKind
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.VolumeSnapshot
import com.gymcoach.core.time.Weeks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §4.16: one violating plan per invariant 1–18 (invariant 12 is checked as a property: every generated plan round-trips). */
class PlanValidatorTest {
    private val kb = PlannerFixtures.kb
    private val c = PlannerConstants.DEFAULT
    private val planner = Planner(kb, c)
    private val validator = PlanValidator(kb, c)
    private val today = PlannerFixtures.MONDAY
    private val inputs: PlanInputs = PlannerFixtures.inputs(PlannerFixtures.p1(), states = PlannerFixtures.knownStates(PlannerFixtures.p1(), PlannerFixtures.home))
    private val n = planner.normalize(inputs, today)
    private val valid: Mesocycle = planner.plan(inputs, today).mesocycle

    private fun violations(m: Mesocycle, inp: PlanInputs = inputs, norm: Normalized = n, realize: Boolean = false) = validator.validate(m, inp, norm, today, realize)
    private fun has(v: List<String>, inv: Int) = v.any { it.startsWith("INV-$inv:") }
    private fun mapWeek1(m: Mesocycle = valid, f: (MesoWeek) -> MesoWeek): Mesocycle = m.copy(weeks = m.weeks.map { if (it.index == 1) f(it) else it })
    private fun mapSession(i: Int, f: (PlannedSession) -> PlannedSession) = mapWeek1 { w -> w.copy(sessions = w.sessions.mapIndexed { k, s -> if (k == i) f(s) else s }) }
    private fun realized(): SessionPlan {
        val w = valid.weeks.first()
        val s = w.sessions.first()
        return planner.realize(s, w, inputs, TodayContext(Weeks.dateFor(w.startDate, s.weekday), valid.gymId), inputs.progressionStates)
    }

    @Test
    fun theGeneratedPlanIsValid() {
        assertTrue(violations(valid, realize = true).isEmpty(), violations(valid, realize = true).toString())
        assertTrue(planner.validate(valid, inputs).isEmpty())
    }

    @Test
    fun inv1SilentDeficitBelowVol01() {
        val noChest = mapWeek1 { w -> w.copy(sessions = w.sessions.map { s -> s.copy(slots = s.slots.filter { kb.requireExercise(it.exerciseId).weightOf(Muscle.CHEST) == 0.0 }, volumeFractional = s.volumeFractional - Muscle.CHEST) }) }.copy(underTargetReport = emptyList())
        assertTrue(has(violations(noChest), 1))
    }

    @Test fun inv2SessionOverItsMinutes() = assertTrue(has(violations(mapSession(0) { it.copy(estimatedMinutes = 61.0) }), 2))

    @Test
    fun inv3Sess02AndVol06() {
        assertTrue(has(violations(mapSession(0) { s -> s.copy(slots = s.slots.mapIndexed { i, sl -> if (i == 0) sl.copy(sets = 20) else sl }) }), 3))
        assertTrue(has(violations(mapSession(0) { s -> s.copy(volumeFractional = s.volumeFractional + (Muscle.CHEST to 12.5)) }), 3))
    }

    @Test fun inv4UnavailableOrExcludedExercise() {
        assertTrue(has(violations(mapSession(0) { s -> s.copy(slots = s.slots.mapIndexed { i, sl -> if (i == 0) sl.copy(exerciseId = "machine_chest_press") else sl }) }), 4))
        assertTrue(has(violations(valid, norm = n.copy(excludedExerciseIds = n.excludedExerciseIds + valid.weeks[0].sessions[0].slots[0].exerciseId)), 4))
    }

    @Test
    fun inv5RirZeroOnASpotterExerciseAndAboveTheCap() {
        val p = realized()
        val bench = p.blocks.first { kb.requireExercise(it.exerciseId).flags.requiresSpotterOrSafeties }
        val zero = p.copy(blocks = p.blocks.map { if (it === bench) it.copy(sets = it.sets.map { s -> if (s.kind == SetKind.WORKING) s.copy(rir = 0) else s }) else it })
        assertTrue(has(validator.validateSession(zero, n, valid.weeks.first()), 5))
        val five = p.copy(blocks = p.blocks.map { b -> b.copy(sets = b.sets.map { s -> if (s.kind == SetKind.WORKING) s.copy(rir = 5) else s }) })
        assertTrue(has(validator.validateSession(five, n, valid.weeks.first()), 5))
        assertTrue(validator.validateSession(p, n, valid.weeks.first()).isEmpty())
    }

    @Test
    fun inv6Mix02CoverageMissingVerticalPull() {
        val noPull = mapWeek1 { w -> w.copy(sessions = w.sessions.map { s -> s.copy(slots = s.slots.filter { it.pattern != com.gymcoach.core.model.MovementPattern.VERTICAL_PULL && it.pattern != com.gymcoach.core.model.MovementPattern.HORIZONTAL_PULL }) }) }
        assertTrue(has(violations(noPull), 6))
    }

    @Test
    fun inv7HeavyLegsOnConsecutiveDaysWithoutSplit02Note() {
        val moved = mapWeek1 { w -> w.copy(sessions = w.sessions.map { s -> if (s.templateId == "LOWER_B") s.copy(weekday = 3) else s }) }
        assertTrue(has(violations(moved), 7))
        val noted = moved.copy(explanations = moved.explanations + Explanation(listOf(RuleIds.SPLIT_02), "no better order"))
        assertTrue(!has(violations(noted), 7))
    }

    @Test
    fun inv8RampSetsNeverCount() {
        val p = realized()
        val wrong = p.copy(volume = VolumeSnapshot(p.volume.fractional + (Muscle.CHEST to 99.0), p.volume.direct))
        assertTrue(has(validator.validateSession(wrong, n, valid.weeks.first()), 8))
    }

    @Test
    fun inv9HiitTheDayBeforeALowerSession() {
        val lowerDay = valid.weeks[0].sessions.first { it.templateId == "LOWER_A" }.weekday
        val hiit = mapWeek1 { w -> w.copy(cardio = w.cardio + w.cardio.first().copy(weekday = lowerDay - 1, kind = CardioKind.HIIT_4X4)) }
        assertTrue(has(violations(hiit), 9))
    }

    @Test
    fun inv10ExplanationsCiteKnownRuleIds() {
        assertTrue(has(violations(mapSession(0) { s -> s.copy(slots = s.slots.mapIndexed { i, sl -> if (i == 0) sl.copy(explanation = Explanation(emptyList(), "none")) else sl }) }), 10))
        assertTrue(has(violations(valid.copy(explanations = valid.explanations + Explanation(listOf("XYZ-99"), "typo"))), 10))
    }

    @Test
    fun inv11RepRangesInsideTheProfileRange() {
        val p = realized()
        val bad = p.copy(blocks = p.blocks.mapIndexed { i, b -> if (i == 0) b.copy(sets = b.sets.map { s -> if (s.kind == SetKind.WORKING) s.copy(reps = Band(1, 50)) else s }) else b })
        assertTrue(has(validator.validateSession(bad, n, valid.weeks.first()), 11))
    }

    @Test
    fun inv12EveryGeneratedPlanRoundTripsByteIdentically() {
        val json = CoreJson.stored.encodeToString(Mesocycle.serializer(), valid)
        assertEquals(json, CoreJson.stored.encodeToString(Mesocycle.serializer(), CoreJson.stored.decodeFromString(Mesocycle.serializer(), json)))
        assertTrue(!has(violations(valid), 12))
    }

    @Test fun inv13Vol07MinimumSets() = assertTrue(has(violations(mapSession(0) { s -> s.copy(slots = s.slots.mapIndexed { i, sl -> if (i == 1) sl.copy(sets = 1) else sl }) }), 13))

    @Test
    fun inv14DifficultyGate() {
        assertTrue(has(violations(valid, norm = n.copy(beginnerOnly = true)), 14))
        assertTrue(has(violations(mapSession(1) { s -> s.copy(slots = s.slots.map { if (it.slotId == "s1") it.copy(exerciseId = "front_squat") else it }) }), 14))
    }

    @Test fun inv15PatternOutsideTheSlotsList() =
        assertTrue(has(violations(mapSession(0) { s -> s.copy(slots = s.slots.map { if (it.slotId == "s5") it.copy(exerciseId = "dumbbell_curl", pattern = com.gymcoach.core.model.MovementPattern.ELBOW_FLEXION) else it }) }), 15))

    @Test
    fun inv16SupersetGroupsPairExactlyTheLinkedSlots() {
        assertTrue(has(violations(mapSession(0) { s -> s.copy(slots = s.slots.map { if (it.slotId == "s1") it.copy(supersetGroup = "s1+s2") else it }) }), 16))
        assertTrue(has(violations(mapSession(0) { s -> s.copy(slots = s.slots.map { if (it.slotId == "s1" || it.slotId == "s2") it.copy(supersetGroup = "x") else it }) }), 16))
    }

    @Test
    fun inv17StrengthRowBackoffMustHoldTheHeavyExercise() {
        val inp = GoldenCases.build("strength_ul4_backoff")
        val sn = planner.normalize(inp, today)
        val m = planner.plan(inp, today).mesocycle
        assertTrue(!has(validator.validate(m, inp, sn, today, false), 17))
        val broken = m.copy(weeks = m.weeks.map { w -> w.copy(sessions = w.sessions.map { s -> if (s.templateId == "UPPER_B_S") s.copy(slots = s.slots.map { if (it.slotId == "s2") it.copy(exerciseId = "dumbbell_bench_press") else it }) else s }) })
        assertTrue(has(validator.validate(broken, inp, sn, today, false), 17))
    }

    @Test
    fun noviceStrengthThreeDaysFullBody3ValidatesWithTheFrq02Note() {
        val inp = PlannerFixtures.inputs(PlannerFixtures.p2().copy(goal = Goal.STRENGTH), gyms = listOf(PlannerFixtures.base), bodyWeightKg = 78.0)
        val sn = planner.normalize(inp, today)
        assertEquals(TrainingAge.NOVICE, sn.trainingAge)
        val m = planner.plan(inp, today).mesocycle
        assertEquals(SplitKind.FULL_BODY_3, m.split)
        assertTrue(m.explanations.any { RuleIds.FRQ_02 in it.ruleIds })
        assertTrue(validator.validate(m, inp, sn, today, true).isEmpty())
        val withoutNote = m.copy(explanations = m.explanations.filter { RuleIds.FRQ_02 !in it.ruleIds })
        assertTrue(has(validator.validate(withoutNote, inp, sn, today, false), 17))
    }

    @Test
    fun inv18DuplicateSessionIdsInPpl6AndUl6() {
        for (name in listOf("advanced_ppl6")) {
            val inp = GoldenCases.build(name)
            val m = planner.plan(inp, today).mesocycle
            val sn = planner.normalize(inp, today)
            assertTrue(!has(validator.validate(m, inp, sn, today, false), 18))
            val dup = m.copy(weeks = m.weeks.map { w -> w.copy(sessions = w.sessions.map { s -> s.copy(id = "${m.id}:w${w.index}:${s.templateId}") }) })
            assertTrue(has(validator.validate(dup, inp, sn, today, false), 18))
        }
        val ul6 = PlannerFixtures.inputs(PlannerFixtures.p1().copy(goal = Goal.STRENGTH, daysPerWeek = 6), gyms = listOf(PlannerFixtures.base))
        val m6 = planner.plan(ul6, today).mesocycle
        assertEquals(SplitKind.UL_6, m6.split)
        val ids = m6.weeks.flatMap { w -> w.sessions.map { it.id } }
        assertEquals(ids.size, ids.toSet().size)
        // ul_6 repeats UPPER_A_S4 / LOWER_A_S with the same main lift on both days (§4.6 pre-pass).
        val w = m6.weeks.first()
        val benches = w.sessions.filter { it.templateId == "UPPER_A_S4" }.map { s -> s.slots.first { it.slotId == "s1" }.exerciseId }
        assertEquals(2, benches.size)
        assertEquals(1, benches.toSet().size)
    }
}
