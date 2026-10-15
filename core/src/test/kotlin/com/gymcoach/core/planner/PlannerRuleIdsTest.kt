package com.gymcoach.core.planner

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.CardioKind
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.LimitationRegion
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.model.ReadinessCheckin
import com.gymcoach.core.model.SessionPlan
import com.gymcoach.core.model.SlotRole
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.planner.realize.ReadinessModifiers
import com.gymcoach.core.planner.realize.TimeConcessions
import com.gymcoach.core.planner.realize.WorkBlock
import com.gymcoach.core.time.Weeks
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every rule id the WP-03 code writes into an explanation is asserted here on a real output (HR-15: the planner explains
 * every decision with a rule id; RuleCoverageTest requires each id used in main to be pinned by a test).
 */
class PlannerRuleIdsTest {
    private val kb = PlannerFixtures.kb
    private val c = PlannerConstants.DEFAULT
    private val planner = Planner(kb, c)
    private val p1 = PlannerFixtures.p1()
    private val states = PlannerFixtures.knownStates(p1, PlannerFixtures.home)
    private val inputs = PlannerFixtures.inputs(p1, gyms = listOf(PlannerFixtures.home, PlannerFixtures.hotel), defaultGymId = PlannerFixtures.HOME_ID, states = states)
    private val week = PlannerFixtures.e1Week()

    private fun ids(e: List<Explanation>): Set<String> = e.flatMap { it.ruleIds }.toSet()
    private fun blockIds(p: SessionPlan): Set<String> = p.blocks.flatMap { it.explanation.ruleIds + it.warnings.flatMap { w -> w.ruleIds } }.toSet()
    private fun realize(i: Int, inp: PlanInputs = inputs, today: TodayContext = TodayContext(Weeks.dateFor(week.startDate, week.sessions[i].weekday), PlannerFixtures.HOME_ID)) =
        planner.realize(week.sessions[i], week, inp, today, inp.progressionStates)

    @Test
    fun blockExplanationsCite_DEF02_DEF03_RIR01_ORD01_ORD02_TEMPO01_TEMPO02_ROM01_REST01_REST02_UNI01_WU04_MOB05() {
        val upper = realize(0)
        val bench = upper.blocks.first { it.exerciseId == "barbell_bench_press" }.explanation.ruleIds
        assertTrue(bench.containsAll(listOf(RuleIds.DEF_02, RuleIds.DEF_03, RuleIds.DEF_07, RuleIds.RIR_01, RuleIds.ORD_01, RuleIds.TEMPO_01, RuleIds.TEMPO_02, RuleIds.ROM_01, RuleIds.REST_02)), bench.toString())
        val row = upper.blocks.first { it.exerciseId == "barbell_row" }.explanation.ruleIds
        assertTrue(row.containsAll(listOf(RuleIds.ORD_02, RuleIds.REST_01)), row.toString())
        assertTrue(upper.blocks.first { it.exerciseId == "dumbbell_lateral_raise" }.explanation.ruleIds.contains(RuleIds.REST_03))
        assertTrue(RuleIds.UNI_01 in realize(1).blocks.first { it.exerciseId == "bulgarian_split_squat" }.explanation.ruleIds)
        assertTrue(upper.warmup.explanation.ruleIds.containsAll(listOf(RuleIds.WU_01, RuleIds.WU_02, RuleIds.WU_03, RuleIds.WU_04, RuleIds.MOB_01, RuleIds.MOB_05)))
    }

    @Test
    fun strengthBlocksCite_RIR02_LOAD02_VOL05_andGymScopedKeys_E1RM04() {
        val inp = GoldenCases.build("strength_ul4_backoff")
        val m = planner.plan(inp, PlannerFixtures.MONDAY).mesocycle
        val w = m.weeks.first()
        val lowerB = w.sessions.first { it.templateId == "LOWER_B_S" }
        assertTrue(lowerB.slots.first { it.slotId == "s1" }.explanation.ruleIds.contains(RuleIds.VOL_05))
        val p = planner.realize(lowerB, w, inp, TodayContext(Weeks.dateFor(w.startDate, lowerB.weekday), m.gymId), inp.progressionStates)
        val heavy = p.blocks.first { it.slotId == "s1" }.explanation.ruleIds
        assertTrue(heavy.containsAll(listOf(RuleIds.RIR_02, RuleIds.LOAD_02)), heavy.toString())
        val machine = p.blocks.firstOrNull { it.progressionKey.gymScope != null }
        if (machine != null) assertTrue(RuleIds.E1RM_04 in machine.explanation.ruleIds)
        val e3 = PlannerFixtures.inputs(PlannerFixtures.p2(), gyms = listOf(PlannerFixtures.base), bodyWeightKg = 78.0)
        val wk = PlannerFixtures.e1Week().copy(sessions = listOf(PlannerFixtures.session("x:w1:d1:FULL_A", "FULL_A", 1, listOf(PlannerFixtures.slot("s3", SlotRole.PRIMARY, "seated_cable_row", 3, gym = PlannerFixtures.base)))))
        val cable = planner.realize(wk.sessions[0], wk, e3, TodayContext(PlannerFixtures.MONDAY, PlannerFixtures.BASE_ID), emptyList())
        assertTrue(RuleIds.E1RM_04 in cable.blocks.single().explanation.ruleIds)
    }

