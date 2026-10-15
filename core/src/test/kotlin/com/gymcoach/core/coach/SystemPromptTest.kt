package com.gymcoach.core.coach

import com.gymcoach.core.util.Hashing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemPromptTest {
    private val kb = CoachFixtures.kb
    private val prompt = SystemPrompt.assemble(kb)

    @Test
    fun sha256OfTheAssembledPromptMatchesTheFixture() {
        val expected = CoachFixtures.resource("coach/system_prompt_v1.sha256").trim()
        val actual = Hashing.sha256Hex(prompt)
        assertEquals(expected, actual, "the system prompt changed: update core/src/test/resources/coach/system_prompt_v1.sha256 deliberately (it forces a new conversation)")
        assertEquals(actual, SystemPrompt.hash(kb))
        assertEquals("v1", SystemPrompt.PROMPT_VERSION)
    }

    @Test
    fun assemblyIsDeterministic() {
        assertEquals(prompt, SystemPrompt.assemble(kb))
    }

    @Test
    fun resourceIsTheSpecTextAndEndsWithTheVocabularyHeading() {
        val base = CoachFixtures.resource(SystemPrompt.RESOURCE)
        assertTrue(base.startsWith("You are the coach inside GymCoach, an offline-first Android training app"))
        assertTrue(base.endsWith("## Exercise vocabulary (id — name)\n"))
        for (h in listOf("## Hard rules", "## How the app talks to you", "## Tools", "## Knowledge digest (rule id [confidence] statement — source)", "## Style")) {
            assertTrue(base.contains("\n\n$h\n\n"), h)
        }
        assertTrue(prompt.startsWith(base))
        assertTrue(base.contains(SupplementCaveat.TEXT), "Hard rule 4 carries the SUP-12 sentence verbatim")
    }

    @Test
    fun vocabularyAppendixListsEveryExerciseThenPatternsAndMuscles() {
        val appendix = prompt.substringAfter("## Exercise vocabulary (id — name)\n")
        val vocab = appendix.substringBefore("\n## Patterns\n").trimEnd('\n').lines()
        assertEquals(kb.exerciseOrder.size, vocab.size)
        assertEquals(156, vocab.size)
        assertEquals(kb.exerciseOrder.first() + " — " + kb.exercises[kb.exerciseOrder.first()]!!.name, vocab.first())
        val patterns = appendix.substringAfter("\n## Patterns\n").substringBefore("\n## Muscles\n").trimEnd('\n').lines()
        assertEquals(29, patterns.size)
        assertTrue("horizontal_push" in patterns)
        val muscles = appendix.substringAfter("\n## Muscles\n").trimEnd('\n').lines()
        assertEquals(21, muscles.size)
        assertEquals("chest", muscles.first())
        assertEquals("calves", muscles.last())
    }

    @Test
    fun everyRuleIdInTheDigestExistsInRulesJson() {
        val digest = prompt.substringAfter("## Knowledge digest (rule id [confidence] statement — source)\n").substringBefore("## Style")
        val ids = Regex("\\b[A-Z][A-Z0-9]{1,4}-[0-9]{2}\\b").findAll(prompt.substringBefore("## Exercise vocabulary")).map { it.value }.toSet()
        val digestIds = Regex("\\b[A-Z][A-Z0-9]{1,4}-[0-9]{2}\\b").findAll(digest).map { it.value }.toSet()
        assertTrue(digestIds.size >= 90, "digest ids: ${digestIds.size}")
        val missing = ids.filter { kb.rules.get(it) == null }
        assertTrue(missing.isEmpty(), "rule ids in the prompt but not in rules.json: $missing")
        for (line in digest.lines().filter { it.isNotBlank() && !it.startsWith("(") && !it.startsWith("##") }) {
            assertTrue(Regex("^[A-Z][A-Z0-9]{1,4}-[0-9]{2} \\[").containsMatchIn(line), "digest line format: $line")
        }
    }

    @Test
    fun promptHasNoVolatileOrPersonalContent() {
        assertFalse(Regex("\\b20[0-9]{2}-[0-9]{2}-[0-9]{2}\\b").containsMatchIn(prompt), "no ISO dates")
        assertFalse(Regex("\\b[0-2]?[0-9]:[0-5][0-9]\\b").containsMatchIn(prompt), "no clock times")
        for (word in listOf("Johannes", "Ulm", "kiteherz", "sk-ant", "@")) assertFalse(prompt.contains(word), word)
        assertFalse(prompt.contains("<context schema=\"1\">{"), "the context is sent in the user turn, never in the system prompt")
    }

    @Test
    fun estimatedLengthIsAtLeastTheCacheMinimum() {
        assertTrue(TokenEstimator.estimate(prompt) >= 1024, "≥ 512 tokens (Opus 5) and ≥ 1,024 (Sonnet 5): ${TokenEstimator.estimate(prompt)}")
    }

    @Test
    fun compactionPromptIsTheFixedSpecSentence() {
        assertEquals(
            "Summarise the following coaching conversation in at most 600 tokens: standing decisions, user preferences, open questions, pending proposals. Facts only, no advice.",
            SystemPrompt.compactionPrompt(),
        )
    }
}
