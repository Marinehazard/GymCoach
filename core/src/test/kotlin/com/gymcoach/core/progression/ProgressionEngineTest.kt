package com.gymcoach.core.progression

import com.gymcoach.core.equipment.GymParams
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.AdjustmentKind
import com.gymcoach.core.model.Band
import com.gymcoach.core.model.E1rmConfidence
import com.gymcoach.core.model.ExerciseBlock
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.ProgressionStatus
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.model.SessionCloseResult
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetPrescription
import com.gymcoach.core.model.Side
import com.gymcoach.core.model.SlotRole
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.WeekMode
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §4.9 / §10.2 Progression: G1, PROG-01..05, RIR-05, UNI-02, blockers, "hotel day at rack max", sacred inputs. */
class ProgressionEngineTest {
    private val engine = ProgressionEngine(PF.kb, PF.c)
    private val bench = "barbell_bench_press"

    private fun only(r: SessionCloseResult): ProgressionState { assertEquals(1, r.states.size, "states"); return r.states[0] }

    private fun run(state: ProgressionState, reps: List<Int>, rir: Int, kg: Double, date: LocalDate, mode: WeekMode = WeekMode.NORMAL,
                    prescribed: Double? = null, idx: Int = 3, exercise: String = state.key.exerciseId, perHand: Boolean = false,
                    trainingAge: TrainingAge = TrainingAge.INTERMEDIATE, band: ReadinessBand? = ReadinessBand.GREEN,
                    gym: com.gymcoach.core.model.GymSet = PF.home, firstMeso: Boolean = false): SessionCloseResult {
        val id = "sess-$exercise-$date"
        val sets = PF.sets(id, exercise, kg, reps, rir, perHand = perHand, prescribed = prescribed, date = date)
        return engine.afterSession(PF.input(PF.record(id, date, gym), sets, listOf(state), gym = gym, mode = mode, weekIndex = idx,
            trainingAge = trainingAge, band = band, firstMeso = firstMeso))
    }

    // ---- initialState --------------------------------------------------------------------------------------------

    @Test fun initialStateFromKnownLoadIsCalibrating() {
        val ex = PF.kb.requireExercise(bench)
        val s = engine.initialState(ProgressionKey(bench), ex, Goal.HYPERTROPHY, 80.0, PF.T0)
        assertEquals(ProgressionStatus.CALIBRATING, s.status)
        assertEquals(80.0, s.workingLoadKg)
        assertEquals(Band(5, 10), s.workingReps)
        assertEquals(10, s.repHiBase)
        assertEquals(2, s.targetRir)
        assertEquals(0, s.calibrationSessions)
        val u = engine.initialState(ProgressionKey(bench), ex, Goal.HYPERTROPHY, null, PF.T0)
        assertEquals(ProgressionStatus.UNKNOWN, u.status)
        assertNull(u.workingLoadKg)
    }

    // ---- G1 -------------------------------------------------------------------------------------------------------

