package app.playsift.spotify

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

const val SPOTIFY_REDIRECT_URI = "playsift://spotify-callback"
const val SPOTIFY_SCOPES = "playlist-modify-private playlist-modify-public playlist-read-private"

private val ACCOUNTS_URL = "https://accounts.spotify.com/".toHttpUrl()

/** Kept between opening the browser and the callback, so it survives the app being closed in the meantime. */
data class PendingAuthorization(val verifier: String, val state: String)

data class SpotifyTokens(val accessToken: String, val refreshToken: String, val expiresAt: Instant)

class SpotifyAuthException(message: String) : Exception(message)

fun authorizationUrl(clientId: String, challenge: String, state: String, baseUrl: HttpUrl = ACCOUNTS_URL): String =
    baseUrl.newBuilder()
        .addPathSegment("authorize")
        .addQueryParameter("client_id", clientId)
        .addQueryParameter("response_type", "code")
        .addQueryParameter("redirect_uri", SPOTIFY_REDIRECT_URI)
        .addQueryParameter("scope", SPOTIFY_SCOPES)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("code_challenge", challenge)
        .addQueryParameter("state", state)
        .build()
        .toString()

interface SpotifyAccounts {
    suspend fun exchangeCode(clientId: String, code: String, verifier: String): SpotifyTokens

    suspend fun refresh(clientId: String, refreshToken: String): SpotifyTokens
}

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresInSeconds: Long,
)

class OkHttpSpotifyAccounts(
    private val client: OkHttpClient,
    private val clock: Clock,
    private val baseUrl: HttpUrl = ACCOUNTS_URL,
) : SpotifyAccounts {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun exchangeCode(clientId: String, code: String, verifier: String): SpotifyTokens =
        requestTokens(
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", SPOTIFY_REDIRECT_URI)
                .add("client_id", clientId)
                .add("code_verifier", verifier)
                .build(),
            previousRefreshToken = null,
        )

    override suspend fun refresh(clientId: String, refreshToken: String): SpotifyTokens =
        requestTokens(
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", clientId)
                .build(),
            previousRefreshToken = refreshToken,
        )

    /** A refresh response may omit a new refresh token, in which case the old one stays valid. */
    private suspend fun requestTokens(body: FormBody, previousRefreshToken: String?): SpotifyTokens =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(baseUrl.newBuilder().addPathSegments("api/token").build()).post(body).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw SpotifyAuthException("Spotify sign-in failed with status ${response.code}")
                val tokenResponse = json.decodeFromString<TokenResponse>(response.body.string())
                SpotifyTokens(
                    accessToken = tokenResponse.accessToken,
                    refreshToken = tokenResponse.refreshToken ?: checkNotNull(previousRefreshToken) { "Spotify sent no refresh token" },
                    expiresAt = clock.instant().plusSeconds(tokenResponse.expiresInSeconds),
                )
            }
        }
}

interface TokenStore {
    suspend fun tokens(): SpotifyTokens?

    suspend fun saveTokens(tokens: SpotifyTokens)

    suspend fun pendingAuthorization(): PendingAuthorization?

    suspend fun savePendingAuthorization(pending: PendingAuthorization)

    /** Removes the tokens and any pending sign-in. */
    suspend fun clear()
}

/** What the API client needs: a token now, and a fresh one after Spotify rejects the old one. */
interface AccessTokens {
    suspend fun current(): String

    suspend fun refreshedAfterRejecting(rejectedToken: String): String
}

/** Hands out the stored access token, refreshing it shortly before it expires. */
class SpotifySession(
    private val store: TokenStore,
    private val accounts: SpotifyAccounts,
    private val clientId: suspend () -> String,
    private val clock: Clock,
) : AccessTokens {
    private val refreshLock = Mutex()

    suspend fun isSignedIn(): Boolean = store.tokens() != null

    /** Saves under [refreshLock], so a refresh that started earlier cannot overwrite the new sign-in afterwards. */
    suspend fun completeSignIn(code: String, verifier: String) {
        val tokens = accounts.exchangeCode(clientId(), code, verifier)
        refreshLock.withLock { store.saveTokens(tokens) }
    }

    /** Waits for an in-flight refresh, so the refresh cannot store credentials after the sign-out. */
    suspend fun signOut() = refreshLock.withLock { store.clear() }

    override suspend fun current(): String {
        val stored = checkNotNull(store.tokens()) { "Not signed in to Spotify" }
        return if (stored.expiresAt.isBefore(clock.instant().plus(EXPIRY_MARGIN))) refreshLocked(stored.accessToken) else stored.accessToken
    }

    override suspend fun refreshedAfterRejecting(rejectedToken: String): String = refreshLocked(rejectedToken)

    /** Two callers that both found the token stale share one refresh: the second sees a different token and reuses it. */
    private suspend fun refreshLocked(staleToken: String): String =
        refreshLock.withLock {
            val stored = checkNotNull(store.tokens()) { "Not signed in to Spotify" }
            if (stored.accessToken != staleToken) {
                stored.accessToken
            } else {
                accounts.refresh(clientId(), stored.refreshToken).also { store.saveTokens(it) }.accessToken
            }
        }

    private companion object {
        val EXPIRY_MARGIN: Duration = Duration.ofSeconds(60)
    }
}
