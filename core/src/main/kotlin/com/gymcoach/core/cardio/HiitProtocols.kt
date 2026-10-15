// core/src/main/kotlin/com/gymcoach/core/cardio/HiitProtocols.kt
package com.gymcoach.core.cardio

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.CardioKind
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Explanation

data class HiitProtocol(val kind: CardioKind, val intervals: Int, val workSec: Int, val restSec: Int, val workHr: HrZone, val restHr: HrZone?, val explanation: Explanation)

/**
 * CAR-05 menu and CAR-09 progression ("add one interval before raising intensity"): `intervalsCompleted` is the
 * interval count of the last completed session of this protocol (0 = never done) → next count = that + 1, inside the
 * protocol's range. SIT is ADVANCED-only; otherwise the default 4 × 4 is returned with a note. ZONE2/WALK return one
 * continuous zone-2 bout.
 */
object HiitProtocols {

    fun protocol(kind: CardioKind, hrMax: Double, intervalsCompleted: Int, advanced: Boolean): HiitProtocol = when (kind) {
        CardioKind.HIIT_4X4 -> fourByFour(hrMax, intervalsCompleted, null)
        CardioKind.HIIT_10X1 -> {
            val n = next(intervalsCompleted, 10, 12)
            HiitProtocol(kind, n, 60, 75, HeartRateZones.pctOfMax(hrMax, 0.88, 0.92), HeartRateZones.pctOfMax(hrMax, 0.60, 0.70),
                Explanations.of(listOf(RuleIds.CAR_05, RuleIds.CAR_09), "$n × 1 min at about 90 % HRmax, 60–75 s easy between (up to 12 intervals before raising intensity)",
                    mapOf("intervals" to n.toString(), "hr_max" to fmt(hrMax)), Confidence.MODERATE))
        }
        CardioKind.SIT -> if (!advanced) fourByFour(hrMax, intervalsCompleted, "sprint intervals are for advanced trainees: 4 × 4 instead") else {
            val n = next(intervalsCompleted, 4, 6)
            HiitProtocol(kind, n, 30, 240, HeartRateZones.pctOfMax(hrMax, 0.95, 1.00), null,
                Explanations.of(listOf(RuleIds.CAR_05, RuleIds.CAR_09), "$n × 30 s all-out, 4 min easy between",
                    mapOf("intervals" to n.toString(), "hr_max" to fmt(hrMax)), Confidence.MODERATE))
        }
        CardioKind.ZONE2, CardioKind.WALK -> HiitProtocol(kind, 1, ZONE2_DEFAULT_SEC, 0, HeartRateZones.zone2ByMax(hrMax), null,
            Explanations.of(listOf(RuleIds.CAR_02), "Continuous zone 2: 60–70 % HRmax, you can still talk in full sentences (RPE 3–4/10)",
                mapOf("hr_max" to fmt(hrMax)), Confidence.MODERATE))
    }

    private fun fourByFour(hrMax: Double, done: Int, note: String?): HiitProtocol {
        val n = next(done, 4, 6)
        val work = HeartRateZones.hiit4x4Work(hrMax)
        val rest = HeartRateZones.pctOfMax(hrMax, 0.70, 0.70)
        val msg = "$n × 4 min at ${work.lo}–${work.hi} bpm (90–95 % HRmax), 3 min recovery at about ${rest.lo} bpm (~70 %)" + (note?.let { " — $it" } ?: "")
        return HiitProtocol(CardioKind.HIIT_4X4, n, 240, 180, work, rest,
            Explanations.of(listOf(RuleIds.CAR_05, RuleIds.CAR_09), msg, mapOf("intervals" to n.toString(), "hr_max" to fmt(hrMax)), Confidence.MODERATE))
    }

    private fun next(done: Int, min: Int, max: Int): Int = if (done < min) min else minOf(max, done + 1)

    private fun fmt(x: Double): String = String.format(java.util.Locale.ROOT, "%.1f", x)

    private const val ZONE2_DEFAULT_SEC: Int = 30 * 60
}
