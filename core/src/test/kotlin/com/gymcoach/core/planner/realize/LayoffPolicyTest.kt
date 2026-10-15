package com.gymcoach.core.planner.realize

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.ProgressionStatus
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SessionStatus
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.model.SplitKind
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.planner.PlannerFixtures
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoffPolicyTest {
    private val kb = PlannerFixtures.kb
    private val c = PlannerConstants.DEFAULT
    private val policy = LayoffPolicy(kb, c)
    private val week = PlannerFixtures.e1Week()
    private val meso = Mesocycle("meso-x", PlannerFixtures.MONDAY, Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE, SplitKind.UPPER_LOWER_4, PlannerFixtures.HOME_ID, listOf(week), 7, "x")

    private fun logged(exerciseId: String, date: LocalDate, completed: Int = 0): PlanInputs {
        val rec = SessionRecord("rec-$date", date, PlannerFixtures.HOME_ID, status = SessionStatus.COMPLETED)
        val set = SetLog("set-$date", rec.id, null, exerciseId, 1, SetKind.WORKING, reps = 8, rir = 2, loggedAt = date.atTime(18, 0).toInstant(ZoneOffset.UTC))
        return PlannerFixtures.inputs(PlannerFixtures.p1(), mesocyclesCompleted = completed).copy(recentSessions = listOf(rec), recentSets = listOf(set))
    }

    @Test
    fun e1BenchOnWeek2MondayOnceWeeklyPatternHasNoLayoff_LAY01() {
        assertEquals(7, policy.scheduledGapDays(MovementPattern.HORIZONTAL_PUSH, week))
        val inp = logged("barbell_bench_press", PlannerFixtures.MONDAY)
        assertNull(policy.forBlock(MovementPattern.HORIZONTAL_PUSH, "barbell_bench_press", PlannerFixtures.MONDAY.plusDays(7), meso, inp, emptyList(), 38, TrainingAge.INTERMEDIATE))
    }

    @Test
    fun nineDayGapOnATwiceWeeklyPatternIsRow1() {
        assertEquals(4, policy.scheduledGapDays(MovementPattern.VERTICAL_PULL, week))   // Mon + Thu → gaps 3 and 4
        val today = PlannerFixtures.MONDAY.plusDays(14)
        val inp = logged("pull_up", today.minusDays(9))
        val m = assertNotNull(policy.forBlockWithGap(MovementPattern.VERTICAL_PULL, "pull_up", today, 4, inp, emptyList(), 38, TrainingAge.INTERMEDIATE))
        assertEquals(1, m.row)
        assertEquals(c.lay.lay01Row1Load, m.loadFactor)
        assertEquals(-1, m.setDelta)
        assertEquals(1, m.rirOffset)
        assertTrue(RuleIds.LAY_01 in m.explanation.ruleIds)
    }

    @Test
    fun patternLastTrained100DaysAgoViaPatternLastTrainedIsRow4() {
        val today = PlannerFixtures.MONDAY
        val inp = PlannerFixtures.inputs(PlannerFixtures.p1(), mesocyclesCompleted = 1).copy(patternLastTrained = mapOf(MovementPattern.SQUAT to today.minusDays(100)))
        val m = assertNotNull(policy.forBlockWithGap(MovementPattern.SQUAT, "back_squat", today, 7, inp, emptyList(), 38, TrainingAge.INTERMEDIATE))
        assertEquals(4, m.row)
        assertEquals(0.65, m.loadFactor)
        assertNull(m.setFactor)
    }

    @Test
    fun excessRowsAndTheAge65ConservativeShift() {
        val today = PlannerFixtures.MONDAY.plusDays(60)
        val rows = listOf(12 to 1, 20 to 2, 40 to 3, 70 to 4)
        for ((excess, row) in rows) {
            val inp = logged("back_squat", today.minusDays((excess + 7).toLong()))
            assertEquals(row, policy.forBlockWithGap(MovementPattern.SQUAT, "back_squat", today, 7, inp, emptyList(), 38, TrainingAge.INTERMEDIATE)?.row, "excess $excess")
        }
        val inp = logged("back_squat", today.minusDays(19))
        assertEquals(2, policy.forBlockWithGap(MovementPattern.SQUAT, "back_squat", today, 7, inp, emptyList(), 66, TrainingAge.INTERMEDIATE)?.row)
        assertNull(policy.forBlockWithGap(MovementPattern.SQUAT, "back_squat", today, 7, logged("back_squat", today.minusDays(11)), emptyList(), 38, TrainingAge.INTERMEDIATE))
    }

    @Test
    fun brandNewUserGetsNoModifierButAKnownUserWithoutRecordGetsRow4() {
        val fresh = PlannerFixtures.inputs(PlannerFixtures.p1())
        assertNull(policy.forBlockWithGap(MovementPattern.HINGE, "romanian_deadlift", PlannerFixtures.MONDAY, 7, fresh, emptyList(), 38, TrainingAge.INTERMEDIATE))
        val known = PlannerFixtures.inputs(PlannerFixtures.p1(), mesocyclesCompleted = 2)
        val m = assertNotNull(policy.forBlockWithGap(MovementPattern.HINGE, "romanian_deadlift", PlannerFixtures.MONDAY, 7, known, emptyList(), 38, TrainingAge.NOVICE))
        assertEquals(4, m.row)
        assertTrue(m.noviceRestart)
        assertTrue(RuleIds.PROG_05 in m.explanation.ruleIds)
    }

    @Test
    fun exerciseNotDoneForThreeWeeksOnAPatternOnCadenceGetsRow1Only() {
        val today = PlannerFixtures.MONDAY.plusDays(28)
        val inp = logged("dumbbell_bench_press", today.minusDays(7))
        val state = ProgressionState(ProgressionKey("barbell_bench_press"), ProgressionStatus.PROGRESSING, workingLoadKg = 80.0, workingReps = PlannerFixtures.band(5, 10), repHiBase = 10, targetRir = 2,
            lastSessionDate = today.minusDays(28), updatedAt = PlannerFixtures.NOW)
        val m = assertNotNull(policy.forBlockWithGap(MovementPattern.HORIZONTAL_PUSH, "barbell_bench_press", today, 7, inp, listOf(state), 38, TrainingAge.INTERMEDIATE))
        assertEquals(1, m.row)
        // G5: a barbell lift skipped for one travel week (14 − 7 = 7 < 14) resumes without a modifier.
        val oneWeek = state.copy(lastSessionDate = today.minusDays(14))
        assertNull(policy.forBlockWithGap(MovementPattern.HORIZONTAL_PUSH, "barbell_bench_press", today, 7, inp, listOf(oneWeek), 38, TrainingAge.INTERMEDIATE))
    }
}