    @Test
    fun mesocycleNotesCite_SESS01_SESS02_VOL03_VOL06_VOL07_DEF05_TIME02_MIX02_MIX03_TIER() {
        val m = planner.plan(inputs, PlannerFixtures.MONDAY).mesocycle
        val all = ids(m.explanations)
        assertTrue(all.containsAll(listOf(RuleIds.SESS_01, RuleIds.SESS_02, RuleIds.VOL_03, RuleIds.VOL_06, RuleIds.VOL_07, RuleIds.DEF_05, RuleIds.TIME_02, RuleIds.MIX_02, RuleIds.MIX_03, RuleIds.TIER)), all.toString())
        assertTrue(m.underTargetReport.all { RuleIds.DEF_05 in it.ruleIds && RuleIds.VOL_04 in it.ruleIds })
        assertTrue(ids(planner.plan(GoldenCases.build("fat_loss_ul_ppl5_history"), PlannerFixtures.MONDAY).mesocycle.explanations).contains(RuleIds.VOL_09))
    }

    @Test
    fun cardioCites_CAR02_CAR03_CAR06_CAR07_ORD03_andHiitOverrides_CAR04_CAR05() {
        val m = planner.plan(inputs, PlannerFixtures.MONDAY).mesocycle
        assertTrue(ids(m.weeks.first().cardio.map { it.explanation }).containsAll(listOf(RuleIds.CAR_02, RuleIds.CAR_03, RuleIds.CAR_06, RuleIds.CAR_07)))
        val six = PlannerFixtures.inputs(p1.copy(goal = Goal.FAT_LOSS, daysPerWeek = 6), gyms = listOf(PlannerFixtures.base))
        val fin = planner.plan(six, PlannerFixtures.MONDAY).mesocycle.weeks.first().cardio
        assertTrue(ids(fin.map { it.explanation }).contains(RuleIds.ORD_03), fin.map { it.placement }.toString())
        val hiit = PlanOverride("h", PlannerFixtures.NOW, OverrideSource.USER, null, PlanMutation.AddCardio(3, 24, "stationary_bike", CardioKind.HIIT_4X4, "x"))
        val withHiit = planner.plan(inputs.copy(overrides = listOf(hiit)), PlannerFixtures.MONDAY).mesocycle.weeks.first().cardio
        assertTrue(ids(withHiit.map { it.explanation }).containsAll(listOf(RuleIds.CAR_04, RuleIds.CAR_05, RuleIds.OVERRIDE)))
    }

    @Test
    fun travelWeekNotesCite_TRV01_TRV02_TRV03() {
        val travelMonday = PlannerFixtures.MONDAY.plusWeeks(2)
        val m = planner.plan(inputs.copy(travelWeeks = listOf(travelMonday)), travelMonday).mesocycle
        val notes = ids(m.weeks.first { it.startDate == travelMonday }.notes)
        assertTrue(notes.containsAll(listOf(RuleIds.TRV_01, RuleIds.TRV_02, RuleIds.TRV_03)))
    }

    @Test
    fun timeLadderDeepLevels_TIMEL5_TIMEL6_REST04_andIsolationDrop_MIX01() {
        val e2 = realize(3, today = TodayContext(Weeks.dateFor(week.startDate, 5), PlannerFixtures.HOTEL_ID))
        assertTrue(ids(e2.modifiers).contains(RuleIds.MIX_01))
        val tc = TimeConcessions(c)
        fun w(slot: String, role: SlotRole, id: String, lt: LoadType, sets: Int, required: Boolean = true) = WorkBlock(slot, role, false, required, kb.requireExercise(id), 0, lt, sets, sets, null)
        val blocks = mutableListOf(
            w("s1", SlotRole.PRIMARY, "goblet_squat", LoadType.DUMBBELL, 4), w("s2", SlotRole.SECONDARY, "split_squat", LoadType.DUMBBELL, 4),
            w("s3", SlotRole.SECONDARY, "dumbbell_bench_press", LoadType.DUMBBELL, 4), w("s4", SlotRole.ACCESSORY, "dumbbell_floor_press", LoadType.DUMBBELL, 4, required = false),
        )
        val r = tc.apply(blocks, 30, 5, 0, true, DoubleArray(Muscle.entries.size), setOf("s1"))
        assertTrue(tc.levelsOf(r).containsAll(listOf(RuleIds.TIME_L4, RuleIds.TIME_L5, RuleIds.TIME_L6)), tc.levelsOf(r).toString())
        val tight = realize(0, today = TodayContext(PlannerFixtures.MONDAY, PlannerFixtures.HOME_ID, minutesOverride = 35))
        val lv = ids(tight.modifiers)
        if (RuleIds.TIME_L4 in lv) assertTrue(blockIds(tight).contains(RuleIds.REST_04))
        assertTrue(RuleIds.TIME_01 in lv)
    }

