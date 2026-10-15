// core/src/main/kotlin/com/gymcoach/core/checkin/CheckinBank.kt
package com.gymcoach.core.checkin

import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.BodyMetric
import com.gymcoach.core.model.CheckinAnswer
import com.gymcoach.core.model.CheckinQuestion
import com.gymcoach.core.model.CheckinQuestionDef
import com.gymcoach.core.model.CheckinSchedule
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SessionStatus
import com.gymcoach.core.model.WeeklyCheckin
import com.gymcoach.core.nutrition.WeightTrend
import com.gymcoach.core.planner.PlannerConstants
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * CHK-01 selection (§5.2): the bank part holds at most [MAX_BANK_ITEMS] items — always Q4 and Q8 (auto-computed),
 * Q1 (Q2 on alternate weeks), Q3, Q7, then the rotating items longest-unasked first so that each appears at least every
 * third week (Q15 only in a deficit, Q10 forced in the week before marked travel days). Active custom questions
 * (≤ [MAX_CUSTOM]) are appended after the bank items.
 */
class CheckinBank(private val kb: KnowledgeBase) {

    fun select(weekStart: LocalDate, history: List<WeeklyCheckin>, inDeficit: Boolean, travelDaysNextWeek: Boolean, custom: List<CheckinQuestion>): List<CheckinQuestion> {
        val defs = kb.checkinQuestions.associateBy { it.id }
        val order = kb.checkinQuestions.map { it.id }
        val sleepItem = if (Math.floorMod(weekStart.toEpochDay() / 7, 2L) == 0L) Q1 else Q2
        val fixed = listOf(Q4, Q8, sleepItem, Q3, Q7).filter { it in defs }
        val chosen = ArrayList<String>(fixed)
        val rotating = ROTATION.filter { it in defs && (it != Q15 || inDeficit) }
        if (travelDaysNextWeek && Q10 in defs) chosen += Q10
        val lastAsked: Map<String, LocalDate?> = rotating.associateWith { id ->
            history.filter { w -> w.weekStart.isBefore(weekStart) && w.answers.any { it.questionId == id } }.maxOfOrNull { it.weekStart }
        }
        val ranked = rotating.filter { it !in chosen }.sortedWith(
            compareBy<String>({ lastAsked[it]?.let { d -> -ChronoUnit.DAYS.between(d, weekStart) } ?: Long.MIN_VALUE }, { order.indexOf(it) })
        )
        for (id in ranked) {
            if (chosen.size >= MAX_BANK_ITEMS) break
            chosen += id
        }
        val bank = chosen.sortedBy { order.indexOf(it) }.mapNotNull { defs[it] }.map { toQuestion(it, weekStart) }
        val weekEnd = weekStart.plusDays(6)
        val active = custom.filter { q ->
            !q.answered || q.schedule != CheckinSchedule.ONCE
        }.filter { q ->
            (q.activeFrom == null || !q.activeFrom.isAfter(weekEnd)) && (q.activeUntil == null || !q.activeUntil.isBefore(weekStart)) &&
                (q.schedule != CheckinSchedule.ONCE || q.onDate == null || !q.onDate.isAfter(weekEnd))
        }.take(MAX_CUSTOM)
        return bank + active
    }

    /** Q4 (14-day MA7 change, kg, from WeightTrend), Q5 (mean of BodyMetric.steps over the week), Q8 (% of planned sessions completed). */
    fun autoAnswers(weekStart: LocalDate, metrics: List<BodyMetric>, sessions: List<SessionRecord>, plannedCount: Int): Map<String, CheckinAnswer> {
        val out = LinkedHashMap<String, CheckinAnswer>()
        val weekEnd = weekStart.plusDays(6)
        val upToWeek = metrics.filter { !it.date.isAfter(weekEnd) }
        val trend = WeightTrend(PlannerConstants.DEFAULT)
        trend.delta14Kg(trend.series(upToWeek))?.let { out[Q4] = CheckinAnswer(Q4, number = it) }
        val steps = metrics.filter { !it.date.isBefore(weekStart) && !it.date.isAfter(weekEnd) }
            .groupBy { it.date }.mapNotNull { (_, v) -> (v.maxByOrNull { it.loggedAt } ?: v.last()).steps }
        if (steps.isNotEmpty()) out[Q5] = CheckinAnswer(Q5, number = Math.round(steps.sum().toDouble() / steps.size).toDouble())
        if (plannedCount > 0) {
            val done = sessions.count { it.status == SessionStatus.COMPLETED && !it.date.isBefore(weekStart) && !it.date.isAfter(weekEnd) }
            out[Q8] = CheckinAnswer(Q8, number = Math.round(done * 100.0 / plannedCount).toDouble())
        }
        return out
    }

    private fun toQuestion(d: CheckinQuestionDef, weekStart: LocalDate): CheckinQuestion = CheckinQuestion(
        id = d.id,
        text = d.text,
        answerType = d.answerType,
        schedule = CheckinSchedule.WEEKLY,
        triggerRuleIds = d.triggerRuleIds,
        addedBy = OverrideSource.PLANNER,
        activeFrom = weekStart,
    )

    companion object {
        const val MAX_BANK_ITEMS: Int = 8
        const val MAX_CUSTOM: Int = 5
        const val Q1: String = "Q1"; const val Q2: String = "Q2"; const val Q3: String = "Q3"; const val Q4: String = "Q4"
        const val Q5: String = "Q5"; const val Q6: String = "Q6"; const val Q7: String = "Q7"; const val Q8: String = "Q8"
        const val Q9: String = "Q9"; const val Q10: String = "Q10"; const val Q11: String = "Q11"; const val Q12: String = "Q12"
        const val Q13: String = "Q13"; const val Q14: String = "Q14"; const val Q15: String = "Q15"
        val ROTATION: List<String> = listOf(Q5, Q6, Q9, Q10, Q11, Q12, Q13, Q14, Q15)
    }
}
