package com.gymcoach.core.model

import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.planner.AgeConstants
import com.gymcoach.core.planner.CarConstants
import com.gymcoach.core.planner.DefConstants
import com.gymcoach.core.planner.HabitConstants
import com.gymcoach.core.planner.LayConstants
import com.gymcoach.core.planner.NutConstants
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.planner.PlatDldConstants
import com.gymcoach.core.planner.ProgConstants
import com.gymcoach.core.planner.RdyConstants
import com.gymcoach.core.planner.RestConstants
import com.gymcoach.core.planner.RirConstants
import com.gymcoach.core.planner.SelSubConstants
import com.gymcoach.core.planner.SessConstants
import com.gymcoach.core.planner.TierConstants
import com.gymcoach.core.planner.TrvTimeConstants
import com.gymcoach.core.planner.VolConstants
import com.gymcoach.core.planner.WuConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * §9 WP-01 acceptance: every `@Serializable` type of §2 encodes → decodes → encodes byte-identically
 * through `CoreJson.stored` (knowledge types additionally through the strict `CoreJson.assets`).
 */
class ModelRoundTripTest {

    private fun <T> roundTrip(serializer: KSerializer<T>, value: T, json: Json = CoreJson.stored): String {
        val first = json.encodeToString(serializer, value)
        val decoded = json.decodeFromString(serializer, first)
        val second = json.encodeToString(serializer, decoded)
        assertEquals(first, second, "second encoding must be byte-identical")
        assertEquals(value, decoded, "decoded value must equal the original")
        return first
    }

    private fun <T> roundTripAsset(serializer: KSerializer<T>, value: T) {
        roundTrip(serializer, value, CoreJson.stored)
        roundTrip(serializer, value, CoreJson.assets)
    }

    // --- Values ---
    @Test fun band() = run { roundTrip(Band.serializer(), Fixtures.band); Unit }
    @Test fun explanation() = run { roundTrip(Explanation.serializer(), Fixtures.explanation); Unit }
    @Test fun explanationMinimal() = run { roundTrip(Explanation.serializer(), Explanation(listOf("POL-01"), "m")); Unit }
    @Test fun enteredWeight() = run { roundTrip(EnteredWeight.serializer(), Fixtures.enteredWeight); Unit }
    @Test fun enteredLength() = run { roundTrip(EnteredLength.serializer(), Fixtures.enteredLength); Unit }

