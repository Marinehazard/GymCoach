// core/src/main/kotlin/com/gymcoach/core/knowledge/KnowledgeValidator.kt
package com.gymcoach.core.knowledge

import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.equipment.Presets
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.GroupKind
import com.gymcoach.core.model.GymItem
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.SessionEquipment
import java.time.Instant

/**
 * R4 §6.3 rules 1–8 plus rule 9 (renamed ids), rule 10 (templates reference existing patterns/muscles; every `backoff_of`
 * points at a `heavy` slot AND, for every split row, every `backoff_of` of every template listed in that row resolves to a
 * heavy slot of a template listed in the SAME row — §3.4; the strength rows are real `SplitTemplate` entries, so the check
 * runs over `kb.splits` as loaded, with no code-side substitution) and rule 11 (RuleIds.ALL ⊆ rules.json, incl. the
 * prose-only ids of §3.5). Returns violations; empty = valid. Each message starts with "R<n> ".
 */
class KnowledgeValidator(private val kb: KnowledgeBase, private val frozenIds: List<String>) {

    fun validate(): List<String> {
        val out = ArrayList<String>()
        rule1Ids(out)
        rule2Equipment(out)
        rule3BaseGym(out)
        rule4Travel(out)
        rule5Muscles(out)
        rule6Flags(out)
        rule7RoundTrip(out)
        rule8Batches(out)
        rule9Renamed(out)
        rule10Templates(out)
        rule11Rules(out)
        crossReferences(out)
        contentTables(out)
        return out
    }

    private fun rule1Ids(out: MutableList<String>) {
        val allIds = kb.exercises.keys + kb.equipment.keys + kb.groups.keys + kb.repProfiles.keys + kb.drills.map { it.id }
        for (id in allIds) if (!ID.matches(id)) out += "R1 id '$id' does not match ${ID.pattern}"
        val dupItems = (kb.equipment.keys.toList() + kb.groups.keys.toList()).groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        for (d in dupItems) out += "R1 id '$d' is both an equipment id and a group id"
        val drillDup = kb.drills.groupingBy { it.id }.eachCount().filter { it.value > 1 }.keys
        for (d in drillDup) out += "R1 duplicate drill id '$d'"
        for (e in kb.exercises.values) {
            if (e.substitutions.toSet().size != e.substitutions.size) out += "R1 ${e.id}: duplicate substitutions"
            for (s in e.substitutions) {
                if (s == e.id) out += "R1 ${e.id}: substitutes itself"
                if (kb.exercise(s) == null) out += "R1 ${e.id}: unknown substitution '$s'"
            }
            if (e.repProfile !in kb.repProfiles) out += "R1 ${e.id}: unknown rep profile '${e.repProfile}'"
            for (k in e.repOverrides.keys) if (k !in GOAL_KEYS) out += "R1 ${e.id}: rep_overrides key '$k' is not a goal key"
            if (e.equipmentOptions.isEmpty()) out += "R1 ${e.id}: no equipment option"
            if (e.equipmentOptions.count { it.preferred } > 1) out += "R1 ${e.id}: more than one preferred option"
            for (o in e.equipmentOptions) {
                if (o.allOf.toSet().size != o.allOf.size) out += "R1 ${e.id}: duplicate item in option ${o.allOf}"
                for (item in o.allOf) if (!resolvesItem(item)) out += "R1 ${e.id}: unknown equipment item '$item'"
            }
        }
        for (p in kb.repProfiles.values) for (k in GOAL_KEYS) if (k !in p.byGoal) out += "R1 rep profile ${p.id} lacks goal '$k'"
    }

    private fun resolvesItem(item: String): Boolean {
        val id = kb.renamedEquipmentIds[item] ?: item
        return id in kb.equipment || id in kb.groups
    }

    private fun rule2Equipment(out: MutableList<String>) {
        for (g in kb.groups.values) {
            if (g.members.isEmpty()) out += "R2 group ${g.id} has no members"
            if (g.members.toSet().size != g.members.size) out += "R2 group ${g.id} has duplicate members"
            for (m in g.members) {
                if (m in kb.groups) out += "R2 group ${g.id} nests group $m"
                else if (m !in kb.equipment) out += "R2 group ${g.id}: unknown member $m"
            }
        }
        for (d in kb.equipment.values) {
            for (p in d.defaultIn) if (p !in kb.presets) out += "R2 ${d.id}: default_in names unknown preset $p"
            if (d.defaultIn.toSet().size != d.defaultIn.size) out += "R2 ${d.id}: duplicate default_in"
            val keys = d.params.map { it.key }
            if (keys.toSet().size != keys.size) out += "R2 ${d.id}: duplicate param keys"
            for (k in keys) if (!ID.matches(k)) out += "R2 ${d.id}: param key '$k' does not match ${ID.pattern}"
        }
        for (p in kb.presets.values) {
            if (p.id !in PRESET_IDS) out += "R2 unknown preset id ${p.id}"
            for (item in p.items) {
                val def = kb.equipment[item.equipmentId]
                if (def == null) { out += "R2 preset ${p.id}: unknown equipment ${item.equipmentId}"; continue }
                val declared = def.params.map { it.key }.toSet()
                for (k in item.params.keys) if (k !in declared) out += "R2 preset ${p.id}: ${item.equipmentId}.$k is not a declared param"
            }
        }
    }

