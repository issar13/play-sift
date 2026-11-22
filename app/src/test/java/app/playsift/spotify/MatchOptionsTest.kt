package app.playsift.spotify

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatchOptionsTest {
    private val api = FakeSpotifyApi()
    private val search = OptionSearch(api)
    private val song = songTrack(1, title = "Halo (Remastered) feat. Someone", artist = "Beyonce")

    private fun tracks(vararg names: String) = names.map { spotifyTrack(it) }

    @Test
    fun theThreeQueriesAreStrictThenLooseThenTitleOnly() {
        assertEquals(
            listOf("track:\"Halo (Remastered) feat. Someone\" artist:\"Beyonce\"", "Halo Beyonce", "Halo"),
            optionQueries(song),
        )
    }

    @Test
    fun aTitleThatIsAlreadyCleanDoesNotRepeatAQuery() {
        assertEquals(3, optionQueries(songTrack(2, title = "Halo", artist = "Beyonce")).size)
        assertEquals(listOf("track:\"Halo\" artist:\"\"", "Halo"), optionQueries(songTrack(3, title = "Halo", artist = "")))
    }

    @Test
    fun theResultsOfAllThreeQueriesAreMergedWithoutDuplicates() = runTest {
        val queries = optionQueries(song)
        api.optionResults = mapOf(
            queries[0] to tracks("One", "Two"),
            queries[1] to tracks("Two", "Three"),
            queries[2] to tracks("Three", "Four"),
        )

        val options = search.optionsFor(song)

        assertEquals(listOf("One", "Two", "Three", "Four"), options.map { it.title })
        assertEquals(queries, api.optionQueriesAsked)
    }

    @Test
    fun theSameSpotifyIdUnderADifferentTitleCountsOnce() = runTest {
        val queries = optionQueries(song)
        api.optionResults = mapOf(queries[0] to listOf(spotifyTrack("Halo", uri = "spotify:track:abc")), queries[1] to listOf(spotifyTrack("Halo - Single", uri = "spotify:track:abc")))

        assertEquals(listOf("Halo"), search.optionsFor(song).map { it.title })
    }

    @Test
    fun searchingStopsOnceThereAreEightOptions() = runTest {
        val queries = optionQueries(song)
        api.optionResults = mapOf(
            queries[0] to tracks("A", "B", "C", "D", "E"),
            queries[1] to tracks("F", "G", "H", "I", "J"),
            queries[2] to tracks("K"),
        )

        val options = search.optionsFor(song)

        assertEquals(listOf("A", "B", "C", "D", "E", "F", "G", "H"), options.map { it.title })
        assertEquals(queries.take(2), api.optionQueriesAsked)
    }

    @Test
    fun aFullFirstQuerySkipsTheOthers() = runTest {
        val queries = optionQueries(song)
        api.optionResults = mapOf(queries[0] to tracks("A", "B", "C", "D", "E", "F", "G", "H"))

        search.optionsFor(song)

        assertEquals(queries.take(1), api.optionQueriesAsked)
    }

    @Test
    fun lengthsShowAsMinutesAndSeconds() {
        assertEquals("1:12", lengthText(72_000))
        assertEquals("0:05", lengthText(5_000))
        assertEquals("3:20", lengthText(200_000))
    }

    @Test
    fun theLengthHintAppearsOnlyBeyondFiveSeconds() {
        assertEquals("1:12 longer", lengthHint(localMillis = 200_000, optionMillis = 272_000))
        assertEquals("0:20 shorter", lengthHint(localMillis = 200_000, optionMillis = 180_000))
        assertNull(lengthHint(localMillis = 200_000, optionMillis = 205_000))
        assertNull(lengthHint(localMillis = 0, optionMillis = 205_000))
    }
}