    // --- Knowledge (strict as well) ---
    @Test fun muscleWeight() = roundTripAsset(MuscleWeight.serializer(), Fixtures.muscleWeight)
    @Test fun equipmentOption() = roundTripAsset(EquipmentOption.serializer(), Fixtures.equipmentOption)
    @Test fun goalPrescription() = roundTripAsset(GoalPrescription.serializer(), Fixtures.goalPrescription)
    @Test fun repProfile() = roundTripAsset(RepProfile.serializer(), Fixtures.repProfile)
    @Test fun increment() = roundTripAsset(Increment.serializer(), Fixtures.increment)
    @Test fun exerciseFlags() = roundTripAsset(ExerciseFlags.serializer(), Fixtures.exerciseFlags)
    @Test fun exerciseContent() = roundTripAsset(ExerciseContent.serializer(), Fixtures.exerciseContent)
    @Test fun exercise() = roundTripAsset(Exercise.serializer(), Fixtures.exercise)
    @Test fun exerciseMinimal() = roundTripAsset(Exercise.serializer(), Fixtures.isolationExercise)
    @Test fun exerciseLibraryFile() = roundTripAsset(ExerciseLibraryFile.serializer(), Fixtures.exerciseLibraryFile)
    @Test fun contentOverlay() = roundTripAsset(ContentOverlay.serializer(), Fixtures.contentOverlay)
    @Test fun paramSpec() = roundTripAsset(ParamSpec.serializer(), Fixtures.paramSpec)
    @Test fun equipmentDef() = roundTripAsset(EquipmentDef.serializer(), Fixtures.equipmentDef)
    @Test fun equipmentGroup() = roundTripAsset(EquipmentGroup.serializer(), Fixtures.equipmentGroup)
    @Test fun presetItem() = roundTripAsset(PresetItem.serializer(), Fixtures.presetItem)
    @Test fun equipmentPreset() = roundTripAsset(EquipmentPreset.serializer(), Fixtures.equipmentPreset)
    @Test fun equipmentCatalogFile() = roundTripAsset(EquipmentCatalogFile.serializer(), Fixtures.equipmentCatalogFile)
    @Test fun ruleEntry() = roundTripAsset(RuleEntry.serializer(), Fixtures.ruleEntry)
    @Test fun rulesFile() = roundTripAsset(RulesFile.serializer(), Fixtures.rulesFile)
    @Test fun drill() = roundTripAsset(Drill.serializer(), Fixtures.drill)
    @Test fun drillsFile() = roundTripAsset(DrillsFile.serializer(), Fixtures.drillsFile)
    @Test fun slotTemplate() = roundTripAsset(SlotTemplate.serializer(), Fixtures.slotTemplate)
    @Test fun sessionTemplate() = roundTripAsset(SessionTemplate.serializer(), Fixtures.sessionTemplate)
    @Test fun splitTemplate() = roundTripAsset(SplitTemplate.serializer(), Fixtures.splitTemplate)
    @Test fun templatesFile() = roundTripAsset(TemplatesFile.serializer(), Fixtures.templatesFile)
    @Test fun checkinQuestionDef() = roundTripAsset(CheckinQuestionDef.serializer(), Fixtures.checkinQuestionDef)
    @Test fun checkinQuestionsFile() = roundTripAsset(CheckinQuestionsFile.serializer(), Fixtures.checkinQuestionsFile)
    @Test fun rpLandmark() = roundTripAsset(RpLandmark.serializer(), Fixtures.rpLandmark)
    @Test fun rpLandmarksFile() = roundTripAsset(RpLandmarksFile.serializer(), Fixtures.rpLandmarksFile)
    @Test fun supplementEntry() = roundTripAsset(SupplementEntry.serializer(), Fixtures.supplementEntry)
    @Test fun supplementsFile() = roundTripAsset(SupplementsFile.serializer(), Fixtures.supplementsFile)
    @Test fun cardioModalityEntry() = roundTripAsset(CardioModalityEntry.serializer(), Fixtures.cardioModalityEntry)
    @Test fun cardioModalitiesFile() = roundTripAsset(CardioModalitiesFile.serializer(), Fixtures.cardioModalitiesFile)
    @Test fun nutritionTip() = roundTripAsset(NutritionTip.serializer(), Fixtures.nutritionTip)
    @Test fun nutritionTipsFile() = roundTripAsset(NutritionTipsFile.serializer(), Fixtures.nutritionTipsFile)
    @Test fun loadTransferSeed() = roundTripAsset(LoadTransferSeed.serializer(), Fixtures.loadTransferSeed)
    @Test fun bodyweightSeed() = roundTripAsset(BodyweightSeed.serializer(), Fixtures.bodyweightSeed)
    @Test fun loadTransferFile() = roundTripAsset(LoadTransferFile.serializer(), Fixtures.loadTransferFile)

    // --- Profile / gyms ---
    @Test fun limitation() = run { roundTrip(Limitation.serializer(), Fixtures.limitation); Unit }
    @Test fun profile() = run { roundTrip(Profile.serializer(), Fixtures.profile); Unit }
    @Test fun bodyMetric() = run { roundTrip(BodyMetric.serializer(), Fixtures.bodyMetric); Unit }
    @Test fun bodyMetricWithoutWeight() = run { roundTrip(BodyMetric.serializer(), BodyMetric(id = "b", date = Fixtures.date, loggedAt = Fixtures.instant)); Unit }
    @Test fun gymItem() = run { roundTrip(GymItem.serializer(), Fixtures.gymItem); Unit }
    @Test fun gymSet() = run { roundTrip(GymSet.serializer(), Fixtures.gymSet); Unit }
    @Test fun todayContext() = run { roundTrip(TodayContext.serializer(), Fixtures.todayContext); Unit }
    @Test fun todayContextMinimal() = run { roundTrip(TodayContext.serializer(), TodayContext(date = Fixtures.date, gymId = "g")); Unit }

    // --- Readiness / check-ins ---
    @Test fun readinessCheckin() = run { roundTrip(ReadinessCheckin.serializer(), Fixtures.readinessCheckin); Unit }
    @Test fun checkinQuestion() = run { roundTrip(CheckinQuestion.serializer(), Fixtures.checkinQuestion); Unit }
    @Test fun checkinAnswer() = run { roundTrip(CheckinAnswer.serializer(), Fixtures.checkinAnswer); Unit }
    @Test fun weeklyCheckin() = run { roundTrip(WeeklyCheckin.serializer(), Fixtures.weeklyCheckin); Unit }

