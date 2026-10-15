package com.gymcoach.core.knowledge

import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.ContentStatus
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.repo.KnowledgeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shared, lazily loaded knowledge base for the knowledge and equipment tests (classpath = the shipped assets). */
object KnowledgeFixture {
    val kb: KnowledgeBase by lazy { KnowledgeLoader(CoreJson.assets).load(ClasspathKnowledgeSource()) }

    /** core/src/test/resources/knowledge/ids_v1.txt — the frozen 156 ids (rule 9). */
    val frozenIdsV1: List<String> by lazy {
        val stream = checkNotNull(KnowledgeFixture::class.java.getResourceAsStream("/knowledge/ids_v1.txt")) { "ids_v1.txt missing" }
        stream.bufferedReader(Charsets.UTF_8).use { r -> r.readLines().map { it.trim() }.filter { it.isNotEmpty() } }
    }
}

/** A source that hides or replaces files of the classpath source. */
class FilteringSource(
    private val delegate: KnowledgeSource = ClasspathKnowledgeSource(),
    private val hidden: Set<String> = emptySet(),
    private val replaced: Map<String, String> = emptyMap(),
) : KnowledgeSource {
    override fun exists(relativePath: String): Boolean = relativePath in replaced || (relativePath !in hidden && delegate.exists(relativePath))
    override fun readText(relativePath: String): String = replaced[relativePath] ?: delegate.readText(relativePath)
}

class KnowledgeLoaderTest {
    private val kb = KnowledgeFixture.kb

    @Test
    fun loadsEveryFileWithTheR4Counts() {
        assertEquals(156, kb.exercises.size)
        assertEquals(156, kb.exerciseOrder.size)
        assertEquals(11, kb.repProfiles.size)
        assertEquals(78, kb.equipment.size, "every row of the R4 §2.3 table")
        assertEquals(19, kb.groups.size)
        assertEquals(listOf("base_gym", "home_gym", "hotel_gym", "travel_kit"), kb.presets.keys.toList())
        assertEquals(15, kb.checkinQuestions.size)
        assertEquals(14, kb.rpLandmarks.size)
        assertTrue(kb.splits.size == 13)
        assertTrue(kb.drills.isNotEmpty())
        assertTrue(kb.nutritionTips.isNotEmpty())
    }

    @Test
    fun exerciseOrderIsBatchThenId() {
        val expected = kb.exercises.values.sortedWith(compareBy({ it.batch }, { it.id })).map { it.id }
        assertEquals(expected, kb.exerciseOrder)
        assertEquals(expected, kb.exercises.keys.toList())
    }

    @Test
    fun everyRequiredFileMissingFailsWithItsPath() {
        for (file in KnowledgeLoader.REQUIRED_FILES) {
            val e = assertFailsWith<IllegalStateException> { KnowledgeLoader(CoreJson.assets).load(FilteringSource(hidden = setOf(file))) }
            assertTrue(e.message!!.contains(file), "message names $file: ${e.message}")
        }
    }

    @Test
    fun missingBatchFileIsToleratedAndLeavesThatBatchTodo() {
        val loaded = KnowledgeLoader(CoreJson.assets).load(FilteringSource(hidden = setOf(KnowledgeLoader.batchPath(3))))
        val batch3 = loaded.exercises.values.filter { it.batch == 3 }
        assertEquals(25, batch3.size)
        assertTrue(batch3.all { it.contentStatus == ContentStatus.TODO && it.content == null })
        assertTrue(loaded.exercises.values.filter { it.batch != 3 }.all { it.content != null })
    }

    @Test
    fun noBatchFilesAtAllStillLoads() {
        val loaded = KnowledgeLoader(CoreJson.assets).load(FilteringSource(hidden = (1..6).map { KnowledgeLoader.batchPath(it) }.toSet()))
        assertTrue(loaded.exercises.values.all { it.contentStatus == ContentStatus.TODO })
    }

