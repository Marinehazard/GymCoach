package com.gymcoach.core.coach

import com.gymcoach.core.explain.RuleIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupplementCaveatTest {
    @Test
    fun namingASupplementWithoutTheCaveatRequiresIt() {
        assertTrue(SupplementCaveat.check("Take 3-5 g creatine monohydrate daily (SUP-01)."))
        assertTrue(SupplementCaveat.check("Kreatin ist gut untersucht."))
        assertTrue(SupplementCaveat.check("A pre-workout with caffeine 60 min before helps."))
        assertTrue(SupplementCaveat.check("Skip BCAA and HMB."))
        assertTrue(SupplementCaveat.check("Fish oil is optional."))
    }

    @Test
    fun theCaveatSentenceOrNoSupplementMeansNothingToAppend() {
        assertFalse(SupplementCaveat.check("Creatine 3-5 g per day (SUP-01). " + SupplementCaveat.TEXT))
        assertFalse(SupplementCaveat.check("Add one set of rows (VOL-08)."))
        assertFalse(SupplementCaveat.check(""))
    }

    @Test
    fun textIsTheSup12SentenceVerbatim() {
        assertEquals(
            "Not medical advice; check with a physician or pharmacist if you take medication or have a heart, kidney, liver, thyroid or psychiatric condition; prefer third-party-tested products; legal status differs by country.",
            SupplementCaveat.TEXT,
        )
        assertEquals(24, SupplementCaveat.VOCABULARY.size)
        assertEquals(listOf(RuleIds.SUP_12, RuleIds.POL_03), SupplementCaveat.RULE_IDS)
        assertEquals(listOf("SUP-12", "POL-03"), SupplementCaveat.RULE_IDS)
    }
}
