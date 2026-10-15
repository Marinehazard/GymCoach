// core/src/main/kotlin/com/gymcoach/core/coach/ToolExecutor.kt
package com.gymcoach.core.coach

import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.WeightUnit
import com.gymcoach.core.progression.E1rm
import com.gymcoach.core.repo.GymRepository
import com.gymcoach.core.repo.PlanRepository
import com.gymcoach.core.repo.ProgressionRepository
import com.gymcoach.core.repo.WorkoutRepository
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class ToolExecution(val resultJson: String, val isError: Boolean)          // resultJson ≤ 2,000 chars

/**
 * §6.6 read-only tools, executed on the phone in the same turn. `get_gym_equipment` without `gym_id` → the gym of
 * `today.gymId` (§6.5 fallback chain: unknown/archived → default gym → any active gym); every `available` flag is
 * resolved against `SessionEquipment(today)` (extras, removals, spotter). Invalid input → `isError` with
 * `{"status":"invalid","errors":[…]}` so the model corrects itself. Results are compact JSON of at most
 * [MAX_RESULT_CHARS] characters (lists are shortened, never cut mid-JSON).
 */
class ToolExecutor(
    private val kb: KnowledgeBase, private val workouts: WorkoutRepository, private val progression: ProgressionRepository,
    private val gyms: GymRepository, private val plans: PlanRepository,
) {
    companion object {
        const val MAX_RESULT_CHARS: Int = 2000
        const val MAX_LOOKUP_RESULTS: Int = 10
        const val MAX_HISTORY_DAYS: Int = 90

        fun invalid(vararg errors: String): ToolExecution = ToolExecution(errorJson("invalid", errors.toList()), isError = true)

        fun errorJson(status: String, errors: List<String>): String =
            CoreJson.wire.encodeToString(JsonElement.serializer(), JsonObject(linkedMapOf("status" to JsonPrimitive(status), "errors" to JsonArray(errors.map { JsonPrimitive(it) }))))

        /** is_error rule of §6.7: the stored result is an object whose `status` is "invalid" or "error". */
        fun isErrorResult(resultJson: String): Boolean = try {
            val el = CoreJson.wire.parseToJsonElement(resultJson)
            val status = ((el as? JsonObject)?.get("status") as? JsonPrimitive)?.contentOrNull
            status == "invalid" || status == "error"
        } catch (e: Exception) {
            false
        }
    }

    private val resolver = EquipmentResolver(kb)

    suspend fun execute(name: String, input: JsonObject, today: TodayContext): ToolExecution = try {
        when (name) {
            "lookup_exercises" -> lookup(input, today)
            "get_exercise_history" -> history(input, today)
            "get_rule" -> rule(input)
            "get_gym_equipment" -> gymEquipment(input, today)
            else -> invalid("unknown read-only tool '$name'")
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolExecution(errorJson("error", listOf("tool failed on the phone: ${e.message ?: e.toString()}")), isError = true)
    }

    // ---- lookup_exercises -------------------------------------------------------------------------------------------

    private suspend fun lookup(input: JsonObject, today: TodayContext): ToolExecution {
        val query = string(input, "query") ?: ""
        val patternId = string(input, "pattern")?.takeIf { it.isNotBlank() }
        val muscleId = string(input, "muscle")?.takeIf { it.isNotBlank() }
        val availableOnly = (input["available_only"] as? JsonPrimitive)?.booleanOrNull ?: false
        val pattern = patternId?.let { p -> MovementPattern.entries.firstOrNull { serialName(it) == p } ?: return invalid("unknown pattern '$p'; use one of the patterns listed in the prompt") }
        val muscle = muscleId?.let { m -> Muscle.entries.firstOrNull { serialName(it) == m } ?: return invalid("unknown muscle '$m'; use one of the muscles listed in the prompt") }
        if (query.isBlank() && pattern == null && muscle == null) return invalid("give a query, a pattern or a muscle")
        val equipment = sessionEquipment(today)
        val candidates = kb.searchByNameOrAlias(query)
            .filter { pattern == null || it.pattern == pattern }
            .filter { muscle == null || it.weightOf(muscle) > 0.0 }
        val rows = ArrayList<JsonObject>()
        for (e in candidates) {
            val available = equipment?.let { resolver.resolve(e, it).available } ?: false
            if (availableOnly && !available) continue
            rows.add(exerciseRow(e, available, equipment))
            if (rows.size >= MAX_LOOKUP_RESULTS) break
        }
        return ToolExecution(fitArray(rows), isError = false)
    }

    private fun exerciseRow(e: Exercise, available: Boolean, equipment: SessionEquipment?): JsonObject {
        val muscles = LinkedHashMap<String, JsonElement>()
        for (mw in e.muscles) muscles[serialName(mw.muscle)] = num(mw.weight)
        val subs = e.substitutions.mapNotNull { kb.exercise(it) }.map { s ->
            val avail = equipment?.let { resolver.resolve(s, it).available } ?: false
            JsonPrimitive(if (avail) s.id else "${s.id} (unavailable)")
        }.take(5)
        val flags = ArrayList<JsonElement>()
        if (e.flags.compound) flags.add(JsonPrimitive("compound"))
        if (e.flags.spinalLoading) flags.add(JsonPrimitive("spinal_loading"))
        if (e.flags.shoulderSensitive) flags.add(JsonPrimitive("shoulder_sensitive"))
        if (e.flags.kneeSensitive) flags.add(JsonPrimitive("knee_sensitive"))
        if (e.flags.lowBackSensitive) flags.add(JsonPrimitive("low_back_sensitive"))
        if (e.flags.requiresSpotterOrSafeties) flags.add(JsonPrimitive("requires_spotter_or_safeties"))
        return JsonObject(linkedMapOf(
            "id" to JsonPrimitive(e.id),
            "name" to JsonPrimitive(e.name),
            "pattern" to JsonPrimitive(serialName(e.pattern)),
            "muscles" to JsonObject(muscles),
            "available" to JsonPrimitive(available),
            "substitutions" to JsonArray(subs),
            "flags" to JsonArray(flags),
            "difficulty" to JsonPrimitive(e.difficulty.name.lowercase(Locale.ROOT)),
        ))
    }

    // ---- get_exercise_history ---------------------------------------------------------------------------------------

    private suspend fun history(input: JsonObject, today: TodayContext): ToolExecution {
        val id = string(input, "exercise_id") ?: return invalid("exercise_id is required")
        val exercise = kb.exercise(id) ?: return invalid("unknown exercise id '$id'; use lookup_exercises")
        val days = (input["days"] as? JsonPrimitive)?.intOrNull ?: return invalid("days must be an integer 1 to $MAX_HISTORY_DAYS")
        if (days !in 1..MAX_HISTORY_DAYS) return invalid("days must be 1 to $MAX_HISTORY_DAYS, got $days")
        val from = today.date.minusDays((days - 1).toLong())
        val sets = workouts.setsForExercise(exercise.id, MAX_HISTORY_DAYS).filter { it.supersededById == null && it.kind != SetKind.RAMP }
        val bySession = LinkedHashMap<String, MutableList<SetLog>>()
        for (s in sets) bySession.getOrPut(s.sessionId) { ArrayList() }.add(s)
        val sessions = ArrayList<Pair<LocalDate, JsonObject>>()
        for ((sessionId, list) in bySession) {
            val date = workouts.getSession(sessionId)?.date ?: list.first().loggedAt.atZone(ZoneOffset.UTC).toLocalDate()
            if (date.isBefore(from) || date.isAfter(today.date)) continue
            val best = list.maxWithOrNull(compareBy<SetLog>({ it.weight?.kg ?: 0.0 }, { it.reps ?: 0 }, { it.seconds ?: 0 })) ?: continue
            val m = LinkedHashMap<String, JsonElement>()
            m["date"] = JsonPrimitive(date.toString())
            m["best"] = JsonPrimitive(bestLabel(best))
            val kg = best.weight?.kg
            val reps = best.reps
            val est = if (kg != null && reps != null && kg > 0.0) E1rm.estimate(kg, reps, best.rir, date) else null
            m["e1rm"] = est?.let { num(it.kg) } ?: JsonNull
            m["confidence"] = est?.let { JsonPrimitive(it.confidence.name.lowercase(Locale.ROOT)) } ?: JsonNull
            sessions.add(Pair(date, JsonObject(m)))
        }
        sessions.sortByDescending { it.first }
        val states = progression.getForExercise(exercise.id)
        val state = states.firstOrNull { it.key.gymScope == today.gymId } ?: states.firstOrNull { it.key.gymScope == null } ?: states.firstOrNull()
        var rows = sessions.map { it.second }
        while (true) {
            val text = encode(JsonObject(linkedMapOf("sessions" to JsonArray(rows), "state" to stateJson(state))))
            if (text.length <= MAX_RESULT_CHARS || rows.isEmpty()) return ToolExecution(text, isError = false)
            rows = rows.dropLast(1)
        }
    }

    private fun bestLabel(s: SetLog): String {
        val w = s.weight
        val load = when {
            w == null -> "BW"
            w.unit == WeightUnit.LB -> fmt(w.entered) + "lb"
            else -> fmt(w.entered)
        }
        val volume = when {
            s.reps != null -> "x" + s.reps
            s.seconds != null -> "x" + s.seconds + "s"
            s.metres != null -> "x" + s.metres + "m"
            else -> ""
        }
        val rir = s.rir?.let { "@$it" } ?: ""
        return load + volume + rir
    }

    private fun stateJson(state: ProgressionState?): JsonElement {
        if (state == null) return JsonNull
        return JsonObject(linkedMapOf(
            "working_kg" to (state.workingLoadKg?.let { num(it) } ?: JsonNull),
            "reps" to JsonPrimitive("${state.workingReps.lo}-${state.workingReps.hi}"),
            "target_rir" to JsonPrimitive(state.targetRir),
            "status" to JsonPrimitive(state.status.name.lowercase(Locale.ROOT)),
            "gym_scope" to (state.key.gymScope?.let { JsonPrimitive(it) } ?: JsonNull),
        ))
    }

    // ---- get_rule ---------------------------------------------------------------------------------------------------

    private fun rule(input: JsonObject): ToolExecution {
        val id = string(input, "rule_id")?.trim()?.uppercase(Locale.ROOT) ?: return invalid("rule_id is required")
        val e = kb.rules.get(id) ?: return invalid("unknown rule id '$id'; cite only ids from the digest")
        val constants = LinkedHashMap<String, JsonElement>()
        for ((k, v) in e.constants) constants[k] = num(v)
        var statement = e.statement
        while (true) {
            val text = encode(JsonObject(linkedMapOf(
                "id" to JsonPrimitive(e.id),
                "statement" to JsonPrimitive(statement),
                "confidence" to JsonPrimitive(e.confidence.name.lowercase(Locale.ROOT)),
                "citation" to JsonPrimitive(e.citation),
                "constants" to JsonObject(constants),
            )))
            if (text.length <= MAX_RESULT_CHARS || statement.length < 40) return ToolExecution(text, isError = false)
            statement = statement.take(statement.length - (text.length - MAX_RESULT_CHARS) - 2) + "…"
        }
    }

    // ---- get_gym_equipment ------------------------------------------------------------------------------------------

    private suspend fun gymEquipment(input: JsonObject, today: TodayContext): ToolExecution {
        val requested = string(input, "gym_id")?.takeIf { it.isNotBlank() }
        val equipment: SessionEquipment
        if (requested == null || requested == today.gymId) {
            equipment = sessionEquipment(today) ?: return invalid("no gym is configured")
        } else {
            val gym = gyms.get(requested)?.takeUnless { it.archived } ?: return invalid("unknown gym id '$requested'")
            equipment = SessionEquipment(gym)
        }
        val available = resolver.availableExerciseIds(equipment).size
        val full = equipment.items.map { item ->
            val params = LinkedHashMap<String, JsonElement>()
            kb.equipment[item.equipmentId]?.params?.forEach { spec -> spec.default?.let { params[spec.key] = it } }
            for ((k, v) in item.params) params[k] = v
            JsonObject(linkedMapOf("id" to JsonPrimitive(item.equipmentId), "params" to JsonObject(params)))
        }
        val overridesOnly = equipment.items.map { item ->
            JsonObject(linkedMapOf("id" to JsonPrimitive(item.equipmentId), "params" to JsonObject(item.params)))
        }
        val gymJson = JsonObject(linkedMapOf("id" to JsonPrimitive(equipment.gym.id), "name" to JsonPrimitive(equipment.gym.name)))
        for (items in listOf(full, overridesOnly)) {
            val text = encode(JsonObject(linkedMapOf("gym" to gymJson, "items" to JsonArray(items), "available_exercises" to JsonPrimitive(available))))
            if (text.length <= MAX_RESULT_CHARS) return ToolExecution(text, isError = false)
        }
        var ids = equipment.items.map { JsonPrimitive(it.equipmentId) as JsonElement }
        while (true) {
            val text = encode(JsonObject(linkedMapOf("gym" to gymJson, "items" to JsonArray(ids), "available_exercises" to JsonPrimitive(available), "truncated" to JsonPrimitive(true))))
            if (text.length <= MAX_RESULT_CHARS || ids.isEmpty()) return ToolExecution(text, isError = false)
            ids = ids.dropLast(1)
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private suspend fun sessionEquipment(today: TodayContext): SessionEquipment? {
        val gym = resolveGym(today.gymId) ?: return null
        return SessionEquipment(gym, today.extraItems, today.removedEquipmentIds.toSet(), today.spotter)
    }

    private suspend fun resolveGym(gymId: String): GymSet? =
        gyms.get(gymId)?.takeUnless { it.archived } ?: gyms.getAll().firstOrNull { it.isDefault } ?: gyms.getAll().firstOrNull()

    private fun fitArray(rows: List<JsonObject>): String {
        var list = rows
        while (true) {
            val text = encode(JsonArray(list))
            if (text.length <= MAX_RESULT_CHARS || list.size <= 1) {
                if (text.length <= MAX_RESULT_CHARS) return text
                val first = list.first()
                val slim = JsonObject(first.filterKeys { it != "substitutions" && it != "muscles" })
                return encode(JsonArray(listOf(slim)))
            }
            list = list.dropLast(1)
        }
    }

    private fun string(input: JsonObject, key: String): String? = (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun encode(el: JsonElement): String = CoreJson.wire.encodeToString(JsonElement.serializer(), el)

    private fun serialName(p: MovementPattern): String = MovementPattern.serializer().descriptor.getElementName(p.ordinal)
    private fun serialName(m: Muscle): String = Muscle.serializer().descriptor.getElementName(m.ordinal)

    private fun num(v: Double): JsonPrimitive {
        val r = Math.round(v * 10.0) / 10.0
        return if (r == Math.floor(r) && kotlin.math.abs(r) < 1e15) JsonPrimitive(r.toLong()) else JsonPrimitive(r)
    }

    private fun fmt(v: Double): String = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
}
