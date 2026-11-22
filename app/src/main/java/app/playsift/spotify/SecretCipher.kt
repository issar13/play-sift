package app.playsift.spotify

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretCipher {
    fun encrypt(plainText: String): String

    /** Throws [java.security.GeneralSecurityException] when the text was not made by this device's key. */
    fun decrypt(encodedText: String): String
}

/** The random IV and the encrypted bytes (with the GCM tag) that were stored together. */
class SecretEnvelope(val initializationVector: ByteArray, val encryptedBytes: ByteArray)

private const val ENVELOPE_IV_BYTES = 12
private const val ENVELOPE_TAG_BYTES = 16

/** Reads the stored text back into its parts; text that is not Base64 or is too short to hold an IV and a tag is a [GeneralSecurityException]. */
fun openEnvelope(encodedText: String): SecretEnvelope {
    val bytes = try {
        Base64.getDecoder().decode(encodedText)
    } catch (exception: IllegalArgumentException) {
        throw GeneralSecurityException("The stored secret is not valid Base64", exception)
    }
    if (bytes.size < ENVELOPE_IV_BYTES + ENVELOPE_TAG_BYTES) throw GeneralSecurityException("The stored secret is too short")
    return SecretEnvelope(bytes.copyOfRange(0, ENVELOPE_IV_BYTES), bytes.copyOfRange(ENVELOPE_IV_BYTES, bytes.size))
}

/** AES-GCM with a key that never leaves the Android Keystore; the output is Base64 of the random IV followed by the ciphertext. */
class KeystoreSecretCipher : SecretCipher {
    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plainText.toByteArray()))
    }

    override fun decrypt(encodedText: String): String {
        val envelope = openEnvelope(encodedText)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, envelope.initializationVector))
        }
        return String(cipher.doFinal(envelope.encryptedBytes))
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return (keyStore.getKey(KEY_ALIAS, null) as SecretKey?) ?: newKey()
    }

    private fun newKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(KEY_BITS)
                        .build(),
                )
            }
            .generateKey()

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "play_sift_spotify_tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val KEY_BITS = 256
    }
}
