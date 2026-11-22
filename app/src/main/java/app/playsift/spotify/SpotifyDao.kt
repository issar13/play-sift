package app.playsift.spotify

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SpotifyDao {
    @Query("SELECT * FROM spotify_match")
    suspend fun allMatches(): List<SpotifyMatchEntry>

    @Upsert
    suspend fun upsertMatch(entry: SpotifyMatchEntry)

    @Query("SELECT * FROM spotify_playlist WHERE accountId = :accountId AND sourceKey = :sourceKey")
    suspend fun playlistFor(accountId: String, sourceKey: String): SpotifyPlaylistEntry?

    @Upsert
    suspend fun upsertPlaylist(entry: SpotifyPlaylistEntry)

    /** Gives the playlists saved before accounts were tracked to [accountId], the account that is signed in now. */
    @Query("UPDATE OR REPLACE spotify_playlist SET accountId = :accountId WHERE accountId = ''")
    suspend fun adoptUnscopedPlaylists(accountId: String)
}
