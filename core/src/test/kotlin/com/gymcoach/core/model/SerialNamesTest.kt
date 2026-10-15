package com.gymcoach.core.model

import com.gymcoach.core.json.CoreJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer

/**
 * Pins the exact JSON keys of stored documents (§2 preamble: "the @SerialNames below are exactly the
 * ones that appear in stored documents"). A round trip cannot detect a camelCase key leaking through;
 * these assertions can.
 */
class SerialNamesTest {

    private fun <T> encoded(serializer: KSerializer<T>, value: T): String = CoreJson.stored.encodeToString(serializer, value)

    private fun assertKeys(json: String, vararg keys: String) {
        for (k in keys) assertTrue(json.contains("\"$k\":"), "missing key \"$k\" in $json")
    }

    private val keyPattern = Regex("(?<![\\\\])\"([A-Za-z0-9_.:@\\- ]+)\":")

    private fun topLevelKeys(json: String): List<String> = keyPattern.findAll(json).map { it.groupValues[1] }.toList()

    private val allDocuments: List<Pair<String, String>> = listOf(
        "Explanation" to encoded(Explanation.serializer(), Fixtures.explanation),
        "Exercise" to encoded(Exercise.serializer(), Fixtures.exercise),
        "ExerciseLibraryFile" to encoded(ExerciseLibraryFile.serializer(), Fixtures.exerciseLibraryFile),
        "EquipmentCatalogFile" to encoded(EquipmentCatalogFile.serializer(), Fixtures.equipmentCatalogFile),
        "RulesFile" to encoded(RulesFile.serializer(), Fixtures.rulesFile),
        "DrillsFile" to encoded(DrillsFile.serializer(), Fixtures.drillsFile),
        "TemplatesFile" to encoded(TemplatesFile.serializer(), Fixtures.templatesFile),
        "CheckinQuestionsFile" to encoded(CheckinQuestionsFile.serializer(), Fixtures.checkinQuestionsFile),
        "RpLandmarksFile" to encoded(RpLandmarksFile.serializer(), Fixtures.rpLandmarksFile),
        "SupplementsFile" to encoded(SupplementsFile.serializer(), Fixtures.supplementsFile),
        "CardioModalitiesFile" to encoded(CardioModalitiesFile.serializer(), Fixtures.cardioModalitiesFile),
        "NutritionTipsFile" to encoded(NutritionTipsFile.serializer(), Fixtures.nutritionTipsFile),
        "LoadTransferFile" to encoded(LoadTransferFile.serializer(), Fixtures.loadTransferFile),
        "ContentOverlay" to encoded(ContentOverlay.serializer(), Fixtures.contentOverlay),
        "Profile" to encoded(Profile.serializer(), Fixtures.profile),
        "BodyMetric" to encoded(BodyMetric.serializer(), Fixtures.bodyMetric),
        "GymSet" to encoded(GymSet.serializer(), Fixtures.gymSet),
        "TodayContext" to encoded(TodayContext.serializer(), Fixtures.todayContext),
        "ReadinessCheckin" to encoded(ReadinessCheckin.serializer(), Fixtures.readinessCheckin),
        "CheckinQuestion" to encoded(CheckinQuestion.serializer(), Fixtures.checkinQuestion),
        "WeeklyCheckin" to encoded(WeeklyCheckin.serializer(), Fixtures.weeklyCheckin),
        "Mesocycle" to encoded(Mesocycle.serializer(), Fixtures.mesocycle),
        "SessionPlan" to encoded(SessionPlan.serializer(), Fixtures.sessionPlan),
        "SetLog" to encoded(SetLog.serializer(), Fixtures.setLog),
        "SessionRecord" to encoded(SessionRecord.serializer(), Fixtures.sessionRecord),
        "Adjustment" to encoded(Adjustment.serializer(), Fixtures.adjustment),
        "CardioLog" to encoded(CardioLog.serializer(), Fixtures.cardioLog),
        "NutritionLog" to encoded(NutritionLog.serializer(), Fixtures.nutritionLog),
        "ProgressionState" to encoded(ProgressionState.serializer(), Fixtures.progressionState),
        "PlanOverride" to encoded(PlanOverride.serializer(), Fixtures.planOverride),
        "PlanInputs" to encoded(PlanInputs.serializer(), Fixtures.planInputs),
        "PlanOutput" to encoded(PlanOutput.serializer(), Fixtures.planOutput),
        "SessionCloseResult" to encoded(SessionCloseResult.serializer(), Fixtures.sessionCloseResult),
        "WeekRollResult" to encoded(WeekRollResult.serializer(), Fixtures.weekRollResult),
        "PlanDiff" to encoded(PlanDiff.serializer(), Fixtures.planDiff),
        "EnergyModel" to encoded(EnergyModel.serializer(), Fixtures.energyModel),
        "NutritionTargets" to encoded(NutritionTargets.serializer(), Fixtures.nutritionTargets),
        "WeightTrendPoint" to encoded(WeightTrendPoint.serializer(), Fixtures.weightTrendPoint),
        "NutritionState" to encoded(NutritionState.serializer(), Fixtures.nutritionState),
        "ChatConversation" to encoded(ChatConversation.serializer(), Fixtures.chatConversation),
        "ChatMessage" to encoded(ChatMessage.serializer(), Fixtures.chatMessage),
        "Proposal" to encoded(Proposal.serializer(), Fixtures.proposal),
        "SpendRecord" to encoded(SpendRecord.serializer(), Fixtures.spendRecord),
        "AppSettings" to encoded(AppSettings.serializer(), Fixtures.appSettings),
        "ChartSeries" to encoded(ChartSeries.serializer(), Fixtures.chartSeries),
        "MuscleBandRow" to encoded(MuscleBandRow.serializer(), Fixtures.muscleBandRow),
        "SupplementAdvice" to encoded(SupplementAdvice.serializer(), Fixtures.supplementAdvice),
    ) + Fixtures.mutations.map { it::class.simpleName.orEmpty() to encoded(PlanMutation.serializer(), it) }

