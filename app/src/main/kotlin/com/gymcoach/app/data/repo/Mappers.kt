package com.gymcoach.app.data.repo

import com.gymcoach.app.data.db.AdjustmentEntity
import com.gymcoach.app.data.db.BodyMetricEntity
import com.gymcoach.app.data.db.CardioLogEntity
import com.gymcoach.app.data.db.ChatConversationEntity
import com.gymcoach.app.data.db.ChatMessageEntity
import com.gymcoach.app.data.db.CheckinQuestionEntity
import com.gymcoach.app.data.db.GymEntity
import com.gymcoach.app.data.db.NutritionLogEntity
import com.gymcoach.app.data.db.NutritionStateEntity
import com.gymcoach.app.data.db.OverrideEntity
import com.gymcoach.app.data.db.PlanSnapshotEntity
import com.gymcoach.app.data.db.ProfileEntity
import com.gymcoach.app.data.db.ProgressionStateEntity
import com.gymcoach.app.data.db.ProposalEntity
import com.gymcoach.app.data.db.ReadinessEntity
import com.gymcoach.app.data.db.SessionEntity
import com.gymcoach.app.data.db.SetLogEntity
import com.gymcoach.app.data.db.SpendRecordEntity
import com.gymcoach.app.data.db.WeeklyCheckinEntity
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
import com.gymcoach.core.model.CheckinQuestion
import com.gymcoach.core.model.ConversationStatus
import com.gymcoach.core.model.Effort
import com.gymcoach.core.model.EnteredWeight
import com.gymcoach.core.model.GymItem
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.NutritionLog
import com.gymcoach.core.model.NutritionState
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.ProgressionStatus
import com.gymcoach.core.model.Proposal
import com.gymcoach.core.model.ProposalStatus
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.model.ReadinessCheckin
import com.gymcoach.core.model.SessionPlan
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SessionStatus
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.model.Side
import com.gymcoach.core.model.SpendRecord
import com.gymcoach.core.model.TokenUsage
import com.gymcoach.core.model.WeeklyCheckin
import com.gymcoach.core.model.WeightUnit
import com.gymcoach.core.repo.UnreadableDoc
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A decoded stored document, or the marker that it was written by a newer app version (§7.2).
 * Repositories drop [Unreadable] rows from lists and report them to UnreadableDocsRepository.
 */
sealed class Doc<T> {
    data class Ok<T>(val value: T) : Doc<T>()
    data class Unreadable<T>(val type: String, val version: Int, val id: String) : Doc<T>()

    fun valueOrNull(): T? = when (this) {
        is Ok -> value
        is Unreadable -> null
    }

    fun asUnreadableDoc(): UnreadableDoc? = when (this) {
        is Ok -> null
        is Unreadable -> UnreadableDoc(type, version, id)
    }
}

// ---- shared helpers ------------------------------------------------------------------------------

private val STRING_LIST: KSerializer<List<String>> = ListSerializer(String.serializer())
private val STRING_MAP: KSerializer<Map<String, String>> = MapSerializer(String.serializer(), String.serializer())
private val GYM_ITEMS: KSerializer<List<GymItem>> = ListSerializer(GymItem.serializer())

internal fun Instant.millis(): Long = toEpochMilli()
internal fun Long.instant(): Instant = Instant.ofEpochMilli(this)
internal fun String.localDate(): LocalDate = LocalDate.parse(this)

/** `schema_version` key of a JSON document; a missing key (or a non-object document) counts as 1 (§7.2). */
fun storedSchemaVersion(text: String, json: Json = CoreJson.stored): Int {
    val element = json.parseToJsonElement(text)
    val obj = element as? JsonObject ?: return 1
    val v = obj["schema_version"] as? JsonPrimitive ?: return 1
    return v.intOrNull ?: 1
}

/** Version gate for a JSON document: newer than [DocVersions.KNOWN] → Unreadable, else decoded. */
fun <T> decodeVersioned(type: String, id: String, text: String, serializer: KSerializer<T>, json: Json = CoreJson.stored): Doc<T> {
    val version = storedSchemaVersion(text, json)
    if (DocVersions.isNewer(type, version)) return Doc.Unreadable(type, version, id)
    return Doc.Ok(json.decodeFromString(serializer, text))
}

