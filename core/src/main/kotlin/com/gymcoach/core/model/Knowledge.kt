// core/src/main/kotlin/com/gymcoach/core/model/Knowledge.kt
package com.gymcoach.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class MuscleWeight(val muscle: Muscle, val weight: Double)          // 1.0 | 0.5 | 0.25

@Serializable
data class EquipmentOption(
    @SerialName("all_of") val allOf: List<String>,
    @SerialName("load_type") val loadType: LoadType? = null,
    val note: String? = null,
    val preferred: Boolean = false,
)

@Serializable
data class GoalPrescription(
    val range: Band,
    val rir: Band,
    @SerialName("rir_last_week") val rirLastWeek: Band? = null,
    val sets: Band? = null,
    val note: String? = null,
)

@Serializable
data class RepProfile(
    val id: String,
    val unit: RepUnit,
    @SerialName("by_goal") val byGoal: Map<String, GoalPrescription>,   // keys: strength | hypertrophy | endurance | general_fitness
    val notes: String? = null,
    val sources: List<String> = emptyList(),
) {
    fun forGoal(goal: Goal): GoalPrescription = checkNotNull(byGoal[goal.repProfileKey]) { "rep profile $id lacks ${goal.repProfileKey}" }
}

@Serializable
data class Increment(
    val kind: IncrementKind,
    @SerialName("default_kg") val defaultKg: Double? = null,
    @SerialName("min_kg") val minKg: Double? = null,
    @SerialName("per_hand") val perHand: Boolean = false,
    val direction: IncrementDirection = IncrementDirection.LOAD,
    val note: String? = null,
)

@Serializable
data class ExerciseFlags(
    val compound: Boolean,
    @SerialName("spinal_loading") val spinalLoading: Boolean,
    @SerialName("shoulder_sensitive") val shoulderSensitive: Boolean,
    @SerialName("knee_sensitive") val kneeSensitive: Boolean,
    @SerialName("low_back_sensitive") val lowBackSensitive: Boolean,
    @SerialName("requires_spotter_or_safeties") val requiresSpotterOrSafeties: Boolean,
)

@Serializable
data class ExerciseContent(
    val description: String = "",
    val setup: List<String> = emptyList(),
    val cues: List<String> = emptyList(),
    val mistakes: List<String> = emptyList(),
    val safety: List<String> = emptyList(),
    @SerialName("substitution_rationale") val substitutionRationale: Map<String, String> = emptyMap(),
    val sources: List<String> = emptyList(),
    @SerialName("review_notes") val reviewNotes: String? = null,
)

@Serializable
data class Exercise(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val pattern: MovementPattern,
    val muscles: List<MuscleWeight> = emptyList(),
    @SerialName("equipment_options") val equipmentOptions: List<EquipmentOption>,
    @SerialName("load_type") val loadType: LoadType,
    val unilateral: Boolean,
    val difficulty: Difficulty,
    @SerialName("rep_profile") val repProfile: String,
    @SerialName("rep_overrides") val repOverrides: Map<String, GoalPrescription> = emptyMap(),
    val increment: Increment,
    val progression: ProgressionKind,
    val substitutions: List<String> = emptyList(),
    val flags: ExerciseFlags,
    val batch: Int,
    val tags: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val content: ExerciseContent? = null,
    @SerialName("content_status") val contentStatus: ContentStatus = ContentStatus.TODO,
) {
    val isCardioOrMobility: Boolean get() = pattern == MovementPattern.CARDIO || pattern == MovementPattern.MOBILITY
    val isIsolation: Boolean get() = !flags.compound && !isCardioOrMobility
    val primaryMuscles: List<Muscle> get() = muscles.filter { it.weight >= 1.0 }.map { it.muscle }
    fun weightOf(muscle: Muscle): Double = muscles.firstOrNull { it.muscle == muscle }?.weight ?: 0.0

    /** R3 DEF-07 class for the effective load type of the chosen equipment option. */
    fun exerciseClass(effectiveLoadType: LoadType): ExerciseClass {
        if (isIsolation) return ExerciseClass.ISOLATION
        return when (effectiveLoadType) {
            LoadType.BARBELL, LoadType.SMITH ->
                if (pattern == MovementPattern.SQUAT || pattern == MovementPattern.HINGE ||
                    pattern == MovementPattern.LUNGE_SPLIT || pattern == MovementPattern.HIP_EXTENSION_ISOLATION)
                    ExerciseClass.LOWER_BARBELL else ExerciseClass.UPPER_BARBELL
            LoadType.DUMBBELL, LoadType.KETTLEBELL -> ExerciseClass.DUMBBELL
            LoadType.MACHINE, LoadType.SLED -> ExerciseClass.MACHINE
            LoadType.CABLE -> ExerciseClass.CABLE
            LoadType.BODYWEIGHT, LoadType.BAND, LoadType.MEDICINE_BALL, LoadType.CARDIO, LoadType.NONE -> ExerciseClass.BODYWEIGHT
        }
    }
}

