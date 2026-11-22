package app.playsift.player

import java.time.Instant
import java.time.Period
import java.time.ZoneOffset

enum class HistoryHealth { Healthy, TooYoung, MostlyUnplayed }

private const val MOSTLY_UNPLAYED_PERCENT = 95

fun historyHealth(
    earliestFirstSeenAt: Instant?,
    totalTracks: Int,
    unplayedTracks: Int,
    cutoff: Period,
    now: Instant,
): HistoryHealth {
    val cutoffInstant = now.atZone(ZoneOffset.UTC).minus(cutoff).toInstant()
    val isTooYoung = earliestFirstSeenAt == null || earliestFirstSeenAt.isAfter(cutoffInstant)
    return when {
        isTooYoung -> HistoryHealth.TooYoung
        unplayedTracks * 100 >= totalTracks * MOSTLY_UNPLAYED_PERCENT -> HistoryHealth.MostlyUnplayed
        else -> HistoryHealth.Healthy
    }
}
