package com.gymcoach.core.model

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * One fully-populated instance of every §2 data class (non-default values wherever possible so that a
 * wrong `@SerialName` or a dropped field shows up in the round-trip and key tests). Shared by the WP-01
 * tests; other work packages may read it. Deterministic (fixed date/instant, no Random).
 */
object Fixtures {
    val date: LocalDate = LocalDate.of(2026, 9, 5)
    val instant: Instant = Instant.parse("2026-09-05T06:40:00Z")

    // --- Values ---
    val band: Band = Band(8, 12)
    val explanation: Explanation = Explanation(
        ruleIds = listOf("VOL-04", "SEL"),
        message = "12 fractional sets/week for chest (intermediate, tier 1)",
        inputs = mapOf("tier" to "tier_1", "training_age" to "intermediate"),
        confidence = Confidence.WEAK,
    )
    val enteredWeight: EnteredWeight = EnteredWeight(entered = 135.0, unit = WeightUnit.LB, kg = 61.23496995)
    val enteredLength: EnteredLength = EnteredLength(entered = 70.0, unit = LengthUnit.IN, cm = 177.8)

    // --- Knowledge ---
    val muscleWeight: MuscleWeight = MuscleWeight(Muscle.CHEST, 1.0)
    val equipmentOption: EquipmentOption = EquipmentOption(
        allOf = listOf("barbell", "flat_bench", "rack_or_spotter"),
        loadType = LoadType.BARBELL,
        note = "preferred option",
        preferred = true,
    )
    val goalPrescription: GoalPrescription = GoalPrescription(
        range = Band(6, 10), rir = Band(1, 3), rirLastWeek = Band(0, 2), sets = Band(3, 5), note = "moderate day",
    )
    val repProfile: RepProfile = RepProfile(
        id = "barbell_compound",
        unit = RepUnit.REPS,
        byGoal = mapOf(
            "strength" to GoalPrescription(range = Band(3, 6), rir = Band(2, 4)),
            "hypertrophy" to goalPrescription,
            "endurance" to GoalPrescription(range = Band(12, 20), rir = Band(2, 3)),
            "general_fitness" to GoalPrescription(range = Band(6, 12), rir = Band(2, 3)),
        ),
        notes = "LOAD-03 barbell row",
        sources = listOf("Halperin 2022"),
    )
    val increment: Increment = Increment(
        kind = IncrementKind.KG, defaultKg = 2.5, minKg = 1.25, perHand = false, direction = IncrementDirection.LOAD, note = "microplates if present",
    )
    val exerciseFlags: ExerciseFlags = ExerciseFlags(
        compound = true, spinalLoading = false, shoulderSensitive = true, kneeSensitive = false, lowBackSensitive = false, requiresSpotterOrSafeties = true,
    )
    val exerciseContent: ExerciseContent = ExerciseContent(
        description = "Horizontal press with a barbell.",
        setup = listOf("Eyes under the bar", "Feet flat"),
        cues = listOf("Elbows ~45°", "Bar to lower chest", "Drive through the floor"),
        mistakes = listOf("Bouncing", "Flaring elbows"),
        safety = listOf("Use safeties or a spotter"),
        substitutionRationale = mapOf("dumbbell_bench_press" to "same pattern, no rack needed"),
        sources = listOf("Schoenfeld 2017"),
        reviewNotes = "reviewed by author",
    )
    val exercise: Exercise = Exercise(
        id = "barbell_bench_press",
        name = "Barbell bench press",
        aliases = listOf("Bankdrücken", "flat bench"),
        pattern = MovementPattern.HORIZONTAL_PUSH,
        muscles = listOf(MuscleWeight(Muscle.CHEST, 1.0), MuscleWeight(Muscle.TRICEPS, 0.5), MuscleWeight(Muscle.FRONT_DELTS, 0.5)),
        equipmentOptions = listOf(equipmentOption, EquipmentOption(allOf = listOf("smith_machine", "flat_bench"), loadType = LoadType.SMITH)),
        loadType = LoadType.BARBELL,
        unilateral = false,
        difficulty = Difficulty.INTERMEDIATE,
        repProfile = "barbell_compound",
        repOverrides = mapOf("strength" to GoalPrescription(range = Band(3, 5), rir = Band(2, 3))),
        increment = increment,
        progression = ProgressionKind.DOUBLE_PROGRESSION,
        substitutions = listOf("dumbbell_bench_press", "machine_chest_press"),
        flags = exerciseFlags,
        batch = 1,
        tags = listOf("main_lift"),
        sources = listOf("R4 §5.1"),
        content = exerciseContent,
        contentStatus = ContentStatus.DRAFT,
    )
    val isolationExercise: Exercise = Exercise(
        id = "cable_curl",
        name = "Cable curl",
        pattern = MovementPattern.ELBOW_FLEXION,
        muscles = listOf(MuscleWeight(Muscle.BICEPS, 1.0), MuscleWeight(Muscle.FOREARMS, 0.25)),
        equipmentOptions = listOf(EquipmentOption(allOf = listOf("cable_station"))),
        loadType = LoadType.CABLE,
        unilateral = false,
        difficulty = Difficulty.BEGINNER,
        repProfile = "isolation",
        increment = Increment(kind = IncrementKind.KG, defaultKg = 2.5),
        progression = ProgressionKind.DOUBLE_PROGRESSION,
        flags = ExerciseFlags(false, false, false, false, false, false),
        batch = 3,
    )
    val cardioExercise: Exercise = Exercise(
        id = "stationary_bike",
        name = "Stationary bike",
        pattern = MovementPattern.CARDIO,
        equipmentOptions = listOf(EquipmentOption(allOf = listOf("bike"))),
        loadType = LoadType.CARDIO,
        unilateral = false,
        difficulty = Difficulty.BEGINNER,
        repProfile = "cardio_minutes",
        increment = Increment(kind = IncrementKind.TIME),
        progression = ProgressionKind.DISTANCE_OR_TIME,
        flags = ExerciseFlags(false, false, false, false, false, false),
        batch = 6,
    )
    val exerciseLibraryFile: ExerciseLibraryFile = ExerciseLibraryFile(
        schemaVersion = 1,
        renamedIds = mapOf("bench_press" to "barbell_bench_press"),
        repProfiles = listOf(repProfile),
        exercises = listOf(exercise, isolationExercise, cardioExercise),
    )
    val contentOverlay: ContentOverlay = ContentOverlay(
        schemaVersion = 1, batch = 1, entries = mapOf("barbell_bench_press" to exerciseContent), status = mapOf("barbell_bench_press" to ContentStatus.DRAFT),
    )
    val paramSpec: ParamSpec = ParamSpec(
        key = "max_kg", type = "number", unit = "kg", default = JsonPrimitive(40), min = 1.0, max = 100.0, description = "heaviest pair",
    )
    val equipmentDef: EquipmentDef = EquipmentDef(
        id = "dumbbells",
        name = "Dumbbells",
        category = EquipmentCategory.FREE_WEIGHTS,
        aliases = listOf("Kurzhanteln", "DBs"),
        description = "A rack of fixed dumbbells.",
        params = listOf(
            paramSpec,
            ParamSpec(key = "has_microplates", type = "boolean", default = JsonPrimitive(false)),
            ParamSpec(key = "available_kg", type = "number_list", unit = "kg", default = buildJsonArray { add(JsonPrimitive(12)); add(JsonPrimitive(16)) }),
        ),
        sessionFlag = false,
        packable = false,
        defaultIn = listOf("base_gym", "home_gym"),
        notes = "step_kg 2.5 typical",
    )
    val equipmentGroup: EquipmentGroup = EquipmentGroup(
        id = "pullup_bar_any", kind = GroupKind.ANY_OF, name = "Any pull-up bar", members = listOf("pullup_bar", "power_rack"), notes = "honours has_pullup_bar",
    )
    val presetItem: PresetItem = PresetItem(equipmentId = "dumbbells", params = mapOf("max_kg" to JsonPrimitive(20)))
    val equipmentPreset: EquipmentPreset = EquipmentPreset(
        id = "hotel_gym", name = "Hotel gym", description = "Typical hotel fitness room", defaultSessionMinutes = 45, items = listOf(presetItem),
    )
    val equipmentCatalogFile: EquipmentCatalogFile = EquipmentCatalogFile(
        schemaVersion = 1, renamedIds = mapOf("db_rack" to "dumbbells"), equipment = listOf(equipmentDef), groups = listOf(equipmentGroup), presets = listOf(equipmentPreset),
    )
    val ruleEntry: RuleEntry = RuleEntry(
        id = "VOL-04",
        section = "Resistance training / Weekly volume",
        statement = "Hypertrophy sets/muscle/week: novice 8–12, intermediate 12–18, advanced 15–25",
        confidence = Confidence.MODERATE,
        citation = "Schoenfeld 2017; Pelland 2025",
        url = "https://example.org/vol-04",
        constants = mapOf("intermediate.lo" to 12.0, "intermediate.hi" to 18.0),
        userAdjustable = true,
        note = "tier convention is weak",
    )
    val rulesFile: RulesFile = RulesFile(schemaVersion = 1, rules = listOf(ruleEntry))
    val drill: Drill = Drill(
        id = "hip_90_90",
        name = "90/90 hip switch",
        joint = Joint.HIPS,
        kind = DrillKind.DYNAMIC,
        patterns = listOf(MovementPattern.SQUAT, MovementPattern.HINGE),
        limitationRegions = listOf(LimitationRegion.HIP),
        doseReps = Band(8, 10),
        doseSeconds = null,
        exerciseId = "hip_90_90_switch",
        sources = listOf("Behm 2016"),
    )
    val drillsFile: DrillsFile = DrillsFile(schemaVersion = 1, drills = listOf(drill))
    val slotTemplate: SlotTemplate = SlotTemplate(
        slotId = "u1",
        role = SlotRole.PRIMARY,
        patterns = listOf(MovementPattern.HORIZONTAL_PUSH, MovementPattern.INCLINE_PUSH),
        heavy = true,
        preferLoadTypes = listOf(LoadType.BARBELL),
        targetMuscles = listOf(Muscle.CHEST),
        required = true,
        supersetWith = "u2",
        backoffOf = "upper_b_s.s3",
    )
    val sessionTemplate: SessionTemplate = SessionTemplate(id = "upper_a", name = "Upper A", slots = listOf(slotTemplate))
    val splitTemplate: SplitTemplate = SplitTemplate(
        kind = SplitKind.UPPER_LOWER_4,
        daysPerWeek = 4,
        sessionTemplateIds = listOf("upper_a", "lower_a", "upper_b", "lower_b"),
        defaultWeekdays = listOf(1, 2, 4, 5),
        forGoals = listOf(Goal.HYPERTROPHY, Goal.STRENGTH),
        forTrainingAges = listOf(TrainingAge.INTERMEDIATE),
    )
    val templatesFile: TemplatesFile = TemplatesFile(schemaVersion = 1, sessionTemplates = listOf(sessionTemplate), splits = listOf(splitTemplate))
    val checkinQuestionDef: CheckinQuestionDef = CheckinQuestionDef(
        id = "Q1",
        text = "How many hours did you sleep on average?",
        answerType = CheckinAnswerType.NUMBER,
        autoSource = "weight_trend",
        triggerRuleIds = listOf("SLP-03"),
        triggerCondition = "7-day average < 7 h",
        onlyInDeficit = false,
        alwaysAsk = true,
    )
    val checkinQuestionsFile: CheckinQuestionsFile = CheckinQuestionsFile(schemaVersion = 1, questions = listOf(checkinQuestionDef))
    val rpLandmark: RpLandmark = RpLandmark(
        muscle = Muscle.CHEST, mv = Band(4, 6), mev = Band(6, 8), mav = Band(12, 20), mrv = Band(20, 22), sessionsPerWeek = Band(2, 3),
    )
    val rpLandmarksFile: RpLandmarksFile = RpLandmarksFile(schemaVersion = 1, source = "RP landmarks", fetchedOn = "2026-09-04", landmarks = listOf(rpLandmark))
    val supplementEntry: SupplementEntry = SupplementEntry(
        id = "SUP-01",
        name = "Creatine monohydrate",
        tier = "A",
        dose = "3–5 g/day",
        timing = "any time",
        evidence = Confidence.STRONG,
        citation = "Kreider 2017",
        notes = listOf("water-weight +1 kg is normal"),
        doNotReason = null,
        gatedByMedical = listOf(MedicalFlag.KIDNEY_DISEASE),
        legalNote = "third-party tested products",
    )
    val supplementsFile: SupplementsFile = SupplementsFile(
        schemaVersion = 1,
        caveatSup12 = listOf("General education, not medical advice."),
        priorityOrder = "protein → calories → sleep → consistency → creatine/caffeine",
        entries = listOf(supplementEntry),
    )
    val cardioModalityEntry: CardioModalityEntry = CardioModalityEntry(
        exerciseId = "stationary_bike", zone2Control = "good", hiitSuitability = "high", legDayAdjacency = "good", isRunning = false, kneeImpact = false,
    )
    val cardioModalitiesFile: CardioModalitiesFile = CardioModalitiesFile(schemaVersion = 1, modalities = listOf(cardioModalityEntry))
    val nutritionTip: NutritionTip = NutritionTip(
        id = "tip_protein_spread", ruleId = "NUT-02", title = "Spread protein", body = "0.4 g/kg per meal × 4", confidence = Confidence.MODERATE, citation = "Schoenfeld & Aragon 2018",
    )
    val nutritionTipsFile: NutritionTipsFile = NutritionTipsFile(schemaVersion = 1, tips = listOf(nutritionTip))
    val loadTransferSeed: LoadTransferSeed = LoadTransferSeed(
        fromExerciseId = "barbell_bench_press", toExerciseId = "dumbbell_bench_press", factor = 0.35, note = "per hand",
    )
    val bodyweightSeed: BodyweightSeed = BodyweightSeed(pattern = MovementPattern.SQUAT, loadType = LoadType.BARBELL, fraction = 0.5, perHand = false)
    val loadTransferFile: LoadTransferFile = LoadTransferFile(
        schemaVersion = 1,
        unilateralFactor = 0.5,
        seeds = listOf(loadTransferSeed),
        bodyweightSeeds = listOf(bodyweightSeed),
        femaleFactor = 0.6,
        intermediateFactor = 1.4,
        advancedFactor = 1.7,
    )