    // --- Plan ---
    @Test fun plannedSlot() = run { roundTrip(PlannedSlot.serializer(), Fixtures.plannedSlot); Unit }
    @Test fun cardioPrescription() = run { roundTrip(CardioPrescription.serializer(), Fixtures.cardioPrescription); Unit }
    @Test fun plannedSession() = run { roundTrip(PlannedSession.serializer(), Fixtures.plannedSession); Unit }
    @Test fun mesoWeek() = run { roundTrip(MesoWeek.serializer(), Fixtures.mesoWeek); Unit }
    @Test fun mesocycle() = run { roundTrip(Mesocycle.serializer(), Fixtures.mesocycle); Unit }
    @Test fun drillPrescription() = run { roundTrip(DrillPrescription.serializer(), Fixtures.drillPrescription); Unit }
    @Test fun warmup() = run { roundTrip(Warmup.serializer(), Fixtures.warmup); Unit }
    @Test fun setPrescription() = run { roundTrip(SetPrescription.serializer(), Fixtures.setPrescription); Unit }
    @Test fun exerciseBlock() = run { roundTrip(ExerciseBlock.serializer(), Fixtures.exerciseBlock); Unit }
    @Test fun volumeSnapshot() = run { roundTrip(VolumeSnapshot.serializer(), Fixtures.volumeSnapshot); Unit }
    @Test fun sessionPlan() = run { roundTrip(SessionPlan.serializer(), Fixtures.sessionPlan); Unit }

    // --- Session / logs ---
    @Test fun setLog() = run { roundTrip(SetLog.serializer(), Fixtures.setLog); Unit }
    @Test fun sessionRecord() = run { roundTrip(SessionRecord.serializer(), Fixtures.sessionRecord); Unit }
    @Test fun sessionRecordMinimal() = run { roundTrip(SessionRecord.serializer(), SessionRecord(id = "s", date = Fixtures.date, gymId = "g", status = SessionStatus.PLANNED)); Unit }
    @Test fun adjustment() = run { roundTrip(Adjustment.serializer(), Fixtures.adjustment); Unit }
    @Test fun cardioLog() = run { roundTrip(CardioLog.serializer(), Fixtures.cardioLog); Unit }
    @Test fun nutritionLog() = run { roundTrip(NutritionLog.serializer(), Fixtures.nutritionLog); Unit }

    // --- Progression ---
    @Test fun progressionKey() = run { roundTrip(ProgressionKey.serializer(), Fixtures.progressionKey); Unit }
    @Test fun progressionKeyUnscoped() = run { roundTrip(ProgressionKey.serializer(), ProgressionKey("barbell_bench_press")); Unit }
    @Test fun e1rmEstimate() = run { roundTrip(E1rmEstimate.serializer(), Fixtures.e1rmEstimate); Unit }
    @Test fun sessionSummary() = run { roundTrip(SessionSummary.serializer(), Fixtures.sessionSummary); Unit }
    @Test fun progressionState() = run { roundTrip(ProgressionState.serializer(), Fixtures.progressionState); Unit }

