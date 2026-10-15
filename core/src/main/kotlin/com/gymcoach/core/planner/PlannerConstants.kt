// core/src/main/kotlin/com/gymcoach/core/planner/PlannerConstants.kt
package com.gymcoach.core.planner

import com.gymcoach.core.model.Band
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/*
 * Every leaf field is an R3 planner default (weak/convention unless noted) or a moderate/strong value
 * kept here so that ONE object tree holds every number the planner uses. Leaf names carry the rule ID
 * (prefix before the first capital letter, e.g. vol04Novice -> "VOL-04", e1rm02MaxReps -> "E1RM-02";
 * exceptions in RULE_ID_EXCEPTIONS); rules.json holds the statement, confidence, citation and the
 * same defaults under the §3.5 key convention (RuleBookTest). User overrides from Settings ▸ Advanced
 * are merged over DEFAULT by [PlannerConstants.withOverrides] (nested JSON, e.g. {"vol":{"vol04Novice":[8,12]}});
 * only leaves whose rule is user_adjustable are shown as editable.
 */

/** DEF-01. */
@Serializable
data class DefConstants(
    val def01NoviceMaxMonths: Int = 6,
    val def01IntermediateMaxMonths: Int = 24,
    val def01LayoffResetMonths: Int = 12,
    val def01RetrainMonthsToLeaveNovice: Int = 6,
)

/** VOL-01/04/05/06/07/08/09, MIX-03, R4-4.5 (fractional sets per muscle per week unless stated). */
@Serializable
data class VolConstants(
    val vol01Minimum: Int = 4,
    val vol04Novice: Band = Band(8, 12),
    val vol04Intermediate: Band = Band(12, 18),
    val vol04Advanced: Band = Band(15, 25),
    val vol05StrengthPerLift: Band = Band(4, 10),
    val vol06PerSessionDefaultStop: Int = 10,
    val vol06PerSessionCap: Int = 12,
    val vol07MinSetsPerAppearance: Int = 2,
    val vol08StepTier1: Double = 1.0,
    val vol08StepTier2: Double = 0.5,
    val vol08StepMax: Int = 2,
    val vol08SorenessGate: Int = 3,
    val vol08RirTolerance: Double = 0.5,             // hold when mean reported RIR < prescribed RIR − tolerance (§4.4)
    val vol09DeficitFractionLo: Double = 0.6,
    val vol09DeficitFractionHi: Double = 1.0,
    val vol09NoHistoryFactor: Double = 0.8,
    val mix03CompoundShare: Band = Band(60, 75),
    val r45SoftCapFactor: Double = 0.5,              // allocator gain × 0.5 once direct(m) ≥ RP MRV.lo(m), NOVICE/INTERMEDIATE only (§4.4)
)

/** TIER (planner convention). */
@Serializable
data class TierConstants(
    val tierTier2Factor: Double = 0.6,
    val tierTier3DirectCap: Int = 6,
)

/** SESS-01/02. */
@Serializable
data class SessConstants(
    val sess01HeavyLowerMin: Double = 4.0,
    val sess01HeavyUpperMin: Double = 3.5,
    val sess01CompoundMin: Double = 2.5,
    val sess01IsolationMin: Double = 1.75,
    val sess01IsolationRestCutMin: Double = 1.5,
    val sess01SupersetPairMin: Double = 2.5,
    val sess01SupersetFactor: Double = 0.7,
    val sess01UnilateralFactor: Double = 1.7,
    val sess01BudgetToleranceMin: Double = 0.0,
    val sess02CapDefault: Int = 25,
    val sess02CapNovice: Int = 20,
    val sess02CapAdvanced: Int = 32,
)