    // --- Profile / gyms ---
    val limitation: Limitation = Limitation(
        id = "lim1", region = LimitationRegion.KNEE, side = Side.LEFT, pain0to10 = 2, since = date.minusDays(30), active = true, limitedRange = true, notes = "old meniscus",
    )
    val profile: Profile = Profile(
        sex = Sex.MALE,
        birthYear = 1988,
        height = enteredLength,
        trainingMonthsConsistent = 18,
        layoffMonths = 2,
        monthsRetrainedSinceLayoff = 1,
        goal = Goal.HYPERTROPHY,
        daysPerWeek = 4,
        sessionMinutes = 60,
        preferredWeekdays = listOf(1, 2, 4, 5),
        limitations = listOf(limitation),
        medicalFlags = setOf(MedicalFlag.OTHER),
        clearanceConfirmedOn = date,
        priorityMuscles = listOf(Muscle.CHEST, Muscle.SIDE_DELTS),
        likedExerciseIds = listOf("barbell_bench_press"),
        dislikedExerciseIds = listOf("good_morning"),
        knownLoadsKg = mapOf("barbell_bench_press" to 80.0),
        allowAdvancedExercises = true,
        bodyFatPct = 18.5,
        physicalJob = true,
        activityFactorOverride = 1.55,
        hasHrMonitor = true,
        measuredHrMax = 185,
        wakeTimeLocal = "06:00",
        bedtimeLocal = "22:30",
        usualSessionTime = "morning",
        cardioInsideSessionMinutes = true,
        updatedAt = instant,
    )
    val bodyMetric: BodyMetric = BodyMetric(
        id = "bm1", date = date, weight = enteredWeight, waistCm = 84.5, restingHr = 58, steps = 8000, note = "after coffee", loggedAt = instant,
    )
    val gymItem: GymItem = GymItem(equipmentId = "dumbbells", params = mapOf("max_kg" to JsonPrimitive(40), "step_kg" to JsonPrimitive(2.5)))
    val gymSet: GymSet = GymSet(
        id = "gym1",
        name = "Home gym",
        presetOrigin = "home_gym",
        items = listOf(gymItem, GymItem(equipmentId = "barbell"), GymItem(equipmentId = "power_rack", params = mapOf("has_safeties" to JsonPrimitive(true)))),
        defaultSessionMinutes = 60,
        colorIndex = 2,
        travelKitMerged = true,
        notes = "garage",
        isDefault = true,
        archived = false,
        createdAt = instant,
        lastUsedAt = instant,
    )
    val readinessCheckin: ReadinessCheckin = ReadinessCheckin(
        id = "r1",
        date = date,
        quickMode = true,
        sleepHours = 6.5,
        sleepQuality = null,
        soreness = 2,
        sorenessRegion = Muscle.QUADS,
        stress = null,
        energy = 3,
        motivation = 4,
        restingHr = 58,
        jointPain0to10 = 1,
        jointRegion = LimitationRegion.KNEE,
        redFlagReported = false,
        assumedFields = listOf("sleep_quality", "stress"),
        score = 56,
        band = ReadinessBand.AMBER,
        explanation = explanation,
        loggedAt = instant,
    )
    val todayContext: TodayContext = TodayContext(
        date = date,
        gymId = "gym1",
        extraItems = listOf(GymItem(equipmentId = "resistance_bands")),
        removedEquipmentIds = listOf("leg_press_45"),
        spotter = true,
        minutesOverride = 45,
        readiness = readinessCheckin,
        ambientCold = true,
        minutesSinceWaking = 30,
        illnessSymptoms = false,
        alcoholDrinksLastNight = 1,
    )