/** Version gate for a columnar document: the column value is compared, never reset (§7.2). */
private inline fun <T> columnar(type: String, id: String, schemaVersion: Int, build: () -> T): Doc<T> =
    if (DocVersions.isNewer(type, schemaVersion)) Doc.Unreadable(type, schemaVersion, id) else Doc.Ok(build())

/** The PlanMutation discriminator ("swap_exercise", …) as written by CoreJson.stored (classDiscriminator = "type"). */
fun mutationType(mutation: PlanMutation, json: Json = CoreJson.stored): String =
    json.encodeToJsonElement(PlanMutation.serializer(), mutation).jsonObject["type"]?.jsonPrimitive?.content ?: ""

// ---- profile ------------------------------------------------------------------------------------------

fun Profile.toEntity(json: Json = CoreJson.stored): ProfileEntity =
    ProfileEntity(id = 1, json = json.encodeToString(Profile.serializer(), this), updatedAt = updatedAt.millis())

fun ProfileEntity.toModel(json: Json = CoreJson.stored): Profile = json.decodeFromString(Profile.serializer(), this.json)

// ---- body metrics -------------------------------------------------------------------------------------

fun BodyMetric.toEntity(): BodyMetricEntity = BodyMetricEntity(
    id = id,
    date = date.toString(),
    enteredWeight = weight?.entered,
    enteredWeightUnit = weight?.unit?.let { EnumNames.name(WeightUnit.serializer(), it) },
    weightKg = weight?.kg,
    waistCm = waistCm,
    restingHr = restingHr,
    steps = steps,
    note = note,
    loggedAt = loggedAt.millis(),
)

fun BodyMetricEntity.toModel(): BodyMetric {
    val entered = enteredWeight
    val unit = enteredWeightUnit
    val kg = weightKg
    return BodyMetric(
        id = id,
        date = date.localDate(),
        weight = if (entered != null && unit != null && kg != null) EnteredWeight(entered, EnumNames.parse(WeightUnit.serializer(), unit), kg) else null,
        waistCm = waistCm,
        restingHr = restingHr,
        steps = steps,
        note = note,
        loggedAt = loggedAt.instant(),
    )
}

// ---- gyms (columnar document "GymSet") ----------------------------------------------------------------

fun GymSet.toEntity(json: Json = CoreJson.stored): GymEntity = GymEntity(
    id = id,
    name = name,
    presetOrigin = presetOrigin,
    itemsJson = json.encodeToString(GYM_ITEMS, items),
    defaultSessionMinutes = defaultSessionMinutes,
    colorIndex = colorIndex,
    travelKitMerged = travelKitMerged,
    notes = notes,
    isDefault = isDefault,
    archived = archived,
    createdAt = createdAt.millis(),
    lastUsedAt = lastUsedAt?.millis(),
    schemaVersion = schemaVersion,
)

fun GymEntity.toDoc(json: Json = CoreJson.stored): Doc<GymSet> = columnar(DocVersions.GYM_SET, id, schemaVersion) {
    GymSet(
        id = id,
        name = name,
        presetOrigin = presetOrigin,
        items = json.decodeFromString(GYM_ITEMS, itemsJson),
        defaultSessionMinutes = defaultSessionMinutes,
        colorIndex = colorIndex,
        travelKitMerged = travelKitMerged,
        notes = notes,
        isDefault = isDefault,
        archived = archived,
        createdAt = createdAt.instant(),
        lastUsedAt = lastUsedAt?.instant(),
        schemaVersion = schemaVersion,
    )
}

// ---- sessions (plan_json is a "SessionPlan" document) -------------------------------------------------

fun SessionRecord.toEntity(json: Json = CoreJson.stored): SessionEntity = SessionEntity(
    id = id,
    date = date.toString(),
    gymId = gymId,
    plannedSessionId = plannedSessionId,
    templateId = templateId,
    planJson = plan?.let { json.encodeToString(SessionPlan.serializer(), it) },
    status = EnumNames.name(SessionStatus.serializer(), status),
    startedAt = startedAt?.millis(),
    finishedAt = finishedAt?.millis(),
    sessionRpe = sessionRpe,
    durationMinutes = durationMinutes,
    readinessCheckinId = readinessCheckinId,
    skipReason = skipReason,
    notes = notes,
)

