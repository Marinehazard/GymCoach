package com.gymcoach.core.planner

import com.gymcoach.core.equipment.Presets
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.LimitationRegion
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.Sex
import com.gymcoach.core.model.WeekMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §9 WP-03 acceptance: seeded java.util.Random(42), 500 profile × gym × goal combinations → the validator passes on every
 * returned plan, or the generator reports why (PlanGenerationException listing the violated invariants). Also checks the
 * two hard real-world constraints on every plan: no exercise whose equipment options are unsatisfied by the gym, and no
 * session over its minute budget.
 */
class PlanInvariantPropertyTest {
    private val kb = PlannerFixtures.kb
    private val c = PlannerConstants.DEFAULT

    data class Case(val index: Int, val profile: Profile, val gym: GymSet)

    private fun cases(): List<Case> {
        val rnd = java.util.Random(SEED)
        val gyms = listOf(PlannerFixtures.base, PlannerFixtures.home, PlannerFixtures.hotel, PlannerFixtures.gym(Presets.TRAVEL_KIT, "gym-kit"))
        return (0 until CASES).map { i ->
            val gym = gyms[rnd.nextInt(gyms.size)]
            val goal = Goal.entries[rnd.nextInt(Goal.entries.size)]
            val months = listOf(2, 12, 30)[rnd.nextInt(3)]
            val birth = listOf(1960, 1975, 1990, 2000)[rnd.nextInt(4)]
            val days = 1 + rnd.nextInt(6)
            val minutes = listOf(30, 45, 60, 75, 90)[rnd.nextInt(5)]
            val lim = when (rnd.nextInt(4)) {
                0 -> listOf(PlannerFixtures.lim(LimitationRegion.KNEE, 5))
                1 -> listOf(PlannerFixtures.lim(LimitationRegion.SHOULDER, 2))
                2 -> listOf(PlannerFixtures.lim(LimitationRegion.LOW_BACK, 4))
                else -> emptyList()
            }
            val profile = PlannerFixtures.p1().copy(
                sex = if (rnd.nextBoolean()) Sex.MALE else Sex.FEMALE, birthYear = birth, trainingMonthsConsistent = months, goal = goal,
                daysPerWeek = days, sessionMinutes = minutes, limitations = lim, allowAdvancedExercises = rnd.nextBoolean(),
                priorityMuscles = if (rnd.nextInt(3) == 0) listOf(Muscle.entries[rnd.nextInt(Muscle.entries.size)]) else emptyList(),
            )
            Case(i, profile, gym)
        }
    }

    @Test
    fun fiveHundredSeededCombinationsValidateOrReportWhy() {
        val planner = Planner(kb, c)
        val resolver = com.gymcoach.core.equipment.EquipmentResolver(kb)
        var produced = 0
        var reported = 0
        for (case in cases()) {
            val inputs = PlannerFixtures.inputs(case.profile, gyms = listOf(case.gym))
            val out = try {
                planner.plan(inputs, PlannerFixtures.MONDAY)
            } catch (e: PlanGenerationException) {
                reported++
                assertTrue(e.violations.isNotEmpty(), "case ${case.index}: a failure must name the violated invariants")
                continue
            }
            produced++
            val violations = planner.validate(out.mesocycle, inputs)
            if (violations.isNotEmpty()) fail("case ${case.index} (${case.gym.id}, ${case.profile.goal}, ${case.profile.daysPerWeek} d × ${case.profile.sessionMinutes} min): " + violations.joinToString { it.message })
            val eq = com.gymcoach.core.model.SessionEquipment(case.gym)
            for (w in out.mesocycle.weeks) for (s in w.sessions) {
                if (w.mode != WeekMode.TRAVEL_MAINTENANCE) assertTrue(s.estimatedMinutes <= case.profile.sessionMinutes + 1e-6, "case ${case.index} ${s.id} ${s.estimatedMinutes} min")
                for (slot in s.slots) assertTrue(resolver.resolve(kb.requireExercise(slot.exerciseId), eq).available, "case ${case.index} ${s.id} ${slot.exerciseId} unavailable")
            }
        }
        assertEquals(CASES, produced + reported)
        assertTrue(produced >= CASES * 9 / 10, "only $produced of $CASES combinations produced a plan")
    }

    companion object {
        const val SEED: Long = 42L
        const val CASES: Int = 500
    }
}
