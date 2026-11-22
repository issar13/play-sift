package app.playsift.spotify

import app.playsift.player.PlayerTrack
import android.net.Uri

/** One "Title - Artist" line per song, for the share sheet. */
fun notFoundShareText(tracks: List<PlayerTrack>): String = tracks.joinToString("\n") { "${it.title} - ${it.artist}" }

/** Where to look for a song by hand: the Spotify app's search first, the website when the app is missing. */
data class SpotifySearchLinks(val appUri: String, val webUrl: String)

fun spotifySearchLinks(track: PlayerTrack): SpotifySearchLinks {
    val encodedQuery = Uri.encode("${track.title} ${track.artist}")
    return SpotifySearchLinks(appUri = "spotify:search:$encodedQuery", webUrl = "https://open.spotify.com/search/$encodedQuery")
}