    @Test fun g1BenchMesocycleIncludingDeloadAndReentryFairness() {
        var s = PF.state(bench, 80.0, Band(5, 10), 2)
        fun week(k: Int): LocalDate = PF.MONDAY.plusWeeks((k - 1).toLong())

        // Week 1 (RIR 3): 8, 8, 7, 7 @ 3 → hold; e1RM 80 × 8 @ 3 → 106.7 HIGH.
        var r = run(s, listOf(8, 8, 7, 7), 3, 80.0, week(1), idx = 1); s = only(r)
        assertEquals(80.0, s.workingLoadKg)
        assertEquals(106.7, s.history[0].e1rm?.kg)
        assertEquals(E1rmConfidence.HIGH, s.history[0].e1rm?.confidence)
        assertTrue(RuleIds.PROG_01 in r.adjustments[0].ruleIds)

        // Week 2: 9, 9, 8, 8 @ 2 → hold, reps at load improved → noProgressCount 0.
        r = run(s, listOf(9, 9, 8, 8), 2, 80.0, week(2), idx = 2); s = only(r)
        assertEquals(80.0, s.workingLoadKg)
        assertEquals(0, s.noProgressCount)

        // Week 3: 10, 10, 9, 9 @ 2 → hold (2 sets short of hi).
        r = run(s, listOf(10, 10, 9, 9), 2, 80.0, week(3), idx = 3); s = only(r)
        assertEquals(80.0, s.workingLoadKg)
        assertEquals(0, s.noProgressCount)

        // Week 4: 10 × 4 @ 2 → PROG-01, cadence ok → +2.5 kg (UPPER_BARBELL) → 82.5.
        r = run(s, listOf(10, 10, 10, 10), 2, 80.0, week(4), idx = 4); s = only(r)
        assertEquals(82.5, s.workingLoadKg)
        assertEquals(week(4), s.lastIncrementDate)
        assertEquals(Band(5, 10), s.workingReps)
        val inc = r.adjustments.single()
        assertEquals(AdjustmentKind.PROGRESSION, inc.kind)
        assertEquals(OverrideSource.PLANNER, inc.source)
        assertTrue(RuleIds.PROG_01 in inc.ruleIds && RuleIds.PROG_02 in inc.ruleIds, inc.ruleIds.toString())
        assertTrue(inc.summary.contains("80 → 82.5 kg"), inc.summary)

        // Week 5: 8, 8, 7, 7 @ 2 → hold. Week 6: 9, 9, 8, 8 @ 1 → hold; best e1RM 14 d = 110.0.
        s = only(run(s, listOf(8, 8, 7, 7), 2, 82.5, week(5), idx = 5))
        assertEquals(82.5, s.workingLoadKg)
        s = only(run(s, listOf(9, 9, 8, 8), 1, 82.5, week(6), idx = 6))
        assertEquals(82.5, s.workingLoadKg)
        assertEquals(110.0, s.bestE1rm14d)
        assertEquals(ProgressionStatus.PROGRESSING, s.status)

        // Week 7 DELOAD: 2 × 8 @ 4 at 70 (prescribed 70 < 82.5) → DELOADING, noProgressCount 0, e1RM 93.3 stored but unfair.
        r = run(s, listOf(8, 8), 4, 70.0, week(7), mode = WeekMode.DELOAD, prescribed = 70.0, idx = 7); s = only(r)
        assertEquals(ProgressionStatus.DELOADING, s.status)
        assertEquals(0, s.noProgressCount)
        assertEquals(82.5, s.workingLoadKg)
        assertEquals(93.3, s.history[0].e1rm?.kg)
        assertFalse(s.history[0].fair)
        assertEquals(110.0, s.bestE1rm14d)
        assertTrue(RuleIds.DLD_02 in r.adjustments[0].ruleIds)

        // Week 8 REENTRY: 3 × 10 @ 3 at 77.5 (prescribed 77.5 < 82.5): capped → no increment, no PLAT count, no PLAT-03.
        r = run(s, listOf(10, 10, 10), 3, 77.5, week(8), mode = WeekMode.REENTRY, prescribed = 77.5, idx = 1); s = only(r)
        assertEquals(ProgressionStatus.REENTRY, s.status)
        assertEquals(0, s.noProgressCount)
        assertEquals(82.5, s.workingLoadKg)
        assertTrue(r.flags.none { RuleIds.PLAT_03 in it.ruleIds })
        assertTrue(RuleIds.DLD_04 in r.adjustments[0].ruleIds)

        // Week 9 NORMAL → PROGRESSING.
        r = run(s, listOf(8, 8, 8, 8), 2, 82.5, week(9), idx = 2); s = only(r)
        assertEquals(ProgressionStatus.PROGRESSING, s.status)
        assertEquals(0, s.noProgressCount)
        assertTrue(r.flags.isEmpty())
    }

    @Test fun g1AutoregulationChipSuggests75kgAndIsNeverApplied() {
        val ex = PF.kb.requireExercise(bench)
        val block = benchBlock()
        val first = PF.set("s-auto", bench, 1, 80.0, 7, 0, blockId = block.blockId)
        val copy = first.copy()
        val params = GymParams(PF.kb, SessionEquipment(PF.home))
        val hit = engine.inSessionBackoff(block, first, params)
        assertNotNull(hit)
        assertEquals(75.0, hit.first)
        assertEquals(listOf(RuleIds.PROG_04), hit.second.ruleIds)
        assertEquals(copy, first)
        assertNull(engine.inSessionBackoff(block, first.copy(rir = 1), params), "RIR 1 vs target 2 is not ≤ target − 2")
        assertEquals(LoadType.BARBELL, ex.loadType)
    }

