package app.playsift.history

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** The largest history file Play Sift reads: far more than any real library produces, small enough to hold in memory. */
const val MAX_BACKUP_BYTES = 20 * 1024 * 1024

private const val READ_CHUNK_BYTES = 8192

class BackupTooLargeException(maxBytes: Int) : IOException("The history file is larger than $maxBytes bytes")

/** Reads [stream] as text, giving up with [BackupTooLargeException] as soon as it holds more than [maxBytes], so a huge file is never loaded whole. */
fun readBackupText(stream: InputStream, maxBytes: Int = MAX_BACKUP_BYTES): String {
    val collected = ByteArrayOutputStream()
    val buffer = ByteArray(READ_CHUNK_BYTES)
    generateSequence { stream.read(buffer).takeIf { it >= 0 } }.forEach { readCount ->
        collected.write(buffer, 0, readCount)
        if (collected.size() > maxBytes) throw BackupTooLargeException(maxBytes)
    }
    return String(collected.toByteArray(), Charsets.UTF_8)
}
