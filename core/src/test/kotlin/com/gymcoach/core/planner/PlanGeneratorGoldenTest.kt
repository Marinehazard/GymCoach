package com.gymcoach.core.planner

import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Difficulty
import com.gymcoach.core.model.ExerciseClass
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MuscleTier
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SplitKind
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.time.Weeks
import com.gymcoach.core.volume.VolumeAccountant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §4.17 worked examples on the generated plan. The Stage 0–2 numbers of E1/E3, the budgets, caps, targets, prescriptions and
 * the G5 travel week are reproduced exactly; the Stage 3–5 tables of E1/E3 (which exercise fills which slot, how many sets)
 * are NOT reproducible from the §4.6 formulas as specified (see the WP-03 spec-drift report) — those numbers are pinned
 * instead by the golden plan files (GoldenPlansTest) and, for Stage 6, by SessionRealizerTest on the §4.17 allocation.
 */
class PlanGeneratorGoldenTest {
    private val kb = PlannerFixtures.kb
    private val c = PlannerConstants.DEFAULT
    private val planner = Planner(kb, c)
    private val monday = PlannerFixtures.MONDAY

    private fun week(m: Mesocycle, start: java.time.LocalDate = monday): MesoWeek = m.weeks.first { it.startDate == start }
    private fun weekVolume(w: MesoWeek): Map<Muscle, Double> {
        val arr = DoubleArray(Muscle.entries.size)
        for (s in w.sessions) for ((m, v) in s.volumeFractional) arr[m.ordinal] += v
        return Muscle.entries.associateWith { VolumeAccountant.round2(arr[it.ordinal]) }
    }

    @Test
    fun e1HomeUpperLower4Stages0To2AndBudgets() {
        val inputs = PlannerFixtures.inputs(PlannerFixtures.p1(), states = PlannerFixtures.knownStates(PlannerFixtures.p1(), PlannerFixtures.home))
        val out = planner.plan(inputs, monday)
        val m = out.mesocycle
        assertEquals(TrainingAge.INTERMEDIATE, m.trainingAge)
        assertEquals(SplitKind.UPPER_LOWER_4, m.split)
        assertEquals(7, m.weeks.size)
        assertEquals(7, m.deloadWeekIndex)
        assertEquals(WeekMode.DELOAD, m.weeks.last().mode)
        assertEquals(1, out.currentWeekIndex)
        val w1 = week(m)
        assertEquals(1, w1.rirOffset)
        assertEquals(-1, m.weeks[5].rirOffset)                                  // last accumulation week
        assertEquals(listOf(1, 2, 4, 5), w1.sessions.map { it.weekday })
        assertEquals(listOf("UPPER_A", "LOWER_A", "UPPER_B", "LOWER_B"), w1.sessions.map { it.templateId })
        val tiers = planner.normalize(inputs, monday).tiers
        for ((mu, t) in w1.targets) assertEquals(if (tiers[mu] == MuscleTier.TIER_1) 12.0 else 7.0, t, mu.name)
        // Budget 60 − 10 = 50 lifting minutes; SESS-02 cap 25; every session within both.
        for (s in w1.sessions) {
            assertTrue(s.estimatedMinutes <= 60.0, "${s.templateId} ${s.estimatedMinutes}")
            assertTrue(s.slots.sumOf { it.sets } <= c.sess.sess02CapDefault)
            for (slot in s.slots) {
                val t = kb.templates.getValue(s.templateId).slots.first { it.slotId == slot.slotId }
                assertTrue(kb.requireExercise(slot.exerciseId).pattern in t.patterns, "invariant 15: ${s.templateId}/${slot.slotId}")
                assertTrue(slot.explanation.ruleIds.containsAll(listOf(RuleIds.SEL, RuleIds.VOL_04, RuleIds.VOL_06)))
            }
        }
        // Availability at HOME excludes machines, cables and lat pulldowns; ADVANCED front squat / Nordic curl are gated.
        val ids = w1.sessions.flatMap { s -> s.slots.map { it.exerciseId } }
        assertTrue(ids.none { it in setOf("lat_pulldown", "front_squat", "nordic_hamstring_curl") || kb.requireExercise(it).loadType.isGymScoped })
        assertTrue("barbell_bench_press" in ids && "back_squat" in ids && "barbell_row" in ids)
        // Under-target report: quiet cards for TIER_1/TIER_2 deficits only; no proposal (nothing below VOL-01).
        val vol = weekVolume(w1)
        for (e in m.underTargetReport) {
            val mu = Muscle.entries.first { it.name.lowercase() == e.inputs["muscle"] }
            assertTrue((vol[mu] ?: 0.0) < w1.targets.getValue(mu))
            assertTrue((vol[mu] ?: 0.0) >= c.vol.vol01Minimum)
        }
        assertTrue(out.proposals.isEmpty())
        // Week-1 bench prescription (known load 80 kg, CALIBRATING): 5–10 @ 80 kg, RIR 3, rest 180 s, WU-02 heavy ramp.
        val upperA = w1.sessions.first()
        val p = planner.realize(upperA, w1, inputs, TodayContext(monday, PlannerFixtures.HOME_ID), inputs.progressionStates)
        val bench = p.blocks.first { it.exerciseId == "barbell_bench_press" }
        assertTrue(bench.sets.filter { it.kind == SetKind.WORKING }.all { it.loadKg == 80.0 && it.rir == 3 && it.restSeconds == 180 && it.reps == PlannerFixtures.band(5, 10) })
        assertEquals(listOf(32.5, 45.0, 55.0, 65.0), bench.sets.filter { it.kind == SetKind.RAMP }.map { it.loadKg })
    }

