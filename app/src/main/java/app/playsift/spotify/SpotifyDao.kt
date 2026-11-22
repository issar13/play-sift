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

    @Query("SELECT * FROM spotify_playlist WHERE sourceKey = :sourceKey")
    suspend fun playlistFor(sourceKey: String): SpotifyPlaylistEntry?

    @Upsert
    suspend fun upsertPlaylist(entry: SpotifyPlaylistEntry)

    @Query("DELETE FROM spotify_playlist WHERE sourceKey = :sourceKey")
    suspend fun deletePlaylist(sourceKey: String)
}
