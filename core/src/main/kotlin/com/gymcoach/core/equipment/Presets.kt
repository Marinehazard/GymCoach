// core/src/main/kotlin/com/gymcoach/core/equipment/Presets.kt
package com.gymcoach.core.equipment

import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.GymItem
import com.gymcoach.core.model.GymSet

object Presets {
    const val BASE_GYM: String = "base_gym"
    const val HOME_GYM: String = "home_gym"
    const val HOTEL_GYM: String = "hotel_gym"
    const val TRAVEL_KIT: String = "travel_kit"

    /** Builds a GymSet from a preset id (items + params from the preset). */
    fun gymFromPreset(kb: KnowledgeBase, presetId: String, name: String, id: String, createdAt: java.time.Instant): GymSet {
        val preset = requireNotNull(kb.presets[presetId]) { "unknown equipment preset $presetId" }
        return GymSet(
            id = id,
            name = name,
            presetOrigin = presetId,
            items = preset.items.map { GymItem(it.equipmentId, it.params) },
            defaultSessionMinutes = preset.defaultSessionMinutes,
            travelKitMerged = presetId == TRAVEL_KIT,
            createdAt = createdAt,
        )
    }

    /** Adds the travel_kit items to a gym (idempotent): items already present keep their own params. */
    fun mergeTravelKit(kb: KnowledgeBase, gym: GymSet): GymSet {
        val kit = requireNotNull(kb.presets[TRAVEL_KIT]) { "travel_kit preset missing" }
        val present = gym.items.map { it.equipmentId }.toHashSet()
        val added = kit.items.filter { it.equipmentId !in present }.map { GymItem(it.equipmentId, it.params) }
        if (added.isEmpty() && gym.travelKitMerged) return gym
        return gym.copy(items = gym.items + added, travelKitMerged = true)
    }
}
