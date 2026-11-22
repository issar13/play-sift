package app.playsift.spotify

import java.security.GeneralSecurityException
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SecretCipherTest {
    @Test
    fun textThatIsNotBase64IsADecryptionFailure() {
        assertThrows(GeneralSecurityException::class.java) { openEnvelope("!") }
    }

    @Test
    fun aTruncatedEnvelopeIsADecryptionFailure() {
        val tooShort = Base64.getEncoder().encodeToString(ByteArray(20))

        assertThrows(GeneralSecurityException::class.java) { openEnvelope(tooShort) }
    }

    @Test
    fun aValidEnvelopeIsSplitIntoItsIvAndEncryptedBytes() {
        val bytes = ByteArray(12) { 1 } + ByteArray(30) { 2 }

        val envelope = openEnvelope(Base64.getEncoder().encodeToString(bytes))

        assertEquals(12, envelope.initializationVector.size)
        assertEquals(30, envelope.encryptedBytes.size)
    }
}
