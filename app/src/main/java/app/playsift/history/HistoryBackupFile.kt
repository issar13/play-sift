package app.playsift.history

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface HistoryBackupFile {
    suspend fun write(json: String)
}

/** Writes `Documents/PlaySift/play-history.json`, which survives an uninstall and can be copied to a new phone. */
class MediaStoreHistoryBackupFile(private val contentResolver: ContentResolver) : HistoryBackupFile {
    private val collection = MediaStore.Files.getContentUri("external_primary")

    override suspend fun write(json: String) =
        withContext(Dispatchers.IO) {
            val uri = existingFileUri() ?: contentResolver.insert(collection, newFileValues())!!
            contentResolver.openOutputStream(uri, "wt")!!.use { stream -> stream.write(json.toByteArray()) }
        }

    private fun existingFileUri() =
        contentResolver
            .query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(RELATIVE_PATH, FILE_NAME),
                null,
            )!!
            .use { cursor -> if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null }

    private fun newFileValues() = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
        put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
    }

    private companion object {
        const val RELATIVE_PATH = "Documents/PlaySift/"
        const val FILE_NAME = "play-history.json"
    }
}