/** WU-01/02/05/06. */
@Serializable
data class WuConstants(
    val wu01Minutes: Int = 10,
    val wu05TrimmedMinutes: Int = 5,
    val wu05TrimAtOrBelowMinutes: Int = 45,
    val wu06ExtraMinutes: Int = 2,
    val wu06AgeThreshold: Int = 40,
    val wu06MinutesSinceWakingThreshold: Int = 60,
    val wu02HeavyPcts: List<Double> = listOf(0.40, 0.55, 0.70, 0.825),
    val wu02HeavyReps: List<Int> = listOf(8, 5, 3, 2),
    val wu02ModeratePcts: List<Double> = listOf(0.50, 0.70),
    val wu02ModerateReps: List<Int> = listOf(8, 5),
    val wu02LightPcts: List<Double> = listOf(0.60),
    val wu02LightReps: List<Int> = listOf(8),
    val wu02IsolationPct: Double = 0.55,
    val wu02WarmPatternPct: Double = 0.65,
    val wu02RampRestSec: Int = 60,
)

/** REST-01..04 (seconds). */
@Serializable
data class RestConstants(
    val rest01HypertrophySec: Int = 120,             // moderate compounds (REST-01)
    val rest02HeavySec: Int = 180,                   // heavy compounds (REST-02)
    val rest02TopSetSec: Int = 240,                  // ≤ 6-rep top sets (REST-02)
    val rest03IsolationSec: Int = 90,                // isolation / machines (REST-03)
    val rest03SupersetTransitionSec: Int = 30,
    val rest04CutSec: Int = 60,                      // concession level 4 (REST-04)
    val rest04MainLiftFloorSec: Int = 120,
)

/** RIR-03/04/05, RIR-CAP. */
@Serializable
data class RirConstants(
    val rir03LowerBarbellFloor: Int = 2,
    val rir03UpperBarbellFloor: Int = 1,
    val rir03SpWithoutRackFloor: Int = 2,            // SP exercise whose satisfied option lacks rack_or_spotter (§3.1 decision)
    val rir04Week1Offset: Int = 1,
    val rir04LastWeekOffset: Int = -1,               // fallback when the profile has no rir_last_week
    val rir04LastWeekCompoundFloor: Int = 1,
    val rir04LastWeekIsolationFloor: Int = 0,
    val rir04DeloadRir: Int = 4,
    val rir05HighRepThreshold: Int = 12,
    val rir05CalibrationEveryWeeks: Int = 3,
    val rirCapMax: Int = 4,                          // RIR-CAP (DEF-04)
)

/** PROG-02/03/04/05, LOAD-03/04, E1RM-02/04. */
@Serializable
data class ProgConstants(
    val prog02LowerBarbellKg: Double = 5.0,
    val prog02LowerBarbellSmallKg: Double = 2.5,
    val prog02LowerBarbellSmallAboveKg: Double = 100.0,
    val prog02UpperBarbellKg: Double = 2.5,
    val prog02MicroplateKg: Double = 1.0,
    val prog02BodyweightExternalKg: Double = 2.5,
    val prog02MaxJumpPct: Double = 10.0,
    val prog02RepExtension: Int = 2,
    val prog03IntermediateMinDays: Int = 7,
    val prog04EasyOffset: Int = 2,
    val prog04HardOffset: Int = 2,
    val prog04BackoffFactor: Double = 0.95,
    val prog05NoviceRir: Band = Band(3, 4),
    val prog05NoviceWeeks: Int = 2,
    val load03BackoffFactor: Double = 0.85,          // strength back-off day (H16)
    val load03BackoffReps: Band = Band(4, 8),
    val load04MaxStrengthRepsMax: Double = 5.0,      // LoadZones.of rep-midpoint cut-points (§4.6)
    val load04HeavyRepsMax: Double = 8.0,
    val load04ModerateRepsMax: Double = 15.0,
    val load04MaxStrengthPct: Double = 0.85,         // %1RM cut-points when a HIGH e1RM exists
    val load04HeavyPct: Double = 0.78,
    val load04ModeratePct: Double = 0.65,
    val e1rm02MaxReps: Int = 10,
    val e1rm04RollingDays: Int = 14,
)

