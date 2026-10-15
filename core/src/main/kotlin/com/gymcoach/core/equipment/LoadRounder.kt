// core/src/main/kotlin/com/gymcoach/core/equipment/LoadRounder.kt
package com.gymcoach.core.equipment

import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.IncrementDirection
import com.gymcoach.core.model.LoadType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

data class RoundedLoad(val kg: Double, val label: String, val roundingErrorPct: Double, val perHand: Boolean)

/**
 * Rounds a desired load to what the gym can actually load (bar + plate pairs, dumbbell rack step, stack step/half step,
 * kettlebell sizes, assist stacks). Nearest achievable wins; on a tie the lower load (SPEC §3.9). Labels are in kg.
 * Basis of [round]'s `desiredKg`: the TOTAL load (pair total for a bilateral dumbbell/kettlebell pair, per hand for a
 * single implement); external load for BODYWEIGHT; assistance for assisted machines; the 0-based level index for BAND.
 */
class LoadRounder(private val kb: KnowledgeBase) {
    private val increments = IncrementResolver(kb)

    fun round(desiredKg: Double, exercise: Exercise, effectiveLoadType: LoadType, params: GymParams): RoundedLoad {
        val desired = if (desiredKg.isNaN() || desiredKg < 0.0) 0.0 else desiredKg
        if (exercise.increment.direction == IncrementDirection.ASSIST) {
            val machine = Implements.stackEquipment(kb, exercise, effectiveLoadType, params) ?: IncrementResolver.ASSIST_MACHINE
            val kg = stackValue(desired, params.stackStepKg(machine) ?: IncrementResolver.DEFAULT_STACK_STEP_KG, params.halfStepKg(machine), allowZero = true)
            return result(kg, desired, "assist ${Implements.fmt(kg)} kg", false)
        }
        return when (effectiveLoadType) {
            LoadType.BARBELL, LoadType.SMITH -> barbell(desired, exercise, effectiveLoadType, params)
            LoadType.DUMBBELL -> {
                val n = increments.implements(exercise, effectiveLoadType)
                val min = params.dumbbellMinKg ?: DEFAULT_DB_MIN_KG
                val step = params.dumbbellStepKg ?: IncrementResolver.DEFAULT_DUMBBELL_STEP_KG
                val perImplement = sequence(desired / n, min, step, params.dumbbellMaxKg)
                if (n == 2) result(perImplement * 2, desired, "2×${Implements.fmt(perImplement)} kg", false)
                else result(perImplement, desired, "${Implements.fmt(perImplement)} kg", true)
            }
            LoadType.KETTLEBELL -> {
                val n = increments.implements(exercise, effectiveLoadType)
                val bells = params.kettlebellsKg ?: DEFAULT_KETTLEBELLS
                val bell = nearest(desired / n, bells)
                if (n == 2) result(bell * 2, desired, "2×${Implements.fmt(bell)} kg", false)
                else result(bell, desired, "${Implements.fmt(bell)} kg", true)
            }
            LoadType.MACHINE, LoadType.CABLE -> {
                val machine = Implements.stackEquipment(kb, exercise, effectiveLoadType, params)
                if (machine != null && params.bool(machine, "plate_loaded") == true) plateLoaded(desired, params, params.number(machine, "sled_kg"))
                else {
                    val stack = machine?.let { params.stackStepKg(it) } ?: IncrementResolver.DEFAULT_STACK_STEP_KG
                    val kg = stackValue(desired, stack, machine?.let { params.halfStepKg(it) }, allowZero = false)
                    result(kg, desired, "stack ${Implements.fmt(kg)} kg", false)
                }
            }
            LoadType.SLED -> plateLoaded(desired, params, params.number("sled", "sled_kg"))
            LoadType.BODYWEIGHT -> {
                val step = 2.0 * (params.smallestPlateKg ?: IncrementResolver.DEFAULT_SMALLEST_PLATE_KG)
                val kg = sequence(desired, 0.0, step, null)
                result(kg, desired, if (kg <= 0.0) "BW" else "BW+${Implements.fmt(kg)} kg", false)
            }
            LoadType.BAND -> {
                val levels = params.bandLevels() ?: DEFAULT_BAND_LEVELS
                val idx = desired.roundToInt().coerceIn(0, levels.size - 1)
                RoundedLoad(idx.toDouble(), "band: ${levels[idx]}", 0.0, false)
            }
            LoadType.MEDICINE_BALL -> {
                val balls = params.numbers("medicine_balls", "available_kg")
                if (balls.isNullOrEmpty()) result(desired, desired, "${Implements.fmt(desired)} kg", false)
                else nearest(desired, balls).let { result(it, desired, "${Implements.fmt(it)} kg ball", false) }
            }
            LoadType.CARDIO, LoadType.NONE -> RoundedLoad(desired, "", 0.0, false)
        }
    }

