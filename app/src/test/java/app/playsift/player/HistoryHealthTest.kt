package app.playsift.player

import java.time.Instant
import java.time.Period
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryHealthTest {
    private val now = Instant.parse("2026-11-21T00:00:00Z")
    private val oldEnough = Instant.parse("2024-01-01T00:00:00Z")
    private val cutoff = Period.ofYears(1)

    @Test
    fun tooYoungWhenEarliestNewerThanCutoff() {
        val earliest = Instant.parse("2026-06-01T00:00:00Z")

        assertEquals(HistoryHealth.TooYoung, historyHealth(earliest, 100, 10, cutoff, now))
    }

    @Test
    fun mostlyUnplayedAtNinetyFivePercent() {
        assertEquals(HistoryHealth.MostlyUnplayed, historyHealth(oldEnough, 100, 95, cutoff, now))
        assertEquals(HistoryHealth.Healthy, historyHealth(oldEnough, 100, 94, cutoff, now))
    }

    @Test
    fun tooYoungWinsOverMostlyUnplayed() {
        val earliest = Instant.parse("2026-06-01T00:00:00Z")

        assertEquals(HistoryHealth.TooYoung, historyHealth(earliest, 100, 100, cutoff, now))
    }

    @Test
    fun emptyLibraryIsTooYoung() {
        assertEquals(HistoryHealth.TooYoung, historyHealth(null, 0, 0, cutoff, now))
    }
}
