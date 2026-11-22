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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

interface HistoryBackupFile {
    suspend fun write(json: String)
}

/** Where backup files live, as uri strings. [overwrite] throws when the file cannot be changed, e.g. it belongs to an older install. */
interface BackupStorage {
    suspend fun findExisting(): String?

    suspend fun overwrite(uri: String, json: String)

    /** Makes a new file; the system renames it (e.g. "play-history (1).json") when the name is already taken. */
    suspend fun create(): String
}

/** Remembers the uri of a backup file that was created after the original could not be updated. */
interface BackupLocationStore {
    suspend fun read(): String?

    suspend fun save(uri: String)
}

/** Updates the known backup file, or writes a new one and remembers it when the known one cannot be updated. */
class FallbackHistoryBackupFile(
    private val storage: BackupStorage,
    private val locationStore: BackupLocationStore,
) : HistoryBackupFile {
    override suspend fun write(json: String) {
        val knownUri = locationStore.read() ?: storage.findExisting()
        if (knownUri != null && canOverwrite(knownUri, json)) return
        val createdUri = storage.create()
        storage.overwrite(createdUri, json)
        locationStore.save(createdUri)
    }

    private suspend fun canOverwrite(uri: String, json: String): Boolean =
        try {
            storage.overwrite(uri, json)
            true
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            false
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

    override suspend fun overwrite(uri: String, json: String) =
        withContext(Dispatchers.IO) {
            contentResolver.openOutputStream(Uri.parse(uri), "wt")!!.use { stream -> stream.write(json.toByteArray()) }
        }

    override suspend fun create(): String =
        withContext(Dispatchers.IO) { contentResolver.insert(collection, newFileValues())!!.toString() }

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
