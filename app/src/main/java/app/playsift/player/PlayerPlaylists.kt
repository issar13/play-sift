package app.playsift.player

import android.content.ContentResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PlayerPlaylist(val id: Long, val name: String, val songCount: Int)

/** The music player's own playlists, read only. */
interface PlayerPlaylists {
    /** Every playlist, sorted by name without regard to case. */
    suspend fun playlists(): List<PlayerPlaylist>

    /** The song ids (`PlayerTrack.id`) of one playlist, in the playlist's order. */
    suspend fun songIds(playlistId: Long): List<Long>
}

class PlayerPlaylistLibrary(private val contentResolver: ContentResolver) : PlayerPlaylists {
    /**
     * Reads by position in the projections, since qualified column names may not appear verbatim in the cursor.
     * A [SecurityException] from the provider (data access not granted) propagates unchanged.
     */
    override suspend fun playlists(): List<PlayerPlaylist> =
        withContext(Dispatchers.IO) {
            contentResolver
                .query(
                    PlayerContract.PLAYLISTS_URI,
                    arrayOf(PlayerContract.Columns.PLAYLIST_ID, PlayerContract.Columns.PLAYLIST_NAME, PlayerContract.Columns.PLAYLIST_NUM_FILES),
                    null,
                    null,
                    "${PlayerContract.Columns.PLAYLIST_NAME} COLLATE NOCASE ASC",
                )!!
                .use { cursor -> cursor.readRows { PlayerPlaylist(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getInt(2)) } }
        }

    override suspend fun songIds(playlistId: Long): List<Long> =
        withContext(Dispatchers.IO) {
            contentResolver
                .query(
                    PlayerContract.playlistFilesUri(playlistId),
                    arrayOf(PlayerContract.Columns.ENTRY_FILE_ID),
                    null,
                    null,
                    "${PlayerContract.Columns.ENTRY_SORT} ASC",
                )!!
                .use { cursor -> cursor.readRows { cursor.getLong(0) } }
        }
}
