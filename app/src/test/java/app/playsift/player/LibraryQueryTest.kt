package app.playsift.player

import java.time.Instant
import java.time.Period
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryQueryTest {
    private val now = Instant.parse("2026-11-21T00:00:00Z")

    @Test
    fun deleteCandidatesUsesCutoffInSeconds() {
        val query = libraryQuery(LibraryList.DeleteCandidates, Period.ofYears(1), now)

        assertArrayEquals(arrayOf("1763683200"), query.selectionArgs)
    }

    @Test
    fun mostPlayedSortsByTotalPlaysDescending() {
        val query = libraryQuery(LibraryList.MostPlayed, Period.ofYears(1), now)

        assertEquals("folder_files.total_played_times DESC", query.sortOrder)
    }

    @Test
    fun neverPlayedUsesTotalPlayedTimes() {
        val query = libraryQuery(LibraryList.NeverPlayed, Period.ofYears(1), now)

        val selection = query.selection.orEmpty()

        assertTrue(selection.contains("total_played_times"))
        assertFalse(selection.contains(".played_times "))
    }
}