    // --- Check-ins ---
    val checkinQuestion: CheckinQuestion = CheckinQuestion(
        id = "C-abc",
        text = "Did you hit your protein target most days?",
        answerType = CheckinAnswerType.YESNO,
        schedule = CheckinSchedule.ONCE,
        onDate = date,
        triggerRuleIds = listOf("NUT-01"),
        addedBy = OverrideSource.CHAT,
        proposalId = "toolu_01",
        activeFrom = date,
        activeUntil = date.plusWeeks(4),
        answered = true,
        answer = "yes",
        answeredAt = instant,
    )
    val checkinAnswer: CheckinAnswer = CheckinAnswer(questionId = "Q1", number = 7.0, yes = true, text = "fine")
    val weeklyCheckin: WeeklyCheckin = WeeklyCheckin(
        id = "wc1", weekStart = date.minusDays(5), answers = listOf(checkinAnswer), autoAnswered = listOf("Q3"), triggered = listOf(explanation),
        holds = listOf(Muscle.CHEST, Muscle.QUADS), completedAt = instant,
    )

    // --- Plan ---
    val plannedSlot: PlannedSlot = PlannedSlot(
        slotId = "u1", role = SlotRole.PRIMARY, pattern = MovementPattern.HORIZONTAL_PUSH, exerciseId = "barbell_bench_press", optionIndex = 0, sets = 4, heavy = true, supersetGroup = "ss1", explanation = explanation,
    )
    val cardioPrescription: CardioPrescription = CardioPrescription(
        exerciseId = "stationary_bike", minutes = 25, kind = CardioKind.ZONE2, hrLow = 109, hrHigh = 127, rpeLow = 11, rpeHigh = 13, intervals = null, placement = CardioPlacement.FINISHER, weekday = 2, explanation = explanation,
    )
    val volumeFractional: Map<Muscle, Double> = mapOf(Muscle.CHEST to 4.0, Muscle.TRICEPS to 2.0, Muscle.FRONT_DELTS to 2.0)
    val volumeDirect: Map<Muscle, Int> = mapOf(Muscle.CHEST to 4)
    val plannedSession: PlannedSession = PlannedSession(
        id = "meso1:w1:upper_a", templateId = "upper_a", name = "Upper A", weekday = 1, slots = listOf(plannedSlot), estimatedMinutes = 48.37, volumeFractional = volumeFractional, volumeDirect = volumeDirect,
    )
    val mesoWeek: MesoWeek = MesoWeek(
        index = 1, startDate = date, mode = WeekMode.NORMAL, rirOffset = 1, targets = mapOf(Muscle.CHEST to 12.0, Muscle.QUADS to 12.0), sessions = listOf(plannedSession), cardio = listOf(cardioPrescription), notes = listOf(explanation),
    )
    val mesocycle: Mesocycle = Mesocycle(
        id = "meso1",
        startDate = date,
        goal = Goal.HYPERTROPHY,
        trainingAge = TrainingAge.INTERMEDIATE,
        split = SplitKind.UPPER_LOWER_4,
        gymId = "gym1",
        weeks = listOf(mesoWeek, mesoWeek.copy(index = 2, startDate = date.plusWeeks(1), rirOffset = 0)),
        deloadWeekIndex = 5,
        inputsHash = "0123456789abcdef",
        underTargetReport = listOf(explanation),
        explanations = listOf(explanation),
        schemaVersion = 1,
    )
    val drillPrescription: DrillPrescription = DrillPrescription(drillId = "hip_90_90", sets = 2, reps = Band(8, 10), seconds = null, explanation = explanation)
    val warmup: Warmup = Warmup(raiseMinutes = 5, raiseExerciseId = "stationary_bike", drills = listOf(drillPrescription), totalMinutes = 9, explanation = explanation)
    val progressionKey: ProgressionKey = ProgressionKey(exerciseId = "leg_press_45", gymScope = "gym1")
    val setPrescription: SetPrescription = SetPrescription(
        index = 1, kind = SetKind.WORKING, reps = Band(8, 10), seconds = null, metres = null, loadKg = 82.5, loadLabel = "82.5 kg", perHand = false, rir = 2, restSeconds = 150, loadSource = LoadSource.PROGRESSION_STATE,
    )
    val exerciseBlock: ExerciseBlock = ExerciseBlock(
        blockId = "b1",
        slotId = "u1",
        exerciseId = "barbell_bench_press",
        optionIndex = 0,
        effectiveLoadType = LoadType.BARBELL,
        substitutedFor = "incline_barbell_bench_press",
        slotRole = SlotRole.PRIMARY,
        order = 1,
        sets = listOf(setPrescription.copy(index = 0, kind = SetKind.RAMP, loadKg = 40.0, rir = null), setPrescription, setPrescription.copy(index = 2)),
        supersetGroup = "ss1",
        progressionKey = ProgressionKey(exerciseId = "barbell_bench_press"),
        warnings = listOf(explanation),
        explanation = explanation,
    )
    val volumeSnapshot: VolumeSnapshot = VolumeSnapshot(fractional = volumeFractional, direct = volumeDirect)
    val sessionPlan: SessionPlan = SessionPlan(
        id = "sp1",
        plannedSessionId = "meso1:w1:upper_a",
        templateId = "upper_a",
        name = "Upper A",
        date = date,
        gymId = "gym1",
        mode = SessionMode.AMBER,
        weekIndex = 1,
        readinessScore = 56,
        warmup = warmup,
        blocks = listOf(exerciseBlock),
        cardio = cardioPrescription,
        cooldown = listOf(drillPrescription),
        modifiers = listOf(explanation),
        estimatedMinutes = 52.5,
        volume = volumeSnapshot,
        ledgerCarryover = mapOf(Muscle.CHEST to 0.5),
        alternativeOffered = true,
    )