/** PLAT-01/03, DLD-01..04. */
@Serializable
data class PlatDldConstants(
    val plat01Sessions: Int = 3,
    val plat01ImprovementFactor: Double = 1.005,
    val plat03DropFactor: Double = 0.95,
    val plat03Sessions: Int = 2,
    val dld01NoviceWeeks: Int = 12,
    val dld01IntermediateWeeks: Int = 6,
    val dld01AdvancedWeeks: Int = 5,
    val dld02SetsFactor: Double = 0.5,
    val dld02CompoundLoadFactor: Double = 0.85,
    val dld03MinTriggers: Int = 2,
    val dld03MinDays: Int = 5,
    val dld03RedDaysOf7: Int = 3,
    val dld03SorenessLevel: Int = 4,
    val dld03SorenessHours: Int = 72,
    val dld03SleepQualityLevel: Int = 2,
    val dld03SleepQualityNights: Int = 4,
    val dld03MotivationLevel: Int = 2,
    val dld03MotivationDays: Int = 4,
    val dld03JointPain: Int = 3,
    val dld03MinWeeksBetweenDeloads: Int = 2,        // ScheduleDeload rejected inside this window (§4.11, §4.15)
    val dld04LoadFactor: Double = 0.95,
    val dld04RirOffset: Int = 1,                     // == the RIR-04 week-1 offset carried by MesoWeek.rirOffset; never added twice (§4.7 step 4)
)

/** LAY-01/02. The `*Days` bands are EXCESS days beyond the plan's own cadence (§4.7 step 2). */
@Serializable
data class LayConstants(
    val lay01MinExcessDays: Int = 5,                 // below this excess nothing is a layoff
    val lay01Row1Days: Band = Band(5, 13),
    val lay01Row1Load: Double = 0.95,
    val lay01Row1SetDelta: Int = -1,
    val lay01Row1Rir: Int = 1,
    val lay01Row2Days: Band = Band(14, 28),
    val lay01Row2Load: Double = 0.875,
    val lay01Row2SetFactor: Double = 0.7,
    val lay01Row2Rir: Int = 2,
    val lay01Row3Days: Band = Band(29, 56),
    val lay01Row3Load: Double = 0.775,
    val lay01Row3SetFactor: Double = 0.5,
    val lay01Row3Rir: Int = 2,
    val lay01Row4Load: Double = 0.65,
    val lay01Row4Rir: Band = Band(3, 4),
    val lay01ConservativeAge: Int = 65,
    val lay01ExerciseGapDays: Int = 14,              // per-exercise rule: excess ≥ this while the pattern was trained → row 1 only
    val lay02Zone2OnlyDays: Int = 3,
)

/** RDY-01..06, SLP-03. */
@Serializable
data class RdyConstants(
    val rdy02GreenMin: Int = 70,
    val rdy02AmberMin: Int = 45,
    val rdy02AmberRirOffset: Int = 1,
    val rdy02AmberSetDropAfter: Int = 2,
    val rdy02RedSetFactor: Double = 0.6,
    val rdy02RedRirOffset: Int = 2,
    val rdy03RhrAmberDelta: Int = 5,
    val rdy03RhrRedDelta: Int = 10,
    val rdy03RhrDays: Int = 2,
    val rdy04SorenessLevel: Int = 4,
    val rdy05StressLevel: Int = 4,
    val rdy05Checkins: Int = 2,
    val rdy05SetFactor: Double = 0.8,
    val rdy05RirOffset: Int = 1,
    val rdy06SpikeFactor: Double = 1.5,
    val slp03WeeklyAvgHours: Double = 7.0,
    val slp03SetFactor: Double = 0.9,
    val slp03RirOffset: Int = 1,
    val slp03NightHours: Double = 6.0,
    val slp03TwoNightsNoMaxStrength: Int = 2,
)

