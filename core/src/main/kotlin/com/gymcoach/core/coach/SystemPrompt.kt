// core/src/main/kotlin/com/gymcoach/core/coach/SystemPrompt.kt
package com.gymcoach.core.coach

import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.util.Hashing

/**
 * §6.4: the byte-stable system prompt = `gymcoach/coach/system_prompt_v1.txt` (verbatim from SPEC, ends with the
 * "## Exercise vocabulary (id — name)" heading) + one `id — name` line per exercise in knowledge order, then a
 * `## Patterns` and a `## Muscles` list (serial names). Nothing per-user or per-day is ever part of it.
 */
object SystemPrompt {
    const val PROMPT_VERSION: String = "v1"
    const val RESOURCE: String = "gymcoach/coach/system_prompt_v1.txt"
    const val COMPACTION_RESOURCE: String = "gymcoach/coach/compaction_prompt_v1.txt"

    private val base: String by lazy { readResource(RESOURCE) }

    fun assemble(kb: KnowledgeBase): String {
        val sb = StringBuilder(base.length + 8192)
        sb.append(base)
        if (!base.endsWith("\n")) sb.append('\n')
        for (id in kb.exerciseOrder) {
            val e = kb.exercises[id] ?: continue
            sb.append(e.id).append(" — ").append(e.name).append('\n')
        }
        sb.append('\n').append("## Patterns").append('\n')
        val pd = MovementPattern.serializer().descriptor
        for (i in 0 until pd.elementsCount) sb.append(pd.getElementName(i)).append('\n')
        sb.append('\n').append("## Muscles").append('\n')
        val md = Muscle.serializer().descriptor
        for (i in 0 until md.elementsCount) sb.append(md.getElementName(i)).append('\n')
        return sb.toString()
    }

    fun hash(kb: KnowledgeBase): String = Hashing.sha256Hex(assemble(kb))

    /** The fixed compaction prompt of §6.9 (trimmed of its trailing newline). */
    fun compactionPrompt(): String = readResource(COMPACTION_RESOURCE).trimEnd('\n')

    private fun readResource(path: String): String {
        val loader = SystemPrompt::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
        val stream = checkNotNull(loader.getResourceAsStream(path)) { "missing resource $path" }
        return stream.use { String(it.readBytes(), Charsets.UTF_8) }
    }
}
