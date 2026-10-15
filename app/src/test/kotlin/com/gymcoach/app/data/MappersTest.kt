package com.gymcoach.app.data

import com.gymcoach.app.data.repo.Doc
import com.gymcoach.app.data.repo.DocVersions
import com.gymcoach.app.data.repo.EnumNames
import com.gymcoach.app.data.repo.mutationType
import com.gymcoach.app.data.repo.storedSchemaVersion
import com.gymcoach.app.data.repo.toDoc
import com.gymcoach.app.data.repo.toEntity
import com.gymcoach.app.data.repo.toModel
import com.gymcoach.app.data.repo.toSnapshotEntity
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.Adjustment
import com.gymcoach.core.model.AdjustmentKind
import com.gymcoach.core.model.BackendMode
import com.gymcoach.core.model.Band
import com.gymcoach.core.model.BodyMetric
import com.gymcoach.core.model.CardioKind
import com.gymcoach.core.model.CardioLog
import com.gymcoach.core.model.ChatConversation
import com.gymcoach.core.model.ChatMessage
import com.gymcoach.core.model.ChatRole
import com.gymcoach.core.model.CheckinAnswer
import com.gymcoach.core.model.CheckinAnswerType
import com.gymcoach.core.model.CheckinQuestion
import com.gymcoach.core.model.ConversationStatus
import com.gymcoach.core.model.Effort
import com.gymcoach.core.model.EnteredLength
import com.gymcoach.core.model.EnteredWeight
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.GymItem
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.LengthUnit
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.MutationScope
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.NutritionLog
import com.gymcoach.core.model.NutritionState
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.ProgressionStatus
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
import com.gymcoach.core.model.Sex
import com.gymcoach.core.model.Side
import com.gymcoach.core.model.SplitKind
import com.gymcoach.core.model.SpendRecord
import com.gymcoach.core.model.TokenUsage
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.VolumeSnapshot
import com.gymcoach.core.model.Warmup
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.model.WeeklyCheckin
import com.gymcoach.core.model.WeightUnit
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/** Entity ↔ model round trips for all 19 tables (§7.2) and the newer-version → Unreadable path (§7.1 policy). */
class MappersTest {
    private val t0: Instant = Instant.ofEpochMilli(1_788_000_000_000L)   // millisecond precision: columns store epoch millis
    private val t1: Instant = t0.plusSeconds(3_600)
    private val day: LocalDate = LocalDate.of(2026, 9, 7)
    private val expl = Explanation(listOf("PROG-01"), "all sets hit the top", mapOf("reps" to "10"))

    private fun <T> ok(doc: Doc<T>): T {
        assertIs<Doc.Ok<T>>(doc)
        return doc.value
    }

    private fun <T> unreadable(doc: Doc<T>, type: String, version: Int, id: String) {
        assertEquals(Doc.Unreadable<T>(type, version, id), doc)
    }

    /** Raises the top-level "schema_version" key of a stored JSON document. */
    private fun bumpVersion(text: String, version: Int): String {
        val obj = CoreJson.stored.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        return kotlinx.serialization.json.JsonObject(obj + ("schema_version" to JsonPrimitive(version))).toString()
    }

    // ---- samples -----------------------------------------------------------------------------------

