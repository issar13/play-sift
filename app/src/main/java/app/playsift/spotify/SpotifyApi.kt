package app.playsift.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class SpotifyTrack(val uri: String, val title: String, val artists: List<String>, val durationMillis: Long)

data class SpotifyPlaylist(val id: String, val url: String)

/** [status] is the HTTP status Spotify answered with. */
class SpotifyHttpException(val status: Int, message: String) : Exception(message)

/** Spotify allows at most 100 items per playlist request. */
const val PLAYLIST_BATCH_SIZE = 100

interface SpotifyApi {
    suspend fun searchTracks(title: String, artist: String): List<SpotifyTrack>

    suspend fun currentUserName(): String

    suspend fun createPlaylist(name: String): SpotifyPlaylist

    /** Replaces everything in the playlist with [uris], which must hold at most [PLAYLIST_BATCH_SIZE] items. */
    suspend fun replaceItems(playlistId: String, uris: List<String>)

    /** Appends [uris], which must hold at most [PLAYLIST_BATCH_SIZE] items. */
    suspend fun addItems(playlistId: String, uris: List<String>)
}

private const val API_URL = "https://api.spotify.com/v1/"
private const val SEARCH_RESULT_LIMIT = 5
private const val MAX_RATE_LIMIT_RETRIES = 5
private const val DEFAULT_RETRY_SECONDS = 1L
private const val LONGEST_WAIT_SECONDS = 120L

@Serializable
private data class SearchResponse(val tracks: TrackPage)

@Serializable
private data class TrackPage(val items: List<TrackDto>)

@Serializable
private data class TrackDto(val uri: String, val name: String, @SerialName("duration_ms") val durationMillis: Long, val artists: List<ArtistDto>)

@Serializable
private data class ArtistDto(val name: String)

@Serializable
private data class UserDto(val id: String, @SerialName("display_name") val displayName: String? = null)

@Serializable
private data class PlaylistDto(val id: String, @SerialName("external_urls") val externalUrls: ExternalUrlsDto)

@Serializable
private data class ExternalUrlsDto(val spotify: String)

@Serializable
private data class CreatePlaylistBody(val name: String, val public: Boolean, val description: String)

@Serializable
private data class UrisBody(val uris: List<String>)

private class RequestSpec(val method: String, val url: HttpUrl, val body: String?)

private class Reply(val status: Int, val retryAfterSeconds: Long?, val body: String)

/**
 * Sends the requests, waiting out a 429 for as long as Spotify's Retry-After says (a few retries)
 * and refreshing the token once after a 401. [pause] is injectable so tests do not wait.
 */
class OkHttpSpotifyApi(
    private val client: OkHttpClient,
    private val tokens: AccessTokens,
    private val baseUrl: HttpUrl = API_URL.toHttpUrl(),
    private val pause: suspend (Long) -> Unit = { milliseconds -> delay(milliseconds) },
) : SpotifyApi {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun searchTracks(title: String, artist: String): List<SpotifyTrack> {
        val url = baseUrl.newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", "track:\"${title.withoutQuotes()}\" artist:\"${artist.withoutQuotes()}\"")
            .addQueryParameter("type", "track")
            .addQueryParameter("limit", SEARCH_RESULT_LIMIT.toString())
            .build()
        return json.decodeFromString<SearchResponse>(execute(RequestSpec("GET", url, null))).tracks.items.map { track ->
            SpotifyTrack(track.uri, track.name, track.artists.map { it.name }, track.durationMillis)
        }
    }

    override suspend fun currentUserName(): String {
        val user = json.decodeFromString<UserDto>(execute(RequestSpec("GET", baseUrl.newBuilder().addPathSegment("me").build(), null)))
        return user.displayName ?: user.id
    }

    override suspend fun createPlaylist(name: String): SpotifyPlaylist {
        val url = baseUrl.newBuilder().addPathSegment("me").addPathSegment("playlists").build()
        val body = json.encodeToString(CreatePlaylistBody(name, public = false, description = "Made with Play Sift"))
        val playlist = json.decodeFromString<PlaylistDto>(execute(RequestSpec("POST", url, body)))
        return SpotifyPlaylist(playlist.id, playlist.externalUrls.spotify)
    }

    override suspend fun replaceItems(playlistId: String, uris: List<String>) {
        execute(RequestSpec("PUT", itemsUrl(playlistId), json.encodeToString(UrisBody(uris))))
    }

    override suspend fun addItems(playlistId: String, uris: List<String>) {
        execute(RequestSpec("POST", itemsUrl(playlistId), json.encodeToString(UrisBody(uris))))
    }

    private fun itemsUrl(playlistId: String) =
        baseUrl.newBuilder().addPathSegment("playlists").addPathSegment(playlistId).addPathSegment("items").build()

    private fun String.withoutQuotes() = replace("\"", "")

    private suspend fun execute(spec: RequestSpec): String =
        attempt(spec, tokens.current(), rateLimitRetries = 0, hasRefreshedToken = false)

    private tailrec suspend fun attempt(spec: RequestSpec, token: String, rateLimitRetries: Int, hasRefreshedToken: Boolean): String {
        val reply = send(spec, token)
        return when {
            reply.status in SUCCESS_STATUSES -> reply.body
            reply.status == STATUS_UNAUTHORIZED && !hasRefreshedToken ->
                attempt(spec, tokens.refreshedAfterRejecting(token), rateLimitRetries, hasRefreshedToken = true)
            reply.status == STATUS_TOO_MANY_REQUESTS && canWait(reply, rateLimitRetries) -> {
                pause((reply.retryAfterSeconds ?: DEFAULT_RETRY_SECONDS) * MILLISECONDS_PER_SECOND)
                attempt(spec, token, rateLimitRetries + 1, hasRefreshedToken)
            }
            else -> throw SpotifyHttpException(reply.status, "Spotify answered ${reply.status} for ${spec.method} ${spec.url.encodedPath}")
        }
    }

    private fun canWait(reply: Reply, rateLimitRetries: Int) =
        rateLimitRetries < MAX_RATE_LIMIT_RETRIES && (reply.retryAfterSeconds ?: DEFAULT_RETRY_SECONDS) <= LONGEST_WAIT_SECONDS

    private suspend fun send(spec: RequestSpec, token: String): Reply =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(spec.url)
                .header("Authorization", "Bearer $token")
                .method(spec.method, spec.body?.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                Reply(response.code, response.header("Retry-After")?.toLongOrNull(), response.body.string())
            }
        }

    private companion object {
        val SUCCESS_STATUSES = 200..299
        const val STATUS_UNAUTHORIZED = 401
        const val STATUS_TOO_MANY_REQUESTS = 429
        const val MILLISECONDS_PER_SECOND = 1000L
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
