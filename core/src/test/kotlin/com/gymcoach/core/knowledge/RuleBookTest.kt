package com.gymcoach.core.knowledge

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.json.BandSerializer
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.RuleEntry
import com.gymcoach.core.model.RulesFile
import com.gymcoach.core.planner.PlannerConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** SPEC §3.5: rules.json ↔ PlannerConstants (both directions), confidence normalisation, coverage of RuleIds.ALL. */
class RuleBookTest {
    private val kb = KnowledgeFixture.kb
    private val book = kb.rules

    /** (ruleId, key) -> default value, built from PlannerConstants.DEFAULT via the §3.5 key convention. */
    private fun expectedConstants(): Map<Pair<String, String>, Double> {
        val obj = CoreJson.assets.encodeToJsonElement(PlannerConstants.serializer(), PlannerConstants.DEFAULT).jsonObject
        val out = LinkedHashMap<Pair<String, String>, Double>()
        for ((group, leaf, desc) in PlannerConstants.leafFields()) {
            val id = PlannerConstants.ruleIdOf(leaf)
            val key = PlannerConstants.constantKeyOf(leaf)
            val value = (obj.getValue(group) as JsonObject).getValue(leaf)
            when {
                desc === BandSerializer.descriptor -> {
                    val arr = value.jsonArray
                    out[id to "$key.lo"] = (arr[0] as JsonPrimitive).doubleOrNull!!
                    out[id to "$key.hi"] = (arr[1] as JsonPrimitive).doubleOrNull!!
                }
                value is JsonArray -> value.forEachIndexed { i, v -> out[id to "$key.$i"] = (v as JsonPrimitive).doubleOrNull!! }
                value is JsonPrimitive && value.booleanOrNull != null -> out[id to key] = if (value.booleanOrNull == true) 1.0 else 0.0
                else -> out[id to key] = (value as JsonPrimitive).doubleOrNull!!
            }
        }
        return out
    }

    @Test
    fun everyPlannerConstantsLeafHasItsRuleConstantWithTheDefaultValue() {
        val expected = expectedConstants()
        assertTrue(expected.size > 250, "leaf constants: ${expected.size}")
        for ((k, v) in expected) {
            val entry = book.get(k.first)
            assertTrue(entry != null, "rule ${k.first} missing for constant ${k.second}")
            assertEquals(v, entry.constants[k.second], "${k.first}.${k.second}")
        }
    }

    @Test
    fun everyRuleConstantIsAPlannerConstantsLeaf() {
        val expected = expectedConstants()
        for (e in book.entries.values) for ((key, value) in e.constants) {
            assertEquals(expected[e.id to key], value, "rules.json ${e.id}.$key has no matching PlannerConstants leaf / value")
        }
    }

    @Test
    fun car05CarriesHiitBaseWeeks() {
        assertEquals(4.0, book.constant(RuleIds.CAR_05, "hiitBaseWeeks"))
        assertEquals(0.6, book.constant(RuleIds.TIER, "tier2Factor"))
        assertEquals(4.0, book.constant(RuleIds.RIR_CAP, "capMax"))
        assertEquals(0.6, book.constant(RuleIds.R4_2_6, "keepAtMaxThreshold"))
        assertEquals(0.825, book.constant(RuleIds.WU_02, "heavyPcts.3"))
        assertEquals(0.0, book.constant(RuleIds.AGE_03, "maxStrengthAllowed"))
        assertFailsWith<IllegalStateException> { book.constant(RuleIds.CAR_05, "nope") }
        assertFailsWith<IllegalStateException> { book.require("NOPE-01") }
    }

    @Test
    fun ruleIdsAllIsCoveredIncludingProseOnlyIds() {
        for (id in RuleIds.ALL) assertTrue(book.get(id) != null, "rules.json lacks $id")
        for (id in listOf(RuleIds.POL_03, RuleIds.DEF_06, RuleIds.DEF_07, RuleIds.NUT_18)) assertTrue(book.require(id).constants.isEmpty())
        assertEquals("R3 §0.2", book.citation(RuleIds.POL_03))
        assertEquals(RuleIds.ALL.size, book.entries.size, "no ids beyond RuleIds.ALL")
    }

