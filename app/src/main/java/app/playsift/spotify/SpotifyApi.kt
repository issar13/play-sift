package app.playsift.spotify

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
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

@Serializable
data class SpotifyTrack(
    val uri: String,
    val title: String,
    val artists: List<String>,
    val durationMillis: Long,
    /** Empty for a song saved before albums were read. */
    val album: String = "",
) {
    /** Spotify's track id, the last part of the uri; the same song listed twice has the same id. */
    val id: String get() = uri.substringAfterLast(':')
}

@Serializable
data class SpotifyPlaylist(val id: String, val url: String)

/** [status] is the HTTP status Spotify answered with; [retryAfterSeconds] is its Retry-After for a 429, when it sent one. */
class SpotifyHttpException(val status: Int, message: String, val retryAfterSeconds: Long? = null) : Exception(message)

/**
 * Put in the coroutine context by a caller that wants to hear about it when Spotify makes a request wait:
 * [onWait] gets the seconds the request is about to wait. Calls made without it wait silently.
 */
class RateLimitWaitHook(val onWait: suspend (seconds: Long) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RateLimitWaitHook>
}

/** Spotify allows at most 100 items per playlist request. */
const val PLAYLIST_BATCH_SIZE = 100

interface SpotifyApi {
    suspend fun searchTracks(title: String, artist: String): List<SpotifyTrack>

    /** Spotify's answer to a free-form search [query], at most [OPTION_SEARCH_LIMIT] tracks in Spotify's ranking order. */
    suspend fun searchOptions(query: String): List<SpotifyTrack>

    suspend fun currentUserName(): String

    /** Spotify's id for the signed-in account. */
    suspend fun currentUserId(): String

    /** True when the signed-in account still follows the playlist: a playlist deleted in the Spotify app is only unfollowed. */
    suspend fun followsPlaylist(playlistId: String): Boolean

    suspend fun createPlaylist(name: String): SpotifyPlaylist

    /** Replaces everything in the playlist with [uris], which must hold at most [PLAYLIST_BATCH_SIZE] items. */
    suspend fun replaceItems(playlistId: String, uris: List<String>)

    /** Appends [uris], which must hold at most [PLAYLIST_BATCH_SIZE] items. */
    suspend fun addItems(playlistId: String, uris: List<String>)
}

private const val API_URL = "https://api.spotify.com/v1/"
private const val SEARCH_RESULT_LIMIT = 5
private const val FOLLOWED_PLAYLISTS_PAGE_SIZE = 50

/** How many tracks one search for options asks for. */
const val OPTION_SEARCH_LIMIT = 8
private const val DEFAULT_RETRY_SECONDS = 1L
private const val REQUEST_GAP_MILLISECONDS = 250L
private const val LONGEST_WAIT_SECONDS = 600L
private const val MAX_RATE_LIMIT_RETRIES = 10
private const val LOG_TAG = "PlaySift"

/** How long and how often a request may wait out a 429 before giving up. */
private class WaitLimit(val longestWaitSeconds: Long, val maxRetries: Int)

/** For the sending worker, which runs as a foreground service and can wait minutes. */
private val SENDING_WAIT_LIMIT = WaitLimit(LONGEST_WAIT_SECONDS, MAX_RATE_LIMIT_RETRIES)

/** For picker searches made from the screen, which must not leave the user waiting minutes. */
private val SCREEN_WAIT_LIMIT = WaitLimit(longestWaitSeconds = 120L, maxRetries = 5)

@Serializable
private data class SearchResponse(val tracks: TrackPage)

@Serializable
private data class TrackPage(val items: List<TrackDto>)

@Serializable
private data class TrackDto(
    val uri: String,
    val name: String,
    @SerialName("duration_ms") val durationMillis: Long,
    val artists: List<ArtistDto>,
    val album: AlbumDto? = null,
)

@Serializable
private data class AlbumDto(val name: String)

@Serializable
private data class ArtistDto(val name: String)

@Serializable
private data class UserDto(val id: String, @SerialName("display_name") val displayName: String? = null)

@Serializable
private data class PlaylistDto(val id: String, @SerialName("external_urls") val externalUrls: ExternalUrlsDto)

@Serializable
private data class ExternalUrlsDto(val spotify: String)

@Serializable
private data class FollowedPlaylistsResponse(val items: List<PlaylistIdDto>, val next: String? = null)

@Serializable
private data class PlaylistIdDto(val id: String)

@Serializable
private data class CreatePlaylistBody(val name: String, val public: Boolean, val description: String)

@Serializable
private data class UrisBody(val uris: List<String>)

private class RequestSpec(val method: String, val url: HttpUrl, val body: String?)

private class Reply(val status: Int, val retryAfterSeconds: Long?, val body: String)

/**
 * Sends the requests one short gap apart, waiting out a 429 for as long as Spotify's Retry-After says
 * (up to 10 minutes, 10 times; picker searches only up to 2 minutes, 5 times) and refreshing the token once
 * after a 401. A caller that put a [RateLimitWaitHook] in its coroutine context is told about each wait.
 * [pause] is injectable so tests do not wait.
 */
