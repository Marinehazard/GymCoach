// core/src/main/kotlin/com/gymcoach/core/time/Clock.kt
package com.gymcoach.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

interface Clock {
    fun now(): Instant
    fun today(): LocalDate
    fun zone(): ZoneId
}

class SystemClock : Clock {
    override fun now(): Instant = Instant.now()
    override fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

class FixedClock(private val instant: Instant, private val zoneId: ZoneId = ZoneId.of("Europe/Berlin")) : Clock {
    override fun now(): Instant = instant
    override fun today(): LocalDate = instant.atZone(zoneId).toLocalDate()
    override fun zone(): ZoneId = zoneId
}
