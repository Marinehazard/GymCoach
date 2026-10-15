package com.gymcoach.core.planner

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.Band
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind

/**
 * §4.1: guards the grouped shape of PlannerConstants (JVMS §4.3.3 255-slot limit), construction,
 * round trip, nested override merging and the leaf-name → rule-id / constants-key conventions.
 * Walks `serializer().descriptor` — no reflection (HR-5).
 */
class PlannerConstantsShapeTest {

    private val root: SerialDescriptor = PlannerConstants.serializer().descriptor

    /** JVM argument slots of one leaf: Double = 2, everything else (Int, Boolean, Band, List) = 1. */
    private fun slotsOf(desc: SerialDescriptor): Int = if (desc.kind == PrimitiveKind.DOUBLE) 2 else 1

    private fun groups(): List<Pair<String, SerialDescriptor>> =
        (0 until root.elementsCount).map { root.getElementName(it) to root.getElementDescriptor(it) }

    @Test
    fun rootIsGroupedIntoSeventeenGroups() {
        assertEquals(17, root.elementsCount)
        assertEquals(
            listOf("def", "vol", "tier", "sess", "wu", "rest", "rir", "prog", "plat", "lay", "rdy", "trv", "sel", "car", "nut", "hab", "age"),
            groups().map { it.first },
        )
    }

    @Test
    fun everyGroupIsAClassWithAtMostThirtyLeaves() {
        for ((name, desc) in groups()) {
            assertEquals(StructureKind.CLASS, desc.kind, name)
            assertTrue(desc.elementsCount in 1..30, "$name has ${desc.elementsCount} leaves")
        }
    }

    @Test
    fun everyGroupDescriptorHasFewerThan200ArgumentSlots() {
        for ((name, desc) in groups()) {
            var slots = 0
            for (i in 0 until desc.elementsCount) slots += slotsOf(desc.getElementDescriptor(i))
            assertTrue(slots < 200, "$name needs $slots JVM argument slots")
        }
    }

    @Test
    fun rootItselfStaysFarBelowTheSlotLimit() {
        // 17 object references; the flat ~220-parameter class of the decision record would not load.
        assertTrue(root.elementsCount < 200)
    }

    @Test
    fun leafTypesAreOnlyPrimitivesBandsAndLists() {
        for ((group, leaf, desc) in PlannerConstants.leafFields()) {
            val ok = desc.kind is PrimitiveKind || desc.kind == StructureKind.LIST
            assertTrue(ok, "$group.$leaf has kind ${desc.kind}")
        }
    }

    @Test
    fun leafFieldsWalksEveryLeafOfEveryGroup() {
        val expected = groups().sumOf { it.second.elementsCount }
        val leaves = PlannerConstants.leafFields()
        assertEquals(expected, leaves.size)
        assertEquals(groups().map { it.first }.toSet(), leaves.map { it.first }.toSet())
    }

    @Test
    fun leafNamesAreUniqueAcrossGroups() {
        val names = PlannerConstants.leafFields().map { it.second }
        assertEquals(names.size, names.toSet().size, "duplicate leaf names: ${names.groupBy { it }.filter { it.value.size > 1 }.keys}")
    }

    @Test
    fun defaultConstructsAndEqualsDEFAULT() {
        assertEquals(PlannerConstants(), PlannerConstants.DEFAULT)
        assertSame(PlannerConstants.DEFAULT, PlannerConstants.DEFAULT)
    }

    @Test
    fun defaultRoundTripsThroughStoredJson() {
        val json = CoreJson.stored
        val first = json.encodeToString(PlannerConstants.serializer(), PlannerConstants.DEFAULT)
        val decoded = json.decodeFromString(PlannerConstants.serializer(), first)
        val second = json.encodeToString(PlannerConstants.serializer(), decoded)
        assertEquals(first, second)
        assertEquals(PlannerConstants.DEFAULT, decoded)
        assertTrue(first.startsWith("{\"def\":{\"def01NoviceMaxMonths\":6"), first.take(80))
    }