    @Test
    fun noCamelCaseKeyLeaksIntoAnyDocument() {
        for ((name, json) in allDocuments) {
            val bad = topLevelKeys(json).filter { key -> key.any { it.isUpperCase() } }
            assertTrue(bad.isEmpty(), "$name has non-snake_case keys $bad in $json")
        }
    }

    @Test
    fun everyDocumentHasAtLeastOneKey() {
        for ((name, json) in allDocuments) assertTrue(topLevelKeys(json).isNotEmpty(), "$name produced no keys: $json")
    }

    @Test fun explanationKeys() = assertKeys(encoded(Explanation.serializer(), Fixtures.explanation), "rule_ids", "message", "inputs", "confidence")
    @Test fun enteredWeightKeys() = assertKeys(encoded(EnteredWeight.serializer(), Fixtures.enteredWeight), "entered", "unit", "kg")

    @Test fun exerciseKeys() = assertKeys(
        encoded(Exercise.serializer(), Fixtures.exercise),
        "id", "name", "aliases", "pattern", "muscles", "equipment_options", "load_type", "unilateral", "difficulty", "rep_profile",
        "rep_overrides", "increment", "progression", "substitutions", "flags", "batch", "tags", "sources", "content", "content_status",
    )
    @Test fun exerciseFlagsKeys() = assertKeys(
        encoded(ExerciseFlags.serializer(), Fixtures.exerciseFlags),
        "compound", "spinal_loading", "shoulder_sensitive", "knee_sensitive", "low_back_sensitive", "requires_spotter_or_safeties",
    )
    @Test fun equipmentOptionKeys() = assertKeys(encoded(EquipmentOption.serializer(), Fixtures.equipmentOption), "all_of", "load_type", "note", "preferred")
    @Test fun goalPrescriptionKeys() = assertKeys(encoded(GoalPrescription.serializer(), Fixtures.goalPrescription), "range", "rir", "rir_last_week", "sets", "note")
    @Test fun repProfileKeys() = assertKeys(encoded(RepProfile.serializer(), Fixtures.repProfile), "id", "unit", "by_goal", "notes", "sources")
    @Test fun incrementKeys() = assertKeys(encoded(Increment.serializer(), Fixtures.increment), "kind", "default_kg", "min_kg", "per_hand", "direction", "note")
    @Test fun exerciseContentKeys() = assertKeys(encoded(ExerciseContent.serializer(), Fixtures.exerciseContent), "description", "setup", "cues", "mistakes", "safety", "substitution_rationale", "sources", "review_notes")
    @Test fun exerciseLibraryFileKeys() = assertKeys(encoded(ExerciseLibraryFile.serializer(), Fixtures.exerciseLibraryFile), "schema_version", "renamed_ids", "rep_profiles", "exercises")
    @Test fun contentOverlayKeys() = assertKeys(encoded(ContentOverlay.serializer(), Fixtures.contentOverlay), "schema_version", "batch", "entries", "status")
    @Test fun paramSpecKeys() = assertKeys(encoded(ParamSpec.serializer(), Fixtures.paramSpec), "key", "type", "unit", "default", "min", "max", "description")
    @Test fun equipmentDefKeys() = assertKeys(encoded(EquipmentDef.serializer(), Fixtures.equipmentDef), "id", "name", "category", "aliases", "description", "params", "session_flag", "packable", "default_in", "notes")
    @Test fun presetKeys() {
        assertKeys(encoded(PresetItem.serializer(), Fixtures.presetItem), "equipment_id", "params")
        assertKeys(encoded(EquipmentPreset.serializer(), Fixtures.equipmentPreset), "id", "name", "description", "default_session_minutes", "items")
    }
    @Test fun ruleEntryKeys() = assertKeys(encoded(RuleEntry.serializer(), Fixtures.ruleEntry), "id", "section", "statement", "confidence", "citation", "url", "constants", "user_adjustable", "note")
    @Test fun drillKeys() = assertKeys(encoded(Drill.serializer(), Fixtures.drill), "joint", "kind", "patterns", "limitation_regions", "dose_reps", "exercise_id")
    @Test fun slotTemplateKeys() = assertKeys(encoded(SlotTemplate.serializer(), Fixtures.slotTemplate), "slot_id", "role", "patterns", "heavy", "prefer_load_types", "target_muscles", "required", "superset_with", "backoff_of")
    @Test fun splitTemplateKeys() = assertKeys(encoded(SplitTemplate.serializer(), Fixtures.splitTemplate), "kind", "days_per_week", "session_template_ids", "default_weekdays", "for_goals", "for_training_ages")
    @Test fun templatesFileKeys() = assertKeys(encoded(TemplatesFile.serializer(), Fixtures.templatesFile), "schema_version", "session_templates", "splits")
    @Test fun checkinQuestionDefKeys() = assertKeys(encoded(CheckinQuestionDef.serializer(), Fixtures.checkinQuestionDef), "answer_type", "auto_source", "trigger_rule_ids", "trigger_condition", "only_in_deficit", "always_ask")
    @Test fun rpLandmarkKeys() = assertKeys(encoded(RpLandmark.serializer(), Fixtures.rpLandmark), "muscle", "mv", "mev", "mav", "mrv", "sessions_per_week")
    @Test fun rpLandmarksFileKeys() = assertKeys(encoded(RpLandmarksFile.serializer(), Fixtures.rpLandmarksFile), "schema_version", "source", "fetched_on", "landmarks")
    @Test fun supplementKeys() {
        assertKeys(encoded(SupplementEntry.serializer(), Fixtures.supplementEntry), "tier", "dose", "timing", "evidence", "citation", "notes", "gated_by_medical", "legal_note")
        assertKeys(encoded(SupplementsFile.serializer(), Fixtures.supplementsFile), "caveat_sup12", "priority_order", "entries")
    }
    @Test fun cardioModalityKeys() = assertKeys(encoded(CardioModalityEntry.serializer(), Fixtures.cardioModalityEntry), "exercise_id", "zone2_control", "hiit_suitability", "leg_day_adjacency", "is_running", "knee_impact")
    @Test fun nutritionTipKeys() = assertKeys(encoded(NutritionTip.serializer(), Fixtures.nutritionTip), "rule_id", "title", "body", "confidence", "citation")
    @Test fun loadTransferKeys() {
        assertKeys(encoded(LoadTransferSeed.serializer(), Fixtures.loadTransferSeed), "from_exercise_id", "to_exercise_id", "factor", "note")
        assertKeys(encoded(BodyweightSeed.serializer(), Fixtures.bodyweightSeed), "pattern", "load_type", "fraction", "per_hand")
        assertKeys(encoded(LoadTransferFile.serializer(), Fixtures.loadTransferFile), "unilateral_factor", "seeds", "bodyweight_seeds", "female_factor", "intermediate_factor", "advanced_factor")
    }

