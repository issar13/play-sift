package app.playsift.spotify

import app.playsift.history.HistoryKey
import app.playsift.history.historyKey
import app.playsift.player.PlayerTrack
import java.time.Clock
import kotlinx.serialization.Serializable

/**
 * Where a playlist's songs come from. [playlistKey] is the remembered playlist for a typed name, so every source keeps one
 * playlist per name: a new name makes a new playlist and the same name replaces that playlist's songs.
 */
sealed class SendSource(val key: String, val defaultName: String) {
    fun playlistKey(name: String): String = "$key:${name.trim().lowercase()}"

    /** The name to use for what the user typed: blank means the source's default name. */
    fun nameFor(typedName: String): String = typedName.trim().ifBlank { defaultName }

    data class TopPlayed(val count: Int) : SendSource("top", "Play Sift top $count")

    data object EverythingPlayed : SendSource("played", "Play Sift played")

    data object TickedSongs : SendSource("ticked", "Play Sift picks")
}

fun sourceFor(choice: SourceChoice, topCount: Int): SendSource =
    when (choice) {
        SourceChoice.TopPlayed -> SendSource.TopPlayed(topCount)
        SourceChoice.EverythingPlayed -> SendSource.EverythingPlayed
        SourceChoice.TickedSongs -> SendSource.TickedSongs
    }

/** Songs a source holds, in play-count order for the played sources. [mergedTracks] carry total plays. */
fun tracksFor(source: SendSource, mergedTracks: List<PlayerTrack>, tickedTracks: List<PlayerTrack>): List<PlayerTrack> =
    when (source) {
        is SendSource.TopPlayed -> playedByCount(mergedTracks).take(source.count)
        SendSource.EverythingPlayed -> playedByCount(mergedTracks)
        SendSource.TickedSongs -> tickedTracks
    }

private fun playedByCount(mergedTracks: List<PlayerTrack>) = mergedTracks.filter { it.playCount > 0 }.sortedByDescending { it.playCount }

@Serializable
data class MatchedSong(val track: PlayerTrack, val spotifyUri: String, val wasConfirmedByUser: Boolean)

@Serializable
data class UnsureSong(val track: PlayerTrack, val candidate: SpotifyTrack)

data class MatchReport(val matched: List<MatchedSong>, val unsure: List<UnsureSong>, val notFound: List<PlayerTrack>)

/** Looks songs up on Spotify, reusing earlier matches from the local cache. */
class SongMatcher(private val api: SpotifyApi, private val dao: SpotifyDao, private val clock: Clock) {
    /** Only automatic matches and confirmed ones are cached; unsure and missing songs are searched again next time. */
    suspend fun match(tracks: List<PlayerTrack>, onProgress: suspend (done: Int, total: Int) -> Unit): MatchReport {
        val cached = dao.allMatches().associateBy { HistoryKey(it.folderPath, it.fileName, it.cueTitle) }
        val results = tracks.mapIndexed { index, track ->
            val cachedMatch = cached[track.historyKey()]
            val result = if (cachedMatch != null) {
                SongResult.Matched(MatchedSong(track, cachedMatch.spotifyUri, cachedMatch.isConfirmedByUser))
            } else {
                searchAndCache(track)
            }
            onProgress(index + 1, tracks.size)
            result
        }
        return MatchReport(
            matched = results.filterIsInstance<SongResult.Matched>().map { it.song },
            unsure = results.filterIsInstance<SongResult.Unsure>().map { it.song },
            notFound = results.filterIsInstance<SongResult.Missing>().map { it.track },
        )
    }

    /** Records the user's yes to an unsure candidate, so the song is added and never asked about again. */
    suspend fun confirm(unsure: UnsureSong): MatchedSong {
        dao.upsertMatch(entryFor(unsure.track, unsure.candidate, isConfirmedByUser = true))
        return MatchedSong(unsure.track, unsure.candidate.uri, wasConfirmedByUser = true)
    }

    private sealed interface SongResult {
        data class Matched(val song: MatchedSong) : SongResult

