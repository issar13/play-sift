package app.playsift.history

import androidx.room.Entity
import java.time.Instant

/**
 * One song, keyed by file location because the player's track ids change when its library is rebuilt.
 * [carriedPlays] holds plays from before player resets; [lastSeenPlayerCount] is the player's count at the last snapshot.
 */
@Entity(tableName = "play_history", primaryKeys = ["folderPath", "fileName"])
data class PlayHistoryEntry(
    val folderPath: String,
    val fileName: String,
    val title: String,
    val artist: String,
    val album: String,
    val lastSeenPlayerCount: Int,
    val carriedPlays: Int,
    val firstSeenAt: Instant,
    val lastPlayedAt: Instant?,
    val updatedAt: Instant,
) {
    val totalPlays: Int get() = carriedPlays + lastSeenPlayerCount
}

data class HistoryKey(val folderPath: String, val fileName: String)

fun PlayHistoryEntry.historyKey() = HistoryKey(folderPath, fileName)
