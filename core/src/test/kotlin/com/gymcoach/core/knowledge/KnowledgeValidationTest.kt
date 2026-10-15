package com.gymcoach.core.knowledge

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.CardioModalitiesFile
import com.gymcoach.core.model.CheckinQuestionsFile
import com.gymcoach.core.model.DrillsFile
import com.gymcoach.core.model.EquipmentCatalogFile
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.ExerciseLibraryFile
import com.gymcoach.core.model.IncrementDirection
import com.gymcoach.core.model.IncrementKind
import com.gymcoach.core.model.LoadTransferFile
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MuscleWeight
import com.gymcoach.core.model.NutritionTipsFile
import com.gymcoach.core.model.ProgressionKind
import com.gymcoach.core.model.RpLandmarksFile
import com.gymcoach.core.model.RuleEntry
import com.gymcoach.core.model.RulesFile
import com.gymcoach.core.model.SessionTemplate
import com.gymcoach.core.model.SplitTemplate
import com.gymcoach.core.model.SupplementsFile
import com.gymcoach.core.model.TemplatesFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** R4 §6.3 rules 1–8 + SPEC §3.9 rules 9–11 over the shipped assets, plus negative cases per rule. */
class KnowledgeValidationTest {
    private val kb = KnowledgeFixture.kb
    private val frozen = KnowledgeFixture.frozenIdsV1

    private fun copy(
        exercises: Map<String, Exercise> = kb.exercises,
        templates: Map<String, SessionTemplate> = kb.templates,
        splits: List<SplitTemplate> = kb.splits,
        rules: RuleBook = kb.rules,
        renamed: Map<String, String> = kb.renamedExerciseIds,
    ) = KnowledgeBase(
        exercises, exercises.keys.toList(), kb.repProfiles, kb.equipment, kb.groups, kb.presets, renamed, kb.renamedEquipmentIds,
        templates, splits, rules, kb.drills, kb.checkinQuestions, kb.supplements, kb.cardioModalities, kb.nutritionTips, kb.rpLandmarks, kb.loadTransfer,
    )

    private fun withExercise(e: Exercise): Map<String, Exercise> = LinkedHashMap(kb.exercises).also { it[e.id] = e }

    @Test
    fun shippedAssetsAreValidUnderRules1To11() {
        val violations = KnowledgeValidator(kb, frozen).validate()
        assertEquals(emptyList(), violations)
    }

    @Test
    fun everyAssetFileDecodesStrictly() {
        val src = ClasspathKnowledgeSource()
        val json = CoreJson.assets
        json.decodeFromString(ExerciseLibraryFile.serializer(), src.readText("exercises.json"))
        json.decodeFromString(EquipmentCatalogFile.serializer(), src.readText("equipment.json"))
        json.decodeFromString(TemplatesFile.serializer(), src.readText("templates.json"))
        json.decodeFromString(RulesFile.serializer(), src.readText("rules.json"))
        json.decodeFromString(DrillsFile.serializer(), src.readText("mobility_drills.json"))
        json.decodeFromString(CheckinQuestionsFile.serializer(), src.readText("checkin_questions.json"))
        json.decodeFromString(SupplementsFile.serializer(), src.readText("supplements.json"))
        json.decodeFromString(CardioModalitiesFile.serializer(), src.readText("cardio_modalities.json"))
        json.decodeFromString(NutritionTipsFile.serializer(), src.readText("nutrition_rules.json"))
        json.decodeFromString(RpLandmarksFile.serializer(), src.readText("rp_landmarks.json"))
        json.decodeFromString(LoadTransferFile.serializer(), src.readText("load_transfer.json"))
    }

    @Test
    fun libraryRoundTripsWithoutLoss() {
        val json = CoreJson.assets
        val file = json.decodeFromString(ExerciseLibraryFile.serializer(), ClasspathKnowledgeSource().readText("exercises.json"))
        val again = json.decodeFromString(ExerciseLibraryFile.serializer(), json.encodeToString(ExerciseLibraryFile.serializer(), file))
        assertEquals(file, again)
        val eq = json.decodeFromString(EquipmentCatalogFile.serializer(), ClasspathKnowledgeSource().readText("equipment.json"))
        assertEquals(eq, json.decodeFromString(EquipmentCatalogFile.serializer(), json.encodeToString(EquipmentCatalogFile.serializer(), eq)))
    }