    @Test fun limitationKeys() = assertKeys(encoded(Limitation.serializer(), Fixtures.limitation), "id", "region", "side", "pain_0_10", "since", "active", "limited_range", "notes")
    @Test fun profileKeys() = assertKeys(
        encoded(Profile.serializer(), Fixtures.profile),
        "sex", "birth_year", "height", "training_months_consistent", "layoff_months", "months_retrained_since_layoff", "goal", "days_per_week",
        "session_minutes", "preferred_weekdays", "limitations", "medical_flags", "clearance_confirmed_on", "priority_muscles", "liked_exercise_ids",
        "disliked_exercise_ids", "known_loads_kg", "allow_advanced_exercises", "body_fat_pct", "physical_job", "activity_factor_override",
        "has_hr_monitor", "measured_hr_max", "wake_time_local", "bedtime_local", "usual_session_time", "cardio_inside_session_minutes", "updated_at",
    )
    @Test fun bodyMetricKeys() = assertKeys(encoded(BodyMetric.serializer(), Fixtures.bodyMetric), "id", "date", "weight", "waist_cm", "resting_hr", "steps", "note", "logged_at")
    @Test fun gymSetKeys() = assertKeys(
        encoded(GymSet.serializer(), Fixtures.gymSet),
        "id", "name", "preset_origin", "items", "default_session_minutes", "color_index", "travel_kit_merged", "notes", "is_default", "archived", "created_at", "last_used_at", "schema_version", "equipment_id", "params",
    )
    @Test fun todayContextKeys() = assertKeys(
        encoded(TodayContext.serializer(), Fixtures.todayContext),
        "date", "gym_id", "extra_items", "removed_equipment_ids", "spotter", "minutes_override", "readiness", "ambient_cold", "minutes_since_waking", "illness_symptoms", "alcohol_drinks_last_night",
    )
    @Test fun readinessCheckinKeys() = assertKeys(
        encoded(ReadinessCheckin.serializer(), Fixtures.readinessCheckin),
        "quick_mode", "sleep_hours", "soreness", "soreness_region", "energy", "motivation", "resting_hr", "joint_pain_0_10", "joint_region", "red_flag_reported", "assumed_fields", "score", "band", "explanation", "logged_at", "schema_version",
    )
    @Test fun checkinKeys() {
        assertKeys(encoded(CheckinQuestion.serializer(), Fixtures.checkinQuestion), "answer_type", "schedule", "on_date", "trigger_rule_ids", "added_by", "proposal_id", "active_from", "active_until", "answered", "answer", "answered_at", "schema_version")
        assertKeys(encoded(CheckinAnswer.serializer(), Fixtures.checkinAnswer), "question_id", "number", "yes", "text")
        assertKeys(encoded(WeeklyCheckin.serializer(), Fixtures.weeklyCheckin), "week_start", "answers", "auto_answered", "triggered", "holds", "completed_at", "schema_version")
    }

