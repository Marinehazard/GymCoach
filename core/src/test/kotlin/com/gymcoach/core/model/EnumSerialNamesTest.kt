package com.gymcoach.core.model

import com.gymcoach.core.json.CoreJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlinx.serialization.KSerializer

/**
 * Decision record H2: every enum constant carries an explicit `@SerialName`; the convention in §2.2 is
 * the lower-snake-case of the constant name. kotlinx decodes by the serial name only (no case folding).
 */
class EnumSerialNamesTest {

    private fun <T : Enum<T>> check(serializer: KSerializer<T>, entries: List<T>, expectedCount: Int) {
        assertEquals(expectedCount, entries.size, "constant count of ${serializer.descriptor.serialName}")
        for (e in entries) {
            val encoded = CoreJson.stored.encodeToString(serializer, e)
            assertEquals("\"" + e.name.lowercase() + "\"", encoded, "serial name of ${e.name}")
            assertEquals(e, CoreJson.stored.decodeFromString(serializer, encoded))
            assertEquals(e, CoreJson.assets.decodeFromString(serializer, encoded))
        }
    }

    @Test fun sex() = check(Sex.serializer(), Sex.entries, 2)
    @Test fun goal() = check(Goal.serializer(), Goal.entries, 5)
    @Test fun trainingAge() = check(TrainingAge.serializer(), TrainingAge.entries, 3)
    @Test fun unitSystem() = check(UnitSystem.serializer(), UnitSystem.entries, 2)
    @Test fun weightUnit() = check(WeightUnit.serializer(), WeightUnit.entries, 2)
    @Test fun lengthUnit() = check(LengthUnit.serializer(), LengthUnit.entries, 2)
    @Test fun confidence() = check(Confidence.serializer(), Confidence.entries, 5)
    @Test fun movementPattern() = check(MovementPattern.serializer(), MovementPattern.entries, 29)
    @Test fun muscle() = check(Muscle.serializer(), Muscle.entries, 21)
    @Test fun loadType() = check(LoadType.serializer(), LoadType.entries, 12)
    @Test fun difficulty() = check(Difficulty.serializer(), Difficulty.entries, 3)
    @Test fun equipmentCategory() = check(EquipmentCategory.serializer(), EquipmentCategory.entries, 8)
    @Test fun groupKind() = check(GroupKind.serializer(), GroupKind.entries, 2)
    @Test fun repUnit() = check(RepUnit.serializer(), RepUnit.entries, 4)
    @Test fun incrementKind() = check(IncrementKind.serializer(), IncrementKind.entries, 7)
    @Test fun incrementDirection() = check(IncrementDirection.serializer(), IncrementDirection.entries, 2)
    @Test fun progressionKind() = check(ProgressionKind.serializer(), ProgressionKind.entries, 7)
    @Test fun contentStatus() = check(ContentStatus.serializer(), ContentStatus.entries, 3)
    @Test fun limitationRegion() = check(LimitationRegion.serializer(), LimitationRegion.entries, 8)
    @Test fun side() = check(Side.serializer(), Side.entries, 3)
    @Test fun medicalFlag() = check(MedicalFlag.serializer(), MedicalFlag.entries, 8)
    @Test fun muscleTier() = check(MuscleTier.serializer(), MuscleTier.entries, 3)
    @Test fun splitKind() = check(SplitKind.serializer(), SplitKind.entries, 10)   // + CUSTOM (§3.4.1, Phase 2)
    @Test fun weekMode() = check(WeekMode.serializer(), WeekMode.entries, 4)
    @Test fun slotRole() = check(SlotRole.serializer(), SlotRole.entries, 6)
    @Test fun sessionMode() = check(SessionMode.serializer(), SessionMode.entries, 6)
    @Test fun setKind() = check(SetKind.serializer(), SetKind.entries, 5)
    @Test fun readinessBand() = check(ReadinessBand.serializer(), ReadinessBand.entries, 3)
    @Test fun progressionStatus() = check(ProgressionStatus.serializer(), ProgressionStatus.entries, 7)
    @Test fun adjustmentKind() = check(AdjustmentKind.serializer(), AdjustmentKind.entries, 14)
    @Test fun overrideSource() = check(OverrideSource.serializer(), OverrideSource.entries, 3)
    @Test fun mutationScope() = check(MutationScope.serializer(), MutationScope.entries, 3)
    @Test fun cardioKind() = check(CardioKind.serializer(), CardioKind.entries, 5)
    @Test fun cardioPlacement() = check(CardioPlacement.serializer(), CardioPlacement.entries, 3)
    @Test fun loadZone() = check(LoadZone.serializer(), LoadZone.entries, 5)
    @Test fun exerciseClass() = check(ExerciseClass.serializer(), ExerciseClass.entries, 7)
    @Test fun e1rmConfidence() = check(E1rmConfidence.serializer(), E1rmConfidence.entries, 2)
    @Test fun loadSource() = check(LoadSource.serializer(), LoadSource.entries, 6)
    @Test fun sessionStatus() = check(SessionStatus.serializer(), SessionStatus.entries, 5)
    @Test fun proposalStatus() = check(ProposalStatus.serializer(), ProposalStatus.entries, 7)
    @Test fun backendMode() = check(BackendMode.serializer(), BackendMode.entries, 3)
    @Test fun effort() = check(Effort.serializer(), Effort.entries, 2)
    @Test fun chatRole() = check(ChatRole.serializer(), ChatRole.entries, 2)
    @Test fun conversationStatus() = check(ConversationStatus.serializer(), ConversationStatus.entries, 4)
    @Test fun checkinAnswerType() = check(CheckinAnswerType.serializer(), CheckinAnswerType.entries, 4)
    @Test fun checkinSchedule() = check(CheckinSchedule.serializer(), CheckinSchedule.entries, 3)
    @Test fun drillKind() = check(DrillKind.serializer(), DrillKind.entries, 3)
    @Test fun joint() = check(Joint.serializer(), Joint.entries, 6)

    @Test
    fun upperCaseConstantNamesAreRejectedOnDecode() {
        // H2: no decodeEnumsCaseInsensitive — "MALE" is not a valid wire value.
        assertFails { CoreJson.stored.decodeFromString(Sex.serializer(), "\"MALE\"") }
        assertFails { CoreJson.stored.decodeFromString(Muscle.serializer(), "\"Chest\"") }
    }

    @Test
    fun specificSerialNamesPinned() {
        assertEquals("\"hiit_4x4\"", CoreJson.stored.encodeToString(CardioKind.serializer(), CardioKind.HIIT_4X4))
        assertEquals("\"t_spine\"", CoreJson.stored.encodeToString(Joint.serializer(), Joint.T_SPINE))
        assertEquals("\"ul_ppl_5\"", CoreJson.stored.encodeToString(SplitKind.serializer(), SplitKind.UL_PPL_5))
        assertEquals("\"alternative_z2_mobility\"", CoreJson.stored.encodeToString(SessionMode.serializer(), SessionMode.ALTERNATIVE_Z2_MOBILITY))
        assertEquals("\"e1rm_estimate\"", CoreJson.stored.encodeToString(LoadSource.serializer(), LoadSource.E1RM_ESTIMATE))
        assertEquals("\"in\"", CoreJson.stored.encodeToString(LengthUnit.serializer(), LengthUnit.IN))
    }
}
