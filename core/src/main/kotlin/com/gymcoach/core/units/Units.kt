// core/src/main/kotlin/com/gymcoach/core/units/Units.kt
package com.gymcoach.core.units

import com.gymcoach.core.model.EnteredLength
import com.gymcoach.core.model.EnteredWeight
import com.gymcoach.core.model.LengthUnit
import com.gymcoach.core.model.UnitSystem
import com.gymcoach.core.model.WeightUnit
import java.util.Locale

object Units {
    const val KG_PER_LB = 0.45359237
    const val CM_PER_IN = 2.54

    fun lbToKg(lb: Double): Double = lb * KG_PER_LB
    fun kgToLb(kg: Double): Double = kg / KG_PER_LB
    fun inToCm(inches: Double): Double = inches * CM_PER_IN
    fun cmToIn(cm: Double): Double = cm / CM_PER_IN

    /** Builds the sacred typed value; the kg conversion is exact (not rounded). */
    fun enteredWeight(value: Double, unit: WeightUnit): EnteredWeight =
        EnteredWeight(entered = value, unit = unit, kg = if (unit == WeightUnit.KG) value else lbToKg(value))

    fun enteredLength(value: Double, unit: LengthUnit): EnteredLength =
        EnteredLength(entered = value, unit = unit, cm = if (unit == LengthUnit.CM) value else inToCm(value))

    fun weightUnitFor(system: UnitSystem): WeightUnit = if (system == UnitSystem.METRIC) WeightUnit.KG else WeightUnit.LB
    fun lengthUnitFor(system: UnitSystem): LengthUnit = if (system == UnitSystem.METRIC) LengthUnit.CM else LengthUnit.IN

    /** "80 kg", "82.5 kg", "176.4 lb" — at most 2 decimals, trailing zeros trimmed. HR-20: entered value shown verbatim. */
    fun formatWeight(w: EnteredWeight, display: WeightUnit): String =
        if (w.unit == display) "${trim(w.entered)} ${unitLabel(display)}"
        else "≈ ${trim(if (display == WeightUnit.KG) w.kg else kgToLb(w.kg))} ${unitLabel(display)}"

    fun formatKg(kg: Double, display: WeightUnit): String =
        if (display == WeightUnit.KG) "${trim(kg)} kg" else "${trim(kgToLb(kg))} lb"

    fun unitLabel(u: WeightUnit): String = if (u == WeightUnit.KG) "kg" else "lb"

    fun trim(v: Double): String {
        val s = String.format(Locale.ROOT, "%.2f", v)
        return s.trimEnd('0').trimEnd('.')
    }
}
