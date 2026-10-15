package com.gymcoach.core.knowledge

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Band
import com.gymcoach.core.model.CheckinAnswerType
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.DrillKind
import com.gymcoach.core.model.Joint
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.MedicalFlag
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.Muscle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** mobility_drills, checkin_questions, supplements, cardio_modalities, nutrition_rules, rp_landmarks, load_transfer (SPEC §3.6–3.8). */
class KnowledgeAssetsTest {
    private val kb = KnowledgeFixture.kb

    private fun firstDrills(p: MovementPattern, n: Int): List<String> =
        kb.drills.filter { it.kind == DrillKind.DYNAMIC && p in it.patterns }.take(n).map { it.exerciseId ?: it.id }

    @Test
    fun mob07DrillOrderReproducesTheSpecWarmupLists() {
        assertEquals(listOf("bodyweight_squat", "hip_90_90", "ankle_dorsiflexion_rock", "worlds_greatest_stretch"), firstDrills(MovementPattern.SQUAT, 4))
        assertEquals(listOf("bodyweight_squat", "hip_90_90", "ankle_dorsiflexion_rock", "worlds_greatest_stretch"), firstDrills(MovementPattern.LUNGE_SPLIT, 4))
        assertEquals(listOf("cat_cow", "glute_bridge", "band_lateral_walk", "worlds_greatest_stretch"), firstDrills(MovementPattern.HINGE, 4))
        assertEquals(listOf("band_pull_apart", "shoulder_pass_through", "wall_slide"), firstDrills(MovementPattern.HORIZONTAL_PUSH, 3))
        assertEquals(listOf("band_pull_apart", "shoulder_pass_through", "wall_slide"), firstDrills(MovementPattern.INCLINE_PUSH, 3))
        assertEquals(listOf("shoulder_pass_through", "wall_slide", "thoracic_rotation"), firstDrills(MovementPattern.VERTICAL_PUSH, 3))
        assertEquals(listOf("band_pull_apart", "thoracic_rotation", "dead_hang"), firstDrills(MovementPattern.HORIZONTAL_PULL, 3))
        assertEquals(listOf("band_pull_apart", "thoracic_rotation", "dead_hang"), firstDrills(MovementPattern.VERTICAL_PULL, 3))
    }

    @Test
    fun drillDosesAndJoints() {
        assertEquals(Joint.entries.toSet(), kb.drills.map { it.joint }.toSet())
        assertEquals(DrillKind.entries.toSet(), kb.drills.map { it.kind }.toSet())
        for (d in kb.drills) {
            when (d.kind) {
                DrillKind.DYNAMIC -> assertTrue(d.doseReps == Band(8, 10) || d.id == "dead_hang", d.id)
                DrillKind.STATIC -> assertTrue(d.doseSeconds != null && d.doseSeconds.hi <= 60, d.id)
                DrillKind.STRENGTH_THROUGH_ROM -> assertTrue(d.patterns.isEmpty(), d.id)
            }
            assertTrue(d.limitationRegions.isNotEmpty() && d.sources.isNotEmpty(), d.id)
        }
        val leg = kb.drills.first { it.id == "leg_swings" }
        assertEquals(listOf(MovementPattern.SQUAT, MovementPattern.HINGE, MovementPattern.LUNGE_SPLIT), leg.patterns)
        val ex = setOf("hip_90_90", "couch_stretch", "worlds_greatest_stretch", "ankle_dorsiflexion_rock", "thoracic_rotation", "cat_cow",
            "shoulder_pass_through", "wall_slide", "band_pull_apart", "band_external_rotation", "foam_rolling", "dead_hang", "bodyweight_squat",
            "glute_bridge", "band_lateral_walk")
        assertEquals(ex, kb.drills.mapNotNull { it.exerciseId }.toSet())
    }

    @Test
    fun checkinBankIsChk01() {
        assertEquals((1..15).map { "Q$it" }, kb.checkinQuestions.map { it.id })
        val q4 = kb.checkinQuestions[3]
        assertEquals("weight_trend", q4.autoSource)
        assertEquals(listOf(RuleIds.NUT_16), q4.triggerRuleIds)
        assertTrue(kb.checkinQuestions[14].onlyInDeficit)
        assertEquals(CheckinAnswerType.YESNO, kb.checkinQuestions[12].answerType)
        assertEquals(setOf("Q1", "Q3", "Q4", "Q7", "Q8"), kb.checkinQuestions.filter { it.alwaysAsk }.map { it.id }.toSet())
    }

