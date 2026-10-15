package com.gymcoach.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §1.1 / §10.2: `:core` never imports `android.*`, `androidx.*` or OkHttp. Scans the main source tree
 * textually (no reflection, HR-5). Gradle runs tests with the module directory as the working directory;
 * the fallback covers an IDE run from the repository root.
 */
class NoAndroidImportsTest {

    private fun mainSourceRoot(): File {
        val candidates = listOf(File("src/main"), File("core/src/main"))
        return candidates.firstOrNull { it.isDirectory }
            ?: fail("core main source root not found from ${File(".").absolutePath}")
    }

    private fun kotlinFiles(): List<File> =
        mainSourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun offendingLines(prefixes: List<String>): List<String> {
        val hits = mutableListOf<String>()
        for (file in kotlinFiles()) {
            file.readLines(Charsets.UTF_8).forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (prefixes.any { trimmed.startsWith(it) }) hits += "${file.path}:${index + 1}: $trimmed"
            }
        }
        return hits
    }

    @Test
    fun coreMainHasSourceFiles() {
        assertTrue(kotlinFiles().size >= 25, "expected the §2 contract files under core/src/main, found ${kotlinFiles().size}")
    }

    @Test
    fun noAndroidImportsUnderCoreMain() {
        val hits = offendingLines(listOf("import android.", "import android;"))
        assertTrue(hits.isEmpty(), "android imports in :core:\n" + hits.joinToString("\n"))
    }

    @Test
    fun noAndroidxImportsUnderCoreMain() {
        val hits = offendingLines(listOf("import androidx."))
        assertTrue(hits.isEmpty(), "androidx imports in :core:\n" + hits.joinToString("\n"))
    }

    @Test
    fun noOkHttpImportsUnderCoreMain() {
        val hits = offendingLines(listOf("import okhttp3.", "import okio."))
        assertTrue(hits.isEmpty(), "OkHttp/Okio imports in :core (transport belongs to :app, §1.1):\n" + hits.joinToString("\n"))
    }

    @Test
    fun noWildcardImportsUnderCoreMain() {
        val hits = mutableListOf<String>()
        for (file in kotlinFiles()) {
            file.readLines(Charsets.UTF_8).forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import ") && trimmed.endsWith(".*")) hits += "${file.path}:${index + 1}: $trimmed"
            }
        }
        assertTrue(hits.isEmpty(), "wildcard imports violate HR-1:\n" + hits.joinToString("\n"))
    }
}
