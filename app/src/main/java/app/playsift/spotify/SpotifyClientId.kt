package app.playsift.spotify

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/** The Client ID built in from local.properties, unless the user typed another one in the Spotify screen. */
class SpotifyClientId(private val dataStore: DataStore<Preferences>, private val builtIn: String) {
    suspend fun override(): String = dataStore.data.first()[OVERRIDE_KEY].orEmpty()

    suspend fun effective(): String = override().ifBlank { builtIn }

    suspend fun setOverride(clientId: String) {
        dataStore.edit { preferences -> preferences[OVERRIDE_KEY] = clientId.trim() }
    }

    private companion object {
        val OVERRIDE_KEY = stringPreferencesKey("spotify_client_id_override")
    }
}
