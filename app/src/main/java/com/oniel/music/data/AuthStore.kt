package com.oniel.music.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Saved login, as read back from storage. Null when never saved / after [AuthStore.clear]. */
data class SavedAuth(
    val serverUrl: String,
    val token: String,
    val userId: String,
    val userName: String,
    val isAdmin: Boolean
)

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "oniel_auth")

/**
 * Persists the login (server address + token + user) in DataStore
 * Preferences so the app can skip the login screen on the next launch.
 * Nothing else is stored here — the play log lives in [PlayLog].
 * A login saved by a pre-2.0 (Jellyfin) build reads back as null, so the
 * app shows the sign-in screen once after the update.
 */
class AuthStore(private val context: Context) {

    companion object {
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_TOKEN = stringPreferencesKey("token")
        private val KEY_USER_ID = stringPreferencesKey("user_id")
        private val KEY_USER_NAME = stringPreferencesKey("user_name")
        private val KEY_IS_ADMIN = booleanPreferencesKey("is_admin")
        /** Which kind of server the login is for. Logins saved before 2.0 (Jellyfin) have none. */
        private val KEY_BACKEND = stringPreferencesKey("backend")
        private const val BACKEND = "spinus"
    }

    suspend fun save(
        serverUrl: String,
        token: String,
        userId: String,
        userName: String,
        isAdmin: Boolean
    ) {
        context.authDataStore.edit { prefs ->
            prefs[KEY_SERVER_URL] = serverUrl
            prefs[KEY_TOKEN] = token
            prefs[KEY_USER_ID] = userId
            prefs[KEY_USER_NAME] = userName
            prefs[KEY_IS_ADMIN] = isAdmin
            prefs[KEY_BACKEND] = BACKEND
        }
    }

    /** Returns the saved login, or null when any part of it is missing. */
    suspend fun load(): SavedAuth? {
        return context.authDataStore.data.map { prefs ->
            val serverUrl = prefs[KEY_SERVER_URL]
            val token = prefs[KEY_TOKEN]
            val userId = prefs[KEY_USER_ID]
            val userName = prefs[KEY_USER_NAME]
            val isAdmin = prefs[KEY_IS_ADMIN]
            if (serverUrl == null || token == null || userId == null ||
                userName == null || isAdmin == null || prefs[KEY_BACKEND] != BACKEND
            ) {
                // Missing, or a Jellyfin login from before 2.0: sign in again.
                null
            } else {
                SavedAuth(serverUrl, token, userId, userName, isAdmin)
            }
        }.first()
    }

    suspend fun clear() {
        context.authDataStore.edit { prefs -> prefs.clear() }
    }
}
