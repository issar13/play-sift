package app.playsift.spotify

import androidx.room.Entity
import java.time.Instant

/**
 * A cached Spotify match for one song, keyed like the play history. [isConfirmedByUser] is true when the user accepted an unsure candidate.
 * The local title, artist and length say which recording the match was made for, so a file replaced under the same name is matched again.
 */
@Entity(tableName = "spotify_match", primaryKeys = ["folderPath", "fileName", "cueTitle"])
data class SpotifyMatchEntry(
    val folderPath: String,
    val fileName: String,
    val cueTitle: String,
    val spotifyUri: String,
    val spotifyTitle: String,
    val spotifyArtist: String,
    val localTitle: String,
    val localArtist: String,
    val localDurationMillis: Long,
    val isConfirmedByUser: Boolean,
    val matchedAt: Instant,
)

/**
 * The playlist a Spotify account made for one source and name, so sending the same name again replaces its items instead of making a second playlist.
 * [accountId] is the Spotify user id; it is empty for a row saved before playlists were scoped to an account.
 */
@Entity(tableName = "spotify_playlist", primaryKeys = ["accountId", "sourceKey"])
data class SpotifyPlaylistEntry(
    val accountId: String,
    val sourceKey: String,
    val playlistId: String,
    val url: String,
)
