package com.gymcoach.core.coach

import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.Band
import com.gymcoach.core.model.EnergyModel
import com.gymcoach.core.model.EnteredWeight
import com.gymcoach.core.model.ExerciseBlock
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.NutritionTargets
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.Proposal
import com.gymcoach.core.model.ProposalStatus
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.model.ReadinessCheckin
import com.gymcoach.core.model.SessionMode
import com.gymcoach.core.model.SessionPlan
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SessionStatus
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.model.SetPrescription
import com.gymcoach.core.model.SlotRole
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.UnitSystem
import com.gymcoach.core.model.VolumeSnapshot
import com.gymcoach.core.model.Warmup
import com.gymcoach.core.model.WeightUnit
import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.volume.VolumeView
import com.gymcoach.core.volume.WeeklyLedger
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CoachContextBuilderTest {
    private val kb = CoachFixtures.kb
    private val builder = CoachContextBuilder(kb, TokenEstimator)
    private val at = Instant.parse("2026-09-08T17:00:00Z")
    private val expl = Explanation(listOf("TRV-04"), "barbell_bench_press → dumbbell_bench_press (score 0.8)")

    private val session = SessionRecord("s-mon", LocalDate.of(2026, 9, 7), CoachFixtures.HOME_ID, "meso-t:w1:d1:UPPER_A", "UPPER_A", status = SessionStatus.COMPLETED, finishedAt = at)
    private val sets = listOf(
        SetLog("x1", "s-mon", null, "barbell_bench_press", 1, SetKind.WORKING, EnteredWeight(82.25, WeightUnit.KG, 82.25), reps = 8, rir = 2, loggedAt = at),
        SetLog("x2", "s-mon", null, "barbell_row", 1, SetKind.WORKING, EnteredWeight(155.0, WeightUnit.LB, 70.3), reps = 10, rir = 2, loggedAt = at),
    )

    private fun inputs(
        gymId: String = CoachFixtures.HOME_ID,
        todaySession: SessionPlan? = null,
        ledger: WeeklyLedger? = null,
        nutrition: NutritionTargets? = null,
        recent: List<SessionRecord> = listOf(session),
        pending: List<Proposal> = emptyList(),
    ): ContextInputs {
        val gym = CoachFixtures.home
        val today = TodayContext(CoachFixtures.TODAY, gymId)
        val readiness = (0..6).map { d -> readiness(CoachFixtures.TODAY.minusDays(d.toLong()), 60 + d) }
        return ContextInputs(
            today = today, units = UnitSystem.METRIC, profile = CoachFixtures.profile(), trainingAge = TrainingAge.INTERMEDIATE, weightMa7Kg = 84.44,
            gym = gym, availableExerciseIds = EquipmentResolver(kb).availableExerciseIds(SessionEquipment(gym)).toList(), mesocycle = CoachFixtures.mesocycle(),
            weekIndex = 1, weekSessions = listOf(session), ledger = ledger, todaySession = todaySession, readiness7d = readiness,
            states = emptyList(), recentSessions = recent, recentSets = sets, openQuestions = emptyList(), lastCheckin = null,
            nutrition = nutrition, energy = nutrition?.let { EnergyModel(1850, 1.4, 2609, emptyList(), expl) }, trendKgPerWeek = 0.351,
            pendingProposals = pending, appliedSince = emptyList(),
        )
    }

    private fun readiness(date: LocalDate, score: Int) = ReadinessCheckin(
        id = "r-$date", date = date, score = score, band = if (score >= 70) ReadinessBand.GREEN else ReadinessBand.AMBER,
        explanation = expl, loggedAt = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
    )

    private fun inner(snapshot: String): JsonObject {
        assertTrue(snapshot.startsWith("<context schema=\"1\">") && snapshot.endsWith("</context>"), snapshot.take(40))
        return CoreJson.wire.parseToJsonElement(snapshot.removePrefix("<context schema=\"1\">").removeSuffix("</context>")).jsonObject
    }

    private val keyOrder = listOf("today", "units", "profile", "gym_today", "available_exercises", "mesocycle", "week", "ledger", "today_session", "readiness", "progression_flags", "recent", "checkin", "nutrition", "proposals")

    @Test
    fun snapshotHasTheFixedKeyOrderAndIsByteStable() {
        val a = builder.snapshot(inputs())
        assertEquals(a, builder.snapshot(inputs()))
        val o = inner(a)
        assertEquals(keyOrder, o.keys.toList())
        assertTrue(a.startsWith("<context schema=\"1\">{\"today\":\"2026-09-09\",\"units\":\"metric\",\"profile\":{\"sex\":\"male\",\"age\":38,\"height_cm\":180,\"weight_ma7_kg\":84.4,"), a.take(200))
        assertEquals(listOf("sex", "age", "height_cm", "weight_ma7_kg", "training_age", "goal", "days", "minutes", "limitations", "medical_gate", "priority_muscles"), o["profile"]!!.jsonObject.keys.toList())
        assertEquals("upper_lower_4", o["mesocycle"]!!.jsonObject["split"]!!.jsonPrimitive.content)
        val days = o["week"]!!.jsonObject["sessions"]!!.jsonArray.map { it.jsonObject["day"]!!.jsonPrimitive.content + ":" + it.jsonObject["status"]!!.jsonPrimitive.content }
        assertEquals(listOf("Mon:completed", "Tue:not_logged", "Thu:planned", "Fri:planned"), days)
    }

    @Test
    fun loggedNumbersAreQuotedExactlyWithTheirUnit() {
        val recent = inner(builder.snapshot(inputs()))["recent"]!!.jsonArray.single().jsonObject
        val top = recent["top_sets"]!!.jsonArray.map { it.jsonObject }
        assertEquals("82.25", top[0]["kg"]!!.jsonPrimitive.content)
        assertEquals("155", top[1]["lb"]!!.jsonPrimitive.content)
        assertEquals(null, top[1]["kg"])
        assertEquals("POL-01", CoachContextBuilder.LOGGED_NUMBERS_RULE)
    }

    @Test
    fun todaySessionNullVersusGiven() {
        assertEquals(JsonNull, inner(builder.snapshot(inputs()))["today_session"])
        val plan = SessionPlan(
            id = "meso-t:w1:d3:LOWER_B@2026-09-09", plannedSessionId = null, templateId = "LOWER_B", name = "Lower B", date = CoachFixtures.TODAY,
            gymId = CoachFixtures.HOTEL_ID, mode = SessionMode.TRAVEL, weekIndex = 1, warmup = Warmup(5, null, emptyList(), 5, expl),
            blocks = listOf(ExerciseBlock(
                "s1:dumbbell_romanian_deadlift", "s1", "dumbbell_romanian_deadlift", 0, LoadType.DUMBBELL, "conventional_deadlift", SlotRole.PRIMARY, 0,
                listOf(SetPrescription(0, SetKind.RAMP, Band(8, 8), restSeconds = 60), SetPrescription(1, SetKind.WORKING, Band(15, 30), loadKg = 40.0, loadLabel = "2×20 kg", rir = 1, restSeconds = 120),
                    SetPrescription(2, SetKind.WORKING, Band(15, 30), loadKg = 40.0, loadLabel = "2×20 kg", rir = 1, restSeconds = 120)),
                progressionKey = ProgressionKey("dumbbell_romanian_deadlift"), explanation = expl,
            )),
            modifiers = listOf(expl), estimatedMinutes = 39.5, volume = VolumeSnapshot(emptyMap(), emptyMap()),
        )
        val ts = inner(builder.snapshot(inputs(todaySession = plan)))["today_session"]!!.jsonObject
        val block = ts["blocks"]!!.jsonArray.single().jsonObject
        assertEquals("{\"ex\":\"dumbbell_romanian_deadlift\",\"sub_for\":\"conventional_deadlift\",\"sets\":2,\"reps\":\"15-30\",\"load\":\"2×20 kg\",\"rir\":1}", block.toString())
        assertEquals("TRV-04: barbell_bench_press → dumbbell_bench_press (score 0.8)", ts["modifiers"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun ledgerAndNutritionRenderedWhenGivenAndNullWhenAbsent() {
        val none = inner(builder.snapshot(inputs()))
        assertEquals(JsonNull, none["ledger"])
        assertEquals(JsonNull, none["nutrition"])
        val ledger = WeeklyLedger(
            CoachFixtures.MONDAY, mapOf(Muscle.CHEST to 14.0, Muscle.CALVES to 8.0, Muscle.FOREARMS to 4.0),
            mapOf(Muscle.CHEST to VolumeView(4, 6.0, 1)), mapOf(Muscle.CHEST to 6.0, Muscle.CALVES to 6.0), emptyMap(),
        )
        val nutrition = NutritionTargets(2870, 151, 350, 80, 35, 3.0, 261, explanation = expl, nextReviewDate = LocalDate.of(2026, 9, 14))
        val o = inner(builder.snapshot(inputs(ledger = ledger, nutrition = nutrition)))
        val l = o["ledger"]!!.jsonObject
        assertEquals("{\"chest\":14,\"calves\":8}", l["targets"].toString(), "TIER_1/TIER_2 muscles only")
        assertEquals("{\"chest\":6}", l["achieved"].toString())
        assertEquals("[\"chest -2\",\"calves -2\"]", l["under_target"].toString())
        assertEquals("{\"tdee_est\":2609,\"target_kcal\":2870,\"protein_g\":151,\"weight_ma7\":84.4,\"trend_kg_per_week\":0.35,\"next_review\":\"2026-09-14\"}", o["nutrition"].toString())
    }

    @Test
    fun gymFallbackIsStatedInTheSnapshot() {
        val g = inner(builder.snapshot(inputs(gymId = "archived-gym")))["gym_today"]!!.jsonObject
        assertEquals("default", g["fallback"]!!.jsonPrimitive.content)
        assertEquals(null, inner(builder.snapshot(inputs()))["gym_today"]!!.jsonObject["fallback"])
    }

    @Test
    fun truncationFollowsTheSpecOrderAndIsStated() {
        val order = listOf("recent", "available_exercises", "readiness.last7", "week.sessions")
        val full = builder.snapshot(inputs(), budgetTokens = 100_000)
        assertEquals(null, inner(full)["truncated"])
        val seen = LinkedHashSet<List<String>>()
        var budget = TokenEstimator.estimate(full)
        while (budget > 50) {
            val s = builder.snapshot(inputs(), budgetTokens = budget)
            val t = inner(s)["truncated"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            assertEquals(order.take(t.size), t, "budget $budget")
            seen.add(t)
            budget -= 10
        }
        assertTrue(order in seen, "the smallest budgets apply every step: $seen")
        val counted = inner(builder.snapshot(inputs(), budgetTokens = 50))
        assertTrue(counted["available_exercises"] is JsonObject, "ids collapsed to pattern counts")
        assertEquals(null, counted["readiness"]!!.jsonObject["last7"])
        assertTrue(counted["week"]!!.jsonObject["sessions"]!!.jsonArray.size <= 2, "today + tomorrow kept")
    }

    @Test
    fun defaultBudgetKeepsTheHomeSnapshotWithinTarget() {
        val s = builder.snapshot(inputs())
        assertTrue(TokenEstimator.estimate(s) <= 2500, "≈ ${TokenEstimator.estimate(s)} tokens")
    }

    @Test
    fun pendingProposalsAreDescribedWithoutRationale() {
        val p = Proposal("toolu_1", "c1", "a1", "propose_add_cardio", 1, "{\"contract_version\":1,\"weekday\":3,\"minutes\":30,\"kind\":\"zone2\",\"rationale\":\"CAR-07\"}",
            null, "CAR-07", ProposalStatus.PENDING, createdAt = at, expiresOn = CoachFixtures.TODAY.plusDays(7))
        val o = inner(builder.snapshot(inputs(pending = listOf(p))))
        assertEquals("{\"pending\":[\"propose_add_cardio weekday=3 minutes=30 kind=zone2\"],\"applied_since_last_conversation\":[]}", o["proposals"].toString())
    }

    @Test
    fun deltaContainsOnlyWhatChanged() {
        val empty = DeltaInputs(null, emptyList(), emptyList(), emptyList(), false, false, emptyList(), null, false)
        assertEquals("<delta schema=\"1\">{}</delta>", builder.delta(empty))
        assertEquals("<delta schema=\"1\">{\"today\":\"2026-09-10\"}</delta>", builder.delta(empty.copy(today = LocalDate.of(2026, 9, 10))))
        val applied = Proposal("toolu_01A", "c1", "a1", "propose_add_cardio", 1, "{}", null, "r", ProposalStatus.APPLIED, createdAt = at, expiresOn = CoachFixtures.TODAY)
        val rejected = applied.copy(id = "toolu_01B", status = ProposalStatus.REJECTED)
        assertEquals("<delta schema=\"1\">{\"proposals\":{\"applied\":[\"toolu_01A\"],\"rejected\":[\"toolu_01B\"]}}</delta>", builder.delta(empty.copy(proposals = listOf(applied, rejected))))
        val all = builder.delta(empty.copy(sessionsLogged = listOf(session), sets = sets, profileChanged = true, gymChanged = true, gym = CoachFixtures.hotel,
            planChangedFields = listOf("swap_exercise"), readiness = readiness(CoachFixtures.TODAY, 58), checkinAnswered = true))
        val o = CoreJson.wire.parseToJsonElement(all.removePrefix("<delta schema=\"1\">").removeSuffix("</delta>")).jsonObject
        assertEquals(listOf("sessions_logged", "profile_changed", "gym_changed", "gym_today", "plan_changed", "readiness", "checkin_answered"), o.keys.toList())
        assertEquals("gym-hotel", o["gym_today"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(TokenEstimator.estimate(builder.delta(empty.copy(proposals = listOf(applied)))) < 100)
    }

    @Test
    fun bridgeDeltaCarriesProposalResults() {
        val empty = DeltaInputs(null, emptyList(), emptyList(), emptyList(), false, false, emptyList(), null, false)
        assertEquals("<delta schema=\"1\">{\"proposals\":{\"results\":{\"toolu_1\":{\"status\":\"rejected\",\"reason\":\"rejected by user\"}}}}</delta>",
            builder.delta(empty, mapOf("toolu_1" to "{\"status\":\"rejected\",\"reason\":\"rejected by user\"}")))
    }

    @Test
    fun summaryBlockWrapsTheSummary() {
        assertEquals("<summary>Prefers dumbbells.</summary>", builder.summaryBlock("  Prefers dumbbells.\n"))
    }
}
