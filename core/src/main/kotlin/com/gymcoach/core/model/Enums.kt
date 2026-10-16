// core/src/main/kotlin/com/gymcoach/core/model/Enums.kt
package com.gymcoach.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Sex { @SerialName("male") MALE, @SerialName("female") FEMALE }

/**
 * R3 DEF-06 goals. FAT_LOSS uses the hypertrophy rep profile (DEF-06: "hypertrophy-style training in
 * deficit") with VOL-09 volume; ENDURANCE_SUPPORT uses the endurance profile. Decision record H7.
 */
@Serializable
enum class Goal {
    @SerialName("hypertrophy") HYPERTROPHY,
    @SerialName("strength") STRENGTH,
    @SerialName("general_fitness") GENERAL_FITNESS,
    @SerialName("fat_loss") FAT_LOSS,
    @SerialName("endurance_support") ENDURANCE_SUPPORT;

    /** Key into RepProfile.byGoal (R4 §5.2 has four goal keys). */
    val repProfileKey: String
        get() = when (this) {
            HYPERTROPHY -> "hypertrophy"
            STRENGTH -> "strength"
            GENERAL_FITNESS -> "general_fitness"
            FAT_LOSS -> "hypertrophy"
            ENDURANCE_SUPPORT -> "endurance"
        }
}

/** R3 DEF-01. Derived by Normalizer, never entered directly. */
@Serializable
enum class TrainingAge { @SerialName("novice") NOVICE, @SerialName("intermediate") INTERMEDIATE, @SerialName("advanced") ADVANCED }

@Serializable enum class UnitSystem { @SerialName("metric") METRIC, @SerialName("imperial") IMPERIAL }
@Serializable enum class WeightUnit { @SerialName("kg") KG, @SerialName("lb") LB }
@Serializable enum class LengthUnit { @SerialName("cm") CM, @SerialName("in") IN }

/** R3 §0.1 confidence tags plus UNVERIFIED for R3 §12 items. */
@Serializable
enum class Confidence {
    @SerialName("strong") STRONG, @SerialName("moderate") MODERATE, @SerialName("weak") WEAK,
    @SerialName("contested") CONTESTED, @SerialName("unverified") UNVERIFIED
}

/** R4 §3 — 29 movement patterns. */
@Serializable
enum class MovementPattern {
    @SerialName("squat") SQUAT, @SerialName("hinge") HINGE, @SerialName("lunge_split") LUNGE_SPLIT,
    @SerialName("horizontal_push") HORIZONTAL_PUSH, @SerialName("incline_push") INCLINE_PUSH,
    @SerialName("vertical_push") VERTICAL_PUSH, @SerialName("chest_fly") CHEST_FLY,
    @SerialName("horizontal_pull") HORIZONTAL_PULL, @SerialName("vertical_pull") VERTICAL_PULL,
    @SerialName("elbow_flexion") ELBOW_FLEXION, @SerialName("elbow_extension") ELBOW_EXTENSION,
    @SerialName("shoulder_abduction") SHOULDER_ABDUCTION, @SerialName("shoulder_er_rear_delt") SHOULDER_ER_REAR_DELT,
    @SerialName("scapular_elevation") SCAPULAR_ELEVATION, @SerialName("hip_extension_isolation") HIP_EXTENSION_ISOLATION,
    @SerialName("hip_abduction") HIP_ABDUCTION, @SerialName("hip_adduction") HIP_ADDUCTION,
    @SerialName("knee_flexion_isolation") KNEE_FLEXION_ISOLATION, @SerialName("knee_extension_isolation") KNEE_EXTENSION_ISOLATION,
    @SerialName("calf") CALF, @SerialName("core_anti_extension") CORE_ANTI_EXTENSION,
    @SerialName("core_anti_rotation") CORE_ANTI_ROTATION, @SerialName("core_flexion") CORE_FLEXION,
    @SerialName("core_rotation") CORE_ROTATION, @SerialName("carry") CARRY, @SerialName("grip") GRIP,
    @SerialName("power") POWER, @SerialName("cardio") CARDIO, @SerialName("mobility") MOBILITY
}

