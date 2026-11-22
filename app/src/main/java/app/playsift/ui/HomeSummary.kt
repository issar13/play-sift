package app.playsift.ui

import app.playsift.files.FileLocation
import app.playsift.files.fileLocation
import app.playsift.player.HistorySummary
import app.playsift.player.PlayerTrack
import java.time.Instant

/** 5 MB as the app shows it: 1 MB is 1,000,000 bytes. */
const val LARGE_SONG_MINIMUM_BYTES = 5_000_000L

data class ArtistPlays(val artist: String, val plays: Int)

data class HomeSummary(
    val totalSongs: Int,
    val playedSongs: Int,
    val neverPlayedSongs: Int,
    val deleteCandidates: Int,
    val deleteCandidatesSizeBytes: Long?,
    val librarySizeBytes: Long?,
    val largeSongs: Int?,
    val largeSongsSizeBytes: Long?,
    val mostPlayed: PlayerTrack?,
    val topArtist: ArtistPlays?,
    val trackingSince: Instant?,
)

/** [sizes] is null when the phone files are not readable, which hides every size. */
fun homeSummary(
    history: HistorySummary,
    mostPlayed: List<PlayerTrack>,
    deleteCandidates: List<PlayerTrack>,
    allTracks: List<PlayerTrack>,
    sizes: Map<FileLocation, Long>?,
): HomeSummary {
    val largeSongList = sizes?.let { knownSizes -> largeSongs(allTracks, knownSizes) }
    return HomeSummary(
        totalSongs = history.totalTracks,
        playedSongs = history.totalTracks - history.unplayedTracks,
        neverPlayedSongs = history.unplayedTracks,
        deleteCandidates = deleteCandidates.size,
        deleteCandidatesSizeBytes = sizes?.let { knownSizes -> sizeOf(deleteCandidates, knownSizes) },
        librarySizeBytes = sizes?.let { knownSizes -> sizeOf(allTracks, knownSizes) },
        largeSongs = largeSongList?.size,
        largeSongsSizeBytes = largeSongList?.sumOf { (_, sizeBytes) -> sizeBytes },
        mostPlayed = mostPlayed.firstOrNull(),
        topArtist = topArtist(mostPlayed),
        trackingSince = history.earliestFirstSeenAt,
    )
}

/** Songs of [LARGE_SONG_MINIMUM_BYTES] or more: fewest plays first, then biggest first. */
fun largeSongs(allTracks: List<PlayerTrack>, sizes: Map<FileLocation, Long>): List<Pair<PlayerTrack, Long>> =
    allTracks
        .mapNotNull { track -> track.fileLocation()?.let(sizes::get)?.let { sizeBytes -> track to sizeBytes } }
        .filter { (_, sizeBytes) -> sizeBytes >= LARGE_SONG_MINIMUM_BYTES }
        .sortedWith(compareBy<Pair<PlayerTrack, Long>> { (track, _) -> track.playCount }.thenByDescending { (_, sizeBytes) -> sizeBytes })

/** Tracks without a known size (shared location, cue, stream) count as 0, so this is a lower bound. */
private fun sizeOf(tracks: List<PlayerTrack>, sizes: Map<FileLocation, Long>): Long =
    tracks.sumOf { track -> track.fileLocation()?.let(sizes::get) ?: 0L }

/** Sums plays by artist in Kotlin because the player's provider can't group. Ties go to the alphabetically first artist. */
private fun topArtist(mostPlayed: List<PlayerTrack>): ArtistPlays? =
    mostPlayed
        .filter { track -> track.artist.isNotBlank() }
        .groupBy({ track -> track.artist }, { track -> track.playCount })
        .map { (artist, playCounts) -> ArtistPlays(artist, playCounts.sum()) }
        .sortedWith(compareByDescending<ArtistPlays> { artistPlays -> artistPlays.plays }.thenBy { artistPlays -> artistPlays.artist })
        .firstOrNull()
