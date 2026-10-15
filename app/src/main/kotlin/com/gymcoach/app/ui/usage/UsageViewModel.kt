package com.gymcoach.app.ui.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gymcoach.core.coach.SpendMeter
import com.gymcoach.core.repo.CoachRepository
import com.gymcoach.core.repo.SettingsRepository
import com.gymcoach.core.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** §8.19 usage. Costs are estimates from list prices; `cacheHitRate` = cache_read / total input (null without data). */
data class UsageUiState(
    val monthSpendUsd: Double,
    val budgetUsd: Double,
    val perDay: List<Pair<LocalDate, Double>>,
    val perConversation: List<Pair<String, Double>>,
    val tokensIn: Long,
    val tokensOut: Long,
    val cacheHitRate: Double?,
    val error: String?,
)

class UsageViewModel(
    private val coach: CoachRepository,
    private val settings: SettingsRepository,
    private val spend: SpendMeter,
    private val clock: Clock,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(UsageUiState(0.0, 0.0, emptyList(), emptyList(), 0L, 0L, null, null))
    val state: StateFlow<UsageUiState> = _state.asStateFlow()

    init {
        scope.launch { load() }
    }

    fun onRefresh() {
        scope.launch { load() }
    }

    private suspend fun load() {
        try {
            val zone = clock.zone()
            val monthStartDate = clock.today().withDayOfMonth(1)
            val from = monthStartDate.atStartOfDay(zone).toInstant()
            val records = coach.spendSince(from)
            val perDay = records.groupBy { it.at.atZone(zone).toLocalDate() }.map { (d, rs) -> d to rs.sumOf { it.costUsd } }.sortedBy { it.first }
            val fmt = DateTimeFormatter.ofPattern("d MMM HH:mm")
            val perConv = ArrayList<Pair<String, Double>>()
            for ((id, rs) in records.groupBy { it.conversationId }) {
                val started = coach.conversation(id)?.startedAt
                val label = (started?.atZone(zone)?.format(fmt) ?: "?") + " · " + id.take(4)
                perConv.add(label to rs.sumOf { it.costUsd })
            }
            _state.update {
                it.copy(
                    monthSpendUsd = spend.monthToDate(), budgetUsd = settings.get().monthlyBudgetUsd,
                    perDay = perDay, perConversation = perConv.sortedByDescending { p -> p.second },
                    tokensIn = records.sumOf { r -> r.usage.totalInput.toLong() }, tokensOut = records.sumOf { r -> r.usage.outputTokens.toLong() },
                    cacheHitRate = spend.cacheHitRate(from), error = null,
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Could not read the usage records.") }
        }
    }
}
