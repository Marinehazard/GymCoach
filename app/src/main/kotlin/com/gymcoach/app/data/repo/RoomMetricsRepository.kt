package com.gymcoach.app.data.repo

import com.gymcoach.app.data.db.CheckinQuestionEntity
import com.gymcoach.app.data.db.MetricsDao
import com.gymcoach.app.data.db.ReadinessEntity
import com.gymcoach.app.data.db.WeeklyCheckinEntity
import com.gymcoach.core.model.BodyMetric
import com.gymcoach.core.model.CardioLog
import com.gymcoach.core.model.CheckinQuestion
import com.gymcoach.core.model.NutritionLog
import com.gymcoach.core.model.ReadinessCheckin
import com.gymcoach.core.model.WeeklyCheckin
import com.gymcoach.core.repo.MetricsRepository
import com.gymcoach.core.repo.UnreadableDocsRepository
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Body metrics, cardio/nutrition logs, readiness and check-ins. A second body-metric (or readiness)
 * entry for a date is a NEW row (history kept); reads return the newest entry per date (§7.2).
 */
class RoomMetricsRepository(
    private val dao: MetricsDao,
    private val json: Json,
    private val unreadable: UnreadableDocsRepository,
) : MetricsRepository {

    private fun latestPerDate(list: List<BodyMetric>): List<BodyMetric> =
        list.groupBy { it.date }.values.map { sameDay -> sameDay.maxBy { it.loggedAt } }.sortedBy { it.date }

    private fun readiness(list: List<ReadinessEntity>): List<ReadinessCheckin> {
        val decoded = list.mapNotNull { e ->
            val doc = e.toDoc(json)
            doc.asUnreadableDoc()?.let { unreadable.report(it) }
            doc.valueOrNull()
        }
        return decoded.groupBy { it.date }.values.map { sameDay -> sameDay.maxBy { it.loggedAt } }.sortedBy { it.date }
    }

    private fun weekly(list: List<WeeklyCheckinEntity>): List<WeeklyCheckin> = list.mapNotNull { e ->
        val doc = e.toDoc(json)
        doc.asUnreadableDoc()?.let { unreadable.report(it) }
        doc.valueOrNull()
    }

    private fun questions(list: List<CheckinQuestionEntity>): List<CheckinQuestion> = list.mapNotNull { e ->
        val doc = e.toDoc(json)
        doc.asUnreadableDoc()?.let { unreadable.report(it) }
        doc.valueOrNull()
    }

    override fun observeBodyMetrics(days: Int): Flow<List<BodyMetric>> =
        dao.observeBodyMetrics(LocalDate.now().minusDays(days.toLong()).toString())
            .map { list -> latestPerDate(list.map { it.toModel() }) }

    override suspend fun bodyMetricsBetween(from: LocalDate, to: LocalDate): List<BodyMetric> = withContext(Dispatchers.IO) {
        latestPerDate(dao.bodyMetricsBetween(from.toString(), to.toString()).map { it.toModel() })
    }

    override suspend fun addBodyMetric(metric: BodyMetric) = withContext(Dispatchers.IO) { dao.insertBodyMetric(metric.toEntity()) }

    override suspend fun addCardioLog(log: CardioLog) = withContext(Dispatchers.IO) { dao.insertCardio(log.toEntity()) }

    override suspend fun cardioBetween(from: LocalDate, to: LocalDate): List<CardioLog> = withContext(Dispatchers.IO) {
        dao.cardioBetween(from.toString(), to.toString()).map { it.toModel() }
    }

    /** One row per date (unique index): a later log for the same date replaces the earlier one. */
    override suspend fun addNutritionLog(log: NutritionLog) = withContext(Dispatchers.IO) { dao.upsertNutrition(log.toEntity()) }

    override suspend fun nutritionBetween(from: LocalDate, to: LocalDate): List<NutritionLog> = withContext(Dispatchers.IO) {
        dao.nutritionBetween(from.toString(), to.toString()).map { it.toModel() }
    }

    override suspend fun addReadiness(checkin: ReadinessCheckin) = withContext(Dispatchers.IO) { dao.insertReadiness(checkin.toEntity(json)) }

    override suspend fun readinessFor(date: LocalDate): ReadinessCheckin? = withContext(Dispatchers.IO) {
        dao.readinessFor(date.toString())?.let { readiness(listOf(it)).firstOrNull() }
    }

    override suspend fun readinessBetween(from: LocalDate, to: LocalDate): List<ReadinessCheckin> = withContext(Dispatchers.IO) {
        readiness(dao.readinessBetween(from.toString(), to.toString()))
    }

    override fun observeReadiness(days: Int): Flow<List<ReadinessCheckin>> =
        dao.observeReadiness(LocalDate.now().minusDays(days.toLong()).toString()).map { readiness(it) }

    /** Unique per week_start: saving again for the same week replaces the earlier row. */
    override suspend fun saveWeeklyCheckin(checkin: WeeklyCheckin) = withContext(Dispatchers.IO) { dao.upsertWeekly(checkin.toEntity(json)) }

    override suspend fun weeklyCheckinFor(weekStart: LocalDate): WeeklyCheckin? = withContext(Dispatchers.IO) {
        dao.weeklyFor(weekStart.toString())?.let { weekly(listOf(it)).firstOrNull() }
    }

    /** Newest first. */
    override suspend fun latestWeeklyCheckins(count: Int): List<WeeklyCheckin> = withContext(Dispatchers.IO) {
        weekly(dao.latestWeekly(count))
    }

    override fun observeQuestions(): Flow<List<CheckinQuestion>> = dao.observeQuestions().map { questions(it) }

    override suspend fun activeQuestions(on: LocalDate): List<CheckinQuestion> = withContext(Dispatchers.IO) {
        questions(dao.activeQuestions(on.toString()))
    }

    override suspend fun saveQuestion(question: CheckinQuestion) = withContext(Dispatchers.IO) { dao.upsertQuestion(question.toEntity(json)) }
}
