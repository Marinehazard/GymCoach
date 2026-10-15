// core/src/main/kotlin/com/gymcoach/core/coach/CoachContextBuilder.kt
package com.gymcoach.core.coach

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.CheckinQuestion
import com.gymcoach.core.model.EnergyModel
import com.gymcoach.core.model.GymItem
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.MuscleTier
import com.gymcoach.core.model.NutritionTargets
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.ProgressionStatus
import com.gymcoach.core.model.Proposal
import com.gymcoach.core.model.ProposalStatus
import com.gymcoach.core.model.ReadinessCheckin
import com.gymcoach.core.model.SessionPlan
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SessionStatus
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.UnitSystem
import com.gymcoach.core.model.WeeklyCheckin
import com.gymcoach.core.model.WeightUnit
import com.gymcoach.core.time.Weeks
import com.gymcoach.core.volume.MuscleTiers
import com.gymcoach.core.volume.WeeklyLedger
import java.time.LocalDate
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class ContextInputs(
    val today: TodayContext, val units: UnitSystem, val profile: Profile, val trainingAge: TrainingAge, val weightMa7Kg: Double?,
    val gym: GymSet, val availableExerciseIds: List<String>, val mesocycle: Mesocycle?, val weekIndex: Int, val weekSessions: List<SessionRecord>,
    val ledger: WeeklyLedger?, val todaySession: SessionPlan?, val readiness7d: List<ReadinessCheckin>, val states: List<ProgressionState>,
    val recentSessions: List<SessionRecord>, val recentSets: List<SetLog>, val openQuestions: List<CheckinQuestion>, val lastCheckin: WeeklyCheckin?,
    val nutrition: NutritionTargets?, val energy: EnergyModel?, val trendKgPerWeek: Double?, val pendingProposals: List<Proposal>, val appliedSince: List<Proposal>,
)

/** [gym] (additive, WP-05): the newly resolved gym, rendered as `gym_today` {id, name} when [gymChanged]. */
data class DeltaInputs(
    val today: LocalDate?, val sessionsLogged: List<SessionRecord>, val sets: List<SetLog>, val proposals: List<Proposal>, val profileChanged: Boolean,
    val gymChanged: Boolean, val planChangedFields: List<String>, val readiness: ReadinessCheckin?, val checkinAnswered: Boolean,
    val gym: GymSet? = null,
)

/**
 * §6.5 context assembly. The snapshot is compact JSON with the fixed key order of the SPEC example, numbers rounded
 * to one decimal, logged loads quoted exactly as entered (value + unit). Over [snapshot]'s token budget the
 * truncation order is: `recent` 14 → 7 → 3 days, `available_exercises` ids → pattern counts, `readiness.last7`,
 * `week.sessions` (today + tomorrow kept); every applied step is listed in `"truncated"`.
 */
class CoachContextBuilder(private val kb: KnowledgeBase, private val tokens: TokenEstimator) {
    companion object {
        const val MAX_RECENT_SESSIONS: Int = 8
        /** POL-01: logged numbers are quoted exactly as entered (value + unit), never rounded or converted. */
        val LOGGED_NUMBERS_RULE: String = RuleIds.POL_01
        val WEEKDAYS: List<String> = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }

    private data class Level(val recentDays: Int = 14, val availableAsCounts: Boolean = false, val dropLast7: Boolean = false, val trimWeek: Boolean = false)

    fun snapshot(inputs: ContextInputs, budgetTokens: Int = 2500): String {
        val levels = listOf(
            Level(),
            Level(recentDays = 7),
            Level(recentDays = 3),
            Level(recentDays = 3, availableAsCounts = true),
            Level(recentDays = 3, availableAsCounts = true, dropLast7 = true),
            Level(recentDays = 3, availableAsCounts = true, dropLast7 = true, trimWeek = true),
        )
        var text = ""
        for (level in levels) {
            text = "<context schema=\"1\">" + encode(snapshotJson(inputs, level)) + "</context>"
            if (tokens.estimate(text) <= budgetTokens) return text
        }
        return text
    }