    private fun rule3BaseGym(out: MutableList<String>) {
        if (Presets.BASE_GYM !in kb.presets) { out += "R3 base_gym preset missing"; return }
        val available = EquipmentResolver(kb).availableExerciseIds(presetEquipment(Presets.BASE_GYM))
        for (e in kb.exercises.values) {
            if (e.id !in available && TAG_OPTIONAL !in e.tags) out += "R3 ${e.id} is unavailable in base_gym and lacks the '$TAG_OPTIONAL' tag"
        }
    }

    private fun rule4Travel(out: MutableList<String>) {
        if (Presets.HOTEL_GYM !in kb.presets || Presets.TRAVEL_KIT !in kb.presets) { out += "R4 hotel_gym / travel_kit preset missing"; return }
        val resolver = EquipmentResolver(kb)
        val hotel = resolver.availableExerciseIds(presetEquipment(Presets.HOTEL_GYM)).size
        if (hotel < HOTEL_MIN) out += "R4 hotel_gym makes only $hotel exercises available (< $HOTEL_MIN)"
        val kit = resolver.availableExerciseIds(presetEquipment(Presets.TRAVEL_KIT)).size
        if (kit < TRAVEL_KIT_MIN) out += "R4 travel_kit alone makes only $kit exercises available (< $TRAVEL_KIT_MIN)"
    }

    private fun rule5Muscles(out: MutableList<String>) {
        for (e in kb.exercises.values) {
            for (m in e.muscles) if (m.weight !in WEIGHTS) out += "R5 ${e.id}: weight ${m.weight} for ${m.muscle} not in $WEIGHTS"
            if (e.muscles.map { it.muscle }.toSet().size != e.muscles.size) out += "R5 ${e.id}: muscle listed twice"
            if (e.isCardioOrMobility) continue
            val primaries = e.muscles.count { it.weight == 1.0 }
            val minPrimaries = if (e.pattern == MovementPattern.POWER || e.id in NO_PRIMARY_EXEMPT) 0 else 1
            if (primaries < minPrimaries || primaries > 2) out += "R5 ${e.id}: $primaries weight-1.0 muscles (expected $minPrimaries..2)"
        }
    }

    private fun rule6Flags(out: MutableList<String>) {
        for (e in kb.exercises.values) {
            if (e.flags.requiresSpotterOrSafeties) {
                val ok = e.equipmentOptions.any { o -> o.allOf.any { it == RACK_OR_SPOTTER || it == RACK_ANY || it == SMITH } }
                if (!ok) out += "R6 ${e.id}: SP flag but no option names rack_or_spotter, rack_any or smith_machine"
            }
            if (e.flags.compound && e.muscles.size < 2) out += "R6 ${e.id}: compound with fewer than 2 muscles"
        }
        // §3.1 decision: bar-over-face lifts name rack_or_spotter in every non-Smith option.
        for (id in BAR_OVER_FACE) {
            val e = kb.exercises[id] ?: continue
            for (o in e.equipmentOptions) {
                val lt = o.loadType ?: e.loadType
                if (lt != LoadType.SMITH && RACK_OR_SPOTTER !in o.allOf) out += "R6 $id: option ${o.allOf} lacks rack_or_spotter (SPEC §3.1 decision)"
            }
        }
    }

    private fun rule7RoundTrip(out: MutableList<String>) {
        val json = CoreJson.assets
        for (e in kb.exercises.values) {
            val again = json.decodeFromString(Exercise.serializer(), json.encodeToString(Exercise.serializer(), e))
            if (again != e) out += "R7 ${e.id}: decode/encode/decode round trip differs"
        }
    }

    private fun rule8Batches(out: MutableList<String>) {
        for (e in kb.exercises.values) if (e.batch !in 1..6) out += "R8 ${e.id}: batch ${e.batch} outside 1..6"
        val frozen = frozenIds.map { kb.renamedExerciseIds[it] ?: it }.toSet()
        for (id in kb.exercises.keys) if (id !in frozen) out += "R8 $id is not in the frozen id list (add it to the next ids_vN.txt)"
        val perBatch = kb.exercises.values.groupBy { it.batch }.mapValues { it.value.size }
        if ((1..6).any { (perBatch[it] ?: 0) == 0 }) out += "R8 a batch 1..6 has no exercises: $perBatch"
    }

