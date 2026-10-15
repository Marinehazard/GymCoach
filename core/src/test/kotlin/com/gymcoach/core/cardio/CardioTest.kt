package com.gymcoach.core.cardio

import com.gymcoach.core.equipment.Presets
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeFixture
import com.gymcoach.core.model.CardioKind
import com.gymcoach.core.model.CardioLog
import com.gymcoach.core.model.CardioPlacement
import com.gymcoach.core.model.EnteredLength
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.GymItem
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.LengthUnit
import com.gymcoach.core.model.Limitation
import com.gymcoach.core.model.LimitationRegion
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.Sex
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.planner.PlannerConstants
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** G3 (CAR-02/03/04/05), CAR-07 per goal, CAR-06 placement, CAR-08 modality, CAR-05 gating, CAR-09 progression. */
class CardioTest {
    private val kb = KnowledgeFixture.kb
    private val planner = CardioPlanner(kb, PlannerConstants.DEFAULT)
    private val t0 = Instant.parse("2026-09-01T08:00:00Z")
    private val monday = LocalDate.of(2026, 9, 7)
    private val p1 = Profile(Sex.MALE, 1988, EnteredLength(180.0, LengthUnit.CM, 180.0), 18, goal = Goal.HYPERTROPHY, daysPerWeek = 4, sessionMinutes = 60, updatedAt = t0)
    private val week1 = MesoWeek(1, monday, WeekMode.NORMAL, 1, emptyMap(), emptyList())
    private val home: GymSet = Presets.gymFromPreset(kb, "home_gym", "HOME", "gym_home", t0)
    private val bikeGym: GymSet = home.copy(items = home.items + GymItem("stationary_bike") + GymItem("treadmill"))
    private val lifting = listOf(1, 2, 4, 5)
    private val legs = listOf(2, 5)

    // ---- G3 ------------------------------------------------------------------------------------------------------

    @Test fun g3Zones() {
        val max = HeartRateZones.hrMax(38, null)
        assertEquals(181.4, Math.round(max * 10.0) / 10.0)
        assertEquals(HrZone(109, 127), HeartRateZones.zone2ByMax(max))
        assertEquals(HrZone(107, 131), HeartRateZones.zone2ByReserve(max, 58))
        assertEquals(HrZone(140, 172), HeartRateZones.vigorousByMax(max))
        assertEquals(HrZone(163, 172), HeartRateZones.hiit4x4Work(max))
        assertEquals(190.0, HeartRateZones.hrMax(38, 190))
    }

    @Test fun g3HiitProtocols() {
        val max = HeartRateZones.hrMax(38, null)
        val p = HiitProtocols.protocol(CardioKind.HIIT_4X4, max, 0, false)
        assertEquals(4, p.intervals)
        assertEquals(240, p.workSec)
        assertEquals(180, p.restSec)
        assertEquals(HrZone(163, 172), p.workHr)
        assertEquals(127, p.restHr?.lo)
        assertTrue(RuleIds.CAR_05 in p.explanation.ruleIds)
        assertEquals(5, HiitProtocols.protocol(CardioKind.HIIT_4X4, max, 4, false).intervals)
        assertEquals(6, HiitProtocols.protocol(CardioKind.HIIT_4X4, max, 6, false).intervals)
        assertEquals(12, HiitProtocols.protocol(CardioKind.HIIT_10X1, max, 12, false).intervals)
        assertEquals(CardioKind.HIIT_4X4, HiitProtocols.protocol(CardioKind.SIT, max, 0, advanced = false).kind)
        assertEquals(CardioKind.SIT, HiitProtocols.protocol(CardioKind.SIT, max, 0, advanced = true).kind)
        assertTrue(RuleIds.CAR_09 in p.explanation.ruleIds)
        assertTrue(RuleIds.CAR_02 in HiitProtocols.protocol(CardioKind.ZONE2, max, 0, false).explanation.ruleIds)
    }

    // ---- CAR-07 per goal ---------------------------------------------------------------------------------------

    @Test fun hypertrophyTwoZone2OnOffDaysWithHrrBand() {
        val list = planner.weekly(Goal.HYPERTROPHY, p1, 38, 58, week1, lifting, legs, emptyList(), hiitEligible = true, equipment = SessionEquipment(bikeGym))
        assertEquals(2, list.size)
        assertTrue(list.all { it.kind == CardioKind.ZONE2 && it.minutes == 20 && it.placement == CardioPlacement.OFF_DAY })
        assertTrue(list.all { it.weekday !in lifting })
        assertEquals(107, list[0].hrLow)
        assertEquals(131, list[0].hrHigh)
        assertTrue(list.all { RuleIds.CAR_07 in it.explanation.ruleIds })
    }

    @Test fun generalFitnessAddsHiitOnlyWhenEligibleAndNotBeforeLegDay() {
        val no = planner.weekly(Goal.GENERAL_FITNESS, p1, 38, null, week1, lifting, legs, emptyList(), false, SessionEquipment(bikeGym))
        assertEquals(2, no.size)
        assertTrue(no.all { it.minutes == 30 })
        val yes = planner.weekly(Goal.GENERAL_FITNESS, p1, 38, null, week1, lifting, legs, emptyList(), true, SessionEquipment(bikeGym))
        val hiit = yes.single { it.kind == CardioKind.HIIT_4X4 }
        assertTrue(hiit.weekday !in legs && (hiit.weekday % 7 + 1) !in legs, "HIIT day ${hiit.weekday}")
        assertEquals(4, hiit.intervals)
        assertTrue(RuleIds.CAR_01 in hiit.explanation.ruleIds)
    }

