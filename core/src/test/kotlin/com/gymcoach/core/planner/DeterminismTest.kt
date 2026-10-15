package com.gymcoach.core.planner

import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.SessionPlan
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.time.Weeks
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** HR-18: identical inputs → identical inputsHash, identical ids, byte-identical JSON; no IdGen / Random / wall clock in the WP-03 packages. */
class DeterminismTest {
    private val kb = PlannerFixtures.kb
    private val c = PlannerConstants.DEFAULT

    @Test
    fun twoRunsAreByteIdentical() {
        for (name in listOf("e1_home_ul4", "strength_ul4_backoff", "age_62")) {
            val inputs = GoldenCases.build(name)
            val a = Planner(kb, c).plan(inputs, PlannerFixtures.MONDAY)
            val b = Planner(kb, PlannerConstants()).plan(CoreJson.stored.decodeFromString(com.gymcoach.core.model.PlanInputs.serializer(), CoreJson.stored.encodeToString(com.gymcoach.core.model.PlanInputs.serializer(), inputs)), PlannerFixtures.MONDAY)
            assertEquals(a.mesocycle.inputsHash, b.mesocycle.inputsHash)
            assertEquals("meso-" + Planner.inputsHash(inputs).take(16), a.mesocycle.id)
            assertEquals(a.mesocycle.weeks.flatMap { w -> w.sessions.map { it.id } }, b.mesocycle.weeks.flatMap { w -> w.sessions.map { it.id } })
            assertEquals(CoreJson.stored.encodeToString(Mesocycle.serializer(), a.mesocycle), CoreJson.stored.encodeToString(Mesocycle.serializer(), b.mesocycle))
            val w = a.mesocycle.weeks.first()
            val s = w.sessions.first()
            val t = TodayContext(Weeks.dateFor(w.startDate, s.weekday), a.mesocycle.gymId)
            val r1 = Planner(kb, c).realize(s, w, inputs, t, inputs.progressionStates)
            val r2 = Planner(kb, c).realize(s, w, inputs, t, inputs.progressionStates)
            assertEquals(CoreJson.stored.encodeToString(SessionPlan.serializer(), r1), CoreJson.stored.encodeToString(SessionPlan.serializer(), r2))
            assertEquals(s.id + "@" + t.date, r1.id)
            assertTrue(r1.blocks.all { it.blockId == it.slotId + ":" + it.exerciseId })
        }
    }

    @Test
    fun sessionIdsCarryTheWeekdaySegment() {
        val m = Planner(kb, c).plan(GoldenCases.build("advanced_ppl6"), PlannerFixtures.MONDAY).mesocycle
        for (w in m.weeks) for (s in w.sessions) assertEquals("${m.id}:w${w.index}:d${s.weekday}:${s.templateId}", s.id)
    }

    @Test
    fun noIdGenRandomOrWallClockInTheWp03Packages() {
        val root = listOf(File("src/main/kotlin"), File("core/src/main/kotlin")).firstOrNull { it.isDirectory } ?: fail("main sources not found")
        val pkgs = listOf("planner", "planner/realize", "substitution", "volume", "mutation", "mobility")
        val forbidden = listOf("IdGen", "java.util.Random", "kotlin.random", "Instant.now", "LocalDate.now", "System.currentTimeMillis", "System.nanoTime", "Clock.system", "getenv")
        val hits = ArrayList<String>()
        for (p in pkgs) {
            val dir = File(root, "com/gymcoach/core/$p")
            dir.listFiles()?.filter { it.isFile && it.extension == "kt" }?.forEach { f ->
                val text = f.readText(Charsets.UTF_8)
                for (bad in forbidden) if (text.contains(bad)) hits.add("${f.name}: $bad")
            }
        }
        assertTrue(hits.isEmpty(), hits.toString())
    }
}