    private fun benchBlock(): ExerciseBlock = ExerciseBlock(
        blockId = "A1:$bench", slotId = "A1", exerciseId = bench, optionIndex = 0, effectiveLoadType = LoadType.BARBELL,
        slotRole = SlotRole.PRIMARY, order = 0,
        sets = (1..4).map { SetPrescription(index = it, kind = SetKind.WORKING, reps = Band(5, 10), loadKg = 80.0, rir = 2, restSeconds = 180) },
        progressionKey = ProgressionKey(bench), explanation = Explanation(emptyList(), ""),
    )

    @Test fun findLoadDecisionStopsAtTargetPlusOne() {
        val block = benchBlock()
        assertTrue(engine.findLoadDecision(block, PF.set("f", bench, 1, 60.0, 7, 3, kind = SetKind.RAMP)))
        assertFalse(engine.findLoadDecision(block, PF.set("f", bench, 2, 50.0, 7, 4, kind = SetKind.RAMP)))
        assertFalse(engine.findLoadDecision(block, PF.set("f", bench, 3, 50.0, 7, null, kind = SetKind.RAMP)))
    }

    // ---- PROG-02 dumbbell per-hand basis -------------------------------------------------------------------------

    @Test fun dumbbellJumpAbove10PercentExtendsRepsFirstInBothLoggingStyles() {
        val db = "dumbbell_bench_press"
        val start = PF.state(db, 24.0, Band(8, 12), 2, hits = 1)
        val perHand = only(run(start, listOf(12, 12, 12), 2, 12.0, PF.MONDAY, exercise = db, perHand = true))
        val pair = only(run(start, listOf(12, 12, 12), 2, 24.0, PF.MONDAY, exercise = db, perHand = false))
        for (s in listOf(perHand, pair)) {
            assertTrue(s.repHiExtended)
            assertEquals(Band(8, 14), s.workingReps)
            assertEquals(24.0, s.workingLoadKg)
        }
        assertEquals(perHand.copy(history = emptyList()), pair.copy(history = emptyList()))
    }

    @Test fun dumbbellJumpBelow10PercentIncrementsThePair() {
        val db = "dumbbell_bench_press"
        val start = PF.state(db, 44.0, Band(8, 12), 2, hits = 1)
        val r = run(start, listOf(12, 12, 12), 2, 22.0, PF.MONDAY, exercise = db, perHand = true)
        val s = only(r)
        assertEquals(48.0, s.workingLoadKg)            // 2 × 24 kg (+9.1 % per hand)
        assertFalse(s.repHiExtended)
        assertTrue(RuleIds.PROG_02 in r.adjustments[0].ruleIds)
    }

    @Test fun extendedRepCeilingThenIncrementsLoadAndResetsBand() {
        val db = "dumbbell_bench_press"
        val start = PF.state(db, 24.0, Band(8, 14), 2, hits = 1, extended = true, repHiBase = 12)
        val s = only(run(start, listOf(14, 14, 14), 1, 12.0, PF.MONDAY, exercise = db, perHand = true))   // RIR-05: 14 reps @ 1 audits to 2
        assertEquals(28.0, s.workingLoadKg)
        assertEquals(Band(8, 12), s.workingReps)
        assertFalse(s.repHiExtended)
    }

    // ---- hits / cadence ----------------------------------------------------------------------------------------

    @Test fun coarseImplementNeedsTwoHits() {
        val db = "dumbbell_bench_press"
        val r = run(PF.state(db, 44.0, Band(8, 12), 2), listOf(12, 12, 12), 2, 22.0, PF.MONDAY, exercise = db, perHand = true)
        val s = only(r)
        assertEquals(44.0, s.workingLoadKg)
        assertEquals(1, s.consecutiveHits)
        assertTrue(RuleIds.R4_5_4 in r.adjustments[0].ruleIds)
    }

    @Test fun advancedBarbellNeedsTwoHitsIntermediateOne() {
        val st = PF.state(bench, 80.0, Band(5, 10), 2)
        assertEquals(80.0, only(run(st, listOf(10, 10, 10), 2, 80.0, PF.MONDAY, trainingAge = TrainingAge.ADVANCED)).workingLoadKg)
        assertEquals(82.5, only(run(st, listOf(10, 10, 10), 2, 80.0, PF.MONDAY, trainingAge = TrainingAge.INTERMEDIATE)).workingLoadKg)
    }

