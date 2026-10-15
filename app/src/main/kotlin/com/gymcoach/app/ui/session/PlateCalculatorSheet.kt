@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gymcoach.app.R
import com.gymcoach.app.ui.components.PlateDiagram
import com.gymcoach.core.equipment.GymParams
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.ExerciseBlock
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.units.Units

// Pure plate arithmetic (JVM-testable) above the UI marker.

/** What the sheet knows about today's gym for the block's implement. */
data class PlateContext(
    val loadType: LoadType, val smallestPlateKg: Double, val microplates: Boolean, val dumbbellMinKg: Double?, val dumbbellMaxKg: Double?,
    val dumbbellStepKg: Double?, val stackStepKg: Double?, val halfStepKg: Double?,
)

data class PlateResult(val barKg: Double, val perSide: List<Double>, val achievedKg: Double, val exact: Boolean, val belowKg: Double, val aboveKg: Double)

object PlateMath {
    private val STANDARD = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25)
    private val MICRO = listOf(0.5, 0.25)

    /** §8.8: standard plates from the gym's smallest plate upward; microplates add 0.5 and 0.25. Descending. */
    fun inventory(smallestPlateKg: Double, microplates: Boolean): List<Double> {
        val base = STANDARD.filter { it >= smallestPlateKg - 1e-9 }
        return (if (microplates) base + MICRO else base).sortedDescending()
    }

    fun context(kb: KnowledgeBase, eq: SessionEquipment?, block: ExerciseBlock?): PlateContext {
        val lt = block?.effectiveLoadType ?: LoadType.BARBELL
        if (eq == null) return PlateContext(lt, 1.25, false, null, null, null, null, null)
        val p = GymParams(kb, eq)
        val machine = block?.let { b -> kb.exercise(b.exerciseId)?.equipmentOptions?.flatMap { it.allOf }?.firstOrNull { p.stackStepKg(it) != null } }
        return PlateContext(lt, p.smallestPlateKg ?: 1.25, p.hasMicroplates, p.dumbbellMinKg, p.dumbbellMaxKg, p.dumbbellStepKg,
            machine?.let { p.stackStepKg(it) }, machine?.let { p.halfStepKg(it) })
    }

    /** Greedy per side. [belowKg] is the load actually loadable at or below the target, [aboveKg] the next step up. Nothing is applied here. */
    fun load(targetKg: Double, barKg: Double, inventory: List<Double>): PlateResult {
        var remaining = maxOf(0.0, (targetKg - barKg) / 2.0)
        val plates = ArrayList<Double>()
        for (plate in inventory) {
            while (remaining + 1e-9 >= plate) { plates.add(plate); remaining -= plate }
        }
        val achieved = barKg + 2.0 * plates.sum()
        val smallest = inventory.minOrNull() ?: 1.25
        val exact = kotlin.math.abs(achieved - targetKg) < 1e-6
        return PlateResult(barKg, plates, achieved, exact, achieved, if (exact) achieved else achieved + 2.0 * smallest)
    }

    /** Dumbbell rounder: the nearest rack weights at or below / above [perHandKg]. */
    fun rack(perHandKg: Double, min: Double, max: Double, step: Double): Pair<Double, Double> {
        val s = if (step > 0.0) step else 1.0
        val below = (min + Math.floor((perHandKg - min) / s + 1e-9) * s).coerceIn(min, max)
        val above = (below + if (kotlin.math.abs(below - perHandKg) < 1e-9) 0.0 else s).coerceAtMost(max)
        return below to above
    }

    fun pin(totalKg: Double, stepKg: Double, halfStepKg: Double?): String {
        val pin = Math.floor(totalKg / stepKg + 1e-9).toInt()
        val base = pin * stepKg
        val addOn = halfStepKg?.let { if (totalKg - base >= it - 1e-9) it else 0.0 } ?: 0.0
        return "pin $pin = ${Units.trim(base)} kg" + if (addOn > 0.0) " (+${Units.trim(addOn)} add-on = ${Units.trim(base + addOn)})" else ""
    }
}

// ==== UI ====

@Composable
fun PlateCalculatorContent(initialKg: Double, ctx: PlateContext, onUse: (Double) -> Unit) {
    var bar by remember { mutableStateOf(20.0) }
    var target by remember { mutableStateOf(initialKg) }
    val result = PlateMath.load(target, bar, PlateMath.inventory(ctx.smallestPlateKg, ctx.microplates))
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.session_plates_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.session_plates_target, Units.trim(target)), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { target = maxOf(0.0, target - 2.5) }) { Text("−2.5") }
            Button(onClick = { target += 2.5 }) { Text("+2.5") }
        }
        when (ctx.loadType) {
            LoadType.DUMBBELL, LoadType.KETTLEBELL -> {
                val max = ctx.dumbbellMaxKg; val min = ctx.dumbbellMinKg ?: 0.0; val step = ctx.dumbbellStepKg ?: 1.0
                if (max == null) Text(stringResource(R.string.session_plates_no_rack))
                else {
                    val (lo, hi) = PlateMath.rack(target, min, max, step)
                    Text(stringResource(R.string.session_plates_rack, Units.trim(target), Units.trim(lo), Units.trim(max)))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onUse(lo) }) { Text(stringResource(R.string.session_plates_use, Units.trim(lo))) }
                        if (hi != lo) Button(onClick = { onUse(hi) }) { Text(stringResource(R.string.session_plates_use, Units.trim(hi))) }
                    }
                }
            }
            LoadType.MACHINE, LoadType.CABLE -> {
                val step = ctx.stackStepKg
                if (step == null) Text(stringResource(R.string.session_plates_no_stack))
                else {
                    Text(PlateMath.pin(target, step, ctx.halfStepKg))
                    Button(onClick = { onUse(target) }) { Text(stringResource(R.string.session_plates_use, Units.trim(target))) }
                }
            }
            LoadType.BARBELL, LoadType.SMITH, LoadType.SLED, LoadType.BODYWEIGHT, LoadType.BAND, LoadType.MEDICINE_BALL, LoadType.CARDIO, LoadType.NONE -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = bar == 20.0, onClick = { bar = 20.0 }, label = { Text("20 kg bar") })
                    FilterChip(selected = bar == 15.0, onClick = { bar = 15.0 }, label = { Text("15 kg bar") })
                }
                PlateDiagram(barKg = bar, perSidePlates = result.perSide)
                Text(stringResource(R.string.session_plates_per_side, result.perSide.joinToString(" + ") { Units.trim(it) }.ifEmpty { "—" }))
                if (!result.exact) {
                    Text(stringResource(R.string.session_plates_unreachable, Units.trim(target), Units.trim(result.belowKg), Units.trim(result.aboveKg)))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onUse(result.belowKg) }) { Text(stringResource(R.string.session_plates_use, Units.trim(result.belowKg))) }
                        Button(onClick = { onUse(result.aboveKg) }) { Text(stringResource(R.string.session_plates_use, Units.trim(result.aboveKg))) }
                    }
                } else Button(onClick = { onUse(result.achievedKg) }) { Text(stringResource(R.string.session_plates_use, Units.trim(result.achievedKg))) }
            }
        }
    }
}
