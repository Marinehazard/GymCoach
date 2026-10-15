// core/src/main/kotlin/com/gymcoach/core/knowledge/RuleBook.kt
package com.gymcoach.core.knowledge

import com.gymcoach.core.model.RuleEntry
import java.util.Locale

/** rules.json (SPEC §3.5): statement, confidence, citation and the PlannerConstants defaults of every rule id. */
class RuleBook(val entries: Map<String, RuleEntry>) {
    fun get(id: String): RuleEntry? = entries[id]
    fun require(id: String): RuleEntry = checkNotNull(entries[id]) { "unknown rule $id" }
    fun constant(id: String, key: String): Double = checkNotNull(require(id).constants[key]) { "rule $id has no constant $key" }
    fun citation(id: String): String = require(id).citation

    /** One line per rule: "VOL-04 [weak] Hypertrophy sets/muscle/week … — Schoenfeld 2017; Pelland 2025" (used by the coach digest and Knowledge screen). */
    fun digestLine(id: String): String {
        val e = require(id)
        return "${e.id} [${e.confidence.name.lowercase(Locale.ROOT)}] ${e.statement} — ${e.citation}"
    }
}