    @Test fun overRirTopHitIsNotAHit() {
        val s = only(run(PF.state(bench, 80.0, Band(5, 10), 2), listOf(10, 10, 10), 3, 80.0, PF.MONDAY))
        assertEquals(80.0, s.workingLoadKg)
        assertEquals(0, s.consecutiveHits)
    }

    @Test fun prog03IntermediateCadenceSevenDays() {
        val st = PF.state(bench, 80.0, Band(5, 10), 2, lastIncrement = PF.MONDAY.minusDays(3))
        val r = run(st, listOf(10, 10, 10), 2, 80.0, PF.MONDAY)
        assertEquals(80.0, only(r).workingLoadKg)
        assertTrue(RuleIds.PROG_03 in r.adjustments[0].ruleIds)
        val novice = only(run(st, listOf(10, 10, 10), 2, 80.0, PF.MONDAY, trainingAge = TrainingAge.NOVICE))
        assertEquals(82.5, novice.workingLoadKg)
        val week = only(run(st.copy(lastIncrementDate = PF.MONDAY.minusDays(7)), listOf(10, 10, 10), 2, 80.0, PF.MONDAY))
        assertEquals(82.5, week.workingLoadKg)
    }

    @Test fun prog05NoviceWeeksOneAndTwoAccumulateHitsWithoutIncrement() {
        val st = PF.state(bench, 60.0, Band(5, 10), 2)
        val r = run(st, listOf(10, 10, 10), 2, 60.0, PF.MONDAY, trainingAge = TrainingAge.NOVICE, idx = 1, firstMeso = true)
        val s = only(r)
        assertEquals(60.0, s.workingLoadKg)
        assertEquals(1, s.consecutiveHits)
        assertTrue(RuleIds.PROG_05 in r.adjustments[0].ruleIds)
        val wk3 = only(run(s, listOf(10, 10, 10), 2, 60.0, PF.MONDAY.plusWeeks(2), trainingAge = TrainingAge.NOVICE, idx = 3, firstMeso = true))
        assertEquals(62.5, wk3.workingLoadKg)
    }

    @Test fun prog04AllSetsEasyIncrements() {
        val r = run(PF.state(bench, 80.0, Band(5, 10), 2), listOf(8, 8, 8, 8), 4, 80.0, PF.MONDAY)
        assertEquals(82.5, only(r).workingLoadKg)
        assertTrue(RuleIds.PROG_04 in r.adjustments[0].ruleIds)
    }

    @Test fun prog01RepsBelowRangeOnTwoSetsHoldsAndResetsHits() {
        val r = run(PF.state(bench, 80.0, Band(5, 10), 2, hits = 1), listOf(6, 6, 4, 4), 1, 80.0, PF.MONDAY)
        val s = only(r)
        assertEquals(80.0, s.workingLoadKg)
        assertEquals(0, s.consecutiveHits)
        assertTrue(r.adjustments[0].summary.contains("below range"), r.adjustments[0].summary)
    }

    @Test fun rir05HighRepSetsAuditedPlusOne() {
        val lr = "dumbbell_lateral_raise"
        val st = PF.state(lr, 16.0, Band(12, 20), 1, hits = 1)
        val r = run(st, listOf(20, 20, 20), 1, 8.0, PF.MONDAY, exercise = lr, perHand = true)
        val s = only(r)
        assertEquals(16.0, s.workingLoadKg)           // audited RIR 2 > target 1 → not a hit
        assertEquals(0, s.consecutiveHits)
        assertTrue(RuleIds.RIR_05 in r.adjustments[0].ruleIds)
    }

    // ---- blockers ------------------------------------------------------------------------------------------------