    @Test
    fun supplementsTiersGatesAndNormalisedEvidence() {
        val s = kb.supplements
        assertEquals(4, s.caveatSup12.size)
        assertEquals("total protein, calories, sleep, training consistency, then creatine and caffeine", s.priorityOrder)
        val byId = s.entries.associateBy { it.id }
        assertEquals((1..10).map { "SUP-%02d".format(it) }.toSet(), byId.keys.filter { !it.startsWith("SUP-11") }.toSet())
        assertEquals(listOf(MedicalFlag.KIDNEY_DISEASE, MedicalFlag.DOCTOR_RESTRICTION), byId.getValue("SUP-01").gatedByMedical)
        assertEquals(listOf(MedicalFlag.UNCONTROLLED_HYPERTENSION, MedicalFlag.HEART_DISEASE, MedicalFlag.PREGNANCY, MedicalFlag.DOCTOR_RESTRICTION),
            byId.getValue("SUP-02").gatedByMedical)
        assertTrue(s.entries.filter { it.tier != "C" }.all { MedicalFlag.DOCTOR_RESTRICTION in it.gatedByMedical })
        assertEquals(Confidence.WEAK, byId.getValue("SUP-06").evidence)
        assertTrue(byId.getValue("SUP-06").notes.contains("R3 tag: strong (effect)/weak (relevance)"))
        assertEquals(Confidence.CONTESTED, byId.getValue("SUP-04").evidence)
        assertEquals("prescription-only in several EU countries at ≥ 2 mg — check local rules; not verified live (R3 §12.13)", byId.getValue("SUP-08").legalNote)
        val tierC = s.entries.filter { it.tier == "C" }
        assertEquals(8, tierC.size)
        assertTrue(tierC.all { it.doNotReason != null && it.id.startsWith("SUP-11") })
    }

    @Test
    fun cardioModalitiesFollowCar08() {
        val m = kb.cardioModalities
        assertEquals("good", m.getValue("stationary_bike").legDayAdjacency)
        assertEquals("high", m.getValue("stationary_bike").hiitSuitability)
        assertEquals("moderate", m.getValue("rowing_erg").legDayAdjacency)
        assertTrue(m.getValue("jump_rope").kneeImpact && m.getValue("stair_climber").kneeImpact)
        assertTrue(!m.getValue("treadmill").isRunning && !m.getValue("treadmill").kneeImpact)
        assertTrue(m.getValue(KnowledgeBase.cardioModalityKey("outdoor_run_walk", true)).isRunning)
        val cardioIds = kb.exercises.values.filter { it.pattern == MovementPattern.CARDIO }.map { it.id }.toSet()
        assertEquals(cardioIds, m.values.map { it.exerciseId }.toSet())
    }

    @Test
    fun nutritionTipsCarryRuleConfidenceAndCitation() {
        val rules = kb.nutritionTips.map { it.ruleId }.toSet()
        assertEquals(setOf(RuleIds.NUT_02, RuleIds.NUT_03, RuleIds.NUT_12, RuleIds.NUT_13, RuleIds.NUT_14), rules)
        assertEquals(7, kb.nutritionTips.count { it.ruleId == RuleIds.NUT_14 })
        assertEquals(Confidence.WEAK, kb.nutritionTips.single { it.ruleId == RuleIds.NUT_13 }.confidence, "moderate/weak → weak")
        assertTrue(kb.nutritionTips.all { it.citation.isNotBlank() && it.body.isNotBlank() })
    }

    @Test
    fun rpLandmarksAreR4Verbatim() {
        val back = kb.rpLandmarks.getValue(Muscle.LATS)
        assertEquals(Band(2, 6), back.mv); assertEquals(Band(6, 8), back.mev); assertEquals(Band(8, 20), back.mav); assertEquals(Band(20, 26), back.mrv)
        assertEquals(Band(2, 4), back.sessionsPerWeek)
        val front = kb.rpLandmarks.getValue(Muscle.FRONT_DELTS)
        assertEquals(Band(0, 2), front.mev); assertEquals(Band(8, 12), front.mrv)
        assertEquals(Band(14, 18), kb.rpLandmarks.getValue(Muscle.QUADS).mrv)
        assertNotNull(kb.rpLandmarks[Muscle.TRAPS_UPPER])
        assertNull(kb.rpLandmarks[Muscle.UPPER_BACK])
        assertEquals(back, RpLandmarks.forMuscle(kb, Muscle.UPPER_BACK))
        assertEquals(kb.rpLandmarks[Muscle.ABS], RpLandmarks.forMuscle(kb, Muscle.OBLIQUES))
        assertNull(RpLandmarks.forMuscle(kb, Muscle.LOWER_BACK))
        assertEquals(20, RpLandmarks.mrvLo(kb, Muscle.UPPER_BACK))
        assertTrue(RpLandmarks.isBackAggregate(Muscle.UPPER_BACK))
    }

    @Test
    fun loadTransferSeedsAreAppendixC() {
        val lt = kb.loadTransfer
        assertEquals(0.5, lt.unilateralFactor)
        assertEquals(0.6, lt.femaleFactor); assertEquals(1.4, lt.intermediateFactor); assertEquals(1.7, lt.advancedFactor)
        fun f(a: String, b: String) = lt.seeds.single { it.fromExerciseId == a && it.toExerciseId == b }.factor
        assertEquals(0.35, f("barbell_bench_press", "dumbbell_bench_press"))
        assertEquals(2.0, f("back_squat", "leg_press_45"))
        assertEquals(1.05, f("conventional_deadlift", "trap_bar_deadlift"))
        assertEquals(0.65, f("hack_squat", "back_squat"))
        assertEquals(1.0, f("reverse_lunge", "bulgarian_split_squat"))
        val squatBb = lt.bodyweightSeeds.single { it.pattern == MovementPattern.SQUAT && it.loadType == LoadType.BARBELL }
        assertEquals(0.50, squatBb.fraction)
        assertTrue(lt.bodyweightSeeds.single { it.pattern == MovementPattern.HINGE && it.loadType == LoadType.DUMBBELL }.perHand)
    }
}