@Serializable
data class ExerciseLibraryFile(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("renamed_ids") val renamedIds: Map<String, String> = emptyMap(),   // old id -> new id
    @SerialName("rep_profiles") val repProfiles: List<RepProfile>,
    val exercises: List<Exercise>,
)

/** One content-overlay file per authoring batch (R4 §5.12): exercise_content/batch_N.json. */
@Serializable
data class ContentOverlay(
    @SerialName("schema_version") val schemaVersion: Int,
    val batch: Int,
    val entries: Map<String, ExerciseContent>,
    val status: Map<String, ContentStatus> = emptyMap(),
)

@Serializable
data class ParamSpec(
    val key: String,
    val type: String,                        // "number" | "integer" | "boolean" | "number_list"
    val unit: String? = null,
    val default: JsonElement? = null,        // R4 §6.1: number | boolean | array
    val min: Double? = null,
    val max: Double? = null,
    val description: String? = null,
)

@Serializable
data class EquipmentDef(
    val id: String,
    val name: String,
    val category: EquipmentCategory,
    val aliases: List<String> = emptyList(),
    val description: String? = null,
    val params: List<ParamSpec> = emptyList(),
    @SerialName("session_flag") val sessionFlag: Boolean = false,
    val packable: Boolean = false,
    @SerialName("default_in") val defaultIn: List<String> = emptyList(),
    val notes: String? = null,
)

@Serializable
data class EquipmentGroup(val id: String, val kind: GroupKind, val name: String? = null, val members: List<String>, val notes: String? = null)

@Serializable
data class PresetItem(@SerialName("equipment_id") val equipmentId: String, val params: Map<String, JsonElement> = emptyMap())

@Serializable
data class EquipmentPreset(val id: String, val name: String, val description: String? = null, @SerialName("default_session_minutes") val defaultSessionMinutes: Int? = null, val items: List<PresetItem>)

@Serializable
data class EquipmentCatalogFile(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("renamed_ids") val renamedIds: Map<String, String> = emptyMap(),
    val equipment: List<EquipmentDef>,
    val groups: List<EquipmentGroup>,
    val presets: List<EquipmentPreset>,
)

/** rules.json: one statement per R3 rule ID with provenance; constants are the PlannerConstants defaults (§3.5). */
@Serializable
data class RuleEntry(
    val id: String,                          // "VOL-04"
    val section: String,                     // "Resistance training / Weekly volume"
    val statement: String,
    val confidence: Confidence,
    val citation: String,                    // author-year or position stand
    val url: String? = null,
    val constants: Map<String, Double> = emptyMap(),   // e.g. "intermediate.lo": 12.0
    @SerialName("user_adjustable") val userAdjustable: Boolean = false,
    val note: String? = null,
)

@Serializable
data class RulesFile(@SerialName("schema_version") val schemaVersion: Int, val rules: List<RuleEntry>)

/** mobility_drills.json (MOB-07). */
@Serializable
data class Drill(
    val id: String,
    val name: String,
    val joint: Joint,
    val kind: DrillKind,
    val patterns: List<MovementPattern> = emptyList(),   // session patterns this drill prepares
    @SerialName("limitation_regions") val limitationRegions: List<LimitationRegion> = emptyList(),
    @SerialName("dose_reps") val doseReps: Band? = null,
    @SerialName("dose_seconds") val doseSeconds: Band? = null,
    @SerialName("exercise_id") val exerciseId: String? = null,   // when the drill is an R4 mobility exercise
    val sources: List<String> = emptyList(),
)

@Serializable
data class DrillsFile(@SerialName("schema_version") val schemaVersion: Int, val drills: List<Drill>)