fun SessionEntity.toDoc(json: Json = CoreJson.stored): Doc<SessionRecord> {
    val text = planJson
    var plan: SessionPlan? = null
    if (text != null) {
        when (val d = decodeVersioned(DocVersions.SESSION_PLAN, id, text, SessionPlan.serializer(), json)) {
            is Doc.Ok -> plan = d.value
            is Doc.Unreadable -> return Doc.Unreadable(d.type, d.version, d.id)
        }
    }
    return Doc.Ok(
        SessionRecord(
            id = id,
            date = date.localDate(),
            gymId = gymId,
            plannedSessionId = plannedSessionId,
            templateId = templateId,
            plan = plan,
            status = EnumNames.parse(SessionStatus.serializer(), status),
            startedAt = startedAt?.instant(),
            finishedAt = finishedAt?.instant(),
            sessionRpe = sessionRpe,
            durationMinutes = durationMinutes,
            readinessCheckinId = readinessCheckinId,
            skipReason = skipReason,
            notes = notes,
        )
    )
}

// ---- set logs (sacred: every field round-trips exactly) -----------------------------------------------

fun SetLog.toEntity(): SetLogEntity = SetLogEntity(
    id = id,
    sessionId = sessionId,
    blockId = blockId,
    exerciseId = exerciseId,
    setIndex = setIndex,
    kind = EnumNames.name(SetKind.serializer(), kind),
    enteredWeight = weight?.entered,
    enteredWeightUnit = weight?.unit?.let { EnumNames.name(WeightUnit.serializer(), it) },
    weightKg = weight?.kg,
    perHand = perHand,
    reps = reps,
    seconds = seconds,
    metres = metres,
    rir = rir,
    side = EnumNames.name(Side.serializer(), side),
    prescribedLoadKg = prescribedLoadKg,
    prescribedRepsLo = prescribedReps?.lo,
    prescribedRepsHi = prescribedReps?.hi,
    prescribedRir = prescribedRir,
    loggedAt = loggedAt.millis(),
    supersededById = supersededById,
    supersedesId = supersedesId,
    note = note,
)

fun SetLogEntity.toModel(): SetLog {
    val entered = enteredWeight
    val unit = enteredWeightUnit
    val kg = weightKg
    val lo = prescribedRepsLo
    val hi = prescribedRepsHi
    return SetLog(
        id = id,
        sessionId = sessionId,
        blockId = blockId,
        exerciseId = exerciseId,
        setIndex = setIndex,
        kind = EnumNames.parse(SetKind.serializer(), kind),
        weight = if (entered != null && unit != null && kg != null) EnteredWeight(entered, EnumNames.parse(WeightUnit.serializer(), unit), kg) else null,
        perHand = perHand,
        reps = reps,
        seconds = seconds,
        metres = metres,
        rir = rir,
        side = EnumNames.parse(Side.serializer(), side),
        prescribedLoadKg = prescribedLoadKg,
        prescribedReps = if (lo != null && hi != null && lo <= hi) Band(lo, hi) else null,
        prescribedRir = prescribedRir,
        loggedAt = loggedAt.instant(),
        supersededById = supersededById,
        supersedesId = supersedesId,
        note = note,
    )
}

// ---- adjustments ---------------------------------------------------------------------------------------

fun Adjustment.toEntity(json: Json = CoreJson.stored): AdjustmentEntity = AdjustmentEntity(
    id = id,
    sessionId = sessionId,
    exerciseId = exerciseId,
    kind = EnumNames.name(AdjustmentKind.serializer(), kind),
    ruleIds = ruleIds.joinToString(","),
    summary = summary,
    inputsJson = json.encodeToString(STRING_MAP, inputs),
    beforeJson = beforeJson,
    afterJson = afterJson,
    source = EnumNames.name(OverrideSource.serializer(), source),
    proposalId = proposalId,
    overrideId = overrideId,
    createdAt = createdAt.millis(),
    undoneAt = undoneAt?.millis(),
)

