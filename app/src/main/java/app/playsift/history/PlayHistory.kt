package app.playsift.history

import app.playsift.player.PlayerTrack
import java.time.Clock

interface PlayHistory {
    /** Folds the player's current counts into the stored history and returns every stored row. */
    suspend fun record(playerTracks: List<PlayerTrack>): List<PlayHistoryEntry>
}

class RoomPlayHistory(private val dao: PlayHistoryDao, private val clock: Clock) : PlayHistory {
    override suspend fun record(playerTracks: List<PlayerTrack>): List<PlayHistoryEntry> =
        dao.record(playerTracks, clock.instant())
}
