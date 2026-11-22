package app.playsift.history

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.playsift.player.PlayerTrack
import java.time.Instant

@Dao
abstract class PlayHistoryDao {
    @Query("SELECT * FROM play_history")
    abstract suspend fun all(): List<PlayHistoryEntry>

    @Upsert
    abstract suspend fun upsert(entries: List<PlayHistoryEntry>)

    @Delete
    abstract suspend fun delete(entries: List<PlayHistoryEntry>)

    /** Reads, folds in [playerTracks] and writes in one transaction, so two snapshots can never interleave. */
    @Transaction
    open suspend fun record(playerTracks: List<PlayerTrack>, now: Instant): List<PlayHistoryEntry> {
        val snapshot = snapshotHistory(all(), playerTracks, now)
        delete(snapshot.removed)
        upsert(snapshot.entries)
        return snapshot.entries
    }
}
