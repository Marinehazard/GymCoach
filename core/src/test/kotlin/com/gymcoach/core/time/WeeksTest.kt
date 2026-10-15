package com.gymcoach.core.time

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** ISO week arithmetic (§2.12). 2026-09-05 is a Saturday. */
class WeeksTest {

    private val saturday = LocalDate.of(2026, 9, 5)
    private val monday = LocalDate.of(2026, 8, 31)
    private val sunday = LocalDate.of(2026, 9, 6)

    @Test fun fixtureDatesAreWhatWeThink() {
        assertEquals(DayOfWeek.SATURDAY, saturday.dayOfWeek)
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, sunday.dayOfWeek)
    }

    @Test fun weekStartMondayDefault() = assertEquals(monday, Weeks.weekStart(saturday))
    @Test fun weekStartMondayOnMondayIsSameDay() = assertEquals(monday, Weeks.weekStart(monday, mondayStart = true))
    @Test fun weekStartMondayOnSundayGoesBackSixDays() = assertEquals(monday, Weeks.weekStart(sunday, mondayStart = true))
    @Test fun weekStartSundayMode() = assertEquals(LocalDate.of(2026, 8, 30), Weeks.weekStart(saturday, mondayStart = false))
    @Test fun weekStartSundayModeOnSundayIsSameDay() = assertEquals(sunday, Weeks.weekStart(sunday, mondayStart = false))
    @Test fun weekStartSundayModeOnMondayGoesBackOneDay() = assertEquals(LocalDate.of(2026, 8, 30), Weeks.weekStart(monday, mondayStart = false))

    @Test fun weekStartAcrossYearBoundary() {
        // 2027-01-01 is a Friday; its ISO week starts on Monday 2026-12-28.
        assertEquals(LocalDate.of(2026, 12, 28), Weeks.weekStart(LocalDate.of(2027, 1, 1)))
    }

    @Test fun daysBetween() {
        assertEquals(5L, Weeks.daysBetween(monday, saturday))
        assertEquals(-5L, Weeks.daysBetween(saturday, monday))
        assertEquals(0L, Weeks.daysBetween(saturday, saturday))
        assertEquals(366L, Weeks.daysBetween(LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1)))
    }

    @Test fun isoWeekday() {
        assertEquals(1, Weeks.isoWeekday(monday))
        assertEquals(6, Weeks.isoWeekday(saturday))
        assertEquals(7, Weeks.isoWeekday(sunday))
    }

    @Test fun dateForWeekday() {
        assertEquals(monday, Weeks.dateFor(monday, 1))
        assertEquals(saturday, Weeks.dateFor(monday, 6))
        assertEquals(sunday, Weeks.dateFor(monday, 7))
    }

    @Test fun dateForRoundTripsWithIsoWeekday() {
        for (d in 0L until 14L) {
            val date = monday.plusDays(d)
            assertEquals(date, Weeks.dateFor(Weeks.weekStart(date), Weeks.isoWeekday(date)))
        }
    }
}