    private val profile = Profile(
        sex = Sex.MALE, birthYear = 1984, height = EnteredLength(180.0, LengthUnit.CM, 180.0), trainingMonthsConsistent = 30,
        goal = Goal.HYPERTROPHY, daysPerWeek = 4, sessionMinutes = 60, knownLoadsKg = mapOf("barbell_bench_press" to 80.0),
        updatedAt = t0,
    )
    private val gym = GymSet(
        id = "gym-1", name = "Home", presetOrigin = "home_gym",
        items = listOf(GymItem("dumbbells_adjustable", mapOf("max_kg" to JsonPrimitive(32)))),
        defaultSessionMinutes = 60, colorIndex = 3, travelKitMerged = true, notes = "garage", isDefault = true,
        archived = false, createdAt = t0, lastUsedAt = t1, schemaVersion = 1,
    )
    private val sessionPlan = SessionPlan(
        id = "free@2026-09-07", plannedSessionId = null, templateId = "UPPER_A", name = "Upper A", date = day, gymId = "gym-1",
        mode = SessionMode.AS_PLANNED, weekIndex = 2, warmup = Warmup(raiseMinutes = 5, drills = emptyList(), totalMinutes = 8, explanation = expl),
        blocks = emptyList(), modifiers = listOf(expl), estimatedMinutes = 55.5, volume = VolumeSnapshot(mapOf(Muscle.CHEST to 3.0), mapOf(Muscle.CHEST to 3)),
    )
    private val record = SessionRecord(
        id = "s-1", date = day, gymId = "gym-1", plannedSessionId = "meso-x:w2:d1:UPPER_A", templateId = "UPPER_A", plan = sessionPlan,
        status = SessionStatus.IN_PROGRESS, startedAt = t0, finishedAt = null, sessionRpe = 7, durationMinutes = 52,
        readinessCheckinId = "r-1", skipReason = null, notes = "felt good",
    )
    private val setLog = SetLog(
        id = "set-1", sessionId = "s-1", blockId = "slot1:barbell_bench_press", exerciseId = "barbell_bench_press", setIndex = 2,
        kind = SetKind.WORKING, weight = EnteredWeight(176.5, WeightUnit.LB, 80.06), perHand = false, reps = 9, seconds = null,
        metres = null, rir = 2, side = Side.LEFT, prescribedLoadKg = 80.0, prescribedReps = Band(8, 10), prescribedRir = 2,
        loggedAt = t1, supersededById = "set-2", supersedesId = "set-0", note = "grip",
    )
    private val state = ProgressionState(
        key = ProgressionKey("cable_row", "gym-1"), status = ProgressionStatus.PROGRESSING, workingLoadKg = 45.0,
        workingReps = Band(10, 12), repHiBase = 12, targetRir = 2, consecutiveHits = 1, lastSessionDate = day, updatedAt = t0,
    )
    private val mesocycle = Mesocycle(
        id = "meso-0123456789abcdef", startDate = day, goal = Goal.HYPERTROPHY, trainingAge = TrainingAge.INTERMEDIATE,
        split = SplitKind.UPPER_LOWER_4, gymId = "gym-1",
        weeks = listOf(MesoWeek(index = 1, startDate = day, mode = WeekMode.NORMAL, rirOffset = 1, targets = mapOf(Muscle.QUADS to 10.0), sessions = emptyList())),
        inputsHash = "0123456789abcdef0123", explanations = listOf(expl),
    )
    private val readiness = ReadinessCheckin(
        id = "r-1", date = day, sleepHours = 7.5, sleepQuality = 4, soreness = 2, stress = 2, energy = 4, motivation = 4,
        score = 72, band = ReadinessBand.GREEN, explanation = expl, loggedAt = t0,
    )
    private val weekly = WeeklyCheckin(
        id = "w-1", weekStart = day, answers = listOf(CheckinAnswer("Q10", number = 4.0)), autoAnswered = listOf("Q4"),
        triggered = listOf(expl), holds = listOf(Muscle.CHEST), completedAt = t1,
    )
    private val question = CheckinQuestion(
        id = "C-1", text = "Knee ok?", answerType = CheckinAnswerType.YESNO, addedBy = OverrideSource.CHAT, proposalId = "toolu_1",
        activeUntil = day.plusDays(28), answered = false,
    )
    private val mutation = PlanMutation.SwapExercise("barbell_bench_press", "dumbbell_bench_press", null, MutationScope.PERMANENT, "shoulder")
    private val conversationDefault = ChatConversation(
        id = "c-1", startedAt = t0, endedAt = null, backend = BackendMode.DIRECT_API, model = "claude-opus-5", effort = Effort.LOW,
        systemPromptHash = "abc", toolContractVersion = 1, seedSummary = "summary", status = ConversationStatus.AWAITING_TOOL_RESULTS,
        assistantTurns = 3, lastInputTokens = 5_500, isDefault = true, schemaVersion = 1,
    )
    private val proposal = Proposal(
        id = "toolu_1", conversationId = "c-1", assistantMessageId = "m-2", toolName = "propose_swap_exercise", contractVersion = 1,
        payloadJson = "{\"contract_version\":1}", mutation = mutation, rationale = "shoulder", status = ProposalStatus.PENDING,
        validationWarnings = listOf("w1"), validationErrors = emptyList(), createdAt = t0, expiresOn = day.plusDays(7),
        decidedAt = null, overrideId = null, resultSummary = null, resultSent = false, schemaVersion = 1,
    )