    @Test fun plannedSlotKeys() = assertKeys(encoded(PlannedSlot.serializer(), Fixtures.plannedSlot), "slot_id", "role", "pattern", "exercise_id", "option_index", "sets", "heavy", "superset_group", "explanation")
    @Test fun cardioPrescriptionKeys() = assertKeys(encoded(CardioPrescription.serializer(), Fixtures.cardioPrescription), "exercise_id", "minutes", "kind", "hr_low", "hr_high", "rpe_low", "rpe_high", "placement", "weekday")
    @Test fun plannedSessionKeys() = assertKeys(encoded(PlannedSession.serializer(), Fixtures.plannedSession), "template_id", "weekday", "slots", "estimated_minutes", "volume_fractional", "volume_direct")
    @Test fun mesoWeekKeys() = assertKeys(encoded(MesoWeek.serializer(), Fixtures.mesoWeek), "index", "start_date", "mode", "rir_offset", "targets", "sessions", "cardio", "notes")
    @Test fun mesocycleKeys() = assertKeys(encoded(Mesocycle.serializer(), Fixtures.mesocycle), "start_date", "goal", "training_age", "split", "gym_id", "weeks", "deload_week_index", "inputs_hash", "under_target_report", "explanations", "schema_version")
    @Test fun warmupKeys() {
        assertKeys(encoded(Warmup.serializer(), Fixtures.warmup), "raise_minutes", "raise_exercise_id", "drills", "total_minutes", "explanation")
        assertKeys(encoded(DrillPrescription.serializer(), Fixtures.drillPrescription), "drill_id", "sets", "reps", "explanation")
    }
    @Test fun setPrescriptionKeys() = assertKeys(encoded(SetPrescription.serializer(), Fixtures.setPrescription), "index", "kind", "reps", "load_kg", "load_label", "per_hand", "rir", "rest_seconds", "load_source")
    @Test fun exerciseBlockKeys() = assertKeys(
        encoded(ExerciseBlock.serializer(), Fixtures.exerciseBlock),
        "block_id", "slot_id", "exercise_id", "option_index", "effective_load_type", "substituted_for", "slot_role", "order", "sets", "superset_group", "progression_key", "warnings", "explanation",
    )
    @Test fun sessionPlanKeys() = assertKeys(
        encoded(SessionPlan.serializer(), Fixtures.sessionPlan),
        "planned_session_id", "template_id", "date", "gym_id", "mode", "week_index", "readiness_score", "warmup", "blocks", "cardio", "cooldown", "modifiers", "estimated_minutes", "volume", "fractional", "direct", "ledger_carryover", "alternative_offered", "schema_version",
    )