fun AdjustmentEntity.toModel(json: Json = CoreJson.stored): Adjustment = Adjustment(
    id = id,
    sessionId = sessionId,
    exerciseId = exerciseId,
    kind = EnumNames.parse(AdjustmentKind.serializer(), kind),
    ruleIds = ruleIds.split(",").map { it.trim() }.filter { it.isNotEmpty() },
    summary = summary,
    inputs = json.decodeFromString(STRING_MAP, inputsJson),
    beforeJson = beforeJson,
    afterJson = afterJson,
    source = EnumNames.parse(OverrideSource.serializer(), source),
    proposalId = proposalId,
    overrideId = overrideId,
    createdAt = createdAt.instant(),
    undoneAt = undoneAt?.instant(),
)

// ---- progression states ("ProgressionState" document) ------------------------------------------------

fun ProgressionState.toEntity(json: Json = CoreJson.stored): ProgressionStateEntity = ProgressionStateEntity(
    key = key.id,
    exerciseId = key.exerciseId,
    gymScope = key.gymScope,
    status = EnumNames.name(ProgressionStatus.serializer(), status),
    json = json.encodeToString(ProgressionState.serializer(), this),
    updatedAt = updatedAt.millis(),
)

fun ProgressionStateEntity.toDoc(json: Json = CoreJson.stored): Doc<ProgressionState> =
    decodeVersioned(DocVersions.PROGRESSION_STATE, key, this.json, ProgressionState.serializer(), json)

// ---- overrides (columnar document "PlanOverride") -----------------------------------------------------

fun PlanOverride.toEntity(json: Json = CoreJson.stored): OverrideEntity = OverrideEntity(
    id = id,
    createdAt = createdAt.millis(),
    source = EnumNames.name(OverrideSource.serializer(), source),
    proposalId = proposalId,
    mutationType = mutationType(mutation, json),
    mutationJson = json.encodeToString(PlanMutation.serializer(), mutation),
    revokedAt = revokedAt?.millis(),
    expiresOn = expiresOn?.toString(),
    schemaVersion = schemaVersion,
)

fun OverrideEntity.toDoc(json: Json = CoreJson.stored): Doc<PlanOverride> = columnar(DocVersions.PLAN_OVERRIDE, id, schemaVersion) {
    PlanOverride(
        id = id,
        createdAt = createdAt.instant(),
        source = EnumNames.parse(OverrideSource.serializer(), source),
        proposalId = proposalId,
        mutation = json.decodeFromString(PlanMutation.serializer(), mutationJson),
        revokedAt = revokedAt?.instant(),
        expiresOn = expiresOn?.localDate(),
        schemaVersion = schemaVersion,
    )
}

// ---- plan snapshots ("Mesocycle" document) -------------------------------------------------------------

fun Mesocycle.toSnapshotEntity(computedAt: Instant, isCurrent: Boolean, json: Json = CoreJson.stored): PlanSnapshotEntity = PlanSnapshotEntity(
    id = id,
    inputsHash = inputsHash,
    mesocycleJson = json.encodeToString(Mesocycle.serializer(), this),
    computedAt = computedAt.millis(),
    isCurrent = isCurrent,
)

fun PlanSnapshotEntity.toDoc(json: Json = CoreJson.stored): Doc<Mesocycle> =
    decodeVersioned(DocVersions.MESOCYCLE, id, mesocycleJson, Mesocycle.serializer(), json)

// ---- nutrition state ----------------------------------------------------------------------------------

fun NutritionState.toEntity(json: Json = CoreJson.stored): NutritionStateEntity =
    NutritionStateEntity(id = 1, json = json.encodeToString(NutritionState.serializer(), this), updatedAt = updatedAt.millis())

fun NutritionStateEntity.toModel(json: Json = CoreJson.stored): NutritionState = json.decodeFromString(NutritionState.serializer(), this.json)

// ---- readiness ("ReadinessCheckin" document + queryable columns) --------------------------------------

fun ReadinessCheckin.toEntity(json: Json = CoreJson.stored): ReadinessEntity = ReadinessEntity(
    id = id,
    date = date.toString(),
    json = json.encodeToString(ReadinessCheckin.serializer(), this),
    score = score,
    band = EnumNames.name(ReadinessBand.serializer(), band),
    loggedAt = loggedAt.millis(),
)

fun ReadinessEntity.toDoc(json: Json = CoreJson.stored): Doc<ReadinessCheckin> =
    decodeVersioned(DocVersions.READINESS_CHECKIN, id, this.json, ReadinessCheckin.serializer(), json)

// ---- weekly check-ins / questions ---------------------------------------------------------------------