    // ---- 1 profile ---------------------------------------------------------------------------------

    @Test fun profile_roundTrip() {
        val e = profile.toEntity()
        assertEquals(1, e.id)
        assertEquals(t0.toEpochMilli(), e.updatedAt)
        assertEquals(profile, e.toModel())
    }

    // ---- 2 body metrics ----------------------------------------------------------------------------

    @Test fun bodyMetric_roundTrip_keepsEnteredValueAndUnit() {
        val m = BodyMetric("b-1", day, EnteredWeight(181.0, WeightUnit.LB, 82.1), waistCm = 88.5, restingHr = 55, steps = 9_000, note = "am", loggedAt = t0)
        val e = m.toEntity()
        assertEquals(181.0, e.enteredWeight)
        assertEquals("lb", e.enteredWeightUnit)
        assertEquals(82.1, e.weightKg)
        assertEquals("2026-09-07", e.date)
        assertEquals(m, e.toModel())
        val noWeight = m.copy(weight = null)
        assertEquals(noWeight, noWeight.toEntity().toModel())
    }

    // ---- 3 gyms (columnar) -------------------------------------------------------------------------

    @Test fun gym_roundTrip_andColumnarVersion() {
        val e = gym.toEntity()
        assertEquals(1, e.schemaVersion)
        assertEquals(gym, ok(e.toDoc()))
        val archived = gym.copy(archived = true, isDefault = false, lastUsedAt = null, presetOrigin = null)
        assertEquals(archived, ok(archived.toEntity().toDoc()))
    }

    @Test fun gym_newerColumnarVersion_isUnreadable() {
        val e = gym.toEntity().copy(schemaVersion = 2)
        unreadable(e.toDoc(), DocVersions.GYM_SET, 2, "gym-1")
    }

    // ---- 4 sessions (+ SessionPlan document) -------------------------------------------------------

    @Test fun session_roundTrip_withAndWithoutPlan() {
        val e = record.toEntity()
        assertEquals("in_progress", e.status)
        assertEquals(record, ok(e.toDoc()))
        val noPlan = record.copy(plan = null, status = SessionStatus.SKIPPED, skipReason = "travel", startedAt = null)
        assertEquals(noPlan, ok(noPlan.toEntity().toDoc()))
    }

    @Test fun session_newerPlanDocument_isUnreadable() {
        val e = record.toEntity()
        val bumped = e.copy(planJson = bumpVersion(e.planJson!!, 2))
        unreadable(bumped.toDoc(), DocVersions.SESSION_PLAN, 2, "s-1")
    }

    // ---- 5 set logs --------------------------------------------------------------------------------