    @Test fun amberDeloadReentryTravelAndCappedBlockIncrements() {
        val st = PF.state(bench, 80.0, Band(5, 10), 2)
        val cases = listOf(
            Triple(RuleIds.RDY_02, WeekMode.NORMAL, ReadinessBand.AMBER),
            Triple(RuleIds.RDY_02, WeekMode.NORMAL, ReadinessBand.RED),
            Triple(RuleIds.DLD_02, WeekMode.DELOAD, ReadinessBand.GREEN),
            Triple(RuleIds.DLD_04, WeekMode.REENTRY, ReadinessBand.GREEN),
            Triple(RuleIds.TRV_01, WeekMode.TRAVEL_MAINTENANCE, ReadinessBand.GREEN),
        )
        for ((rule, mode, band) in cases) {
            val r = run(st, listOf(10, 10, 10), 2, 80.0, PF.MONDAY, mode = mode, band = band)
            assertEquals(80.0, only(r).workingLoadKg, rule)
            assertTrue(rule in r.adjustments[0].ruleIds, "$rule ${r.adjustments[0].ruleIds}")
        }
        val capped = run(st, listOf(10, 10, 10), 2, 77.5, PF.MONDAY, prescribed = 77.5)
        assertEquals(80.0, only(capped).workingLoadKg)
        assertTrue(RuleIds.R4_2_6 in capped.adjustments[0].ruleIds)
    }

    @Test fun hotelDayAtRackMaxDoesNotLowerWorkingLoadKg() {
        val goblet = "goblet_squat"
        val st = PF.state(goblet, 24.0, Band(8, 12), 2, hits = 1).copy(noProgressCount = 1,
            history = listOf(com.gymcoach.core.model.SessionSummary(PF.MONDAY.minusDays(7), 24.0, 10, 2, false)))
        val r = run(st, listOf(14, 14, 14), 1, 20.0, PF.MONDAY, exercise = goblet, prescribed = 20.0, gym = PF.hotel)
        val s = only(r)
        assertEquals(24.0, s.workingLoadKg)
        assertEquals(1, s.consecutiveHits)
        assertEquals(1, s.noProgressCount)
        assertFalse(s.history[0].fair)
        assertTrue(RuleIds.R4_2_6 in r.adjustments[0].ruleIds)
    }

    // ---- UNI-02 pairing --------------------------------------------------------------------------------------------

    @Test fun leftRightPairCountsOnceAtTheWeakerSideAndUnpairedHalf() {
        val bss = "bulgarian_split_squat"
        val st = PF.state(bss, 22.0, Band(8, 12), 2, hits = 1)
        val id = "sess-bss"
        val sets = listOf(
            PF.set(id, bss, 1, 22.0, 12, 2, perHand = true, side = Side.LEFT), PF.set(id, bss, 1, 22.0, 11, 2, perHand = true, side = Side.RIGHT),
            PF.set(id, bss, 2, 22.0, 12, 2, perHand = true, side = Side.LEFT), PF.set(id, bss, 2, 22.0, 12, 2, perHand = true, side = Side.RIGHT),
        )
        val s = only(engine.afterSession(PF.input(PF.record(id, PF.MONDAY), sets, listOf(st))))
        assertEquals(22.0, s.workingLoadKg)            // set 1 counts 11 reps (min of the pair) → not all at hi
        assertEquals(0, s.consecutiveHits)

        val unpaired = sets.take(2) + PF.set(id, bss, 3, 22.0, 12, 2, perHand = true, side = Side.LEFT)
        val r = engine.afterSession(PF.input(PF.record(id, PF.MONDAY), unpaired, listOf(st)))
        assertTrue(RuleIds.UNI_02 in r.adjustments[0].ruleIds, r.adjustments[0].ruleIds.toString())
    }

    // ---- find-load / calibration -----------------------------------------------------------------------------------

    @Test fun findLoadSessionCalibratesThenTwoSessionsProgress() {
        val ex = PF.kb.requireExercise("back_squat")
        val unknown = engine.initialState(ProgressionKey("back_squat"), ex, Goal.HYPERTROPHY, null, PF.T0)
        val id = "sess-find"
        val sets = listOf(
            PF.set(id, "back_squat", 1, 60.0, 7, 5, kind = SetKind.RAMP), PF.set(id, "back_squat", 2, 70.0, 7, 3, kind = SetKind.RAMP),
            PF.set(id, "back_squat", 3, 80.0, 7, 2), PF.set(id, "back_squat", 4, 80.0, 7, 2),
        )
        val r = engine.afterSession(PF.input(PF.record(id, PF.MONDAY), sets, listOf(unknown)))
        val s = only(r)
        assertEquals(ProgressionStatus.CALIBRATING, s.status)
        assertEquals(80.0, s.workingLoadKg)
        assertEquals(1, s.calibrationSessions)
        assertTrue(RuleIds.TRV_04 in r.adjustments[0].ruleIds)
        val s2 = only(run(s, listOf(8, 8, 8), 2, 80.0, PF.MONDAY.plusDays(3), exercise = "back_squat"))
        assertEquals(ProgressionStatus.PROGRESSING, s2.status)
        assertEquals(2, s2.calibrationSessions)
    }