/** templates.json (§4.5): session templates and split rows. */
@Serializable
data class SlotTemplate(
    @SerialName("slot_id") val slotId: String,
    val role: SlotRole,
    val patterns: List<MovementPattern>,                 // first is preferred; later ones are family fallbacks
    val heavy: Boolean = false,
    @SerialName("prefer_load_types") val preferLoadTypes: List<LoadType> = emptyList(),
    @SerialName("target_muscles") val targetMuscles: List<Muscle> = emptyList(),   // ISOLATION slots; empty = "priority deficit"
    val required: Boolean = true,
    @SerialName("superset_with") val supersetWith: String? = null,
    @SerialName("backoff_of") val backoffOf: String? = null,          // STRENGTH variants (§3.4): "<templateId>.<slotId>" of the heavy slot this slot backs off; templateId must be listed in the SAME split row (KnowledgeValidator rule 10)
    // ---- §3.4.1 personal-template fields (Phase 2, all optional; generic templates leave them unset) ----
    @SerialName("exercise_ids") val exerciseIds: List<String>? = null,   // preference list: the first available, not excluded id takes the slot; none → normal SEL scoring
    val sets: Int? = null,                                                // fixed working sets: the allocator neither adds nor rebalances them
    val reps: Band? = null,                                               // fixed rep band (replaces the rep profile band)
    val rir: Int? = null,                                                 // fixed target RIR (replaces the RIR-04 week offsets; floors still apply)
    @SerialName("rest_seconds") val restSeconds: Int? = null,             // fixed rest between working sets
    @SerialName("progression_variant") val progressionVariant: String? = null,   // separate ProgressionKey.variant, e.g. "backoff" for top-set + back-off slots
    @SerialName("load_factor") val loadFactor: Double? = null,            // with progression_variant: load = factor × the base key's load while the variant has no state of its own
    val ramp: List<RampStep>? = null,                                     // explicit warm-up ladder (replaces the WU-02 percentages)
    val note: String? = null,                                             // cue shown with the block ("left leg first", "1-count pause on the chest")
)

/** One step of an explicit warm-up ladder: `pct` of the first working load (0 = the empty bar) for `reps` reps. */
@Serializable
data class RampStep(val pct: Double, val reps: Int)

/** A drill of a personal template's mobility block or stretch list with its own dose. */
@Serializable
data class DrillDose(
    @SerialName("drill_id") val drillId: String,
    val sets: Int = 1,
    val reps: Band? = null,
    val seconds: Band? = null,
    val note: String? = null,
)

@Serializable
data class SessionTemplate(
    val id: String,
    val name: String,
    val slots: List<SlotTemplate>,
    // ---- §3.4.1 personal-template fields (Phase 2, all optional) ----
    val minutes: Int? = null,                                             // session length of this template (replaces Profile.sessionMinutes for it)
    @SerialName("raise_minutes") val raiseMinutes: Int? = null,           // WU-01 raise length (easy cardio first)
    @SerialName("raise_exercise_ids") val raiseExerciseIds: List<String>? = null,   // preferred raise modalities, first available wins
    @SerialName("warmup_drills") val warmupDrills: List<DrillDose>? = null,         // replaces the automatic MOB-01 drill pick
    @SerialName("cooldown_drills") val cooldownDrills: List<DrillDose>? = null,     // post-workout stretches (prepended to the MOB-02 cool-down)
    val lower: Boolean? = null,                                           // lower-body day (raise on the bike) — informational
)

@Serializable
data class SplitTemplate(
    val kind: SplitKind,
    @SerialName("days_per_week") val daysPerWeek: Int,
    @SerialName("session_template_ids") val sessionTemplateIds: List<String>,   // weekly order
    @SerialName("default_weekdays") val defaultWeekdays: List<Int>,            // ISO 1=Mon..7=Sun
    @SerialName("for_goals") val forGoals: List<Goal> = emptyList(),           // empty = any
    @SerialName("for_training_ages") val forTrainingAges: List<TrainingAge> = emptyList(),
    // ---- §3.4.1 personal split rows (Phase 2, kind = custom; never chosen by the generic row walk) ----
    val id: String? = null,                                               // chosen through Profile.splitId
    @SerialName("drop_order") val dropOrder: List<String>? = null,       // templates dropped first when fewer days are scheduled (e.g. arms day first)
    @SerialName("accumulation_weeks") val accumulationWeeks: Int? = null, // DLD-01 override: weeks before the closing (test) week
    @SerialName("closing_week") val closingWeek: String? = null,          // "test": the week after the accumulation weeks is a test week (deload-dosed, singles on the lift closest to its target)
    @SerialName("min_days_squat_to_hinge") val minDaysSquatToHinge: Int? = null,   // heavy squat and heavy hinge days at least this many days apart
)

@Serializable
data class TemplatesFile(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("session_templates") val sessionTemplates: List<SessionTemplate>,
    val splits: List<SplitTemplate>,
)

