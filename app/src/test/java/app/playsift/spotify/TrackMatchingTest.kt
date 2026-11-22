package app.playsift.spotify

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackMatchingTest {
    private val track = songTrack(1, title = "Blue Monday", artist = "New Order", durationMillis = 450_000)

    private fun candidate(
        title: String = "Blue Monday",
        artist: String = "New Order",
        durationMillis: Long = 450_000,
        uri: String = "spotify:track:$title",
    ) = SpotifyTrack(uri, title, listOf(artist), durationMillis)

    @Test
    fun normaliseIgnoresCaseAccentsBracketsAndPunctuation() {
        assertEquals("beyonce halo", normalise("Beyoncé - Halo"))
        assertEquals("blue monday", normalise("Blue Monday (2011 Remaster) [Live]"))
        assertEquals("rock n roll", normalise("Rock 'n' Roll!"))
    }

    @Test
    fun sameTitleArtistAndLengthIsMatched() {
        val result = matchTrack(track, listOf(candidate()))

        assertEquals(MatchResult.Matched(candidate()), result)
    }

    @Test
    fun bracketedRemasterNoteStillMatches() {
        val result = matchTrack(track, listOf(candidate(title = "Blue Monday (2015 Remaster)")))

        assertEquals(MatchResult.Matched(candidate(title = "Blue Monday (2015 Remaster)")), result)
    }

    @Test
    fun lengthWithinFiveSecondsIsMatchedAndSixIsNot() {
        assertEquals(MatchResult.Matched::class, matchTrack(track, listOf(candidate(durationMillis = 455_000)))::class)
        assertEquals(MatchResult.Unsure::class, matchTrack(track, listOf(candidate(durationMillis = 456_000)))::class)
    }

    @Test
    fun differentLengthWithSameTitleAndArtistIsUnsure() {
        val result = matchTrack(track, listOf(candidate(durationMillis = 300_000)))

        assertEquals(MatchResult.Unsure(candidate(durationMillis = 300_000)), result)
    }

    @Test
    fun differentArtistWithSameTitleAndLengthIsUnsure() {
        val result = matchTrack(track, listOf(candidate(artist = "Some Cover Band")))

        assertEquals(MatchResult.Unsure(candidate(artist = "Some Cover Band")), result)
    }

    @Test
    fun unrelatedSongIsNotFound() {
        assertEquals(MatchResult.NotFound, matchTrack(track, listOf(candidate(title = "Something Else", artist = "Other"))))
    }

    @Test
    fun noResultsIsNotFound() {
        assertEquals(MatchResult.NotFound, matchTrack(track, emptyList()))
    }

    @Test
    fun artistOnlyOrLengthOnlyIsNotEnough() {
        assertEquals(MatchResult.NotFound, matchTrack(track, listOf(candidate(title = "Other Song"))))
    }

    @Test
    fun theExactResultBeatsAnEarlierCloseOne() {
        val close = candidate(durationMillis = 300_000, uri = "spotify:track:close")
        val exact = candidate(uri = "spotify:track:exact")

        assertEquals(MatchResult.Matched(exact), matchTrack(track, listOf(close, exact)))
    }

    @Test
    fun oneOfSeveralCreditedArtistsIsEnough() {
        val collaboration = songTrack(2, title = "Song", artist = "Artist One feat. Artist Two", durationMillis = 100_000)

        val result = matchTrack(collaboration, listOf(SpotifyTrack("spotify:track:x", "Song", listOf("Artist Two"), 100_000)))

        assertEquals(MatchResult.Matched::class, result::class)
    }

    @Test
    fun unknownLocalLengthNeverCountsAsMatchingLength() {
        val result = matchTrack(track.copy(durationMillis = 0), listOf(candidate()))

        assertEquals(MatchResult.Unsure::class, result::class)
    }
}
