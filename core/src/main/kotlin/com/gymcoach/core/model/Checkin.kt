// core/src/main/kotlin/com/gymcoach/core/model/Checkin.kt
@file:UseSerializers(LocalDateIsoSerializer::class, InstantIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.json.InstantIsoSerializer
import com.gymcoach.core.json.LocalDateIsoSerializer
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** An active question: a CHK-01 bank item (id "Q1".."Q15") or a coach/user-added one ("C-<uuid>"). */
@Serializable
data class CheckinQuestion(
    val id: String,
    val text: String,
    @SerialName("answer_type") val answerType: CheckinAnswerType,
    val schedule: CheckinSchedule = CheckinSchedule.WEEKLY,
    @SerialName("on_date") val onDate: LocalDate? = null,             // schedule ONCE
    @SerialName("trigger_rule_ids") val triggerRuleIds: List<String> = emptyList(),
    @SerialName("added_by") val addedBy: OverrideSource = OverrideSource.PLANNER,
    @SerialName("proposal_id") val proposalId: String? = null,
    @SerialName("active_from") val activeFrom: LocalDate? = null,
    @SerialName("active_until") val activeUntil: LocalDate? = null,
    val answered: Boolean = false,
    val answer: String? = null,
    @SerialName("answered_at") val answeredAt: Instant? = null,
    @SerialName("schema_version") val schemaVersion: Int = 1,           // §7.2 DocVersions
)

@Serializable
data class CheckinAnswer(@SerialName("question_id") val questionId: String, val number: Double? = null, val yes: Boolean? = null, val text: String? = null)

@Serializable
data class WeeklyCheckin(
    val id: String,
    @SerialName("week_start") val weekStart: LocalDate,
    val answers: List<CheckinAnswer>,
    @SerialName("auto_answered") val autoAnswered: List<String> = emptyList(),
    val triggered: List<Explanation> = emptyList(),                  // what CheckinEvaluator concluded
    val holds: List<Muscle> = emptyList(),                           // VOL-08 holds from CheckinOutcome.holds (Q6 / Q12, §5.2), enum order; written by CheckinViewModel, read by TargetPolicy from the latest inputs.weeklyCheckins entry (§4.4)
    @SerialName("completed_at") val completedAt: Instant? = null,
    @SerialName("schema_version") val schemaVersion: Int = 1,           // §7.2 DocVersions
)
