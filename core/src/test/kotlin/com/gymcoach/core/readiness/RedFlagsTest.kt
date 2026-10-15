package com.gymcoach.core.readiness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RED-01 vocabulary in §5.2 order; the first four are emergencies; hand-off texts; POL-02 (no diagnosis words). */
class RedFlagsTest {

    @Test fun red01ListOrderAndEmergencySplit() {
        val ids = RedFlags.items.map { it.id }
        assertEquals(12, ids.size)
        assertEquals(listOf("chest_pain", "dyspnoea", "dizziness_fainting", "palpitations"), ids.take(4))
        assertTrue(RedFlags.items.take(4).all { it.emergency })
        assertTrue(RedFlags.items.drop(4).none { it.emergency })
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("fever_illness", ids.last())
    }

    @Test fun handoffTexts() {
        assertTrue(RedFlags.handoffText(true).contains("call emergency services"))
        assertTrue(RedFlags.handoffText(false).contains("see a professional now"))
    }

    @Test fun pol02NoDiagnosisLanguage() {
        val banned = listOf("heart attack", "stroke", "thrombosis", "dvt", "rhabdo", "cauda equina", "you have", "diagnos", "fracture")
        for (item in RedFlags.items) {
            val t = item.text.lowercase()
            for (b in banned) assertFalse(t.contains(b), "${item.id}: '$b'")
        }
        for (e in listOf(true, false)) for (b in banned) assertFalse(RedFlags.handoffText(e).lowercase().contains(b))
    }
}
