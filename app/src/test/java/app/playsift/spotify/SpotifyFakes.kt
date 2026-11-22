package app.playsift.spotify

import app.playsift.player.PlayerTrack
import java.time.Instant

fun songTrack(
    id: Long,
    title: String = "Song $id",
    artist: String = "Artist",
    playCount: Int = 1,
    durationMillis: Long = 200_000,
    fileName: String = "$id.mp3",
) = PlayerTrack(
    id = id, title = title, artist = artist, album = "Album", fileName = fileName, folderPath = "primary/Music/",
    filePath = null, isCueTrack = false, isUserAdded = false, playCount = playCount, lastPlayedAt = null,
    firstSeenAt = Instant.parse("2024-01-01T00:00:00Z"), durationMillis = durationMillis,
)

fun spotifyTrack(
    title: String,
    artist: String = "Artist",
    durationMillis: Long = 200_000,
    uri: String = "spotify:track:${title.replace(' ', '-')}",
    album: String = "",
) = SpotifyTrack(uri = uri, title = title, artists = listOf(artist), durationMillis = durationMillis, album = album)

/** Answers searches from [catalogue] keyed by the searched title and records every call. No network. */
open class FakeSpotifyApi(var catalogue: Map<String, List<SpotifyTrack>> = emptyMap()) : SpotifyApi {
    val searchedTitles = mutableListOf<String>()
    val createdNames = mutableListOf<String>()
    val replaceCalls = mutableListOf<Pair<String, List<String>>>()
    val addCalls = mutableListOf<Pair<String, List<String>>>()
    var deletedPlaylistIds = setOf<String>()
    var failure: Exception? = null
    /** Answers [searchOptions] by the exact query text; every query asked is recorded in [optionQueriesAsked]. */
    var optionResults: Map<String, List<SpotifyTrack>> = emptyMap()
    val optionQueriesAsked = mutableListOf<String>()
    var beforeSearch: suspend (title: String) -> Unit = {}

    override suspend fun searchTracks(title: String, artist: String): List<SpotifyTrack> {
        failure?.let { throw it }
        beforeSearch(title)
        searchedTitles += title
        return catalogue[title].orEmpty()
    }

    override suspend fun searchOptions(query: String): List<SpotifyTrack> {
        failure?.let { throw it }
        optionQueriesAsked += query
        return optionResults[query].orEmpty()
    }

    override suspend fun currentUserName() = "Test User"

    override suspend fun createPlaylist(name: String): SpotifyPlaylist {
        createdNames += name
        return SpotifyPlaylist("playlist-${createdNames.size}", "https://open.spotify.com/playlist/playlist-${createdNames.size}")
    }

    override suspend fun replaceItems(playlistId: String, uris: List<String>) {
        if (playlistId in deletedPlaylistIds) throw SpotifyHttpException(404, "gone")
        replaceCalls += playlistId to uris
    }

    override suspend fun addItems(playlistId: String, uris: List<String>) {
        addCalls += playlistId to uris
    }
}

class FakeSpotifyDao : SpotifyDao {
    val matches = mutableMapOf<Triple<String, String, String>, SpotifyMatchEntry>()
    val playlists = mutableMapOf<String, SpotifyPlaylistEntry>()

    override suspend fun allMatches() = matches.values.toList()

    override suspend fun upsertMatch(entry: SpotifyMatchEntry) {
        matches[Triple(entry.folderPath, entry.fileName, entry.cueTitle)] = entry
    }

    override suspend fun playlistFor(sourceKey: String) = playlists[sourceKey]

    override suspend fun upsertPlaylist(entry: SpotifyPlaylistEntry) {
        playlists[entry.sourceKey] = entry
    }

    override suspend fun deletePlaylist(sourceKey: String) {
        playlists.remove(sourceKey)
    }
}

/** The failure [block] throws, which must be a [T]; fails the test when it does not throw. */
suspend inline fun <reified T : Throwable> failureOf(block: () -> Unit): T =
    try {
        block()
        error("Expected ${T::class.simpleName} but nothing was thrown")
    } catch (failure: Throwable) {
        if (failure is T) failure else throw failure
    }

class InMemorySendStore(initial: SavedSend? = null) : SendStore {
    private val savedState = kotlinx.coroutines.flow.MutableStateFlow(initial)
    var storedRequest: SendRequest? = null
    override val saved: kotlinx.coroutines.flow.Flow<SavedSend?> = savedState

    override suspend fun request() = storedRequest

    override suspend fun saveRequest(request: SendRequest) {
        storedRequest = request
    }

    override suspend fun save(saved: SavedSend) {
        savedState.value = saved
    }

    override suspend fun clearSaved() {
        savedState.value = null
    }
}

class FakeSendScheduler(var outcome: StartOutcome = StartOutcome.Started) : SendScheduler {
    val workState = kotlinx.coroutines.flow.MutableStateFlow<SendWorkState>(SendWorkState.Idle)
    val startedRequests = mutableListOf<SendRequest>()
    var cancelCount = 0
    override val state: kotlinx.coroutines.flow.Flow<SendWorkState> = workState

    override suspend fun start(request: SendRequest): StartOutcome {
        startedRequests += request
        return outcome
    }

    override fun cancel() {
        cancelCount += 1
    }
}

fun sendRequest(
    choice: SourceChoice = SourceChoice.TickedSongs,
    topCount: Int = 100,
    playlistName: String = "Play Sift picks",
    tickedTracks: List<app.playsift.player.PlayerTrack> = emptyList(),
) = SendRequest(choice, topCount, playlistName, tickedTracks)

fun savedSend(
    added: List<MatchedSong> = emptyList(),
    unsure: List<UnsureSong> = emptyList(),
    notFound: List<app.playsift.player.PlayerTrack> = emptyList(),
    playlist: SpotifyPlaylist? = SpotifyPlaylist("playlist-1", "https://open.spotify.com/playlist/playlist-1"),
) = SavedSend(
    choice = SourceChoice.TickedSongs,
    topCount = 100,
    playlistName = "Play Sift picks",
    result = SendResult(playlist, added, skippedUnsure = emptyList(), notFound = notFound),
    unsure = unsure,
)
