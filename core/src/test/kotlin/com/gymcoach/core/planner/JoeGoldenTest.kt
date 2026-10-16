package com.gymcoach.core.planner

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.Band
import com.gymcoach.core.model.ExerciseBlock
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.SessionPlan
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SplitKind
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.seed.SeedProfiles
import com.gymcoach.core.time.Weeks
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * §10.4 golden case joe_home_5day: the seed profile joe_v1 (docs/USER_PROFILE.md) planned at the Home gym on Monday
 * 19 Oct 2026 — the plan is byte-compared like the twelve generic cases and the USER_PROFILE §3/§4/§8 structure is asserted.
 */
class JoeGoldenTest {
    private val kb = PlannerFixtures.kb
    private val c = PlannerConstants.DEFAULT
    private val planner = Planner(kb, c)
    private val today: LocalDate = TODAY

    private fun inputs(): PlanInputs {
        val built = build()
        if (GoldenCases.regenerate()) {
            GoldenCases.inputsFile(NAME).apply { parentFile.mkdirs() }.writeText(GoldenCases.encodeInputs(built), Charsets.UTF_8)
            return built
        }
        val stored = GoldenCases.readInputs(NAME)
        assertEquals(built, stored, "golden inputs drifted from the seed profile (regenerate and explain the diff)")
        return stored
    }

    private val inp: PlanInputs by lazy { inputs() }
    private val meso: Mesocycle by lazy { planner.plan(inp, today).mesocycle }
    private fun week(): MesoWeek = meso.weeks.first { it.startDate == today }

    private fun realize(templateId: String, m: Mesocycle = meso, inputs: PlanInputs = inp): SessionPlan {
        val w = m.weeks.first { it.startDate == today }
        val s = w.sessions.first { it.templateId == templateId }
        return planner.realize(s, w, inputs, TodayContext(Weeks.dateFor(w.startDate, s.weekday), m.gymId), inputs.progressionStates)
    }

    private fun working(b: ExerciseBlock) = b.sets.filter { it.kind != SetKind.RAMP }
    private fun ramps(b: ExerciseBlock) = b.sets.filter { it.kind == SetKind.RAMP }.map { it.loadKg }

    @Test
    fun reproducesByteIdenticallyAndValidates() {
        val json = GoldenCases.encode(meso)
        if (GoldenCases.regenerate()) GoldenCases.planFile(NAME).apply { parentFile.mkdirs() }.writeText(json, Charsets.UTF_8)
        else assertEquals(GoldenCases.readPlan(NAME), json, "golden plan $NAME differs (regenerate with GYMCOACH_REGENERATE_GOLDEN=true and explain the diff)")
        assertEquals(emptyList(), planner.validate(meso, inp).map { it.message })
        assertEquals(meso, planner.plan(CoreJson.stored.decodeFromString(PlanInputs.serializer(), CoreJson.stored.encodeToString(PlanInputs.serializer(), inp)), today).mesocycle)
    }

    @Test
    fun fiveDaySplitInUserOrderWithSquatAndDeadliftApart() {
        assertEquals(SplitKind.CUSTOM, meso.split)
        assertEquals("gym-joe-home", meso.gymId)
        val w = week()
        assertEquals(listOf("JOE_A", "JOE_B", "JOE_C", "JOE_D", "JOE_E"), w.sessions.map { it.templateId })
        assertEquals(listOf(1, 2, 4, 5, 6), w.sessions.map { it.weekday })
        val squatDays = w.sessions.filter { s -> s.slots.any { it.heavy && it.pattern == MovementPattern.SQUAT } }.map { it.weekday }
        val hingeDays = w.sessions.filter { s -> s.slots.any { it.heavy && it.pattern == MovementPattern.HINGE } }.map { it.weekday }
        assertEquals(listOf(1), squatDays)
        assertEquals(listOf(4), hingeDays)
        for (a in squatDays) for (b in hingeDays) {
            val d = Math.floorMod(b - a, 7)
            assertTrue(minOf(d, 7 - d) >= 2, "squat day $a and deadlift day $b must not be consecutive")
        }
        // Sets are the user's tables, not the generic allocator's.
        val a = w.sessions.first { it.templateId == "JOE_A" }.slots
        assertEquals(listOf("s1" to 1, "s1b" to 3, "s2" to 3, "s3" to 2, "s4" to 3, "s5" to 2, "s6" to 2, "s7" to 3, "s8" to 3, "s9" to 3), a.map { it.slotId to it.sets })
        assertEquals(listOf("back_squat", "back_squat", "smith_machine_squat", "bulgarian_split_squat", "machine_hip_thrust", "band_lateral_walk", "spanish_squat",
            "standing_calf_raise", "pallof_press", "dead_bug"), a.map { it.exerciseId })
    }