    @Test fun fatLoss150MinutesSpreadOverOffDaysAndFinishers() {
        val list = planner.weekly(Goal.FAT_LOSS, p1, 38, null, week1, lifting, legs, emptyList(), false, SessionEquipment(home))
        assertEquals(150, list.sumOf { it.minutes })
        assertEquals(3, list.count { it.placement == CardioPlacement.OFF_DAY })
        assertTrue(list.filter { it.placement == CardioPlacement.FINISHER }.all { it.weekday !in legs })
    }

    @Test fun enduranceSupportLeavesTheUsersPlan() {
        assertTrue(planner.weekly(Goal.ENDURANCE_SUPPORT, p1, 38, null, week1, lifting, legs, emptyList(), true, SessionEquipment(home)).isEmpty())
    }

    @Test fun car09ProgressionTenPercentPerWeek() {
        val w4 = week1.copy(index = 4)
        val list = planner.weekly(Goal.GENERAL_FITNESS, p1, 38, null, w4, lifting, legs, emptyList(), false, SessionEquipment(home))
        assertTrue(list.all { it.minutes == 40 }, list.map { it.minutes }.toString())   // 30 × 1.1³ = 39.9
        val w9 = week1.copy(index = 9)
        assertTrue(planner.weekly(Goal.GENERAL_FITNESS, p1, 38, null, w9, lifting, legs, emptyList(), false, SessionEquipment(home)).all { it.minutes == 45 })
    }

    // ---- CAR-06 / CAR-08 ---------------------------------------------------------------------------------------

    @Test fun noRunningBeforeLegDayAndNeverWithAKneeLimitation() {
        val oneOffDay = listOf(1, 2, 3, 5, 6, 7)              // off day 4, the day before the Friday leg day
        val gym = home.copy(items = listOf(GymItem("treadmill")))
        val list = planner.weekly(Goal.HYPERTROPHY, p1, 38, null, week1, oneOffDay, listOf(5), emptyList(), true, SessionEquipment(gym))
        val thursday = list.first { it.weekday == 4 }
        assertEquals("treadmill", thursday.exerciseId)      // the walking row of the treadmill (leg-day adjacency good)
        val knee = p1.copy(limitations = listOf(Limitation("k", LimitationRegion.KNEE, pain0to10 = 4, since = monday)))
        val gf = planner.weekly(Goal.GENERAL_FITNESS, knee, 38, null, week1, lifting, legs, emptyList(), true, SessionEquipment(home))
        assertTrue(gf.none { it.exerciseId == "jump_rope" }, gf.toString())
    }

    @Test fun bikeRankedFirstNextToLegDays() {
        val list = planner.weekly(Goal.FAT_LOSS, p1, 38, null, week1, lifting, legs, emptyList(), false, SessionEquipment(bikeGym))
        val nearLegs = list.filter { it.weekday in legs || (it.weekday % 7 + 1) in legs }
        assertTrue(nearLegs.isNotEmpty())
        assertTrue(nearLegs.all { it.exerciseId == "treadmill" || it.exerciseId == "stationary_bike" })
        assertTrue(list.all { RuleIds.CAR_08 in it.explanation.ruleIds && RuleIds.CAR_06 in it.explanation.ruleIds })
    }

    @Test fun overridesAddAndRemoveCardio() {
        val add = PlanOverride("o1", t0, OverrideSource.USER, mutation = PlanMutation.AddCardio(7, 25, "stationary_bike", CardioKind.ZONE2, "sunday ride"))
        val remove = PlanOverride("o2", t0.plusSeconds(1), OverrideSource.USER, mutation = PlanMutation.RemoveCardio(3, "busy"))
        val base = planner.weekly(Goal.HYPERTROPHY, p1, 38, null, week1, lifting, legs, emptyList(), false, SessionEquipment(bikeGym))
        val list = planner.weekly(Goal.HYPERTROPHY, p1, 38, null, week1, lifting, legs, listOf(add, remove), false, SessionEquipment(bikeGym))
        val sunday = list.filter { it.weekday == 7 }.last()
        assertEquals("stationary_bike", sunday.exerciseId)
        assertTrue(list.none { it.weekday == 3 })
        assertTrue(RuleIds.OVERRIDE in sunday.explanation.ruleIds)
        assertTrue(base.size >= 2)
        val revoked = add.copy(revokedAt = t0)
        assertEquals(base, planner.weekly(Goal.HYPERTROPHY, p1, 38, null, week1, lifting, legs, listOf(revoked), false, SessionEquipment(bikeGym)))
    }

    // ---- CAR-05 gating -------------------------------------------------------------------------------------------

    @Test fun hiitNeedsFourBaseWeeksAndNoMedicalGate() {
        val today = LocalDate.of(2026, 10, 7)
        fun log(d: LocalDate, k: CardioKind = CardioKind.ZONE2) = CardioLog("c$d$k", d, exerciseId = "stationary_bike", kind = k, minutes = 30, loggedAt = t0)
        val thisWeek = LocalDate.of(2026, 10, 5)
        val logs = (1..4).flatMap { w -> val s = thisWeek.minusWeeks(w.toLong()); listOf(log(s), log(s.plusDays(3))) }
        assertTrue(planner.hiitEligible(logs, today, medicalGate = false))
        assertFalse(planner.hiitEligible(logs, today, medicalGate = true))
        assertFalse(planner.hiitEligible(logs.drop(1), today, false))
        val hiitOnly = logs.map { it.copy(kind = CardioKind.HIIT_4X4) }
        assertFalse(planner.hiitEligible(hiitOnly, today, false))
    }
}
