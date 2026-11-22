package app.playsift.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotFoundSongsTest {
    @Test
    fun theShareTextHasOneTitleHyphenArtistLinePerSong() {
        val text = notFoundShareText(listOf(songTrack(1, title = "Blue Monday", artist = "New Order"), songTrack(2, title = "Temptation", artist = "New Order")))

        assertEquals("Blue Monday - New Order\nTemptation - New Order", text)
        assertFalse(text.contains(Char(EM_DASH_CODE)))
    }

    @Test
    fun theSearchLinksCarryTheEncodedTitleAndArtist() {
        val links = spotifySearchLinks(songTrack(1, title = "Rock & Roll", artist = "Led Zeppelin"))

        assertEquals("spotify:search:Rock%20%26%20Roll%20Led%20Zeppelin", links.appUri)
        assertEquals("https://open.spotify.com/search/Rock%20%26%20Roll%20Led%20Zeppelin", links.webUrl)
    }

    private companion object {
        const val EM_DASH_CODE = 8212
    }
}