    @Test
    fun sixWeekCycleThenATestWeekOnTheLiftClosestToItsTarget() {
        assertEquals(7, meso.weeks.size)
        assertEquals(List(6) { WeekMode.NORMAL } + WeekMode.DELOAD, meso.weeks.map { it.mode })
        val test = meso.weeks.last().notes.first { RuleIds.LOAD_02 in it.ruleIds }
        assertEquals("conventional_deadlift", test.inputs["exercise_id"], test.message)   // e1RM ≈ 168 of 180 kg: "first to fall"
        assertTrue(test.message.startsWith("Test week"))
    }

    @Test
    fun mainLiftsAreOneTopTripleAtRir2PlusBackoffs() {
        val a = realize("JOE_A")
        val top = a.blocks.first { it.slotId == "s1" }
        val back = a.blocks.first { it.slotId == "s1b" }
        assertEquals("back_squat", top.exerciseId)
        assertEquals(listOf(Band(3, 3)), working(top).map { it.reps })
        assertEquals(listOf(2), working(top).map { it.rir })
        assertEquals(listOf(90.0), working(top).map { it.loadKg })
        assertEquals(List(3) { Band(5, 7) }, working(back).map { it.reps })
        assertEquals(List(3) { 80.0 }, working(back).map { it.loadKg })
        assertEquals(List(3) { 2 }, working(back).map { it.rir })
        assertEquals("back_squat#backoff", back.progressionKey.id)
        assertEquals("back_squat", top.progressionKey.id)
        assertEquals(List(3) { 180 }, working(back).map { it.restSeconds })
        // Section 8 ladder for a 90 kg triple: bar, 40, 60, 70, 80; back-offs need no second ramp.
        assertEquals(listOf(20.0, 40.0, 60.0, 70.0, 80.0), ramps(top))
        assertEquals(emptyList(), ramps(back))

        val b = realize("JOE_B")
        val bench = b.blocks.first { it.slotId == "s1" }
        assertEquals(listOf(80.0), working(bench).map { it.loadKg })
        assertEquals(listOf(20.0, 40.0, 50.0, 60.0, 70.0), ramps(bench))
        assertEquals(List(3) { 70.0 }, working(b.blocks.first { it.slotId == "s1b" }).map { it.loadKg })

        // Deadlift: the log's last hinge is 160×2 in mid-September, so LAY-01 row 3 (29–56 days beyond the weekly cadence) scales
        // the seeded ~140 kg target: × 0.775 = 108.5 → 110 kg, back-off sets × 0.5, RIR + 2 — explained, never silent.
        val cDay = realize("JOE_C")
        val dl = cDay.blocks.first { it.slotId == "s1" }
        assertEquals(listOf(110.0 to Band(3, 3)), working(dl).map { it.loadKg to it.reps })
        assertTrue(cDay.modifiers.any { RuleIds.LAY_01 in it.ruleIds && it.inputs["exercise_id"] == "conventional_deadlift" && it.inputs["row"] == "3" })
        // On cadence (a hinge logged last week) the seeded numbers apply as they are: 140 × 3, back-offs 0.85 × 140 = 119 → 120 kg on 5 kg steps.
        val lastWeek = LocalDate.of(2026, 10, 16)
        val onCadence = inp.copy(patternLastTrained = mapOf(MovementPattern.HINGE to lastWeek),
            progressionStates = inp.progressionStates.map { if (it.key.exerciseId == "conventional_deadlift") it.copy(lastSessionDate = lastWeek) else it })
        val c2 = realize("JOE_C", inputs = onCadence)
        assertEquals(listOf(140.0 to Band(3, 3)), working(c2.blocks.first { it.slotId == "s1" }).map { it.loadKg to it.reps })
        assertEquals(List(2) { 120.0 }, working(c2.blocks.first { it.slotId == "s1b" }).map { it.loadKg })

        val d = realize("JOE_D")
        val ohp = d.blocks.first { it.slotId == "s1" }
        assertEquals(List(4) { 45.0 to Band(6, 6) }, working(ohp).map { it.loadKg to it.reps })
        assertEquals(listOf(20.0, 30.0, 40.0), ramps(ohp))
    }

