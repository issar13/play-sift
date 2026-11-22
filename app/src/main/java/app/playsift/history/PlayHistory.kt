package app.playsift.history

import android.content.Context
import android.util.Log
import app.playsift.player.PlayerTrack
import app.playsift.settings.settingsDataStore
import java.time.Clock
import kotlinx.coroutines.CancellationException

interface PlayHistory {
    /** Folds the player's current counts into the stored history, refreshes the backup file and returns every stored row. */
    suspend fun record(playerTracks: List<PlayerTrack>): List<PlayHistoryEntry>

    /** Writes the backup file now; a failure propagates so the caller can tell the user. */
    suspend fun exportNow()

    /** Merges a backup file's text into the stored history. */
    suspend fun importFrom(json: String)

    /** Marks the history rows of songs Play Sift just moved to the trash, so a re-added song starts a fresh row. */
    suspend fun markTrashed(tracks: List<PlayerTrack>)

    /** True before the first snapshot of a fresh install. */
    suspend fun isEmpty(): Boolean
}

class RoomPlayHistory(
    private val dao: PlayHistoryDao,
    private val backupFile: HistoryBackupFile,
    private val clock: Clock,
) : PlayHistory {
    /** The backup is a safety copy, so failing to write it never blocks reading the library. */
    override suspend fun record(playerTracks: List<PlayerTrack>): List<PlayHistoryEntry> {
        val entries = dao.record(playerTracks, clock.instant())
        try {
            backupFile.write(encodeHistory(entries, clock.instant()))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Log.w("PlayHistory", "Could not write the history backup file", exception)
        }
        return entries
    }

    override suspend fun exportNow() = backupFile.write(encodeHistory(dao.all(), clock.instant()))

    override suspend fun importFrom(json: String) = dao.importEntries(decodeHistory(json))

    override suspend fun markTrashed(tracks: List<PlayerTrack>) =
        inObservationOrder { dao.markTrashed(tracks.map { it.historyKey() }, clock.instant()) }

    override suspend fun isEmpty() = dao.count() == 0
}

fun roomPlayHistory(context: Context): PlayHistory =
    RoomPlayHistory(
        dao = PlayHistoryDatabase.open(context).playHistoryDao(),
        backupFile = FallbackHistoryBackupFile(
            storage = MediaStoreBackupStorage(context.contentResolver),
            locationStore = DataStoreBackupLocation(context.settingsDataStore),
        ),
        clock = Clock.systemUTC(),
    )