/** TRV-01/04, TIME-01. */
@Serializable
data class TrvTimeConstants(
    val trv01Sessions: Int = 2,
    val trv01Minutes: Band = Band(25, 40),
    val trv01Exercises: Band = Band(4, 6),
    val trv01Sets: Band = Band(2, 3),
    val trv01TravelDaysThreshold: Int = 3,
    val trv01EnduranceReps: Band = Band(15, 30),     // light-load fallback (LOAD-01): fixed RIR, no stack
    val trv01EnduranceRir: Int = 1,
    val trv04RampFactors: List<Double> = listOf(1.0, 1.15, 1.30, 1.45),   // find-load protocol (§4.9.2)
    val trv04StopRirOffset: Int = 1,                 // stop when reported RIR ≤ min(target + 1, rirCapMax)
    val time01MinViableRequiredSets: Int = 2,        // per required COMPOUND slot (§4.7 step 5)
    val time01MinViablePrioritySets: Int = 1,
)

/** SEL / SUB / REBAL / R4-2.6 (planner conventions). */
@Serializable
data class SelSubConstants(
    val selPatternPref: Double = 0.35,
    val selCoverage: Double = 0.25,
    val selContinuity: Double = 0.15,
    val selPreference: Double = 0.10,
    val selLoadTypePref: Double = 0.10,
    val selDifficultyFit: Double = 0.05,
    val selFamilyPatternScore: Double = 0.6,
    val selRepeatInSessionPenalty: Double = 0.5,
    val selVarietyPenalty: Double = 0.2,
    val selMildLimitationPenalty: Double = 0.3,
    val selNoRackPenalty: Double = 0.3,
    val subPattern: Double = 0.35,
    val subCosine: Double = 0.35,
    val subLoadFit: Double = 0.15,
    val subContinuity: Double = 0.10,
    val subDifficulty: Double = 0.05,
    val subRankBonus: Double = 0.10,
    val subProfilePenalty: Double = 0.20,
    val subLoadFitTier1: Double = 0.85,
    val subLoadFitTier2: Double = 0.60,
    val subLoadFitTier3: Double = 0.40,
    val rebalDeficitMin: Double = 1.0,
    val rebalSurplusMin: Double = 2.0,
    val rebalTolerance: Double = 1.0,
    val r426KeepAtMaxThreshold: Double = 0.6,
    val r426KeepAtMaxRepExtension: Int = 2,          // "keep at max load, rep band +2 at the top, RIR −1" (§4.7 step 1)
    val r426KeepAtMaxRirOffset: Int = -1,
)

/** CAR-05/06/07/09. */
@Serializable
data class CarConstants(
    val car05HiitBaseWeeks: Int = 4,                 // keyed to CAR-05 by prefix (R3 §5 CAR-05 prose: "requires ≥ 4 weeks of zone-2 base"); the R3 §10 CAR-05 row omits the number and CAR-09 restates it — rules.json's CAR-05 entry carries constants.hiitBaseWeeks (§3.5)
    val car06SeparationHours: Int = 6,
    val car06RunBeforeLegsHours: Int = 24,
    val car06HypertrophyCapSessions: Int = 3,
    val car06HypertrophyCapMinutes: Int = 150,
    val car09WeeklyIncreasePct: Double = 10.0,
)

