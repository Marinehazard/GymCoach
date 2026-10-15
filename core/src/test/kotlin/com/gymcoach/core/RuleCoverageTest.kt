package com.gymcoach.core

import com.gymcoach.core.explain.RuleIds
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §10.2 "Coverage": reads core/src/main and core/src/test (text only, no reflection — HR-5).
 *  1. Every rule id referenced in main code (a `RuleIds.X` reference or a rule-id string literal,
 *     comments stripped) appears in at least one test — as a test method name, a `RuleIds.X`
 *     reference or a literal in an assertion/expected value. Data-only test files (the R3 §10
 *     transcript in RuleIdsTest, the Fixtures, this file) do not count as coverage.
 *  2. Every rule-id string literal in main is a known RuleIds value (a typo fails here).
 *  3. Every RuleIds constant is referenced somewhere in main. This assertion is ARMED once every
 *     Phase-1 `:core` package of §1.3 exists on disk; in Phase 0 (WP-01 shell only) it verifies the
 *     weaker invariant that whatever is referenced is a known constant, because the planner /
 *     progression / coach code that consumes the ids has not landed yet.
 */
class RuleCoverageTest {

    private val moduleRoot: File = listOf(File("."), File("core")).firstOrNull { File(it, "src/main/kotlin").isDirectory }
        ?: fail("core module root not found from ${File(".").absolutePath}")
    private val mainRoot = File(moduleRoot, "src/main/kotlin")
    private val testRoot = File(moduleRoot, "src/test/kotlin")

    private val dataOnlyTestFiles = setOf("RuleIdsTest.kt", "FixturesTest.kt", "RuleCoverageTest.kt")
    private val phase1Packages = listOf(
        "com/gymcoach/core/knowledge", "com/gymcoach/core/equipment", "com/gymcoach/core/volume",
        "com/gymcoach/core/planner/Planner.kt", "com/gymcoach/core/planner/realize", "com/gymcoach/core/substitution",
        "com/gymcoach/core/mobility", "com/gymcoach/core/mutation", "com/gymcoach/core/progression/ProgressionEngine.kt",
        "com/gymcoach/core/readiness", "com/gymcoach/core/checkin", "com/gymcoach/core/cardio",
        "com/gymcoach/core/nutrition", "com/gymcoach/core/coach",
    )

    private val constRef = Regex("RuleIds\\.([A-Z][A-Z0-9_]*)")
    private val literalId = Regex("\"((?:[A-Z][A-Z0-9]*-(?:[0-9]{2}|L[1-7]|CAP))|R4-[0-9]\\.[0-9]|SEL|SUB|REBAL|TIER|ROUNDING|OVERRIDE)\"")
    private val testName = Regex("fun\\s+`?([A-Za-z0-9_ ]+)`?\\s*\\(")

    private fun ktFiles(root: File): List<File> = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Removes // line comments and /* */ block comments so that prose mentions do not count as references. */
    private fun stripComments(source: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(source, " ")
        return noBlock.lines().joinToString("\n") { line -> line.substringBefore("//") }
    }

    /** const name -> value, parsed from RuleIds.kt (the only place the mapping is declared). */
    private fun constantMap(): Map<String, String> {
        val source = File(mainRoot, "com/gymcoach/core/explain/RuleIds.kt").readText(Charsets.UTF_8)
        return Regex("const val ([A-Z0-9_]+) = \"([^\"]+)\"").findAll(source).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private data class Reference(val id: String, val where: String)

    /** Rule ids referenced in main code outside RuleIds.kt itself. */
    private fun mainReferences(): List<Reference> {
        val consts = constantMap()
        val out = mutableListOf<Reference>()
        for (file in ktFiles(mainRoot)) {
            if (file.name == "RuleIds.kt") continue
            val code = stripComments(file.readText(Charsets.UTF_8))
            for (m in constRef.findAll(code)) {
                val value = consts[m.groupValues[1]]
                out += Reference(value ?: "?${m.groupValues[1]}", file.path)
            }
            for (m in literalId.findAll(code)) out += Reference(m.groupValues[1], file.path)
        }
        return out
    }

    /** Rule ids covered by tests: literals, RuleIds references and normalised test method names. */
    private fun testCoverage(): Set<String> {
        val consts = constantMap()
        val byValue = consts.entries.associate { it.value to it.key }
        val covered = mutableSetOf<String>()
        for (file in ktFiles(testRoot)) {
            if (file.name in dataOnlyTestFiles) continue
            val source = file.readText(Charsets.UTF_8)
            for (m in literalId.findAll(source)) covered += m.groupValues[1]
            for (m in constRef.findAll(source)) consts[m.groupValues[1]]?.let { covered += it }
            val names = testName.findAll(source).map { it.groupValues[1].lowercase().replace(" ", "").replace("_", "") }.toList()
            for ((value, _) in byValue) {
                if (!value.contains('-')) continue   // bare convention ids (SEL, TIER …) count only as literals / references
                val needle = value.lowercase().replace("-", "").replace(".", "")
                if (names.any { it.contains(needle) }) covered += value
            }
        }
        return covered
    }

    private fun phase1HasLanded(): Boolean = phase1Packages.all { File(mainRoot, it).exists() }

    @Test
    fun sourceTreesExist() {
        assertTrue(mainRoot.isDirectory, mainRoot.path)
        assertTrue(testRoot.isDirectory, testRoot.path)
        assertTrue(constantMap().size >= 160, "RuleIds constants parsed: ${constantMap().size}")
    }

    @Test
    fun everyRuleIdLiteralInMainIsAKnownRuleIdsValue() {
        val known = RuleIds.ALL.toSet()
        val unknown = mainReferences().filter { it.id !in known }
        assertTrue(unknown.isEmpty(), "unknown rule ids in main: $unknown")
    }

    @Test
    fun everyRuleIdReferencedInMainIsCoveredByATest() {
        val covered = testCoverage()
        val missing = mainReferences().filter { it.id !in covered }.distinctBy { it.id }
        assertTrue(missing.isEmpty(), "rule ids used in main without a test name / assertion mentioning them: $missing")
    }

    @Test
    fun everyRuleIdsConstantIsReferencedInMainOncePhase1HasLanded() {
        val referenced = mainReferences().map { it.id }.toSet()
        if (!phase1HasLanded()) {
            // Phase 0: the consumers of the ids do not exist yet; the weaker invariant must hold.
            assertTrue(referenced.all { it in RuleIds.ALL }, "unknown ids: ${referenced - RuleIds.ALL.toSet()}")
            return
        }
        val unreferenced = RuleIds.ALL.filter { it !in referenced }
        assertTrue(unreferenced.isEmpty(), "RuleIds constants never referenced in main: $unreferenced")
    }

    @Test
    fun ruleIdsAllMatchesTheDeclaredConstants() {
        val declared = constantMap().values.toList()
        assertTrue(declared.toSet() == RuleIds.ALL.toSet(), "ALL differs from the declared constants: ${(declared - RuleIds.ALL) + (RuleIds.ALL - declared)}")
    }
}