    @Test
    fun e3CommercialFullBody3NoviceBeginnerOnly() {
        val inputs = PlannerFixtures.inputs(PlannerFixtures.p2(), gyms = listOf(PlannerFixtures.base), bodyWeightKg = 78.0)
        val n = planner.normalize(inputs, monday)
        assertEquals(TrainingAge.NOVICE, n.trainingAge)
        assertTrue(n.beginnerOnly)
        for (id in listOf("back_squat", "barbell_bench_press", "conventional_deadlift", "romanian_deadlift", "overhead_press_barbell", "pull_up", "barbell_row")) assertTrue(id in n.excludedExerciseIds, id)
        val out = planner.plan(inputs, monday)
        val m = out.mesocycle
        assertEquals(SplitKind.FULL_BODY_3, m.split)
        assertEquals(12, m.weeks.size)
        assertNull(m.deloadWeekIndex)                                           // first mesocycle: reactive deloads only
        val w1 = week(m)
        assertEquals(listOf(1, 3, 5), w1.sessions.map { it.weekday })
        assertEquals(listOf("FULL_A", "FULL_B", "FULL_C"), w1.sessions.map { it.templateId })
        assertEquals(8.0, w1.targets[Muscle.QUADS])
        assertEquals(5.0, w1.targets[Muscle.CALVES])
        for (s in w1.sessions) {
            assertTrue(s.slots.sumOf { it.sets } <= c.sess.sess02CapNovice, "${s.templateId} novice cap")
            assertTrue(s.estimatedMinutes <= 60.0)
            assertTrue(s.slots.all { kb.requireExercise(it.exerciseId).difficulty == Difficulty.BEGINNER })
        }
        assertTrue(out.proposals.isEmpty())
        // PROG-05: weeks 1–2 at RIR 3–4 → target 4; every state UNKNOWN → find-load (ramp sets, no working load yet).
        val s = w1.sessions.first()
        val p = planner.realize(s, w1, inputs, TodayContext(monday, PlannerFixtures.BASE_ID), emptyList())
        for (b in p.blocks) {
            val work = b.sets.filter { it.kind == SetKind.WORKING || it.kind == SetKind.CALIBRATION }
            assertTrue(work.all { it.rir == 4 }, "${b.blockId} ${work.map { it.rir }}")
        }
        assertTrue(p.blocks.any { b -> b.sets.any { it.kind == SetKind.RAMP && it.loadSource == com.gymcoach.core.model.LoadSource.LOAD_FIND } })
    }

    @Test
    fun e3HackSquatFindLoadFromTheBodyweightSeed() {
        val inputs = PlannerFixtures.inputs(PlannerFixtures.p2(), gyms = listOf(PlannerFixtures.base), bodyWeightKg = 78.0)
        val w1 = PlannerFixtures.e1Week().copy(sessions = listOf(PlannerFixtures.session("e3:w1:d1:FULL_A", "FULL_A", 1, listOf(
            PlannerFixtures.slot("s1", com.gymcoach.core.model.SlotRole.PRIMARY, "hack_squat", 3, heavy = true, gym = PlannerFixtures.base),
            PlannerFixtures.slot("s2", com.gymcoach.core.model.SlotRole.PRIMARY, "dumbbell_bench_press", 3, gym = PlannerFixtures.base),
            PlannerFixtures.slot("s3", com.gymcoach.core.model.SlotRole.PRIMARY, "seated_cable_row", 3, gym = PlannerFixtures.base),
        ))))
        val p = planner.realize(w1.sessions[0], w1, inputs, TodayContext(monday, PlannerFixtures.BASE_ID), emptyList())
        fun ramp(id: String) = p.blocks.first { it.exerciseId == id }.sets.filter { it.kind == SetKind.RAMP }.map { it.loadKg }
        // Seed 0.15 × 78 = 11.7 kg per hand → 2 × 12 → 2 × 14 → 2 × 16; §4.17 prints a fourth step of 2 × 18, but 11.7 × 1.45 =
        // 16.97 kg rounds to 16 (nearest on the 2-kg rack), which duplicates the third step and is skipped.
        assertEquals(listOf(24.0, 28.0, 32.0), ramp("dumbbell_bench_press"))
        assertEquals(listOf(35.0, 40.0, 45.0, 50.0), ramp("seated_cable_row"))       // seed 0.45 × 78 = 35.1 → stack 35 → 40 → 45 → 50
        assertEquals(ExerciseClass.MACHINE, kb.requireExercise("hack_squat").exerciseClass(com.gymcoach.core.model.LoadType.MACHINE))
        assertTrue(ramp("hack_squat").isNotEmpty())
        assertTrue(p.blocks.all { b -> b.sets.filter { it.kind == SetKind.WORKING }.all { it.rir == 4 } })
    }

