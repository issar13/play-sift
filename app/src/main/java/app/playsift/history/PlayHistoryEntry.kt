package app.playsift.history

import androidx.room.Entity
import java.time.Instant

/**
 * One song, keyed by file location (plus the title for cue tracks) because the player's track ids change when its library is rebuilt.
 * [carriedPlays] holds plays from before player resets; [lastSeenPlayerCount] is the player's count at the last snapshot.
 */
@Entity(tableName = "play_history", primaryKeys = ["folderPath", "fileName", "cueTitle"])
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
    /** The track title for a cue track, so tracks cut from one file stay separate rows; empty for an ordinary file. */
    val cueTitle: String = "",
    /** When Play Sift moved this song's file to the trash; such a row is never relinked onto a new file. */
    val trashedAt: Instant? = null,
) {
    val totalPlays: Int get() = carriedPlays + lastSeenPlayerCount
}

data class HistoryKey(val folderPath: String, val fileName: String, val cueTitle: String = "")

fun PlayHistoryEntry.historyKey() = HistoryKey(folderPath, fileName, cueTitle)