fun WeeklyCheckin.toEntity(json: Json = CoreJson.stored): WeeklyCheckinEntity = WeeklyCheckinEntity(
    id = id,
    weekStart = weekStart.toString(),
    json = json.encodeToString(WeeklyCheckin.serializer(), this),
    completedAt = completedAt?.millis(),
)

fun WeeklyCheckinEntity.toDoc(json: Json = CoreJson.stored): Doc<WeeklyCheckin> =
    decodeVersioned(DocVersions.WEEKLY_CHECKIN, id, this.json, WeeklyCheckin.serializer(), json)

fun CheckinQuestion.toEntity(json: Json = CoreJson.stored): CheckinQuestionEntity = CheckinQuestionEntity(
    id = id,
    json = json.encodeToString(CheckinQuestion.serializer(), this),
    answered = answered,
    activeUntil = activeUntil?.toString(),
)

fun CheckinQuestionEntity.toDoc(json: Json = CoreJson.stored): Doc<CheckinQuestion> =
    decodeVersioned(DocVersions.CHECKIN_QUESTION, id, this.json, CheckinQuestion.serializer(), json)

// ---- nutrition / cardio logs (columns only) -----------------------------------------------------------

fun NutritionLog.toEntity(): NutritionLogEntity = NutritionLogEntity(
    id = id,
    date = date.toString(),
    kcal = kcal,
    proteinG = proteinG,
    proteinTargetHit = proteinTargetHit,
    mealsHeaviness = mealsHeaviness,
    alcoholDrinks = alcoholDrinks,
    travelDay = travelDay,
    note = note,
    loggedAt = loggedAt.millis(),
)

fun NutritionLogEntity.toModel(): NutritionLog = NutritionLog(
    id = id,
    date = date.localDate(),
    kcal = kcal,
    proteinG = proteinG,
    proteinTargetHit = proteinTargetHit,
    mealsHeaviness = mealsHeaviness,
    alcoholDrinks = alcoholDrinks,
    travelDay = travelDay,
    note = note,
    loggedAt = loggedAt.instant(),
)

fun CardioLog.toEntity(): CardioLogEntity = CardioLogEntity(
    id = id,
    date = date.toString(),
    sessionId = sessionId,
    exerciseId = exerciseId,
    kind = EnumNames.name(CardioKind.serializer(), kind),
    minutes = minutes,
    avgHr = avgHr,
    rpe = rpe,
    note = note,
    loggedAt = loggedAt.millis(),
)

fun CardioLogEntity.toModel(): CardioLog = CardioLog(
    id = id,
    date = date.localDate(),
    sessionId = sessionId,
    exerciseId = exerciseId,
    kind = EnumNames.parse(CardioKind.serializer(), kind),
    minutes = minutes,
    avgHr = avgHr,
    rpe = rpe,
    note = note,
    loggedAt = loggedAt.instant(),
)

// ---- chat conversations (columnar document "ChatConversation") ----------------------------------------

fun ChatConversation.toEntity(): ChatConversationEntity = ChatConversationEntity(
    id = id,
    startedAt = startedAt.millis(),
    endedAt = endedAt?.millis(),
    backend = EnumNames.name(BackendMode.serializer(), backend),
    model = model,
    effort = EnumNames.name(Effort.serializer(), effort),
    systemPromptHash = systemPromptHash,
    toolContractVersion = toolContractVersion,
    seedSummary = seedSummary,
    status = EnumNames.name(ConversationStatus.serializer(), status),
    assistantTurns = assistantTurns,
    lastInputTokens = lastInputTokens,
    isDefault = isDefault,
    schemaVersion = schemaVersion,
)

fun ChatConversationEntity.toDoc(): Doc<ChatConversation> = columnar(DocVersions.CHAT_CONVERSATION, id, schemaVersion) {
    ChatConversation(
        id = id,
        startedAt = startedAt.instant(),
        endedAt = endedAt?.instant(),
        backend = EnumNames.parse(BackendMode.serializer(), backend),
        model = model,
        effort = EnumNames.parse(Effort.serializer(), effort),
        systemPromptHash = systemPromptHash,
        toolContractVersion = toolContractVersion,
        seedSummary = seedSummary,
        status = EnumNames.parse(ConversationStatus.serializer(), status),
        assistantTurns = assistantTurns,
        lastInputTokens = lastInputTokens,
        isDefault = isDefault,
        schemaVersion = schemaVersion,
    )
}