    @Test
    fun frozenIdListMatchesTheLibrary() {
        assertEquals(156, frozen.size)
        assertEquals(frozen.toSet(), kb.exercises.keys)
    }

    @Test
    fun spFlagImpliesRackOrSmithOptionAndBenchVariantsNameRackOrSpotter() {
        for (e in kb.exercises.values.filter { it.flags.requiresSpotterOrSafeties }) {
            assertTrue(e.equipmentOptions.any { o -> o.allOf.any { it == "rack_or_spotter" || it == "rack_any" || it == "smith_machine" } }, e.id)
        }
        for (id in KnowledgeValidator.BAR_OVER_FACE) {
            val e = kb.requireExercise(id)
            for (o in e.equipmentOptions.filter { (it.loadType ?: e.loadType) != LoadType.SMITH }) assertTrue("rack_or_spotter" in o.allOf, "$id ${o.allOf}")
        }
        // squat family and good morning use rack_any (the RIR-floor path of §3.1), as printed in R4.
        assertEquals(listOf("bb_loaded", "rack_any"), kb.requireExercise("back_squat").equipmentOptions.single().allOf)
        assertEquals(listOf("bb_loaded", "rack_any"), kb.requireExercise("good_morning").equipmentOptions.single().allOf)
    }

    @Test
    fun r4RowsTranscribedExactly() {
        val bench = kb.requireExercise("barbell_bench_press")
        assertEquals(listOf(MuscleWeight(Muscle.CHEST, 1.0), MuscleWeight(Muscle.FRONT_DELTS, 0.5), MuscleWeight(Muscle.TRICEPS, 0.5)), bench.muscles)
        assertEquals(listOf("dumbbell_bench_press", "smith_bench_press", "machine_chest_press", "push_up"), bench.substitutions)
        assertEquals(2.5, bench.increment.defaultKg); assertEquals(1.0, bench.increment.minKg)
        assertEquals(ProgressionKind.LINEAR_LOAD, bench.progression)
        assertTrue(bench.flags.compound && bench.flags.shoulderSensitive && bench.flags.requiresSpotterOrSafeties && !bench.flags.spinalLoading)
        assertTrue("big_lift" in bench.tags && "exrx_verified" in bench.tags)

        val squat = kb.requireExercise("back_squat")
        assertEquals(listOf(1.0, 0.5, 0.5, 0.25, 0.25), squat.muscles.map { it.weight })
        assertEquals(listOf("hack_squat", "leg_press_45", "smith_machine_squat", "safety_bar_squat", "goblet_squat", "belt_squat"), squat.substitutions)
        assertTrue(squat.flags.spinalLoading && squat.flags.kneeSensitive && squat.flags.lowBackSensitive && squat.flags.requiresSpotterOrSafeties)

        val preacher = kb.requireExercise("preacher_curl")
        assertEquals(listOf(listOf("preacher_bench", "ez_loaded"), listOf("preacher_bench", "dumbbells"), listOf("preacher_curl_machine")), preacher.equipmentOptions.map { it.allOf })
        assertEquals(listOf(null, LoadType.DUMBBELL, LoadType.MACHINE), preacher.equipmentOptions.map { it.loadType })
        assertEquals(listOf(true, false, false), preacher.equipmentOptions.map { it.preferred })

        val assisted = kb.requireExercise("assisted_pull_up")
        assertEquals(IncrementDirection.ASSIST, assisted.increment.direction)
        assertEquals(5.0, assisted.increment.defaultKg)

        assertEquals(IncrementKind.BAND_LEVEL, kb.requireExercise("band_pull_apart").increment.kind)
        assertEquals(IncrementKind.TIME, kb.requireExercise("plank").increment.kind)
        assertEquals(IncrementKind.BOX_HEIGHT, kb.requireExercise("box_jump").increment.kind)
        assertEquals(IncrementKind.NONE, kb.requireExercise("treadmill").increment.kind)
        assertEquals(emptyList(), kb.requireExercise("push_up").equipmentOptions.single().allOf)
        assertTrue(kb.requireExercise("landmine_press").unilateral)
        assertEquals(ProgressionKind.DISTANCE_OR_TIME, kb.requireExercise("farmers_carry").progression)
        assertEquals(ProgressionKind.TIME_PROGRESSION, kb.requireExercise("plank").progression)
        assertEquals(ProgressionKind.BAND_PROGRESSION, kb.requireExercise("band_lateral_raise").progression)
        assertEquals(ProgressionKind.REP_PROGRESSION, kb.requireExercise("pull_up").progression)
        assertEquals(ProgressionKind.DOUBLE_PROGRESSION, kb.requireExercise("dumbbell_bench_press").progression)
        assertEquals(ProgressionKind.NONE, kb.requireExercise("cat_cow").progression)
        assertEquals(3, kb.requireExercise("nordic_hamstring_curl").repOverrides.getValue("strength").range.lo)
        assertTrue(kb.requireExercise("cable_fly").increment.perHand)
    }

