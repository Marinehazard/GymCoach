// app/src/main/kotlin/com/gymcoach/app/data/prefs/DataStores.kt
package com.gymcoach.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

internal val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
internal val Context.secretsStore: DataStore<Preferences> by preferencesDataStore(name = "secrets")
