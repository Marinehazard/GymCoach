package com.gymcoach.core.coach

import com.gymcoach.core.explain.RuleIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RedFlagScannerTest {
    @Test
    fun emergencyPhrasesInEnglishAndGermanAreFlagged() {
        assertEquals(RedFlagHit("chest pain", true), RedFlagScanner.scan("I had CHEST PAIN during squats"))
        assertEquals(RedFlagHit("brustschmerz", true), RedFlagScanner.scan("Seit gestern Brustschmerzen beim Laufen"))
        assertEquals(RedFlagHit("dizzy", true), RedFlagScanner.scan("felt dizzy after the last set"))
        assertEquals(RedFlagHit("herzrasen", true), RedFlagScanner.scan("Herzrasen nach dem Training"))
        assertEquals(RedFlagHit("short of breath", true), RedFlagScanner.scan("Very short of breath walking upstairs"))
        assertEquals(RedFlagHit("fainted", true), RedFlagScanner.scan("I fainted yesterday"))
    }

    @Test
    fun referPhrasesAreFlaggedWithoutEmergency() {
        assertEquals(RedFlagHit("numb", false), RedFlagScanner.scan("my left hand goes numb"))
        assertEquals(RedFlagHit("numb", false), RedFlagScanner.scan("Numbness in the leg"))
        assertEquals(RedFlagHit("kribbeln", false), RedFlagScanner.scan("Kribbeln im Fuß"))
        assertEquals(RedFlagHit("can't feel", false), RedFlagScanner.scan("I can’t feel my toes"))
        assertEquals(RedFlagHit("dark urine", false), RedFlagScanner.scan("dark urine after the workout"))
        assertEquals(RedFlagHit("fieber", false), RedFlagScanner.scan("Ich habe Fieber"))
        assertEquals(RedFlagHit("worst headache", false), RedFlagScanner.scan("worst headache of my life during deadlifts"))
        assertEquals(RedFlagHit("gave way", false), RedFlagScanner.scan("my knee gave way"))
    }

    @Test
    fun emergencyWinsOverReferWhenBothOccur() {
        assertEquals(RedFlagHit("palpitations", true), RedFlagScanner.scan("fever and palpitations"))
    }

    @Test
    fun ordinaryTrainingChatIsNotFlagged() {
        for (t in listOf(
            "What number of sets should I do for chest?", "Can we add a bike day on Wednesday?", "How much protein per meal?",
            "Swap deadlifts for RDLs today", "Mein Kniebeugen-Gewicht stagniert", "",
        )) assertNull(RedFlagScanner.scan(t), t)
    }

    @Test
    fun vocabularyCoversTheSpecList() {
        val all = RedFlagScanner.EMERGENCY + RedFlagScanner.REFER
        assertEquals(33, all.size)
        assertTrue(all.all { it == it.lowercase() })
        assertEquals(listOf(RuleIds.RED_01, RuleIds.POL_02), RedFlagScanner.RULE_IDS)
        assertEquals(listOf("RED-01", "POL-02"), RedFlagScanner.RULE_IDS)
    }
}