/** R4 §4.1 — 21 muscles. */
@Serializable
enum class Muscle {
    @SerialName("chest") CHEST, @SerialName("front_delts") FRONT_DELTS, @SerialName("side_delts") SIDE_DELTS,
    @SerialName("rear_delts") REAR_DELTS, @SerialName("rotator_cuff") ROTATOR_CUFF, @SerialName("lats") LATS,
    @SerialName("upper_back") UPPER_BACK, @SerialName("traps_upper") TRAPS_UPPER, @SerialName("lower_back") LOWER_BACK,
    @SerialName("biceps") BICEPS, @SerialName("triceps") TRICEPS, @SerialName("forearms") FOREARMS,
    @SerialName("abs") ABS, @SerialName("obliques") OBLIQUES, @SerialName("hip_flexors") HIP_FLEXORS,
    @SerialName("quads") QUADS, @SerialName("hamstrings") HAMSTRINGS, @SerialName("glutes") GLUTES,
    @SerialName("glute_med") GLUTE_MED, @SerialName("adductors") ADDUCTORS, @SerialName("calves") CALVES
}

/** R4 §6.2 loadType. Gym-scoped types have no transferable load between gyms (E1RM-04, TRV-04). */
@Serializable
enum class LoadType {
    @SerialName("barbell") BARBELL, @SerialName("dumbbell") DUMBBELL, @SerialName("machine") MACHINE,
    @SerialName("cable") CABLE, @SerialName("bodyweight") BODYWEIGHT, @SerialName("band") BAND,
    @SerialName("kettlebell") KETTLEBELL, @SerialName("smith") SMITH, @SerialName("sled") SLED,
    @SerialName("medicine_ball") MEDICINE_BALL, @SerialName("cardio") CARDIO, @SerialName("none") NONE;

    val isGymScoped: Boolean get() = this == MACHINE || this == CABLE || this == SMITH || this == SLED
}

@Serializable enum class Difficulty { @SerialName("beginner") BEGINNER, @SerialName("intermediate") INTERMEDIATE, @SerialName("advanced") ADVANCED }

@Serializable
enum class EquipmentCategory {
    @SerialName("free_weights") FREE_WEIGHTS, @SerialName("racks_benches") RACKS_BENCHES, @SerialName("cables") CABLES,
    @SerialName("machines_upper") MACHINES_UPPER, @SerialName("machines_lower") MACHINES_LOWER,
    @SerialName("bodyweight_stations") BODYWEIGHT_STATIONS, @SerialName("cardio") CARDIO, @SerialName("accessories") ACCESSORIES
}

@Serializable enum class GroupKind { @SerialName("any_of") ANY_OF, @SerialName("all_of") ALL_OF }
@Serializable enum class RepUnit { @SerialName("reps") REPS, @SerialName("seconds") SECONDS, @SerialName("metres") METRES, @SerialName("minutes") MINUTES }

@Serializable
enum class IncrementKind {
    @SerialName("kg") KG, @SerialName("band_level") BAND_LEVEL, @SerialName("reps") REPS, @SerialName("time") TIME,
    @SerialName("distance") DISTANCE, @SerialName("box_height") BOX_HEIGHT, @SerialName("none") NONE
}

@Serializable enum class IncrementDirection { @SerialName("load") LOAD, @SerialName("assist") ASSIST }

@Serializable
enum class ProgressionKind {
    @SerialName("linear_load") LINEAR_LOAD, @SerialName("double_progression") DOUBLE_PROGRESSION,
    @SerialName("rep_progression") REP_PROGRESSION, @SerialName("time_progression") TIME_PROGRESSION,
    @SerialName("band_progression") BAND_PROGRESSION, @SerialName("distance_or_time") DISTANCE_OR_TIME,
    @SerialName("none") NONE
}

@Serializable enum class ContentStatus { @SerialName("todo") TODO, @SerialName("draft") DRAFT, @SerialName("reviewed") REVIEWED }

/** INJ-01..04 regions plus HIP/ANKLE/NECK (MOB-07 references ankle limitations). */
@Serializable
enum class LimitationRegion {
    @SerialName("knee") KNEE, @SerialName("shoulder") SHOULDER, @SerialName("low_back") LOW_BACK,
    @SerialName("elbow_wrist") ELBOW_WRIST, @SerialName("hip") HIP, @SerialName("ankle") ANKLE,
    @SerialName("neck") NECK, @SerialName("other") OTHER
}

