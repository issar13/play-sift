package app.playsift.history

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface HistoryBackupFile {
    suspend fun write(json: String)
}

/** Where backup files live, as uri strings. A backup is only ever replaced by a file that was written completely first. */
interface BackupStorage {
    suspend fun findExisting(): String?

    /** Writes [json] to a new file under a temporary name; a failed write removes the partial file and throws. */
    suspend fun writeTemporary(json: String): String

    /**
     * Makes the finished [temporaryUri] the backup: the [previousUri] file is removed when the system lets this app remove it
     * (a file from an older install it cannot remove stays, and the new file gets the next free name), then the temporary file
     * takes the backup's name. Returns the uri the backup has now. The previous backup is untouched if this throws before removing it.
     */
    suspend fun publish(temporaryUri: String, previousUri: String?): String
}

/** Remembers the uri of the current backup file. */
interface BackupLocationStore {
    suspend fun read(): String?

    suspend fun save(uri: String)
}

/** Writes the backup as a new complete file and swaps it in, so a failed or interrupted write never destroys the previous backup. */
class FallbackHistoryBackupFile(
    private val storage: BackupStorage,
    private val locationStore: BackupLocationStore,
) : HistoryBackupFile {
    override suspend fun write(json: String) {
        writeLock.withLock {
            val previousUri = locationStore.read() ?: storage.findExisting()
            val temporaryUri = storage.writeTemporary(json)
            val publishedUri = storage.publish(temporaryUri, previousUri)
            locationStore.save(publishedUri)
        }
    }

    private companion object {
        /** One lock for every instance and every caller (screen, export, daily worker), because each call makes its own instance. */
        val writeLock = Mutex()
    }
}

class DataStoreBackupLocation(private val dataStore: DataStore<Preferences>) : BackupLocationStore {
    override suspend fun read(): String? = dataStore.data.first()[LOCATION_KEY]

    override suspend fun save(uri: String) {
        dataStore.edit { preferences -> preferences[LOCATION_KEY] = uri }
    }

    private companion object {
        val LOCATION_KEY = stringPreferencesKey("history_backup_uri")
    }
}

/** Files live in `Documents/PlaySift/`, which survives an uninstall and can be copied to a new phone. */
class MediaStoreBackupStorage(private val contentResolver: ContentResolver) : BackupStorage {
    private val collection = MediaStore.Files.getContentUri("external_primary")

    override suspend fun findExisting(): String? =
        withContext(Dispatchers.IO) {
            contentResolver
                .query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(RELATIVE_PATH, FILE_NAME),
                    null,
                )!!
                .use { cursor -> if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)).toString() else null }
        }

    override suspend fun writeTemporary(json: String): String =
        withContext(Dispatchers.IO) {
            val uri = checkNotNull(contentResolver.insert(collection, fileValues(TEMPORARY_FILE_NAME))) { "Could not create a backup file" }
            try {
                checkNotNull(contentResolver.openOutputStream(uri, "w")) { "Could not open the backup file" }.use { stream -> stream.write(json.toByteArray()) }
            } catch (exception: Exception) {
                removeQuietly(uri)
                throw exception
            }
            uri.toString()
        }

    override suspend fun publish(temporaryUri: String, previousUri: String?): String =
        withContext(Dispatchers.IO) {
            previousUri?.let { removeQuietly(Uri.parse(it)) }
            val renamed = contentResolver.update(Uri.parse(temporaryUri), ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME) }, null, null)
            check(renamed == 1) { "Could not name the new backup file" }
            temporaryUri
        }

    private fun removeQuietly(uri: Uri) {
        try {
            contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
            // A file this app does not own cannot be removed; the backup then keeps the next free name.
        }
    }

    private fun fileValues(displayName: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
        put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
    }

    private companion object {
        const val RELATIVE_PATH = "Documents/PlaySift/"
        const val FILE_NAME = "play-history.json"
        const val TEMPORARY_FILE_NAME = "play-history-new.json"
    }
}
