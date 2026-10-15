// core/src/main/kotlin/com/gymcoach/core/equipment/IncrementResolver.kt
package com.gymcoach.core.equipment

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.EquipmentCategory
import com.gymcoach.core.model.ExerciseClass
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.GroupKind
import com.gymcoach.core.model.IncrementDirection
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.Sex
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.planner.PlannerConstants
import java.math.BigDecimal
import java.math.RoundingMode

/** `kg` is PER HAND for dumbbell/kettlebell pairs (perHand = true) and per total load otherwise; callers normalise the logged load to the same basis before comparing (§4.9.3). */
data class LoadStep(val kg: Double, val perHand: Boolean, val direction: IncrementDirection, val explanation: Explanation)

/** R4 §5.4 / PROG-02: the smallest sensible load jump for an exercise in this gym. Values from PlannerConstants.DEFAULT (PROG-02 is not user-adjustable). */
class IncrementResolver(private val kb: KnowledgeBase) {

    fun step(exercise: Exercise, effectiveLoadType: LoadType, params: GymParams, currentLoadKg: Double?, sex: Sex, trainingAge: TrainingAge): LoadStep {
        val c = PlannerConstants.DEFAULT.prog
        val isolationNote = if (exercise.isIsolation) "; isolation: prefer +reps before adding load" else ""

        if (exercise.increment.direction == IncrementDirection.ASSIST) {
            val machine = Implements.stackEquipment(kb, exercise, effectiveLoadType, params) ?: ASSIST_MACHINE
            val kg = params.halfStepKg(machine) ?: params.stackStepKg(machine) ?: DEFAULT_STACK_STEP_KG
            return LoadStep(kg, false, IncrementDirection.ASSIST,
                explain("assisted machine: ${Implements.fmt(kg)} kg less assistance", "stack_step_kg" to Implements.fmt(kg)))
        }

        return when (effectiveLoadType) {
            LoadType.BAND -> LoadStep(0.0, false, IncrementDirection.LOAD, explain("next band level$isolationNote"))
            LoadType.BODYWEIGHT -> {
                val kg = c.prog02BodyweightExternalKg
                LoadStep(kg, false, IncrementDirection.LOAD, explain("reps first, then +${Implements.fmt(kg)} kg external load$isolationNote"))
            }
            LoadType.BARBELL, LoadType.SMITH -> barbellStep(exercise, effectiveLoadType, params, currentLoadKg, sex, trainingAge, isolationNote)
            LoadType.DUMBBELL -> {
                val kg = params.dumbbellStepKg ?: DEFAULT_DUMBBELL_STEP_KG
                LoadStep(kg, true, IncrementDirection.LOAD, explain("next dumbbell: +${Implements.fmt(kg)} kg per hand$isolationNote", "step_kg" to Implements.fmt(kg)))
            }
            LoadType.KETTLEBELL -> {
                val kg = nextInList(params.kettlebellsKg, currentLoadKg, implements(exercise, effectiveLoadType)) ?: (exercise.increment.defaultKg ?: DEFAULT_KETTLEBELL_STEP_KG)
                LoadStep(kg, true, IncrementDirection.LOAD, explain("next kettlebell: +${Implements.fmt(kg)} kg per bell$isolationNote"))
            }
            LoadType.MACHINE, LoadType.CABLE -> {
                val machine = Implements.stackEquipment(kb, exercise, effectiveLoadType, params)
                if (machine != null && params.bool(machine, "plate_loaded") == true) {
                    val kg = 2.0 * (params.smallestPlateKg ?: DEFAULT_SLED_PLATE_KG)
                    LoadStep(kg, false, IncrementDirection.LOAD, explain("plate-loaded: +${Implements.fmt(kg)} kg (one plate pair)$isolationNote"))
                } else {
                    val kg = machine?.let { params.halfStepKg(it) ?: params.stackStepKg(it) } ?: DEFAULT_STACK_STEP_KG
                    LoadStep(kg, false, IncrementDirection.LOAD, explain("weight stack: +${Implements.fmt(kg)} kg$isolationNote", "stack_step_kg" to Implements.fmt(kg)))
                }
            }
            LoadType.SLED -> {
                val kg = 2.0 * (params.smallestPlateKg ?: DEFAULT_SLED_PLATE_KG)
                LoadStep(kg, false, IncrementDirection.LOAD, explain("sled: +${Implements.fmt(kg)} kg (one plate pair)"))
            }
            LoadType.MEDICINE_BALL -> {
                val kg = nextInList(params.numbers("medicine_balls", "available_kg"), currentLoadKg, 1) ?: (exercise.increment.defaultKg ?: DEFAULT_MEDICINE_BALL_STEP_KG)
                LoadStep(kg, false, IncrementDirection.LOAD, explain("next ball: +${Implements.fmt(kg)} kg"))
            }
            LoadType.CARDIO, LoadType.NONE -> LoadStep(0.0, false, IncrementDirection.LOAD, explain("no load increment (duration / intensity progression)"))
        }
    }

    /** 2 for a bilateral dumbbell/kettlebell exercise held with two implements, 1 for unilateral or single-implement exercises (goblet, suitcase carry) and every other load type. */
    fun implements(exercise: Exercise, effectiveLoadType: LoadType): Int {
        if (effectiveLoadType != LoadType.DUMBBELL && effectiveLoadType != LoadType.KETTLEBELL) return 1
        if (exercise.unilateral) return 1
        if (exercise.id in SINGLE_IMPLEMENT) return 1
        return 2
    }