@Serializable enum class Side { @SerialName("left") LEFT, @SerialName("right") RIGHT, @SerialName("both") BOTH }

/** RED-03 gate items; any member present gates HIIT, MAX_STRENGTH zone and supplement doses. */
@Serializable
enum class MedicalFlag {
    @SerialName("heart_disease") HEART_DISEASE, @SerialName("kidney_disease") KIDNEY_DISEASE,
    @SerialName("liver_disease") LIVER_DISEASE, @SerialName("pregnancy") PREGNANCY,
    @SerialName("diabetes_on_medication") DIABETES_ON_MEDICATION,
    @SerialName("uncontrolled_hypertension") UNCONTROLLED_HYPERTENSION,
    @SerialName("doctor_restriction") DOCTOR_RESTRICTION, @SerialName("other") OTHER
}

/** Planner muscle tiers (§4.4, convention, weak). */
@Serializable enum class MuscleTier { @SerialName("tier_1") TIER_1, @SerialName("tier_2") TIER_2, @SerialName("tier_3") TIER_3 }

/** SPLIT-01 rows. */
@Serializable
enum class SplitKind {
    @SerialName("full_body_1") FULL_BODY_1, @SerialName("full_body_2") FULL_BODY_2, @SerialName("full_body_3") FULL_BODY_3,
    @SerialName("upper_lower_full_3") UPPER_LOWER_FULL_3, @SerialName("upper_lower_4") UPPER_LOWER_4,
    @SerialName("ul_ppl_5") UL_PPL_5, @SerialName("ppl_6") PPL_6, @SerialName("ul_6") UL_6,
    @SerialName("travel_2") TRAVEL_2,
    /** §3.4.1 personal split row (templates.json row with an `id`), chosen only through Profile.splitId. */
    @SerialName("custom") CUSTOM
}

@Serializable
enum class WeekMode {
    @SerialName("normal") NORMAL, @SerialName("deload") DELOAD, @SerialName("reentry") REENTRY,
    @SerialName("travel_maintenance") TRAVEL_MAINTENANCE
}

@Serializable
enum class SlotRole {
    @SerialName("primary") PRIMARY, @SerialName("secondary") SECONDARY, @SerialName("accessory") ACCESSORY,
    @SerialName("isolation") ISOLATION, @SerialName("core") CORE, @SerialName("optional") OPTIONAL
}

@Serializable
enum class SessionMode {
    @SerialName("as_planned") AS_PLANNED, @SerialName("amber") AMBER, @SerialName("red") RED,
    @SerialName("alternative_z2_mobility") ALTERNATIVE_Z2_MOBILITY, @SerialName("deload") DELOAD,
    @SerialName("travel") TRAVEL
}

/** DEF-04 / WU-03: only WORKING, AMRAP, CALIBRATION and DROP count as volume. */
@Serializable
enum class SetKind {
    @SerialName("ramp") RAMP, @SerialName("working") WORKING, @SerialName("amrap") AMRAP,
    @SerialName("calibration") CALIBRATION, @SerialName("drop") DROP
}

@Serializable enum class ReadinessBand { @SerialName("green") GREEN, @SerialName("amber") AMBER, @SerialName("red") RED }

@Serializable
enum class ProgressionStatus {
    @SerialName("unknown") UNKNOWN, @SerialName("calibrating") CALIBRATING, @SerialName("progressing") PROGRESSING,
    @SerialName("plateau") PLATEAU, @SerialName("regressing") REGRESSING, @SerialName("deloading") DELOADING,
    @SerialName("reentry") REENTRY
}

@Serializable
enum class AdjustmentKind {
    @SerialName("readiness") READINESS, @SerialName("layoff") LAYOFF, @SerialName("equipment") EQUIPMENT,
    @SerialName("time_budget") TIME_BUDGET, @SerialName("progression") PROGRESSION,
    @SerialName("autoregulation") AUTOREGULATION, @SerialName("deload") DELOAD, @SerialName("volume") VOLUME,
    @SerialName("rounding") ROUNDING, @SerialName("safety") SAFETY, @SerialName("override") OVERRIDE,
    @SerialName("nutrition") NUTRITION, @SerialName("checkin") CHECKIN, @SerialName("user") USER
}

