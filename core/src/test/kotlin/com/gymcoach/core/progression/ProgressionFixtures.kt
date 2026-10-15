package com.gymcoach.core.progression

import com.gymcoach.core.equipment.Presets
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.knowledge.KnowledgeFixture
import com.gymcoach.core.model.Band
import com.gymcoach.core.model.EnteredLength
import com.gymcoach.core.model.EnteredWeight
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.LengthUnit
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.ProgressionStatus
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SessionStatus
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.model.Sex
import com.gymcoach.core.model.Side
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.model.WeightUnit
import com.gymcoach.core.planner.PlannerConstants
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Shared fixtures for the WP-04 progression tests: profile P1 (§4.17), HOME / hotel gyms, set builders. */
object PF {
    val kb: KnowledgeBase get() = KnowledgeFixture.kb
    val c: PlannerConstants = PlannerConstants.DEFAULT
    val T0: Instant = Instant.parse("2026-09-01T08:00:00Z")
    val MONDAY: LocalDate = LocalDate.of(2026, 9, 7)

    val p1: Profile = Profile(
        sex = Sex.MALE, birthYear = 1988, height = EnteredLength(180.0, LengthUnit.CM, 180.0),
        trainingMonthsConsistent = 18, goal = Goal.HYPERTROPHY, daysPerWeek = 4, sessionMinutes = 60,
        knownLoadsKg = mapOf("barbell_bench_press" to 80.0, "goblet_squat" to 24.0), updatedAt = T0,
    )

    val home: GymSet by lazy { Presets.gymFromPreset(kb, "home_gym", "HOME", "gym_home", T0) }
    val hotel: GymSet by lazy { Presets.gymFromPreset(kb, "hotel_gym", "Hotel", "gym_hotel", T0) }

    fun state(
        exerciseId: String, load: Double?, reps: Band, target: Int, status: ProgressionStatus = ProgressionStatus.PROGRESSING,
        hits: Int = 0, lastIncrement: LocalDate? = null, extended: Boolean = false, repHiBase: Int = reps.hi, gymScope: String? = null,
    ): ProgressionState = ProgressionState(
        key = ProgressionKey(exerciseId, gymScope), status = status, workingLoadKg = load, workingReps = reps, repHiBase = repHiBase,
        repHiExtended = extended, targetRir = target, consecutiveHits = hits, lastIncrementDate = lastIncrement, updatedAt = T0,
    )

    private var seq = 0

    fun set(
        sessionId: String, exerciseId: String, index: Int, kg: Double?, reps: Int, rir: Int?, perHand: Boolean = false,
        kind: SetKind = SetKind.WORKING, side: Side = Side.BOTH, prescribed: Double? = null, blockId: String? = null, date: LocalDate = MONDAY,
    ): SetLog {
        seq += 1
        return SetLog(
            id = "s$seq-$sessionId-$index-${side.name}", sessionId = sessionId, blockId = blockId, exerciseId = exerciseId, setIndex = index,
            kind = kind, weight = kg?.let { EnteredWeight(it, WeightUnit.KG, it) }, perHand = perHand, reps = reps, rir = rir, side = side,
            prescribedLoadKg = prescribed, loggedAt = date.atTime(18, 0).plusMinutes(index.toLong()).toInstant(ZoneOffset.UTC),
        )
    }

    /** One set per entry of `reps` at `kg` / `rir`. */
    fun sets(sessionId: String, exerciseId: String, kg: Double?, reps: List<Int>, rir: Int?, perHand: Boolean = false, prescribed: Double? = null, date: LocalDate = MONDAY): List<SetLog> =
        reps.mapIndexed { i, r -> set(sessionId, exerciseId, i + 1, kg, r, rir, perHand = perHand, prescribed = prescribed, date = date) }

    fun record(id: String, date: LocalDate, gym: GymSet = home): SessionRecord = SessionRecord(
        id = id, date = date, gymId = gym.id, status = SessionStatus.COMPLETED,
        finishedAt = date.atTime(19, 0).toInstant(ZoneOffset.UTC),
    )

    fun input(
        record: SessionRecord, sets: List<SetLog>, states: List<ProgressionState>, gym: GymSet = home,
        trainingAge: TrainingAge = TrainingAge.INTERMEDIATE, band: ReadinessBand? = ReadinessBand.GREEN, mode: WeekMode = WeekMode.NORMAL,
        weekIndex: Int = 3, external: Boolean = false, firstMeso: Boolean = false, profile: Profile = p1,
    ): AfterSessionInput = AfterSessionInput(
        record = record, sets = sets, states = states, profile = profile, trainingAge = trainingAge, readinessBand = band,
        weekMode = mode, weekIndex = weekIndex, gym = gym, today = record.date, externalCause = external, firstMesocycle = firstMeso,
    )
}