class OkHttpSpotifyApi(
    private val client: OkHttpClient,
    private val tokens: AccessTokens,
    private val baseUrl: HttpUrl = API_URL.toHttpUrl(),
    private val pause: suspend (Long) -> Unit = { milliseconds -> delay(milliseconds) },
) : SpotifyApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val hasSentARequest = AtomicBoolean(false)

    @Volatile
    private var userIdForToken: Pair<String, String>? = null

    override suspend fun searchTracks(title: String, artist: String): List<SpotifyTrack> =
        search(strictQuery(title, artist), SEARCH_RESULT_LIMIT)

    override suspend fun searchOptions(query: String): List<SpotifyTrack> = search(query, OPTION_SEARCH_LIMIT, SCREEN_WAIT_LIMIT)

    private suspend fun search(query: String, limit: Int, waitLimit: WaitLimit = SENDING_WAIT_LIMIT): List<SpotifyTrack> {
        val url = baseUrl.newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", query)
            .addQueryParameter("type", "track")
            .addQueryParameter("limit", limit.toString())
            .build()
        return json.decodeFromString<SearchResponse>(execute(RequestSpec("GET", url, null), waitLimit)).tracks.items.map { track ->
            SpotifyTrack(track.uri, track.name, track.artists.map { it.name }, track.durationMillis, track.album?.name.orEmpty())
        }
    }

    override suspend fun currentUserName(): String {
        val user = json.decodeFromString<UserDto>(execute(userRequest(), SCREEN_WAIT_LIMIT))
        return user.displayName ?: user.id
    }

    /** The account behind one access token never changes, so the id is asked for once per token. */
    override suspend fun currentUserId(): String {
        val token = tokens.current()
        userIdForToken?.takeIf { (knownToken, _) -> knownToken == token }?.let { (_, userId) -> return userId }
        return currentUser().id.also { userId -> userIdForToken = token to userId }
    }

    private suspend fun currentUser(): UserDto =
        json.decodeFromString<UserDto>(execute(userRequest()))

    private fun userRequest() = RequestSpec("GET", baseUrl.newBuilder().addPathSegment("me").build(), null)

    override suspend fun followsPlaylist(playlistId: String): Boolean {
        val firstPage = baseUrl.newBuilder()
            .addPathSegment("me")
            .addPathSegment("playlists")
            .addQueryParameter("limit", FOLLOWED_PLAYLISTS_PAGE_SIZE.toString())
            .build()
        return followedPlaylistsContain(playlistId, firstPage)
    }

    private tailrec suspend fun followedPlaylistsContain(playlistId: String, pageUrl: HttpUrl): Boolean {
        val page = json.decodeFromString<FollowedPlaylistsResponse>(execute(RequestSpec("GET", pageUrl, null)))
        val nextPageUrl = page.next?.toHttpUrl()
        return when {
            page.items.any { it.id == playlistId } -> true
            nextPageUrl == null -> false
            else -> followedPlaylistsContain(playlistId, nextPageUrl)
        }
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

    private suspend fun execute(spec: RequestSpec, waitLimit: WaitLimit = SENDING_WAIT_LIMIT): String =
        attempt(spec, waitLimit, tokens.current(), rateLimitRetries = 0, hasRefreshedToken = false)

    private tailrec suspend fun attempt(spec: RequestSpec, waitLimit: WaitLimit, token: String, rateLimitRetries: Int, hasRefreshedToken: Boolean): String {
        val reply = send(spec, token)
        return when {
            reply.status in SUCCESS_STATUSES -> reply.body
            reply.status == STATUS_UNAUTHORIZED && !hasRefreshedToken ->
                attempt(spec, waitLimit, tokens.refreshedAfterRejecting(token), rateLimitRetries, hasRefreshedToken = true)
            reply.status == STATUS_TOO_MANY_REQUESTS && canWait(reply, waitLimit, rateLimitRetries) -> {
                val waitSeconds = reply.retryAfterSeconds ?: DEFAULT_RETRY_SECONDS
                coroutineContext[RateLimitWaitHook]?.onWait?.invoke(waitSeconds)
                pause(waitSeconds * MILLISECONDS_PER_SECOND)
                attempt(spec, waitLimit, token, rateLimitRetries + 1, hasRefreshedToken)
            }
            else -> throw SpotifyHttpException(
                reply.status,
                "Spotify answered ${reply.status} for ${spec.method} ${spec.url.encodedPath}",
                reply.retryAfterSeconds,
            )
        }
    }

    private fun canWait(reply: Reply, waitLimit: WaitLimit, rateLimitRetries: Int) =
        rateLimitRetries < waitLimit.maxRetries && (reply.retryAfterSeconds ?: DEFAULT_RETRY_SECONDS) <= waitLimit.longestWaitSeconds

    private suspend fun send(spec: RequestSpec, token: String): Reply {
        if (hasSentARequest.getAndSet(true)) pause(REQUEST_GAP_MILLISECONDS)
        return sendNow(spec, token).also { reply ->
            if (reply.status == STATUS_TOO_MANY_REQUESTS) {
                Log.w(LOG_TAG, "Spotify answered 429 for ${spec.method} ${spec.url.encodedPath}, Retry-After ${reply.retryAfterSeconds ?: "missing"}")
            }
        }
    }

    private suspend fun sendNow(spec: RequestSpec, token: String): Reply =
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
