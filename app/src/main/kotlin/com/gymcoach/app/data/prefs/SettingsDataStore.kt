package com.gymcoach.app.data.prefs

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.AppSettings
import com.gymcoach.core.repo.SettingsRepository
import com.gymcoach.core.util.IdGen
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.SerializationException

/**
 * Pure mapping between the `settings` store and [AppSettings] (§7.3): the whole settings object is
 * ONE JSON string under `settings_json`, plus `settings_version` = 1. Missing or corrupt JSON maps to
 * the defaults; unknown keys written by a newer build are ignored (CoreJson.stored). JVM-testable
 * (SettingsMappingTest) — no Android types besides the DataStore `Preferences` value class.
 */
object SettingsMapping {
    const val SETTINGS_VERSION: Int = 1
    val KEY_SETTINGS_JSON: Preferences.Key<String> = stringPreferencesKey("settings_json")
    val KEY_SETTINGS_VERSION: Preferences.Key<Int> = intPreferencesKey("settings_version")

    fun decode(text: String?): AppSettings {
        if (text.isNullOrBlank()) return AppSettings()
        return try {
            CoreJson.stored.decodeFromString(AppSettings.serializer(), text)
        } catch (e: SerializationException) {
            AppSettings()
        } catch (e: IllegalArgumentException) {
            AppSettings()
        }
    }

    fun encode(settings: AppSettings): String = CoreJson.stored.encodeToString(AppSettings.serializer(), settings)

    fun fromPreferences(prefs: Preferences): AppSettings = decode(prefs[KEY_SETTINGS_JSON])
}

/** Preferences → AppSettings (defaults for a missing or corrupt document). */
fun Preferences.toAppSettings(): AppSettings = SettingsMapping.fromPreferences(this)

/**
 * [SettingsRepository] over the `settings` DataStore (declared once in DataStores.kt). A corrupt
 * store (IOException on read) is mapped to defaults with one log line. The first read that finds
 * `coachUserId` blank writes `IdGen.newId()` once (`metadata.user_id`, §6.2).
 */
class SettingsDataStore(private val appContext: Context) : SettingsRepository {
    private val userIdEnsured = AtomicBoolean(false)
    private val corruptLogged = AtomicBoolean(false)

    private val data: Flow<Preferences>
        get() = appContext.settingsStore.data.catch { e ->
            if (e is IOException) {
                if (corruptLogged.compareAndSet(false, true)) Log.w(TAG, "settings store unreadable, using defaults")
                emit(emptyPreferences())
            } else {
                throw e
            }
        }

    override fun observe(): Flow<AppSettings> = data
        .map { it.toAppSettings() }
        .onEach { if (it.coachUserId.isBlank()) ensureCoachUserId() }

    override suspend fun get(): AppSettings {
        val current = data.first().toAppSettings()
        if (current.coachUserId.isNotBlank()) return current
        ensureCoachUserId()
        return data.first().toAppSettings()
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        try {
            appContext.settingsStore.edit { prefs ->
                val next = transform(prefs.toAppSettings())
                prefs[SettingsMapping.KEY_SETTINGS_JSON] = SettingsMapping.encode(next)
                prefs[SettingsMapping.KEY_SETTINGS_VERSION] = SettingsMapping.SETTINGS_VERSION
            }
        } catch (e: IOException) {
            Log.w(TAG, "settings write failed: ${e.javaClass.simpleName}")
            throw e
        }
    }

    /** Writes a fresh coachUserId once per process when the stored one is blank (re-checked inside the edit). */
    private suspend fun ensureCoachUserId() {
        if (!userIdEnsured.compareAndSet(false, true)) return
        update { s -> if (s.coachUserId.isBlank()) s.copy(coachUserId = IdGen.newId()) else s }
    }

    private companion object {
        const val TAG: String = "SettingsDataStore"
    }
}
