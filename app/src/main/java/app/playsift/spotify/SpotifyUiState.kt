package app.playsift.spotify

import app.playsift.player.PlayerTrack

enum class SourceChoice { TopPlayed, EverythingPlayed, TickedSongs }

/** A song that is now on Spotify and could be moved to the trash; [sizeBytes] is null when the file size is unknown. */
data class OfferedSong(val track: PlayerTrack, val sizeBytes: Long?)

sealed interface SpotifyStage {
    data object Setup : SpotifyStage

    data class Matching(val done: Int, val total: Int) : SpotifyStage

    data object Publishing : SpotifyStage

    /** [unsure] songs wait for the user: the first is on screen, and adding them later updates the same playlist. */
    data class Done(
        val result: SendResult,
        val unsure: List<UnsureSong>,
        val offer: List<OfferedSong>,
        val selectedIds: Set<Long>,
    ) : SpotifyStage

    data class Failed(val message: String) : SpotifyStage
}

data class SpotifyUiState(
    val builtInClientIdMissing: Boolean,
    val clientIdOverride: String,
    val isSignedIn: Boolean,
    val userName: String?,
    val choice: SourceChoice,
    /** What the user typed for how many top songs; empty while editing, so it is parsed only when sending. */
    val topCountText: String,
    val playlistName: String,
    val tickedTracks: List<PlayerTrack>,
    val stage: SpotifyStage,
    val message: String? = null,
) {
    val hasClientId: Boolean get() = !builtInClientIdMissing || clientIdOverride.isNotBlank()

    /** The typed count when it is a number above zero. */
    val topCount: Int? get() = topCountText.toIntOrNull()?.takeIf { it > 0 }

    val canSend: Boolean get() = choice != SourceChoice.TopPlayed || topCount != null
}

const val DEFAULT_TOP_COUNT = 100

const val MAX_TOP_COUNT_DIGITS = 4
const val MAX_TOP_COUNT = 9999

/** Keeps digits only, at most [MAX_TOP_COUNT_DIGITS] of them. */
fun topCountTextFrom(input: String): String = input.filter(Char::isDigit).take(MAX_TOP_COUNT_DIGITS)
