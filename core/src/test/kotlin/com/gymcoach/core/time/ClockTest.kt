package com.gymcoach.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClockTest {

    @Test fun fixedClockReturnsTheInstant() {
        val i = Instant.parse("2026-09-05T06:40:00Z")
        val c = FixedClock(i)
        assertEquals(i, c.now())
        assertEquals(i, c.now())
    }

    @Test fun fixedClockDefaultZoneIsBerlin() {
        assertEquals(ZoneId.of("Europe/Berlin"), FixedClock(Instant.parse("2026-09-05T06:40:00Z")).zone())
    }

    @Test fun fixedClockTodayUsesTheZone() {
        // 23:30 UTC on 5 Sep is already 6 Sep in Berlin (UTC+2 in summer) but still 5 Sep in UTC.
        val late = Instant.parse("2026-09-05T23:30:00Z")
        assertEquals(LocalDate.of(2026, 9, 6), FixedClock(late).today())
        assertEquals(LocalDate.of(2026, 9, 5), FixedClock(late, ZoneId.of("UTC")).today())
        assertEquals(LocalDate.of(2026, 9, 5), FixedClock(late, ZoneId.of("America/New_York")).today())
    }

    @Test fun fixedClockTodayInWinterOffset() {
        val i = Instant.parse("2026-01-10T23:30:00Z")
        assertEquals(LocalDate.of(2026, 1, 11), FixedClock(i).today())
    }

    @Test fun systemClockIsConsistentWithItsZone() {
        val c = SystemClock()
        assertEquals(ZoneId.systemDefault(), c.zone())
        val before = Instant.now()
        val now = c.now()
        val after = Instant.now()
        assertTrue(!now.isBefore(before) && !now.isAfter(after))
        val today = c.today()
        assertTrue(today == now.atZone(c.zone()).toLocalDate() || today == after.atZone(c.zone()).toLocalDate())
    }

    @Test fun clockInterfaceIsImplementedByBoth() {
        val clocks: List<Clock> = listOf(SystemClock(), FixedClock(Instant.EPOCH))
        assertEquals(2, clocks.size)
        assertEquals(LocalDate.of(1970, 1, 1), clocks[1].today())
    }
}
