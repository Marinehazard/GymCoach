// app/src/main/kotlin/com/gymcoach/app/ui/nav/Routes.kt
package com.gymcoach.app.ui.nav

import kotlinx.serialization.Serializable

// Tabs (bottom bar visible): Today · Plan · Progress · Coach · More
@Serializable object TodayRoute
@Serializable object PlanRoute
@Serializable object ProgressRoute
@Serializable object CoachRoute
@Serializable object MoreRoute

// Full-screen destinations (bottom bar hidden)
@Serializable object OnboardingRoute
@Serializable data class ReadinessRoute(val date: String)                      // ISO date
@Serializable data class SessionRoute(val sessionId: String)
@Serializable data class SessionSummaryRoute(val sessionId: String)
@Serializable data class PlanSessionRoute(val plannedSessionId: String)
@Serializable data class ExerciseProgressRoute(val exerciseId: String)
@Serializable object GymsRoute
@Serializable data class GymEditRoute(val gymId: String, val fromPreset: String)   // "" = new gym / no preset
@Serializable object LibraryRoute
@Serializable data class ExerciseDetailRoute(val exerciseId: String)
@Serializable object ProfileRoute
@Serializable object CheckinRoute
@Serializable object NutritionRoute
@Serializable object KnowledgeRoute
@Serializable data class RuleDetailRoute(val ruleId: String)
@Serializable object AdjustmentLogRoute
@Serializable data class ConversationRoute(val conversationId: String)
@Serializable object SettingsRoute
@Serializable object BackendSettingsRoute
@Serializable object AdvancedConstantsRoute
@Serializable object UsageRoute