    // --- Session / logs ---
    val setLog: SetLog = SetLog(
        id = "set1",
        sessionId = "s1",
        blockId = "b1",
        exerciseId = "barbell_bench_press",
        setIndex = 1,
        kind = SetKind.WORKING,
        weight = enteredWeight,
        perHand = false,
        reps = 10,
        seconds = null,
        metres = null,
        rir = 2,
        side = Side.BOTH,
        prescribedLoadKg = 82.5,
        prescribedReps = Band(8, 10),
        prescribedRir = 2,
        loggedAt = instant,
        supersededById = "set2",
        supersedesId = null,
        note = "felt heavy",
    )
    val sessionRecord: SessionRecord = SessionRecord(
        id = "s1",
        date = date,
        gymId = "gym1",
        plannedSessionId = "meso1:w1:upper_a",
        templateId = "upper_a",
        plan = sessionPlan,
        status = SessionStatus.IN_PROGRESS,
        startedAt = instant,
        finishedAt = null,
        sessionRpe = 7,
        durationMinutes = 55,
        readinessCheckinId = "r1",
        skipReason = null,
        notes = "good session",
    )
    val adjustment: Adjustment = Adjustment(
        id = "adj1",
        sessionId = "s1",
        exerciseId = "barbell_bench_press",
        kind = AdjustmentKind.PROGRESSION,
        ruleIds = listOf("PROG-01"),
        summary = "Load 80 → 82.5 kg (PROG-01: all sets hit 10 reps at ≤ 2 RIR)",
        inputs = mapOf("reps" to "10,10,10", "rir" to "2,2,1"),
        beforeJson = "{\"load_kg\":80.0}",
        afterJson = "{\"load_kg\":82.5}",
        source = OverrideSource.PLANNER,
        proposalId = null,
        overrideId = null,
        createdAt = instant,
        undoneAt = null,
    )
    val cardioLog: CardioLog = CardioLog(
        id = "cl1", date = date, sessionId = "s1", exerciseId = "stationary_bike", kind = CardioKind.ZONE2, minutes = 25, avgHr = 118, rpe = 12, note = "easy", loggedAt = instant,
    )
    val nutritionLog: NutritionLog = NutritionLog(
        id = "nl1", date = date, kcal = 2400, proteinG = 180, proteinTargetHit = true, mealsHeaviness = 2, alcoholDrinks = 1, travelDay = false, note = "hotel breakfast", loggedAt = instant,
    )

