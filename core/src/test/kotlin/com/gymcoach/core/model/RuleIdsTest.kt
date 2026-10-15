package com.gymcoach.core.model

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §9 WP-01 acceptance: `RuleIds` contains every R3 §10 id. The R3 §10 table (docs/research/
 * R3-training-science.md, 151 rows POL-01 … INJ-04) is transcribed here; each id is checked against the
 * constant by name at compile time (a missing constant fails compilation, a wrong value fails the test).
 */
class RuleIdsTest {

    /** R3 §10 verbatim, in table order. */
    private val r3Section10: List<String> = listOf(
        "POL-01", "POL-02",
        "DEF-01", "DEF-02", "DEF-03", "DEF-04", "DEF-05",
        "VOL-01", "VOL-02", "VOL-03", "VOL-04", "VOL-05", "VOL-06", "VOL-07", "VOL-08", "VOL-09",
        "FRQ-01", "FRQ-02", "FRQ-03",
        "LOAD-01", "LOAD-02", "LOAD-03", "LOAD-04",
        "RIR-01", "RIR-02", "RIR-03", "RIR-04", "RIR-05",
        "REST-01", "REST-02", "REST-03", "REST-04",
        "ORD-01", "ORD-02", "ORD-03",
        "MIX-01", "MIX-02", "MIX-03",
        "UNI-01", "UNI-02",
        "TEMPO-01", "TEMPO-02",
        "ROM-01", "ROM-02", "ROM-03",
        "SESS-01", "SESS-02", "SPLIT-01", "SPLIT-02",
        "PROG-01", "PROG-02", "PROG-03", "PROG-04", "PROG-05",
        "PLAT-01", "PLAT-02", "PLAT-03",
        "DLD-01", "DLD-02", "DLD-03", "DLD-04",
        "E1RM-01", "E1RM-02", "E1RM-03", "E1RM-04",
        "RDY-01", "RDY-02", "RDY-03", "RDY-04", "RDY-05", "RDY-06",
        "CHK-01",
        "RED-01", "RED-02", "RED-03",
        "WU-01", "WU-02", "WU-03", "WU-04", "WU-05", "WU-06",
        "MOB-01", "MOB-02", "MOB-03", "MOB-04", "MOB-05", "MOB-06", "MOB-07",
        "CAR-01", "CAR-02", "CAR-03", "CAR-04", "CAR-05", "CAR-06", "CAR-07", "CAR-08", "CAR-09",
        "NUT-01", "NUT-02", "NUT-03", "NUT-04", "NUT-05", "NUT-06", "NUT-07", "NUT-08", "NUT-09", "NUT-10",
        "NUT-11", "NUT-12", "NUT-13", "NUT-14", "NUT-15", "NUT-16", "NUT-17",
        "SUP-01", "SUP-02", "SUP-03", "SUP-04", "SUP-05", "SUP-06", "SUP-07", "SUP-08", "SUP-09", "SUP-10", "SUP-11", "SUP-12",
        "SLP-01", "SLP-02", "SLP-03",
        "STP-01", "STP-02", "STR-01", "STR-02",
        "AGE-01", "AGE-02", "AGE-03",
        "LAY-01", "LAY-02", "LAY-03",
        "TRV-01", "TRV-02", "TRV-03", "TRV-04",
        "TIME-01", "TIME-02", "TIME-03",
        "INJ-00", "INJ-01", "INJ-02", "INJ-03", "INJ-04",
    )

