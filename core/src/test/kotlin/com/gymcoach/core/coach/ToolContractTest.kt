package com.gymcoach.core.coach

import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.LimitationRegion
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.Side
import com.gymcoach.core.mutation.MutationValidator
import com.gymcoach.core.mutation.ValidationResult
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.time.FixedClock
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class ToolContractTest {
    private val kb = CoachFixtures.kb
    private val clock = FixedClock(Instant.parse("2026-09-09T07:00:00Z"))
    private val contract = ToolContract(kb, MutationValidator(kb, PlannerConstants.DEFAULT), clock)
    private val inputs = CoachFixtures.inputs()

    private fun j(s: String): JsonObject = CoreJson.wire.parseToJsonElement(s).jsonObject
    private fun parse(tool: String, s: String): ParsedTool = contract.parse(tool, j(s))
    private fun mutation(tool: String, s: String): PlanMutation = (parse(tool, s) as ParsedTool.ProposalCall).mutation
    private fun validate(m: PlanMutation, mesocycle: com.gymcoach.core.model.Mesocycle? = inputs.mesocycle): ValidationResult =
        contract.validate(m, inputs.planInputs, mesocycle, inputs.today, inputs.nutrition)

    @Test
    fun unknownOrMissingContractVersionIsRejected() {
        assertEquals(ParsedTool.Invalid(listOf("unsupported contract_version")), parse("propose_deload", "{\"contract_version\":2,\"start_this_week\":true,\"rationale\":\"r\"}"))
        assertEquals(ParsedTool.Invalid(listOf("unsupported contract_version")), parse("propose_deload", "{\"start_this_week\":true,\"rationale\":\"r\"}"))
        assertEquals(1, ToolContract.CONTRACT_VERSION)
    }

    @Test
    fun unknownToolAndMissingRationaleAndBadEnumAreInvalid() {
        assertIs<ParsedTool.Invalid>(parse("propose_teleport", "{\"contract_version\":1,\"rationale\":\"r\"}"))
        assertEquals(ParsedTool.Invalid(listOf("rationale is required")), parse("propose_deload", "{\"contract_version\":1,\"start_this_week\":true}"))
        assertIs<ParsedTool.Invalid>(parse("propose_change_goal", "{\"contract_version\":1,\"goal\":\"bulking\",\"start_next_week\":true,\"rationale\":\"r\"}"))
        assertIs<ParsedTool.Invalid>(parse("propose_change_volume", "{\"contract_version\":1,\"muscle\":\"chest\",\"scope\":\"permanent\",\"rationale\":\"r\"}"))
    }

    @Test
    fun setLimitationIsWrappedWithTheClockDateAndDefaults() {
        val m = mutation("propose_set_limitation", "{\"contract_version\":1,\"region\":\"shoulder\",\"side\":\"right\",\"pain_0_10\":2,\"rationale\":\"INJ-02\"}") as PlanMutation.SetLimitation
        assertEquals(LimitationRegion.SHOULDER, m.limitation.region)
        assertEquals(Side.RIGHT, m.limitation.side)
        assertEquals(2, m.limitation.pain0to10)
        assertEquals(LocalDate.of(2026, 9, 9), m.limitation.since)
        assertTrue(m.limitation.active)
        assertEquals(false, m.limitation.limitedRange)
        assertEquals("", m.limitation.notes)
        assertTrue(m.limitation.id.isNotBlank())
        assertEquals("INJ-02", m.rationale)
        val withOptionals = mutation("propose_set_limitation", "{\"contract_version\":1,\"region\":\"knee\",\"side\":\"both\",\"pain_0_10\":1,\"limited_range\":true,\"notes\":\"stairs\",\"rationale\":\"r\"}") as PlanMutation.SetLimitation
        assertEquals(true, withOptionals.limitation.limitedRange)
        assertEquals("stairs", withOptionals.limitation.notes)
    }

    @Test
    fun adjustWorkingLoadRenamesGymIdToGymScope() {
        val m = mutation("propose_adjust_working_load", "{\"contract_version\":1,\"exercise_id\":\"barbell_bench_press\",\"gym_id\":\"gym-home\",\"new_working_load_kg\":82.5,\"rationale\":\"PROG-01\"}") as PlanMutation.AdjustWorkingLoad
        assertEquals("gym-home", m.gymScope)
    }

    @Test
    fun validSwapInTheHomeGymIsValid() {
        val m = mutation("propose_swap_exercise", "{\"contract_version\":1,\"from_exercise_id\":\"barbell_bench_press\",\"to_exercise_id\":\"dumbbell_bench_press\",\"scope\":\"rest_of_mesocycle\",\"rationale\":\"TRV-04\"}")
        assertIs<ValidationResult.Valid>(validate(m))
    }

    @Test
    fun unknownExerciseIdIsAnInstructiveError() {
        val m = mutation("propose_swap_exercise", "{\"contract_version\":1,\"from_exercise_id\":\"barbell_bench_press\",\"to_exercise_id\":\"db_rdl\",\"scope\":\"today\",\"rationale\":\"r\"}")
        assertEquals(ValidationResult.Invalid(listOf("unknown exercise id 'db_rdl'; use lookup_exercises")), validate(m))
    }

    @Test
    fun everyRejectionPathReturnsInvalid() {
        val rejections = listOf(
            "propose_swap_exercise" to "{\"contract_version\":1,\"from_exercise_id\":\"goblet_squat\",\"to_exercise_id\":\"dumbbell_bench_press\",\"scope\":\"permanent\",\"rationale\":\"not in plan\"}",
            "propose_change_volume" to "{\"contract_version\":1,\"muscle\":\"chest\",\"delta_sets_per_week\":2,\"scope\":\"permanent\",\"rationale\":\"below VOL-01 floor\"}",
            "propose_change_rep_range" to "{\"contract_version\":1,\"exercise_id\":\"barbell_row\",\"rep_lo\":12,\"rep_hi\":8,\"scope\":\"permanent\",\"rationale\":\"lo>hi\"}",
            "propose_change_rep_range" to "{\"contract_version\":1,\"exercise_id\":\"barbell_row\",\"rep_lo\":10,\"rep_hi\":40,\"scope\":\"permanent\",\"rationale\":\"above 30\"}",
            "propose_add_cardio" to "{\"contract_version\":1,\"weekday\":3,\"minutes\":5,\"kind\":\"zone2\",\"rationale\":\"too short\"}",
            "propose_add_cardio" to "{\"contract_version\":1,\"weekday\":3,\"minutes\":30,\"kind\":\"hiit_4x4\",\"rationale\":\"no CAR-05 base\"}",
            "propose_remove_cardio" to "{\"contract_version\":1,\"weekday\":6,\"rationale\":\"nothing there\"}",
            "propose_change_schedule" to "{\"contract_version\":1,\"session_minutes\":200,\"rationale\":\"too long\"}",
            "propose_checkin_question" to "{\"contract_version\":1,\"question\":\"Weigh-in?\",\"answer_type\":\"yesno\",\"schedule\":\"once\",\"rationale\":\"once without date\"}",
            "propose_adjust_working_load" to "{\"contract_version\":1,\"exercise_id\":\"barbell_bench_press\",\"new_working_load_kg\":82.5,\"rationale\":\"no state\"}",
            "propose_set_priority_muscles" to "{\"contract_version\":1,\"muscles\":[\"chest\",\"lats\",\"quads\",\"calves\"],\"rationale\":\"four\"}",
            "propose_set_limitation" to "{\"contract_version\":1,\"region\":\"knee\",\"side\":\"left\",\"pain_0_10\":7,\"rationale\":\"RED-02 hand-off\"}",
            "propose_travel_days" to "{\"contract_version\":1,\"from\":\"2026-09-20\",\"to\":\"2026-09-14\",\"rationale\":\"reversed\"}",
            "propose_update_nutrition_targets" to "{\"contract_version\":1,\"protein_g_per_kg\":5.0,\"rationale\":\"too much\"}",
            "propose_update_nutrition_targets" to "{\"contract_version\":1,\"kcal_target\":2500,\"rationale\":\"no estimate yet\"}",
        )
        for ((tool, input) in rejections) {
            val parsed = parse(tool, input)
            val result = if (parsed is ParsedTool.ProposalCall) validate(parsed.mutation) else ValidationResult.Invalid((parsed as ParsedTool.Invalid).errors)
            assertIs<ValidationResult.Invalid>(result, "$tool $input → $result")
            assertTrue(result.errors.isNotEmpty())
        }
    }

    @Test
    fun planDependentMutationsAreInvalidBeforeTheFirstPlan() {
        val m = mutation("propose_deload", "{\"contract_version\":1,\"start_this_week\":true,\"rationale\":\"DLD-03\"}")
        assertIs<ValidationResult.Invalid>(validate(m, mesocycle = null))
        val goal = mutation("propose_change_goal", "{\"contract_version\":1,\"goal\":\"strength\",\"start_next_week\":true,\"rationale\":\"r\"}")
        assertIs<ValidationResult.Valid>(validate(goal, mesocycle = null))
    }

    @Test
    fun validatorUsesTheGivenConstants() {
        val m = mutation("propose_change_volume", "{\"contract_version\":1,\"muscle\":\"chest\",\"delta_sets_per_week\":5,\"scope\":\"permanent\",\"rationale\":\"VOL-01\"}")
        assertIs<ValidationResult.Valid>(validate(m))
        val strict = PlannerConstants.DEFAULT.copy(vol = PlannerConstants.DEFAULT.vol.copy(vol01Minimum = 6))
        val c2 = ToolContract(kb, MutationValidator(kb, strict), clock)
        assertIs<ValidationResult.Invalid>(c2.validate(m, inputs.planInputs, inputs.mesocycle, inputs.today, null))
    }
}