    fun delta(inputs: DeltaInputs): String = delta(inputs, emptyMap())

    /** [proposalResults] (HOME_BRIDGE, §6.13): tool_use id → the result JSON that a tool_result would have carried. */
    fun delta(inputs: DeltaInputs, proposalResults: Map<String, String>): String {
        val m = LinkedHashMap<String, JsonElement>()
        inputs.today?.let { m["today"] = JsonPrimitive(it.toString()) }
        if (inputs.sessionsLogged.isNotEmpty()) m["sessions_logged"] = JsonArray(inputs.sessionsLogged.sortedBy { it.date }.map { sessionJson(it, inputs.sets) })
        val groups = LinkedHashMap<String, JsonElement>()
        for (status in ProposalStatus.entries) {
            val ids = inputs.proposals.filter { it.status == status }.map { JsonPrimitive(it.id) }
            if (ids.isNotEmpty()) groups[lower(status.name)] = JsonArray(ids)
        }
        if (proposalResults.isNotEmpty()) {
            val r = LinkedHashMap<String, JsonElement>()
            for ((id, json) in proposalResults) r[id] = parseOrString(json)
            groups["results"] = JsonObject(r)
        }
        if (groups.isNotEmpty()) m["proposals"] = JsonObject(groups)
        if (inputs.profileChanged) m["profile_changed"] = JsonPrimitive(true)
        if (inputs.gymChanged) {
            m["gym_changed"] = JsonPrimitive(true)
            inputs.gym?.let { m["gym_today"] = JsonObject(linkedMapOf("id" to JsonPrimitive(it.id), "name" to JsonPrimitive(it.name))) }
        }
        if (inputs.planChangedFields.isNotEmpty()) m["plan_changed"] = JsonArray(inputs.planChangedFields.distinct().map { JsonPrimitive(it) })
        inputs.readiness?.let { m["readiness"] = JsonObject(linkedMapOf("date" to JsonPrimitive(it.date.toString()), "score" to JsonPrimitive(it.score), "band" to JsonPrimitive(lower(it.band.name)))) }
        if (inputs.checkinAnswered) m["checkin_answered"] = JsonPrimitive(true)
        return "<delta schema=\"1\">" + encode(JsonObject(m)) + "</delta>"
    }

    fun summaryBlock(summary: String): String = "<summary>" + summary.trim() + "</summary>"

    // ---- snapshot ---------------------------------------------------------------------------------------------------

    private fun snapshotJson(inp: ContextInputs, level: Level): JsonObject {
        val truncated = ArrayList<String>()
        if (level.recentDays < 14) truncated.add("recent")
        if (level.availableAsCounts) truncated.add("available_exercises")
        if (level.dropLast7) truncated.add("readiness.last7")
        if (level.trimWeek) truncated.add("week.sessions")
        val m = LinkedHashMap<String, JsonElement>()
        m["today"] = JsonPrimitive(inp.today.date.toString())
        m["units"] = JsonPrimitive(lower(inp.units.name))
        m["profile"] = profileJson(inp)
        m["gym_today"] = gymJson(inp)
        m["available_exercises"] = if (level.availableAsCounts) availableCounts(inp.availableExerciseIds) else JsonArray(inp.availableExerciseIds.map { JsonPrimitive(it) })
        m["mesocycle"] = mesocycleJson(inp)
        m["week"] = weekJson(inp, level.trimWeek)
        m["ledger"] = ledgerJson(inp)
        m["today_session"] = todaySessionJson(inp.todaySession)
        m["readiness"] = readinessJson(inp, level.dropLast7)
        m["progression_flags"] = JsonArray(inp.states.filter { it.status == ProgressionStatus.PLATEAU || it.status == ProgressionStatus.REGRESSING }
            .sortedBy { it.key.id }.map { flagJson(it) })
        m["recent"] = recentJson(inp, level.recentDays)
        m["checkin"] = checkinJson(inp)
        m["nutrition"] = nutritionJson(inp)
        m["proposals"] = JsonObject(linkedMapOf(
            "pending" to JsonArray(inp.pendingProposals.map { JsonPrimitive(describe(it)) }),
            "applied_since_last_conversation" to JsonArray(inp.appliedSince.map { JsonPrimitive(describe(it)) }),
        ))
        if (truncated.isNotEmpty()) m["truncated"] = JsonArray(truncated.map { JsonPrimitive(it) })
        return JsonObject(m)
    }

