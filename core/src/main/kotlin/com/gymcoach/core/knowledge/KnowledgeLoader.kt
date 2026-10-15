// core/src/main/kotlin/com/gymcoach/core/knowledge/KnowledgeLoader.kt
package com.gymcoach.core.knowledge

import com.gymcoach.core.model.CardioModalitiesFile
import com.gymcoach.core.model.CardioModalityEntry
import com.gymcoach.core.model.CheckinQuestionsFile
import com.gymcoach.core.model.ContentOverlay
import com.gymcoach.core.model.ContentStatus
import com.gymcoach.core.model.DrillsFile
import com.gymcoach.core.model.EquipmentCatalogFile
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.ExerciseLibraryFile
import com.gymcoach.core.model.LoadTransferFile
import com.gymcoach.core.model.NutritionTipsFile
import com.gymcoach.core.model.RpLandmarksFile
import com.gymcoach.core.model.RuleEntry
import com.gymcoach.core.model.RulesFile
import com.gymcoach.core.model.SupplementsFile
import com.gymcoach.core.model.TemplatesFile
import com.gymcoach.core.repo.KnowledgeSource
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json

class KnowledgeLoader(private val json: Json) {

    /**
     * Reads every file of §3 from the source, merges content overlays, applies renamed ids. Throws IllegalStateException
     * naming the path when a REQUIRED file is missing; a missing exercise_content/batch_N.json is NOT an error
     * (every id of that batch stays `todo`, §3.2).
     */
    fun load(source: KnowledgeSource): KnowledgeBase {
        val library = read(source, EXERCISES, ExerciseLibraryFile.serializer())
        val catalog = read(source, EQUIPMENT, EquipmentCatalogFile.serializer())
        val templatesFile = read(source, TEMPLATES, TemplatesFile.serializer())
        val rulesFile = read(source, RULES, RulesFile.serializer())
        val drillsFile = read(source, DRILLS, DrillsFile.serializer())
        val checkinFile = read(source, CHECKIN, CheckinQuestionsFile.serializer())
        val supplements = read(source, SUPPLEMENTS, SupplementsFile.serializer())
        val cardioFile = read(source, CARDIO, CardioModalitiesFile.serializer())
        val nutritionFile = read(source, NUTRITION, NutritionTipsFile.serializer())
        val rpFile = read(source, RP_LANDMARKS, RpLandmarksFile.serializer())
        val loadTransfer = read(source, LOAD_TRANSFER, LoadTransferFile.serializer())

        val overlays: List<ContentOverlay> = (1..BATCH_COUNT).mapNotNull { n ->
            val path = batchPath(n)
            if (source.exists(path)) read(source, path, ContentOverlay.serializer()) else null
        }
        val merged = mergeOverlays(library.exercises, overlays, library.renamedIds)
        val ordered = merged.sortedWith(compareBy<Exercise>({ it.batch }, { it.id }))
        val exercises = LinkedHashMap<String, Exercise>()
        for (e in ordered) exercises[e.id] = e

        val cardio = LinkedHashMap<String, CardioModalityEntry>()
        for (m in cardioFile.modalities) {
            val key = KnowledgeBase.cardioModalityKey(m.exerciseId, m.isRunning && cardioFile.modalities.any { it.exerciseId == m.exerciseId && !it.isRunning })
            if (!cardio.containsKey(key)) cardio[key] = m
        }
        val rules = LinkedHashMap<String, RuleEntry>()
        for (r in rulesFile.rules) rules[r.id] = r

        return KnowledgeBase(
            exercises = exercises,
            exerciseOrder = ordered.map { it.id },
            repProfiles = library.repProfiles.associateByTo(LinkedHashMap()) { it.id },
            equipment = catalog.equipment.associateByTo(LinkedHashMap()) { it.id },
            groups = catalog.groups.associateByTo(LinkedHashMap()) { it.id },
            presets = catalog.presets.associateByTo(LinkedHashMap()) { it.id },
            renamedExerciseIds = library.renamedIds,
            renamedEquipmentIds = catalog.renamedIds,
            templates = templatesFile.sessionTemplates.associateByTo(LinkedHashMap()) { it.id },
            splits = templatesFile.splits,
            rules = RuleBook(rules),
            drills = drillsFile.drills,
            checkinQuestions = checkinFile.questions,
            supplements = supplements,
            cardioModalities = cardio,
            nutritionTips = nutritionFile.tips,
            rpLandmarks = rpFile.landmarks.associateByTo(LinkedHashMap()) { it.muscle },
            loadTransfer = loadTransfer,
        )
    }

    private fun <T> read(source: KnowledgeSource, path: String, strategy: DeserializationStrategy<T>): T {
        check(source.exists(path)) { "missing knowledge file $path" }
        val text = source.readText(path)
        return try {
            json.decodeFromString(strategy, text)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("cannot decode knowledge file $path: ${e.message}", e)
        }
    }

    companion object {
        const val EXERCISES: String = "exercises.json"
        const val EQUIPMENT: String = "equipment.json"
        const val TEMPLATES: String = "templates.json"
        const val RULES: String = "rules.json"
        const val DRILLS: String = "mobility_drills.json"
        const val CHECKIN: String = "checkin_questions.json"
        const val SUPPLEMENTS: String = "supplements.json"
        const val CARDIO: String = "cardio_modalities.json"
        const val NUTRITION: String = "nutrition_rules.json"
        const val RP_LANDMARKS: String = "rp_landmarks.json"
        const val LOAD_TRANSFER: String = "load_transfer.json"
        const val BATCH_COUNT: Int = 6

        /** Every required file of §3 (the six content overlays are optional). */
        val REQUIRED_FILES: List<String> = listOf(EXERCISES, EQUIPMENT, TEMPLATES, RULES, DRILLS, CHECKIN, SUPPLEMENTS, CARDIO, NUTRITION, RP_LANDMARKS, LOAD_TRANSFER)

        fun batchPath(batch: Int): String = "exercise_content/batch_$batch.json"

        /**
         * §3.2: an overlay entry becomes Exercise.content; its status is the overlay's `status[id]` (DRAFT when the entry exists
         * but has no status). Overlay ids that were renamed are mapped first; ids unknown to the library are ignored here
         * (KnowledgeValidator / ContentBatchesTest report them). Exercises without an entry keep their own content/status.
         */
        fun mergeOverlays(exercises: List<Exercise>, overlays: List<ContentOverlay>, renamedIds: Map<String, String>): List<Exercise> {
            if (overlays.isEmpty()) return exercises
            val content = HashMap<String, Pair<com.gymcoach.core.model.ExerciseContent, ContentStatus>>()
            for (overlay in overlays) {
                for ((rawId, entry) in overlay.entries) {
                    val id = renamedIds[rawId] ?: rawId
                    val status = overlay.status[rawId] ?: ContentStatus.DRAFT
                    content[id] = entry to status
                }
            }
            return exercises.map { e ->
                val c = content[e.id]
                if (c == null) e else e.copy(content = c.first, contentStatus = c.second)
            }
        }
    }
}