// ---- chat messages (content_json verbatim) ------------------------------------------------------------

fun ChatMessage.toEntity(json: Json = CoreJson.stored): ChatMessageEntity = ChatMessageEntity(
    id = id,
    conversationId = conversationId,
    seq = seq,
    role = EnumNames.name(ChatRole.serializer(), role),
    contentJson = contentJson,
    displayText = displayText,
    createdAt = createdAt.millis(),
    usageJson = usage?.let { json.encodeToString(TokenUsage.serializer(), it) },
    requestId = requestId,
    stopReason = stopReason,
    stopDetailsJson = stopDetailsJson,
    caveatAppended = caveatAppended,
    toolResultsJson = toolResultsJson,
)

fun ChatMessageEntity.toModel(json: Json = CoreJson.stored): ChatMessage = ChatMessage(
    id = id,
    conversationId = conversationId,
    seq = seq,
    role = EnumNames.parse(ChatRole.serializer(), role),
    contentJson = contentJson,
    displayText = displayText,
    createdAt = createdAt.instant(),
    usage = usageJson?.let { json.decodeFromString(TokenUsage.serializer(), it) },
    requestId = requestId,
    stopReason = stopReason,
    stopDetailsJson = stopDetailsJson,
    caveatAppended = caveatAppended,
    toolResultsJson = toolResultsJson,
)

// ---- proposals (columnar document "Proposal") ---------------------------------------------------------

fun Proposal.toEntity(json: Json = CoreJson.stored): ProposalEntity = ProposalEntity(
    id = id,
    conversationId = conversationId,
    assistantMessageId = assistantMessageId,
    toolName = toolName,
    contractVersion = contractVersion,
    payloadJson = payloadJson,
    mutationJson = mutation?.let { json.encodeToString(PlanMutation.serializer(), it) },
    rationale = rationale,
    status = EnumNames.name(ProposalStatus.serializer(), status),
    warningsJson = json.encodeToString(STRING_LIST, validationWarnings),
    errorsJson = json.encodeToString(STRING_LIST, validationErrors),
    createdAt = createdAt.millis(),
    expiresOn = expiresOn.toString(),
    decidedAt = decidedAt?.millis(),
    overrideId = overrideId,
    resultSummary = resultSummary,
    resultSent = resultSent,
    schemaVersion = schemaVersion,
)

fun ProposalEntity.toDoc(json: Json = CoreJson.stored): Doc<Proposal> = columnar(DocVersions.PROPOSAL, id, schemaVersion) {
    Proposal(
        id = id,
        conversationId = conversationId,
        assistantMessageId = assistantMessageId,
        toolName = toolName,
        contractVersion = contractVersion,
        payloadJson = payloadJson,
        mutation = mutationJson?.let { json.decodeFromString(PlanMutation.serializer(), it) },
        rationale = rationale,
        status = EnumNames.parse(ProposalStatus.serializer(), status),
        validationWarnings = json.decodeFromString(STRING_LIST, warningsJson),
        validationErrors = json.decodeFromString(STRING_LIST, errorsJson),
        createdAt = createdAt.instant(),
        expiresOn = expiresOn.localDate(),
        decidedAt = decidedAt?.instant(),
        overrideId = overrideId,
        resultSummary = resultSummary,
        resultSent = resultSent,
        schemaVersion = schemaVersion,
    )
}

// ---- spend records ------------------------------------------------------------------------------------

fun SpendRecord.toEntity(json: Json = CoreJson.stored): SpendRecordEntity = SpendRecordEntity(
    id = id,
    at = at.millis(),
    model = model,
    usageJson = json.encodeToString(TokenUsage.serializer(), usage),
    costUsd = costUsd,
    backend = EnumNames.name(BackendMode.serializer(), backend),
    conversationId = conversationId,
)

fun SpendRecordEntity.toModel(json: Json = CoreJson.stored): SpendRecord = SpendRecord(
    id = id,
    at = at.instant(),
    model = model,
    usage = json.decodeFromString(TokenUsage.serializer(), usageJson),
    costUsd = costUsd,
    backend = EnumNames.parse(BackendMode.serializer(), backend),
    conversationId = conversationId,
)