    @Test
    fun barbellWorkStaysAtRpe8AndJumpsInFiveKiloSteps() {
        for (t in listOf("JOE_A", "JOE_B", "JOE_C", "JOE_D", "JOE_E")) {
            val p = realize(t)
            for (b in p.blocks) {
                if (b.effectiveLoadType != LoadType.BARBELL && b.effectiveLoadType != LoadType.SMITH) continue
                val e = kb.requireExercise(b.exerciseId)
                for (s in b.sets) {
                    val kg = s.loadKg ?: continue
                    assertEquals(0.0, Math.floorMod(Math.round(kg * 100).toInt() - 2000, 500).toDouble(), "$t ${b.blockId} $kg kg is not bar + 2.5 kg plate pairs")
                    if (s.kind != SetKind.RAMP && e.flags.compound) assertTrue((s.rir ?: 99) >= 2, "$t ${b.blockId} RIR ${s.rir} < 2 (RPE-8 cap)")
                }
            }
        }
    }

    @Test
    fun everySessionStartsWithFiveMinutesEasyCardioThenTheMobilityBlock() {
        val bikeDays = setOf("JOE_A", "JOE_C")
        for (t in listOf("JOE_A", "JOE_B", "JOE_C", "JOE_D", "JOE_E")) {
            val w = realize(t).warmup
            assertEquals(5, w.raiseMinutes, t)
            assertEquals(if (t in bikeDays) "stationary_bike" else "rowing_erg", w.raiseExerciseId, t)
            assertTrue(RuleIds.WU_01 in w.explanation.ruleIds)
        }
        val a = realize("JOE_A")
        assertEquals(listOf("knee_to_wall_pulses", "hip_90_90", "half_kneeling_hip_flexor_stretch", "goblet_squat_pry", "cat_cow", "squat_bottom_breathing", "band_lateral_walk"),
            a.warmup.drills.map { it.drillId })
        assertEquals(2, a.warmup.drills.last().sets)
        assertEquals(listOf("couch_stretch", "figure_four_stretch", "wall_calf_stretch", "bent_knee_calf_stretch", "frog_stretch"), a.cooldown.take(5).map { it.drillId })
        assertEquals(listOf("foam_roller_thoracic_extension", "band_pull_apart", "shoulder_pass_through", "doorway_pec_stretch", "lat_stretch_rack", "scapular_push_up",
            "band_external_rotation", "wrist_circles"), realize("JOE_B").warmup.drills.map { it.drillId })
        assertEquals(listOf("band_pull_apart", "scapular_pull_up", "open_book_rotation", "dead_hang", "lat_stretch_rack"), realize("JOE_C").warmup.drills.map { it.drillId })
    }

    @Test
    fun leftKneeWorkIsScheduledOnSquatDay() {
        val a = realize("JOE_A")
        assertEquals("band_lateral_walk", a.warmup.drills.last().drillId, "banded lateral walks last, right before the bar")
        val bss = a.blocks.first { it.exerciseId == "bulgarian_split_squat" }
        assertTrue(bss.explanation.message.contains("Left leg first"), bss.explanation.message)
        assertTrue(a.blocks.any { kb.requireExercise(it.exerciseId).pattern == MovementPattern.HIP_ABDUCTION }, "abductor work")
        assertTrue(a.blocks.any { it.exerciseId == "machine_hip_thrust" }, "hip thrust")
        val order = a.blocks.sortedBy { it.order }.map { it.exerciseId }
        assertEquals("back_squat", order.first())
    }

    @Test
    fun heavyWorkWeekDropsTheArmsDayFirst() {
        val stamp = Instant.parse("2026-10-18T08:00:00Z")
        val four = inp.copy(overrides = listOf(PlanOverride("ov-4days", stamp, OverrideSource.USER, mutation = PlanMutation.ChangeSchedule(daysPerWeek = 4, rationale = "12 h shifts this week"))))
        val m = planner.plan(four, today).mesocycle
        val w = m.weeks.first { it.startDate == today }
        assertEquals(listOf("JOE_A", "JOE_B", "JOE_C", "JOE_D"), w.sessions.map { it.templateId })
        assertEquals(listOf(1, 2, 4, 5), w.sessions.map { it.weekday })
        assertTrue(m.explanations.any { it.inputs["dropped"] == "JOE_E" })
    }

    companion object {
        const val NAME: String = "joe_home_5day"
        val TODAY: LocalDate = LocalDate.of(2026, 10, 19)

        fun build(): PlanInputs {
            val seed = checkNotNull(SeedProfiles.classpath(SeedProfiles.JOE_V1))
            return SeedProfiles.planInputs(seed, PlannerFixtures.kb, TODAY, anchor = TODAY)
        }
    }
}
