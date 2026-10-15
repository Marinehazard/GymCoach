package com.gymcoach.core.coach

import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.CheckinAnswerType
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.mutation.MutationValidator
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.time.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** §6.6: the 20 strict schemas, the keyword whitelist, additionalProperties:false, required ⊆ properties, one sample per tool through the mapping table. */
class ProposalSchemasTest {
    private val allowed = setOf("type", "enum", "required", "additionalProperties", "format", "description", "items", "properties")
    private val contract = ToolContract(CoachFixtures.kb, MutationValidator(CoachFixtures.kb, PlannerConstants.DEFAULT), FixedClock(CoachFixtures.NOW))

    @Test
    fun twentyStrictToolsFourReadOnlyThenSixteenProposals() {
        val tools = ToolSchemas.all
        assertEquals(20, tools.size)
        assertEquals(20, tools.map { it.name }.toSet().size)
        assertTrue(tools.all { it.strict })
        assertEquals(ToolSchemas.READ_ONLY_NAMES, tools.take(4).map { it.name })
        assertEquals(16, ToolSchemas.proposals.size)
        assertTrue(ToolSchemas.proposals.all { it.name.startsWith("propose_") })
        assertEquals(ToolContract.SERIAL_NAMES.keys.toList(), ToolSchemas.proposals.map { it.name })
        assertTrue(tools.all { Regex("^[a-zA-Z0-9_-]{1,64}$").matches(it.name) && it.description.isNotBlank() })
    }

    private fun checkSchema(tool: String, path: String, schema: JsonObject) {
        val extra = schema.keys - allowed
        assertTrue(extra.isEmpty(), "$tool $path uses non-whitelisted keywords $extra")
        val type = schema["type"]?.jsonPrimitive?.content
        if (type == "object") {
            assertEquals(false, schema["additionalProperties"]?.jsonPrimitive?.boolean, "$tool $path additionalProperties:false")
            val props = schema["properties"]?.jsonObject ?: fail("$tool $path has no properties")
            val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: fail("$tool $path has no required")
            assertTrue(props.keys.containsAll(required), "$tool $path required ⊆ properties: $required vs ${props.keys}")
            for ((k, v) in props) checkSchema(tool, "$path.$k", v.jsonObject)
        }
        if (type == "array") checkSchema(tool, "$path[]", schema["items"]?.jsonObject ?: fail("$tool $path array without items"))
        schema["enum"]?.let { e -> assertTrue(e.jsonArray.isNotEmpty(), "$tool $path enum") }
        schema["format"]?.let { f -> assertEquals("date", f.jsonPrimitive.content, "$tool $path format") }
        assertTrue(type in setOf("object", "string", "integer", "number", "boolean", "array"), "$tool $path type $type")
    }

    @Test
    fun everySchemaUsesOnlyTheWhitelistAndIsClosed() {
        for (t in ToolSchemas.all) checkSchema(t.name, "input", t.inputSchema)
        val text = ToolSchemas.CONTRACT_JSON
        for (k in listOf("minimum", "maximum", "minLength", "maxLength", "maxItems")) assertTrue(!text.contains("\"$k"), k)
    }

    @Test
    fun everyProposalToolCarriesContractVersionAndRationale() {
        for (t in ToolSchemas.proposals) {
            val props = t.inputSchema["properties"]!!.jsonObject
            assertEquals("{\"type\":\"integer\",\"enum\":[1]}", props["contract_version"].toString(), t.name)
            assertEquals("{\"type\":\"string\"}", props["rationale"].toString(), t.name)
            val required = t.inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue("contract_version" in required && "rationale" in required, t.name)
        }
    }

