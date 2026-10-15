package com.gymcoach.core.volume

import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.knowledge.RpLandmarks
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MuscleBandRow
import com.gymcoach.core.model.MuscleTier
import com.gymcoach.core.model.SessionPlan
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SessionStatus
import com.gymcoach.core.model.SetLog
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * §4.13 weekly ledger: the week's targets, what the logs achieved, what the remaining planned sessions (today's realized
 * plan in place of its planned session when given) still credit, and the carryover (negative = still missing at the end
 * of the week if everything remaining is done as planned). Carryover never crosses a week boundary.
 */
data class WeeklyLedger(
    val weekStart: LocalDate,
    val targets: Map<Muscle, Double>,
    val achieved: Map<Muscle, VolumeView>,
    val plannedRemaining: Map<Muscle, Double>,
    val carryover: Map<Muscle, Double>,
) {
    fun deficit(m: Muscle): Double {
        val t = targets[m] ?: return 0.0
        val d = t - (achieved[m]?.fractional ?: 0.0) - (plannedRemaining[m] ?: 0.0)
        return if (d > 0.0) VolumeAccountant.round2(d) else 0.0
    }

    fun rows(tiers: Map<Muscle, MuscleTier>, kb: KnowledgeBase): List<MuscleBandRow> {
        val out = ArrayList<MuscleBandRow>()
        for (tier in listOf(MuscleTier.TIER_1, MuscleTier.TIER_2, MuscleTier.TIER_3)) {
            for (m in Muscle.entries) {
                if ((tiers[m] ?: MuscleTier.TIER_3) != tier) continue
                val lm = RpLandmarks.forMuscle(kb, m)
                out.add(
                    MuscleBandRow(
                        muscle = m,
                        tier = tier,
                        target = if (tier == MuscleTier.TIER_3) null else targets[m],
                        achievedFractional = achieved[m]?.fractional ?: 0.0,
                        plannedRemaining = plannedRemaining[m] ?: 0.0,
                        direct = achieved[m]?.direct ?: 0,
                        rpMev = lm?.mev,
                        rpMrv = lm?.mrv,
                    ),
                )
            }
        }
        return out
    }

    companion object {
        fun build(week: MesoWeek, sets: List<SetLog>, sessions: List<SessionRecord>, todayPlan: SessionPlan?, kb: KnowledgeBase): WeeklyLedger {
            val start = week.startDate
            val end = start.plusDays(7)
            fun inWeek(d: LocalDate): Boolean = !d.isBefore(start) && d.isBefore(end)
            val weekSessions = sessions.filter { inWeek(it.date) }
            val sessionIds = weekSessions.map { it.id }.toHashSet()
            val knownIds = sessions.map { it.id }.toHashSet()
            val weekSets = sets.filter { s ->
                if (s.sessionId in sessionIds) true
                else if (s.sessionId in knownIds) false
                else inWeek(s.loggedAt.atZone(ZoneOffset.UTC).toLocalDate())
            }
            val achieved = VolumeAccountant.weekly(weekSets, kb)

            val done = weekSessions.filter { it.status != SessionStatus.PLANNED }.mapNotNull { it.plannedSessionId }.toHashSet()
            val remaining = DoubleArray(Muscle.entries.size)
            for (ps in week.sessions) {
                if (ps.id in done) continue
                val snapshot = if (todayPlan != null && todayPlan.plannedSessionId == ps.id) todayPlan.volume else VolumeAccountant.ofPlanned(ps, kb)
                for ((m, v) in snapshot.fractional) remaining[m.ordinal] += v
            }
            if (todayPlan != null && todayPlan.plannedSessionId == null && weekSessions.none { it.plan?.id == todayPlan.id }) {
                for ((m, v) in todayPlan.volume.fractional) remaining[m.ordinal] += v
            }
            val plannedRemaining = LinkedHashMap<Muscle, Double>()
            for (m in Muscle.entries) if (remaining[m.ordinal] > 0.0) plannedRemaining[m] = VolumeAccountant.round2(remaining[m.ordinal])

            val carry = LinkedHashMap<Muscle, Double>()
            for (m in Muscle.entries) {
                val t = week.targets[m] ?: continue
                val projected = (achieved[m]?.fractional ?: 0.0) + (plannedRemaining[m] ?: 0.0)
                val delta = VolumeAccountant.round2(projected - t)
                if (delta < 0.0) carry[m] = delta
            }
            return WeeklyLedger(start, VolumeAccountant.ordered(week.targets), achieved, plannedRemaining, carry)
        }
    }
}