    @Test fun missingStateIsSeededFromTheKnownLoad() {
        val id = "sess-seed"
        val sets = PF.sets(id, bench, 80.0, listOf(8, 8, 8), 2)
        val s = only(engine.afterSession(PF.input(PF.record(id, PF.MONDAY), sets, emptyList())))
        assertEquals(ProgressionStatus.CALIBRATING, s.status)
        assertEquals(1, s.calibrationSessions)
        assertEquals(80.0, s.workingLoadKg)
    }

    // ---- sacred inputs, adjustments ---------------------------------------------------------------------------------

    @Test fun inputSetLogsAreUnmodifiedAndEveryDecisionEmitsAnAdjustment() {
        val id = "sess-sacred"
        val sets = PF.sets(id, bench, 80.0, listOf(10, 10, 10), 2) + PF.sets(id, "dumbbell_bench_press", 22.0, listOf(10, 9), 2, perHand = true)
        val before = sets.map { CoreJson.stored.encodeToString(com.gymcoach.core.model.SetLog.serializer(), it) }
        val copies = sets.map { it.copy() }
        val states = listOf(PF.state(bench, 80.0, Band(5, 10), 2), PF.state("dumbbell_bench_press", 44.0, Band(8, 12), 2))
        val r = engine.afterSession(PF.input(PF.record(id, PF.MONDAY), sets, states))
        assertEquals(copies, sets)
        assertEquals(before, sets.map { CoreJson.stored.encodeToString(com.gymcoach.core.model.SetLog.serializer(), it) })
        assertEquals(2, r.states.size)
        assertEquals(2, r.adjustments.size)
        for (a in r.adjustments) {
            assertEquals(AdjustmentKind.PROGRESSION, a.kind)
            assertTrue(a.ruleIds.isNotEmpty())
            val b = CoreJson.stored.decodeFromString(ProgressionState.serializer(), a.beforeJson)
            val af = CoreJson.stored.decodeFromString(ProgressionState.serializer(), a.afterJson)
            assertEquals(a.exerciseId, b.key.exerciseId)
            assertEquals(r.states.first { it.key.exerciseId == a.exerciseId }, af)
        }
    }

    @Test fun sessionWithoutCountedSetsLeavesTheStateAlone() {
        val id = "sess-ramp"
        val sets = listOf(PF.set(id, bench, 1, 60.0, 5, 4, kind = SetKind.RAMP))
        val r = engine.afterSession(PF.input(PF.record(id, PF.MONDAY), sets, listOf(PF.state(bench, 80.0, Band(5, 10), 2))))
        assertTrue(r.states.isEmpty())
        assertTrue(r.adjustments.isEmpty())
    }

    @Test fun repProgressionShiftsRepsThenAddsExternalLoad() {
        val pu = "push_up"
        val st = PF.state(pu, null, Band(6, 12), 2)
        val s = only(runBw(st, listOf(12, 12, 12), 2, null, PF.MONDAY, exercise = pu))
        assertEquals(Band(8, 14), s.workingReps)
        val top = PF.state(pu, null, Band(10, 16), 2, repHiBase = 12)
        val s2 = only(runBw(top, listOf(16, 16, 16), 1, null, PF.MONDAY, exercise = pu))   // RIR-05 audit: 1 → 2
        assertEquals(2.5, s2.workingLoadKg)
        assertEquals(Band(6, 12), s2.workingReps)
    }

    private fun runBw(state: ProgressionState, reps: List<Int>, rir: Int, kg: Double?, date: LocalDate, exercise: String): SessionCloseResult {
        val id = "sess-$exercise-$date"
        val sets = PF.sets(id, exercise, kg, reps, rir, date = date)
        return engine.afterSession(PF.input(PF.record(id, date), sets, listOf(state)))
    }
}