    @Test fun setLog_roundTrip_isExact() {
        val e = setLog.toEntity()
        assertEquals("working", e.kind)
        assertEquals("left", e.side)
        assertEquals(176.5, e.enteredWeight)
        assertEquals("lb", e.enteredWeightUnit)
        assertEquals(8, e.prescribedRepsLo)
        assertEquals(10, e.prescribedRepsHi)
        assertEquals(setLog, e.toModel())
        val bodyweight = setLog.copy(weight = null, prescribedReps = null, reps = null, seconds = 45, side = Side.BOTH, supersededById = null, supersedesId = null)
        assertEquals(bodyweight, bodyweight.toEntity().toModel())
    }

    // ---- 6 adjustments -----------------------------------------------------------------------------

    @Test fun adjustment_roundTrip() {
        val a = Adjustment(
            id = "a-1", sessionId = "s-1", exerciseId = "barbell_bench_press", kind = AdjustmentKind.PROGRESSION,
            ruleIds = listOf("PROG-01", "LOAD-02"), summary = "Load 80 → 82.5 kg", inputs = mapOf("from" to "80", "to" to "82.5"),
            beforeJson = "{\"w\":80}", afterJson = "{\"w\":82.5}", source = OverrideSource.PLANNER, proposalId = null, overrideId = "o-1",
            createdAt = t0, undoneAt = t1,
        )
        val e = a.toEntity()
        assertEquals("PROG-01,LOAD-02", e.ruleIds)
        assertEquals("progression", e.kind)
        assertEquals(a, e.toModel())
    }

    // ---- 7 progression states ----------------------------------------------------------------------

    @Test fun progressionState_roundTrip_andNewer() {
        val e = state.toEntity()
        assertEquals("cable_row@gym-1", e.key)
        assertEquals("cable_row", e.exerciseId)
        assertEquals("gym-1", e.gymScope)
        assertEquals("progressing", e.status)
        assertEquals(state, ok(e.toDoc()))
        unreadable(e.copy(json = bumpVersion(e.json, 2)).toDoc(), DocVersions.PROGRESSION_STATE, 2, "cable_row@gym-1")
    }

    // ---- 8 overrides (columnar) --------------------------------------------------------------------

    @Test fun override_roundTrip_andNewer() {
        val o = PlanOverride(id = "o-1", createdAt = t0, source = OverrideSource.CHAT, proposalId = "toolu_1", mutation = mutation, revokedAt = t1, expiresOn = day, schemaVersion = 1)
        val e = o.toEntity()
        assertEquals("swap_exercise", e.mutationType)
        assertEquals("swap_exercise", mutationType(mutation))
        assertEquals(1, e.schemaVersion)
        assertEquals(o, ok(e.toDoc()))
        val deload = o.copy(mutation = PlanMutation.ScheduleDeload(startThisWeek = true, rationale = "DLD-03"), revokedAt = null, expiresOn = null)
        assertEquals("schedule_deload", deload.toEntity().mutationType)
        assertEquals(deload, ok(deload.toEntity().toDoc()))
        unreadable(e.copy(schemaVersion = 3).toDoc(), DocVersions.PLAN_OVERRIDE, 3, "o-1")
    }

    // ---- 9 plan snapshots --------------------------------------------------------------------------

    @Test fun planSnapshot_roundTrip_andNewer() {
        val e = mesocycle.toSnapshotEntity(t1, isCurrent = true)
        assertEquals(mesocycle.id, e.id)
        assertEquals(mesocycle.inputsHash, e.inputsHash)
        assertTrue(e.isCurrent)
        assertEquals(t1.toEpochMilli(), e.computedAt)
        assertEquals(mesocycle, ok(e.toDoc()))
        unreadable(e.copy(mesocycleJson = bumpVersion(e.mesocycleJson, 2)).toDoc(), DocVersions.MESOCYCLE, 2, mesocycle.id)
    }

    @Test fun missingSchemaVersionKey_countsAsOne() {
        assertEquals(1, storedSchemaVersion("{\"a\":1}"))
        assertEquals(1, storedSchemaVersion("[1,2]"))
        assertEquals(5, storedSchemaVersion("{\"schema_version\":5}"))
    }