    @Test fun setLogKeys() = assertKeys(
        encoded(SetLog.serializer(), Fixtures.setLog),
        "session_id", "block_id", "exercise_id", "set_index", "kind", "weight", "per_hand", "reps", "rir", "side", "prescribed_load_kg", "prescribed_reps", "prescribed_rir", "logged_at", "superseded_by_id", "note",
    )
    @Test fun sessionRecordKeys() = assertKeys(
        encoded(SessionRecord.serializer(), Fixtures.sessionRecord),
        "planned_session_id", "template_id", "plan", "status", "started_at", "session_rpe", "duration_minutes", "readiness_checkin_id", "notes",
    )
    @Test fun adjustmentKeys() = assertKeys(encoded(Adjustment.serializer(), Fixtures.adjustment), "session_id", "exercise_id", "kind", "rule_ids", "summary", "inputs", "before_json", "after_json", "source", "created_at")
    @Test fun cardioLogKeys() = assertKeys(encoded(CardioLog.serializer(), Fixtures.cardioLog), "session_id", "exercise_id", "kind", "minutes", "avg_hr", "rpe", "logged_at")
    @Test fun nutritionLogKeys() = assertKeys(encoded(NutritionLog.serializer(), Fixtures.nutritionLog), "kcal", "protein_g", "protein_target_hit", "meals_heaviness", "alcohol_drinks", "travel_day", "logged_at")

    @Test fun progressionKeys() {
        assertKeys(encoded(ProgressionKey.serializer(), Fixtures.progressionKey), "exercise_id", "gym_scope")
        assertKeys(encoded(E1rmEstimate.serializer(), Fixtures.e1rmEstimate), "kg", "from_weight_kg", "from_reps", "from_rir", "confidence", "date", "formula_id")
        assertKeys(encoded(SessionSummary.serializer(), Fixtures.sessionSummary), "load_kg", "best_reps", "min_rir", "all_sets_at_hi", "e1rm", "load_x_reps", "readiness_band")
        assertKeys(
            encoded(ProgressionState.serializer(), Fixtures.progressionState),
            "key", "status", "working_load_kg", "working_reps", "rep_hi_base", "rep_hi_extended", "target_rir", "consecutive_hits", "last_session_date", "last_increment_date",
            "sessions_since_increment", "no_progress_count", "best_e1rm_14d", "best_e1rm_14d_date", "plateau_step", "calibration_sessions", "rep_zone_shift", "history", "updated_at", "schema_version",
        )
    }

