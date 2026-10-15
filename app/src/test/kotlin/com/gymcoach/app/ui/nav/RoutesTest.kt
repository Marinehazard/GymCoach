package com.gymcoach.app.ui.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * §10.3: routes serialise (Navigation 2.10 type-safe routes are `@Serializable`; R2 G4 requires the
 * serialization plugin in :app — a missing plugin fails here at compile time, not at runtime).
 * Plain JVM test; runs at the first :app build (Appendix A step 8).
 */
class RoutesTest {

    private val json = Json

    @Test
    fun objectRoutesEncodeAsEmptyObjects() {
        assertEquals("{}", json.encodeToString(TodayRoute.serializer(), TodayRoute))
        assertEquals("{}", json.encodeToString(PlanRoute.serializer(), PlanRoute))
        assertEquals("{}", json.encodeToString(ProgressRoute.serializer(), ProgressRoute))
        assertEquals("{}", json.encodeToString(CoachRoute.serializer(), CoachRoute))
        assertEquals("{}", json.encodeToString(MoreRoute.serializer(), MoreRoute))
        assertEquals("{}", json.encodeToString(OnboardingRoute.serializer(), OnboardingRoute))
        assertEquals("{}", json.encodeToString(GymsRoute.serializer(), GymsRoute))
        assertEquals("{}", json.encodeToString(LibraryRoute.serializer(), LibraryRoute))
        assertEquals("{}", json.encodeToString(ProfileRoute.serializer(), ProfileRoute))
        assertEquals("{}", json.encodeToString(CheckinRoute.serializer(), CheckinRoute))
        assertEquals("{}", json.encodeToString(NutritionRoute.serializer(), NutritionRoute))
        assertEquals("{}", json.encodeToString(KnowledgeRoute.serializer(), KnowledgeRoute))
        assertEquals("{}", json.encodeToString(AdjustmentLogRoute.serializer(), AdjustmentLogRoute))
        assertEquals("{}", json.encodeToString(SettingsRoute.serializer(), SettingsRoute))
        assertEquals("{}", json.encodeToString(BackendSettingsRoute.serializer(), BackendSettingsRoute))
        assertEquals("{}", json.encodeToString(AdvancedConstantsRoute.serializer(), AdvancedConstantsRoute))
        assertEquals("{}", json.encodeToString(UsageRoute.serializer(), UsageRoute))
    }

    @Test
    fun readinessRouteRoundTrips() {
        val r = ReadinessRoute("2026-09-05")
        val s = json.encodeToString(ReadinessRoute.serializer(), r)
        assertEquals("{\"date\":\"2026-09-05\"}", s)
        assertEquals(r, json.decodeFromString(ReadinessRoute.serializer(), s))
    }

    @Test
    fun sessionRoutesRoundTrip() {
        val a = SessionRoute("s1")
        assertEquals(a, json.decodeFromString(SessionRoute.serializer(), json.encodeToString(SessionRoute.serializer(), a)))
        val b = SessionSummaryRoute("s1")
        assertEquals(b, json.decodeFromString(SessionSummaryRoute.serializer(), json.encodeToString(SessionSummaryRoute.serializer(), b)))
    }

    @Test
    fun planAndProgressRoutesRoundTrip() {
        val a = PlanSessionRoute("meso1:w1:upper_a")
        assertEquals(a, json.decodeFromString(PlanSessionRoute.serializer(), json.encodeToString(PlanSessionRoute.serializer(), a)))
        val b = ExerciseProgressRoute("barbell_bench_press")
        assertEquals(b, json.decodeFromString(ExerciseProgressRoute.serializer(), json.encodeToString(ExerciseProgressRoute.serializer(), b)))
    }

    @Test
    fun gymEditRouteKeepsEmptyStringsForNewGym() {
        val r = GymEditRoute(gymId = "", fromPreset = "")
        val s = json.encodeToString(GymEditRoute.serializer(), r)
        assertEquals("{\"gymId\":\"\",\"fromPreset\":\"\"}", s)
        assertEquals(r, json.decodeFromString(GymEditRoute.serializer(), s))
    }

    @Test
    fun gymEditRouteWithPreset() {
        val r = GymEditRoute(gymId = "", fromPreset = "hotel_gym")
        assertEquals(r, json.decodeFromString(GymEditRoute.serializer(), json.encodeToString(GymEditRoute.serializer(), r)))
    }

    @Test
    fun detailRoutesRoundTrip() {
        val a = ExerciseDetailRoute("cable_curl")
        assertEquals(a, json.decodeFromString(ExerciseDetailRoute.serializer(), json.encodeToString(ExerciseDetailRoute.serializer(), a)))
        val b = RuleDetailRoute("VOL-04")
        assertEquals(b, json.decodeFromString(RuleDetailRoute.serializer(), json.encodeToString(RuleDetailRoute.serializer(), b)))
        val c = ConversationRoute("c1")
        assertEquals(c, json.decodeFromString(ConversationRoute.serializer(), json.encodeToString(ConversationRoute.serializer(), c)))
    }

    @Test
    fun tabRoutesAreDistinctObjects() {
        val tabs: List<Any> = listOf(TodayRoute, PlanRoute, ProgressRoute, CoachRoute, MoreRoute)
        assertEquals(5, tabs.toSet().size)
    }
}