    // --- Progression ---
    val e1rmEstimate: E1rmEstimate = E1rmEstimate(kg = 106.7, fromWeightKg = 80.0, fromReps = 10, fromRir = 2, confidence = E1rmConfidence.HIGH, date = date, formulaId = "E1RM-01")
    val sessionSummary: SessionSummary = SessionSummary(
        date = date, loadKg = 80.0, bestReps = 10, minRir = 2, allSetsAtHi = true, e1rm = e1rmEstimate, loadXReps = 800.0, readinessBand = ReadinessBand.GREEN,
    )
    val progressionState: ProgressionState = ProgressionState(
        key = progressionKey,
        status = ProgressionStatus.PROGRESSING,
        workingLoadKg = 80.0,
        bandLevel = null,
        workingReps = Band(8, 10),
        repHiBase = 10,
        repHiExtended = false,
        targetRir = 2,
        consecutiveHits = 1,
        lastSessionDate = date,
        lastIncrementDate = date.minusDays(7),
        sessionsSinceIncrement = 1,
        noProgressCount = 0,
        bestE1rm14d = 106.7,
        bestE1rm14dDate = date,
        plateauStep = 0,
        calibrationSessions = 1,
        repZoneShift = 0,
        history = listOf(sessionSummary),
        updatedAt = instant,
    )

