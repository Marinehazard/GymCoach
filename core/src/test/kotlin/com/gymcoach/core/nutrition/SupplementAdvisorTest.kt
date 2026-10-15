package com.gymcoach.core.nutrition

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeFixture
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.MedicalFlag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §5.6 / G4: creatine 5 g, caffeine cut-offs 10:00 / 14:00, kidney → refer, RED-03 gate, SUP-12 on every row. */
class SupplementAdvisorTest {
    private val kb = KnowledgeFixture.kb
    private val advisor = SupplementAdvisor(kb, NF.c)

    private fun advise(profile: com.gymcoach.core.model.Profile = NF.p1, gate: Boolean = false, fish: Int? = null, vitD: Int = 0, travel: Boolean = false, goal: Goal = Goal.HYPERTROPHY) =
        advisor.advise(profile, 84.0, goal, gate, fish, vitD, travel)

    @Test fun g4Creatine5gAndCaffeineDose() {
        val rows = advise()
        val creatine = rows.single { it.id == RuleIds.SUP_01 }
        assertEquals("5 g/day", creatine.doseText)
        assertFalse(creatine.gated)
        val caffeine = rows.single { it.id == RuleIds.SUP_02 }
        assertTrue(caffeine.doseText.startsWith("start 170–250 mg"), caffeine.doseText)
        assertTrue(caffeine.timing.contains("10:00") && caffeine.timing.contains("14:00"), caffeine.timing)
        assertEquals("3 g/day", advisor.advise(NF.p1, 25.0, Goal.HYPERTROPHY, false, null, 0, false).single { it.id == RuleIds.SUP_01 }.doseText)
    }

    @Test fun g4CaffeineCutoffs() {
        assertEquals("10:00" to "14:00", advisor.caffeineCutoffs("23:00"))
        assertEquals("09:30" to "13:30", advisor.caffeineCutoffs("22:30"))
        assertEquals("11:00" to "15:00", advisor.caffeineCutoffs("00:00"))
    }

    @Test fun kidneyDiseaseRefersInsteadOfACreatineDose() {
        val rows = advise(NF.p1.copy(medicalFlags = setOf(MedicalFlag.KIDNEY_DISEASE)))
        val creatine = rows.single { it.id == RuleIds.SUP_01 }
        assertTrue(creatine.gated)
        assertTrue(creatine.doseText.startsWith("refer"))
    }

    @Test fun red03GateHidesEveryDoseNumber() {
        val rows = advise(gate = true)
        for (r in rows.filter { it.tier != "C" }) {
            assertTrue(r.gated, r.id)
            assertEquals(SupplementAdvisor.GATE_TEXT, r.doseText)
            assertFalse(r.doseText.any { it.isDigit() })
        }
        assertTrue(RuleIds.RED_03.isNotEmpty())
    }

    @Test fun sup12CaveatOnEveryRowAndTierCWithReasons() {
        val rows = advise()
        val caveat = kb.supplements.caveatSup12
        assertTrue(rows.all { r -> caveat.all { it in r.notes } })
        val c = rows.filter { it.tier == "C" }
        assertTrue(c.isNotEmpty())
        assertTrue(c.all { it.doNotReason != null && it.doseText == SupplementAdvisor.NOT_RECOMMENDED })
        assertEquals(RuleIds.SUP_12, "SUP-12")
    }

    @Test fun conditionalItems() {
        assertFalse(advise().any { it.id == RuleIds.SUP_04 })
        assertTrue(advise(vitD = 2).any { it.id == RuleIds.SUP_04 })
        assertFalse(advise().any { it.id == RuleIds.SUP_08 })
        val mel = advise(travel = true).single { it.id == RuleIds.SUP_08 }
        assertTrue(mel.legalNote != null)
        assertFalse(advise(fish = 3).any { it.id == RuleIds.SUP_05 })
        assertTrue(advise(fish = 1).any { it.id == RuleIds.SUP_05 })
        assertFalse(advise().any { it.id == RuleIds.SUP_10 })
        assertTrue(advise(goal = Goal.FAT_LOSS).any { it.id == RuleIds.SUP_10 })
        assertTrue(advise().any { it.id == RuleIds.SUP_03 } && advise().any { it.id == RuleIds.SUP_09 })
    }
}
