package com.gymcoach.core.volume

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.model.Side
import com.gymcoach.core.planner.PlannerFixtures
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VolumeAccountantTest {
    private val kb = PlannerFixtures.kb

    private fun set(ex: String, idx: Int, kind: SetKind = SetKind.WORKING, side: Side = Side.BOTH, session: String = "s1", superseded: String? = null, block: String? = "b:$ex") =
        SetLog("id-$ex-$idx-${side.name}-$session", session, block, ex, idx, kind, reps = 10, rir = 2, side = side, loggedAt = Instant.parse("2026-09-07T18:00:00Z"), supersededById = superseded)

    @Test
    fun r4WorkedRowsBenchSquatDeadliftCableRowPulldown() {
        val w = VolumeAccountant.weekly(listOf(set("barbell_bench_press", 1)), kb)
        assertEquals(1.0, w.getValue(Muscle.CHEST).fractional)
        assertEquals(0.5, w.getValue(Muscle.FRONT_DELTS).fractional)
        assertEquals(0.5, w.getValue(Muscle.TRICEPS).fractional)
        assertEquals(1, w.getValue(Muscle.CHEST).direct)
        assertEquals(0, w.getValue(Muscle.TRICEPS).direct)

        val squat = VolumeAccountant.weekly(listOf(set("back_squat", 1)), kb)
        assertEquals(1.0, squat.getValue(Muscle.QUADS).fractional)
        assertEquals(0.5, squat.getValue(Muscle.GLUTES).fractional)
        assertEquals(0.5, squat.getValue(Muscle.ADDUCTORS).fractional)
        assertEquals(0.25, squat.getValue(Muscle.HAMSTRINGS).fractional)
        assertEquals(0.25, squat.getValue(Muscle.LOWER_BACK).fractional)

        val dl = VolumeAccountant.weekly(listOf(set("conventional_deadlift", 1)), kb)
        assertEquals(1.0, dl.getValue(Muscle.GLUTES).fractional)
        assertEquals(1.0, dl.getValue(Muscle.LOWER_BACK).fractional)
        assertEquals(0.5, dl.getValue(Muscle.HAMSTRINGS).fractional)

        val row = VolumeAccountant.weekly(listOf(set("seated_cable_row", 1), set("seated_cable_row", 2)), kb)
        assertEquals(2.0, row.getValue(Muscle.LATS).fractional)
        assertEquals(2.0, row.getValue(Muscle.UPPER_BACK).fractional)
        assertEquals(1.0, row.getValue(Muscle.BICEPS).fractional)
        assertEquals(0.5, row.getValue(Muscle.LOWER_BACK).fractional)

        val pd = VolumeAccountant.weekly(listOf(set("lat_pulldown", 1)), kb)
        assertEquals(1.0, pd.getValue(Muscle.LATS).fractional)
        assertEquals(0.25, pd.getValue(Muscle.REAR_DELTS).fractional)
    }

    @Test
    fun leftRightPairCountsOnceAndAnUnpairedSideCountsHalf_UNI02() {
        assertTrue(RuleIds.UNI_02 in RuleIds.ALL)
        val paired = listOf(set("bulgarian_split_squat", 1, side = Side.LEFT), set("bulgarian_split_squat", 1, side = Side.RIGHT))
        assertEquals(1.0, VolumeAccountant.weekly(paired, kb).getValue(Muscle.QUADS).fractional)
        val unpaired = listOf(set("bulgarian_split_squat", 1, side = Side.LEFT))
        assertEquals(0.5, VolumeAccountant.weekly(unpaired, kb).getValue(Muscle.QUADS).fractional)
        val both = listOf(set("bulgarian_split_squat", 1), set("bulgarian_split_squat", 2))
        assertEquals(2.0, VolumeAccountant.weekly(both, kb).getValue(Muscle.QUADS).fractional)
    }

    @Test
    fun rampAndSupersededSetsNeverCount_WU03() {
        val sets = listOf(set("barbell_bench_press", 1, kind = SetKind.RAMP), set("barbell_bench_press", 2), set("barbell_bench_press", 3, superseded = "x"),
            set("barbell_bench_press", 4, kind = SetKind.AMRAP), set("barbell_bench_press", 5, kind = SetKind.CALIBRATION), set("barbell_bench_press", 6, kind = SetKind.DROP))
        assertEquals(4.0, VolumeAccountant.weekly(sets, kb).getValue(Muscle.CHEST).fractional)
    }

    @Test
    fun sessionsCountDistinctSessionIds() {
        val sets = listOf(set("barbell_bench_press", 1, session = "a"), set("barbell_bench_press", 1, session = "b"))
        assertEquals(2, VolumeAccountant.weekly(sets, kb).getValue(Muscle.CHEST).sessions)
    }

    @Test
    fun backAggregateCountsASetOnceAtTheLargerWeight() {
        val sets = (1..3).map { set("barbell_row", it) }
        val exact = VolumeAccountant.aggregatesOfSets(sets, kb).getValue(VolumeAccountant.BACK)
        assertEquals(3.0, exact.fractional)
        assertEquals(3, exact.direct)
        val approx = VolumeAccountant.aggregates(VolumeAccountant.weekly(sets, kb)).getValue(VolumeAccountant.BACK)
        assertEquals(3.0, approx.fractional)
        val delts = VolumeAccountant.aggregates(VolumeAccountant.weekly(listOf(set("overhead_press_barbell", 1)), kb)).getValue(VolumeAccountant.DELTS)
        assertEquals(1.5, delts.fractional)
    }

    @Test
    fun plannedAndRealizedSnapshotsMatchTheE1UpperA() {
        val week = PlannerFixtures.e1Week()
        val upperA = week.sessions.first()
        val snap = VolumeAccountant.ofPlanned(upperA, kb)
        // §4.17 credit arithmetic: UPPER_A chest 6, front_delts 5.5, triceps 3.5, lats 6, upper_back 5.5, biceps 5, rear_delts 4.25.
        assertEquals(6.0, snap.fractional[Muscle.CHEST])
        assertEquals(5.5, snap.fractional[Muscle.FRONT_DELTS])
        assertEquals(3.5, snap.fractional[Muscle.TRICEPS])
        assertEquals(6.0, snap.fractional[Muscle.LATS])
        assertEquals(5.5, snap.fractional[Muscle.UPPER_BACK])
        assertEquals(5.0, snap.fractional[Muscle.BICEPS])
        assertEquals(4.25, snap.fractional[Muscle.REAR_DELTS])
        assertEquals(0.75, snap.fractional[Muscle.LOWER_BACK])
        assertEquals(5.5, snap.fractional[Muscle.SIDE_DELTS])
        assertFalse(snap.fractional.containsKey(Muscle.QUADS))
    }
}