    // --- Mutations / overrides ---
    val swapExercise: PlanMutation.SwapExercise = PlanMutation.SwapExercise(
        fromExerciseId = "barbell_bench_press", toExerciseId = "dumbbell_bench_press", templateId = "upper_a", scope = MutationScope.REST_OF_MESOCYCLE, rationale = "rack is busy",
    )
    val changeVolume: PlanMutation.ChangeVolume = PlanMutation.ChangeVolume(muscle = Muscle.CHEST, deltaSetsPerWeek = 2, scope = MutationScope.PERMANENT, rationale = "priority")
    val mutations: List<PlanMutation> = listOf(
        swapExercise,
        changeVolume,
        PlanMutation.ChangeRepRange(exerciseId = "cable_curl", repLo = 12, repHi = 15, scope = MutationScope.TODAY, rationale = "elbow"),
        PlanMutation.AddCardio(weekday = 3, minutes = 30, exerciseId = "stationary_bike", kind = CardioKind.ZONE2, rationale = "CAR-07"),
        PlanMutation.RemoveCardio(weekday = 3, rationale = "travel"),
        PlanMutation.ChangeGoal(goal = Goal.STRENGTH, startNextWeek = true, rationale = "competition"),
        PlanMutation.ChangeSchedule(daysPerWeek = 3, sessionMinutes = 45, preferredWeekdays = listOf(1, 3, 5), rationale = "work"),
        PlanMutation.SetCheckinQuestion(question = "Hunger 1-5?", answerType = CheckinAnswerType.SCALE5, schedule = CheckinSchedule.WEEKLY, onDate = null, weeksActive = 6, rationale = "NUT-17"),
        PlanMutation.ExcludeExercise(exerciseId = "good_morning", until = date.plusWeeks(2), rationale = "low back"),
        PlanMutation.PinExercise(exerciseId = "barbell_bench_press", templateId = "upper_a", rationale = "favourite"),
        PlanMutation.AdjustWorkingLoad(exerciseId = "leg_press_45", gymScope = "gym1", newWorkingLoadKg = 160.0, rationale = "known load"),
        PlanMutation.SetPriorityMuscles(muscles = listOf(Muscle.SIDE_DELTS, Muscle.GLUTES), rationale = "aesthetics"),
        PlanMutation.ScheduleDeload(startThisWeek = false, rationale = "DLD-03"),
        PlanMutation.SetLimitation(limitation = limitation, rationale = "reported pain"),
        PlanMutation.SetTravelDays(from = date.plusDays(3), to = date.plusDays(6), gymId = "hotel1", rationale = "trip"),
        PlanMutation.UpdateNutritionTargets(kcalTarget = 2600, proteinGPerKg = 2.2, rationale = "NUT-16"),
    )
    val planOverride: PlanOverride = PlanOverride(
        id = "ov1", createdAt = instant, source = OverrideSource.CHAT, proposalId = "toolu_01", mutation = swapExercise, revokedAt = null, expiresOn = date.plusDays(7),
    )