        data class Unsure(val song: UnsureSong) : SongResult

        data class Missing(val track: PlayerTrack) : SongResult
    }

    private suspend fun searchAndCache(track: PlayerTrack): SongResult =
        when (val result = matchTrack(track, api.searchTracks(track.title, track.artist))) {
            is MatchResult.Matched -> {
                dao.upsertMatch(entryFor(track, result.candidate, isConfirmedByUser = false))
                SongResult.Matched(MatchedSong(track, result.candidate.uri, wasConfirmedByUser = false))
            }
            is MatchResult.Unsure -> SongResult.Unsure(UnsureSong(track, result.candidate))
            MatchResult.NotFound -> SongResult.Missing(track)
        }

    private fun entryFor(track: PlayerTrack, candidate: SpotifyTrack, isConfirmedByUser: Boolean): SpotifyMatchEntry {
        val key = track.historyKey()
        return SpotifyMatchEntry(
            folderPath = key.folderPath,
            fileName = key.fileName,
            cueTitle = key.cueTitle,
            spotifyUri = candidate.uri,
            spotifyTitle = candidate.title,
            spotifyArtist = candidate.artists.joinToString(", "),
            isConfirmedByUser = isConfirmedByUser,
            matchedAt = clock.instant(),
        )
    }
}

/** Makes or refreshes the Spotify playlist for a source. */
class PlaylistPublisher(private val api: SpotifyApi, private val dao: SpotifyDao) {
    /**
     * Fills the playlist remembered for [source] and [name] (or a new one) with [uris], first batch replacing the old items
     * and the rest appended, 100 at a time. A remembered playlist that was deleted on Spotify is replaced by a new one.
     */
    suspend fun publish(source: SendSource, name: String, uris: List<String>): SpotifyPlaylist {
        val playlistKey = source.playlistKey(name)
        val remembered = dao.playlistFor(playlistKey)
        val playlist = remembered?.let { SpotifyPlaylist(it.playlistId, it.url) }
        val reused = playlist?.let { tryFill(it, uris) }
        if (reused != null) return reused
        val created = api.createPlaylist(name)
        dao.upsertPlaylist(SpotifyPlaylistEntry(playlistKey, created.id, created.url))
        fill(created, uris)
        return created
    }

    /** True when sending [source] under [name] would update a playlist Play Sift already made. */
    suspend fun hasPlaylistFor(source: SendSource, name: String): Boolean = dao.playlistFor(source.playlistKey(name)) != null

    private suspend fun tryFill(playlist: SpotifyPlaylist, uris: List<String>): SpotifyPlaylist? =
        try {
            fill(playlist, uris)
            playlist
        } catch (exception: SpotifyHttpException) {
            if (exception.status == STATUS_NOT_FOUND) null else throw exception
        }

    private suspend fun fill(playlist: SpotifyPlaylist, uris: List<String>) {
        val batches = uris.chunked(PLAYLIST_BATCH_SIZE)
        api.replaceItems(playlist.id, batches.firstOrNull().orEmpty())
        batches.drop(1).forEach { batch -> api.addItems(playlist.id, batch) }
    }

    private companion object {
        const val STATUS_NOT_FOUND = 404
    }
}

/**
 * What the user is told after a send. [added] are the songs that go into the playlist, including ones the user confirmed.
 * [playlist] is null while nothing is added yet, which happens when every match was unsure.
 */
@Serializable
data class SendResult(
    val playlist: SpotifyPlaylist?,
    val added: List<MatchedSong>,
    val skippedUnsure: List<UnsureSong>,
    val notFound: List<PlayerTrack>,
)

/** Only automatic matches that are in the playlist: confirmed-unsure, skipped and missing songs are never offered for the trash. */
fun trashOffer(result: SendResult): List<PlayerTrack> = result.added.filterNot { it.wasConfirmedByUser }.map { it.track }

/** The same Spotify song listed twice would be a duplicate in the playlist, so each uri is added once. */
fun uniqueUris(songs: List<MatchedSong>): List<String> = songs.map { it.spotifyUri }.distinct()
