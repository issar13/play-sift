package app.playsift.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.playsift.ui.theme.PlaySiftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtworkTileTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun artworkTileUsesInitials() {
        composeRule.setContent { PlaySiftTheme { ArtworkTile(label = "Blue Monday", colorSeed = "New Order") } }
        composeRule.onNodeWithText("BM").assertExists()
    }

    @Test
    fun initialsUseTheFirstTwoWords() {
        assertEquals("BM", artworkInitials("blue monday"))
        assertEquals("TL", artworkInitials("The Long Goodbye"))
        assertEquals("S", artworkInitials("Sandstorm"))
        assertEquals("1S", artworkInitials("(1) Song"))
        assertEquals("?", artworkInitials("   "))
    }

    @Test
    fun colourIsStablePerArtistAndIgnoresCaseAndSpaces() {
        assertEquals(artworkColor("New Order"), artworkColor("  new order "))
        assertEquals(artworkColor(""), artworkColor(""))
    }

    @Test
    fun differentArtistsSpreadAcrossThePalette() {
        val colours = listOf("New Order", "Daft Punk", "Bjork", "Kraftwerk", "Radiohead", "Portishead").map(::artworkColor).toSet()
        assertNotEquals(1, colours.size)
    }
}