    @Test fun knownVersionWithExtraFields_decodes() {
        val e = mesocycle.toSnapshotEntity(t1, isCurrent = false)
        val obj = CoreJson.stored.parseToJsonElement(e.mesocycleJson) as kotlinx.serialization.json.JsonObject
        val extended = kotlinx.serialization.json.JsonObject(obj + ("future_field" to JsonPrimitive("x"))).toString()
        assertEquals(mesocycle, ok(e.copy(mesocycleJson = extended).toDoc()))
    }

    // ---- 10 nutrition state ------------------------------------------------------------------------

    @Test fun nutritionState_roundTrip() {
        val n = NutritionState(tdeeEstimateKcal = 2_700, kcalTargetOverride = 2_400, proteinGPerKgOverride = 1.9, lastReviewDate = day, deficitStartedOn = day, updatedAt = t0)
        val e = n.toEntity()
        assertEquals(1, e.id)
        assertEquals(n, e.toModel())
    }

    // ---- 11 readiness ------------------------------------------------------------------------------

    @Test fun readiness_roundTrip_andNewer() {
        val e = readiness.toEntity()
        assertEquals(72, e.score)
        assertEquals("green", e.band)
        assertEquals("2026-09-07", e.date)
        assertEquals(readiness, ok(e.toDoc()))
        unreadable(e.copy(json = bumpVersion(e.json, 9)).toDoc(), DocVersions.READINESS_CHECKIN, 9, "r-1")
    }

    // ---- 12 weekly check-ins -----------------------------------------------------------------------

    @Test fun weeklyCheckin_roundTrip_andNewer() {
        val e = weekly.toEntity()
        assertEquals("2026-09-07", e.weekStart)
        assertEquals(t1.toEpochMilli(), e.completedAt)
        assertEquals(weekly, ok(e.toDoc()))
        unreadable(e.copy(json = bumpVersion(e.json, 2)).toDoc(), DocVersions.WEEKLY_CHECKIN, 2, "w-1")
    }

    // ---- 13 check-in questions ---------------------------------------------------------------------

    @Test fun checkinQuestion_roundTrip_andNewer() {
        val e = question.toEntity()
        assertEquals("2026-10-05", e.activeUntil)
        assertEquals(false, e.answered)
        assertEquals(question, ok(e.toDoc()))
        unreadable(e.copy(json = bumpVersion(e.json, 2)).toDoc(), DocVersions.CHECKIN_QUESTION, 2, "C-1")
    }

    // ---- 14 nutrition logs -------------------------------------------------------------------------

    @Test fun nutritionLog_roundTrip() {
        val n = NutritionLog("n-1", day, kcal = 2_300, proteinG = 160, proteinTargetHit = true, mealsHeaviness = 2, alcoholDrinks = 1, travelDay = true, note = "hotel", loggedAt = t0)
        assertEquals(n, n.toEntity().toModel())
        val sparse = NutritionLog("n-2", day, loggedAt = t0)
        assertEquals(sparse, sparse.toEntity().toModel())
    }

    // ---- 15 cardio logs ----------------------------------------------------------------------------

    @Test fun cardioLog_roundTrip() {
        val c = CardioLog("cl-1", day, sessionId = "s-1", exerciseId = "stationary_bike", kind = CardioKind.HIIT_4X4, minutes = 28, avgHr = 150, rpe = 15, note = "", loggedAt = t0)
        val e = c.toEntity()
        assertEquals("hiit_4x4", e.kind)
        assertEquals(c, e.toModel())
    }

    // ---- 16 chat conversations (columnar) ----------------------------------------------------------