    private fun barbellStep(exercise: Exercise, lt: LoadType, params: GymParams, currentLoadKg: Double?, sex: Sex, trainingAge: TrainingAge, isolationNote: String): LoadStep {
        val c = PlannerConstants.DEFAULT.prog
        val bar = Implements.barEquipment(kb, exercise, lt, params)
        if (bar == Implements.FIXED_BARBELLS) {
            val kg = params.number(Implements.FIXED_BARBELLS, "step_kg") ?: c.prog02UpperBarbellKg
            return LoadStep(kg, false, IncrementDirection.LOAD, explain("next fixed barbell: +${Implements.fmt(kg)} kg$isolationNote"))
        }
        val micro = params.hasMicroplates
        val loadable = if (micro) 2.0 * MICROPLATE_KG else 2.0 * (params.smallestPlateKg ?: DEFAULT_SMALLEST_PLATE_KG)
        val cls = exercise.exerciseClass(lt)
        val wanted = when {
            cls == ExerciseClass.LOWER_BARBELL ->
                if ((currentLoadKg != null && currentLoadKg > c.prog02LowerBarbellSmallAboveKg) || sex == Sex.FEMALE || trainingAge == TrainingAge.NOVICE) c.prog02LowerBarbellSmallKg
                else c.prog02LowerBarbellKg
            micro -> c.prog02MicroplateKg
            else -> c.prog02UpperBarbellKg
        }
        val kg = maxOf(wanted, loadable)
        return LoadStep(kg, false, IncrementDirection.LOAD,
            explain("${cls.name.lowercase().replace('_', ' ')}: +${Implements.fmt(kg)} kg$isolationNote", "class" to cls.name, "microplates" to micro.toString()))
    }

    /** Gap from the current implement to the next one in [available] (per implement); null when the list is unknown or the top is reached. */
    private fun nextInList(available: List<Double>?, currentLoadKg: Double?, implements: Int): Double? {
        if (available.isNullOrEmpty()) return null
        val sorted = available.sorted()
        if (currentLoadKg == null) return if (sorted.size >= 2) sorted[1] - sorted[0] else null
        val perImplement = if (implements > 1 && sorted.none { kotlin.math.abs(it - currentLoadKg) < 1e-6 }) currentLoadKg / implements else currentLoadKg
        val next = sorted.firstOrNull { it > perImplement + 1e-6 } ?: return null
        return next - perImplement
    }

    private fun explain(message: String, vararg inputs: Pair<String, String>): Explanation =
        Explanations.of(listOf(RuleIds.PROG_02, RuleIds.R4_5_4), message, inputs.toMap(), Confidence.MODERATE)

    companion object {
        const val DEFAULT_STACK_STEP_KG: Double = 5.0
        const val DEFAULT_DUMBBELL_STEP_KG: Double = 2.0
        const val DEFAULT_KETTLEBELL_STEP_KG: Double = 4.0
        const val DEFAULT_SMALLEST_PLATE_KG: Double = 1.25
        const val DEFAULT_SLED_PLATE_KG: Double = 2.5
        const val DEFAULT_MEDICINE_BALL_STEP_KG: Double = 2.0
        const val MICROPLATE_KG: Double = 0.5
        const val ASSIST_MACHINE: String = "assisted_pullup_dip_machine"

        /** Bilateral exercises that hold ONE dumbbell/kettlebell (R4 §5.4 note; goblet, swing, overhead DB extension, DB on hips). */
        val SINGLE_IMPLEMENT: Set<String> = setOf("goblet_squat", "kettlebell_swing", "dumbbell_overhead_triceps_extension", "glute_bridge", "suitcase_carry")
    }
}

/** Shared lookups for IncrementResolver and LoadRounder: which concrete equipment of this gym carries the load. */
internal object Implements {
    const val FIXED_BARBELLS: String = "fixed_weight_barbells"
    private val BAR_IDS: List<String> = listOf("barbell", "ez_bar", "trap_bar", "safety_squat_bar", FIXED_BARBELLS, "smith_machine")
    private val MACHINE_CATEGORIES: Set<EquipmentCategory> = setOf(EquipmentCategory.CABLES, EquipmentCategory.MACHINES_UPPER, EquipmentCategory.MACHINES_LOWER)

    /** Concrete ids named by the exercise's options whose effective load type is [lt], groups expanded one level, in printed order. */
    fun concreteItems(kb: KnowledgeBase, exercise: Exercise, lt: LoadType): List<String> {
        val out = LinkedHashSet<String>()
        val options = exercise.equipmentOptions.filter { (it.loadType ?: exercise.loadType) == lt }.ifEmpty { exercise.equipmentOptions }
        for (o in options) {
            for (item in o.allOf) {
                val g = kb.groups[item]
                if (g == null) out.add(item) else if (g.kind == GroupKind.ALL_OF || g.kind == GroupKind.ANY_OF) out.addAll(g.members)
            }
        }
        return out.toList()
    }

    fun stackEquipment(kb: KnowledgeBase, exercise: Exercise, lt: LoadType, params: GymParams): String? =
        concreteItems(kb, exercise, lt).firstOrNull { params.has(it) && kb.equipment[it]?.category in MACHINE_CATEGORIES }

    fun barEquipment(kb: KnowledgeBase, exercise: Exercise, lt: LoadType, params: GymParams): String? {
        val items = concreteItems(kb, exercise, lt)
        if (lt == LoadType.SMITH) return if (params.has("smith_machine")) "smith_machine" else null
        return items.firstOrNull { it in BAR_IDS && it != "smith_machine" && params.has(it) }
    }

    /** "82.5", "80", "2.25": plain decimal, no trailing zeros, locale-independent. */
    fun fmt(x: Double): String = BigDecimal(x).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
