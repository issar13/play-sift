package app.playsift.spotify

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.playsift.player.PlayerTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** What a send needs, saved before the background work starts: the ticked songs can be too many for work input data. */
@Serializable
data class SendRequest(
    val choice: SourceChoice,
    val topCount: Int,
    val playlistName: String,
    val tickedTracks: List<PlayerTrack>,
)

/**
 * How a send ended. [unsure] songs wait for the user on the result screen and are not in [result] yet.
 * A set [failureMessage] means the send failed and the rest is empty.
 */
@Serializable
data class SavedSend(
    val choice: SourceChoice,
    val topCount: Int,
    val playlistName: String,
    val result: SendResult,
    val unsure: List<UnsureSong>,
    val failureMessage: String? = null,
) {
    val source: SendSource get() = sourceFor(choice, topCount)
}

/** Keeps the request and the last outcome across process death. */
interface SendStore {
    val saved: Flow<SavedSend?>

    suspend fun request(): SendRequest?

    suspend fun saveRequest(request: SendRequest)

    suspend fun save(saved: SavedSend)

    suspend fun clearSaved()
}

class DataStoreSendStore(private val dataStore: DataStore<Preferences>) : SendStore {
    override val saved: Flow<SavedSend?> = dataStore.data.map { preferences -> preferences[SAVED_KEY]?.let(::decodeOrNull) }

    override suspend fun request(): SendRequest? = dataStore.data.first()[REQUEST_KEY]?.let(::decodeOrNull)

    override suspend fun saveRequest(request: SendRequest) {
        dataStore.edit { preferences -> preferences[REQUEST_KEY] = json.encodeToString(request) }
    }

    override suspend fun save(saved: SavedSend) {
        dataStore.edit { preferences -> preferences[SAVED_KEY] = json.encodeToString(saved) }
    }

    override suspend fun clearSaved() {
        dataStore.edit { preferences -> preferences.remove(SAVED_KEY) }
    }

    /** A saved value from an older app version that no longer reads counts as nothing saved. */
    private inline fun <reified T> decodeOrNull(text: String): T? =
        try {
            json.decodeFromString<T>(text)
        } catch (_: SerializationException) {
            null
        }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val REQUEST_KEY = stringPreferencesKey("spotify_send_request")
        val SAVED_KEY = stringPreferencesKey("spotify_saved_send")
    }
}
