package app.playsift.history

import app.playsift.player.Access
import app.playsift.player.AccessState
import app.playsift.player.Library
import app.playsift.player.LibraryList
import java.time.Clock
import java.time.Period

/** Read only: records the player's counts without deleting anything, and does nothing while the player is not readable. */
class HistorySnapshotter(
    private val access: Access,
    private val library: Library,
    private val history: PlayHistory,
    private val clock: Clock,
) {
    suspend fun snapshot() {
        if (access.state() != AccessState.Ready) return
        try {
            // The cutoff only matters to the lists, not to AllTracks.
            history.record(library.tracks(LibraryList.AllTracks, Period.ZERO, clock.instant()))
        } catch (_: SecurityException) {
            return
        }
    }
}
