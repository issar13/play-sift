package app.playsift.spotify

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendRunnerTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-22T00:00:00Z"), ZoneOffset.UTC)
    private val api = FakeSpotifyApi()
    private val dao = FakeSpotifyDao()
    private var mergedTrackLoads = 0
    private val runner = SendRunner(SongMatcher(api, dao, clock), PlaylistPublisher(api, dao)) {
        mergedTrackLoads += 1
        listOf(songTrack(1, title = "One", playCount = 30), songTrack(2, title = "Two", playCount = 20), songTrack(3, title = "Three", playCount = 10))
    }
    private val progress = mutableListOf<Pair<Int, Int>>()
    private var publishingCount = 0

    private suspend fun run(request: SendRequest) = runner.run(request, { done, total -> progress += done to total }, { publishingCount += 1 })

    @Test
    fun reportsProgressForEverySongThenPublishes() = runTest {
        api.catalogue = mapOf("One" to listOf(spotifyTrack("One")), "Two" to listOf(spotifyTrack("Two")))

        val saved = run(sendRequest(SourceChoice.TopPlayed, topCount = 3, playlistName = "My list"))

        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
        assertEquals(1, publishingCount)
        assertEquals(listOf("My list"), api.createdNames)
        assertEquals(listOf(1L, 2L), saved.result.added.map { it.track.id })
        assertEquals(listOf(3L), saved.result.notFound.map { it.id })
        assertNull(saved.failureMessage)
    }

    @Test
    fun unsureSongsAreSavedForLaterAndNotPutInThePlaylist() = runTest {
        api.catalogue = mapOf("One" to listOf(spotifyTrack("One")), "Two" to listOf(spotifyTrack("Two", durationMillis = 90_000)))

        val saved = run(sendRequest(SourceChoice.EverythingPlayed))

        assertEquals(listOf(2L), saved.unsure.map { it.track.id })
        assertEquals(listOf("spotify:track:One"), api.replaceCalls.single().second)
        assertTrue(saved.result.skippedUnsure.isEmpty())
    }

    @Test
    fun onlyUnsureMatchesMakeNoPlaylistYet() = runTest {
        api.catalogue = mapOf("Picked" to listOf(spotifyTrack("Picked", durationMillis = 90_000)))

        val saved = run(sendRequest(tickedTracks = listOf(songTrack(9, title = "Picked"))))

        assertNull(saved.result.playlist)
        assertEquals(1, saved.unsure.size)
        assertTrue(api.createdNames.isEmpty())
    }

    @Test
    fun tickedSongsAreSentWithoutReadingTheWholeLibrary() = runTest {
        api.catalogue = mapOf("Picked" to listOf(spotifyTrack("Picked")))

        run(sendRequest(tickedTracks = listOf(songTrack(9, title = "Picked"))))

        assertEquals(0, mergedTrackLoads)
    }

    @Test
    fun aBlankPlaylistNameFallsBackToTheDefault() = runTest {
        api.catalogue = mapOf("Picked" to listOf(spotifyTrack("Picked")))

        run(sendRequest(playlistName = "   ", tickedTracks = listOf(songTrack(9, title = "Picked"))))

        assertEquals(listOf("Play Sift picks"), api.createdNames)
    }

    @Test
    fun whenNothingMatchesNoPlaylistIsMadeAndTheNotFoundSongsAreKept() = runTest {
        val nowhere = songTrack(9, title = "Nowhere")

        val saved = run(sendRequest(tickedTracks = listOf(nowhere)))

        assertEquals(null, saved.failureMessage)
        assertEquals(null, saved.result.playlist)
        assertEquals(listOf(nowhere), saved.result.notFound)
        assertTrue(api.createdNames.isEmpty())
    }

    @Test
    fun aSendWithNoSongsAtAllFailsWithTheNoneMatchedMessage() = runTest {
        val saved = run(sendRequest(tickedTracks = emptyList()))

        assertEquals(NONE_MATCHED_MESSAGE, saved.failureMessage)
        assertTrue(api.createdNames.isEmpty())
    }

    @Test
    fun aForbiddenAnswerExplainsTheDashboardRequirement() = runTest {
        api.failure = SpotifyHttpException(403, "forbidden")

        val saved = run(sendRequest(tickedTracks = listOf(songTrack(9))))

        assertTrue(saved.failureMessage!!.contains("developer dashboard"))
    }

    @Test
    fun cancellingKeepsTheMatchesFoundSoFar() = runTest {
        api.catalogue = mapOf("One" to listOf(spotifyTrack("One")), "Two" to listOf(spotifyTrack("Two")), "Three" to listOf(spotifyTrack("Three")))
        val cancelled = failureOf<CancellationException> {
            runner.run(sendRequest(SourceChoice.EverythingPlayed), { done, _ -> if (done == 2) throw CancellationException("stop") }, {})
        }

        assertNotNull(cancelled)
        assertEquals(setOf("1.mp3", "2.mp3"), dao.matches.keys.map { it.second }.toSet())
        assertTrue(api.createdNames.isEmpty())
    }
}