    @Test
    fun readinessCites_SLP01_SLP02_STR01_RDY05_andLayoffRow1Reassures_LAY03() {
        val rm = ReadinessModifiers(c)
        fun ck(d: java.time.LocalDate, sleep: Double, stress: Int) = ReadinessCheckin("r$d", d, sleepHours = sleep, stress = stress, score = 60, band = ReadinessBand.AMBER,
            explanation = Explanations.of(RuleIds.RDY_01, "f"), loggedAt = PlannerFixtures.NOW)
        val day = PlannerFixtures.MONDAY
        val e = rm.evaluate(TodayContext(day, "g", readiness = ck(day, 5.5, 5).copy(band = ReadinessBand.GREEN, score = 80)), (1..6).map { ck(day.minusDays(it.toLong()), 6.2, 4) })
        assertTrue(ids(e.explanations).containsAll(listOf(RuleIds.SLP_01, RuleIds.SLP_02, RuleIds.SLP_03, RuleIds.STR_01, RuleIds.RDY_05)))
        val pol = com.gymcoach.core.planner.realize.LayoffPolicy(kb, c)
        val past = day.minusDays(16)
        val rec = com.gymcoach.core.model.SessionRecord("rec", past, PlannerFixtures.HOME_ID, status = com.gymcoach.core.model.SessionStatus.COMPLETED)
        val set = com.gymcoach.core.model.SetLog("s", "rec", null, "back_squat", 1, com.gymcoach.core.model.SetKind.WORKING, reps = 5, loggedAt = past.atTime(18, 0).toInstant(java.time.ZoneOffset.UTC))
        val mod = pol.forBlockWithGap(com.gymcoach.core.model.MovementPattern.SQUAT, "back_squat", day, 7, inputs.copy(recentSessions = listOf(rec), recentSets = listOf(set)), emptyList(), 38, TrainingAge.INTERMEDIATE)
        assertTrue(mod != null && RuleIds.LAY_03 in mod.explanation.ruleIds)
    }

    @Test
    fun mildLimitationWarnings_INJ00_ROM02_ROM03_andAgeSingleSet_VOL02() {
        val shoulder = inputs.copy(profile = p1.copy(limitations = listOf(PlannerFixtures.lim(LimitationRegion.SHOULDER, 2))))
        val p = realize(0, inp = shoulder)
        val bench = p.blocks.first { it.exerciseId == "barbell_bench_press" }
        assertTrue(ids(bench.warnings).containsAll(listOf(RuleIds.INJ_00, RuleIds.ROM_02, RuleIds.ROM_03)))
        val novice62 = PlannerFixtures.inputs(PlannerFixtures.p2().copy(birthYear = 1964), gyms = listOf(PlannerFixtures.base), bodyWeightKg = 70.0)
        val m = planner.plan(novice62, PlannerFixtures.MONDAY).mesocycle
        val w = m.weeks.first()
        val s = w.sessions.first()
        val r = planner.realize(s, w, novice62, TodayContext(Weeks.dateFor(w.startDate, s.weekday), m.gymId), emptyList())
        assertTrue(ids(r.modifiers).containsAll(listOf(RuleIds.AGE_02, RuleIds.VOL_02)))
        assertTrue(r.blocks.all { b -> b.sets.count { it.kind == com.gymcoach.core.model.SetKind.WORKING || it.kind == com.gymcoach.core.model.SetKind.CALIBRATION } == 1 })
    }

    @Test
    fun plateauStep2CitesTheE1rmTable_E1RM03() {
        val st = states.first { it.key.exerciseId == "barbell_bench_press" }.copy(status = com.gymcoach.core.model.ProgressionStatus.PLATEAU, plateauStep = 1, bestE1rm14d = 110.0)
        val r = planner.rollWeek(inputs.copy(progressionStates = listOf(st)), PlannerFixtures.MONDAY.plusWeeks(1))
        assertTrue(ids(r.ladderNotes).contains(RuleIds.E1RM_03))
    }
}