/** checkin_questions.json (CHK-01). */
@Serializable
data class CheckinQuestionDef(
    val id: String,                          // "Q1".."Q15"
    val text: String,
    @SerialName("answer_type") val answerType: CheckinAnswerType,
    @SerialName("auto_source") val autoSource: String? = null,   // "weight_trend" | "steps" | "sessions_completed" | null
    @SerialName("trigger_rule_ids") val triggerRuleIds: List<String>,
    @SerialName("trigger_condition") val triggerCondition: String,   // human-readable, evaluated in CheckinEvaluator by id
    @SerialName("only_in_deficit") val onlyInDeficit: Boolean = false,
    @SerialName("always_ask") val alwaysAsk: Boolean = false,
)

@Serializable
data class CheckinQuestionsFile(@SerialName("schema_version") val schemaVersion: Int, val questions: List<CheckinQuestionDef>)

/** rp_landmarks.json (R4 §4.5, direct-count sets/week, intermediate). */
@Serializable
data class RpLandmark(val muscle: Muscle, val mv: Band, val mev: Band, val mav: Band, val mrv: Band, @SerialName("sessions_per_week") val sessionsPerWeek: Band)

@Serializable
data class RpLandmarksFile(@SerialName("schema_version") val schemaVersion: Int, val source: String, @SerialName("fetched_on") val fetchedOn: String, val landmarks: List<RpLandmark>)

/** supplements.json (R3 §7). */
@Serializable
data class SupplementEntry(
    val id: String,                          // "SUP-01"
    val name: String,
    val tier: String,                        // "A" | "B" | "C"
    val dose: String,
    val timing: String,
    val evidence: Confidence,
    val citation: String,
    val notes: List<String> = emptyList(),
    @SerialName("do_not_reason") val doNotReason: String? = null,   // tier C
    @SerialName("gated_by_medical") val gatedByMedical: List<MedicalFlag> = emptyList(),
    @SerialName("legal_note") val legalNote: String? = null,
)

@Serializable
data class SupplementsFile(@SerialName("schema_version") val schemaVersion: Int, @SerialName("caveat_sup12") val caveatSup12: List<String>, @SerialName("priority_order") val priorityOrder: String, val entries: List<SupplementEntry>)

/** cardio_modalities.json (CAR-08). */
@Serializable
data class CardioModalityEntry(
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("zone2_control") val zone2Control: String,
    @SerialName("hiit_suitability") val hiitSuitability: String,     // "low" | "moderate" | "high"
    @SerialName("leg_day_adjacency") val legDayAdjacency: String,   // "good" | "moderate" | "poor"
    @SerialName("is_running") val isRunning: Boolean = false,
    @SerialName("knee_impact") val kneeImpact: Boolean = false,
)

@Serializable
data class CardioModalitiesFile(@SerialName("schema_version") val schemaVersion: Int, val modalities: List<CardioModalityEntry>)

/** nutrition_rules.json: display tables for the Nutrition/Knowledge screens and the coach digest (NUT-02/03/12/14). */
@Serializable
data class NutritionTip(val id: String, @SerialName("rule_id") val ruleId: String, val title: String, val body: String, val confidence: Confidence, val citation: String)

@Serializable
data class NutritionTipsFile(@SerialName("schema_version") val schemaVersion: Int, val tips: List<NutritionTip>)

/** load_transfer.json (Appendix C): find-load seeds only. */
@Serializable
data class LoadTransferSeed(@SerialName("from_exercise_id") val fromExerciseId: String, @SerialName("to_exercise_id") val toExerciseId: String, val factor: Double, val note: String? = null)

/** Body-weight-fraction seed for a ~10-rep set when nothing else is known (B graft, weak): "starting guess". */
@Serializable
data class BodyweightSeed(val pattern: MovementPattern, @SerialName("load_type") val loadType: LoadType, val fraction: Double, @SerialName("per_hand") val perHand: Boolean = false)

@Serializable
data class LoadTransferFile(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("unilateral_factor") val unilateralFactor: Double,          // 0.5
    val seeds: List<LoadTransferSeed>,
    @SerialName("bodyweight_seeds") val bodyweightSeeds: List<BodyweightSeed>,
    @SerialName("female_factor") val femaleFactor: Double,                  // 0.6
    @SerialName("intermediate_factor") val intermediateFactor: Double,      // 1.4
    @SerialName("advanced_factor") val advancedFactor: Double,              // 1.7
)