    // --- Mutations / overrides ---
    @Test fun planMutationEverySubclassViaSealedSerializer() {
        for (m in Fixtures.mutations) roundTrip(PlanMutation.serializer(), m)
    }
    @Test fun swapExercise() = run { roundTrip(PlanMutation.SwapExercise.serializer(), Fixtures.swapExercise); Unit }
    @Test fun changeVolume() = run { roundTrip(PlanMutation.ChangeVolume.serializer(), Fixtures.changeVolume); Unit }
    @Test fun changeRepRange() = run { roundTrip(PlanMutation.ChangeRepRange.serializer(), Fixtures.mutations[2] as PlanMutation.ChangeRepRange); Unit }
    @Test fun addCardio() = run { roundTrip(PlanMutation.AddCardio.serializer(), Fixtures.mutations[3] as PlanMutation.AddCardio); Unit }
    @Test fun removeCardio() = run { roundTrip(PlanMutation.RemoveCardio.serializer(), Fixtures.mutations[4] as PlanMutation.RemoveCardio); Unit }
    @Test fun changeGoal() = run { roundTrip(PlanMutation.ChangeGoal.serializer(), Fixtures.mutations[5] as PlanMutation.ChangeGoal); Unit }
    @Test fun changeSchedule() = run { roundTrip(PlanMutation.ChangeSchedule.serializer(), Fixtures.mutations[6] as PlanMutation.ChangeSchedule); Unit }
    @Test fun setCheckinQuestion() = run { roundTrip(PlanMutation.SetCheckinQuestion.serializer(), Fixtures.mutations[7] as PlanMutation.SetCheckinQuestion); Unit }
    @Test fun excludeExercise() = run { roundTrip(PlanMutation.ExcludeExercise.serializer(), Fixtures.mutations[8] as PlanMutation.ExcludeExercise); Unit }
    @Test fun pinExercise() = run { roundTrip(PlanMutation.PinExercise.serializer(), Fixtures.mutations[9] as PlanMutation.PinExercise); Unit }
    @Test fun adjustWorkingLoad() = run { roundTrip(PlanMutation.AdjustWorkingLoad.serializer(), Fixtures.mutations[10] as PlanMutation.AdjustWorkingLoad); Unit }
    @Test fun setPriorityMuscles() = run { roundTrip(PlanMutation.SetPriorityMuscles.serializer(), Fixtures.mutations[11] as PlanMutation.SetPriorityMuscles); Unit }
    @Test fun scheduleDeload() = run { roundTrip(PlanMutation.ScheduleDeload.serializer(), Fixtures.mutations[12] as PlanMutation.ScheduleDeload); Unit }
    @Test fun setLimitation() = run { roundTrip(PlanMutation.SetLimitation.serializer(), Fixtures.mutations[13] as PlanMutation.SetLimitation); Unit }
    @Test fun setTravelDays() = run { roundTrip(PlanMutation.SetTravelDays.serializer(), Fixtures.mutations[14] as PlanMutation.SetTravelDays); Unit }
    @Test fun updateNutritionTargets() = run { roundTrip(PlanMutation.UpdateNutritionTargets.serializer(), Fixtures.mutations[15] as PlanMutation.UpdateNutritionTargets); Unit }
    @Test fun planOverride() = run { roundTrip(PlanOverride.serializer(), Fixtures.planOverride); Unit }
    @Test fun planOverrideEveryMutation() {
        for (m in Fixtures.mutations) roundTrip(PlanOverride.serializer(), Fixtures.planOverride.copy(mutation = m))
    }

    // --- Planner IO ---
    @Test fun planInputs() = run { roundTrip(PlanInputs.serializer(), Fixtures.planInputs); Unit }
    @Test fun planOutput() = run { roundTrip(PlanOutput.serializer(), Fixtures.planOutput); Unit }
    @Test fun sessionCloseResult() = run { roundTrip(SessionCloseResult.serializer(), Fixtures.sessionCloseResult); Unit }
    @Test fun weekRollResult() = run { roundTrip(WeekRollResult.serializer(), Fixtures.weekRollResult); Unit }
    @Test fun planDiff() = run { roundTrip(PlanDiff.serializer(), Fixtures.planDiff); Unit }

    // --- Nutrition ---
    @Test fun tdeeAdjustment() = run { roundTrip(TdeeAdjustment.serializer(), Fixtures.tdeeAdjustment); Unit }
    @Test fun energyModel() = run { roundTrip(EnergyModel.serializer(), Fixtures.energyModel); Unit }
    @Test fun nutritionTargets() = run { roundTrip(NutritionTargets.serializer(), Fixtures.nutritionTargets); Unit }
    @Test fun weightTrendPoint() = run { roundTrip(WeightTrendPoint.serializer(), Fixtures.weightTrendPoint); Unit }
    @Test fun nutritionState() = run { roundTrip(NutritionState.serializer(), Fixtures.nutritionState); Unit }

    // --- Coach ---
    @Test fun chatConversation() = run { roundTrip(ChatConversation.serializer(), Fixtures.chatConversation); Unit }
    @Test fun chatMessage() = run { roundTrip(ChatMessage.serializer(), Fixtures.chatMessage); Unit }
    @Test fun tokenUsage() = run { roundTrip(TokenUsage.serializer(), Fixtures.tokenUsage); Unit }
    @Test fun tokenUsageDefaults() = run { roundTrip(TokenUsage.serializer(), TokenUsage(), CoreJson.wire); Unit }
    @Test fun proposal() = run { roundTrip(Proposal.serializer(), Fixtures.proposal); Unit }
    @Test fun proposalInvalidWithoutMutation() = run { roundTrip(Proposal.serializer(), Fixtures.proposal.copy(mutation = null, status = ProposalStatus.INVALID, validationErrors = listOf("unknown exercise"))); Unit }
    @Test fun spendRecord() = run { roundTrip(SpendRecord.serializer(), Fixtures.spendRecord); Unit }

