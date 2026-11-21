package app.playsift.ui

import app.playsift.player.PlayerTrack

const val GROUP_MINIMUM_SONGS = 6

sealed interface MostPlayedEntry {
    data class Single(val track: PlayerTrack) : MostPlayedEntry

    data class Group(val plays: Int, val tracks: List<PlayerTrack>) : MostPlayedEntry
}

fun groupByMatchingPlays(tracks: List<PlayerTrack>): List<MostPlayedEntry> =
    tracks.groupBy(PlayerTrack::playCount).flatMap { (plays, tracksWithPlays) ->
        if (tracksWithPlays.size >= GROUP_MINIMUM_SONGS) {
            listOf(MostPlayedEntry.Group(plays, tracksWithPlays))
        } else {
            tracksWithPlays.map { track -> MostPlayedEntry.Single(track) }
        }
    }
