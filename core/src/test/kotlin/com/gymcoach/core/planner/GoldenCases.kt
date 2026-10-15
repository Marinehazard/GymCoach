package com.gymcoach.core.planner

import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.GymItem
import com.gymcoach.core.model.LimitationRegion
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SessionStatus
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.model.Sex
import java.io.File
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * §10.2 / §10.4 the twelve named golden cases. The Kotlin builders below are only used to (re)generate
 * `core/src/test/resources/golden/inputs/<case>.json`; the tests read the inputs from those files and compare the plan with
 * `golden/plans/<case>.json` byte for byte. Regenerate with the environment variable GYMCOACH_REGENERATE_GOLDEN=true (or the
 * system property gymcoach.regenerateGolden=true) and explain every diff (canary discipline, §10.1).
 */
object GoldenCases {
    val pretty: Json = Json(from = CoreJson.stored) { prettyPrint = true }

    val NAMES: List<String> = listOf(
        "e1_home_ul4", "e3_commercial_fb3_novice", "advanced_ppl6", "strength_ul4_backoff", "fat_loss_ul_ppl5_history", "hotel_only",
        "dumbbells_only", "bodyweight_band", "thirty_minute_sessions", "knee_limitation_pain5", "shoulder_limitation_pain2", "age_62",
    )

    fun build(name: String): PlanInputs {
        val p1 = PlannerFixtures.p1()
        val base = PlannerFixtures.base
        return when (name) {
            "e1_home_ul4" -> PlannerFixtures.inputs(p1)
            "e3_commercial_fb3_novice" -> PlannerFixtures.inputs(PlannerFixtures.p2(), gyms = listOf(base), bodyWeightKg = 78.0)
            "advanced_ppl6" -> PlannerFixtures.inputs(p1.copy(trainingMonthsConsistent = 40, daysPerWeek = 6, sessionMinutes = 75, allowAdvancedExercises = true), gyms = listOf(base), mesocyclesCompleted = 3)
            "strength_ul4_backoff" -> PlannerFixtures.inputs(p1.copy(goal = Goal.STRENGTH), gyms = listOf(base), mesocyclesCompleted = 1)
            "fat_loss_ul_ppl5_history" -> withHistory(PlannerFixtures.inputs(p1.copy(goal = Goal.FAT_LOSS, daysPerWeek = 5), gyms = listOf(base), mesocyclesCompleted = 2))
            "hotel_only" -> PlannerFixtures.inputs(p1.copy(sessionMinutes = 45), gyms = listOf(PlannerFixtures.hotel))
            "dumbbells_only" -> PlannerFixtures.inputs(p1, gyms = listOf(PlannerFixtures.home.copy(id = "gym-db", name = "Dumbbells", presetOrigin = null,
                items = listOf(GymItem("dumbbells", mapOf("max_kg" to JsonPrimitive(30), "step_kg" to JsonPrimitive(2))), GymItem("mat")))))
            "bodyweight_band" -> PlannerFixtures.inputs(p1.copy(daysPerWeek = 3), gyms = listOf(PlannerFixtures.gym("travel_kit", "gym-kit")))
            "thirty_minute_sessions" -> PlannerFixtures.inputs(p1.copy(sessionMinutes = 30))
            "knee_limitation_pain5" -> PlannerFixtures.inputs(p1.copy(limitations = listOf(PlannerFixtures.lim(LimitationRegion.KNEE, 5))), gyms = listOf(base))
            "shoulder_limitation_pain2" -> PlannerFixtures.inputs(p1.copy(limitations = listOf(PlannerFixtures.lim(LimitationRegion.SHOULDER, 2, limitedRange = true))), gyms = listOf(base))
            "age_62" -> PlannerFixtures.inputs(p1.copy(sex = Sex.FEMALE, birthYear = 1964, trainingMonthsConsistent = 18, goal = Goal.GENERAL_FITNESS, daysPerWeek = 3,
                sessionMinutes = 60, knownLoadsKg = emptyMap()), gyms = listOf(base), bodyWeightKg = 66.0, mesocyclesCompleted = 1)
            else -> error("unknown golden case $name")
        }
    }

    /** Four weeks of logged upper/lower work before the anchor: the VOL-09 accustomed volume of the fat-loss case. */
    private fun withHistory(inputs: PlanInputs): PlanInputs {
        val sessions = ArrayList<SessionRecord>()
        val sets = ArrayList<SetLog>()
        val plan = listOf("barbell_bench_press" to 4, "barbell_row" to 4, "back_squat" to 4, "romanian_deadlift" to 3, "dumbbell_lateral_raise" to 3, "lat_pulldown" to 3)
        for (wk in 1..4) for (d in listOf(0, 3)) {
            val date = PlannerFixtures.MONDAY.minusWeeks(wk.toLong()).plusDays(d.toLong())
            val id = "hist-$date"
            sessions.add(SessionRecord(id, date, PlannerFixtures.BASE_ID, status = SessionStatus.COMPLETED))
            for ((ex, n) in plan) for (i in 1..n) sets.add(SetLog("$id-$ex-$i", id, null, ex, i, SetKind.WORKING, reps = 10, rir = 2, prescribedRir = 2,
                loggedAt = date.atTime(18, 0).toInstant(ZoneOffset.UTC)))
        }
        return inputs.copy(recentSessions = sessions, recentSets = sets)
    }

    private fun moduleRoot(): File = listOf(File("."), File("core")).firstOrNull { File(it, "src/test/resources").isDirectory } ?: File(".")
    fun inputsFile(name: String): File = File(moduleRoot(), "src/test/resources/golden/inputs/$name.json")
    fun planFile(name: String): File = File(moduleRoot(), "src/test/resources/golden/plans/$name.json")

    fun regenerate(): Boolean = System.getenv("GYMCOACH_REGENERATE_GOLDEN") == "true" || System.getProperty("gymcoach.regenerateGolden") == "true"

    fun readInputs(name: String): PlanInputs {
        val res = GoldenCases::class.java.getResource("/golden/inputs/$name.json")
        val text = res?.readText(Charsets.UTF_8) ?: inputsFile(name).readText(Charsets.UTF_8)
        return CoreJson.stored.decodeFromString(PlanInputs.serializer(), text)
    }

    fun readPlan(name: String): String? = GoldenCases::class.java.getResource("/golden/plans/$name.json")?.readText(Charsets.UTF_8)
        ?: planFile(name).takeIf { it.exists() }?.readText(Charsets.UTF_8)

    fun encode(m: Mesocycle): String = pretty.encodeToString(Mesocycle.serializer(), m) + "\n"
    fun encodeInputs(i: PlanInputs): String = pretty.encodeToString(PlanInputs.serializer(), i) + "\n"
}
