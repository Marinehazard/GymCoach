package com.gymcoach.core.planner

import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.SessionTemplates
import com.gymcoach.core.model.LoadZone
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SplitKind
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.time.Weeks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** §10.2 / §10.4: the twelve named golden cases, byte-compared, plus the per-case assertions the inventory names. */
class GoldenPlansTest {
    private val kb = PlannerFixtures.kb
    private val c = PlannerConstants.DEFAULT
    private val planner = Planner(kb, c)
    private val today = PlannerFixtures.MONDAY

    private fun inputs(name: String): PlanInputs {
        if (GoldenCases.regenerate()) {
            val built = GoldenCases.build(name)
            GoldenCases.inputsFile(name).apply { parentFile.mkdirs() }.writeText(GoldenCases.encodeInputs(built), Charsets.UTF_8)
            return built
        }
        return GoldenCases.readInputs(name)
    }

    private fun plan(name: String): Pair<PlanInputs, Mesocycle> {
        val inp = inputs(name)
        return inp to planner.plan(inp, today).mesocycle
    }

    private fun current(m: Mesocycle): MesoWeek = m.weeks.first { it.startDate == Weeks.weekStart(today) }

    @Test
    fun twelveGoldenCasesReproduceByteIdenticallyAndValidate() {
        for (name in GoldenCases.NAMES) {
            val (inp, m) = plan(name)
            val json = GoldenCases.encode(m)
            if (GoldenCases.regenerate()) GoldenCases.planFile(name).apply { parentFile.mkdirs() }.writeText(json, Charsets.UTF_8)
            else assertEquals(GoldenCases.readPlan(name), json, "golden plan $name differs (regenerate with GYMCOACH_REGENERATE_GOLDEN=true and explain the diff)")
            assertTrue(planner.validate(m, inp).isEmpty(), "$name: ${planner.validate(m, inp).map { it.message }}")
            assertEquals(GoldenCases.build(name), inp, "$name: golden inputs drifted from GoldenCases.build")
        }
        assertEquals(12, GoldenCases.NAMES.size)
    }

    @Test
    fun strengthFourDayHasHeavyAndBackoffDaysOfTheSameMainLift_invariant17() {
        val (_, m) = plan("strength_ul4_backoff")
        assertEquals(SplitKind.UPPER_LOWER_4, m.split)
        val w = current(m)
        assertEquals(listOf("UPPER_A_S4", "LOWER_A_S", "UPPER_B_S", "LOWER_B_S"), w.sessions.map { it.templateId })
        val bench = w.sessions.first { it.templateId == "UPPER_A_S4" }.slots.first { it.slotId == "s1" }
        val backoff = w.sessions.first { it.templateId == "UPPER_B_S" }.slots.first { it.slotId == "s2" }
        assertEquals("barbell_bench_press", bench.exerciseId)
        assertEquals(bench.exerciseId, backoff.exerciseId)
        // Tuesday's hinge back-off resolves to Friday's heavy pick (heavy-slot pre-pass, §4.6).
        val tuesdayHinge = w.sessions.first { it.templateId == "LOWER_A_S" }.slots.first { it.slotId == "s2" }
        val fridayHinge = w.sessions.first { it.templateId == "LOWER_B_S" }.slots.first { it.slotId == "s1" }
        assertEquals("conventional_deadlift", fridayHinge.exerciseId)
        assertEquals(fridayHinge.exerciseId, tuesdayHinge.exerciseId)
        assertTrue(RuleIds.LOAD_03 in tuesdayHinge.explanation.ruleIds)
        // The back-off day is realized at 4–8 reps and 0.85 × the heavy-day load.
        val inputs = GoldenCases.readInputsOrBuild("strength_ul4_backoff")
        val lowerA = w.sessions.first { it.templateId == "LOWER_A_S" }
        val p = planner.realize(lowerA, w, inputs, TodayContext(Weeks.dateFor(w.startDate, lowerA.weekday), m.gymId), inputs.progressionStates)
        val dl = p.blocks.first { it.slotId == "s2" }
        assertTrue(dl.sets.filter { it.kind == SetKind.WORKING }.all { it.reps == c.prog.load03BackoffReps })
        assertTrue(dl.sets.first { it.kind == SetKind.WORKING }.loadKg!! <= 0.85 * 140.0 + 2.5)
    }

