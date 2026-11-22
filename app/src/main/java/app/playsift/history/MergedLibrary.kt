package app.playsift.history

import app.playsift.player.HistorySummary
import app.playsift.player.LibraryList
import app.playsift.player.PlayerTrack
import java.time.Instant
import java.time.Period
import java.time.ZoneOffset

/** Shows each song with its total plays (carried plus current) and the earliest date ever stored. [entries] must cover every track. */
fun mergeHistory(playerTracks: List<PlayerTrack>, entries: List<PlayHistoryEntry>): List<PlayerTrack> {
    val entriesByKey = entries.associateBy { it.historyKey() }
    return playerTracks.map { track ->
        val entry = entriesByKey.getValue(track.historyKey())
        track.copy(
            playCount = entry.carriedPlays + track.playCount,
            firstSeenAt = entry.firstSeenAt,
            lastPlayedAt = entry.lastPlayedAt,
        )
    }
}

fun historySummary(mergedTracks: List<PlayerTrack>, entries: List<PlayHistoryEntry>) = HistorySummary(
    earliestFirstSeenAt = entries.minOfOrNull { it.firstSeenAt },
    totalTracks = mergedTracks.size,
    unplayedTracks = mergedTracks.count { it.playCount == 0 },
)

/**
 * Filters and sorts in Kotlin because the player's provider only knows its own counts and dates, not the stored history.
 * A delete candidate has no plays ever (total) and was first seen before the cutoff.
 */
fun tracksFor(list: LibraryList, mergedTracks: List<PlayerTrack>, cutoff: Period, now: Instant): List<PlayerTrack> {
    val cutoffInstant = now.atZone(ZoneOffset.UTC).minus(cutoff).toInstant()
    return when (list) {
        LibraryList.MostPlayed -> mergedTracks.filter { it.playCount > 0 }.sortedByDescending { it.playCount }
        LibraryList.NeverPlayed -> mergedTracks.filter { it.playCount == 0 }.sortedBy { it.firstSeenAt }
        LibraryList.DeleteCandidates ->
            mergedTracks.filter { it.playCount == 0 && it.firstSeenAt.isBefore(cutoffInstant) }.sortedBy { it.firstSeenAt }
        LibraryList.AllTracks, LibraryList.Large -> mergedTracks
    }
}
