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

    @Test
    fun liveVersionOfAStudioTrackIsOnlyUnsure() {
        val live = songTrack(3, title = "Halo (Live at Wembley)", artist = "New Order", durationMillis = 450_000)

        val result = matchTrack(live, listOf(candidate(title = "Halo")))

        assertEquals(MatchResult.Unsure::class, result::class)
    }

    @Test
    fun studioTrackAgainstALiveResultIsOnlyUnsure() {
        val studio = songTrack(3, title = "Halo", artist = "New Order", durationMillis = 450_000)

        val result = matchTrack(studio, listOf(candidate(title = "Halo - Live")))

        assertEquals(MatchResult.Unsure::class, result::class)
    }

    @Test
    fun differentVersionWordsAreOnlyUnsure() {
        val remix = songTrack(3, title = "Halo (Remix)", artist = "New Order", durationMillis = 450_000)

        val result = matchTrack(remix, listOf(candidate(title = "Halo (Acoustic)")))

        assertEquals(MatchResult.Unsure::class, result::class)
    }

    @Test
    fun theSameVersionWordOnBothSidesStillMatches() {
        val live = songTrack(3, title = "Halo (Live)", artist = "New Order", durationMillis = 450_000)

        val result = matchTrack(live, listOf(candidate(title = "Halo [Live]")))

        assertEquals(MatchResult.Matched::class, result::class)
    }

    @Test
    fun remasteredNoteStillMatchesTheStudioTrack() {
        val remastered = songTrack(3, title = "Halo (Remastered)", artist = "New Order", durationMillis = 450_000)

        assertEquals(MatchResult.Matched::class, matchTrack(remastered, listOf(candidate(title = "Halo")))::class)
    }

    @Test
    fun featuredArtistNoteStillMatchesTheStudioTrack() {
        val featuring = songTrack(3, title = "Song (feat. A)", artist = "New Order", durationMillis = 450_000)

        assertEquals(MatchResult.Matched::class, matchTrack(featuring, listOf(candidate(title = "Song")))::class)
    }

    @Test
    fun explicitTagStillMatches() {
        val explicit = songTrack(3, title = "Song [Explicit]", artist = "New Order", durationMillis = 450_000)

        assertEquals(MatchResult.Matched::class, matchTrack(explicit, listOf(candidate(title = "Song")))::class)
    }
}