    @Test
    fun sixBigLiftsAndWarmupTags() {
        assertEquals(setOf("back_squat", "safety_bar_squat", "conventional_deadlift", "trap_bar_deadlift", "barbell_bench_press", "overhead_press_barbell"),
            kb.exercises.values.filter { "big_lift" in it.tags }.map { it.id }.toSet())
        assertTrue(kb.exercises.values.filter { it.pattern == MovementPattern.MOBILITY }.all { "warmup" in it.tags })
        assertEquals(37, kb.exercises.values.count { "exrx_verified" in it.tags }, "daggered rows of R4 §5.6–5.11")
    }

    @Test
    fun repProfilesCarrySetsBands() {
        val expected = mapOf("barbell_compound" to (3 to 5), "compound" to (3 to 4), "isolation" to (2 to 4), "isolation_light" to (2 to 4),
            "bodyweight_progression" to (3 to 4), "core_reps" to (2 to 3), "core_time" to (2 to 3), "carry" to (2 to 3), "power" to (3 to 5),
            "cardio" to (1 to 1), "mobility" to (2 to 2))
        for ((id, band) in expected) {
            val p = kb.repProfiles.getValue(id)
            assertEquals(4, p.byGoal.size, id)
            // SPEC §3.1 example: barbell_compound endurance carries [2, 3]
            for ((goal, g) in p.byGoal) if (!(id == "barbell_compound" && goal == "endurance")) assertEquals(band, g.sets!!.lo to g.sets!!.hi, "$id.$goal")
        }
        val h = kb.repProfiles.getValue("compound").byGoal.getValue("hypertrophy")
        assertEquals(1 to 3, h.rir.lo to h.rir.hi); assertEquals(1 to 1, h.rirLastWeek!!.lo to h.rirLastWeek!!.hi)
    }

    @Test
    fun rule9RemovedIdWithoutRenameIsReported() {
        val without = LinkedHashMap(kb.exercises).also { it.remove("dead_bug") }
        val v = KnowledgeValidator(copy(exercises = without), frozen).validate()
        assertTrue(v.any { it.startsWith("R9") && "dead_bug" in it }, v.toString())
        val renamed = copy(exercises = without, renamed = mapOf("dead_bug" to "plank"))
        assertTrue(KnowledgeValidator(renamed, frozen).validate().none { it.startsWith("R9") })
    }

    @Test
    fun rule10BackoffOutsideTheRowIsReported() {
        val split = kb.splits.first { it.kind == com.gymcoach.core.model.SplitKind.UPPER_LOWER_4 && com.gymcoach.core.model.Goal.STRENGTH in it.forGoals }
        val broken = split.copy(sessionTemplateIds = listOf("UPPER_A", "LOWER_A_S", "UPPER_B_S", "LOWER_B_S"))
        val v = KnowledgeValidator(copy(splits = kb.splits.map { if (it == split) broken else it }), frozen).validate()
        assertTrue(v.any { it.startsWith("R10") && "UPPER_B_S.s2" in it }, v.toString())
    }

    @Test
    fun rule10BackoffToANonHeavySlotIsReported() {
        val t = kb.templates.getValue("UPPER_A_S4")
        val bad = t.copy(slots = t.slots.map { if (it.slotId == "s3") it.copy(backoffOf = "UPPER_B_S.s1") else it })
        val v = KnowledgeValidator(copy(templates = LinkedHashMap(kb.templates).also { it["UPPER_A_S4"] = bad }), frozen).validate()
        assertTrue(v.any { it.startsWith("R10") && "not a heavy slot" in it }, v.toString())
    }