    @Test fun mutationKeys() {
        val discriminators = Fixtures.mutations.map { m ->
            Regex("\"type\":\"([a-z_]+)\"").find(encoded(PlanMutation.serializer(), m))?.groupValues?.get(1) ?: ""
        }.toSet()
        assertEquals(
            setOf(
                "swap_exercise", "change_volume", "change_rep_range", "add_cardio", "remove_cardio", "change_goal", "change_schedule", "set_checkin_question",
                "exclude_exercise", "pin_exercise", "adjust_working_load", "set_priority_muscles", "schedule_deload", "set_limitation", "set_travel_days", "update_nutrition_targets",
            ),
            discriminators,
        )
        assertKeys(encoded(PlanMutation.serializer(), Fixtures.swapExercise), "type", "from_exercise_id", "to_exercise_id", "template_id", "scope", "rationale")
        assertKeys(encoded(PlanMutation.serializer(), Fixtures.changeVolume), "muscle", "delta_sets_per_week", "scope", "rationale")
        assertKeys(encoded(PlanMutation.serializer(), Fixtures.mutations[2]), "exercise_id", "rep_lo", "rep_hi")
        assertKeys(encoded(PlanMutation.serializer(), Fixtures.mutations[3]), "weekday", "minutes", "exercise_id", "kind")
        assertKeys(encoded(PlanMutation.serializer(), Fixtures.mutations[5]), "goal", "start_next_week")
        assertKeys(encoded(PlanMutation.serializer(), Fixtures.mutations[6]), "days_per_week", "session_minutes", "preferred_weekdays")
        assertKeys(encoded(PlanMutation.serializer(), Fixtures.mutations[7]), "question", "answer_type", "schedule", "weeks_active")
        assertKeys(encoded(PlanMutation.serializer(), Fixtures.mutations[8]), "exercise_id", "until")
        assertKeys(encoded(PlanMutation.serializer(), Fixtures.mutations[10]), "exercise_id", "gym_scope", "new_working_load_kg")
        assertKeys(encoded(PlanMutation.serializer(), Fixtures.mutations[12]), "start_this_week")
        assertKeys(encoded(PlanMutation.serializer(), Fixtures.mutations[13]), "limitation", "pain_0_10")
        assertKeys(encoded(PlanMutation.serializer(), Fixtures.mutations[14]), "from", "to", "gym_id")
        assertKeys(encoded(PlanMutation.serializer(), Fixtures.mutations[15]), "kcal_target", "protein_g_per_kg")
    }
    @Test fun planOverrideKeys() = assertKeys(encoded(PlanOverride.serializer(), Fixtures.planOverride), "id", "created_at", "source", "proposal_id", "mutation", "type", "expires_on", "schema_version")

    @Test fun planInputsKeys() = assertKeys(
        encoded(PlanInputs.serializer(), Fixtures.planInputs),
        "profile", "gyms", "default_gym_id", "overrides", "progression_states", "recent_sessions", "recent_sets", "body_metrics", "weekly_checkins", "readiness_history",
        "mesocycle_anchor", "week_of_mesocycle", "travel_weeks", "week_starts_on_monday", "mesocycles_completed", "last_deload_week_start", "deload_triggers_proposed", "deload_triggers_active", "pattern_last_trained",
    )
    @Test fun patternLastTrainedUsesPatternSerialNamesAsKeys() {
        val json = encoded(PlanInputs.serializer(), Fixtures.planInputs)
        assertTrue(json.contains("\"pattern_last_trained\":{\"squat\":\"2026-08-26\",\"hinge\":\"2026-07-27\"}"), json)
    }
    @Test fun plannerResultKeys() {
        assertKeys(encoded(PlanOutput.serializer(), Fixtures.planOutput), "mesocycle", "current_week_index", "explanations", "proposals")
        assertKeys(encoded(SessionCloseResult.serializer(), Fixtures.sessionCloseResult), "states", "adjustments", "flags")
        assertKeys(encoded(WeekRollResult.serializer(), Fixtures.weekRollResult), "week_start", "next_mode", "volume_holds", "deload_triggers", "proposals", "states", "ladder_notes")
        assertKeys(encoded(PlanDiff.serializer(), Fixtures.planDiff), "sessions_changed", "weekly_sets_before", "weekly_sets_after", "exercises_added", "exercises_removed", "warnings", "under_target_report")
    }