/** NUT-01/04/05/06/08/09/15/16/17. */
@Serializable
data class NutConstants(
    val nut01DefaultGPerKg: Double = 1.8,
    val nut04ActivityFactors: List<Double> = listOf(1.2, 1.375, 1.55, 1.725, 1.9),
    val nut04KcalPerKg: Int = 7700,
    val nut04ClampKcal: Int = 150,
    val nut04WindowDays: Int = 14,
    val nut04MinKcalLogs: Int = 10,
    val nut05DeficitKcal: Band = Band(300, 500),
    val nut05DeficitPctOfTdee: Double = 0.20,        // deficit = min(nut05DeficitKcal.hi, round(0.20 × TDEE)) (§5.5)
    val nut05RatePctLo: Double = 0.5,
    val nut05RatePctHi: Double = 1.0,
    val nut05LeanRatePctHi: Double = 0.7,
    val nut05MaxRatePctPerWeek: Double = 1.5,        // observed loss above this → +nut05FastLossStepKcal proposal + supervision text
    val nut05FastLossStepKcal: Int = 200,
    val nut05KcalFloorTdeeFactor: Double = 0.75,     // MutationValidator: kcal ≥ max(REE, 0.75 × TDEE)
    val nut06SurplusPct: Band = Band(10, 20),
    val nut06RatePctLo: Double = 0.25,
    val nut06RatePctHi: Double = 0.5,
    val nut06KcalCeilingTdeeFactor: Double = 1.3,    // MutationValidator: kcal ≤ 1.3 × TDEE
    val nut08CarbFloorGPerKg: Double = 3.0,
    val nut09FatGPerKgDefault: Double = 1.0,
    val nut09FatGPerKgFloor: Double = 0.5,
    val nut15NoiseSingleDayPct: Double = 1.0,
    val nut15MinDays: Int = 14,
    val nut16StepKcal: Band = Band(100, 200),
    val nut17DietBreakAfterWeeks: Int = 8,
)

/** STP-01/02, SUP-02. */
@Serializable
data class HabitConstants(
    val stp01Under60: Band = Band(7000, 8000),
    val stp01Over60: Band = Band(6000, 7000),
    val stp02Bonus: Int = 2000,
    val stp02Cap: Int = 12000,
    val sup02CutoffSmallHours: Int = 9,
    val sup02CutoffLargeHours: Int = 13,
    val sup02DayMaxMg: Int = 400,
)

/** AGE-01/02/03 (§4.4 AgePolicy). */
@Serializable
data class AgeConstants(
    val age01Threshold: Int = 40,
    val age01ProteinGPerKg: Double = 1.9,            // NUT-01 upper half replaces nut01DefaultGPerKg from age01Threshold on
    val age02Threshold: Int = 60,
    val age02SetsPerExercise: Band = Band(2, 3),     // overrides the profile `sets` bands
    val age02ExercisesPerSession: Band = Band(8, 10),
    val age02CompoundReps: Band = Band(7, 9),        // overrides compound rep bands (≈ 70–79 % 1RM); isolation bands unchanged
    val age02RestSec: Band = Band(60, 120),
    val age02SingleSetStartWeeks: Int = 2,           // NOVICE ≥ 60: one working set per exercise in weeks 1–2
    val age02PowerSetsPerWeek: Band = Band(1, 2),    // one POWER-profile slot at 40–60 % of the pattern's e1RM, fast concentric, RIR ≥ 3
    val age02PowerPctLo: Double = 0.40,
    val age02PowerPctHi: Double = 0.60,
    val age02PowerMinRir: Int = 3,
    val age02BalanceDaysPerWeek: Int = 2,            // 5-min balance block in the cool-down list
    val age02BalanceMinutes: Int = 5,
    val age03MaxStrengthAllowed: Boolean = false,    // ≥ 60: no MAX_STRENGTH zone unless ADVANCED && allowAdvancedExercises
)

