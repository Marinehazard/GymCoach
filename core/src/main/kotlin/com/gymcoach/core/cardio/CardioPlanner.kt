// core/src/main/kotlin/com/gymcoach/core/cardio/CardioPlanner.kt
package com.gymcoach.core.cardio

import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.CardioKind
import com.gymcoach.core.model.CardioLog
import com.gymcoach.core.model.CardioModalityEntry
import com.gymcoach.core.model.CardioPlacement
import com.gymcoach.core.model.CardioPrescription
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.LimitationRegion
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.planner.PlannerConstants
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * §5.4 weekly cardio (CAR-01..09): prescription by goal (CAR-07), placement against the lifting week (CAR-06, ORD-03:
 * off days first, otherwise as a finisher after an upper-body session), modality by CAR-08 adjacency and availability
 * (EquipmentResolver; `outdoor_run_walk` needs no gym), zone-2 HR band by HRR when a resting HR is known (CAR-02/03).
 * Active AddCardio / RemoveCardio overrides are applied last, in creation order.
 */
class CardioPlanner(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    private val resolver = EquipmentResolver(kb)

    fun weekly(goal: Goal, profile: Profile, age: Int, restingHr: Int?, week: MesoWeek, liftingWeekdays: List<Int>, heavyLegWeekdays: List<Int>, overrides: List<PlanOverride>, hiitEligible: Boolean, equipment: SessionEquipment): List<CardioPrescription> {
        val hrMax = HeartRateZones.hrMax(age, profile.measuredHrMax)
        val z2 = if (restingHr != null) HeartRateZones.zone2ByReserve(hrMax, restingHr) else HeartRateZones.zone2ByMax(hrMax)
        val knee = profile.limitations.any { it.active && it.region == LimitationRegion.KNEE }
        val lifting = liftingWeekdays.filter { it in 1..7 }.toSet()
        val legs = heavyLegWeekdays.filter { it in 1..7 }.toSet()
        val off = (1..7).filter { it !in lifting }
        val upper = lifting.filter { it !in legs }.sorted()
        val legDays = lifting.filter { it in legs }.sorted()
        val ctx = Ctx(hrMax, z2, restingHr != null, knee, lifting, legs, equipment)

        val out = ArrayList<CardioPrescription>()
        when (goal) {
            Goal.GENERAL_FITNESS -> {
                val minutes = progressive(GF_Z2_MIN, GF_Z2_MAX, week)
                val days = spread(off, 2) + upper + legDays
                for (d in days.distinct().take(2)) out += zone2(ctx, d, minutes, goal, week)
                if (hiitEligible) {
                    val used = out.map { it.weekday }.toSet()
                    val hiitDay = off.firstOrNull { it !in used && hiitOk(it, legs) } ?: off.firstOrNull { hiitOk(it, legs) }
                    if (hiitDay != null) out += hiit(ctx, hiitDay, goal)
                }
            }
            Goal.HYPERTROPHY, Goal.STRENGTH -> {
                val minutes = progressive(HS_Z2_MIN, HS_Z2_MAX, week)
                val days = spread(off, 2) + upper + legDays
                for (d in days.distinct().take(2)) out += zone2(ctx, d, minutes, goal, week)
            }
            Goal.FAT_LOSS -> {
                val n = (FAT_LOSS_WEEK_MIN + FAT_LOSS_SESSION_MIN - 1) / FAT_LOSS_SESSION_MIN
                val days = spread(off, off.size) + upper + legDays
                for (d in days.distinct().take(n)) out += zone2(ctx, d, FAT_LOSS_SESSION_MIN, goal, week)
            }
            Goal.ENDURANCE_SUPPORT -> Unit   // the user's own endurance plan is primary (CAR-07)
        }
        applyOverrides(ctx, out, overrides, week, goal)
        return out.sortedWith(compareBy<CardioPrescription>({ it.weekday }, { it.kind.ordinal }))
    }

    /** CAR-05 gate: ≥ car05HiitBaseWeeks complete weeks before today's week, each with ≥ 2 logged zone-2 sessions, and no RED-03 gate. */
    fun hiitEligible(cardioLogs: List<CardioLog>, today: LocalDate, medicalGate: Boolean): Boolean {
        if (medicalGate) return false
        val thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        for (i in 1..c.car.car05HiitBaseWeeks) {
            val start = thisWeek.minusWeeks(i.toLong())
            val end = start.plusDays(6)
            val n = cardioLogs.count { (it.kind == CardioKind.ZONE2 || it.kind == CardioKind.WALK) && !it.date.isBefore(start) && !it.date.isAfter(end) }
            if (n < Z2_PER_BASE_WEEK) return false
        }
        return true
    }

    // ---------------------------------------------------------------------------------------------------------------

    private class Ctx(val hrMax: Double, val z2: HrZone, val byReserve: Boolean, val knee: Boolean, val lifting: Set<Int>, val legs: Set<Int>, val equipment: SessionEquipment)

    /** CAR-09: +car09WeeklyIncreasePct per week from `lo` up to `hi` (week 1 = lo; deload weeks stay at lo). */
    private fun progressive(lo: Int, hi: Int, week: MesoWeek): Int {
        if (week.mode == WeekMode.DELOAD) return lo
        var m = lo.toDouble()
        for (i in 2..week.index) m *= 1.0 + c.car.car09WeeklyIncreasePct / 100.0
        return minOf(hi, Math.round(m).toInt())
    }

    /** Up to `n` days from `days`, spread so that picked days are as far apart (cyclically) as possible; ties by order. */
    private fun spread(days: List<Int>, n: Int): List<Int> {
        val picked = ArrayList<Int>()
        val left = days.toMutableList()
        while (picked.size < n && left.isNotEmpty()) {
            val next = if (picked.isEmpty()) left.first() else left.maxByOrNull { d -> picked.minOf { p -> cyclic(d, p) } } ?: break
            picked += next
            left.remove(next)
        }
        return picked
    }

    private fun cyclic(a: Int, b: Int): Int { val d = Math.abs(a - b); return minOf(d, 7 - d) }

    private fun nextDay(d: Int): Int = if (d == 7) 1 else d + 1

    /** HIIT not on a heavy-leg day and not within 24 h before one (CAR-06). */
    private fun hiitOk(day: Int, legs: Set<Int>): Boolean = day !in legs && nextDay(day) !in legs

    private fun zone2(ctx: Ctx, day: Int, minutes: Int, goal: Goal, week: MesoWeek): CardioPrescription {
        val placement = if (day in ctx.lifting) CardioPlacement.FINISHER else CardioPlacement.OFF_DAY
        val row = pick(ctx, day, hiit = false)
        val exerciseId = row?.exerciseId ?: FALLBACK
        val hrText = "${ctx.z2.lo}–${ctx.z2.hi} bpm (${if (ctx.byReserve) "40–59 % heart-rate reserve" else "60–70 % HRmax"})"
        val where = if (placement == CardioPlacement.FINISHER) "after lifting" else "on an off day"
        return CardioPrescription(
            exerciseId = exerciseId,
            minutes = minutes,
            kind = CardioKind.ZONE2,
            hrLow = ctx.z2.lo, hrHigh = ctx.z2.hi,
            rpeLow = Z2_RPE_LO, rpeHigh = Z2_RPE_HI,
            placement = placement,
            weekday = day,
            explanation = Explanations.of(
                listOf(RuleIds.CAR_07, RuleIds.CAR_02, RuleIds.CAR_06, RuleIds.CAR_08),
                "$minutes min zone 2 $where: $hrText, or you can still talk in full sentences (RPE 3–4/10)",
                mapOf(
                    "goal" to goal.name.lowercase(), "hr_max" to String.format(java.util.Locale.ROOT, "%.1f", ctx.hrMax),
                    "week_index" to week.index.toString(), "modality" to exerciseId,
                ),
                Confidence.MODERATE,
            ),
        )
    }

    private fun hiit(ctx: Ctx, day: Int, goal: Goal): CardioPrescription {
        val p = HiitProtocols.protocol(CardioKind.HIIT_4X4, ctx.hrMax, 0, false)
        val row = pick(ctx, day, hiit = true)
        val minutes = (p.intervals * (p.workSec + p.restSec) + 59) / 60
        return CardioPrescription(
            exerciseId = row?.exerciseId ?: FALLBACK,
            minutes = minutes,
            kind = p.kind,
            hrLow = p.workHr.lo, hrHigh = p.workHr.hi,
            rpeLow = HIIT_RPE_LO, rpeHigh = HIIT_RPE_HI,
            intervals = p.intervals,
            placement = if (day in ctx.lifting) CardioPlacement.FINISHER else CardioPlacement.OFF_DAY,
            weekday = day,
            explanation = Explanations.of(
                listOf(RuleIds.CAR_07, RuleIds.CAR_05, RuleIds.CAR_06, RuleIds.CAR_01),
                p.explanation.message + "; on an off day at least 24 h before a leg day",
                p.explanation.inputs + mapOf("goal" to goal.name.lowercase()),
                Confidence.MODERATE,
            ),
        )
    }

    /**
     * CAR-08 modality: available rows only; never running / jump rope / knee-impact rows with a knee limitation; no
     * running on or the day before a heavy-leg day, where leg-day adjacency ranks (good > moderate > poor). Zone 2 uses
     * the non-running row of a modality and ranks interval tools controlled by RPE only (jump rope, battle ropes) last;
     * HIIT ranks by suitability first.
     */
    private fun pick(ctx: Ctx, day: Int, hiit: Boolean): CardioModalityEntry? {
        val nearLegs = day in ctx.legs || nextDay(day) in ctx.legs
        val ids = kb.cardioModalities.values.map { it.exerciseId }.distinct()
        val rows = ArrayList<CardioModalityEntry>()
        for (id in ids) {
            val ex = kb.exercise(id) ?: continue
            if (!resolver.resolve(ex, ctx.equipment).available) continue
            kb.cardioModality(id, false)?.let { rows += it }
            if (hiit) kb.cardioModality(id, true)?.takeIf { it.isRunning }?.let { rows += it }
        }
        val allowed = rows.filter { r ->
            !(ctx.knee && (r.isRunning || r.kneeImpact || r.exerciseId == JUMP_ROPE)) &&
                !(nearLegs && r.isRunning) &&
                (hiit || !r.isRunning)
        }
        val order = allowed.withIndex().sortedWith(compareBy<IndexedValue<CardioModalityEntry>>(
            { if (hiit) -suitability(it.value.hiitSuitability) else if (it.value.zone2Control.startsWith(RPE_CONTROLLED)) 1 else 0 },
            { if (nearLegs) -adjacency(it.value.legDayAdjacency) else 0 },
            { it.index },
        ))
        return order.firstOrNull()?.value
    }

    private fun suitability(s: String): Int = when (s) { "high" -> 2; "moderate" -> 1; else -> 0 }
    private fun adjacency(s: String): Int = when (s) { "good" -> 2; "moderate" -> 1; else -> 0 }

    private fun applyOverrides(ctx: Ctx, out: MutableList<CardioPrescription>, overrides: List<PlanOverride>, week: MesoWeek, goal: Goal) {
        val active = overrides.filter { o ->
            o.revokedAt == null && (o.expiresOn == null || !o.expiresOn.isBefore(week.startDate)) &&
                (o.mutation is PlanMutation.AddCardio || o.mutation is PlanMutation.RemoveCardio)
        }.sortedBy { it.createdAt }
        for (o in active) {
            when (val m = o.mutation) {
                is PlanMutation.RemoveCardio -> out.removeAll { it.weekday == m.weekday }
                is PlanMutation.AddCardio -> {
                    if (m.weekday !in 1..7) continue
                    val requested = m.exerciseId?.let { id -> kb.exercise(id)?.takeIf { resolver.resolve(it, ctx.equipment).available }?.id }
                    val base = if (m.kind == CardioKind.ZONE2 || m.kind == CardioKind.WALK) zone2(ctx, m.weekday, m.minutes, goal, week) else hiit(ctx, m.weekday, goal)
                    out += base.copy(
                        exerciseId = requested ?: base.exerciseId,
                        minutes = m.minutes,
                        kind = m.kind,
                        explanation = base.explanation.copy(
                            ruleIds = listOf(RuleIds.OVERRIDE) + base.explanation.ruleIds,
                            message = "Added by you: " + base.explanation.message.substringAfter(": ", base.explanation.message),
                        ),
                    )
                }
                else -> Unit
            }
        }
    }

    private companion object {
        const val GF_Z2_MIN: Int = 30
        const val GF_Z2_MAX: Int = 45
        const val HS_Z2_MIN: Int = 20
        const val HS_Z2_MAX: Int = 30
        const val FAT_LOSS_WEEK_MIN: Int = 150
        const val FAT_LOSS_SESSION_MIN: Int = 30
        const val Z2_PER_BASE_WEEK: Int = 2
        const val Z2_RPE_LO: Int = 11
        const val Z2_RPE_HI: Int = 13
        const val HIIT_RPE_LO: Int = 15
        const val HIIT_RPE_HI: Int = 17
        const val JUMP_ROPE: String = "jump_rope"
        const val RPE_CONTROLLED: String = "RPE"
        const val FALLBACK: String = "outdoor_run_walk"
    }
}