    /** Every constant of RuleIds referenced by name (compile-time completeness), in the same order plus the extras. */
    private val constants: List<String> = listOf(
        RuleIds.POL_01, RuleIds.POL_02, RuleIds.POL_03,
        RuleIds.DEF_01, RuleIds.DEF_02, RuleIds.DEF_03, RuleIds.DEF_04, RuleIds.DEF_05, RuleIds.DEF_06, RuleIds.DEF_07,
        RuleIds.VOL_01, RuleIds.VOL_02, RuleIds.VOL_03, RuleIds.VOL_04, RuleIds.VOL_05, RuleIds.VOL_06, RuleIds.VOL_07, RuleIds.VOL_08, RuleIds.VOL_09,
        RuleIds.FRQ_01, RuleIds.FRQ_02, RuleIds.FRQ_03,
        RuleIds.LOAD_01, RuleIds.LOAD_02, RuleIds.LOAD_03, RuleIds.LOAD_04,
        RuleIds.RIR_01, RuleIds.RIR_02, RuleIds.RIR_03, RuleIds.RIR_04, RuleIds.RIR_05,
        RuleIds.REST_01, RuleIds.REST_02, RuleIds.REST_03, RuleIds.REST_04,
        RuleIds.ORD_01, RuleIds.ORD_02, RuleIds.ORD_03,
        RuleIds.MIX_01, RuleIds.MIX_02, RuleIds.MIX_03,
        RuleIds.UNI_01, RuleIds.UNI_02,
        RuleIds.TEMPO_01, RuleIds.TEMPO_02,
        RuleIds.ROM_01, RuleIds.ROM_02, RuleIds.ROM_03,
        RuleIds.SESS_01, RuleIds.SESS_02, RuleIds.SPLIT_01, RuleIds.SPLIT_02,
        RuleIds.PROG_01, RuleIds.PROG_02, RuleIds.PROG_03, RuleIds.PROG_04, RuleIds.PROG_05,
        RuleIds.PLAT_01, RuleIds.PLAT_02, RuleIds.PLAT_03,
        RuleIds.DLD_01, RuleIds.DLD_02, RuleIds.DLD_03, RuleIds.DLD_04,
        RuleIds.E1RM_01, RuleIds.E1RM_02, RuleIds.E1RM_03, RuleIds.E1RM_04,
        RuleIds.RDY_01, RuleIds.RDY_02, RuleIds.RDY_03, RuleIds.RDY_04, RuleIds.RDY_05, RuleIds.RDY_06,
        RuleIds.CHK_01,
        RuleIds.RED_01, RuleIds.RED_02, RuleIds.RED_03,
        RuleIds.WU_01, RuleIds.WU_02, RuleIds.WU_03, RuleIds.WU_04, RuleIds.WU_05, RuleIds.WU_06,
        RuleIds.MOB_01, RuleIds.MOB_02, RuleIds.MOB_03, RuleIds.MOB_04, RuleIds.MOB_05, RuleIds.MOB_06, RuleIds.MOB_07,
        RuleIds.CAR_01, RuleIds.CAR_02, RuleIds.CAR_03, RuleIds.CAR_04, RuleIds.CAR_05, RuleIds.CAR_06, RuleIds.CAR_07, RuleIds.CAR_08, RuleIds.CAR_09,
        RuleIds.NUT_01, RuleIds.NUT_02, RuleIds.NUT_03, RuleIds.NUT_04, RuleIds.NUT_05, RuleIds.NUT_06, RuleIds.NUT_07, RuleIds.NUT_08, RuleIds.NUT_09, RuleIds.NUT_10,
        RuleIds.NUT_11, RuleIds.NUT_12, RuleIds.NUT_13, RuleIds.NUT_14, RuleIds.NUT_15, RuleIds.NUT_16, RuleIds.NUT_17, RuleIds.NUT_18,
        RuleIds.SUP_01, RuleIds.SUP_02, RuleIds.SUP_03, RuleIds.SUP_04, RuleIds.SUP_05, RuleIds.SUP_06, RuleIds.SUP_07, RuleIds.SUP_08, RuleIds.SUP_09, RuleIds.SUP_10, RuleIds.SUP_11, RuleIds.SUP_12,
        RuleIds.SLP_01, RuleIds.SLP_02, RuleIds.SLP_03,
        RuleIds.STP_01, RuleIds.STP_02, RuleIds.STR_01, RuleIds.STR_02,
        RuleIds.AGE_01, RuleIds.AGE_02, RuleIds.AGE_03,
        RuleIds.LAY_01, RuleIds.LAY_02, RuleIds.LAY_03,
        RuleIds.TRV_01, RuleIds.TRV_02, RuleIds.TRV_03, RuleIds.TRV_04,
        RuleIds.TIME_01, RuleIds.TIME_02, RuleIds.TIME_03,
        RuleIds.INJ_00, RuleIds.INJ_01, RuleIds.INJ_02, RuleIds.INJ_03, RuleIds.INJ_04,
        RuleIds.SEL, RuleIds.SUB, RuleIds.REBAL, RuleIds.TIER,
        RuleIds.R4_2_6, RuleIds.R4_4_5, RuleIds.R4_5_4, RuleIds.R4_5_5,
        RuleIds.RIR_CAP, RuleIds.ROUNDING, RuleIds.OVERRIDE,
        RuleIds.TIME_L1, RuleIds.TIME_L2, RuleIds.TIME_L3, RuleIds.TIME_L4, RuleIds.TIME_L5, RuleIds.TIME_L6, RuleIds.TIME_L7,
    )

