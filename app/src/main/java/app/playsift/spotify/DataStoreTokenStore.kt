package app.playsift.spotify

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.GeneralSecurityException
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class StoredTokens(val accessToken: String, val refreshToken: String, val expiresAtEpochMillis: Long)

@Serializable
private data class StoredPending(val verifier: String, val state: String)

/** Tokens and the pending sign-in sit in DataStore, encrypted with [cipher]; a value the key cannot open counts as signed out. */
class DataStoreTokenStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) : TokenStore {
    override suspend fun tokens(): SpotifyTokens? =
        readDecrypted(TOKENS_KEY)?.let { text ->
            Json.decodeFromString<StoredTokens>(text).let { stored ->
                SpotifyTokens(stored.accessToken, stored.refreshToken, Instant.ofEpochMilli(stored.expiresAtEpochMillis))
            }
        }

    override suspend fun saveTokens(tokens: SpotifyTokens) =
        writeEncrypted(TOKENS_KEY, Json.encodeToString(StoredTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAt.toEpochMilli())))

    override suspend fun pendingAuthorization(): PendingAuthorization? =
        readDecrypted(PENDING_KEY)?.let { text ->
            Json.decodeFromString<StoredPending>(text).let { stored -> PendingAuthorization(stored.verifier, stored.state) }
        }

    override suspend fun savePendingAuthorization(pending: PendingAuthorization) =
        writeEncrypted(PENDING_KEY, Json.encodeToString(StoredPending(pending.verifier, pending.state)))

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(TOKENS_KEY)
            preferences.remove(PENDING_KEY)
        }
    }

    private suspend fun readDecrypted(key: Preferences.Key<String>): String? =
        dataStore.data.first()[key]?.let { encoded ->
            try {
                cipher.decrypt(encoded)
            } catch (_: GeneralSecurityException) {
                null
            }
        }

    private suspend fun writeEncrypted(key: Preferences.Key<String>, plainText: String) {
        val encoded = cipher.encrypt(plainText)
        dataStore.edit { preferences -> preferences[key] = encoded }
    }

    private companion object {
        val TOKENS_KEY = stringPreferencesKey("spotify_tokens")
        val PENDING_KEY = stringPreferencesKey("spotify_pending_authorization")
    }
}
