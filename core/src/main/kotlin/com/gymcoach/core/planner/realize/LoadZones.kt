package com.gymcoach.core.planner.realize

import com.gymcoach.core.model.Band
import com.gymcoach.core.model.E1rmConfidence
import com.gymcoach.core.model.E1rmEstimate
import com.gymcoach.core.model.LoadZone
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.progression.E1rm

/**
 * LOAD-04 zones (§4.6) — one convention for WP-03 and WP-04: the rep-band midpoint m (≤ 5 MAX_STRENGTH, ≤ 8 HEAVY, ≤ 15
 * MODERATE, else LIGHT), or, when a HIGH-confidence e1RM exists, %1RM = pct(lo + targetRir) against the LOAD-04 cut-points.
 * A heavy slot forces at least HEAVY; the power profile is POWER. (MAX_STRENGTH → HEAVY under RED-03/AGE-03 is the caller's.)
 */
object LoadZones {
    fun of(band: Band, targetRir: Int, slotHeavy: Boolean, isPowerProfile: Boolean, e1rm: E1rmEstimate?, c: PlannerConstants): LoadZone {
        if (isPowerProfile) return LoadZone.POWER
        val p = c.prog
        val zone = if (e1rm != null && e1rm.confidence == E1rmConfidence.HIGH) {
            val pct = E1rm.pctOfMaxForReps(band.lo + targetRir)
            when {
                pct >= p.load04MaxStrengthPct - 1e-9 -> LoadZone.MAX_STRENGTH
                pct >= p.load04HeavyPct - 1e-9 -> LoadZone.HEAVY
                pct >= p.load04ModeratePct - 1e-9 -> LoadZone.MODERATE
                else -> LoadZone.LIGHT
            }
        } else {
            val m = (band.lo + band.hi) / 2.0
            when {
                m <= p.load04MaxStrengthRepsMax -> LoadZone.MAX_STRENGTH
                m <= p.load04HeavyRepsMax -> LoadZone.HEAVY
                m <= p.load04ModerateRepsMax -> LoadZone.MODERATE
                else -> LoadZone.LIGHT
            }
        }
        if (slotHeavy && (zone == LoadZone.MODERATE || zone == LoadZone.LIGHT)) return LoadZone.HEAVY
        return zone
    }

    fun isHeavy(z: LoadZone): Boolean = when (z) {
        LoadZone.MAX_STRENGTH, LoadZone.HEAVY -> true
        LoadZone.MODERATE, LoadZone.LIGHT, LoadZone.POWER -> false
    }
}