    @Test
    fun rule11MissingRuleIsReported() {
        val rules = LinkedHashMap<String, RuleEntry>(kb.rules.entries).also { it.remove("NUT-18") }
        val v = KnowledgeValidator(copy(rules = RuleBook(rules)), frozen).validate()
        assertTrue(v.any { it.startsWith("R11") && "NUT-18" in it }, v.toString())
    }

    @Test
    fun rules1And5And6NegativeCases() {
        val e = kb.requireExercise("dumbbell_curl")
        val bad = e.copy(substitutions = listOf("no_such_exercise"), muscles = listOf(MuscleWeight(Muscle.BICEPS, 0.75)),
            equipmentOptions = listOf(e.equipmentOptions.first().copy(allOf = listOf("unknown_thing"))))
        val v = KnowledgeValidator(copy(exercises = withExercise(bad)), frozen).validate()
        assertTrue(v.any { it.startsWith("R1") && "no_such_exercise" in it }, v.toString())
        assertTrue(v.any { it.startsWith("R1") && "unknown_thing" in it }, v.toString())
        assertTrue(v.any { it.startsWith("R5") && "dumbbell_curl" in it }, v.toString())
        assertTrue(v.any { it.startsWith("R3") && "dumbbell_curl" in it }, v.toString())

        val squat = kb.requireExercise("back_squat")
        val noRack = squat.copy(equipmentOptions = listOf(squat.equipmentOptions.first().copy(allOf = listOf("bb_loaded"))))
        val v2 = KnowledgeValidator(copy(exercises = withExercise(noRack)), frozen).validate()
        assertTrue(v2.any { it.startsWith("R6") && "back_squat" in it }, v2.toString())
    }

    @Test
    fun rule3And4TravelViability() {
        val v = KnowledgeValidator(kb, frozen).validate()
        assertTrue(v.none { it.startsWith("R3") || it.startsWith("R4") })
        val optional = kb.exercises.values.filter { KnowledgeValidator.TAG_OPTIONAL in it.tags }.map { it.id }.toSet()
        assertTrue("pendulum_squat" in optional && "machine_crunch" in optional && "back_squat" !in optional)
    }

    @Test
    fun contentTablesChk01Nut02Nut03Nut12Nut14AndSup03To11ArePresent() {
        assertEquals(15, kb.checkinQuestions.size)
        val tipRules = kb.nutritionTips.map { it.ruleId }.toSet()
        assertTrue(tipRules.containsAll(listOf(RuleIds.NUT_02, RuleIds.NUT_03, RuleIds.NUT_12, RuleIds.NUT_14)))
        val ids = kb.supplements.entries.map { it.id }.toSet()
        assertTrue(ids.containsAll(listOf(RuleIds.SUP_03, RuleIds.SUP_06, RuleIds.SUP_07, RuleIds.SUP_09)))
        assertTrue(kb.supplements.entries.any { it.id.startsWith(RuleIds.SUP_11 + ".") && it.tier == "C" })
        assertEquals(false, kb.rules.get(RuleIds.NUT_18)?.userAdjustable)
        assertTrue(KnowledgeValidator(kb, frozen).validate().none { it.startsWith("X ") })
    }

    @Test
    fun contentTablesMissingRowsAreReported() {
        val stripped = KnowledgeBase(
            kb.exercises, kb.exerciseOrder, kb.repProfiles, kb.equipment, kb.groups, kb.presets, kb.renamedExerciseIds, kb.renamedEquipmentIds,
            kb.templates, kb.splits, kb.rules, kb.drills,
            kb.checkinQuestions.filter { it.id != "Q9" },
            kb.supplements.copy(entries = kb.supplements.entries.filter { it.id != RuleIds.SUP_09 && !it.id.startsWith(RuleIds.SUP_11) }),
            kb.cardioModalities,
            kb.nutritionTips.filter { it.ruleId != RuleIds.NUT_12 },
            kb.rpLandmarks, kb.loadTransfer,
        )
        val v = KnowledgeValidator(stripped, frozen).validate()
        assertTrue(v.any { it.contains("CHK-01") && it.contains("Q9") }, v.toString())
        assertTrue(v.any { it.contains("NUT-12") }, v.toString())
        assertTrue(v.any { it.contains("SUP-09") }, v.toString())
        assertTrue(v.any { it.contains("SUP-11") }, v.toString())
    }
}
