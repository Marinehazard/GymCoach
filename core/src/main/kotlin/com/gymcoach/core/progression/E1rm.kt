// core/src/main/kotlin/com/gymcoach/core/progression/E1rm.kt (copied verbatim by WP-01)
package com.gymcoach.core.progression

import com.gymcoach.core.model.E1rmConfidence
import com.gymcoach.core.model.E1rmEstimate
import java.time.LocalDate

object E1rm {
    fun epley(w: Double, r: Int): Double = w * (1.0 + r / 30.0)
    fun brzycki(w: Double, r: Int): Double = w * 36.0 / (37.0 - r)

    /**
     * E1RM-01/02/03: r_eff = min(reps + rir, maxReps); mean of Epley and Brzycki at r_eff.
     * Confidence HIGH for 1 ≤ reps ≤ maxReps (≈ 3 % error, LeSuer 1997); LOW above maxReps (displayed, never used for decisions).
     * Returns null for reps < 1 or w ≤ 0.
     */
    fun estimate(w: Double, reps: Int, rir: Int?, date: LocalDate, maxReps: Int = 10): E1rmEstimate? {
        if (reps < 1 || w <= 0.0) return null
        val rEff = minOf(reps + (rir ?: 0), maxReps)
        val kg = (epley(w, rEff) + brzycki(w, rEff)) / 2.0
        val confidence = if (reps <= maxReps) E1rmConfidence.HIGH else E1rmConfidence.LOW
        return E1rmEstimate(kg = round1(kg), fromWeightKg = w, fromReps = reps, fromRir = rir, confidence = confidence, date = date)
    }

    /** E1RM-03 %1RM table: 1:100 2:94 3:91 4:88 5:86 6:83 8:79 10:75 12:71 15:67; linear interpolation between rows; clamped outside 1..15. */
    private val table: List<Pair<Int, Double>> = listOf(
        1 to 100.0, 2 to 94.0, 3 to 91.0, 4 to 88.0, 5 to 86.0, 6 to 83.0, 8 to 79.0, 10 to 75.0, 12 to 71.0, 15 to 67.0,
    )

    fun pctOfMaxForReps(reps: Int): Double {
        if (reps <= table.first().first) return table.first().second / 100.0
        if (reps >= table.last().first) return table.last().second / 100.0
        for (i in 0 until table.size - 1) {
            val (r0, p0) = table[i]
            val (r1, p1) = table[i + 1]
            if (reps == r0) return p0 / 100.0
            if (reps in (r0 + 1) until r1) return (p0 + (p1 - p0) * (reps - r0).toDouble() / (r1 - r0).toDouble()) / 100.0
            if (reps == r1) return p1 / 100.0
        }
        return table.last().second / 100.0
    }

    /** Load for a target rep count from an e1RM (E1RM-03): e1rm × pct(reps + rir). */
    fun loadFor(e1rmKg: Double, reps: Int, rir: Int): Double = e1rmKg * pctOfMaxForReps(reps + rir)

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
}
