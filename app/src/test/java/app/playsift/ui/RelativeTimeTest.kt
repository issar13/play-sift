package app.playsift.ui

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {
    private val today = LocalDate.of(2026, 6, 15)

    private fun label(firstSeenDate: String) = addedLabel(Instant.parse("${firstSeenDate}T10:00:00Z"), today)

    @Test
    fun usesYearsWhenAtLeastOneYearOld() = assertEquals("Added 2 years ago", label("2024-05-01"))

    @Test
    fun usesSingularForOneYear() = assertEquals("Added 1 year ago", label("2025-06-15"))

    @Test
    fun usesMonthsWhenUnderAYear() = assertEquals("Added 3 months ago", label("2026-03-10"))

    @Test
    fun usesSingularForOneMonth() = assertEquals("Added 1 month ago", label("2026-05-15"))

    @Test
    fun usesDaysWhenUnderAMonth() = assertEquals("Added 5 days ago", label("2026-06-10"))

    @Test
    fun usesSingularForOneDay() = assertEquals("Added 1 day ago", label("2026-06-14"))

    @Test
    fun usesTodayForSameDay() = assertEquals("Added today", label("2026-06-15"))
}
