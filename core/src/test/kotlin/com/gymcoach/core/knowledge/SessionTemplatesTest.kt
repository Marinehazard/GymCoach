package com.gymcoach.core.knowledge

import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.SlotRole
import com.gymcoach.core.model.SplitKind
import com.gymcoach.core.model.TrainingAge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** templates.json per SPEC §3.4 incl. the v1.2 strength split rows. */
class SessionTemplatesTest {
    private val kb = KnowledgeFixture.kb

    @Test
    fun upperAIsTheSpecExample() {
        val t = SessionTemplates.requireTemplate(kb, "UPPER_A")
        assertEquals((1..8).map { "s$it" }, t.slots.map { it.slotId })
        val s1 = t.slots[0]
        assertTrue(s1.heavy && s1.required && s1.role == SlotRole.PRIMARY)
        assertEquals(listOf(MovementPattern.HORIZONTAL_PUSH), s1.patterns)
        assertEquals(listOf(LoadType.BARBELL, LoadType.MACHINE), s1.preferLoadTypes)
        assertEquals("s6", t.slots[4].supersetWith)
        assertEquals(listOf(Muscle.CHEST, Muscle.TRICEPS), t.slots[6].targetMuscles)
        assertTrue(!t.slots[6].required && !t.slots[7].required)
    }

    @Test
    fun splitRowsInFileOrder() {
        val rows = kb.splits.map { it.kind to it.sessionTemplateIds }
        assertEquals(SplitKind.FULL_BODY_1 to listOf("FULL_A"), rows[0])
        assertEquals(listOf(SplitKind.FULL_BODY_1, SplitKind.FULL_BODY_2, SplitKind.FULL_BODY_3, SplitKind.FULL_BODY_3, SplitKind.UPPER_LOWER_FULL_3,
            SplitKind.UPPER_LOWER_FULL_3, SplitKind.UPPER_LOWER_4, SplitKind.UPPER_LOWER_4, SplitKind.UL_PPL_5, SplitKind.UL_PPL_5, SplitKind.PPL_6,
            SplitKind.UL_6, SplitKind.TRAVEL_2, SplitKind.CUSTOM), kb.splits.map { it.kind })   // + the §3.4.1 personal row (Phase 2)
        val four = listOf(Goal.HYPERTROPHY, Goal.GENERAL_FITNESS, Goal.FAT_LOSS, Goal.ENDURANCE_SUPPORT)
        for (s in kb.splits.filter { it.kind in setOf(SplitKind.UPPER_LOWER_4, SplitKind.UL_PPL_5, SplitKind.UPPER_LOWER_FULL_3) }) {
            assertTrue(s.forGoals == four || s.forGoals == listOf(Goal.STRENGTH), "${s.kind} ${s.forGoals}")
        }
        val ul6 = kb.splits.single { it.kind == SplitKind.UL_6 }
        assertEquals(listOf(Goal.STRENGTH), ul6.forGoals)
        assertEquals(listOf("UPPER_A_S4", "LOWER_A_S", "UPPER_B_S", "LOWER_B_S", "UPPER_A_S4", "LOWER_A_S"), ul6.sessionTemplateIds)
        assertEquals(four, kb.splits.single { it.kind == SplitKind.PPL_6 }.forGoals)
        val fb3 = kb.splits.filter { it.kind == SplitKind.FULL_BODY_3 }
        assertEquals(listOf(TrainingAge.NOVICE), fb3[0].forTrainingAges)
        assertEquals(listOf(Goal.ENDURANCE_SUPPORT), fb3[1].forGoals)
    }