    @Test
    fun everyEntryDecodesStrictly() {
        val file = CoreJson.assets.decodeFromString(RulesFile.serializer(), ClasspathKnowledgeSource().readText("rules.json"))
        val raw = CoreJson.assets.parseToJsonElement(ClasspathKnowledgeSource().readText("rules.json")).jsonObject.getValue("rules").jsonArray
        assertEquals(raw.size, file.rules.size)
        for (el in raw) CoreJson.assets.decodeFromJsonElement(RuleEntry.serializer(), el)
        assertEquals(file.rules.map { it.id }.toSet().size, file.rules.size, "unique ids")
    }

    @Test
    fun compositeTagsAreNormalisedToTheWeakestComponent() {
        val rule = mapOf(
            "strong/moderate" to Confidence.MODERATE, "strong–moderate" to Confidence.MODERATE,
            "moderate/weak" to Confidence.WEAK, "weak/moderate" to Confidence.WEAK, "strong/weak" to Confidence.WEAK,
            "strong/weak/moderate" to Confidence.WEAK, "strong (effect)/weak (relevance)" to Confidence.WEAK,
            "weak/contested" to Confidence.CONTESTED, "policy" to Confidence.STRONG, "strong (policy)" to Confidence.STRONG,
        )
        var seen = 0
        for (e in book.entries.values) {
            val note = e.note ?: continue
            if (!note.startsWith("R3 tag: ")) continue
            val tag = note.removePrefix("R3 tag: ").substringBefore("; ")
            val want = rule[tag]
            assertTrue(want != null, "${e.id}: unknown composite tag '$tag'")
            assertEquals(want, e.confidence, e.id)
            if (tag == "policy" || tag == "strong (policy)") assertTrue(note.endsWith("; policy statement, not an evidence claim"), e.id)
            seen++
        }
        assertTrue(seen >= 20, "composite tags seen: $seen")
        assertEquals(Confidence.WEAK, book.require(RuleIds.VOL_04).confidence)
        assertEquals(Confidence.WEAK, book.require(RuleIds.NUT_04).confidence)
        assertEquals(Confidence.CONTESTED, book.require(RuleIds.RDY_06).confidence)
        assertEquals(Confidence.STRONG, book.require(RuleIds.POL_01).confidence)
        assertEquals(Confidence.MODERATE, book.require(RuleIds.SUP_11).confidence)
        assertEquals(Confidence.WEAK, book.require(RuleIds.SUP_06).confidence)
    }

    @Test
    fun userAdjustableFollowsTheNormalisedTag() {
        for (e in book.entries.values) {
            val adjustable = e.confidence == Confidence.WEAK || e.confidence == Confidence.CONTESTED || e.confidence == Confidence.UNVERIFIED
            assertEquals(adjustable, e.userAdjustable, e.id)
        }
    }

    @Test
    fun planningConventionsAreWeakWithSpecCitation() {
        for (id in listOf(RuleIds.SEL, RuleIds.SUB, RuleIds.REBAL, RuleIds.TIER, RuleIds.R4_2_6, RuleIds.R4_4_5, RuleIds.R4_5_4, RuleIds.R4_5_5,
            RuleIds.RIR_CAP, RuleIds.ROUNDING, RuleIds.OVERRIDE, RuleIds.TIME_L1, RuleIds.TIME_L7)) {
            val e = book.require(id)
            assertEquals(Confidence.WEAK, e.confidence, id)
            assertTrue(e.citation.startsWith("planner convention (SPEC §"), id)
        }
        assertEquals("planner convention (SPEC §3.9)", book.citation(RuleIds.ROUNDING))
    }

    @Test
    fun statementsAreTheR3TableVerbatim() {
        assertEquals("Hypertrophy sets/muscle/week: novice 8–12, intermediate 12–18, advanced 15–25", book.require(RuleIds.VOL_04).statement)
        assertEquals("Schoenfeld 2017; Pelland 2025", book.citation(RuleIds.VOL_04))
        assertEquals("https://pubmed.ncbi.nlm.nih.gov/27433992/", book.require(RuleIds.VOL_04).url)
        assertEquals(8.0, book.constant(RuleIds.VOL_04, "novice.lo"))
    }

    @Test
    fun digestLineFormat() {
        assertEquals("VOL-04 [weak] Hypertrophy sets/muscle/week: novice 8–12, intermediate 12–18, advanced 15–25 — Schoenfeld 2017; Pelland 2025",
            book.digestLine(RuleIds.VOL_04))
        assertTrue(book.digestLine(RuleIds.E1RM_01).startsWith("E1RM-01 [strong] Epley"))
    }
}
