// core/src/main/kotlin/com/gymcoach/core/model/Readiness.kt
@file:UseSerializers(LocalDateIsoSerializer::class, InstantIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.json.InstantIsoSerializer
import com.gymcoach.core.json.LocalDateIsoSerializer
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * RDY-01 inputs. Quick mode (B graft) leaves sleepQuality and stress null; the scorer renormalises
 * over answered items and lists them in `assumedFields`. Score/band are derived (stored for history).
 */
@Serializable
data class ReadinessCheckin(
    val id: String,
    val date: LocalDate,
    @SerialName("quick_mode") val quickMode: Boolean = false,
    @SerialName("sleep_hours") val sleepHours: Double? = null,
    @SerialName("sleep_quality") val sleepQuality: Int? = null,      // 1..5
    val soreness: Int? = null,                                       // 1..5 (5 severe)
    @SerialName("soreness_region") val sorenessRegion: Muscle? = null,
    val stress: Int? = null,                                         // 1..5 (5 severe)
    val energy: Int? = null,                                         // 1..5 (5 full)
    val motivation: Int? = null,                                     // 1..5
    @SerialName("resting_hr") val restingHr: Int? = null,
    @SerialName("joint_pain_0_10") val jointPain0to10: Int = 0,
    @SerialName("joint_region") val jointRegion: LimitationRegion? = null,
    @SerialName("red_flag_reported") val redFlagReported: Boolean = false,
    @SerialName("assumed_fields") val assumedFields: List<String> = emptyList(),
    val score: Int,                                                  // 0..100 (RDY-01)
    val band: ReadinessBand,                                         // RDY-02
    val explanation: Explanation,
    @SerialName("logged_at") val loggedAt: Instant,
    @SerialName("schema_version") val schemaVersion: Int = 1,           // §7.2 DocVersions
)
