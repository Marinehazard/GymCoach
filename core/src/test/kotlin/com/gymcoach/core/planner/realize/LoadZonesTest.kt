package com.gymcoach.core.planner.realize

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Band
import com.gymcoach.core.model.LoadZone
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.progression.E1rm
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoadZonesTest {
    private val c = PlannerConstants.DEFAULT

    @Test
    fun midpointConvention_LOAD04() {
        assertTrue(RuleIds.LOAD_04 in RuleIds.ALL)
        assertEquals(LoadZone.HEAVY, LoadZones.of(Band(5, 10), 3, false, false, null, c))       // bench 5–10, m 7.5
        assertEquals(LoadZone.MODERATE, LoadZones.of(Band(8, 12), 2, false, false, null, c))    // row, m 10
        assertEquals(LoadZone.MODERATE, LoadZones.of(Band(10, 20), 1, false, false, null, c))   // m 15 is the MODERATE edge (8 < m ≤ 15)
        assertEquals(LoadZone.LIGHT, LoadZones.of(Band(12, 20), 1, false, false, null, c))      // lateral raise (isolation_light 12–20), m 16
        assertEquals(LoadZone.MAX_STRENGTH, LoadZones.of(Band(3, 6), 2, false, false, null, c)) // m 4.5
    }

    @Test
    fun heavySlotForcesAtLeastHeavyAndPowerIsPower() {
        assertEquals(LoadZone.HEAVY, LoadZones.of(Band(8, 12), 2, true, false, null, c))
        assertEquals(LoadZone.MAX_STRENGTH, LoadZones.of(Band(3, 6), 2, true, false, null, c))
        assertEquals(LoadZone.POWER, LoadZones.of(Band(5, 8), 3, false, true, null, c))
    }

    @Test
    fun highConfidenceE1rmUsesPercentCutPoints() {
        val e = checkNotNull(E1rm.estimate(100.0, 5, 2, LocalDate.of(2026, 9, 1)))
        assertEquals(LoadZone.MAX_STRENGTH, LoadZones.of(Band(3, 6), 1, false, false, e, c))   // pct(4) = 0.88
        assertEquals(LoadZone.HEAVY, LoadZones.of(Band(5, 10), 2, false, false, e, c))         // pct(7) = 0.81
        assertEquals(LoadZone.MODERATE, LoadZones.of(Band(8, 12), 2, false, false, e, c))      // pct(10) = 0.75
        assertEquals(LoadZone.MODERATE, LoadZones.of(Band(15, 25), 1, false, false, e, c))     // pct(16) clamps to 0.67 ≥ 0.65
        val low = checkNotNull(E1rm.estimate(60.0, 12, 1, LocalDate.of(2026, 9, 1)))         // LOW confidence → midpoint rule
        assertEquals(LoadZone.HEAVY, LoadZones.of(Band(5, 10), 2, false, false, low, c))
    }
}