    @Test
    fun r3Section10Has151Ids() {
        assertEquals(151, r3Section10.size)
        assertEquals(r3Section10.size, r3Section10.toSet().size, "R3 §10 ids must be unique")
    }

    @Test
    fun ruleIdsContainsEveryR3Section10Id() {
        val missing = r3Section10.filter { it !in constants }
        assertTrue(missing.isEmpty(), "RuleIds lacks R3 §10 ids: $missing")
    }

    @Test
    fun ruleIdsHasNoDuplicateValues() {
        assertEquals(constants.size, constants.toSet().size, "duplicate values in RuleIds")
    }

    @Test
    fun extrasBeyondR3Section10AreTheDocumentedOnes() {
        val extras = constants.filter { it !in r3Section10 }.toSet()
        assertEquals(
            setOf(
                "POL-03", "DEF-06", "DEF-07", "NUT-18", "SEL", "SUB", "REBAL", "TIER", "R4-2.6", "R4-4.5", "R4-5.4", "R4-5.5",
                "RIR-CAP", "ROUNDING", "OVERRIDE", "TIME-L1", "TIME-L2", "TIME-L3", "TIME-L4", "TIME-L5", "TIME-L6", "TIME-L7",
            ),
            extras,
        )
    }

    @Test
    fun allListsEveryConstantExactlyOnceInDeclarationOrder() {
        assertEquals(constants, RuleIds.ALL)
        assertEquals(RuleIds.ALL.size, RuleIds.ALL.toSet().size)
    }

    @Test
    fun allContainsEveryR3Section10Id() {
        assertTrue(r3Section10.all { it in RuleIds.ALL })
    }

    @Test
    fun everyR3IdMatchesTheRuleIdPattern() {
        val pattern = Regex("^[A-Z0-9]+-[0-9]{2}$")
        for (id in r3Section10) assertTrue(pattern.matches(id), id)
    }

    @Test
    fun constantNamesMirrorTheirValuesInSource() {
        val candidates = listOf(
            File("src/main/kotlin/com/gymcoach/core/explain/RuleIds.kt"),
            File("core/src/main/kotlin/com/gymcoach/core/explain/RuleIds.kt"),
        )
        val source = candidates.firstOrNull { it.isFile } ?: fail("RuleIds.kt not found from ${File(".").absolutePath}")
        val declared = Regex("const val ([A-Z0-9_]+) = \"([^\"]+)\"").findAll(source.readText(Charsets.UTF_8))
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(constants.size, declared.size, "every declared constant must be referenced by this test")
        for ((name, value) in declared) {
            val expected = when {
                name.startsWith("R4_") -> "R4-" + name.removePrefix("R4_").replace('_', '.')
                name.contains('_') -> name.replace('_', '-')
                else -> name
            }
            assertEquals(expected, value, "constant $name")
        }
        assertEquals(constants.toSet(), declared.map { it.second }.toSet())
    }

    @Test
    fun explanationsHelperUsesRuleIds() {
        val e = Explanations.of(RuleIds.VOL_04, "12 sets", "tier" to "tier_1", confidence = Confidence.WEAK)
        assertEquals(listOf("VOL-04"), e.ruleIds)
        assertEquals("12 sets", e.message)
        assertEquals(mapOf("tier" to "tier_1"), e.inputs)
        assertEquals(Confidence.WEAK, e.confidence)

        val multi = Explanations.of(listOf(RuleIds.RDY_02, RuleIds.RIR_04), "Amber: +1 RIR", mapOf("score" to "56"))
        assertEquals(listOf("RDY-02", "RIR-04"), multi.ruleIds)
        assertEquals(mapOf("score" to "56"), multi.inputs)
        assertEquals(null, multi.confidence)
    }

    @Test
    fun explanationsHelperDefaults() {
        val e = Explanations.of(RuleIds.POL_01, "logged numbers are never altered")
        assertEquals(emptyMap(), e.inputs)
        assertEquals(null, e.confidence)
    }
}