    /** Total load ceiling: dumbbell/kettlebell max × IncrementResolver.implements(exercise) (2 × max for a bilateral pair, 1 × max for unilateral or single-implement); stack max; plates unlimited unless the gym says otherwise; null = unlimited. */
    fun achievableMaxKg(exercise: Exercise, effectiveLoadType: LoadType, params: GymParams): Double? {
        val n = increments.implements(exercise, effectiveLoadType)
        return when (effectiveLoadType) {
            LoadType.DUMBBELL -> params.dumbbellMaxKg?.let { it * n }
            LoadType.KETTLEBELL -> params.kettlebellsKg?.maxOrNull()?.let { it * n }
            LoadType.BARBELL -> if (Implements.barEquipment(kb, exercise, effectiveLoadType, params) == Implements.FIXED_BARBELLS) params.number(Implements.FIXED_BARBELLS, "max_kg") else null
            LoadType.MEDICINE_BALL -> params.numbers("medicine_balls", "available_kg")?.maxOrNull()
            else -> null
        }
    }

    private fun barbell(desired: Double, exercise: Exercise, lt: LoadType, params: GymParams): RoundedLoad {
        val barId = Implements.barEquipment(kb, exercise, lt, params)
        if (barId == Implements.FIXED_BARBELLS) {
            val step = params.number(barId, "step_kg") ?: 2.5
            val kg = sequence(desired, step, step, params.number(barId, "max_kg"))
            return result(kg, desired, "${Implements.fmt(kg)} kg", false)
        }
        val bar = when (barId) {
            null -> if (lt == LoadType.SMITH) DEFAULT_SMITH_BAR_KG else params.barKg
            else -> params.number(barId, "bar_kg") ?: if (lt == LoadType.SMITH) DEFAULT_SMITH_BAR_KG else params.barKg
        }
        val step = if (params.hasMicroplates) 2.0 * IncrementResolver.MICROPLATE_KG else 2.0 * (params.smallestPlateKg ?: IncrementResolver.DEFAULT_SMALLEST_PLATE_KG)
        val kg = sequence(desired, bar, step, null)
        return result(kg, desired, "${Implements.fmt(kg)} kg", false)
    }

    private fun plateLoaded(desired: Double, params: GymParams, sledKg: Double?): RoundedLoad {
        val step = 2.0 * (params.smallestPlateKg ?: IncrementResolver.DEFAULT_SMALLEST_PLATE_KG)
        val kg = sequence(desired, 0.0, step, null)
        val sledNote = if (sledKg != null && sledKg > 0.0) " (+${Implements.fmt(sledKg)} kg sled)" else ""
        return result(kg, desired, "${Implements.fmt(kg)} kg on the sled$sledNote", false)
    }

    /** k × stack (+ half if present); k ≥ 1 unless [allowZero]. */
    private fun stackValue(desired: Double, stack: Double, half: Double?, allowZero: Boolean): Double {
        val candidates = ArrayList<Double>()
        val kMax = ceil(desired / stack).toInt() + 1
        for (k in (if (allowZero) 0 else 1)..maxOf(kMax, 1)) {
            candidates.add(k * stack)
            if (half != null && half > 0.0) candidates.add(k * stack + half)
        }
        return nearest(desired, candidates)
    }

    /** base + k × step (k ≥ 0), capped at [max] when given; never below base. */
    private fun sequence(desired: Double, base: Double, step: Double, max: Double?): Double {
        if (step <= 0.0) return base
        val kMax = if (max != null) floor((max - base) / step + 1e-9).toInt().coerceAtLeast(0) else Int.MAX_VALUE
        val exact = (desired - base) / step
        val lo = floor(exact + 1e-9).toInt().coerceIn(0, kMax)
        val hi = ceil(exact - 1e-9).toInt().coerceIn(0, kMax)
        val a = clean(base + lo * step)
        val b = clean(base + hi * step)
        return if (abs(desired - a) <= abs(b - desired) + 1e-9) a else b
    }

    private fun nearest(desired: Double, values: List<Double>): Double {
        var best = values.first()
        for (v in values.sorted()) if (abs(v - desired) < abs(best - desired) - 1e-9 || (abs(abs(v - desired) - abs(best - desired)) <= 1e-9 && v < best)) best = v
        return clean(best)
    }

    private fun clean(x: Double): Double = Math.round(x * 1000.0) / 1000.0

    private fun result(kg: Double, desired: Double, label: String, perHand: Boolean): RoundedLoad {
        val err = if (desired > 0.0) abs(kg - desired) / desired * 100.0 else 0.0
        return RoundedLoad(kg, label, err, perHand)
    }

    companion object {
        const val DEFAULT_DB_MIN_KG: Double = 2.0
        const val DEFAULT_SMITH_BAR_KG: Double = 10.0
        /** Above this rounding error the caller appends an Explanation(ROUNDING) (SPEC §3.9). */
        const val ROUNDING_WARN_PCT: Double = 5.0
        val DEFAULT_KETTLEBELLS: List<Double> = listOf(8.0, 12.0, 16.0, 20.0, 24.0, 28.0, 32.0)
        val DEFAULT_BAND_LEVELS: List<String> = listOf("light", "medium", "heavy", "x-heavy")
    }
}
