package com.gymcoach.core.planner

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.SessionTemplates
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.SplitKind
import com.gymcoach.core.model.TrainingAge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SplitSelectorTest {
    private val kb = PlannerFixtures.kb
    private val sel = SplitSelector(kb)

    @Test
    fun everyDaysGoalTrainingAgeCombinationMatchesARow_SPLIT01() {
        for (d in 1..6) for (g in Goal.entries) for (t in TrainingAge.entries) {
            val row = assertNotNull(sel.row(d, g, t), "days $d $g $t")
            assertEquals(d, row.daysPerWeek)
            assertTrue(row.kind != SplitKind.TRAVEL_2)
        }
    }

    @Test
    fun rowSelectionByDaysGoalAndTrainingAge() {
        assertEquals(SplitKind.FULL_BODY_1, sel.row(1, Goal.HYPERTROPHY, TrainingAge.ADVANCED)?.kind)
        assertEquals(SplitKind.FULL_BODY_2, sel.row(2, Goal.STRENGTH, TrainingAge.INTERMEDIATE)?.kind)
        assertEquals(SplitKind.FULL_BODY_3, sel.row(3, Goal.STRENGTH, TrainingAge.NOVICE)?.kind)
        assertEquals(SplitKind.FULL_BODY_3, sel.row(3, Goal.ENDURANCE_SUPPORT, TrainingAge.ADVANCED)?.kind)
        val ulf = assertNotNull(sel.row(3, Goal.STRENGTH, TrainingAge.INTERMEDIATE))
        assertEquals(SplitKind.UPPER_LOWER_FULL_3, ulf.kind)
        assertEquals(listOf("UPPER_X", "LOWER_X", "FULL_A_S"), ulf.sessionTemplateIds)
        assertEquals(listOf("UPPER_A_S4", "LOWER_A_S", "UPPER_B_S", "LOWER_B_S"), sel.row(4, Goal.STRENGTH, TrainingAge.ADVANCED)?.sessionTemplateIds)
        assertEquals(listOf("UPPER_A", "LOWER_A", "UPPER_B", "LOWER_B"), sel.row(4, Goal.HYPERTROPHY, TrainingAge.ADVANCED)?.sessionTemplateIds)
        assertEquals(listOf("UPPER_A_S5", "LOWER_A_S5", "PUSH_S", "PULL_S", "LEGS_S"), sel.row(5, Goal.STRENGTH, TrainingAge.NOVICE)?.sessionTemplateIds)
        assertEquals(SplitKind.UL_6, sel.row(6, Goal.STRENGTH, TrainingAge.INTERMEDIATE)?.kind)
        assertEquals(SplitKind.PPL_6, sel.row(6, Goal.ENDURANCE_SUPPORT, TrainingAge.INTERMEDIATE)?.kind)
        assertEquals(SplitKind.TRAVEL_2, sel.travelRow()?.kind)
    }

    @Test
    fun frq03FallbackChainDropsOneDayAtATime() {
        val chain = sel.chain(5, Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE).map { it.kind }
        assertEquals(listOf(SplitKind.UL_PPL_5, SplitKind.UPPER_LOWER_4, SplitKind.UPPER_LOWER_FULL_3, SplitKind.FULL_BODY_3, SplitKind.FULL_BODY_2, SplitKind.FULL_BODY_1), chain)
        assertEquals(SplitKind.UL_PPL_5, sel.chain(6, Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE)[1].kind)
    }

    @Test
    fun preferredWeekdaysReplaceTheDefaultsWhenTheCountMatches() {
        val row = checkNotNull(sel.row(4, Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE))
        assertEquals(listOf(1, 2, 4, 5), sel.choose(row, emptyList()).schedule.map { it.weekday })
        assertEquals(listOf(1, 2, 3, 5), sel.choose(row, listOf(5, 1, 3, 2)).schedule.map { it.weekday })
        assertEquals(listOf(1, 2, 4, 5), sel.choose(row, listOf(1, 2)).schedule.map { it.weekday })
        // Mon/Tue/Wed/Fri keeps UPPER_A Mon, LOWER_A Tue, UPPER_B Wed, LOWER_B Fri (SPLIT-02 satisfied).
        assertEquals(listOf("UPPER_A", "LOWER_A", "UPPER_B", "LOWER_B"), sel.choose(row, listOf(1, 2, 3, 5)).schedule.map { it.template.id })
        val one = checkNotNull(sel.row(1, Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE))
        assertTrue(sel.choose(one, emptyList()).explanations.any { RuleIds.FRQ_01 in it.ruleIds })
    }

    @Test
    fun split02RotatesOrReportsConsecutiveHeavyDays() {
        val fb2 = checkNotNull(sel.row(2, Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE))
        val c = sel.choose(fb2, listOf(1, 2))
        assertTrue(sel.conflicts(c.schedule).isNotEmpty())
        assertTrue(c.explanations.any { RuleIds.SPLIT_02 in it.ruleIds && it.inputs.containsKey("conflicts") })
        val fb3 = checkNotNull(sel.row(3, Goal.HYPERTROPHY, TrainingAge.NOVICE))
        val rotated = sel.choose(fb3, listOf(1, 2, 3))
        assertTrue(sel.conflicts(rotated.schedule).isEmpty())
        assertTrue(rotated.explanations.any { RuleIds.SPLIT_02 in it.ruleIds && it.inputs.containsKey("rotation") })
        assertEquals(setOf("legs"), SplitSelector.heavyGroups(SessionTemplates.requireTemplate(kb, "LOWER_A")))
    }
}
