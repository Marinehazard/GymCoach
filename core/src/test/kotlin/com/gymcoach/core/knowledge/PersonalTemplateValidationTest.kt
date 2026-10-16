package com.gymcoach.core.knowledge

import com.gymcoach.core.model.Band
import com.gymcoach.core.model.DrillDose
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.RampStep
import com.gymcoach.core.model.SessionTemplate
import com.gymcoach.core.model.SplitKind
import com.gymcoach.core.model.SplitTemplate
import com.gymcoach.core.planner.PlannerFixtures
import com.gymcoach.core.substitution.LoadTransfer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SPEC §3.4.1 (Phase 2): personal templates/rows are validated, never picked by the generic walk; transfer seeds stay on one load scale. */
class PersonalTemplateValidationTest {
    private val kb = KnowledgeFixture.kb
    private val frozen = KnowledgeFixture.frozenIdsV1

    private fun copy(templates: Map<String, SessionTemplate> = kb.templates, splits: List<SplitTemplate> = kb.splits) = KnowledgeBase(
        kb.exercises, kb.exerciseOrder, kb.repProfiles, kb.equipment, kb.groups, kb.presets, kb.renamedExerciseIds, kb.renamedEquipmentIds,
        templates, splits, kb.rules, kb.drills, kb.checkinQuestions, kb.supplements, kb.cardioModalities, kb.nutritionTips, kb.rpLandmarks, kb.loadTransfer,
    )

    @Test
    fun shippedPersonalRowIsValidAndOnlyReachableById() {
        assertEquals(emptyList(), KnowledgeValidator(kb, frozen).validate())
        val row = assertNotNull(SessionTemplates.personalSplit(kb, "joe_5day"))
        assertEquals(SplitKind.CUSTOM, row.kind)
        assertNull(SessionTemplates.personalSplit(kb, "nobody"))
        for (d in 1..6) for (g in com.gymcoach.core.model.Goal.entries) for (a in com.gymcoach.core.model.TrainingAge.entries) {
            assertTrue(SessionTemplates.applicableSplits(kb, d, g, a).none { it.kind == SplitKind.CUSTOM })
        }
    }

    @Test
    fun brokenPersonalFieldsAreReported() {
        val a = kb.templates.getValue("JOE_A")
        val s1 = a.slots.first()
        val broken = a.copy(
            slots = listOf(s1.copy(exerciseIds = listOf("barbell_bench_press", "no_such_exercise"), reps = Band(0, 3), rir = 7, ramp = listOf(RampStep(0.5, 5), RampStep(0.4, 3))),
                a.slots[1].copy(loadFactor = 1.5)) + a.slots.drop(2),
            warmupDrills = listOf(DrillDose("no_such_drill")), raiseExerciseIds = listOf("back_squat"),
        )
        val v = KnowledgeValidator(copy(templates = kb.templates + ("JOE_A" to broken)), frozen).validate()
        for (needle in listOf("barbell_bench_press (HORIZONTAL_PUSH) is outside", "unknown exercise no_such_exercise", "reps 0-3", "rir 7", "ramp percentages must increase",
            "load_factor 1.5", "unknown drill no_such_drill", "raise exercise back_squat")) {
            assertTrue(v.any { it.startsWith("R10") && needle in it }, "$needle not in $v")
        }
        val row = kb.splits.first { it.kind == SplitKind.CUSTOM }
        val badRow = row.copy(dropOrder = listOf("UPPER_A"), closingWeek = "party")
        val generic = kb.splits.first().copy(dropOrder = listOf("FULL_A"))
        val v2 = KnowledgeValidator(copy(splits = kb.splits.map { if (it === row) badRow else if (it === kb.splits.first()) generic else it }), frozen).validate()
        assertTrue(v2.any { "drop_order names UPPER_A" in it }, v2.toString())
        assertTrue(v2.any { "unknown closing_week 'party'" in it }, v2.toString())
        assertTrue(v2.any { "personal-row fields on a generic row" in it }, v2.toString())
    }

    @Test
    fun siblingSeedsNeverCrossTheMachineScale() {
        // back_squat → smith_machine_squat has no direct seed; the closest same-scale sibling (front squat × 0.8) carries it,
        // never the leg press's "sled load" × 2.0 (which used to seed a 180 kg Smith squat).
        val t = LoadTransfer(PlannerFixtures.kb)
        val (kg, why) = assertNotNull(t.fromReplaced(kb.requireExercise("back_squat"), 90.0, kb.requireExercise("smith_machine_squat"), LoadType.SMITH))
        assertEquals(72.0, kg, 1e-9)
        assertEquals("front_squat", why.inputs["via"])
        // A machine target still takes the machine sibling.
        val (press, why2) = assertNotNull(t.fromReplaced(kb.requireExercise("back_squat"), 90.0, kb.requireExercise("leg_press_horizontal"), LoadType.MACHINE))
        assertTrue(why2.inputs["via"] in setOf("leg_press_45", "hack_squat"), why2.toString())
        assertTrue(press > 90.0)
    }
}
