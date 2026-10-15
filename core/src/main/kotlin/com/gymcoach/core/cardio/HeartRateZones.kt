// core/src/main/kotlin/com/gymcoach/core/cardio/HeartRateZones.kt
package com.gymcoach.core.cardio

data class HrZone(val lo: Int, val hi: Int)

object HeartRateZones {
    /** CAR-03 Tanaka; a measured max wins. */
    fun hrMax(age: Int, measured: Int?): Double = measured?.toDouble() ?: (208.0 - 0.7 * age)
    fun pctOfMax(hrMax: Double, lo: Double, hi: Double): HrZone = HrZone(Math.round(hrMax * lo).toInt(), Math.round(hrMax * hi).toInt())
    /** Karvonen: rest + fraction × (max − rest). */
    fun karvonen(hrMax: Double, restingHr: Int, lo: Double, hi: Double): HrZone {
        val reserve = hrMax - restingHr
        return HrZone(Math.round(restingHr + lo * reserve).toInt(), Math.round(restingHr + hi * reserve).toInt())
    }
    fun zone2ByMax(hrMax: Double): HrZone = pctOfMax(hrMax, 0.60, 0.70)            // CAR-02
    fun zone2ByReserve(hrMax: Double, restingHr: Int): HrZone = karvonen(hrMax, restingHr, 0.40, 0.59)
    fun vigorousByMax(hrMax: Double): HrZone = pctOfMax(hrMax, 0.77, 0.95)         // CAR-04
    fun hiit4x4Work(hrMax: Double): HrZone = pctOfMax(hrMax, 0.90, 0.95)           // CAR-05
}
