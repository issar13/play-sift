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

    @Query("SELECT COUNT(*) FROM play_history")
    abstract suspend fun count(): Int

    @Upsert
    abstract suspend fun upsert(entries: List<PlayHistoryEntry>)

    @Delete
    abstract suspend fun delete(entries: List<PlayHistoryEntry>)

    @Query("UPDATE play_history SET trashedAt = :trashedAt WHERE folderPath = :folderPath AND fileName = :fileName AND cueTitle = :cueTitle")
    abstract suspend fun markTrashed(folderPath: String, fileName: String, cueTitle: String, trashedAt: Instant)

    /** Marks the rows for [keys] in one transaction; a key with no stored row is ignored. */
    @Transaction
    open suspend fun markTrashed(keys: List<HistoryKey>, trashedAt: Instant) {
        keys.forEach { key -> markTrashed(key.folderPath, key.fileName, key.cueTitle, trashedAt) }
    }

    /** Reads, folds in [playerTracks] and writes in one transaction, so two snapshots can never interleave. */
    @Transaction
    open suspend fun record(playerTracks: List<PlayerTrack>, now: Instant): List<PlayHistoryEntry> {
        val snapshot = snapshotHistory(all(), playerTracks, now)
        delete(snapshot.removed)
        upsert(snapshot.entries)
        return snapshot.entries
    }

    /** Merges a backup file into the stored rows in one transaction. */
    @Transaction
    open suspend fun importEntries(imported: List<PlayHistoryEntry>) {
        upsert(mergeImported(all(), imported))
    }
}