    private fun profileJson(inp: ContextInputs): JsonObject {
        val p = inp.profile
        return JsonObject(linkedMapOf(
            "sex" to JsonPrimitive(lower(p.sex.name)),
            "age" to JsonPrimitive(p.ageOn(inp.today.date)),
            "height_cm" to num(p.height.cm),
            "weight_ma7_kg" to numOrNull(inp.weightMa7Kg),
            "training_age" to JsonPrimitive(lower(inp.trainingAge.name)),
            "goal" to JsonPrimitive(lower(p.goal.name)),
            "days" to JsonPrimitive(p.daysPerWeek),
            "minutes" to JsonPrimitive(p.sessionMinutes),
            "limitations" to JsonArray(p.limitations.filter { it.active }.map { l ->
                JsonObject(linkedMapOf(
                    "region" to JsonPrimitive(lower(l.region.name)), "side" to JsonPrimitive(lower(l.side.name)),
                    "pain" to JsonPrimitive(l.pain0to10), "since" to JsonPrimitive(l.since.toString()),
                ))
            }),
            "medical_gate" to JsonPrimitive(p.medicalFlags.isNotEmpty() && p.clearanceConfirmedOn == null),
            "priority_muscles" to JsonArray(p.priorityMuscles.map { JsonPrimitive(lower(it.name)) }),
        ))
    }

    private fun gymJson(inp: ContextInputs): JsonObject {
        val g = inp.gym
        val items = g.items.filter { it.equipmentId !in inp.today.removedEquipmentIds } + inp.today.extraItems
        val m = LinkedHashMap<String, JsonElement>()
        m["id"] = JsonPrimitive(g.id)
        m["name"] = JsonPrimitive(g.name)
        m["origin"] = g.presetOrigin?.let { JsonPrimitive(it) } ?: JsonNull
        m["session_minutes"] = JsonPrimitive(inp.today.minutesOverride ?: g.defaultSessionMinutes ?: inp.profile.sessionMinutes)
        m["equipment"] = JsonArray(items.map { JsonPrimitive(itemLabel(it)) })
        if (inp.today.spotter) m["spotter"] = JsonPrimitive(true)
        if (g.id != inp.today.gymId) m["fallback"] = JsonPrimitive(if (g.isDefault) "default" else "any")
        return JsonObject(m)
    }

    private fun itemLabel(item: GymItem): String {
        if (item.params.isEmpty()) return item.equipmentId
        val parts = item.params.entries.sortedBy { it.key }.map { (k, v) -> "$k=" + paramText(v) }
        return item.equipmentId + "(" + parts.joinToString(", ") + ")"
    }

    private fun paramText(v: JsonElement): String = when (v) {
        is JsonArray -> v.joinToString("/") { paramText(it) }
        is JsonPrimitive -> {
            val c = v.contentOrNull ?: "null"
            val d = if (v.isString) null else c.toDoubleOrNull()
            if (d != null) fmt(round1(d)) else c
        }
        else -> v.toString()
    }

    private fun availableCounts(ids: List<String>): JsonObject {
        val counts = LinkedHashMap<String, Int>()
        for (id in ids) {
            val e = kb.exercise(id) ?: continue
            val key = lower(e.pattern.name)
            counts[key] = (counts[key] ?: 0) + 1
        }
        val m = LinkedHashMap<String, JsonElement>()
        for ((k, v) in counts) m[k] = JsonPrimitive(v)
        return JsonObject(m)
    }