    @Test
    fun muscleEnumsListThe21MusclesInEnumOrder() {
        val volume = ToolSchemas.byName("propose_change_volume")!!.inputSchema["properties"]!!.jsonObject["muscle"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(Muscle.entries.map { it.name.lowercase() }, volume)
    }

    private fun j(s: String): JsonObject = CoreJson.wire.parseToJsonElement(s).jsonObject

    @Test
    fun oneSampleInputPerToolRoundTripsThroughTheMappingTable() {
        val samples: List<Triple<String, String, (PlanMutation) -> Boolean>> = listOf(
            Triple("propose_swap_exercise", "{\"contract_version\":1,\"from_exercise_id\":\"barbell_bench_press\",\"to_exercise_id\":\"dumbbell_bench_press\",\"scope\":\"today\",\"rationale\":\"TRV-04\"}", { it is PlanMutation.SwapExercise }),
            Triple("propose_change_volume", "{\"contract_version\":1,\"muscle\":\"chest\",\"delta_sets_per_week\":2,\"scope\":\"permanent\",\"rationale\":\"VOL-08\"}", { it is PlanMutation.ChangeVolume && it.muscle == Muscle.CHEST }),
            Triple("propose_change_rep_range", "{\"contract_version\":1,\"exercise_id\":\"barbell_row\",\"rep_lo\":8,\"rep_hi\":12,\"scope\":\"rest_of_mesocycle\",\"rationale\":\"LOAD-03\"}", { it is PlanMutation.ChangeRepRange && it.repHi == 12 }),
            Triple("propose_add_cardio", "{\"contract_version\":1,\"weekday\":3,\"minutes\":30,\"kind\":\"zone2\",\"exercise_id\":\"stationary_bike\",\"rationale\":\"CAR-07\"}", { it is PlanMutation.AddCardio && it.exerciseId == "stationary_bike" }),
            Triple("propose_remove_cardio", "{\"contract_version\":1,\"weekday\":3,\"rationale\":\"CAR-06\"}", { it is PlanMutation.RemoveCardio && it.weekday == 3 }),
            Triple("propose_change_goal", "{\"contract_version\":1,\"goal\":\"strength\",\"start_next_week\":true,\"rationale\":\"LOAD-02\"}", { it is PlanMutation.ChangeGoal && it.startNextWeek }),
            Triple("propose_change_schedule", "{\"contract_version\":1,\"days_per_week\":3,\"preferred_weekdays\":[1,3,5],\"rationale\":\"SPLIT-01\"}", { it is PlanMutation.ChangeSchedule && it.preferredWeekdays == listOf(1, 3, 5) && it.sessionMinutes == null }),
            Triple("propose_checkin_question", "{\"contract_version\":1,\"question\":\"How many hours did you sleep?\",\"answer_type\":\"number\",\"schedule\":\"daily\",\"rationale\":\"SLP-01\"}", { it is PlanMutation.SetCheckinQuestion && it.weeksActive == 4 && it.answerType == CheckinAnswerType.NUMBER }),
            Triple("propose_exclude_exercise", "{\"contract_version\":1,\"exercise_id\":\"back_squat\",\"until\":\"2026-10-01\",\"rationale\":\"INJ-01\"}", { it is PlanMutation.ExcludeExercise && it.until.toString() == "2026-10-01" }),
            Triple("propose_pin_exercise", "{\"contract_version\":1,\"exercise_id\":\"goblet_squat\",\"template_id\":\"LOWER_A\",\"rationale\":\"user likes it\"}", { it is PlanMutation.PinExercise && it.templateId == "LOWER_A" }),
            Triple("propose_adjust_working_load", "{\"contract_version\":1,\"exercise_id\":\"barbell_bench_press\",\"gym_id\":\"gym-home\",\"new_working_load_kg\":82.5,\"rationale\":\"PROG-01\"}", { it is PlanMutation.AdjustWorkingLoad && it.gymScope == "gym-home" && it.newWorkingLoadKg == 82.5 }),
            Triple("propose_set_priority_muscles", "{\"contract_version\":1,\"muscles\":[\"side_delts\",\"calves\"],\"rationale\":\"user goal\"}", { it is PlanMutation.SetPriorityMuscles && it.muscles == listOf(Muscle.SIDE_DELTS, Muscle.CALVES) }),
            Triple("propose_deload", "{\"contract_version\":1,\"start_this_week\":false,\"rationale\":\"DLD-03\"}", { it is PlanMutation.ScheduleDeload && !it.startThisWeek }),
            Triple("propose_set_limitation", "{\"contract_version\":1,\"region\":\"knee\",\"side\":\"left\",\"pain_0_10\":2,\"rationale\":\"INJ-00\"}", { it is PlanMutation.SetLimitation && it.limitation.pain0to10 == 2 }),
            Triple("propose_travel_days", "{\"contract_version\":1,\"from\":\"2026-09-14\",\"to\":\"2026-09-18\",\"gym_id\":\"gym-hotel\",\"rationale\":\"TRV-01\"}", { it is PlanMutation.SetTravelDays && it.gymId == "gym-hotel" }),
            Triple("propose_update_nutrition_targets", "{\"contract_version\":1,\"kcal_target\":2600,\"protein_g_per_kg\":2.0,\"rationale\":\"NUT-16\"}", { it is PlanMutation.UpdateNutritionTargets && it.kcalTarget == 2600 }),
        )
        assertEquals(ToolSchemas.proposals.map { it.name }, samples.map { it.first })
        for ((tool, input, check) in samples) {
            val parsed = contract.parse(tool, j(input))
            if (parsed !is ParsedTool.ProposalCall) fail("$tool → $parsed")
            assertTrue(check(parsed.mutation), "$tool → ${parsed.mutation}")
            val encoded = CoreJson.stored.encodeToJsonElement(PlanMutation.serializer(), parsed.mutation).jsonObject
            assertEquals(ToolContract.SERIAL_NAMES[tool], encoded["type"]!!.jsonPrimitive.content, tool)
            assertEquals(j(input)["rationale"], encoded["rationale"], tool)
            assertSampleMatchesSchema(tool, j(input))
        }
    }

    /** The sample inputs themselves satisfy their strict schema (keys ⊆ properties, required present). */
    private fun assertSampleMatchesSchema(tool: String, input: JsonObject) {
        val schema = ToolSchemas.byName(tool)!!.inputSchema
        val props = schema["properties"]!!.jsonObject
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(props.keys.containsAll(input.keys), "$tool sample keys ${input.keys}")
        assertTrue(input.keys.containsAll(required), "$tool sample lacks required $required")
        for ((k, v) in input) {
            val enum: JsonArray? = props[k]!!.jsonObject["enum"]?.jsonArray
            if (enum != null) assertTrue(enum.contains(v as JsonElement), "$tool $k=$v not in enum")
            if (v is JsonPrimitive && props[k]!!.jsonObject["type"]!!.jsonPrimitive.content == "string") assertTrue(v.isString, "$tool $k")
        }
    }

    @Test
    fun readOnlyToolsParseToReadOnly() {
        for (name in ToolSchemas.READ_ONLY_NAMES) {
            val input = j("{\"query\":\"x\"}")
            assertEquals(ParsedTool.ReadOnly(name, input), contract.parse(name, input))
        }
    }
}
