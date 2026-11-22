package app.playsift.spotify

import androidx.room.Entity
import java.time.Instant

/** A cached Spotify match for one song, keyed like the play history. [isConfirmedByUser] is true when the user accepted an unsure candidate. */
@Entity(tableName = "spotify_match", primaryKeys = ["folderPath", "fileName", "cueTitle"])
data class SpotifyMatchEntry(
    val folderPath: String,
    val fileName: String,
    val cueTitle: String,
    val spotifyUri: String,
    val spotifyTitle: String,
    val spotifyArtist: String,
    val isConfirmedByUser: Boolean,
    val matchedAt: Instant,
)

/** The playlist made for one source and name, so sending the same name again replaces its items instead of making a second playlist. */
@Entity(tableName = "spotify_playlist", primaryKeys = ["sourceKey"])
data class SpotifyPlaylistEntry(
    val sourceKey: String,
    val playlistId: String,
    val url: String,
)
