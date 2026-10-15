package com.gymcoach.app.ui.knowledge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.RuleBook
import com.gymcoach.core.model.RuleEntry
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.repo.SettingsRepository
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/** One flattened number of a constant (§8.16): key follows the rules.json convention (`novice.lo`, `heavyPcts.2`). */
data class ConstantRow(
    val group: String,
    val field: String,
    val key: String,
    val default: Double,
    val current: Double,
    val adjustable: Boolean,
)

/** One LEAF of PlannerConstants with its JSON default / current value and kind ("int" | "double" | "band" | "list" | "bool"). */
data class LeafInfo(
    val group: String,
    val field: String,
    val ruleId: String,
    val kind: String,
    val defaultJson: JsonElement,
    val currentJson: JsonElement,
) {
    val id: String
        get() = this.group + "." + this.field
}

/** Shared by Knowledge (read-only rows) and Settings > Advanced (editor): walks `PlannerConstants.leafFields()` without reflection. */
object ConstantsModel {
    fun leaves(defaults: PlannerConstants, current: PlannerConstants, rules: RuleBook): List<LeafInfo> {
        val json = CoreJson.stored
        val d = json.encodeToJsonElement(PlannerConstants.serializer(), defaults).jsonObject
        val c = json.encodeToJsonElement(PlannerConstants.serializer(), current).jsonObject
        return PlannerConstants.leafFields().map { (group, field, _) ->
            val dv = checkNotNull(d[group]?.jsonObject?.get(field)) { "default $group.$field" }
            val cv = checkNotNull(c[group]?.jsonObject?.get(field)) { "current $group.$field" }
            val ruleId = PlannerConstants.ruleIdOf(field)
            LeafInfo(group, field, ruleId, kindOf(dv, field, rules.get(ruleId)), dv, cv)
        }
    }

    fun kindOf(dv: JsonElement, field: String, rule: RuleEntry?): String {
        if (dv is JsonArray) {
            val key = PlannerConstants.constantKeyOf(field)
            val band = rule?.constants?.containsKey("$key.lo") ?: false
            val allInts = dv.size == 2 && dv.all { (it as? JsonPrimitive)?.content?.all { ch -> ch.isDigit() || ch == '-' } == true }
            return if (band || (rule == null && allInts)) "band" else "list"
        }
        if (dv is JsonPrimitive) {
            if (!dv.isString && dv.booleanOrNull != null) return "bool"
            return if (dv.content.contains('.') || dv.content.contains('E') || dv.content.contains('e')) "double" else "int"
        }
        return "double"
    }

    /** Numbers of a leaf value (bool -> 1/0). */
    fun numbers(e: JsonElement): List<Double> = when (e) {
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
        is JsonPrimitive -> listOf(e.booleanOrNull?.let { if (it) 1.0 else 0.0 } ?: e.doubleOrNull ?: 0.0)
        else -> emptyList()
    }

    fun keys(leaf: LeafInfo, count: Int): List<String> {
        val base = PlannerConstants.constantKeyOf(leaf.field)
        return when (leaf.kind) {
            "band" -> listOf("$base.lo", "$base.hi")
            "list" -> (0 until count).map { "$base.$it" }
            else -> listOf(base)
        }
    }

    /** Parses the editor text of one leaf ("8,12" / "0.4,0.55" / "6" / "true") into JSON of the same shape; null when invalid. */
    fun parse(kind: String, text: String, defaultJson: JsonElement): JsonElement? {
        val parts = text.split(',', ';').map { it.trim().replace(',', '.') }.filter { it.isNotEmpty() }
        return when (kind) {
            "bool" -> text.trim().lowercase().let { if (it == "true") JsonPrimitive(true) else if (it == "false") JsonPrimitive(false) else null }
            "int" -> parts.singleOrNull()?.toIntOrNull()?.let { JsonPrimitive(it) }
            "double" -> parts.singleOrNull()?.toDoubleOrNull()?.let { JsonPrimitive(it) }
            "band" -> {
                val lo = parts.getOrNull(0)?.toIntOrNull()
                val hi = parts.getOrNull(1)?.toIntOrNull()
                if (parts.size == 2 && lo != null && hi != null && lo <= hi) JsonArray(listOf(JsonPrimitive(lo), JsonPrimitive(hi))) else null
            }
            else -> {
                val intList = (defaultJson as? JsonArray)?.all { (it as? JsonPrimitive)?.content?.contains('.') == false } == true
                val expected = (defaultJson as? JsonArray)?.size
                val nums = parts.map { it.toDoubleOrNull() ?: return null }
                if (nums.isEmpty() || (expected != null && nums.size != expected)) null
                else JsonArray(nums.map { if (intList) JsonPrimitive(it.toInt()) else JsonPrimitive(it) })
            }
        }
    }

