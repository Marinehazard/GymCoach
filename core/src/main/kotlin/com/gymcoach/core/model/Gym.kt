// core/src/main/kotlin/com/gymcoach/core/model/Gym.kt
@file:UseSerializers(LocalDateIsoSerializer::class, InstantIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.json.InstantIsoSerializer
import com.gymcoach.core.json.LocalDateIsoSerializer
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonElement

/** One line of a named gym set; params override the catalogue defaults (R4 §2.6). */
@Serializable
data class GymItem(@SerialName("equipment_id") val equipmentId: String, val params: Map<String, JsonElement> = emptyMap())

@Serializable
data class GymSet(
    val id: String,
    val name: String,
    @SerialName("preset_origin") val presetOrigin: String? = null,        // "base_gym" | "home_gym" | "hotel_gym" | null
    val items: List<GymItem>,
    @SerialName("default_session_minutes") val defaultSessionMinutes: Int? = null,   // home 60 / hotel 45 (B graft)
    @SerialName("color_index") val colorIndex: Int = 0,                   // 0..7
    @SerialName("travel_kit_merged") val travelKitMerged: Boolean = false,
    val notes: String = "",
    @SerialName("is_default") val isDefault: Boolean = false,
    val archived: Boolean = false,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("last_used_at") val lastUsedAt: Instant? = null,
    @SerialName("schema_version") val schemaVersion: Int = 1,           // §7.2 DocVersions
)

/** Everything that varies day to day; the realizer never reads anything else about "today". */
@Serializable
data class TodayContext(
    val date: LocalDate,
    @SerialName("gym_id") val gymId: String,
    @SerialName("extra_items") val extraItems: List<GymItem> = emptyList(),               // one-off additions / travel kit
    @SerialName("removed_equipment_ids") val removedEquipmentIds: List<String> = emptyList(),   // "the leg press is broken today"
    val spotter: Boolean = false,
    @SerialName("minutes_override") val minutesOverride: Int? = null,
    val readiness: ReadinessCheckin? = null,
    @SerialName("ambient_cold") val ambientCold: Boolean = false,                            // WU-06
    @SerialName("minutes_since_waking") val minutesSinceWaking: Int? = null,                // WU-06
    @SerialName("illness_symptoms") val illnessSymptoms: Boolean = false,                    // RDY-03 / LAY-02
    @SerialName("alcohol_drinks_last_night") val alcoholDrinksLastNight: Int = 0,           // NUT-13
)

/** Resolved equipment for one day = gym + extras − removals + session flag (R4 §2.2 item 4). Not persisted. */
data class SessionEquipment(
    val gym: GymSet,
    val extraItems: List<GymItem> = emptyList(),
    val removedEquipmentIds: Set<String> = emptySet(),
    val spotterPresent: Boolean = false,
) {
    val items: List<GymItem>
        get() = (gym.items.filter { it.equipmentId !in removedEquipmentIds } + extraItems)
    fun has(equipmentId: String): Boolean = items.any { it.equipmentId == equipmentId }
}