    @Test
    fun contentOverlaysAreMergedAsDraft() {
        val bench = kb.requireExercise("barbell_bench_press")
        assertNotNull(bench.content)
        assertEquals(ContentStatus.DRAFT, bench.contentStatus)
        assertTrue(kb.exercises.values.all { it.contentStatus == ContentStatus.DRAFT }, "all six batch files exist on disk")
    }

    @Test
    fun malformedFileFailsWithItsPath() {
        val e = assertFailsWith<IllegalStateException> {
            KnowledgeLoader(CoreJson.assets).load(FilteringSource(replaced = mapOf("rp_landmarks.json" to "{\"schema_version\":1,\"bogus\":true}")))
        }
        assertTrue(e.message!!.contains("rp_landmarks.json"))
    }

    @Test
    fun renamedIdsResolveThroughExerciseLookup() {
        val text = ClasspathKnowledgeSource().readText("exercises.json").replaceFirst("\"renamed_ids\": {}", "\"renamed_ids\": {\"bench_press_old\": \"barbell_bench_press\"}")
        val loaded = KnowledgeLoader(CoreJson.assets).load(FilteringSource(replaced = mapOf("exercises.json" to text)))
        assertEquals("barbell_bench_press", loaded.exercise("bench_press_old")?.id)
        assertNull(loaded.exercise("no_such_exercise"))
        assertFailsWith<IllegalStateException> { loaded.requireExercise("no_such_exercise") }
    }

    @Test
    fun searchFindsGermanAndEnglishAliases() {
        assertTrue(kb.searchByNameOrAlias("kniebeuge").any { it.id == "back_squat" })
        assertTrue(kb.searchByNameOrAlias("BANKDRÜCKEN").any { it.id == "barbell_bench_press" })
        assertTrue(kb.searchByNameOrAlias("skull crusher").any { it.id == "lying_triceps_extension" })
        assertTrue(kb.searchEquipment("Kurzhanteln").any { it.id == "dumbbells" })
        assertTrue(kb.searchEquipment("multipresse").any { it.id == "smith_machine" })
        assertEquals(kb.exerciseOrder, kb.searchByNameOrAlias("  ").map { it.id })
    }

    @Test
    fun prescriptionUsesOverridesThenProfile() {
        val sissy = kb.requireExercise("sissy_squat")
        assertEquals(8, kb.prescription(sissy, Goal.HYPERTROPHY).range.lo)
        assertEquals(15, kb.prescription(sissy, Goal.HYPERTROPHY).range.hi)
        val bench = kb.requireExercise("barbell_bench_press")
        assertEquals(3, kb.prescription(bench, Goal.STRENGTH).range.lo)
        assertEquals(6, kb.prescription(bench, Goal.STRENGTH).range.hi)
        // FAT_LOSS uses the hypertrophy key (DEF-06), ENDURANCE_SUPPORT the endurance key.
        assertEquals(kb.prescription(bench, Goal.HYPERTROPHY), kb.prescription(bench, Goal.FAT_LOSS))
        assertEquals(10, kb.prescription(bench, Goal.ENDURANCE_SUPPORT).range.lo)
    }

    @Test
    fun byPatternKeepsKnowledgeOrder() {
        val squats = kb.byPattern(com.gymcoach.core.model.MovementPattern.SQUAT).map { it.id }
        assertEquals("back_squat", squats.first())
        assertEquals(squats, kb.exerciseOrder.filter { it in squats })
    }

    @Test
    fun cardioModalityRunningVariant() {
        val walk = assertNotNull(kb.cardioModality("treadmill", running = false))
        val run = assertNotNull(kb.cardioModality("treadmill", running = true))
        assertTrue(!walk.isRunning && run.isRunning && run.kneeImpact)
        assertEquals("poor", run.legDayAdjacency)
        assertEquals(kb.cardioModality("stationary_bike", false), kb.cardioModality("stationary_bike", true))
        assertNotNull(kb.rpLandmarks[Muscle.LATS])
    }
}