    private fun rule9Renamed(out: MutableList<String>) {
        for (id in frozenIds) {
            if (id in kb.exercises) continue
            val target = kb.renamedExerciseIds[id]
            if (target == null) out += "R9 frozen id '$id' was removed without a renamed_ids entry"
            else if (target !in kb.exercises) out += "R9 renamed id '$id' -> '$target' points at an unknown exercise"
        }
        for ((old, new) in kb.renamedExerciseIds) if (new !in kb.exercises) out += "R9 renamed_ids $old -> $new: target unknown"
        for ((old, new) in kb.renamedEquipmentIds) if (new !in kb.equipment && new !in kb.groups) out += "R9 equipment renamed_ids $old -> $new: target unknown"
    }

    private fun rule10Templates(out: MutableList<String>) {
        for (t in kb.templates.values) {
            val ids = t.slots.map { it.slotId }
            if (ids.toSet().size != ids.size) out += "R10 ${t.id}: duplicate slot ids"
            if (t.slots.isEmpty()) out += "R10 ${t.id}: no slots"
            for (s in t.slots) {
                if (s.patterns.isEmpty()) out += "R10 ${t.id}.${s.slotId}: no patterns"
                if (s.patterns.any { p -> kb.exerciseOrder.none { kb.exercises[it]?.pattern == p } }) out += "R10 ${t.id}.${s.slotId}: a pattern has no exercise"
                s.supersetWith?.let { other ->
                    val partner = t.slots.firstOrNull { it.slotId == other }
                    if (partner == null) out += "R10 ${t.id}.${s.slotId}: superset partner $other missing"
                    else if (partner.supersetWith != s.slotId) out += "R10 ${t.id}.${s.slotId}: superset with $other is not reciprocal"
                }
                s.backoffOf?.let { ref ->
                    val r = SessionTemplates.parseRef(ref)
                    val target = r?.let { SessionTemplates.slot(kb, it) }
                    if (target == null) out += "R10 ${t.id}.${s.slotId}: backoff_of '$ref' does not resolve"
                    else if (!target.heavy) out += "R10 ${t.id}.${s.slotId}: backoff_of '$ref' is not a heavy slot"
                    if (s.heavy) out += "R10 ${t.id}.${s.slotId}: a back-off slot cannot be heavy"
                }
            }
        }
        for ((index, split) in kb.splits.withIndex()) {
            val row = "split[$index] ${split.kind}"
            if (split.sessionTemplateIds.size != split.daysPerWeek) out += "R10 $row: ${split.sessionTemplateIds.size} templates for ${split.daysPerWeek} days"
            if (split.defaultWeekdays.size != split.daysPerWeek) out += "R10 $row: ${split.defaultWeekdays.size} weekdays for ${split.daysPerWeek} days"
            if (split.defaultWeekdays.any { it !in 1..7 } || split.defaultWeekdays != split.defaultWeekdays.sorted().distinct()) out += "R10 $row: weekdays must be ascending, distinct ISO 1..7"
            for (tid in split.sessionTemplateIds) {
                val t = kb.templates[tid]
                if (t == null) { out += "R10 $row: unknown template $tid"; continue }
                for (s in t.slots) {
                    val ref = s.backoffOf?.let { SessionTemplates.parseRef(it) } ?: continue
                    if (ref.templateId !in split.sessionTemplateIds) out += "R10 $row: $tid.${s.slotId} backs off ${ref} which is not in the same row"
                }
            }
        }
    }

    private fun rule11Rules(out: MutableList<String>) {
        for (id in RuleIds.ALL) if (kb.rules.get(id) == null) out += "R11 rule id $id missing from rules.json"
        for ((id, e) in kb.rules.entries) {
            if (id != e.id) out += "R11 rules map key $id != entry id ${e.id}"
            if (e.statement.isBlank() || e.citation.isBlank()) out += "R11 $id: blank statement or citation"
        }
    }

    private fun crossReferences(out: MutableList<String>) {
        for (d in kb.drills) d.exerciseId?.let { if (kb.exercise(it) == null) out += "X drill ${d.id}: unknown exercise $it" }
        for (q in kb.checkinQuestions) for (r in q.triggerRuleIds) if (kb.rules.get(r) == null) out += "X check-in ${q.id}: unknown rule $r"
        for (t in kb.nutritionTips) if (kb.rules.get(t.ruleId) == null) out += "X tip ${t.id}: unknown rule ${t.ruleId}"
        for (m in kb.cardioModalities.values) {
            val e = kb.exercise(m.exerciseId)
            if (e == null || e.pattern != MovementPattern.CARDIO) out += "X cardio modality ${m.exerciseId} is not a cardio exercise"
        }
        for (s in kb.loadTransfer.seeds) {
            if (kb.exercise(s.fromExerciseId) == null) out += "X load transfer: unknown ${s.fromExerciseId}"
            if (kb.exercise(s.toExerciseId) == null) out += "X load transfer: unknown ${s.toExerciseId}"
        }
    }