    @Test
    fun withOverridesMergesANestedOverrideLeafByLeaf() {
        val merged = PlannerConstants.withOverrides(PlannerConstants.DEFAULT, "{\"vol\":{\"vol04Novice\":[9,13]}}", CoreJson.stored)
        assertEquals(Band(9, 13), merged.vol.vol04Novice)
        assertEquals(PlannerConstants.DEFAULT.vol.vol04Intermediate, merged.vol.vol04Intermediate)
        assertEquals(PlannerConstants.DEFAULT.vol.copy(vol04Novice = Band(9, 13)), merged.vol)
        assertEquals(PlannerConstants.DEFAULT.rest, merged.rest)
        assertNotEquals(PlannerConstants.DEFAULT, merged)
    }

    @Test
    fun withOverridesMergesSeveralGroupsAtOnce() {
        val merged = PlannerConstants.withOverrides(
            PlannerConstants.DEFAULT,
            "{\"rest\":{\"rest02HeavySec\":200},\"nut\":{\"nut01DefaultGPerKg\":2.0,\"nut04ActivityFactors\":[1.3,1.5]},\"age\":{\"age03MaxStrengthAllowed\":true}}",
            CoreJson.stored,
        )
        assertEquals(200, merged.rest.rest02HeavySec)
        assertEquals(2.0, merged.nut.nut01DefaultGPerKg)
        assertEquals(listOf(1.3, 1.5), merged.nut.nut04ActivityFactors)
        assertEquals(true, merged.age.age03MaxStrengthAllowed)
        assertEquals(PlannerConstants.DEFAULT.vol, merged.vol)
    }

    @Test
    fun withOverridesIgnoresUnknownGroupAndUnknownLeaf() {
        val merged = PlannerConstants.withOverrides(
            PlannerConstants.DEFAULT,
            "{\"nope\":{\"x\":1},\"vol\":{\"unknownLeaf\":5,\"vol01Minimum\":5}}",
            CoreJson.stored,
        )
        assertEquals(5, merged.vol.vol01Minimum)
        assertEquals(PlannerConstants.DEFAULT.copy(vol = PlannerConstants.DEFAULT.vol.copy(vol01Minimum = 5)), merged)
    }

    @Test
    fun withOverridesEmptyOrBlankReturnsBase() {
        assertSame(PlannerConstants.DEFAULT, PlannerConstants.withOverrides(PlannerConstants.DEFAULT, "{}", CoreJson.stored))
        assertSame(PlannerConstants.DEFAULT, PlannerConstants.withOverrides(PlannerConstants.DEFAULT, "", CoreJson.stored))
        assertSame(PlannerConstants.DEFAULT, PlannerConstants.withOverrides(PlannerConstants.DEFAULT, "   ", CoreJson.stored))
    }

    @Test
    fun withOverridesInvalidJsonReturnsBase() {
        assertSame(PlannerConstants.DEFAULT, PlannerConstants.withOverrides(PlannerConstants.DEFAULT, "{not json", CoreJson.stored))
        assertSame(PlannerConstants.DEFAULT, PlannerConstants.withOverrides(PlannerConstants.DEFAULT, "[1,2]", CoreJson.stored))
    }

    @Test
    fun withOverridesBadLeafDiscardsTheWholeOverride() {
        // "the app never runs on a half-applied set": the valid vol06PerSessionCap=14 must NOT survive.
        val merged = PlannerConstants.withOverrides(
            PlannerConstants.DEFAULT,
            "{\"vol\":{\"vol01Minimum\":\"five\",\"vol06PerSessionCap\":14}}",
            CoreJson.stored,
        )
        assertEquals(PlannerConstants.DEFAULT, merged)
    }

