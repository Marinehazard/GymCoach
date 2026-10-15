// core/src/main/kotlin/com/gymcoach/core/progression/PlateauDetector.kt
package com.gymcoach.core.progression

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.E1rmConfidence
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.ProgressionStatus
import com.gymcoach.core.model.SessionSummary
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.planner.PlannerConstants
import java.time.temporal.ChronoUnit

data class PlateauVerdict(val noProgressCount: Int, val status: ProgressionStatus, val flags: List<Explanation>)

/**
 * PLAT-01 / PLAT-03 inside ProgressionEngine.afterSession (§4.9.3). Only fair sessions of a NORMAL week count, and only
 * while the state is PROGRESSING, PLATEAU or REGRESSING; every other call returns the state's own counter/status
 * unchanged. The ladder (PLAT-02) is advanced by Planner.rollWeek (WP-03), never here.
 *
 * `state` is the state BEFORE this session (its history does not yet contain `summary`). Look-backs skip unfair and
 * non-NORMAL history entries, so a deload / re-entry / capped session is never the comparison point.
 */
class PlateauDetector(private val c: PlannerConstants) {

    fun evaluate(state: ProgressionState, summary: SessionSummary, fair: Boolean, weekMode: WeekMode): PlateauVerdict {
        val gated = state.status == ProgressionStatus.PROGRESSING || state.status == ProgressionStatus.PLATEAU ||
            state.status == ProgressionStatus.REGRESSING
        if (!fair || weekMode != WeekMode.NORMAL || !gated) {
            return PlateauVerdict(state.noProgressCount, state.status, emptyList())
        }
        val prior = state.history.filter { it.fair && it.weekMode == WeekMode.NORMAL && it.date.isBefore(summary.date) }
            .sortedByDescending { it.date }
        val best = bestHighE1rm(prior, summary)
        val previous = prior.firstOrNull()

        val e1rmNow = summary.e1rm?.takeIf { it.confidence == E1rmConfidence.HIGH }?.kg
        val improvedByE1rm = e1rmNow != null && e1rmNow > (best ?: 0.0) * c.plat.plat01ImprovementFactor
        val improvedByReps = previous != null && summary.loadKg != null && previous.loadKg != null &&
            ((kotlin.math.abs(summary.loadKg - previous.loadKg) < EPS && summary.bestReps > previous.bestReps) ||
                summary.loadKg > previous.loadKg + EPS)
        val improved = improvedByE1rm || improvedByReps || (previous == null && best == null)

        val count = if (improved) 0 else state.noProgressCount + 1
        val flags = ArrayList<Explanation>()
        var status = state.status

        // PLAT-03: two consecutive fair sessions at <= plat03DropFactor x the rolling best.
        val dropLine = best?.let { it * c.plat.plat03DropFactor }
        val nowDropped = e1rmNow != null && dropLine != null && e1rmNow <= dropLine + EPS
        val prevE1rm = previous?.e1rm?.takeIf { it.confidence == E1rmConfidence.HIGH }?.kg
        val prevDropped = prevE1rm != null && dropLine != null && prevE1rm <= dropLine + EPS

        if (nowDropped && prevDropped) {
            if (status != ProgressionStatus.REGRESSING) {
                flags += Explanations.of(
                    listOf(RuleIds.PLAT_03),
                    "e1RM ${fmt(e1rmNow)} kg is at or below ${pct(c.plat.plat03DropFactor)} of the 14-day best (${fmt(best)} kg) on two consecutive sessions: regressing — check sleep, stress, nutrition and recovery",
                    mapOf(
                        "e1rm_kg" to fmt(e1rmNow), "previous_e1rm_kg" to fmt(prevE1rm),
                        "best_e1rm_14d_kg" to fmt(best), "drop_factor" to c.plat.plat03DropFactor.toString(),
                    ),
                    Confidence.WEAK,
                )
            }
            status = ProgressionStatus.REGRESSING
        } else if (status == ProgressionStatus.REGRESSING) {
            if (improved || (e1rmNow != null && dropLine != null && e1rmNow > dropLine + EPS)) status = ProgressionStatus.PROGRESSING
        } else if (status == ProgressionStatus.PLATEAU) {
            if (improved) status = ProgressionStatus.PROGRESSING
        }

        if (status == ProgressionStatus.PROGRESSING && count >= c.plat.plat01Sessions) {
            status = ProgressionStatus.PLATEAU
            flags += Explanations.of(
                listOf(RuleIds.PLAT_01),
                "No progress (load, reps at load or e1RM) on $count consecutive fair sessions: plateau — the PLAT-02 ladder starts next week",
                mapOf("no_progress_count" to count.toString(), "threshold" to c.plat.plat01Sessions.toString()),
                Confidence.WEAK,
            )
        }
        return PlateauVerdict(count, status, flags)
    }

    /** Best HIGH-confidence e1RM among fair NORMAL-week entries inside the E1RM-04 rolling window before `summary.date`. */
    private fun bestHighE1rm(prior: List<SessionSummary>, summary: SessionSummary): Double? =
        prior.filter {
            it.e1rm?.confidence == E1rmConfidence.HIGH &&
                ChronoUnit.DAYS.between(it.date, summary.date) < c.prog.e1rm04RollingDays.toLong()
        }.mapNotNull { it.e1rm?.kg }.maxOrNull()

    private fun pct(f: Double): String = "${Math.round(f * 100.0)} %"

    private fun fmt(x: Double): String = ProgressionText.kg(x)

    private companion object {
        const val EPS: Double = 1e-6
    }
}
