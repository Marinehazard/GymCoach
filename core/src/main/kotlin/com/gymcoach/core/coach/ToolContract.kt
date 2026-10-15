// core/src/main/kotlin/com/gymcoach/core/coach/ToolContract.kt
package com.gymcoach.core.coach

import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.NutritionTargets
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.mutation.MutationValidator
import com.gymcoach.core.mutation.ValidationResult
import com.gymcoach.core.time.Clock
import com.gymcoach.core.util.IdGen
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

sealed class ParsedTool {
    data class ReadOnly(val kind: String, val args: JsonObject) : ParsedTool()           // kind = tool name
    data class ProposalCall(val mutation: PlanMutation, val rationale: String) : ParsedTool()
    data class Invalid(val errors: List<String>) : ParsedTool()
}

/**
 * §6.6 tool → [PlanMutation] mapping: (1) read and remove `contract_version` (≠ 1 → "unsupported contract_version"),
 * (2) apply the renames / wrapping of the table (`gym_id` → `gym_scope` for adjust_working_load; the flat
 * set_limitation fields wrapped into a [com.gymcoach.core.model.Limitation] dated `clock.today()`), (3) insert
 * `"type": <@SerialName>`, (4) decode with `CoreJson.stored`. Bounds are checked by [validate] → [MutationValidator].
 */
class ToolContract(private val kb: KnowledgeBase, private val validator: MutationValidator, private val clock: Clock) {
    companion object {
        const val CONTRACT_VERSION: Int = 1

        /** tool name → PlanMutation @SerialName (§6.6 table). */
        val SERIAL_NAMES: Map<String, String> = linkedMapOf(
            "propose_swap_exercise" to "swap_exercise",
            "propose_change_volume" to "change_volume",
            "propose_change_rep_range" to "change_rep_range",
            "propose_add_cardio" to "add_cardio",
            "propose_remove_cardio" to "remove_cardio",
            "propose_change_goal" to "change_goal",
            "propose_change_schedule" to "change_schedule",
            "propose_checkin_question" to "set_checkin_question",
            "propose_exclude_exercise" to "exclude_exercise",
            "propose_pin_exercise" to "pin_exercise",
            "propose_adjust_working_load" to "adjust_working_load",
            "propose_set_priority_muscles" to "set_priority_muscles",
            "propose_deload" to "schedule_deload",
            "propose_set_limitation" to "set_limitation",
            "propose_travel_days" to "set_travel_days",
            "propose_update_nutrition_targets" to "update_nutrition_targets",
        )
    }

    fun parse(toolName: String, input: JsonObject): ParsedTool {
        if (ToolSchemas.isReadOnly(toolName)) return ParsedTool.ReadOnly(toolName, input)
        val serialName = SERIAL_NAMES[toolName] ?: return ParsedTool.Invalid(listOf("unknown tool '$toolName'"))
        val version = (input["contract_version"] as? JsonPrimitive)?.intOrNull
        if (version != CONTRACT_VERSION) return ParsedTool.Invalid(listOf("unsupported contract_version"))
        val rationale = (input["rationale"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: return ParsedTool.Invalid(listOf("rationale is required"))

        val fields = LinkedHashMap<String, JsonElement>()
        fields["type"] = JsonPrimitive(serialName)
        for ((k, v) in input) if (k != "contract_version" && v !is JsonNull) fields[k] = v
        when (toolName) {
            "propose_adjust_working_load" -> fields.remove("gym_id")?.let { fields["gym_scope"] = it }
            "propose_set_limitation" -> {
                val lim = LinkedHashMap<String, JsonElement>()
                lim["id"] = JsonPrimitive(IdGen.newId())
                fields.remove("region")?.let { lim["region"] = it }
                fields.remove("side")?.let { lim["side"] = it }
                fields.remove("pain_0_10")?.let { lim["pain_0_10"] = it }
                lim["since"] = JsonPrimitive(clock.today().toString())
                lim["active"] = JsonPrimitive(true)
                lim["limited_range"] = fields.remove("limited_range") ?: JsonPrimitive(false)
                lim["notes"] = fields.remove("notes") ?: JsonPrimitive("")
                fields["limitation"] = JsonObject(lim)
            }
            else -> Unit
        }
        return try {
            val mutation = CoreJson.stored.decodeFromJsonElement(PlanMutation.serializer(), JsonObject(fields))
            ParsedTool.ProposalCall(mutation, rationale)
        } catch (e: Exception) {
            ParsedTool.Invalid(listOf("invalid input for $toolName: ${e.message?.lineSequence()?.firstOrNull() ?: "decode failed"}"))
        }
    }

    fun validate(mutation: PlanMutation, inputs: PlanInputs, mesocycle: Mesocycle?, today: TodayContext?, nutrition: NutritionTargets?): ValidationResult {
        val unknown = unknownIds(mutation)
        if (unknown.isNotEmpty()) return ValidationResult.Invalid(unknown.map { "unknown exercise id '$it'; use lookup_exercises" })
        return validator.validate(mutation, inputs, mesocycle, today, nutrition)
    }

    /** Exercise ids the mutation names that the knowledge base does not know (the coach corrects itself, §6.6). */
    private fun unknownIds(m: PlanMutation): List<String> {
        val ids = when (m) {
            is PlanMutation.SwapExercise -> listOf(m.fromExerciseId, m.toExerciseId)
            is PlanMutation.ChangeRepRange -> listOf(m.exerciseId)
            is PlanMutation.AddCardio -> listOfNotNull(m.exerciseId)
            is PlanMutation.ExcludeExercise -> listOf(m.exerciseId)
            is PlanMutation.PinExercise -> listOf(m.exerciseId)
            is PlanMutation.AdjustWorkingLoad -> listOf(m.exerciseId)
            else -> emptyList()
        }
        return ids.filter { kb.exercise(it) == null }.distinct()
    }
}