@Serializable
data class PlannerConstants(
    val def: DefConstants = DefConstants(),
    val vol: VolConstants = VolConstants(),
    val tier: TierConstants = TierConstants(),
    val sess: SessConstants = SessConstants(),
    val wu: WuConstants = WuConstants(),
    val rest: RestConstants = RestConstants(),
    val rir: RirConstants = RirConstants(),
    val prog: ProgConstants = ProgConstants(),
    val plat: PlatDldConstants = PlatDldConstants(),
    val lay: LayConstants = LayConstants(),
    val rdy: RdyConstants = RdyConstants(),
    val trv: TrvTimeConstants = TrvTimeConstants(),
    val sel: SelSubConstants = SelSubConstants(),
    val car: CarConstants = CarConstants(),
    val nut: NutConstants = NutConstants(),
    val hab: HabitConstants = HabitConstants(),
    val age: AgeConstants = AgeConstants(),
) {
    companion object {
        val DEFAULT: PlannerConstants = PlannerConstants()

        /** Leaf names whose rule id is not derivable from the prefix rule. Keyed by full field name OR by prefix. */
        private val RULE_ID_EXCEPTIONS: Map<String, String> = mapOf(
            "rirCapMax" to "RIR-CAP",
            "r426" to "R4-2.6",
            "r45" to "R4-4.5",
        )

        /**
         * Merges a NESTED JSON object ({"group":{"leaf":value}}) over `base`: unknown groups/leaves are
         * ignored, nested objects merge key by key, leaves are replaced. A leaf that fails to decode
         * discards the whole override (the caller logs it) — the app never runs on a half-applied set.
         */
        fun withOverrides(base: PlannerConstants, overridesJson: String, json: Json): PlannerConstants {
            if (overridesJson.isBlank() || overridesJson == "{}") return base
            val baseObj = json.encodeToJsonElement(serializer(), base).jsonObject
            val over = try { json.parseToJsonElement(overridesJson).jsonObject } catch (e: Exception) { return base }
            return try { json.decodeFromJsonElement(serializer(), mergeObjects(baseObj, over)) } catch (e: Exception) { base }
        }

        private fun mergeObjects(base: JsonObject, over: JsonObject): JsonObject {
            val out = base.toMutableMap()
            for ((key, value) in over) {
                val b = base[key] ?: continue
                out[key] = if (b is JsonObject && value is JsonObject) mergeObjects(b, value) else value
            }
            return JsonObject(out)
        }

        /** The rule prefix of a LEAF field name: everything before the first upper-case letter ("vol04Novice" -> "vol04"). */
        fun rulePrefixOf(fieldName: String): String = fieldName.takeWhile { !it.isUpperCase() }

        /**
         * "vol04Novice" -> "VOL-04"; "e1rm02MaxReps" -> "E1RM-02"; "time01MinViableRequiredSets" -> "TIME-01";
         * "selPatternPref" -> "SEL"; "tierTier2Factor" -> "TIER"; "rirCapMax" -> "RIR-CAP";
         * "r426KeepAtMaxThreshold" -> "R4-2.6"; "r45SoftCapFactor" -> "R4-4.5". Operates on leaf names only.
         */
        fun ruleIdOf(fieldName: String): String {
            RULE_ID_EXCEPTIONS[fieldName]?.let { return it }
            val prefix = rulePrefixOf(fieldName)
            RULE_ID_EXCEPTIONS[prefix]?.let { return it }
            val digits = prefix.takeLastWhile { it.isDigit() }
            val letters = prefix.dropLast(digits.length).uppercase()
            return if (digits.isEmpty()) letters else "$letters-$digits"
        }

        /** §3.5 constants key: "vol04Novice" -> "novice"; "tierTier2Factor" -> "tier2Factor"; "rirCapMax" -> "capMax". Bands add ".lo"/".hi", lists ".0", ".1", … */
        fun constantKeyOf(fieldName: String): String =
            fieldName.drop(rulePrefixOf(fieldName).length).replaceFirstChar { it.lowercaseChar() }

        /** (groupName, leafName, leafDescriptor) for every leaf, walked from serializer().descriptor — no reflection (HR-5). */
        fun leafFields(): List<Triple<String, String, SerialDescriptor>> {
            val root = serializer().descriptor
            val out = ArrayList<Triple<String, String, SerialDescriptor>>()
            for (g in 0 until root.elementsCount) {
                val groupName = root.getElementName(g)
                val groupDesc = root.getElementDescriptor(g)
                if (groupDesc.kind != StructureKind.CLASS) continue
                for (i in 0 until groupDesc.elementsCount) {
                    out.add(Triple(groupName, groupDesc.getElementName(i), groupDesc.getElementDescriptor(i)))
                }
            }
            return out
        }
    }
}
