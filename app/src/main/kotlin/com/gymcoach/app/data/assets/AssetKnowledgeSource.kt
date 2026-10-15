package com.gymcoach.app.data.assets

import android.content.res.AssetManager
import com.gymcoach.core.repo.KnowledgeSource
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Fallback knowledge source (§7.5): reads `gymcoach/knowledge/<file>` from the APK assets, which the
 * `sourceSets` assets line of app/build.gradle.kts maps onto :core's resource directory. The primary
 * source is ClasspathKnowledgeSource; AppContainer picks this one only when the class-path resource
 * is absent.
 */
class AssetKnowledgeSource(private val assets: AssetManager) : KnowledgeSource {

    override fun exists(relativePath: String): Boolean = try {
        assets.open(path(relativePath)).close()
        true
    } catch (e: IOException) {
        false
    }

    override fun readText(relativePath: String): String = try {
        assets.open(path(relativePath)).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: FileNotFoundException) {
        throw IllegalStateException("missing knowledge asset ${path(relativePath)}", e)
    }

    private fun path(relativePath: String): String = BASE + relativePath.trimStart('/')

    private companion object {
        const val BASE: String = "gymcoach/knowledge/"
    }
}