    @Test fun nutritionKeys() {
        assertKeys(encoded(TdeeAdjustment.serializer(), Fixtures.tdeeAdjustment), "date", "observed_rate_kg_per_week", "expected_rate_kg_per_week", "delta_kcal", "explanation")
        assertKeys(encoded(EnergyModel.serializer(), Fixtures.energyModel), "ree_kcal", "activity_factor", "tdee_estimate_kcal", "adjustments", "explanation")
        assertKeys(
            encoded(NutritionTargets.serializer(), Fixtures.nutritionTargets),
            "kcal", "protein_g", "carbs_g", "fat_g", "fibre_g", "water_l", "energy_delta_kcal", "goal_rate_bw_pct_per_week_lo", "goal_rate_bw_pct_per_week_hi", "next_review_date", "explanation",
        )
        assertKeys(encoded(WeightTrendPoint.serializer(), Fixtures.weightTrendPoint), "date", "raw_kg", "ma7_kg", "noise")
        assertKeys(encoded(NutritionState.serializer(), Fixtures.nutritionState), "tdee_estimate_kcal", "kcal_target_override", "protein_g_per_kg_override", "last_review_date", "deficit_started_on", "creatine_started_on", "updated_at")
    }

    @Test fun coachKeys() {
        assertKeys(
            encoded(ChatConversation.serializer(), Fixtures.chatConversation),
            "started_at", "backend", "model", "effort", "system_prompt_hash", "tool_contract_version", "seed_summary", "status", "assistant_turns", "last_input_tokens", "is_default", "schema_version",
        )
        assertKeys(encoded(ChatMessage.serializer(), Fixtures.chatMessage), "conversation_id", "seq", "role", "content_json", "display_text", "created_at", "usage", "request_id", "stop_reason", "caveat_appended", "tool_results_json")
        assertKeys(encoded(TokenUsage.serializer(), Fixtures.tokenUsage), "input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens", "thinking_tokens")
        assertKeys(
            encoded(Proposal.serializer(), Fixtures.proposal),
            "conversation_id", "assistant_message_id", "tool_name", "contract_version", "payload_json", "mutation", "rationale", "status", "validation_warnings", "validation_errors", "created_at", "expires_on", "result_sent", "schema_version",
        )
        assertKeys(encoded(SpendRecord.serializer(), Fixtures.spendRecord), "id", "at", "model", "usage", "cost_usd", "backend", "conversation_id")
    }

    @Test fun appSettingsKeys() = assertKeys(
        encoded(AppSettings.serializer(), Fixtures.appSettings),
        "unit_system", "backend_mode", "model_id", "effort", "monthly_budget_usd", "bridge_url", "show_experimental", "onboarding_done", "week_starts_on_monday",
        "planner_overrides_json", "coach_user_id", "readiness_reminder_time", "weekly_checkin_weekday", "keep_screen_on_in_session", "last_week_rollover",
        "catalog_version_seen", "mesocycle_anchor", "mesocycle_week_paused_at", "mesocycles_completed", "last_deload_week_start", "deload_triggers_proposed",
        "active_gym_id", "today_context_json", "session_draft_json", "onboarding_draft_json",
    )
    @Test fun chartKeys() = assertKeys(encoded(ChartSeries.serializer(), Fixtures.chartSeries), "name", "points", "date_epoch_day", "value", "low_confidence")
    @Test fun viewRowKeys() {
        assertKeys(encoded(MuscleBandRow.serializer(), Fixtures.muscleBandRow), "muscle", "tier", "target", "achieved_fractional", "planned_remaining", "direct", "rp_mev", "rp_mrv")
        assertKeys(encoded(SupplementAdvice.serializer(), Fixtures.supplementAdvice), "id", "name", "tier", "dose_text", "timing", "evidence", "citation", "gated", "notes", "legal_note")
    }

    @Test fun datesAndInstantsAreIsoStrings() {
        val json = encoded(BodyMetric.serializer(), Fixtures.bodyMetric)
        assertTrue(json.contains("\"date\":\"2026-09-05\""), json)
        assertTrue(json.contains("\"logged_at\":\"2026-09-05T06:40:00Z\""), json)
    }

    @Test fun muscleMapKeysUseSerialNames() {
        val json = encoded(VolumeSnapshot.serializer(), Fixtures.volumeSnapshot)
        assertTrue(json.contains("\"chest\":4.0"), json)
        assertTrue(json.contains("\"front_delts\":2.0"), json)
        assertFalse(json.contains("CHEST"), json)
    }

    @Test fun medicalFlagsSetUsesSerialNames() {
        val json = encoded(Profile.serializer(), Fixtures.profile)
        assertTrue(json.contains("\"medical_flags\":[\"other\"]"), json)
    }
}