    @Test
    fun withOverridesOverAnAlreadyOverriddenBase() {
        val base = PlannerConstants.withOverrides(PlannerConstants.DEFAULT, "{\"vol\":{\"vol01Minimum\":5}}", CoreJson.stored)
        val merged = PlannerConstants.withOverrides(base, "{\"vol\":{\"vol06PerSessionCap\":14}}", CoreJson.stored)
        assertEquals(5, merged.vol.vol01Minimum)
        assertEquals(14, merged.vol.vol06PerSessionCap)
    }

    @Test
    fun ruleIdOfDocumentedExamples() {
        assertEquals("VOL-04", PlannerConstants.ruleIdOf("vol04Novice"))
        assertEquals("E1RM-02", PlannerConstants.ruleIdOf("e1rm02MaxReps"))
        assertEquals("TIME-01", PlannerConstants.ruleIdOf("time01MinViableRequiredSets"))
        assertEquals("SEL", PlannerConstants.ruleIdOf("selPatternPref"))
        assertEquals("TIER", PlannerConstants.ruleIdOf("tierTier2Factor"))
        assertEquals("RIR-CAP", PlannerConstants.ruleIdOf("rirCapMax"))
        assertEquals("R4-2.6", PlannerConstants.ruleIdOf("r426KeepAtMaxThreshold"))
        assertEquals("R4-4.5", PlannerConstants.ruleIdOf("r45SoftCapFactor"))
    }

    @Test
    fun rulePrefixOfExamples() {
        assertEquals("vol04", PlannerConstants.rulePrefixOf("vol04Novice"))
        assertEquals("e1rm02", PlannerConstants.rulePrefixOf("e1rm02MaxReps"))
        assertEquals("sel", PlannerConstants.rulePrefixOf("selPatternPref"))
        assertEquals("rir", PlannerConstants.rulePrefixOf("rirCapMax"))
    }

    @Test
    fun constantKeyOfDocumentedExamples() {
        assertEquals("novice", PlannerConstants.constantKeyOf("vol04Novice"))
        assertEquals("tier2Factor", PlannerConstants.constantKeyOf("tierTier2Factor"))
        assertEquals("capMax", PlannerConstants.constantKeyOf("rirCapMax"))
        assertEquals("keepAtMaxThreshold", PlannerConstants.constantKeyOf("r426KeepAtMaxThreshold"))
    }

    @Test
    fun everyLeafMapsToAKnownRuleId() {
        for ((group, leaf, _) in PlannerConstants.leafFields()) {
            val id = PlannerConstants.ruleIdOf(leaf)
            assertTrue(id in RuleIds.ALL, "$group.$leaf → $id is not a RuleIds constant")
        }
    }

    @Test
    fun everyLeafHasANonEmptyConstantsKey() {
        for ((group, leaf, _) in PlannerConstants.leafFields()) {
            val key = PlannerConstants.constantKeyOf(leaf)
            assertTrue(key.isNotEmpty() && key.first().isLowerCase(), "$group.$leaf → '$key'")
        }
    }

    @Test
    fun someDocumentedDefaultsArePinned() {
        val c = PlannerConstants.DEFAULT
        assertEquals(Band(8, 12), c.vol.vol04Novice)
        assertEquals(Band(12, 18), c.vol.vol04Intermediate)
        assertEquals(Band(15, 25), c.vol.vol04Advanced)
        assertEquals(4, c.rir.rirCapMax)
        assertEquals(10, c.prog.e1rm02MaxReps)
        assertEquals(2, c.plat.dld03MinTriggers)
        assertEquals(5, c.plat.dld03MinDays)
        assertEquals(70, c.rdy.rdy02GreenMin)
        assertEquals(45, c.rdy.rdy02AmberMin)
        assertEquals(7700, c.nut.nut04KcalPerKg)
        assertEquals(150, c.nut.nut04ClampKcal)
        assertEquals(listOf(1.0, 1.15, 1.30, 1.45), c.trv.trv04RampFactors)
    }
}
