package app.playsift.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    @Test
    fun challengeMatchesTheRfc7636Example() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", codeChallengeFor(verifier))
    }

    @Test
    fun verifierIsLongEnoughAndUrlSafe() {
        val verifier = newCodeVerifier()

        assertTrue(verifier.length in 43..128)
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun everyVerifierIsDifferent() {
        assertNotEquals(newCodeVerifier(), newCodeVerifier())
    }

    @Test
    fun challengeHasNoPadding() {
        assertTrue('=' !in codeChallengeFor(newCodeVerifier()))
    }
}