    private fun mesocycleJson(inp: ContextInputs): JsonElement {
        val meso = inp.mesocycle ?: return JsonNull
        val week = meso.weeks.firstOrNull { it.index == inp.weekIndex }
        return JsonObject(linkedMapOf(
            "week" to JsonPrimitive(inp.weekIndex),
            "of" to JsonPrimitive(meso.weeks.size),
            "mode" to (week?.let { JsonPrimitive(lower(it.mode.name)) } ?: JsonNull),
            "rir_offset" to (week?.let { JsonPrimitive(it.rirOffset) } ?: JsonNull),
            "deload_week" to (meso.deloadWeekIndex?.let { JsonPrimitive(it) } ?: JsonNull),
            "split" to JsonPrimitive(lower(meso.split.name)),
            "gym" to JsonPrimitive(if (meso.gymId == inp.gym.id) inp.gym.name else meso.gymId),
        ))
    }

    private fun weekJson(inp: ContextInputs, trim: Boolean): JsonElement {
        val meso = inp.mesocycle ?: return JsonNull
        val week = meso.weeks.firstOrNull { it.index == inp.weekIndex } ?: return JsonNull
        val todayWd = Weeks.isoWeekday(inp.today.date)
        val tomorrowWd = if (todayWd == 7) 1 else todayWd + 1
        val sessions = week.sessions.sortedBy { it.weekday }.filter { !trim || it.weekday == todayWd || it.weekday == tomorrowWd }.map { s ->
            val record = inp.weekSessions.firstOrNull { it.plannedSessionId == s.id }
            val date = week.startDate.plusDays((s.weekday - 1).toLong())
            val status = when {
                record != null && record.status != SessionStatus.PLANNED -> lower(record.status.name)
                s.weekday == todayWd && date == inp.today.date -> "planned_today"
                date.isBefore(inp.today.date) -> "not_logged"
                else -> "planned"
            }
            val m = LinkedHashMap<String, JsonElement>()
            m["day"] = JsonPrimitive(WEEKDAYS[(s.weekday - 1).coerceIn(0, 6)])
            m["template"] = JsonPrimitive(s.templateId)
            m["status"] = JsonPrimitive(status)
            m["sets"] = JsonPrimitive(s.slots.sumOf { it.sets })
            if (status == "planned_today" && inp.gym.id != meso.gymId) m["gym"] = JsonPrimitive(inp.gym.name)
            JsonObject(m)
        }
        val cardio = week.cardio.sortedBy { it.weekday }.map { c ->
            JsonObject(linkedMapOf(
                "day" to JsonPrimitive(WEEKDAYS[(c.weekday - 1).coerceIn(0, 6)]), "exercise_id" to JsonPrimitive(c.exerciseId),
                "minutes" to JsonPrimitive(c.minutes), "kind" to JsonPrimitive(lower(c.kind.name)),
            ))
        }
        return JsonObject(linkedMapOf("sessions" to JsonArray(sessions), "cardio" to JsonArray(cardio)))
    }

    private fun ledgerJson(inp: ContextInputs): JsonElement {
        val l = inp.ledger ?: return JsonNull
        val tiers = MuscleTiers.assign(inp.profile.priorityMuscles)
        val muscles = tiers.entries.filter { it.value == MuscleTier.TIER_1 || it.value == MuscleTier.TIER_2 }.map { it.key }
        val targets = LinkedHashMap<String, JsonElement>()
        val achieved = LinkedHashMap<String, JsonElement>()
        val remaining = LinkedHashMap<String, JsonElement>()
        val under = ArrayList<JsonElement>()
        for (mu in muscles) {
            val key = lower(mu.name)
            l.targets[mu]?.let { targets[key] = num(it) }
            l.achieved[mu]?.let { achieved[key] = num(it.fractional) }
            l.plannedRemaining[mu]?.let { remaining[key] = num(it) }
            val d = l.deficit(mu)
            if (d > 0.0) under.add(JsonPrimitive("$key -" + fmt(round2(d))))
        }
        return JsonObject(linkedMapOf(
            "targets" to JsonObject(targets), "achieved" to JsonObject(achieved),
            "planned_remaining" to JsonObject(remaining), "under_target" to JsonArray(under),
        ))
    }