    @Test
    fun age62SetsRepsRestPowerSlotBalanceAndNoMaxStrength_AGE02_AGE03() {
        val (inp, m) = plan("age_62")
        assertEquals(TrainingAge.INTERMEDIATE, m.trainingAge)
        val w = current(m)
        assertTrue(w.sessions.flatMap { it.slots }.all { it.sets in 2..3 }, w.sessions.flatMap { it.slots }.map { it.sets }.toString())
        val power = w.sessions.flatMap { it.slots }.filter { it.slotId == Planner.AGE_POWER_SLOT }
        assertEquals(1, power.size)
        assertTrue(RuleIds.AGE_02 in power.single().explanation.ruleIds)
        assertEquals("power", kb.requireExercise(power.single().exerciseId).repProfile)
        var balance = 0
        for (s in w.sessions) {
            val p = planner.realize(s, w, inp, TodayContext(Weeks.dateFor(w.startDate, s.weekday), m.gymId), inp.progressionStates)
            for (b in p.blocks) {
                val e = kb.requireExercise(b.exerciseId)
                for (set in b.sets.filter { it.kind != SetKind.RAMP }) {
                    assertTrue(set.restSeconds <= 120, "${b.blockId} rest ${set.restSeconds}")
                    if (e.flags.compound && e.repProfile !in setOf("power", "core_time", "carry", "cardio", "mobility") && !e.isCardioOrMobility) assertEquals(c.age.age02CompoundReps, set.reps, b.blockId)
                    if (b.slotId == Planner.AGE_POWER_SLOT) assertTrue((set.rir ?: 0) >= c.age.age02PowerMinRir)
                }
                assertTrue(b.explanation.inputs["zone"] != LoadZone.MAX_STRENGTH.name.lowercase())
            }
            balance += p.cooldown.count { it.drillId == "balance_block" }
        }
        assertEquals(c.age.age02BalanceDaysPerWeek, balance)
        assertEquals(1.9, AgePolicy(c).proteinFloorGPerKg(62))
        assertTrue(m.explanations.any { RuleIds.AGE_03 in it.ruleIds })
    }

    @Test
    fun kneeLimitationPain5ExcludesKneeSensitiveExercises_INJ01() {
        val (_, m) = plan("knee_limitation_pain5")
        val ids = m.weeks.flatMap { w -> w.sessions.flatMap { s -> s.slots.map { it.exerciseId } } }.toSet()
        assertTrue(ids.none { kb.requireExercise(it).flags.kneeSensitive }, ids.filter { kb.requireExercise(it).flags.kneeSensitive }.toString())
        assertTrue(m.explanations.any { RuleIds.INJ_01 in it.ruleIds })
    }

    @Test
    fun shoulderLimitationPain2PenalizesAndAddsTheChronicDose_INJ02_MOB02() {
        val (inp, m) = plan("shoulder_limitation_pain2")
        assertTrue(m.explanations.any { RuleIds.INJ_02 in it.ruleIds && it.inputs["pain"] == "2" })
        val w = current(m)
        val slots = w.sessions.flatMap { it.slots }
        slots.filter { kb.requireExercise(it.exerciseId).flags.shoulderSensitive }.forEach { assertTrue(RuleIds.INJ_00 in it.explanation.ruleIds || RuleIds.OVERRIDE in it.explanation.ruleIds || RuleIds.LOAD_03 in it.explanation.ruleIds, it.exerciseId) }
        val s = w.sessions.first()
        val p = planner.realize(s, w, inp, TodayContext(Weeks.dateFor(w.startDate, s.weekday), m.gymId), inp.progressionStates)
        assertTrue(p.cooldown.any { RuleIds.MOB_02 in it.explanation.ruleIds })
    }

    @Test
    fun equipmentConstrainedCasesUseOnlyAvailableExercisesAndFitTheirMinutes() {
        val resolver = EquipmentResolver(kb)
        for (name in listOf("hotel_only", "dumbbells_only", "bodyweight_band", "thirty_minute_sessions")) {
            val (inp, m) = plan(name)
            val eq = SessionEquipment(inp.gyms.first())
            for (w in m.weeks) for (s in w.sessions) {
                assertTrue(s.estimatedMinutes <= inp.profile.sessionMinutes + 1e-6, "$name ${s.id} ${s.estimatedMinutes}")
                for (slot in s.slots) assertTrue(resolver.resolve(kb.requireExercise(slot.exerciseId), eq).available, "$name ${slot.exerciseId}")
            }
        }
    }

    @Test
    fun advancedSixDayPplHasUniqueSessionIdsAndAFiveWeekBlock_invariant18() {
        val (_, m) = plan("advanced_ppl6")
        assertEquals(SplitKind.PPL_6, m.split)
        assertEquals(TrainingAge.ADVANCED, m.trainingAge)
        assertEquals(6, m.deloadWeekIndex)
        val ids = m.weeks.flatMap { w -> w.sessions.map { it.id } }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(current(m).sessions.map { it.templateId }.count { it == "PUSH" } == 2)
    }

    @Test
    fun fatLossTargetsFollowTheAccustomedVolume_VOL09() {
        val (inp, m) = plan("fat_loss_ul_ppl5_history")
        assertEquals(SplitKind.UL_PPL_5, m.split)
        val t = current(m).targets
        // Accustomed chest = 2 sessions × 4 bench sets = 8/week → lo = clamp(0.6 × 8) = 4.8; week 1.
        assertEquals(4.8, t[Muscle.CHEST])
        assertTrue(inp.recentSets.isNotEmpty())
    }

    @Test
    fun e1AndE3StructuralNumbers() {
        val (_, e1) = plan("e1_home_ul4")
        assertEquals(SplitKind.UPPER_LOWER_4, e1.split)
        assertEquals(7, e1.weeks.size)
        assertEquals(7, e1.deloadWeekIndex)
        val (_, e3) = plan("e3_commercial_fb3_novice")
        assertEquals(SplitKind.FULL_BODY_3, e3.split)
        assertEquals(12, e3.weeks.size)
        assertEquals(null, e3.deloadWeekIndex)
        assertNotNull(SessionTemplates.parseRef("UPPER_B_S.s3"))
    }
}

/** Reads a golden input file (or builds it while regenerating). */
fun GoldenCases.readInputsOrBuild(name: String): PlanInputs = if (regenerate()) build(name) else readInputs(name)