    /** Display text: bands "8–12", lists "0.4, 0.55", scalars as is. */
    fun format(kind: String, e: JsonElement): String {
        fun n(d: Double): String =
            if (kind != "double" && d == Math.rint(d)) d.toLong().toString() else String.format(Locale.ROOT, "%.4f", d).trimEnd('0').trimEnd('.')
        val nums = numbers(e)
        return when (kind) {
            "bool" -> (nums.firstOrNull() == 1.0).toString()
            "band" -> nums.joinToString("–") { n(it) }
            "list" -> nums.joinToString(", ") { n(it) }
            else -> nums.firstOrNull()?.let { n(it) } ?: ""
        }
    }

    /** Overrides text -> mutable nested object (invalid JSON -> empty). */
    fun parseOverrides(json: String): JsonObject =
        try { if (json.isBlank()) JsonObject(emptyMap()) else CoreJson.stored.parseToJsonElement(json).jsonObject } catch (e: Exception) { JsonObject(emptyMap()) }
}

/** §8.16 Knowledge: searchable list of every rule, grouped by section. */
data class KnowledgeUiState(val query: String, val section: String?, val sections: List<String>, val rules: List<RuleEntry>)

class KnowledgeViewModel(rulebook: RuleBook) : ViewModel() {
    private val all: List<RuleEntry> = rulebook.entries.values.toList()
    private val sectionList: List<String> = all.map { it.section }.distinct()
    private val _state = MutableStateFlow(KnowledgeUiState("", null, sectionList, all))
    val state: StateFlow<KnowledgeUiState> = _state.asStateFlow()

    fun onQuery(q: String) = _state.update { filtered(it.copy(query = q)) }
    fun onSection(section: String?) = _state.update { filtered(it.copy(section = section)) }

    private fun filtered(s: KnowledgeUiState): KnowledgeUiState {
        val q = s.query.trim().lowercase()
        val rules = all.filter { r ->
            (s.section == null || r.section == s.section) &&
                (q.isEmpty() || r.id.lowercase().contains(q) || r.statement.lowercase().contains(q) || r.citation.lowercase().contains(q))
        }
        return s.copy(rules = rules)
    }
}

/** §8.16 rule detail: statement, confidence, citation and the constants (current vs default) of this rule. */
data class RuleDetailUiState(val rule: RuleEntry?, val constants: List<ConstantRow>)

class RuleDetailViewModel(
    private val ruleId: String,
    private val rulebook: RuleBook,
    private val settings: SettingsRepository,
    private val plannerConstants: (String) -> PlannerConstants,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(RuleDetailUiState(rulebook.get(ruleId), emptyList()))
    val state: StateFlow<RuleDetailUiState> = _state.asStateFlow()

    init {
        scope.launch {
            val rule = rulebook.get(ruleId) ?: return@launch
            val current = plannerConstants(settings.get().plannerOverridesJson)
            val rows = ConstantsModel.leaves(PlannerConstants.DEFAULT, current, rulebook)
                .filter { it.ruleId == rule.id }
                .flatMap { leaf ->
                    val d = ConstantsModel.numbers(leaf.defaultJson)
                    val c = ConstantsModel.numbers(leaf.currentJson)
                    val keys = ConstantsModel.keys(leaf, d.size)
                    d.indices.map { i -> ConstantRow(leaf.group, leaf.field, keys.getOrElse(i) { keys.last() }, d[i], c.getOrElse(i) { d[i] }, rule.userAdjustable) }
                }
            _state.update { it.copy(constants = rows) }
        }
    }
}
