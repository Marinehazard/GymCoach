package com.gymcoach.core.coach

import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.EnteredWeight
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SessionStatus
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.WeightUnit
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ToolExecutorTest {
    private val kb = CoachFixtures.kb
    private val at = Instant.parse("2026-09-08T17:00:00Z")
    private val session = SessionRecord("s1", LocalDate.of(2026, 9, 7), CoachFixtures.HOME_ID, status = SessionStatus.COMPLETED)
    private val sets = listOf(
        SetLog("a", "s1", null, "barbell_bench_press", 0, SetKind.RAMP, EnteredWeight(60.0, WeightUnit.KG, 60.0), reps = 5, loggedAt = at),
        SetLog("b", "s1", null, "barbell_bench_press", 1, SetKind.WORKING, EnteredWeight(80.0, WeightUnit.KG, 80.0), reps = 8, rir = 3, loggedAt = at),
        SetLog("c", "s1", null, "barbell_bench_press", 2, SetKind.WORKING, EnteredWeight(82.5, WeightUnit.KG, 82.5), reps = 6, rir = 2, loggedAt = at),
    )
    private val executor = ToolExecutor(
        kb, FakeWorkouts(listOf(session), sets), FakeProgression(listOf(CoachFixtures.state("barbell_bench_press", 82.5))),
        FakeGyms(CoachFixtures.home, CoachFixtures.hotel), FakePlans(),
    )
    private val today = TodayContext(CoachFixtures.TODAY, CoachFixtures.HOTEL_ID)

    private fun j(s: String): JsonObject = CoreJson.wire.parseToJsonElement(s).jsonObject
    private fun run(name: String, input: String, t: TodayContext = today): ToolExecution = runBlocking { executor.execute(name, j(input), t) }

    @Test
    fun gymEquipmentDefaultsToTodaysGym() {
        val r = run("get_gym_equipment", "{}")
        assertFalse(r.isError)
        val o = j(r.resultJson)
        assertEquals(CoachFixtures.HOTEL_ID, o["gym"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(o["available_exercises"]!!.jsonPrimitive.int > 0)
        assertTrue(r.resultJson.length <= ToolExecutor.MAX_RESULT_CHARS)
        val home = j(run("get_gym_equipment", "{\"gym_id\":\"gym-home\"}").resultJson)
        assertEquals("Home gym", home["gym"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val bad = run("get_gym_equipment", "{\"gym_id\":\"nope\"}")
        assertTrue(bad.isError)
    }

    @Test
    fun availabilityIsResolvedAgainstTodaysSessionEquipment() {
        val plain = j(run("lookup_exercises", "{\"query\":\"barbell bench press\",\"available_only\":false}").resultJson.let { "{\"r\":$it}" })["r"]!!.jsonArray
        val bench = plain.first { it.jsonObject["id"]!!.jsonPrimitive.content == "barbell_bench_press" }.jsonObject
        assertFalse(bench["available"]!!.jsonPrimitive.boolean, "no barbell in the hotel gym")
        val withKit = today.copy(extraItems = CoachFixtures.home.items, spotter = true)
        val kit = j("{\"r\":" + run("lookup_exercises", "{\"query\":\"barbell bench press\",\"available_only\":true}", withKit).resultJson + "}")["r"]!!.jsonArray
        assertTrue(kit.any { it.jsonObject["id"]!!.jsonPrimitive.content == "barbell_bench_press" && it.jsonObject["available"]!!.jsonPrimitive.boolean })
        val removed = CoachFixtures.home.items.map { it.equipmentId }
        val homeDay = TodayContext(CoachFixtures.TODAY, CoachFixtures.HOME_ID, removedEquipmentIds = removed)
        val none = j("{\"r\":" + run("lookup_exercises", "{\"query\":\"barbell bench press\",\"available_only\":true}", homeDay).resultJson + "}")["r"]!!.jsonArray
        assertTrue(none.none { it.jsonObject["id"]!!.jsonPrimitive.content == "barbell_bench_press" })
    }

    @Test
    fun lookupFiltersByPatternAndMuscleAndCapsAtTen() {
        val r = run("lookup_exercises", "{\"query\":\"\",\"pattern\":\"hinge\",\"available_only\":false}")
        val rows = j("{\"r\":${r.resultJson}}")["r"]!!.jsonArray
        assertTrue(rows.isNotEmpty() && rows.size <= 10)
        assertTrue(rows.all { it.jsonObject["pattern"]!!.jsonPrimitive.content == "hinge" })
        val first = rows.first().jsonObject
        assertEquals(listOf("id", "name", "pattern", "muscles", "available", "substitutions", "flags", "difficulty"), first.keys.toList())
        assertTrue(r.resultJson.length <= ToolExecutor.MAX_RESULT_CHARS)
        assertTrue(run("lookup_exercises", "{\"query\":\"\",\"pattern\":\"teleport\",\"available_only\":false}").isError)
        assertTrue(run("lookup_exercises", "{\"query\":\"\",\"available_only\":false}").isError)
    }

    @Test
    fun historyReturnsBestSetPerSessionAndState() {
        val r = run("get_exercise_history", "{\"exercise_id\":\"barbell_bench_press\",\"days\":14}")
        assertFalse(r.isError)
        val o = j(r.resultJson)
        val s = o["sessions"]!!.jsonArray.single().jsonObject
        assertEquals("2026-09-07", s["date"]!!.jsonPrimitive.content)
        assertEquals("82.5x6@2", s["best"]!!.jsonPrimitive.content)
        assertEquals("high", s["confidence"]!!.jsonPrimitive.content)
        assertEquals("82.5", o["state"]!!.jsonObject["working_kg"]!!.jsonPrimitive.content)
        assertEquals("6-10", o["state"]!!.jsonObject["reps"]!!.jsonPrimitive.content)
    }

    @Test
    fun invalidInputsAreIsErrorWithInstructiveMessages() {
        val unknown = run("get_exercise_history", "{\"exercise_id\":\"db_rdl\",\"days\":14}")
        assertTrue(unknown.isError)
        assertEquals("{\"status\":\"invalid\",\"errors\":[\"unknown exercise id 'db_rdl'; use lookup_exercises\"]}", unknown.resultJson)
        assertTrue(run("get_exercise_history", "{\"exercise_id\":\"barbell_bench_press\",\"days\":91}").isError)
        assertTrue(run("get_exercise_history", "{\"exercise_id\":\"barbell_bench_press\",\"days\":0}").isError)
        assertTrue(run("get_rule", "{\"rule_id\":\"XYZ-99\"}").isError)
        assertTrue(run("propose_deload", "{}").isError)
        assertTrue(ToolExecutor.isErrorResult(unknown.resultJson))
        assertFalse(ToolExecutor.isErrorResult("[{\"id\":\"x\"}]"))
    }

    @Test
    fun getRuleReturnsStatementConfidenceCitationAndConstants() {
        val r = run("get_rule", "{\"rule_id\":\"vol-04\"}")
        assertFalse(r.isError)
        val o = j(r.resultJson)
        assertEquals("VOL-04", o["id"]!!.jsonPrimitive.content)
        assertEquals("weak", o["confidence"]!!.jsonPrimitive.content)
        assertEquals(listOf("id", "statement", "confidence", "citation", "constants"), o.keys.toList())
        assertTrue(o["constants"]!!.jsonObject.isNotEmpty())
    }
}
