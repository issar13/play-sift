package app.playsift.ui

import java.util.Locale

private const val BYTES_PER_MEGABYTE = 1_000_000.0

/** The one size format in the app: 1 MB is 1,000,000 bytes. */
fun megabytesText(sizeBytes: Long): String = String.format(Locale.ROOT, "%.1f MB", sizeBytes / BYTES_PER_MEGABYTE)

private const val BYTES_PER_GIGABYTE = 1_000_000_000.0

/** Gigabytes from 1 GB up, megabytes below, so "5.8 GB" instead of "5800.0 MB". */
fun storageText(sizeBytes: Long): String =
    if (sizeBytes >= BYTES_PER_GIGABYTE) String.format(Locale.ROOT, "%.1f GB", sizeBytes / BYTES_PER_GIGABYTE) else megabytesText(sizeBytes)
