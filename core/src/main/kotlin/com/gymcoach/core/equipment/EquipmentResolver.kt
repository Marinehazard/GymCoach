// core/src/main/kotlin/com/gymcoach/core/equipment/EquipmentResolver.kt
package com.gymcoach.core.equipment

import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.EquipmentOption
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.GroupKind
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.SessionEquipment

/** Result of resolving one exercise against today's equipment. */
data class Availability(
    val available: Boolean,
    val optionIndex: Int?,                       // first satisfied option (preferred first), null if none
    val effectiveLoadType: LoadType?,            // option override or exercise default
    val safetiesOrSpotter: Boolean,              // rack_or_spotter satisfied
    val missing: List<String>,                   // items missing from the best option (for the UI)
)

class EquipmentResolver(private val kb: KnowledgeBase) {

    /**
     * R4 §2.2 literally: available iff at least one equipment option has every all_of item satisfied.
     * Item satisfied if: concrete id present; any_of group with ≥ 1 member present; all_of bundle with all present;
     * session flag `spotter` toggled. `pullup_bar_any` honours power_rack.has_pullup_bar; `rack_or_spotter` is
     * satisfied by power_rack/half_rack only when has_safeties is true (default true), or by spotterPresent.
     * `all_of: []` is always satisfied.
     */
    fun resolve(exercise: Exercise, equipment: SessionEquipment): Availability {
        val params = GymParams(kb, equipment)
        val safeties = satisfied(RACK_OR_SPOTTER, equipment, params)
        exercise.equipmentOptions.forEachIndexed { index, option ->
            if (option.allOf.all { satisfied(it, equipment, params) }) {
                return Availability(true, index, option.loadType ?: exercise.loadType, safeties, emptyList())
            }
        }
        return Availability(false, null, null, safeties, bestMissing(exercise.equipmentOptions, equipment, params))
    }

    fun availableExerciseIds(equipment: SessionEquipment): Set<String> {
        val params = GymParams(kb, equipment)
        val out = LinkedHashSet<String>()
        for (id in kb.exerciseOrder) {
            val e = kb.exercises[id] ?: continue
            if (e.equipmentOptions.any { o -> o.allOf.all { satisfied(it, equipment, params) } }) out.add(id)
        }
        return out
    }

    fun isItemSatisfied(item: String, equipment: SessionEquipment): Boolean = satisfied(item, equipment, GymParams(kb, equipment))

    private fun satisfied(rawItem: String, equipment: SessionEquipment, params: GymParams): Boolean {
        val item = kb.renamedEquipmentIds[rawItem] ?: rawItem
        val group = kb.groups[item]
        if (group != null) {
            return when (group.kind) {
                GroupKind.ANY_OF -> group.members.any { memberSatisfied(item, it, equipment, params) }
                GroupKind.ALL_OF -> group.members.all { memberSatisfied(item, it, equipment, params) }
            }
        }
        return concreteSatisfied(item, equipment)
    }

    private fun memberSatisfied(groupId: String, member: String, equipment: SessionEquipment, params: GymParams): Boolean {
        if (!concreteSatisfied(member, equipment)) return false
        return when {
            groupId == RACK_OR_SPOTTER && (member == POWER_RACK || member == HALF_RACK) -> params.bool(member, "has_safeties") ?: true
            groupId == PULLUP_BAR_ANY && member == POWER_RACK -> params.bool(member, "has_pullup_bar") ?: true
            else -> true
        }
    }

    private fun concreteSatisfied(id: String, equipment: SessionEquipment): Boolean {
        val def = kb.equipment[id]
        if (def != null && def.sessionFlag) return equipment.spotterPresent || equipment.items.any { (kb.renamedEquipmentIds[it.equipmentId] ?: it.equipmentId) == id }
        return equipment.items.any { (kb.renamedEquipmentIds[it.equipmentId] ?: it.equipmentId) == id }
    }

    /** Missing items of the option with the fewest unsatisfied items (first option on a tie). */
    private fun bestMissing(options: List<EquipmentOption>, equipment: SessionEquipment, params: GymParams): List<String> {
        var best: List<String>? = null
        for (o in options) {
            val missing = o.allOf.filter { !satisfied(it, equipment, params) }
            if (best == null || missing.size < best.size) best = missing
        }
        return best ?: emptyList()
    }

    companion object {
        const val RACK_OR_SPOTTER: String = "rack_or_spotter"
        const val PULLUP_BAR_ANY: String = "pullup_bar_any"
        const val POWER_RACK: String = "power_rack"
        const val HALF_RACK: String = "half_rack"
        const val SPOTTER: String = "spotter"
    }
}
