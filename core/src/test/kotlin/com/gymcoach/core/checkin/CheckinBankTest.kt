package com.gymcoach.core.checkin

import com.gymcoach.core.knowledge.KnowledgeFixture
import com.gymcoach.core.model.BodyMetric
import com.gymcoach.core.model.CheckinAnswer
import com.gymcoach.core.model.CheckinAnswerType
import com.gymcoach.core.model.CheckinQuestion
import com.gymcoach.core.model.EnteredWeight
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SessionStatus
import com.gymcoach.core.model.WeeklyCheckin
import com.gymcoach.core.model.WeightUnit
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CHK-01 selection ≤ 8 bank items with the every-third-week rotation guarantee; Q4/Q5/Q8 auto-answers. */
class CheckinBankTest {
    private val bank = CheckinBank(KnowledgeFixture.kb)
    private val w0 = LocalDate.of(2026, 9, 7)

    /** Simulates `weeks` consecutive check-ins answering whatever was selected. */
    private fun simulate(weeks: Int, inDeficit: Boolean): List<List<String>> {
        val history = ArrayList<WeeklyCheckin>()
        val out = ArrayList<List<String>>()
        for (k in 0 until weeks) {
            val ws = w0.plusWeeks(k.toLong())
            val q = bank.select(ws, history.reversed(), inDeficit, false, emptyList()).map { it.id }
            out += q
            history += WeeklyCheckin("w$k", ws, q.map { CheckinAnswer(it, number = 1.0) })
        }
        return out
    }

    @Test fun fixedItemsAndAtMostEight() {
        val weeks = simulate(9, inDeficit = false)
        for ((k, q) in weeks.withIndex()) {
            assertTrue(q.size <= CheckinBank.MAX_BANK_ITEMS, "week $k: $q")
            for (id in listOf("Q3", "Q4", "Q7", "Q8")) assertTrue(id in q, "week $k lacks $id")
            assertTrue(("Q1" in q) xor ("Q2" in q), "week $k: $q")
            assertFalse("Q15" in q, "Q15 only in a deficit")
        }
        assertTrue(("Q1" in weeks[0]) != ("Q1" in weeks[1]), "Q1/Q2 alternate")
    }

    @Test fun everyRotatingItemAtLeastEveryThirdWeek() {
        for (deficit in listOf(false, true)) {
            val weeks = simulate(12, deficit)
            val rotating = CheckinBank.ROTATION.filter { deficit || it != "Q15" }
            for (id in rotating) {
                for (start in 0..weeks.size - 3) {
                    assertTrue((start until start + 3).any { id in weeks[it] }, "$id missing in weeks $start..${start + 2} (deficit=$deficit)")
                }
            }
        }
    }

    @Test fun q10ForcedBeforeTravelAndCustomQuestionsAppended() {
        val custom = (1..7).map { CheckinQuestion(id = "C-$it", text = "custom $it", answerType = CheckinAnswerType.TEXT, addedBy = OverrideSource.CHAT) }
        val q = bank.select(w0, emptyList(), false, true, custom)
        assertTrue("Q10" in q.map { it.id })
        assertEquals(CheckinBank.MAX_CUSTOM, q.count { it.id.startsWith("C-") })
        assertTrue(q.filter { !it.id.startsWith("C-") }.size <= CheckinBank.MAX_BANK_ITEMS)
        val expired = custom.first().copy(activeUntil = w0.minusDays(1))
        assertFalse("C-1" in bank.select(w0, emptyList(), false, false, listOf(expired)).map { it.id })
    }

    @Test fun autoAnswersQ4Q5Q8() {
        val metrics = (0 until 21).map { d ->
            val date = w0.minusDays(14).plusDays(d.toLong())
            BodyMetric("m$d", date, weight = EnteredWeight(84.0 + 0.05 * d, WeightUnit.KG, 84.0 + 0.05 * d),
                steps = if (!date.isBefore(w0)) 6000 + 100 * (d - 14) else null, loggedAt = date.atTime(7, 0).toInstant(ZoneOffset.UTC))
        }
        val sessions = listOf(
            SessionRecord("a", w0, "g", status = SessionStatus.COMPLETED),
            SessionRecord("b", w0.plusDays(2), "g", status = SessionStatus.COMPLETED),
            SessionRecord("c", w0.plusDays(4), "g", status = SessionStatus.SKIPPED),
        )
        val a = bank.autoAnswers(w0, metrics, sessions, plannedCount = 4)
        assertEquals(6300.0, a["Q5"]?.number)
        assertEquals(50.0, a["Q8"]?.number)
        val q4 = a["Q4"]?.number
        assertTrue(q4 != null && q4 > 0.5 && q4 < 0.8, "Q4 = $q4")
    }
}
