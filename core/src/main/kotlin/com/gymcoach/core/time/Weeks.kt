// core/src/main/kotlin/com/gymcoach/core/time/Weeks.kt
package com.gymcoach.core.time

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object Weeks {
    fun weekStart(date: LocalDate, mondayStart: Boolean = true): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(if (mondayStart) DayOfWeek.MONDAY else DayOfWeek.SUNDAY))
    fun daysBetween(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)
    /** ISO weekday 1..7 of a date. */
    fun isoWeekday(date: LocalDate): Int = date.dayOfWeek.value
    fun dateFor(weekStart: LocalDate, isoWeekday: Int): LocalDate = weekStart.plusDays((isoWeekday - 1).toLong())
}
