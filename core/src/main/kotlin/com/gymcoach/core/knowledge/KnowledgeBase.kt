// core/src/main/kotlin/com/gymcoach/core/knowledge/KnowledgeBase.kt
package com.gymcoach.core.knowledge

import com.gymcoach.core.model.CardioModalityEntry
import com.gymcoach.core.model.CheckinQuestionDef
import com.gymcoach.core.model.Drill
import com.gymcoach.core.model.EquipmentDef
import com.gymcoach.core.model.EquipmentGroup
import com.gymcoach.core.model.EquipmentPreset
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.GoalPrescription
import com.gymcoach.core.model.LoadTransferFile
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.NutritionTip
import com.gymcoach.core.model.RepProfile
import com.gymcoach.core.model.RpLandmark
import com.gymcoach.core.model.SessionTemplate
import com.gymcoach.core.model.SplitTemplate
import com.gymcoach.core.model.SupplementsFile
import java.util.Locale

/**
 * The loaded, immutable knowledge assets of SPEC §3 (built by [KnowledgeLoader]).
 *
 * [cardioModalities] is keyed by exercise id for the default (non-running) row of each R3 CAR-08 modality; the running
 * variant of a modality that has one (treadmill, outdoor_run_walk) is stored under [cardioModalityKey] (`"<id>:run"`).
 */
class KnowledgeBase(
    val exercises: Map<String, Exercise>,                 // by id, content overlays merged, ordered by (batch, id)
    val exerciseOrder: List<String>,                      // deterministic tie-break order (HR-18)
    val repProfiles: Map<String, RepProfile>,
    val equipment: Map<String, EquipmentDef>,
    val groups: Map<String, EquipmentGroup>,
    val presets: Map<String, EquipmentPreset>,
    val renamedExerciseIds: Map<String, String>,
    val renamedEquipmentIds: Map<String, String>,
    val templates: Map<String, SessionTemplate>,
    val splits: List<SplitTemplate>,
    val rules: RuleBook,
    val drills: List<Drill>,
    val checkinQuestions: List<CheckinQuestionDef>,
    val supplements: SupplementsFile,
    val cardioModalities: Map<String, CardioModalityEntry>,
    val nutritionTips: List<NutritionTip>,
    val rpLandmarks: Map<Muscle, RpLandmark>,
    val loadTransfer: LoadTransferFile,
) {
    fun exercise(id: String): Exercise? = exercises[id] ?: renamedExerciseIds[id]?.let { exercises[it] }
    fun requireExercise(id: String): Exercise = checkNotNull(exercise(id)) { "unknown exercise id $id" }
    fun repProfile(exercise: Exercise): RepProfile = checkNotNull(repProfiles[exercise.repProfile]) { "unknown rep profile ${exercise.repProfile}" }
    fun prescription(exercise: Exercise, goal: Goal): GoalPrescription =
        exercise.repOverrides[goal.repProfileKey] ?: repProfile(exercise).forGoal(goal)
    fun byPattern(pattern: MovementPattern): List<Exercise> = exerciseOrder.mapNotNull { exercises[it] }.filter { it.pattern == pattern }

    /** Case-insensitive "contains" over name + aliases (EN/DE) and the id; results in knowledge order. */
    fun searchByNameOrAlias(query: String): List<Exercise> {
        val q = normalise(query)
        if (q.isEmpty()) return exerciseOrder.mapNotNull { exercises[it] }
        return exerciseOrder.mapNotNull { exercises[it] }.filter { e ->
            normalise(e.name).contains(q) || normalise(e.id.replace('_', ' ')).contains(q) || e.aliases.any { normalise(it).contains(q) }
        }
    }

    /** Case-insensitive "contains" over equipment name + aliases (EN/DE) and the id; results in catalogue order. */
    fun searchEquipment(query: String): List<EquipmentDef> {
        val q = normalise(query)
        if (q.isEmpty()) return equipment.values.toList()
        return equipment.values.filter { d ->
            normalise(d.name).contains(q) || normalise(d.id.replace('_', ' ')).contains(q) || d.aliases.any { normalise(it).contains(q) }
        }
    }

    /** The CAR-08 row for a cardio exercise; `running = true` returns the running variant when one exists. */
    fun cardioModality(exerciseId: String, running: Boolean): CardioModalityEntry? =
        if (running) cardioModalities[cardioModalityKey(exerciseId, true)] ?: cardioModalities[exerciseId]
        else cardioModalities[exerciseId]

    companion object {
        const val RUNNING_SUFFIX: String = ":run"

        fun cardioModalityKey(exerciseId: String, running: Boolean): String = if (running) exerciseId + RUNNING_SUFFIX else exerciseId

        private fun normalise(s: String): String = s.trim().lowercase(Locale.ROOT)
    }
}
