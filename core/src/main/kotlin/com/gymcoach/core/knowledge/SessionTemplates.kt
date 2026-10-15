// core/src/main/kotlin/com/gymcoach/core/knowledge/SessionTemplates.kt
package com.gymcoach.core.knowledge

import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.SessionTemplate
import com.gymcoach.core.model.SlotTemplate
import com.gymcoach.core.model.SplitTemplate
import com.gymcoach.core.model.TrainingAge

/** A parsed `backoff_of` / slot reference "<templateId>.<slotId>" (SPEC §3.4). */
data class SlotRef(val templateId: String, val slotId: String) {
    override fun toString(): String = "$templateId.$slotId"
}

/** Read-only helpers over templates.json (SPEC §3.4). No selection logic: SplitSelector (WP-03) owns that. */
object SessionTemplates {

    /** "UPPER_B_S.s3" → SlotRef("UPPER_B_S", "s3"); null when malformed. */
    fun parseRef(ref: String): SlotRef? {
        val dot = ref.lastIndexOf('.')
        if (dot <= 0 || dot == ref.length - 1) return null
        return SlotRef(ref.substring(0, dot), ref.substring(dot + 1))
    }

    fun template(kb: KnowledgeBase, id: String): SessionTemplate? = kb.templates[id]

    fun requireTemplate(kb: KnowledgeBase, id: String): SessionTemplate = checkNotNull(kb.templates[id]) { "unknown session template $id" }

    fun slot(kb: KnowledgeBase, ref: SlotRef): SlotTemplate? = kb.templates[ref.templateId]?.slots?.firstOrNull { it.slotId == ref.slotId }

    /** The heavy slot a back-off slot points at (null when the slot is not a back-off slot or the reference is broken). */
    fun backoffTarget(kb: KnowledgeBase, slot: SlotTemplate): SlotTemplate? = slot.backoffOf?.let { parseRef(it) }?.let { slot(kb, it) }

    /** Row applicability exactly as §3.4 defines it: an empty `for_goals` / `for_training_ages` list means "any". */
    fun applies(split: SplitTemplate, daysPerWeek: Int, goal: Goal, trainingAge: TrainingAge): Boolean =
        split.daysPerWeek == daysPerWeek &&
            (split.forGoals.isEmpty() || goal in split.forGoals) &&
            (split.forTrainingAges.isEmpty() || trainingAge in split.forTrainingAges)

    /** Every applicable row in file order (the travel_2 row is excluded: it is chosen by week mode, never by the row walk). */
    fun applicableSplits(kb: KnowledgeBase, daysPerWeek: Int, goal: Goal, trainingAge: TrainingAge): List<SplitTemplate> =
        kb.splits.filter { it.kind != com.gymcoach.core.model.SplitKind.TRAVEL_2 && applies(it, daysPerWeek, goal, trainingAge) }

    /** The templates of a split row, in weekly order. */
    fun templatesOf(kb: KnowledgeBase, split: SplitTemplate): List<SessionTemplate> = split.sessionTemplateIds.map { requireTemplate(kb, it) }
}