    // --- Settings ---
    @Test fun appSettings() = run { roundTrip(AppSettings.serializer(), Fixtures.appSettings); Unit }
    @Test fun appSettingsDefaults() = run { roundTrip(AppSettings.serializer(), AppSettings()); Unit }
    @Test fun appSettingsDefaultsDecodeFromEmptyObject() {
        assertEquals(AppSettings(), CoreJson.stored.decodeFromString(AppSettings.serializer(), "{}"))
    }

    // --- Views (§2.14) ---
    @Test fun chartPoint() = run { roundTrip(ChartPoint.serializer(), Fixtures.chartPoint); Unit }
    @Test fun chartSeries() = run { roundTrip(ChartSeries.serializer(), Fixtures.chartSeries); Unit }
    @Test fun muscleBandRow() = run { roundTrip(MuscleBandRow.serializer(), Fixtures.muscleBandRow); Unit }
    @Test fun muscleBandRowTier3WithoutTarget() = run {
        roundTrip(MuscleBandRow.serializer(), Fixtures.muscleBandRow.copy(muscle = Muscle.FRONT_DELTS, tier = MuscleTier.TIER_3, target = null, rpMev = null, rpMrv = null)); Unit
    }
    @Test fun supplementAdvice() = run { roundTrip(SupplementAdvice.serializer(), Fixtures.supplementAdvice); Unit }
    @Test fun supplementAdviceGatedTierC() = run {
        roundTrip(SupplementAdvice.serializer(), Fixtures.supplementAdvice.copy(id = "SUP-11", tier = "C", gated = true, doseText = "not recommended", doNotReason = "no benefit")); Unit
    }

    // --- PlannerConstants (§4.1): the root and every group ---
    @Test fun plannerConstantsDefault() = run { roundTrip(PlannerConstants.serializer(), PlannerConstants.DEFAULT); Unit }
    @Test fun plannerConstantsNonDefault() = run {
        val c = PlannerConstants.DEFAULT.copy(
            vol = VolConstants(vol04Novice = Band(9, 13), vol08StepTier1 = 1.5),
            wu = WuConstants(wu02HeavyPcts = listOf(0.5, 0.7), wu02HeavyReps = listOf(6, 3)),
            age = AgeConstants(age03MaxStrengthAllowed = true),
        )
        roundTrip(PlannerConstants.serializer(), c); Unit
    }
    @Test fun defConstants() = run { roundTrip(DefConstants.serializer(), DefConstants()); Unit }
    @Test fun volConstants() = run { roundTrip(VolConstants.serializer(), VolConstants()); Unit }
    @Test fun tierConstants() = run { roundTrip(TierConstants.serializer(), TierConstants()); Unit }
    @Test fun sessConstants() = run { roundTrip(SessConstants.serializer(), SessConstants()); Unit }
    @Test fun wuConstants() = run { roundTrip(WuConstants.serializer(), WuConstants()); Unit }
    @Test fun restConstants() = run { roundTrip(RestConstants.serializer(), RestConstants()); Unit }
    @Test fun rirConstants() = run { roundTrip(RirConstants.serializer(), RirConstants()); Unit }
    @Test fun progConstants() = run { roundTrip(ProgConstants.serializer(), ProgConstants()); Unit }
    @Test fun platDldConstants() = run { roundTrip(PlatDldConstants.serializer(), PlatDldConstants()); Unit }
    @Test fun layConstants() = run { roundTrip(LayConstants.serializer(), LayConstants()); Unit }
    @Test fun rdyConstants() = run { roundTrip(RdyConstants.serializer(), RdyConstants()); Unit }
    @Test fun trvTimeConstants() = run { roundTrip(TrvTimeConstants.serializer(), TrvTimeConstants()); Unit }
    @Test fun selSubConstants() = run { roundTrip(SelSubConstants.serializer(), SelSubConstants()); Unit }
    @Test fun carConstants() = run { roundTrip(CarConstants.serializer(), CarConstants()); Unit }
    @Test fun nutConstants() = run { roundTrip(NutConstants.serializer(), NutConstants()); Unit }
    @Test fun habitConstants() = run { roundTrip(HabitConstants.serializer(), HabitConstants()); Unit }
    @Test fun ageConstants() = run { roundTrip(AgeConstants.serializer(), AgeConstants()); Unit }

    // --- Stability across the whole document set ---
    @Test fun planInputsEncodingIsDeterministic() {
        val a = CoreJson.stored.encodeToString(PlanInputs.serializer(), Fixtures.planInputs)
        val b = CoreJson.stored.encodeToString(PlanInputs.serializer(), Fixtures.planInputs.copy())
        assertEquals(a, b)
    }
}
