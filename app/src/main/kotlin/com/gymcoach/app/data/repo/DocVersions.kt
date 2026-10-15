package com.gymcoach.app.data.repo

/**
 * The newest `schema_version` this build understands per stored document type (§7.2). A stored
 * document with a greater version is surfaced as [Doc.Unreadable] instead of being guessed at.
 * JSON documents carry the version in their `schema_version` key (missing key = 1); the columnar
 * documents (GymSet, PlanOverride, ChatConversation, Proposal) carry it in the `schema_version` column.
 */
object DocVersions {
    const val MESOCYCLE: String = "Mesocycle"
    const val PROGRESSION_STATE: String = "ProgressionState"
    const val READINESS_CHECKIN: String = "ReadinessCheckin"
    const val GYM_SET: String = "GymSet"
    const val PLAN_OVERRIDE: String = "PlanOverride"
    const val PROPOSAL: String = "Proposal"
    const val CHAT_CONVERSATION: String = "ChatConversation"
    const val WEEKLY_CHECKIN: String = "WeeklyCheckin"
    const val CHECKIN_QUESTION: String = "CheckinQuestion"
    const val SESSION_PLAN: String = "SessionPlan"

    val KNOWN: Map<String, Int> = mapOf(
        MESOCYCLE to 1,
        PROGRESSION_STATE to 1,
        READINESS_CHECKIN to 1,
        GYM_SET to 1,
        PLAN_OVERRIDE to 1,
        PROPOSAL to 1,
        CHAT_CONVERSATION to 1,
        WEEKLY_CHECKIN to 1,
        CHECKIN_QUESTION to 1,
        SESSION_PLAN to 1,
    )

    /** True when [version] is newer than what this build knows for [type] (unknown types are never "newer"). */
    fun isNewer(type: String, version: Int): Boolean {
        val known = KNOWN[type] ?: return false
        return version > known
    }
}