    private fun todaySessionJson(plan: SessionPlan?): JsonElement {
        if (plan == null) return JsonNull
        val blocks = plan.blocks.sortedBy { it.order }.map { b ->
            val working = b.sets.filter { it.kind != SetKind.RAMP }
            val first = working.firstOrNull()
            val m = LinkedHashMap<String, JsonElement>()
            m["ex"] = JsonPrimitive(b.exerciseId)
            b.substitutedFor?.let { m["sub_for"] = JsonPrimitive(it) }
            m["sets"] = JsonPrimitive(working.size)
            first?.reps?.let { m["reps"] = JsonPrimitive("${it.lo}-${it.hi}") }
            first?.seconds?.let { m["seconds"] = JsonPrimitive("${it.lo}-${it.hi}") }
            val load = first?.loadLabel ?: first?.loadKg?.let { fmt(round1(it)) + " kg" }
            load?.let { m["load"] = JsonPrimitive(it) }
            first?.rir?.let { m["rir"] = JsonPrimitive(it) }
            JsonObject(m)
        }
        val m = LinkedHashMap<String, JsonElement>()
        m["template"] = JsonPrimitive(plan.templateId)
        m["mode"] = JsonPrimitive(lower(plan.mode.name))
        m["blocks"] = JsonArray(blocks)
        plan.cardio?.let { c -> m["cardio"] = JsonPrimitive("${c.exerciseId} ${c.minutes} min ${lower(c.kind.name)}") }
        m["estimated_minutes"] = num(plan.estimatedMinutes)
        m["modifiers"] = JsonArray(plan.modifiers.map { JsonPrimitive(it.ruleIds.joinToString(",") + ": " + it.message) })
        return JsonObject(m)
    }

    private fun readinessJson(inp: ContextInputs, dropLast7: Boolean): JsonElement {
        val byDate = inp.readiness7d.groupBy { it.date }.mapValues { (_, v) -> v.maxByOrNull { it.loggedAt }!! }
        val today = inp.today.readiness ?: byDate[inp.today.date]
        if (today == null && byDate.isEmpty()) return JsonNull
        val m = LinkedHashMap<String, JsonElement>()
        m["today"] = today?.let { JsonPrimitive(it.score) } ?: JsonNull
        m["band"] = today?.let { JsonPrimitive(lower(it.band.name)) } ?: JsonNull
        if (!dropLast7) {
            val from = inp.today.date.minusDays(6)
            m["last7"] = JsonArray(byDate.values.filter { !it.date.isBefore(from) && !it.date.isAfter(inp.today.date) }.sortedBy { it.date }.map { JsonPrimitive(it.score) })
        }
        return JsonObject(m)
    }

    private fun flagJson(s: ProgressionState): JsonObject = JsonObject(linkedMapOf(
        "ex" to JsonPrimitive(s.key.exerciseId),
        "status" to JsonPrimitive(lower(s.status.name)),
        "step" to JsonPrimitive(s.plateauStep),
        "working_kg" to numOrNull(s.workingLoadKg),
        "best_e1rm_14d" to numOrNull(s.bestE1rm14d),
    ))

    private fun recentJson(inp: ContextInputs, days: Int): JsonArray {
        val from = inp.today.date.minusDays(days.toLong())
        val sessions = inp.recentSessions
            .filter { it.status == SessionStatus.COMPLETED && it.date.isAfter(from) && !it.date.isAfter(inp.today.date) }
            .sortedWith(compareByDescending<SessionRecord> { it.date }.thenBy { it.id })
            .take(MAX_RECENT_SESSIONS)
        return JsonArray(sessions.map { sessionJson(it, inp.recentSets) })
    }