@Serializable enum class OverrideSource { @SerialName("user") USER, @SerialName("chat") CHAT, @SerialName("planner") PLANNER }

@Serializable
enum class MutationScope {
    @SerialName("today") TODAY, @SerialName("rest_of_mesocycle") REST_OF_MESOCYCLE, @SerialName("permanent") PERMANENT
}

/** CAR-02/05. */
@Serializable
enum class CardioKind {
    @SerialName("zone2") ZONE2, @SerialName("walk") WALK, @SerialName("hiit_4x4") HIIT_4X4,
    @SerialName("hiit_10x1") HIIT_10X1, @SerialName("sit") SIT
}

@Serializable
enum class CardioPlacement { @SerialName("finisher") FINISHER, @SerialName("separate_day") SEPARATE_DAY, @SerialName("off_day") OFF_DAY }

/** LOAD-04 zones. */
@Serializable
enum class LoadZone {
    @SerialName("max_strength") MAX_STRENGTH, @SerialName("heavy") HEAVY, @SerialName("moderate") MODERATE,
    @SerialName("light") LIGHT, @SerialName("power") POWER
}

/** R3 DEF-07 classes for increments and RIR. */
@Serializable
enum class ExerciseClass {
    @SerialName("lower_barbell") LOWER_BARBELL, @SerialName("upper_barbell") UPPER_BARBELL,
    @SerialName("dumbbell") DUMBBELL, @SerialName("machine") MACHINE, @SerialName("cable") CABLE,
    @SerialName("bodyweight") BODYWEIGHT, @SerialName("isolation") ISOLATION
}

/** E1RM-02: HIGH for 1..10 reps (≈ 3 % error); LOW above 10 (display only, never for decisions). */
@Serializable enum class E1rmConfidence { @SerialName("high") HIGH, @SerialName("low") LOW }

@Serializable
enum class LoadSource {
    @SerialName("progression_state") PROGRESSION_STATE, @SerialName("e1rm_estimate") E1RM_ESTIMATE,
    @SerialName("transfer_seed") TRANSFER_SEED, @SerialName("load_find") LOAD_FIND,
    @SerialName("user_override") USER_OVERRIDE, @SerialName("none") NONE
}

@Serializable
enum class SessionStatus {
    @SerialName("planned") PLANNED, @SerialName("in_progress") IN_PROGRESS, @SerialName("completed") COMPLETED,
    @SerialName("abandoned") ABANDONED, @SerialName("skipped") SKIPPED
}

@Serializable
enum class ProposalStatus {
    @SerialName("pending") PENDING, @SerialName("applied") APPLIED, @SerialName("rejected") REJECTED,
    @SerialName("deferred") DEFERRED, @SerialName("expired") EXPIRED, @SerialName("invalid") INVALID,
    @SerialName("failed") FAILED
}

@Serializable enum class BackendMode { @SerialName("none") NONE, @SerialName("direct_api") DIRECT_API, @SerialName("home_bridge") HOME_BRIDGE }
@Serializable enum class Effort { @SerialName("low") LOW, @SerialName("medium") MEDIUM }
@Serializable enum class ChatRole { @SerialName("user") USER, @SerialName("assistant") ASSISTANT }

@Serializable
enum class ConversationStatus {
    @SerialName("open") OPEN, @SerialName("awaiting_tool_results") AWAITING_TOOL_RESULTS,
    @SerialName("compacted") COMPACTED, @SerialName("closed") CLOSED
}

@Serializable enum class CheckinAnswerType { @SerialName("number") NUMBER, @SerialName("scale5") SCALE5, @SerialName("yesno") YESNO, @SerialName("text") TEXT }
@Serializable enum class CheckinSchedule { @SerialName("daily") DAILY, @SerialName("weekly") WEEKLY, @SerialName("once") ONCE }
@Serializable enum class DrillKind { @SerialName("dynamic") DYNAMIC, @SerialName("static") STATIC, @SerialName("strength_through_rom") STRENGTH_THROUGH_ROM }
@Serializable enum class Joint { @SerialName("hips") HIPS, @SerialName("t_spine") T_SPINE, @SerialName("shoulders") SHOULDERS, @SerialName("ankles") ANKLES, @SerialName("hamstrings") HAMSTRINGS, @SerialName("wrists") WRISTS }
