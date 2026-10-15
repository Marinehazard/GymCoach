package com.gymcoach.app.ui.today

import com.gymcoach.core.equipment.Presets
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.Adjustment
import com.gymcoach.core.model.AdjustmentKind
import com.gymcoach.core.model.BodyMetric
import com.gymcoach.core.model.CardioKind
import com.gymcoach.core.model.CardioLog
import com.gymcoach.core.model.CheckinQuestion
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.GymItem
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.PlanDiff
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.Proposal
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.model.SessionPlan
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SessionStatus
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.mutation.PlanDiffer
import com.gymcoach.core.time.Weeks
import com.gymcoach.core.units.Units
import com.gymcoach.core.util.IdGen
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TodayViewModel(
    private val deps: TrainingDeps,
    dispatcher: CoroutineDispatcher = uiDispatcher(),
) : ScopedViewModel(dispatcher) {

    private val initialDate: LocalDate = deps.clock.today()
    private val _state = MutableStateFlow(emptyState(initialDate))
    val state: StateFlow<TodayUiState> = _state

    private val _sheet = MutableStateFlow<TodaySheet?>(null)
    val sheet: StateFlow<TodaySheet?> = _sheet
    private val _cardioDraft = MutableStateFlow<CardioLogDraft?>(null)
    val cardioDraft: StateFlow<CardioLogDraft?> = _cardioDraft
    private val _gymInfo = MutableStateFlow<Map<String, GymAvailability>>(emptyMap())
    val gymInfo: StateFlow<Map<String, GymAvailability>> = _gymInfo
    private val _referral = MutableStateFlow<Explanation?>(null)
    val referral: StateFlow<Explanation?> = _referral
    private val _reminders = MutableStateFlow<List<CheckinQuestion>>(emptyList())
    val reminders: StateFlow<List<CheckinQuestion>> = _reminders
    private val navChannel = Channel<TodayNav>(Channel.BUFFERED)
    val navigation: Flow<TodayNav> = navChannel.receiveAsFlow()

    private val tick = Channel<Unit>(Channel.CONFLATED)
    private var trainAsPlanned = false
    private val dismissedPlanner = HashSet<PlanMutation>()
    private var lastContext: TodayContext? = null

    init {
        scope.launch {
            merge(
                tick.receiveAsFlow(),
                deps.settings.observe().map { },
                deps.workouts.observeOpenSession().map { },
                deps.plans.observeCurrent().map { },
                deps.plans.observeOverrides().map { },
                deps.gyms.observeAll().map { },
                deps.metrics.observeReadiness(2).map { },
                deps.coach.observePendingProposals().map { },
            ).conflate().collectLatest { safeReload() }
        }
        tick.trySend(Unit)
    }

    /** Re-reads everything (also called after every event). */
    fun refresh() { tick.trySend(Unit) }

    // ---- events -------------------------------------------------------------------------------------

    fun onSelectGym(id: String) = launchAction {
        deps.settings.update { it.copy(activeGymId = id) }
        deps.gyms.touchLastUsed(id, deps.clock.now())
        val s = deps.settings.get()
        val ctx = TodayContext(date = deps.clock.today(), gymId = id, spotter = lastContext?.spotter ?: false, minutesOverride = lastContext?.minutesOverride)
        persistContext(s.todayContextJson, ctx)
        _sheet.value = null
    }

    fun onNewGymFromPreset(preset: String) = launchAction {
        val kb = deps.knowledge
        val name = kb.presets[preset]?.name ?: preset
        val gym = Presets.gymFromPreset(kb, preset, name, IdGen.newId(), deps.clock.now())
        deps.gyms.upsert(gym.copy(isDefault = false))
        deps.settings.update { it.copy(activeGymId = gym.id) }
        _sheet.value = null
        navChannel.trySend(TodayNav.GymEdit(gym.id))
    }

    fun onDuplicateCurrentGym() = launchAction {
        val gym = _state.value.gym ?: return@launchAction
        val copy = gym.copy(id = IdGen.newId(), name = gym.name + " (2)", isDefault = false, archived = false,
            createdAt = deps.clock.now(), lastUsedAt = null)
        deps.gyms.upsert(copy)
        deps.settings.update { it.copy(activeGymId = copy.id) }
        _sheet.value = null
        navChannel.trySend(TodayNav.GymEdit(copy.id))
    }

    fun onToggleSpotter() = editContext { it.copy(spotter = !it.spotter) }

    fun onToggleTravelKit() = editContext { ctx ->
        val kit = packableItems()
        val on = kit.isNotEmpty() && ctx.extraItems.map { it.equipmentId }.containsAll(kit.map { it.equipmentId })
        if (on) ctx.copy(extraItems = ctx.extraItems.filter { item -> kit.none { it.equipmentId == item.equipmentId } })
        else ctx.copy(extraItems = ctx.extraItems + kit.filter { k -> ctx.extraItems.none { it.equipmentId == k.equipmentId } })
    }

    fun onRemoveEquipmentToday(ids: Set<String>) = editContext { it.copy(removedEquipmentIds = ids.toList().sorted()) }

    fun onMinutesToday(minutes: Int?) = editContext { it.copy(minutesOverride = minutes) }

    fun onAddExtraItem(equipmentId: String) = editContext { ctx ->
        if (ctx.extraItems.any { it.equipmentId == equipmentId }) ctx else ctx.copy(extraItems = ctx.extraItems + GymItem(equipmentId))
    }

    fun onTrainAsPlanned(value: Boolean) {
        trainAsPlanned = value
        refresh()
    }

    fun onOpenSheet(sheet: TodaySheet?) { _sheet.value = sheet }

    fun onOpenReadiness() { navChannel.trySend(TodayNav.Readiness(deps.clock.today().toString())) }
    fun onResumeSession() { _state.value.openSession?.let { navChannel.trySend(TodayNav.Session(it.id)) } }
    fun onOpenYesterday() { _state.value.yesterday?.let { navChannel.trySend(TodayNav.SessionSummary(it.id)) } }
    fun onOpenOnboarding() { navChannel.trySend(TodayNav.Onboarding) }

    /** Freezes the realized plan into a new IN_PROGRESS record and records every modifier as an Adjustment (§8.6). */
    fun onStartSession() = launchAction {
        val st = _state.value
        val plan = st.sessionPlan ?: return@launchAction
        val gym = st.gym ?: return@launchAction
        if (st.openSession != null) { onResumeSession(); return@launchAction }
        val now = deps.clock.now()
        val record = SessionRecord(
            id = IdGen.newId(), date = st.date, gymId = gym.id, plannedSessionId = plan.plannedSessionId,
            templateId = plan.templateId, plan = plan, status = SessionStatus.IN_PROGRESS, startedAt = now,
            readinessCheckinId = st.readiness?.id,
        )
        deps.workouts.startSession(record)
        for (modifier in plan.modifiers) {
            deps.workouts.recordAdjustment(adjustmentFor(modifier, record.id, OverrideSource.PLANNER, now))
        }
        val readiness = st.readiness
        if (trainAsPlanned && readiness != null && readiness.band != ReadinessBand.GREEN) {
            val e = Explanation(listOf(RuleIds.RDY_02), "Trained as planned although readiness is ${readiness.band.name.lowercase()} (${readiness.score}).")
            deps.workouts.recordAdjustment(adjustmentFor(e, record.id, OverrideSource.USER, now))
        }
        deps.gyms.touchLastUsed(gym.id, now)
        _sheet.value = null
        navChannel.trySend(TodayNav.Session(record.id))
    }

    /** No planner API can move a session; the day is recorded SKIPPED with the reason (the weekly ledger then carries the deficit over, §4.13). */
    fun onSkipToday(reason: String) = launchAction {
        val st = _state.value
        val gym = st.gym ?: return@launchAction
        val now = deps.clock.now()
        val record = SessionRecord(
            id = IdGen.newId(), date = st.date, gymId = gym.id, plannedSessionId = st.sessionPlan?.plannedSessionId,
            templateId = st.sessionPlan?.templateId, plan = null, status = SessionStatus.SKIPPED, skipReason = reason,
        )
        deps.workouts.startSession(record)
        val e = Explanation(listOf(RuleIds.OVERRIDE), "Skipped today's session: $reason")
        deps.workouts.recordAdjustment(adjustmentFor(e, record.id, OverrideSource.USER, now))
    }

    /** ScheduleDeload override (USER) → recordDeloadDecision(date) → recompute("deload") — in exactly this order (§8.6). */
    fun onAcceptDeload() = launchAction {
        val date = deps.clock.today()
        val suggestion = _state.value.deloadSuggestion
        val mutation = PlanMutation.ScheduleDeload(startThisWeek = true, rationale = suggestion?.message ?: "Reactive deload accepted on Today (DLD-03).")
        val now = deps.clock.now()
        deps.plans.addOverride(PlanOverride(id = IdGen.newId(), createdAt = now, source = OverrideSource.USER, mutation = mutation))
        deps.coordinator.recordDeloadDecision(date)
        deps.coordinator.recompute("deload")
        val e = Explanation(listOf(RuleIds.DLD_03), mutation.rationale)
        deps.workouts.recordAdjustment(adjustmentFor(e, null, OverrideSource.USER, now).copy(kind = AdjustmentKind.DELOAD))
    }

    /** Dismiss records the onsets only; no override, no recompute (§8.6). */
    fun onDismissDeload() = launchAction { deps.coordinator.recordDeloadDecision(deps.clock.today()) }

    fun onProposalApply(id: String) = launchAction {
        val p = _state.value.pendingProposals.firstOrNull { it.id == id } ?: deps.coach.proposal(id) ?: return@launchAction
        try {
            val before = deps.plans.getCurrent()
            val override = deps.proposalApplier.apply(p, deps.clock.today())
            val after = deps.coordinator.recompute("proposal")
            val diff = if (before != null) PlanDiffer.between(before, after) else PlanDiff(emptyList(), emptyMap(), emptyMap())
            val e = Explanation(listOf(RuleIds.OVERRIDE), p.rationale)
            deps.workouts.recordAdjustment(adjustmentFor(e, null, OverrideSource.CHAT, deps.clock.now())
                .copy(proposalId = p.id, overrideId = override.id))
            deps.proposalApplier.finalize(p.id, diff, override.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            deps.proposalApplier.markFailed(p.id, e.message ?: "apply failed")
        }
    }

    fun onProposalReject(id: String) = launchAction {
        val p = _state.value.pendingProposals.firstOrNull { it.id == id } ?: return@launchAction
        deps.proposalApplier.reject(p, null)
    }

    fun onProposalLater(id: String) = launchAction {
        val p = _state.value.pendingProposals.firstOrNull { it.id == id } ?: return@launchAction
        deps.proposalApplier.defer(p)
    }

    fun onOpenConversation(proposal: Proposal) { navChannel.trySend(TodayNav.Conversation(proposal.conversationId)) }

    fun onPlannerProposal(mutation: PlanMutation, accept: Boolean) = launchAction {
        if (!accept) { dismissedPlanner.add(mutation); return@launchAction }
        val now = deps.clock.now()
        deps.plans.addOverride(PlanOverride(id = IdGen.newId(), createdAt = now, source = OverrideSource.PLANNER, mutation = mutation))
        deps.coordinator.recompute("planner_proposal")
        val e = Explanation(listOf(RuleIds.OVERRIDE), mutation.rationale)
        deps.workouts.recordAdjustment(adjustmentFor(e, null, OverrideSource.PLANNER, now))
    }

    fun onQuickWeight(value: String) = launchAction {
        val v = value.trim().replace(',', '.').toDoubleOrNull() ?: return@launchAction
        val unit = Units.weightUnitFor(deps.settings.get().unitSystem)
        val now = deps.clock.now()
        deps.metrics.addBodyMetric(BodyMetric(id = IdGen.newId(), date = deps.clock.today(), weight = Units.enteredWeight(v, unit), loggedAt = now))
    }

    // ---- cardio-log sheet -----------------------------------------------------------------------------

    fun onLogCardio() {
        val st = _state.value
        val c = st.cardio
        _cardioDraft.value = CardioLogDraft(
            exerciseId = c?.exerciseId ?: cardioModalities().firstOrNull()?.id ?: "stationary_bike",
            kind = c?.kind ?: CardioKind.ZONE2,
            minutes = (c?.minutes ?: 30).toString(), avgHr = "", rpe = null,
        )
        _sheet.value = TodaySheet.CARDIO_LOG
    }

    fun onCardioDraft(draft: CardioLogDraft) { _cardioDraft.value = draft }

    fun onCardioSave() = launchAction {
        val d = _cardioDraft.value ?: return@launchAction
        val minutes = d.minutes.trim().toIntOrNull() ?: return@launchAction
        deps.metrics.addCardioLog(CardioLog(
            id = IdGen.newId(), date = deps.clock.today(), exerciseId = d.exerciseId, kind = d.kind, minutes = minutes,
            avgHr = d.avgHr.trim().toIntOrNull(), rpe = d.rpe, loggedAt = deps.clock.now(),
        ))
        _cardioDraft.value = null
        _sheet.value = null
    }

    fun cardioModalities(): List<Exercise> = TodayCompute.cardioModalities(deps, _state.value.gym, _state.value.cardio)

    // ---- internals ------------------------------------------------------------------------------------

    private fun launchAction(block: suspend () -> Unit) {
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: e.javaClass.simpleName)
            }
            refresh()
        }
    }

    private fun editContext(f: (TodayContext) -> TodayContext) = launchAction {
        val s = deps.settings.get()
        val gym = pickGym(deps.gyms.getAll(), s.activeGymId) ?: return@launchAction
        val ctx = contextFor(s.todayContextJson, deps.clock.today(), gym)
        persistContext(s.todayContextJson, f(ctx))
    }

    private suspend fun persistContext(@Suppress("UNUSED_PARAMETER") previous: String, ctx: TodayContext) {
        val json = CoreJson.stored.encodeToString(TodayContext.serializer(), ctx.copy(readiness = null))
        deps.settings.update { it.copy(todayContextJson = json) }
    }

    private fun packableItems(): List<GymItem> =
        deps.knowledge.equipment.values.filter { it.packable && !it.sessionFlag }.map { GymItem(it.id) }

    private fun adjustmentFor(e: Explanation, sessionId: String?, source: OverrideSource, now: java.time.Instant) = Adjustment(
        id = IdGen.newId(), sessionId = sessionId, kind = adjustmentKindFor(e.ruleIds), ruleIds = e.ruleIds, summary = e.message,
        inputs = e.inputs, beforeJson = "{}", afterJson = "{}", source = source, createdAt = now,
    )

    private fun pickGym(gyms: List<GymSet>, activeId: String): GymSet? {
        val live = gyms.filter { !it.archived }
        return live.firstOrNull { it.id == activeId } ?: live.firstOrNull { it.isDefault } ?: live.firstOrNull()
    }

    private fun contextFor(json: String, today: LocalDate, gym: GymSet): TodayContext {
        val stored = decodeContext(json)
        return if (stored != null && stored.date == today && stored.gymId == gym.id) stored.copy(readiness = null)
        else TodayContext(date = today, gymId = gym.id)
    }

    private fun decodeContext(json: String): TodayContext? {
        if (json.isBlank()) return null
        return try { CoreJson.stored.decodeFromString(TodayContext.serializer(), json) } catch (e: IllegalArgumentException) { null }
    }

    private suspend fun safeReload() {
        try { reload() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            _state.value = _state.value.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun reload() {
        val s = deps.settings.get()
        val today = deps.clock.today()
        val profile = deps.profile.get()
        val gyms = deps.gyms.getAll()
        val gym = pickGym(gyms, s.activeGymId)
        if (s.todayContextJson.isNotBlank() && decodeContext(s.todayContextJson)?.date != today) {
            deps.settings.update { it.copy(todayContextJson = "") }
        }
        val ctx = gym?.let { contextFor(s.todayContextJson, today, it) }
        val readiness = deps.metrics.readinessFor(today)
        val open = deps.workouts.observeOpenSession().first()
        val meso = deps.plans.getCurrent()
        val noPlan = meso == null || profile == null
        val week = TodayCompute.weekOf(meso, today)
        val planned = week?.sessions?.firstOrNull { it.weekday == Weeks.isoWeekday(today) }
        var plan: SessionPlan? = null
        var error: String? = null
        if (!noPlan && gym != null && ctx != null && open == null) {
            val effective = ctx.copy(readiness = if (trainAsPlanned) null else readiness)
            lastContext = effective
            try {
                plan = withContext(deps.computeDispatcher) { deps.coordinator.realizeToday(effective) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
        } else lastContext = ctx
        val deload = if (noPlan) null else safely { deps.coordinator.deloadSuggestion(today) }
        val proposals = if (noPlan) emptyList() else (safely { deps.coordinator.plannerProposals(today) } ?: emptyList()).filter { it !in dismissedPlanner }
        val kit = packableItems()
        _referral.value = TodayCompute.referral(profile, today)
        _reminders.value = safely { deps.metrics.activeQuestions(today).filter { !it.answered && it.onDate == today } } ?: emptyList()
        _gymInfo.value = TodayCompute.availabilityByGym(deps, gyms, plan, planned)
        _state.value = TodayUiState(
            date = today, gym = gym, gyms = gyms.filter { !it.archived }.sortedByDescending { it.lastUsedAt }, readiness = readiness,
            sessionPlan = plan, plannedSession = planned, openSession = open, weekMode = week?.mode ?: WeekMode.NORMAL,
            restDay = !noPlan && planned == null, trainAsPlanned = trainAsPlanned,
            cardio = plan?.cardio ?: week?.cardio?.firstOrNull { it.weekday == Weeks.isoWeekday(today) },
            cardioLoggedToday = deps.metrics.cardioBetween(today, today).isNotEmpty(),
            pendingProposals = deps.coach.observePendingProposals().first(), plannerProposals = proposals,
            weeklyCheckinDue = deps.metrics.weeklyCheckinFor(Weeks.weekStart(today, s.weekStartsOnMonday)) == null &&
                Weeks.isoWeekday(today) >= s.weeklyCheckinWeekday,
            deloadSuggestion = deload, underTarget = meso?.underTargetReport ?: emptyList(),
            nutritionStrip = TodayCompute.nutritionStrip(deps, today, profile?.bedtimeLocal),
            yesterday = deps.workouts.sessionsBetween(today.minusDays(1), today.minusDays(1)).lastOrNull { it.status == SessionStatus.COMPLETED },
            noPlan = noPlan, loading = false, error = error, minutesToday = ctx?.minutesOverride, spotter = ctx?.spotter ?: false,
            travelKit = kit.isNotEmpty() && ctx != null && ctx.extraItems.map { it.equipmentId }.containsAll(kit.map { it.equipmentId }),
            removedEquipment = ctx?.removedEquipmentIds?.toSet() ?: emptySet(),
        )
    }

    private suspend fun <T> safely(f: suspend () -> T): T? =
        try { f() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    private fun emptyState(date: LocalDate) = TodayUiState(
        date = date, gym = null, gyms = emptyList(), readiness = null, sessionPlan = null, plannedSession = null, openSession = null,
        weekMode = WeekMode.NORMAL, restDay = false, trainAsPlanned = false, cardio = null, cardioLoggedToday = false,
        pendingProposals = emptyList(), plannerProposals = emptyList(), weeklyCheckinDue = false, deloadSuggestion = null,
        underTarget = emptyList(), nutritionStrip = NutritionStrip(null, null, null, null, null), yesterday = null,
        noPlan = false, loading = true, error = null, minutesToday = null, spotter = false, travelKit = false, removedEquipment = emptySet(),
    )
}
