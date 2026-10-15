package com.gymcoach.app.data

import androidx.datastore.preferences.core.emptyPreferences
import com.gymcoach.app.data.prefs.SettingsMapping
import com.gymcoach.app.data.prefs.toAppSettings
import com.gymcoach.core.model.AppSettings
import com.gymcoach.core.model.BackendMode
import com.gymcoach.core.model.Effort
import com.gymcoach.core.model.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals

/** Preferences → AppSettings (§7.3): defaults when empty, defaults when corrupt, exact round trip otherwise. */
class SettingsMappingTest {

    @Test fun emptyStore_mapsToDefaults() {
        assertEquals(AppSettings(), emptyPreferences().toAppSettings())
        assertEquals(AppSettings(), SettingsMapping.decode(null))
        assertEquals(AppSettings(), SettingsMapping.decode(""))
    }

    @Test fun corruptJson_mapsToDefaults() {
        assertEquals(AppSettings(), SettingsMapping.decode("{not json"))
        assertEquals(AppSettings(), SettingsMapping.decode("[1,2,3]"))
        assertEquals(AppSettings(), SettingsMapping.decode("{\"unit_system\":\"furlongs\"}"))
    }

    @Test fun defaults_matchSpec() {
        val d = AppSettings()
        assertEquals(BackendMode.NONE, d.backendMode)
        assertEquals("claude-opus-5", d.modelId)
        assertEquals(Effort.LOW, d.effort)
        assertEquals("", d.coachUserId)
        assertEquals("[]", d.deloadTriggersProposed)
        assertEquals("{}", d.plannerOverridesJson)
    }

    @Test fun roundTrip_isExact() {
        val s = AppSettings(
            unitSystem = UnitSystem.IMPERIAL, backendMode = BackendMode.DIRECT_API, modelId = "claude-sonnet-5", effort = Effort.MEDIUM,
            monthlyBudgetUsd = 45.5, bridgeUrl = "http://pc.tailnet:8787", showExperimental = true, onboardingDone = true,
            weekStartsOnMonday = false, plannerOverridesJson = "{\"vol\":{\"vol04Novice\":[8,12]}}", coachUserId = "3f9c-uuid",
            lastWeekRollover = "2026-09-07", catalogVersionSeen = 2, mesocycleAnchor = "2026-08-31", mesocycleWeekPausedAt = 3,
            mesocyclesCompleted = 1, lastDeloadWeekStart = "2026-08-24", deloadTriggersProposed = "[\"red_days@2026-09-01\"]",
            activeGymId = "gym-2", todayContextJson = "{\"date\":\"2026-09-07\",\"gym_id\":\"gym-2\"}", sessionDraftJson = "{}",
            onboardingDraftJson = "{\"page\":3}",
        )
        assertEquals(s, SettingsMapping.decode(SettingsMapping.encode(s)))
    }

    @Test fun unknownKeysFromNewerBuild_areIgnored() {
        val json = "{\"backend_mode\":\"direct_api\",\"some_future_key\":42}"
        assertEquals(AppSettings(backendMode = BackendMode.DIRECT_API), SettingsMapping.decode(json))
    }

    @Test fun storeKeys_areTheTwoOfSpec() {
        assertEquals("settings_json", SettingsMapping.KEY_SETTINGS_JSON.name)
        assertEquals("settings_version", SettingsMapping.KEY_SETTINGS_VERSION.name)
        assertEquals(1, SettingsMapping.SETTINGS_VERSION)
    }
}
