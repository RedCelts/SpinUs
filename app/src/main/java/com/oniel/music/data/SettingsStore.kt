package com.oniel.music.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "oniel_settings")

/**
 * Persists the app's playback settings in DataStore Preferences
 * (separate file from the login in [AuthStore]).
 * Today that is only the crossfade length in seconds (0-12, default 3).
 */
class SettingsStore(private val context: Context) {

    companion object {
        private val KEY_CROSSFADE_SECONDS = intPreferencesKey("crossfade_seconds")
    }

    /** Saved crossfade seconds, or the default (3) when never saved. */
    suspend fun loadCrossfadeSeconds(): Int {
        return context.settingsDataStore.data.map { prefs ->
            Crossfade.clampSeconds(
                prefs[KEY_CROSSFADE_SECONDS] ?: Crossfade.DEFAULT_SECONDS
            )
        }.first()
    }

    suspend fun saveCrossfadeSeconds(seconds: Int) {
        val value = Crossfade.clampSeconds(seconds)
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_CROSSFADE_SECONDS] = value
        }
    }
}