    private fun sessionJson(s: SessionRecord, sets: List<SetLog>): JsonObject {
        val own = sets.filter { it.sessionId == s.id && it.supersededById == null && it.kind != SetKind.RAMP }
        val byExercise = LinkedHashMap<String, MutableList<SetLog>>()
        for (x in own.sortedBy { it.loggedAt }) byExercise.getOrPut(x.exerciseId) { ArrayList() }.add(x)
        val top = byExercise.map { (ex, list) ->
            val best = list.maxWithOrNull(compareBy<SetLog>({ it.weight?.kg ?: 0.0 }, { it.reps ?: 0 }, { it.seconds ?: 0 }))!!
            val m = LinkedHashMap<String, JsonElement>()
            m["ex"] = JsonPrimitive(ex)
            val w = best.weight
            if (w != null) {
                if (w.unit == WeightUnit.LB) m["lb"] = exact(w.entered) else m["kg"] = exact(w.entered)
            }
            best.reps?.let { m["reps"] = JsonPrimitive(it) }
            best.seconds?.let { m["seconds"] = JsonPrimitive(it) }
            best.rir?.let { m["rir"] = JsonPrimitive(it) }
            JsonObject(m)
        }
        return JsonObject(linkedMapOf(
            "date" to JsonPrimitive(s.date.toString()),
            "template" to (s.templateId?.let { JsonPrimitive(it) } ?: JsonNull),
            "top_sets" to JsonArray(top),
        ))
    }

    private fun checkinJson(inp: ContextInputs): JsonObject {
        val last = inp.lastCheckin
        val answers = LinkedHashMap<String, JsonElement>()
        last?.answers?.forEach { a ->
            val v = a.number?.let { fmt(round1(it)) } ?: a.yes?.let { if (it) "yes" else "no" } ?: a.text
            if (v != null) answers[a.questionId] = JsonPrimitive(v)
        }
        val currentWeek = Weeks.weekStart(inp.today.date, true)
        val pending = last == null || last.completedAt == null || last.weekStart.isBefore(currentWeek.minusDays(7))
        return JsonObject(linkedMapOf(
            "open_questions" to JsonArray(inp.openQuestions.map { JsonPrimitive(it.id) }),
            "last_answers" to JsonObject(answers),
            "pending_checkin" to JsonPrimitive(pending),
        ))
    }

    private fun nutritionJson(inp: ContextInputs): JsonElement {
        val n = inp.nutrition ?: return JsonNull
        return JsonObject(linkedMapOf(
            "tdee_est" to (inp.energy?.let { JsonPrimitive(it.tdeeEstimateKcal) } ?: JsonNull),
            "target_kcal" to JsonPrimitive(n.kcal),
            "protein_g" to JsonPrimitive(n.proteinG),
            "weight_ma7" to numOrNull(inp.weightMa7Kg),
            "trend_kg_per_week" to (inp.trendKgPerWeek?.let { JsonPrimitive(round2(it)) } ?: JsonNull),
            "next_review" to (n.nextReviewDate?.let { JsonPrimitive(it.toString()) } ?: JsonNull),
        ))
    }

    /** "propose_add_cardio weekday=3 minutes=30 kind=zone2" — the tool name plus the payload fields (no rationale). */
    fun describe(p: Proposal): String {
        val payload = try { CoreJson.wire.parseToJsonElement(p.payloadJson) as? JsonObject } catch (e: Exception) { null }
        val parts = payload?.entries?.filter { it.key != "contract_version" && it.key != "rationale" }?.map { (k, v) -> "$k=" + paramText(v) } ?: emptyList()
        return (listOf(p.toolName) + parts).joinToString(" ")
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private fun encode(el: JsonElement): String = CoreJson.wire.encodeToString(JsonElement.serializer(), el)
    private fun parseOrString(json: String): JsonElement = try { CoreJson.wire.parseToJsonElement(json) } catch (e: Exception) { JsonPrimitive(json) }
    private fun lower(s: String): String = s.lowercase(Locale.ROOT)
    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    private fun num(v: Double): JsonPrimitive {
        val r = round1(v)
        return if (r == Math.floor(r) && kotlin.math.abs(r) < 1e15) JsonPrimitive(r.toLong()) else JsonPrimitive(r)
    }
    /** Logged numbers are quoted exactly as entered (never rounded, Hard rule 1). */
    private fun exact(v: Double): JsonPrimitive = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) JsonPrimitive(v.toLong()) else JsonPrimitive(v)
    private fun numOrNull(v: Double?): JsonElement = v?.let { num(it) } ?: JsonNull
    private fun fmt(v: Double): String = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
}
