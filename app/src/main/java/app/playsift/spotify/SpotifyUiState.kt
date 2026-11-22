package app.playsift.spotify

import app.playsift.player.PlayerTrack

enum class SourceChoice { TopPlayed, EverythingPlayed, TickedSongs }

/** A song that is now on Spotify and could be moved to the trash; [sizeBytes] is null when the file size is unknown. */
data class OfferedSong(val track: PlayerTrack, val sizeBytes: Long?)

sealed interface SpotifyStage {
    data object Setup : SpotifyStage

    data class Matching(val done: Int, val total: Int) : SpotifyStage

    /** The first of [pending] is the one on screen; [matched] holds the songs that are already going into the playlist. */
    data class ConfirmingUnsure(
        val source: SendSource,
        val playlistName: String,
        val matched: List<MatchedSong>,
        val pending: List<UnsureSong>,
        val skipped: List<UnsureSong>,
        val notFound: List<PlayerTrack>,
    ) : SpotifyStage

    data object Publishing : SpotifyStage

    data class Done(val result: SendResult, val offer: List<OfferedSong>, val selectedIds: Set<Long>) : SpotifyStage

    data class Failed(val message: String) : SpotifyStage
}

data class SpotifyUiState(
    val builtInClientIdMissing: Boolean,
    val clientIdOverride: String,
    val isSignedIn: Boolean,
    val userName: String?,
    val choice: SourceChoice,
    val topCount: Int,
    val playlistName: String,
    val tickedTracks: List<PlayerTrack>,
    val stage: SpotifyStage,
    val message: String? = null,
) {
    val hasClientId: Boolean get() = !builtInClientIdMissing || clientIdOverride.isNotBlank()
}

const val DEFAULT_TOP_COUNT = 100