    // --- Planner IO ---
    val planInputs: PlanInputs = PlanInputs(
        profile = profile,
        gyms = listOf(gymSet),
        defaultGymId = "gym1",
        overrides = listOf(planOverride),
        progressionStates = listOf(progressionState),
        recentSessions = listOf(sessionRecord),
        recentSets = listOf(setLog),
        bodyMetrics = listOf(bodyMetric),
        weeklyCheckins = listOf(weeklyCheckin),
        readinessHistory = listOf(readinessCheckin),
        mesocycleAnchor = date,
        weekOfMesocycle = 1,
        travelWeeks = listOf(date.plusWeeks(3)),
        weekStartsOnMonday = true,
        mesocyclesCompleted = 2,
        lastDeloadWeekStart = date.minusWeeks(4),
        deloadTriggersProposed = listOf("e1rm_drop@2026-08-30", "red_days@2026-09-01"),
        deloadTriggersActive = listOf("red_days@2026-09-01", "soreness@2026-08-31"),
        patternLastTrained = mapOf(MovementPattern.SQUAT to date.minusDays(10), MovementPattern.HINGE to date.minusDays(40)),
    )
    val planOutput: PlanOutput = PlanOutput(mesocycle = mesocycle, currentWeekIndex = 1, explanations = listOf(explanation), proposals = listOf(mutations[12]))
    val sessionCloseResult: SessionCloseResult = SessionCloseResult(states = listOf(progressionState), adjustments = listOf(adjustment), flags = listOf(explanation))
    val weekRollResult: WeekRollResult = WeekRollResult(
        weekStart = date.plusWeeks(1), nextMode = WeekMode.DELOAD, volumeHolds = listOf(explanation), deloadTriggers = listOf(explanation), proposals = listOf(mutations[12]),
        states = listOf(progressionState.copy(plateauStep = 2, repZoneShift = 1)),
        ladderNotes = listOf(Explanation(listOf("PLAT-02"), "Rep zone shifted one zone lighter (LOAD-04)", mapOf("exercise_id" to "barbell_bench_press", "step" to "2"))),
    )
    val planDiff: PlanDiff = PlanDiff(
        sessionsChanged = listOf("meso1:w1:upper_a"),
        weeklySetsBefore = mapOf(Muscle.CHEST to 12.0),
        weeklySetsAfter = mapOf(Muscle.CHEST to 14.0),
        exercisesAdded = listOf("dumbbell_bench_press"),
        exercisesRemoved = listOf("barbell_bench_press"),
        warnings = listOf("chest above VOL-06 session cap"),
        underTargetReport = listOf("front_delts advisory"),
    )

    // --- Nutrition ---
    val tdeeAdjustment: TdeeAdjustment = TdeeAdjustment(date = date, observedRateKgPerWeek = -0.3, expectedRateKgPerWeek = -0.5, deltaKcal = 150, explanation = explanation)
    val energyModel: EnergyModel = EnergyModel(reeKcal = 1780, activityFactor = 1.55, tdeeEstimateKcal = 2759, adjustments = listOf(tdeeAdjustment), explanation = explanation)
    val nutritionTargets: NutritionTargets = NutritionTargets(
        kcal = 2609, proteinG = 151, carbsG = 300, fatG = 84, fibreG = 42, waterL = 3.7, energyDeltaKcal = -150, goalRateLo = 0.5, goalRateHi = 1.0, nextReviewDate = date.plusDays(14), explanation = explanation,
    )
    val weightTrendPoint: WeightTrendPoint = WeightTrendPoint(date = date, rawKg = 84.5, ma7Kg = 84.2, noise = false)
    val nutritionState: NutritionState = NutritionState(
        tdeeEstimateKcal = 2759, kcalTargetOverride = 2600, proteinGPerKgOverride = 2.2, lastReviewDate = date, deficitStartedOn = date.minusWeeks(3), creatineStartedOn = date.minusWeeks(1), updatedAt = instant,
    )

