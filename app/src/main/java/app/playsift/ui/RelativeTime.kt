package app.playsift.ui

import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneOffset

fun addedLabel(firstSeenAt: Instant, today: LocalDate): String {
    val age = Period.between(firstSeenAt.atZone(ZoneOffset.UTC).toLocalDate(), today)
    val ageText = when {
        age.years > 0 -> unitText(age.years, "year")
        age.months > 0 -> unitText(age.months, "month")
        age.days > 0 -> unitText(age.days, "day")
        else -> null
    }
    return ageText?.let { "Added $it ago" } ?: "Added today"
}

private fun unitText(amount: Int, unit: String) = if (amount == 1) "1 $unit" else "$amount ${unit}s"
