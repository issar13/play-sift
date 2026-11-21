package app.playsift.player

import java.time.Instant
import java.time.Period
import java.time.ZoneOffset

enum class LibraryList { MostPlayed, NeverPlayed, DeleteCandidates }

data class LibraryQuery(
    val selection: String?,
    val selectionArgs: Array<String>?,
    val sortOrder: String,
)

/** The player's `total_played_times` is the real play count; `played_times` is a per-session counter. */
fun libraryQuery(list: LibraryList, cutoff: Period, now: Instant): LibraryQuery =
    when (list) {
        LibraryList.MostPlayed -> LibraryQuery(
            selection = "${PlayerContract.Columns.TOTAL_PLAYED_TIMES} > 0",
            selectionArgs = null,
            sortOrder = "${PlayerContract.Columns.TOTAL_PLAYED_TIMES} DESC",
        )
        LibraryList.NeverPlayed -> LibraryQuery(
            selection = "${PlayerContract.Columns.TOTAL_PLAYED_TIMES} = 0",
            selectionArgs = null,
            sortOrder = "${PlayerContract.Columns.CREATED_AT} ASC",
        )
        LibraryList.DeleteCandidates -> LibraryQuery(
            selection = "${PlayerContract.Columns.TOTAL_PLAYED_TIMES} = 0 AND " +
                "${PlayerContract.Columns.CREATED_AT} < ?",
            selectionArgs = arrayOf(now.atZone(ZoneOffset.UTC).minus(cutoff).toEpochSecond().toString()),
            sortOrder = "${PlayerContract.Columns.CREATED_AT} ASC",
        )
    }
