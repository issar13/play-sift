package app.playsift.spotify

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private const val VERIFIER_BYTE_COUNT = 64

private val urlSafeEncoder = Base64.getUrlEncoder().withoutPadding()

/** 64 random bytes encode to 86 URL-safe characters, inside the 43 to 128 that PKCE allows. */
fun newCodeVerifier(random: SecureRandom = SecureRandom()): String =
    urlSafeEncoder.encodeToString(ByteArray(VERIFIER_BYTE_COUNT).also(random::nextBytes))

/** The S256 challenge: the SHA-256 of the verifier, URL-safe Base64 without padding. */
fun codeChallengeFor(verifier: String): String =
    urlSafeEncoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

/** A random value that ties the callback to the sign-in that was started. */
fun newAuthorizationState(random: SecureRandom = SecureRandom()): String =
    urlSafeEncoder.encodeToString(ByteArray(STATE_BYTE_COUNT).also(random::nextBytes))

private const val STATE_BYTE_COUNT = 16