    /**
     * §3.7/§3.8/§3.5 content tables the screens and the coach digest rely on (nothing is computed from them):
     * the CHK-01 bank has all 15 items Q1..Q15; nutrition_rules.json carries the NUT-02/03/12/13/14 tips; supplements.json
     * has every tier A/B row SUP-01..SUP-10 and at least one tier-C SUP-11.n do-not row; the prose-only policy id NUT-18
     * (weight-class cuts: out of scope, refer) is a constant-free, non-adjustable rules.json entry.
     */
    private fun contentTables(out: MutableList<String>) {
        val questionIds = kb.checkinQuestions.map { it.id }.toSet()
        val bank = (1..CHK01_BANK_SIZE).map { "Q$it" }
        for (q in bank) if (q !in questionIds) out += "X ${RuleIds.CHK_01} check-in bank lacks $q"
        val tipRules = kb.nutritionTips.map { it.ruleId }.toSet()
        for (r in listOf(RuleIds.NUT_02, RuleIds.NUT_03, RuleIds.NUT_12, RuleIds.NUT_13, RuleIds.NUT_14)) if (r !in tipRules) out += "X nutrition_rules.json has no tip for $r"
        val supplements = kb.supplements.entries
        val supIds = supplements.map { it.id }.toSet()
        val abRows = listOf(
            RuleIds.SUP_01, RuleIds.SUP_02, RuleIds.SUP_03, RuleIds.SUP_04, RuleIds.SUP_05,
            RuleIds.SUP_06, RuleIds.SUP_07, RuleIds.SUP_08, RuleIds.SUP_09, RuleIds.SUP_10,
        )
        for (r in abRows) if (r !in supIds) out += "X supplements.json lacks $r"
        for (e in supplements) if (e.id in abRows && e.tier == TIER_C) out += "X supplements.json: ${e.id} is tier C"
        val doNot = supplements.filter { it.id.startsWith(RuleIds.SUP_11 + ".") }
        if (doNot.isEmpty()) out += "X supplements.json has no ${RuleIds.SUP_11} do-not rows"
        for (e in doNot) if (e.tier != TIER_C || e.doNotReason.isNullOrBlank()) out += "X supplements.json: ${e.id} must be tier C with a do_not_reason"
        val nut18 = kb.rules.get(RuleIds.NUT_18)
        if (nut18 != null && (nut18.constants.isNotEmpty() || nut18.userAdjustable)) out += "X ${RuleIds.NUT_18} must be a constant-free policy entry"
    }

    private fun presetEquipment(presetId: String): SessionEquipment {
        val preset = kb.presets.getValue(presetId)
        return SessionEquipment(GymSet(id = "validator_$presetId", name = presetId, items = preset.items.map { GymItem(it.equipmentId, it.params) }, createdAt = EPOCH))
    }

    companion object {
        private val ID = Regex("^[a-z][a-z0-9_]{1,63}$")
        private val GOAL_KEYS = setOf("strength", "hypertrophy", "endurance", "general_fitness")
        private val WEIGHTS = setOf(1.0, 0.5, 0.25)
        private val PRESET_IDS = setOf(Presets.BASE_GYM, Presets.HOME_GYM, Presets.HOTEL_GYM, Presets.TRAVEL_KIT)
        private val EPOCH: Instant = Instant.EPOCH
        const val TAG_OPTIONAL: String = "optional_equipment"
        const val RACK_OR_SPOTTER: String = "rack_or_spotter"
        const val RACK_ANY: String = "rack_any"
        private const val CHK01_BANK_SIZE: Int = 15
        private const val TIER_C: String = "C"
        const val SMITH: String = "smith_machine"
        const val HOTEL_MIN: Int = 40
        const val TRAVEL_KIT_MIN: Int = 25

        /** SPEC §3.1 safeties/spotter decision: the three barbell bench variants. */
        val BAR_OVER_FACE: List<String> = listOf("barbell_bench_press", "incline_barbell_bench_press", "close_grip_bench_press")

        /** R4 §6.3 rule 5 exemption: bird_dog is printed with abs 0.5 / lower_back 0.5 / glutes 0.25 (no prime mover). POWER is exempt by pattern (R4 §4.3 "movement-specific at 0.5"). */
        val NO_PRIMARY_EXEMPT: Set<String> = setOf("bird_dog")
    }
}
