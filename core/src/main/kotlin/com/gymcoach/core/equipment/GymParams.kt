// core/src/main/kotlin/com/gymcoach/core/equipment/GymParams.kt
package com.gymcoach.core.equipment

import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.GymItem
import com.gymcoach.core.model.SessionEquipment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Typed accessors over GymItem.params with catalogue defaults (ParamSpec.default).
 * An accessor returns null when the equipment is not part of [equipment] (or neither the item nor the catalogue gives
 * a value). When an id appears more than once (gym item + a day extra), the LAST occurrence wins (extras override).
 */
class GymParams(private val kb: KnowledgeBase, private val equipment: SessionEquipment) {

    /** True when the concrete equipment id is present today (renamed ids are mapped). */
    fun has(equipmentId: String): Boolean = item(equipmentId) != null

    fun number(equipmentId: String, key: String): Double? = (raw(equipmentId, key) as? JsonPrimitive)?.let { if (it.isString) null else it.doubleOrNull }

    fun bool(equipmentId: String, key: String): Boolean? = (raw(equipmentId, key) as? JsonPrimitive)?.let { if (it.isString) null else it.booleanOrNull }

    fun numbers(equipmentId: String, key: String): List<Double>? {
        val arr = raw(equipmentId, key) as? JsonArray ?: return null
        val out = arr.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
        return out.sorted()
    }

    val dumbbellMaxKg: Double? get() = number("dumbbells", "max_kg")
    val dumbbellMinKg: Double? get() = number("dumbbells", "min_kg")
    val dumbbellStepKg: Double? get() = number("dumbbells", "step_kg")
    val smallestPlateKg: Double? get() = number("plates", "smallest_plate_kg")
    val hasMicroplates: Boolean get() = bool("plates", "has_microplates") ?: false
    val barKg: Double get() = number("barbell", "bar_kg") ?: 20.0
    val kettlebellsKg: List<Double>? get() = numbers("kettlebells", "available_kg")
    fun stackStepKg(equipmentId: String): Double? = number(equipmentId, "stack_step_kg")
    fun halfStepKg(equipmentId: String): Double? = number(equipmentId, "half_step_kg")

    /** resistance_bands.levels as strings, lightest first. */
    fun bandLevels(): List<String>? {
        val arr = raw("resistance_bands", "levels") as? JsonArray ?: return null
        return arr.mapNotNull { (it as? JsonPrimitive)?.content }
    }

    private fun canonical(id: String): String = kb.renamedEquipmentIds[id] ?: id

    private fun item(equipmentId: String): GymItem? {
        val id = canonical(equipmentId)
        return equipment.items.lastOrNull { canonical(it.equipmentId) == id }
    }

    /** Item param, else the catalogue default; null when the equipment is absent or nothing is declared. */
    private fun raw(equipmentId: String, key: String): JsonElement? {
        val item = item(equipmentId) ?: return null
        val own = item.params[key]
        if (own != null && own !is JsonNull) return own
        val spec = kb.equipment[canonical(equipmentId)]?.params?.firstOrNull { it.key == key } ?: return null
        val d = spec.default
        return if (d == null || d is JsonNull) null else d
    }
}