    // --- Coach ---
    val chatConversation: ChatConversation = ChatConversation(
        id = "c1",
        startedAt = instant,
        endedAt = null,
        backend = BackendMode.DIRECT_API,
        model = "claude-opus-5",
        effort = Effort.LOW,
        systemPromptHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        toolContractVersion = 1,
        seedSummary = "Summary of the previous conversation.",
        status = ConversationStatus.OPEN,
        assistantTurns = 3,
        lastInputTokens = 1200,
        isDefault = false,
    )
    val tokenUsage: TokenUsage = TokenUsage(inputTokens = 100, outputTokens = 50, cacheReadInputTokens = 800, cacheCreationInputTokens = 200, thinkingTokens = 30)
    val chatMessage: ChatMessage = ChatMessage(
        id = "m1",
        conversationId = "c1",
        seq = 1,
        role = ChatRole.ASSISTANT,
        contentJson = "[{\"type\":\"text\",\"text\":\"Hi\"}]",
        displayText = "Hi",
        createdAt = instant,
        usage = tokenUsage,
        requestId = "req_01",
        stopReason = "end_turn",
        stopDetailsJson = null,
        caveatAppended = false,
        toolResultsJson = "{\"toolu_02\":\"[]\"}",
    )
    val proposal: Proposal = Proposal(
        id = "toolu_01",
        conversationId = "c1",
        assistantMessageId = "m1",
        toolName = "propose_swap_exercise",
        contractVersion = 1,
        payloadJson = "{\"from_exercise_id\":\"barbell_bench_press\"}",
        mutation = swapExercise,
        rationale = "rack is busy",
        status = ProposalStatus.PENDING,
        validationWarnings = listOf("ledger delta −0.5 chest"),
        validationErrors = emptyList(),
        createdAt = instant,
        expiresOn = date.plusDays(7),
        decidedAt = null,
        overrideId = null,
        resultSummary = null,
        resultSent = false,
    )
    val spendRecord: SpendRecord = SpendRecord(id = "sp1", at = instant, model = "claude-opus-5", usage = tokenUsage, costUsd = 0.02175, backend = BackendMode.DIRECT_API, conversationId = "c1")

    // --- Settings ---
    val appSettings: AppSettings = AppSettings(
        unitSystem = UnitSystem.IMPERIAL,
        backendMode = BackendMode.DIRECT_API,
        modelId = "claude-sonnet-5",
        effort = Effort.MEDIUM,
        monthlyBudgetUsd = 50.0,
        bridgeUrl = "http://192.168.1.10:8787",
        showExperimental = true,
        onboardingDone = true,
        weekStartsOnMonday = false,
        plannerOverridesJson = "{\"VOL-04.intermediate.lo\":14}",
        coachUserId = "3f2a",
        readinessReminderTime = "07:00",
        weeklyCheckinWeekday = 6,
        keepScreenOnInSession = false,
        lastWeekRollover = "2026-08-31",
        catalogVersionSeen = 2,
        mesocycleAnchor = "2026-08-31",
        mesocycleWeekPausedAt = 3,
        mesocyclesCompleted = 2,
        lastDeloadWeekStart = "2026-08-10",
        deloadTriggersProposed = "[\"e1rm_drop@2026-08-30\"]",
        activeGymId = "gym1",
        todayContextJson = "{\"date\":\"2026-09-05\"}",
        sessionDraftJson = "{\"load\":\"82.5\"}",
        onboardingDraftJson = "{\"page\":3}",
    )

    // --- Views (§2.14) ---
    val chartPoint: ChartPoint = ChartPoint(dateEpochDay = date.toEpochDay(), value = 106.7, lowConfidence = true)
    val chartSeries: ChartSeries = ChartSeries(name = "e1RM bench", points = listOf(chartPoint, ChartPoint(date.plusDays(7).toEpochDay(), 108.2)))
    val muscleBandRow: MuscleBandRow = MuscleBandRow(
        muscle = Muscle.CHEST, tier = MuscleTier.TIER_1, target = 12.0, achievedFractional = 7.5, plannedRemaining = 4.0, direct = 6, rpMev = Band(6, 8), rpMrv = Band(20, 22),
    )
    val supplementAdvice: SupplementAdvice = SupplementAdvice(
        id = "SUP-01",
        name = "Creatine monohydrate",
        tier = "A",
        doseText = "5 g/day",
        timing = "any time",
        evidence = Confidence.STRONG,
        citation = "Kreider 2017",
        gated = false,
        notes = listOf("water weight +1 kg is normal"),
        legalNote = "third-party tested products",
        doNotReason = null,
    )
}

class FixturesTest {

    @Test
    fun mutationsFixtureCoversEverySubclassOnce() {
        val classes = Fixtures.mutations.map { it::class }.toSet()
        assertEquals(16, classes.size, "one fixture per PlanMutation subclass")
        assertEquals(16, Fixtures.mutations.size)
    }

    @Test
    fun fixtureDateIsSaturday2026_09_05() {
        assertEquals(6, Fixtures.date.dayOfWeek.value)
    }

    @Test
    fun exerciseFixtureIsCompoundAndIsolationFixtureIsNot() {
        assertTrue(Fixtures.exercise.flags.compound)
        assertTrue(!Fixtures.isolationExercise.flags.compound)
    }
}
