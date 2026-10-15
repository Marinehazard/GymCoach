package com.gymcoach.core.planner

import com.gymcoach.core.model.Band
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.TrainingAge

/**
 * §4.4 AgePolicy (AGE-01/02/03; constants `c.age.*`). For age ≥ age02Threshold (60): per-exercise sets band
 * age02SetsPerExercise, compound rep band age02CompoundReps (isolation unchanged), rest clamped into age02RestSec, no
 * MAX_STRENGTH zone unless ADVANCED with allowAdvancedExercises. From age01Threshold (40) the protein floor is
 * age01ProteinGPerKg (NUT-01 upper half).
 */
class AgePolicy(private val c: PlannerConstants) {
    fun over60(age: Int): Boolean = age >= c.age.age02Threshold

    fun setsBand(age: Int, profileSets: Band): Band = if (over60(age)) c.age.age02SetsPerExercise else profileSets

    fun repBand(age: Int, exercise: Exercise, profileRange: Band): Band =
        if (over60(age) && exercise.flags.compound && !exercise.isCardioOrMobility && exercise.repProfile !in NON_REP_PROFILES) c.age.age02CompoundReps else profileRange

    /** Rest clamped into age02RestSec for age ≥ 60; main-lift floors (REST-04) are applied by the caller afterwards. */
    fun restSec(age: Int, defaultRest: Int): Int =
        if (over60(age)) defaultRest.coerceIn(c.age.age02RestSec.lo, c.age.age02RestSec.hi) else defaultRest

    fun maxStrengthAllowed(age: Int, trainingAge: TrainingAge, allowAdvanced: Boolean): Boolean =
        if (!over60(age)) true else c.age.age03MaxStrengthAllowed || (trainingAge == TrainingAge.ADVANCED && allowAdvanced)

    /** AGE-01: ≥ 40 → 1.9 g/kg; NUT-01's ≥ 65 floor (1.2 g/kg) is a floor below that and stays satisfied; null under 40. */
    fun proteinFloorGPerKg(age: Int): Double? = if (age >= c.age.age01Threshold) c.age.age01ProteinGPerKg else null

    /** AGE-02 (d): a NOVICE ≥ 60 gets one working set per exercise in weeks 1..age02SingleSetStartWeeks. */
    fun singleSetWeek(age: Int, trainingAge: TrainingAge, weekIndex: Int): Boolean =
        over60(age) && trainingAge == TrainingAge.NOVICE && weekIndex <= c.age.age02SingleSetStartWeeks

    companion object {
        /** Profiles whose "reps" are seconds/metres/minutes or power reps: AGE-02 (b) does not rewrite them. */
        val NON_REP_PROFILES: Set<String> = setOf("core_time", "carry", "cardio", "mobility", "power")
    }
}