    @Test
    fun g5TravelWeekReplansWeek3AsTravel2AtTheHotel() {
        val travelMonday = monday.plusWeeks(2)
        val inputs = PlannerFixtures.inputs(PlannerFixtures.p1(), gyms = listOf(PlannerFixtures.home, PlannerFixtures.hotel), states = PlannerFixtures.knownStates(PlannerFixtures.p1(), PlannerFixtures.home))
            .copy(travelWeeks = listOf(travelMonday))
        val out = planner.plan(inputs, travelMonday.plusDays(1))
        val m = out.mesocycle
        assertEquals(3, out.currentWeekIndex)
        val travel = week(m, travelMonday)
        assertEquals(WeekMode.TRAVEL_MAINTENANCE, travel.mode)
        assertEquals(3, travel.index)                                           // week counter paused at 3
        assertEquals(listOf(2, 4), travel.sessions.map { it.weekday })
        assertEquals(listOf("TRAVEL_A", "TRAVEL_B"), travel.sessions.map { it.templateId })
        val resume = week(m, travelMonday.plusWeeks(1))
        assertEquals(WeekMode.NORMAL, resume.mode)
        assertEquals(3, resume.index)                                           // on return week 3 resumes
        val eq = SessionEquipment(PlannerFixtures.hotel)
        val resolver = EquipmentResolver(kb)
        for (s in travel.sessions) {
            assertTrue(s.estimatedMinutes <= c.trv.trv01Minutes.hi, "${s.templateId} ${s.estimatedMinutes}")
            assertTrue(s.slots.size in c.trv.trv01Exercises.lo..c.trv.trv01Exercises.hi, "${s.templateId} ${s.slots.size} exercises")
            assertTrue(s.slots.all { it.sets in c.trv.trv01Sets.lo..c.trv.trv01Sets.hi })
            assertTrue(s.slots.all { resolver.resolve(kb.requireExercise(it.exerciseId), eq).available })
            assertTrue(s.slots.all { RuleIds.TRV_01 in it.explanation.ruleIds })
        }
        // VOL-01 check (TRV-01): every TIER_1 muscle reaches 4 fractional sets in the travel week.
        val vol = weekVolume(travel)
        for (mu in listOf(Muscle.CHEST, Muscle.LATS, Muscle.UPPER_BACK, Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.GLUTES, Muscle.SIDE_DELTS)) {
            assertTrue((vol[mu] ?: 0.0) >= c.vol.vol01Minimum, "$mu ${vol[mu]}")
        }
        // RIR (TRV-01, offset 0): dumbbell compounds 2, isolation 1, light-load endurance fixed 1.
        val s = travel.sessions.first()
        val p = planner.realize(s, travel, inputs, TodayContext(Weeks.dateFor(travelMonday, s.weekday), PlannerFixtures.HOTEL_ID), inputs.progressionStates)
        assertEquals(com.gymcoach.core.model.SessionMode.TRAVEL, p.mode)
        for (b in p.blocks) {
            val e = kb.requireExercise(b.exerciseId)
            val work = b.sets.filter { it.kind == SetKind.WORKING }
            val endurance = b.warnings.any { RuleIds.LOAD_01 in it.ruleIds }
            when {
                endurance -> assertTrue(work.all { it.rir == c.trv.trv01EnduranceRir }, b.blockId)
                e.isIsolation && e.repProfile.startsWith("isolation") -> assertTrue(work.all { it.rir == 1 }, b.blockId)
                e.flags.compound && b.effectiveLoadType == com.gymcoach.core.model.LoadType.DUMBBELL && e.repProfile == "compound" -> assertTrue(work.all { it.rir == 2 || it.rir == 1 }, "${b.blockId} ${work.map { it.rir }}")
                else -> Unit
            }
            assertTrue(work.all { it.restSeconds in 30..90 }, "${b.blockId} rest")
        }
    }
}