    @Test
    fun everyDaysGoalAgeCombinationMatchesARow() {
        for (days in 1..6) for (goal in Goal.entries) for (age in TrainingAge.entries) {
            val rows = SessionTemplates.applicableSplits(kb, days, goal, age)
            assertTrue(rows.isNotEmpty(), "no row for $days days, $goal, $age")
        }
        assertEquals(SplitKind.FULL_BODY_3, SessionTemplates.applicableSplits(kb, 3, Goal.ENDURANCE_SUPPORT, TrainingAge.ADVANCED).first().kind)
        assertEquals(SplitKind.PPL_6, SessionTemplates.applicableSplits(kb, 6, Goal.ENDURANCE_SUPPORT, TrainingAge.ADVANCED).first().kind)
        assertEquals(SplitKind.FULL_BODY_3, SessionTemplates.applicableSplits(kb, 3, Goal.STRENGTH, TrainingAge.NOVICE).first().kind)
        assertEquals("FULL_A_S", SessionTemplates.applicableSplits(kb, 3, Goal.STRENGTH, TrainingAge.INTERMEDIATE).first().sessionTemplateIds[2])
        assertEquals(SplitKind.UL_6, SessionTemplates.applicableSplits(kb, 6, Goal.STRENGTH, TrainingAge.INTERMEDIATE).first().kind)
        assertTrue(SessionTemplates.applicableSplits(kb, 2, Goal.HYPERTROPHY, TrainingAge.NOVICE).none { it.kind == SplitKind.TRAVEL_2 })
    }

    @Test
    fun strengthBackoffResolutionTable() {
        val expected = mapOf(
            "UPPER_B_S.s2" to "UPPER_A_S4.s1", "UPPER_A_S4.s3" to "UPPER_B_S.s3", "LOWER_B_S.s2" to "LOWER_A_S.s1", "LOWER_A_S.s2" to "LOWER_B_S.s1",
            "PUSH_S.s1" to "UPPER_A_S5.s1", "UPPER_A_S5.s3" to "PUSH_S.s2", "LEGS_S.s1" to "LOWER_A_S5.s1", "LOWER_A_S5.s2" to "LEGS_S.s2",
            "FULL_A_S.s1" to "LOWER_X.s1", "FULL_A_S.s2" to "UPPER_X.s1",
        )
        val actual = kb.templates.values.flatMap { t -> t.slots.mapNotNull { s -> s.backoffOf?.let { "${t.id}.${s.slotId}" to it } } }.toMap()
        assertEquals(expected, actual)
        for ((from, to) in expected) {
            val target = assertNotNull(SessionTemplates.slot(kb, SessionTemplates.parseRef(to)!!))
            assertTrue(target.heavy, to)
            val slot = SessionTemplates.slot(kb, SessionTemplates.parseRef(from)!!)!!
            assertTrue(!slot.heavy, from)
            assertEquals(target, SessionTemplates.backoffTarget(kb, slot))
        }
        assertEquals(kb.templates.getValue("PULL").slots, kb.templates.getValue("PULL_S").slots)
        assertEquals(kb.templates.getValue("UPPER_A").slots, kb.templates.getValue("UPPER_X").slots)
        assertEquals(kb.templates.getValue("LOWER_A").slots, kb.templates.getValue("LOWER_X").slots)
        assertEquals(listOf(MovementPattern.INCLINE_PUSH, MovementPattern.CHEST_FLY, MovementPattern.ELBOW_EXTENSION),
            kb.templates.getValue("UPPER_B_S").slots.single { it.slotId == "s7" }.patterns)
    }

    @Test
    fun travelBGivesSideDeltsASecondExposure() {
        val b = kb.templates.getValue("TRAVEL_B")
        val s5 = b.slots.single { it.slotId == "s5" }
        assertEquals(listOf(MovementPattern.SHOULDER_ABDUCTION), s5.patterns)
        assertEquals(listOf(Muscle.SIDE_DELTS), s5.targetMuscles)
        assertEquals("s6", s5.supersetWith)
        assertTrue(kb.templates.getValue("TRAVEL_A").slots.any { Muscle.SIDE_DELTS in it.targetMuscles })
    }

    @Test
    fun priorityIsolationSlotsHaveNoTargetMuscles() {
        for (id in listOf("FULL_A", "FULL_B")) {
            val t = kb.templates.getValue(id)
            for (sid in listOf("s6", "s7")) {
                val s = t.slots.single { it.slotId == sid }
                assertTrue(s.targetMuscles.isEmpty() && s.role == SlotRole.ISOLATION && s.patterns.first() == MovementPattern.SHOULDER_ABDUCTION)
            }
        }
    }

    @Test
    fun parseRef() {
        assertEquals(SlotRef("UPPER_B_S", "s3"), SessionTemplates.parseRef("UPPER_B_S.s3"))
        assertNull(SessionTemplates.parseRef("nodot"))
        assertNull(SessionTemplates.parseRef(".s1"))
        assertEquals("UPPER_B_S.s3", SlotRef("UPPER_B_S", "s3").toString())
    }
}
