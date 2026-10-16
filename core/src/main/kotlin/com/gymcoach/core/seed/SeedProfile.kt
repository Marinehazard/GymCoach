@file:UseSerializers(LocalDateIsoSerializer::class, InstantIsoSerializer::class)
package com.gymcoach.core.seed

import com.gymcoach.core.json.InstantIsoSerializer
import com.gymcoach.core.json.LocalDateIsoSerializer
import com.gymcoach.core.model.Band
import com.gymcoach.core.model.BodyMetric
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.model.UnitSystem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * A shipped starting profile (`core/src/main/resources/gymcoach/profiles/<id>.json`, SPEC §3.11): onboarding prefills every
 * page from it and creates its gyms on first run; the user can still edit everything. Nothing here is computed: the
 * history is the user's own log, copied verbatim (RPE converted to RIR, the original RPE kept in `SetLog.note`).
 */
@Serializable
data class SeedProfile(
    @SerialName("schema_version") val schemaVersion: Int,
    val id: String,
    @SerialName("display_name") val displayName: String,
    val source: String,                                                   // provenance, e.g. "docs/USER_PROFILE.md (15 Oct 2026)"
    val units: UnitSystem,
    val profile: Profile,
    @SerialName("body_metrics") val bodyMetrics: List<BodyMetric> = emptyList(),
    val gyms: List<GymSet>,
    @SerialName("default_gym_id") val defaultGymId: String,
    val history: SeedHistory = SeedHistory(),
    @SerialName("progression_seeds") val progressionSeeds: List<ProgressionSeed> = emptyList(),
    val placeholders: List<String> = emptyList(),                         // profile fields NOT known from the source: onboarding asks for them
    val notes: List<String> = emptyList(),
)

/** Logged sessions and sets before the app existed. Set `block_id` "<session id>:top" / ":backoff" / ":acc" names the set's role. */
@Serializable
data class SeedHistory(val sessions: List<SessionRecord> = emptyList(), val sets: List<SetLog> = emptyList())

/**
 * Starting point of one progression track (ProgressionKey exercise + §3.4.1 variant): the user's own next-session target
 * as working load, the rep band and target RIR of the track. History summaries and e1RM come from [SeedHistory].
 */
@Serializable
data class ProgressionSeed(
    @SerialName("exercise_id") val exerciseId: String,
    val variant: String? = null,
    @SerialName("working_load_kg") val workingLoadKg: Double,
    val reps: Band,
    @SerialName("target_rir") val targetRir: Int,
    val source: String,                                                   // where the number comes from ("USER_PROFILE §7 next-session target")
)
