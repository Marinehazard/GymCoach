// core/src/main/kotlin/com/gymcoach/core/knowledge/ClasspathKnowledgeSource.kt
package com.gymcoach.core.knowledge

class ClasspathKnowledgeSource : com.gymcoach.core.repo.KnowledgeSource {
    override fun exists(relativePath: String): Boolean =
        ClasspathKnowledgeSource::class.java.getResource("/gymcoach/knowledge/$relativePath") != null
    override fun readText(relativePath: String): String =
        checkNotNull(ClasspathKnowledgeSource::class.java.getResourceAsStream("/gymcoach/knowledge/$relativePath")) { "missing knowledge resource $relativePath" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
}
