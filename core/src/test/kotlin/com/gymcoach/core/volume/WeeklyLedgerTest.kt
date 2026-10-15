package com.gymcoach.core.volume

import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MuscleTier
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SessionStatus
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.planner.PlannerFixtures
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeeklyLedgerTest {
    private val kb = PlannerFixtures.kb
    private val week = PlannerFixtures.e1Week()

    @Test
    fun e1LedgerTableToTwoDecimals() {
        val l = WeeklyLedger.build(week, emptyList(), emptyList(), null, kb)
        val expected = mapOf(
            Muscle.CHEST to 12.0, Muscle.LATS to 13.0, Muscle.UPPER_BACK to 13.0, Muscle.QUADS to 13.0, Muscle.HAMSTRINGS to 13.0,
            Muscle.GLUTES to 15.0, Muscle.SIDE_DELTS to 11.0, Muscle.BICEPS to 8.5, Muscle.TRICEPS to 7.0, Muscle.REAR_DELTS to 8.75,
            Muscle.CALVES to 5.0, Muscle.ABS to 4.25, Muscle.FRONT_DELTS to 11.0, Muscle.LOWER_BACK to 6.25, Muscle.ADDUCTORS to 4.5,
            Muscle.OBLIQUES to 3.0, Muscle.FOREARMS to 1.5, Muscle.TRAPS_UPPER to 2.0, Muscle.ROTATOR_CUFF to 1.0, Muscle.HIP_FLEXORS to 0.5,
        )
        for ((m, v) in expected) assertEquals(v, l.plannedRemaining[m] ?: 0.0, "planned ${m.name}")
        // Direct counts of the same table.
        val direct = VolumeAccountant.snapshotOf(week.sessions.flatMap { s -> s.slots.map { it.exerciseId to it.sets.toDouble() } }, kb).direct
        assertEquals(12, direct[Muscle.CHEST]); assertEquals(13, direct[Muscle.LATS]); assertEquals(6, direct[Muscle.UPPER_BACK])
        assertEquals(11, direct[Muscle.QUADS]); assertEquals(9, direct[Muscle.HAMSTRINGS]); assertEquals(5, direct[Muscle.GLUTES])
        assertEquals(8, direct[Muscle.SIDE_DELTS]); assertEquals(2, direct[Muscle.BICEPS]); assertNull(direct[Muscle.TRICEPS])
        assertEquals(4, direct[Muscle.REAR_DELTS]); assertEquals(5, direct[Muscle.CALVES]); assertEquals(2, direct[Muscle.ABS])
        // Under-target report of E1: side_delts −1.0, calves −2.0, abs −2.75.
        assertEquals(mapOf(Muscle.SIDE_DELTS to -1.0, Muscle.ABS to -2.75, Muscle.CALVES to -2.0), l.carryover)
        assertEquals(1.0, l.deficit(Muscle.SIDE_DELTS))
        assertEquals(0.0, l.deficit(Muscle.CHEST))
    }

    @Test
    fun loggedSessionsReplaceTheirPlannedVolume() {
        val upperA = week.sessions.first()
        val rec = SessionRecord("rec1", PlannerFixtures.MONDAY, PlannerFixtures.HOME_ID, plannedSessionId = upperA.id, status = SessionStatus.COMPLETED)
        val sets = (1..3).map { SetLog("x$it", "rec1", "s1:barbell_bench_press", "barbell_bench_press", it, SetKind.WORKING, reps = 8, rir = 2, loggedAt = Instant.parse("2026-09-07T18:00:00Z")) }
        val l = WeeklyLedger.build(week, sets, listOf(rec), null, kb)
        assertEquals(3.0, l.achieved.getValue(Muscle.CHEST).fractional)
        assertEquals(6.0, l.plannedRemaining[Muscle.CHEST])     // UPPER_B only
        assertEquals(-3.0, l.carryover[Muscle.CHEST])
        assertEquals(3.0, l.deficit(Muscle.CHEST))
    }

    @Test
    fun carryoverNeverCrossesAWeekBoundary() {
        val old = SetLog("o1", "old", null, "barbell_bench_press", 1, SetKind.WORKING, reps = 8, loggedAt = Instant.parse("2026-08-31T18:00:00Z"))
        val rec = SessionRecord("old", PlannerFixtures.MONDAY.minusDays(7), PlannerFixtures.HOME_ID, status = SessionStatus.COMPLETED)
        val l = WeeklyLedger.build(week, listOf(old), listOf(rec), null, kb)
        assertTrue(l.achieved.isEmpty())
        val orphan = old.copy(id = "o2", sessionId = "unknown")
        assertTrue(WeeklyLedger.build(week, listOf(orphan), emptyList(), null, kb).achieved.isEmpty())
    }

    @Test
    fun rowsAreInTierOrderWithLandmarks() {
        val l = WeeklyLedger.build(week, emptyList(), emptyList(), null, kb)
        val rows = l.rows(MuscleTiers.assign(emptyList()), kb)
        assertEquals(Muscle.entries.size, rows.size)
        assertEquals(MuscleTier.TIER_1, rows.first().tier)
        assertEquals(MuscleTier.TIER_3, rows.last().tier)
        val chest = rows.first { it.muscle == Muscle.CHEST }
        assertEquals(12.0, chest.target)
        assertEquals(12.0, chest.plannedRemaining)
        assertTrue(chest.rpMrv != null)
        assertNull(rows.first { it.muscle == Muscle.FRONT_DELTS }.target)
    }
}