    @Test fun conversation_roundTrip_isDefaultTrueAndFalse_andVersion() {
        val e = conversationDefault.toEntity()
        assertEquals("direct_api", e.backend)
        assertEquals("awaiting_tool_results", e.status)
        assertTrue(e.isDefault)
        assertEquals(1, e.schemaVersion)
        assertEquals(conversationDefault, ok(e.toDoc()))
        val refused = conversationDefault.copy(isDefault = false, status = ConversationStatus.OPEN, endedAt = t1, seedSummary = null)
        val re = refused.toEntity()
        assertEquals(false, re.isDefault)
        assertEquals(refused, ok(re.toDoc()))
        unreadable(e.copy(schemaVersion = 2).toDoc(), DocVersions.CHAT_CONVERSATION, 2, "c-1")
    }

    // ---- 17 chat messages --------------------------------------------------------------------------

    @Test fun message_roundTrip_contentVerbatim() {
        val content = "[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"Eos…\"},{\"type\":\"text\",\"text\":\"Hi\"}]"
        val m = ChatMessage(
            id = "m-2", conversationId = "c-1", seq = 2, role = ChatRole.ASSISTANT, contentJson = content, displayText = "Hi", createdAt = t0,
            usage = TokenUsage(2_000, 400, 3_500, 0, 120), requestId = "req_1", stopReason = "tool_use", stopDetailsJson = "{\"x\":1}",
            caveatAppended = true, toolResultsJson = "{\"toolu_2\":\"{}\"}",
        )
        val e = m.toEntity()
        assertEquals(content, e.contentJson)
        assertEquals("assistant", e.role)
        assertEquals(m, e.toModel())
        val user = m.copy(id = "m-1", seq = 1, role = ChatRole.USER, usage = null, requestId = null, stopReason = null, stopDetailsJson = null, caveatAppended = false, toolResultsJson = null)
        assertEquals(user, user.toEntity().toModel())
    }

    // ---- 18 proposals (columnar) -------------------------------------------------------------------

    @Test fun proposal_roundTrip_andVersion() {
        val e = proposal.toEntity()
        assertEquals("pending", e.status)
        assertEquals("2026-09-14", e.expiresOn)
        assertEquals(1, e.schemaVersion)
        assertEquals(proposal, ok(e.toDoc()))
        val invalid = proposal.copy(mutation = null, status = ProposalStatus.INVALID, validationErrors = listOf("unknown exercise"), validationWarnings = emptyList(), decidedAt = t1, resultSummary = "rejected", resultSent = true, overrideId = "o-9")
        assertEquals(invalid, ok(invalid.toEntity().toDoc()))
        unreadable(e.copy(schemaVersion = 2).toDoc(), DocVersions.PROPOSAL, 2, "toolu_1")
    }

    // ---- 19 spend records --------------------------------------------------------------------------

    @Test fun spend_roundTrip() {
        val s = SpendRecord("sp-1", t0, "claude-opus-5", TokenUsage(2_000, 400, 3_500, 0), 0.02175, BackendMode.DIRECT_API, "c-1")
        val e = s.toEntity()
        assertEquals("direct_api", e.backend)
        assertEquals(s, e.toModel())
    }

    // ---- enum names ---------------------------------------------------------------------------------

    @Test fun enumNames_useSerialNames() {
        assertEquals("in_progress", EnumNames.name(SessionStatus.serializer(), SessionStatus.IN_PROGRESS))
        assertEquals(SetKind.AMRAP, EnumNames.parse(SetKind.serializer(), "amrap"))
        assertEquals(SetKind.WORKING, EnumNames.parseOr(SetKind.serializer(), "future_kind", SetKind.WORKING))
        assertNull(Doc.Ok("x").asUnreadableDoc())
    }

    @Test fun columnarVersionIsNeverReset() {
        // An entity of a KNOWN version keeps its column value through the model and back.
        val e = conversationDefault.toEntity()
        assertEquals(e, ok(e.toDoc()).toEntity())
        val g = gym.toEntity()
        assertEquals(g, ok(g.toDoc()).toEntity())
        val p = proposal.toEntity()
        assertEquals(p, ok(p.toDoc()).toEntity())
    }
}
