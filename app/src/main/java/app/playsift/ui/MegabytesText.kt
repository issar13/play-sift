package app.playsift.ui

import java.util.Locale

private const val BYTES_PER_MEGABYTE = 1_000_000.0

/** The one size format in the app: 1 MB is 1,000,000 bytes. */
fun megabytesText(sizeBytes: Long): String = String.format(Locale.ROOT, "%.1f MB", sizeBytes / BYTES_PER_MEGABYTE)
