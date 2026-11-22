package app.playsift.player

import android.content.ContentResolver
import android.database.Cursor
import java.time.Instant
import java.time.Period
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HistorySummary(val earliestFirstSeenAt: Instant?, val totalTracks: Int, val unplayedTracks: Int)

interface Library {
    suspend fun tracks(list: LibraryList, cutoff: Period, now: Instant): List<PlayerTrack>
}

class PlayerLibrary(private val contentResolver: ContentResolver) : Library {
    private val trackProjection = with(PlayerContract.Columns) {
        arrayOf(
            ID, TITLE, ARTIST, ALBUM, NAME, FOLDER_ID, FILE_PATH, CUE_FOLDER_ID, USER_ADDED,
            TOTAL_PLAYED_TIMES, PLAYED_AT, CREATED_AT, DURATION, OFFSET_MS,
        )
    }

    /**
     * Lists tracks for [list]. Filtering and sorting happen in the provider via [libraryQuery].
     *
     * The `/files` URI is not relied on to join folders, so folder paths and the cue flag come from
     * one extra query to `/folders` (`folders._id IN (?,?,...)`) for the distinct folder ids in the result.
     *
     * A track is a cue track when its folder is a cue folder, it has a start offset, or it is the
     * cue source image (trashing that image would remove every track cut from it).
     *
     * A [SecurityException] from the provider (data access not granted) propagates unchanged.
     */
    override suspend fun tracks(list: LibraryList, cutoff: Period, now: Instant): List<PlayerTrack> =
        withContext(Dispatchers.IO) {
            val query = libraryQuery(list, cutoff, now)
            val rows = contentResolver
                .query(PlayerContract.FILES_URI, trackProjection, query.selection, query.selectionArgs, query.sortOrder)!!
                .use { cursor -> cursor.readRows { cursor.toTrackRow() } }
            val folders = foldersById(rows.map { it.folderId }.distinct())
            rows.map { row ->
                val folder = folders[row.folderId]
                row.track.copy(
                    folderPath = folder?.path,
                    isCueTrack = folder?.isCue == true || row.hasCueMarkers,
                )
            }
        }

    /** Chunked so each query stays under SQLite's bind-variable limit (999 on older builds). */
    private fun foldersById(folderIds: List<Long>): Map<Long, FolderRow> =
        folderIds
            .chunked(FOLDER_LOOKUP_CHUNK_SIZE)
            .flatMap { chunk ->
                contentResolver
                    .query(
                        PlayerContract.FOLDERS_URI,
                        arrayOf(PlayerContract.Columns.FOLDERS_ID, PlayerContract.Columns.FOLDER_PATH, PlayerContract.Columns.IS_CUE),
                        "${PlayerContract.Columns.FOLDERS_ID} IN (${chunk.joinToString(",") { "?" }})",
                        chunk.map { it.toString() }.toTypedArray(),
                        null,
                    )!!
                    .use { cursor -> cursor.readRows { cursor.getLong(0) to FolderRow(cursor.getString(1), cursor.getInt(2) != 0) } }
            }
            .toMap()

    private class FolderRow(val path: String, val isCue: Boolean)

    private class TrackRow(val folderId: Long, val hasCueMarkers: Boolean, val track: PlayerTrack)

    /** Reads by position in [trackProjection], since qualified column names may not appear verbatim in the cursor. */
    private fun Cursor.toTrackRow(): TrackRow {
        val folderId = getLong(5)
        val lastPlayedMillis = getLong(10)
        return TrackRow(
            folderId = folderId,
            hasCueMarkers = getLong(13) > 0 || (!isNull(7) && getLong(7) > 0),
            track = PlayerTrack(
                id = getLong(0),
                title = getString(1).orEmpty(),
                artist = getString(2).orEmpty(),
                album = getString(3).orEmpty(),
                fileName = getString(4).orEmpty(),
                folderPath = null,
                filePath = getString(6),
                isCueTrack = false,
                isUserAdded = getInt(8) != 0,
                playCount = getInt(9),
                lastPlayedAt = if (lastPlayedMillis > 0) Instant.ofEpochMilli(lastPlayedMillis) else null,
                firstSeenAt = Instant.ofEpochSecond(getLong(11)),
                durationMillis = getLong(12),
            ),
        )
    }

    private companion object {
        const val FOLDER_LOOKUP_CHUNK_SIZE = 500
    }
}

internal fun <T> Cursor.readRows(readRow: () -> T): List<T> =
    generateSequence { if (moveToNext()) readRow() else null }.toList()
