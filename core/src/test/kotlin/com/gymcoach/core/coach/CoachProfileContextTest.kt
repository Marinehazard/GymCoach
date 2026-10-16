package com.gymcoach.core.coach

import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.UnitSystem
import com.gymcoach.core.planner.PlannerFixtures
import com.gymcoach.core.seed.SeedProfiles
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phase 2, item 7: the coach sees the seed profile's facts that matter — strength targets with the current e1RM, the RPE-8
 * cap, the known issues with their fixes, the work-shift recovery rule — through the §6.5 context snapshot (additive keys);
 * the byte-stable system prompt is untouched (SystemPromptTest pins its SHA-256).
 */
class CoachProfileContextTest {
    private val kb = PlannerFixtures.kb
    private val seed = assertNotNull(SeedProfiles.classpath())
    private val today = LocalDate.of(2026, 10, 19)

    private fun snapshot(): JsonObject {
        val gym = seed.gyms.first { it.id == seed.defaultGymId }
        val inputs = ContextInputs(
            today = TodayContext(today, gym.id), units = UnitSystem.METRIC, profile = seed.profile, trainingAge = TrainingAge.ADVANCED, weightMa7Kg = null,
            gym = gym, availableExerciseIds = EquipmentResolver(kb).availableExerciseIds(SessionEquipment(gym)).toList(), mesocycle = null,
            weekIndex = 1, weekSessions = emptyList(), ledger = null, todaySession = null, readiness7d = emptyList(),
            states = SeedProfiles.progressionStates(seed, kb), recentSessions = seed.history.sessions, recentSets = seed.history.sets,
            openQuestions = emptyList(), lastCheckin = null, nutrition = null, energy = null, trendKgPerWeek = null,
            pendingProposals = emptyList(), appliedSince = emptyList(),
        )
        val text = CoachContextBuilder(kb, TokenEstimator).snapshot(inputs)
        assertTrue(text.startsWith("<context schema=\"1\">") && text.endsWith("</context>"))
        return Json.parseToJsonElement(text.removePrefix("<context schema=\"1\">").removeSuffix("</context>")).jsonObject
    }

    @Test
    fun profileFactsReachTheCoach() {
        val o = snapshot()
        val p = o["profile"]!!.jsonObject
        assertEquals("joe_5day", p["split"]!!.jsonPrimitive.content)
        assertEquals("2", p["barbell_rir_floor"]!!.jsonPrimitive.content)
        assertEquals("8", p["barbell_rpe_cap"]!!.jsonPrimitive.content)
        assertEquals("true", p["physical_job"]!!.jsonPrimitive.content)
        val targets = p["strength_targets"]!!.jsonArray.associate { it.jsonObject["ex"]!!.jsonPrimitive.content to it.jsonObject }
        assertEquals(setOf("back_squat", "barbell_bench_press", "conventional_deadlift", "overhead_press_barbell"), targets.keys)
        assertEquals("140", targets.getValue("back_squat")["target_kg"]!!.jsonPrimitive.content)
        assertEquals("103.1", targets.getValue("back_squat")["e1rm_kg"]!!.jsonPrimitive.content)
        assertEquals("74", targets.getValue("back_squat")["pct"]!!.jsonPrimitive.content)
        assertEquals("180", targets.getValue("conventional_deadlift")["target_kg"]!!.jsonPrimitive.content)
        // Known issues travel with their fixes.
        val limitations = p["limitations"]!!.jsonArray.map { it.jsonObject }
        val knee = limitations.first { it["region"]!!.jsonPrimitive.content == "knee" }
        assertEquals("left", knee["side"]!!.jsonPrimitive.content)
        assertTrue(knee["notes"]!!.jsonPrimitive.content.contains("Bulgarian split squats left leg first"))
        assertTrue(limitations.any { it["notes"]?.jsonPrimitive?.content?.contains("Hips rise first") == true })
        assertTrue(limitations.any { it["notes"]?.jsonPrimitive?.content?.contains("no max singles outside test weeks") == true })
        // Work-shift recovery rule and the progression rules are standing notes.
        val notes = p["notes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(notes.any { it.contains("Work days count as training load") && it.contains("drop the arms day first") })
        assertTrue(notes.any { it.contains("increase only when it feels like RPE 7") })
        // The snapshot stays within its default budget (no truncation for this profile).
        assertTrue(o["truncated"] == null, "truncated: ${o["truncated"]}")
    }

    @Test
    fun genericProfilesKeepTheirKeys() {
        // A profile without the Phase-2 fields renders exactly the §6.5 keys (CoachContextBuilderTest pins the order).
        val plain = seed.profile.copy(splitId = null, strengthTargetsKg = null, barbellRirFloor = null, physicalJob = false, coachNotes = null)
        val gym = seed.gyms.first()
        val inputs = ContextInputs(TodayContext(today, gym.id), UnitSystem.METRIC, plain, TrainingAge.ADVANCED, null, gym, emptyList(), null, 1, emptyList(), null, null,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null, null, null, null, emptyList(), emptyList())
        val text = CoachContextBuilder(kb, TokenEstimator).snapshot(inputs)
        val p = Json.parseToJsonElement(text.removePrefix("<context schema=\"1\">").removeSuffix("</context>")).jsonObject["profile"]!!.jsonObject
        assertEquals(listOf("sex", "age", "height_cm", "weight_ma7_kg", "training_age", "goal", "days", "minutes", "limitations", "medical_gate", "priority_muscles"), p.keys.toList())
    }
}
