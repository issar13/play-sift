package app.playsift.spotify

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyTransferTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-22T00:00:00Z"), ZoneOffset.UTC)
    private val api = FakeSpotifyApi()
    private val dao = FakeSpotifyDao()

    @Test
    fun topPlayedTakesTheMostPlayedFirstAndOnlyPlayedSongs() {
        val merged = listOf(
            songTrack(1, playCount = 5),
            songTrack(2, playCount = 50),
            songTrack(3, playCount = 0),
            songTrack(4, playCount = 20),
        )

        val top = tracksFor(SendSource.TopPlayed(2), merged, tickedTracks = emptyList())

        assertEquals(listOf(2L, 4L), top.map { it.id })
    }

    @Test
    fun everythingPlayedKeepsAllPlayedSongsInPlayCountOrder() {
        val merged = listOf(songTrack(1, playCount = 5), songTrack(2, playCount = 50), songTrack(3, playCount = 0))

        assertEquals(listOf(2L, 1L), tracksFor(SendSource.EverythingPlayed, merged, emptyList()).map { it.id })
    }

    @Test
    fun tickedSongsAreSentAsGiven() {
        val ticked = listOf(songTrack(7, playCount = 0))

        assertEquals(ticked, tracksFor(SendSource.TickedSongs, listOf(songTrack(1)), ticked))
    }

    @Test
    fun playerPlaylistKeepsTheEntryOrderDropsDuplicatesAndUnknownIds() {
        val merged = listOf(songTrack(1, playCount = 0), songTrack(2, playCount = 9), songTrack(3, playCount = 5))

        val songs = tracksFor(SendSource.PlayerPlaylist(7, "Gym"), merged, emptyList(), playlistSongIds = listOf(3L, 99L, 1L, 3L, 2L, 1L))

        assertEquals(listOf(3L, 1L, 2L), songs.map { it.id })
    }

    @Test
    fun playerPlaylistSourceIsKeyedByItsNameAndDefaultsToIt() {
        val source = SendSource.PlayerPlaylist(7, "Gym")

        assertEquals("player:gym", source.playlistKey("Gym"))
        assertEquals("Gym", source.nameFor(""))
    }

    @Test
    fun matchingSortsSongsIntoThreeBuckets() = runTest {
        api.catalogue = mapOf(
            "Exact" to listOf(spotifyTrack("Exact")),
            "Close" to listOf(spotifyTrack("Close", durationMillis = 100_000)),
        )
        val tracks = listOf(songTrack(1, title = "Exact"), songTrack(2, title = "Close"), songTrack(3, title = "Missing"))

        val report = SongMatcher(api, dao, clock).match(tracks) { _, _ -> }

        assertEquals(listOf(1L), report.matched.map { it.track.id })
        assertEquals(listOf(2L), report.unsure.map { it.track.id })
        assertEquals(listOf(3L), report.notFound.map { it.id })
    }

    @Test
    fun matchingReportsProgress() = runTest {
        val progress = mutableListOf<Pair<Int, Int>>()

        SongMatcher(api, dao, clock).match(listOf(songTrack(1), songTrack(2))) { done, total -> progress += done to total }

        assertEquals(listOf(1 to 2, 2 to 2), progress)
    }

    @Test
    fun matchedSongsAreCachedAndNotSearchedAgain() = runTest {
        api.catalogue = mapOf("Exact" to listOf(spotifyTrack("Exact")))
        val matcher = SongMatcher(api, dao, clock)
        val tracks = listOf(songTrack(1, title = "Exact"))

        matcher.match(tracks) { _, _ -> }
        val second = matcher.match(tracks) { _, _ -> }

        assertEquals(listOf("Exact"), api.searchedTitles)
        assertEquals(listOf("spotify:track:Exact"), second.matched.map { it.spotifyUri })
    }

    @Test
    fun unsureAndMissingSongsAreNotCachedUntilConfirmed() = runTest {
        api.catalogue = mapOf("Close" to listOf(spotifyTrack("Close", durationMillis = 100_000)))
        val matcher = SongMatcher(api, dao, clock)
        val tracks = listOf(songTrack(1, title = "Close"), songTrack(2, title = "Missing"))

        val first = matcher.match(tracks) { _, _ -> }
        assertTrue(dao.matches.isEmpty())

        matcher.confirm(first.unsure.single())
        val second = matcher.match(tracks) { _, _ -> }

        assertEquals(listOf(1L), second.matched.map { it.track.id })
        assertEquals(true, second.matched.single().wasConfirmedByUser)
    }

    @Test
    fun aSongPickedByHandIsCachedAndReusedWithoutSearching() = runTest {
        val matcher = SongMatcher(api, dao, clock)
        val song = songTrack(1, title = "Lost")
        val picked = spotifyTrack("Lost - Live", uri = "spotify:track:live")

        matcher.confirm(UnsureSong(song, picked))
        val report = matcher.match(listOf(song), onProgress = { _, _ -> })

        assertEquals(listOf(MatchedSong(song, "spotify:track:live", wasConfirmedByUser = true)), report.matched)
        assertTrue(api.searchedTitles.isEmpty())
        assertTrue(trashOffer(SendResult(null, report.matched, emptyList(), emptyList())).isEmpty())
    }

    @Test
    fun cueTracksSharingOneFileAreCachedSeparately() = runTest {
        api.catalogue = mapOf("First" to listOf(spotifyTrack("First")), "Second" to listOf(spotifyTrack("Second")))
        val firstCue = songTrack(1, title = "First", fileName = "album.flac").copy(isCueTrack = true)
        val secondCue = songTrack(2, title = "Second", fileName = "album.flac").copy(isCueTrack = true)

        SongMatcher(api, dao, clock).match(listOf(firstCue, secondCue)) { _, _ -> }

        assertEquals(2, dao.matches.size)
    }

    @Test
    fun publishingMoreThanOneBatchReplacesFirstThenAppendsInHundreds() = runTest {
        val uris = (1..250).map { "spotify:track:$it" }

        PlaylistPublisher(api, dao).publish(SendSource.EverythingPlayed, "All", uris)

        assertEquals(listOf(100), api.replaceCalls.map { it.second.size })
        assertEquals(listOf(100, 50), api.addCalls.map { it.second.size })
        assertEquals(uris, api.replaceCalls.single().second + api.addCalls.flatMap { it.second })
    }

    @Test
    fun exactlyOneHundredSongsNeedNoAppend() = runTest {
        PlaylistPublisher(api, dao).publish(SendSource.TopPlayed(100), "Top", (1..100).map { "spotify:track:$it" })

        assertEquals(1, api.replaceCalls.size)
        assertTrue(api.addCalls.isEmpty())
    }

    @Test
    fun runningTheSameSourceAgainReplacesInsteadOfDuplicating() = runTest {
        val publisher = PlaylistPublisher(api, dao)

        val first = publisher.publish(SendSource.TopPlayed(100), "Play Sift top 100", listOf("spotify:track:a"))
        val second = publisher.publish(SendSource.TopPlayed(100), "Play Sift top 100", listOf("spotify:track:b"))

        assertEquals(1, api.createdNames.size)
        assertEquals(first.id, second.id)
        assertEquals(listOf(first.id to listOf("spotify:track:a"), first.id to listOf("spotify:track:b")), api.replaceCalls)
    }

    @Test
    fun differentSourcesGetTheirOwnPlaylists() = runTest {
        val publisher = PlaylistPublisher(api, dao)

        publisher.publish(SendSource.TopPlayed(100), "Top", listOf("spotify:track:a"))
        publisher.publish(SendSource.EverythingPlayed, "All", listOf("spotify:track:a"))

        assertEquals(listOf("Top", "All"), api.createdNames)
    }

    @Test
    fun tickedSongsWithANewNameMakeANewPlaylist() = runTest {
        val publisher = PlaylistPublisher(api, dao)

        val roadTrip = publisher.publish(SendSource.TickedSongs, "Road trip", listOf("spotify:track:a"))
        val gym = publisher.publish(SendSource.TickedSongs, "Gym", listOf("spotify:track:b"))

        assertEquals(listOf("Road trip", "Gym"), api.createdNames)
        assertNotEquals(roadTrip.id, gym.id)
        assertEquals(listOf(roadTrip.id to listOf("spotify:track:a"), gym.id to listOf("spotify:track:b")), api.replaceCalls)
    }

    @Test
    fun tickedSongsWithTheSameNameUpdateThatPlaylist() = runTest {
        val publisher = PlaylistPublisher(api, dao)

        val first = publisher.publish(SendSource.TickedSongs, "Road trip", listOf("spotify:track:a"))
        publisher.publish(SendSource.TickedSongs, "Gym", listOf("spotify:track:b"))
        val third = publisher.publish(SendSource.TickedSongs, " road trip ", listOf("spotify:track:c"))

        assertEquals(2, api.createdNames.size)
        assertEquals(first.id, third.id)
        assertEquals(first.id to listOf("spotify:track:c"), api.replaceCalls.last())
    }

    @Test
    fun topTwoHundredThenTopOneHundredMakeTwoPlaylists() = runTest {
        val publisher = PlaylistPublisher(api, dao)

        val two = publisher.publish(SendSource.TopPlayed(200), SendSource.TopPlayed(200).defaultName, listOf("spotify:track:a"))
        val one = publisher.publish(SendSource.TopPlayed(100), SendSource.TopPlayed(100).defaultName, listOf("spotify:track:b"))

        assertEquals(listOf("Play Sift top 200", "Play Sift top 100"), api.createdNames)
        assertNotEquals(two.id, one.id)
    }

    @Test
    fun topOneHundredAgainUpdatesTheSamePlaylist() = runTest {
        val publisher = PlaylistPublisher(api, dao)
        val name = SendSource.TopPlayed(100).defaultName

        val first = publisher.publish(SendSource.TopPlayed(100), name, listOf("spotify:track:a"))
        val second = publisher.publish(SendSource.TopPlayed(100), name, listOf("spotify:track:b"))

        assertEquals(1, api.createdNames.size)
        assertEquals(first.id, second.id)
    }

    @Test
    fun aTypedNameForTopPlayedMakesItsOwnPlaylist() = runTest {
        val publisher = PlaylistPublisher(api, dao)

        val byDefault = publisher.publish(SendSource.TopPlayed(100), "Play Sift top 100", listOf("spotify:track:a"))
        val typed = publisher.publish(SendSource.TopPlayed(100), "Summer", listOf("spotify:track:b"))

        assertEquals(2, api.createdNames.size)
        assertNotEquals(byDefault.id, typed.id)
    }

    @Test
    fun everythingPlayedRemembersPlaylistsPerNameToo() = runTest {
        val publisher = PlaylistPublisher(api, dao)

        publisher.publish(SendSource.EverythingPlayed, "One", listOf("spotify:track:a"))
        publisher.publish(SendSource.EverythingPlayed, "Two", listOf("spotify:track:a"))

        assertEquals(listOf("One", "Two"), api.createdNames)
    }

    @Test
    fun defaultNamesCarryTheCount() {
        assertEquals("Play Sift top 200", SendSource.TopPlayed(200).defaultName)
        assertEquals("Play Sift played", SendSource.EverythingPlayed.defaultName)
        assertEquals("Play Sift picks", SendSource.TickedSongs.defaultName)
    }

    @Test
    fun aPlaylistDeletedOnSpotifyIsReplacedByANewOne() = runTest {
        val publisher = PlaylistPublisher(api, dao)
        val first = publisher.publish(SendSource.TickedSongs, "Picks", listOf("spotify:track:a"))
        api.deletedPlaylistIds = setOf(first.id)

        val second = publisher.publish(SendSource.TickedSongs, "Picks", listOf("spotify:track:a"))

        assertEquals(2, api.createdNames.size)
        assertEquals(second.id, dao.playlists.getValue("test-user" to "ticked:picks").playlistId)
    }

    @Test
    fun trashOfferHoldsOnlySongsMatchedAutomaticallyAndAdded() {
        val automatic = MatchedSong(songTrack(1), "spotify:track:1", wasConfirmedByUser = false)
        val confirmed = MatchedSong(songTrack(2), "spotify:track:2", wasConfirmedByUser = true)
        val result = SendResult(
            playlist = SpotifyPlaylist("p", "url"),
            added = listOf(automatic, confirmed),
            skippedUnsure = listOf(UnsureSong(songTrack(3), spotifyTrack("Other"))),
            notFound = listOf(songTrack(4)),
        )

        assertEquals(listOf(1L), trashOffer(result).map { it.id })
    }

    @Test
    fun theSameSpotifySongFromTwoFilesIsAddedOnce() {
        val songs = listOf(
            MatchedSong(songTrack(1), "spotify:track:x", false),
            MatchedSong(songTrack(2), "spotify:track:x", false),
            MatchedSong(songTrack(3), "spotify:track:y", false),
        )

        assertEquals(listOf("spotify:track:x", "spotify:track:y"), uniqueUris(songs))
    }

    @Test
    fun aCachedMatchIsNotReusedWhenTheRecordingChangedUnderTheSameFileName() = runTest {
        api.catalogue = mapOf("Exact" to listOf(spotifyTrack("Exact")), "Replaced" to listOf(spotifyTrack("Replaced", uri = "spotify:track:new")))
        val matcher = SongMatcher(api, dao, clock)
        matcher.match(listOf(songTrack(1, title = "Exact"))) { _, _ -> }

        val replaced = songTrack(1, title = "Replaced")
        val second = matcher.match(listOf(replaced)) { _, _ -> }

        assertEquals(listOf("Exact", "Replaced"), api.searchedTitles)
        assertEquals(listOf("spotify:track:new"), second.matched.map { it.spotifyUri })
        assertEquals("Replaced", dao.matches.values.single().localTitle)
    }

    @Test
    fun aCachedMatchIsNotReusedWhenTheLengthOrArtistChanged() = runTest {
        api.catalogue = mapOf("Exact" to listOf(spotifyTrack("Exact")))
        val matcher = SongMatcher(api, dao, clock)
        val original = songTrack(1, title = "Exact")
        matcher.match(listOf(original)) { _, _ -> }

        matcher.match(listOf(original.copy(durationMillis = 90_000))) { _, _ -> }
        matcher.match(listOf(original.copy(artist = "Someone Else"))) { _, _ -> }

        assertEquals(3, api.searchedTitles.size)
    }

    @Test
    fun aConfirmedMatchIsNotReusedForADifferentRecording() = runTest {
        val matcher = SongMatcher(api, dao, clock)
        val original = songTrack(1, title = "Lost")
        matcher.confirm(UnsureSong(original, spotifyTrack("Lost - Live", uri = "spotify:track:live")))

        val report = matcher.match(listOf(original.copy(title = "Lost (Club Remix)"))) { _, _ -> }

        assertEquals(listOf("Lost (Club Remix)"), api.searchedTitles)
        assertTrue(report.matched.isEmpty())
    }

    @Test
    fun playlistsAreRememberedPerSpotifyAccount() = runTest {
        val publisher = PlaylistPublisher(api, dao)
        val first = publisher.publish(SendSource.TickedSongs, "Picks", listOf("spotify:track:a"))

        api.userId = "other-user"
        assertEquals(false, publisher.hasPlaylistFor(SendSource.TickedSongs, "Picks"))
        val second = publisher.publish(SendSource.TickedSongs, "Picks", listOf("spotify:track:a"))

        assertNotEquals(first.id, second.id)
        assertEquals(2, api.createdNames.size)
    }

    @Test
    fun playlistsSavedBeforeAccountsWereTrackedBelongToTheFirstAccountThatSends() = runTest {
        dao.playlists["" to "ticked:picks"] = SpotifyPlaylistEntry("", "ticked:picks", "old-playlist", "url")
        val publisher = PlaylistPublisher(api, dao)

        val reused = publisher.publish(SendSource.TickedSongs, "Picks", listOf("spotify:track:a"))

        assertEquals("old-playlist", reused.id)
        assertTrue(api.createdNames.isEmpty())
        assertEquals("test-user", dao.playlists.values.single().accountId)
    }
}
