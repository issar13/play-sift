package app.playsift.history

import app.playsift.player.PlayerTrack
import java.time.Instant

/** [entries] is every row after the snapshot; [removed] are stored rows that were re-keyed to a new location. */
data class HistorySnapshot(val entries: List<PlayHistoryEntry>, val removed: List<PlayHistoryEntry>)

fun PlayerTrack.historyKey() = HistoryKey(folderPath.orEmpty(), fileName, cueTitle = if (isCueTrack) title else "")

/**
 * Folds the player's current counts into the [stored] rows.
 *
 * A count lower than the last seen one means the player was reset, so the old count moves into carried plays.
 * A song at a new location takes over a stored row only when exactly one stored row (whose location is gone)
 * and exactly one new song share its title and artist; anything less certain starts a fresh row.
 * Songs that share one key (same file, and for cue tracks the same title) collapse to the one with the highest count.
 */
fun snapshotHistory(stored: List<PlayHistoryEntry>, playerTracks: List<PlayerTrack>, now: Instant): HistorySnapshot {
    val storedByKey = stored.associateBy { it.historyKey() }
    val tracksByKey = playerTracks.groupBy { it.historyKey() }.mapValues { (_, sharingTracks) -> sharingTracks.maxBy { it.playCount } }
    val newTracks = tracksByKey.filterKeys { it !in storedByKey }.values
    val orphanRows = stored.filter { it.historyKey() !in tracksByKey }
    val relinkedRows = newTracks
        .filter { track -> track.title.isNotBlank() && newTracks.count { it.isSameSong(track) } == 1 }
        .mapNotNull { track -> orphanRows.filter { it.isSameSong(track) }.singleOrNull()?.let { row -> track.historyKey() to row } }
        .toMap()
    val updated = tracksByKey.map { (key, track) -> advance(storedByKey[key] ?: relinkedRows[key], track, now) }
    val updatedKeys = updated.map { it.historyKey() }.toSet()
    val removed = relinkedRows.values.toList()
    val untouched = stored.filter { it.historyKey() !in updatedKeys && it !in removed }
    return HistorySnapshot(entries = untouched + updated, removed = removed)
}

private fun PlayerTrack.isSameSong(other: PlayerTrack) = title == other.title && artist == other.artist

private fun PlayHistoryEntry.isSameSong(track: PlayerTrack) = title == track.title && artist == track.artist

private fun advance(previous: PlayHistoryEntry?, track: PlayerTrack, now: Instant): PlayHistoryEntry {
    val carriedFromReset = previous?.takeIf { track.playCount < it.lastSeenPlayerCount }?.lastSeenPlayerCount ?: 0
    return PlayHistoryEntry(
        folderPath = track.folderPath.orEmpty(),
        fileName = track.fileName,
        cueTitle = track.historyKey().cueTitle,
        title = track.title,
        artist = track.artist,
        album = track.album,
        lastSeenPlayerCount = track.playCount,
        carriedPlays = (previous?.carriedPlays ?: 0) + carriedFromReset,
        firstSeenAt = listOfNotNull(previous?.firstSeenAt, track.firstSeenAt).min(),
        lastPlayedAt = listOfNotNull(previous?.lastPlayedAt, track.lastPlayedAt).maxOrNull(),
        updatedAt = now,
    )
}
