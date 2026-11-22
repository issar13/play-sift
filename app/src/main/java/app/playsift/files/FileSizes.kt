package app.playsift.files

import android.content.ContentResolver
import android.database.Cursor
import android.provider.MediaStore
import app.playsift.player.PlayerTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FileLocation(val relativePath: String, val displayName: String)

interface FileSizes {
    /** File sizes in bytes keyed by location; a location that more than one file shares is left out. */
    suspend fun sizesByLocation(): Map<FileLocation, Long>
}

/** Null for cue tracks, streams and tracks that are not on the internal volume. */
fun PlayerTrack.fileLocation(): FileLocation? =
    folderPath
        ?.takeIf { !isCueTrack && filePath == null && !isUserAdded }
        ?.let(::mediaStoreLocation)
        ?.let { relativePath -> FileLocation(relativePath, fileName) }

class MediaStoreFileSizes(private val contentResolver: ContentResolver) : FileSizes {
    private val audioCollection = MediaStore.Audio.Media.getContentUri("external_primary")

    override suspend fun sizesByLocation(): Map<FileLocation, Long> =
        withContext(Dispatchers.IO) {
            contentResolver
                .query(
                    audioCollection,
                    arrayOf(MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE),
                    null,
                    null,
                    null,
                )!!
                .use { cursor -> cursor.readRows { FileLocation(cursor.getString(0).orEmpty(), cursor.getString(1).orEmpty()) to cursor.getLong(2) } }
                .groupBy({ (location, _) -> location }, { (_, sizeBytes) -> sizeBytes })
                .filterValues { sizes -> sizes.size == 1 }
                .mapValues { (_, sizes) -> sizes.single() }
        }

    private fun <T> Cursor.readRows(readRow: () -> T): List<T> =
        generateSequence { if (moveToNext()) readRow() else null }.toList()
}
