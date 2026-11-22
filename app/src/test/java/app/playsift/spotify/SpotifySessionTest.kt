package app.playsift.spotify

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.GeneralSecurityException

class InMemoryTokenStore(var stored: SpotifyTokens? = null, var pending: PendingAuthorization? = null) : TokenStore {
    override suspend fun tokens() = stored

    override suspend fun saveTokens(tokens: SpotifyTokens) {
        stored = tokens
    }

    override suspend fun pendingAuthorization() = pending

    override suspend fun savePendingAuthorization(pending: PendingAuthorization) {
        this.pending = pending
    }

    override suspend fun clear() {
        stored = null
        pending = null
    }
}

class FakeAccounts : SpotifyAccounts {
    var refreshCount = 0
    /** When set, a refresh waits here after it started, like a slow network call. */
    var refreshGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var exchangedWith: Triple<String, String, String>? = null

    override suspend fun exchangeCode(clientId: String, code: String, verifier: String): SpotifyTokens {
        exchangedWith = Triple(clientId, code, verifier)
        return SpotifyTokens("access-from-code", "refresh-1", Instant.parse("2026-11-22T01:00:00Z"))
    }

    override suspend fun refresh(clientId: String, refreshToken: String): SpotifyTokens {
        refreshCount += 1
        refreshGate?.await()
        return SpotifyTokens("access-refreshed-$refreshCount", refreshToken, Instant.parse("2026-11-22T02:00:00Z"))
    }
}

class SpotifySessionTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-22T00:00:00Z"), ZoneOffset.UTC)
    private val accounts = FakeAccounts()

    private fun session(store: TokenStore) = SpotifySession(store, accounts, { "client-1" }, clock)

    @Test
    fun aFreshTokenIsReturnedWithoutRefreshing() = runTest {
        val store = InMemoryTokenStore(SpotifyTokens("fresh", "refresh", Instant.parse("2026-11-22T01:00:00Z")))

        assertEquals("fresh", session(store).current())
        assertEquals(0, accounts.refreshCount)
    }

    @Test
    fun aTokenAboutToExpireIsRefreshedAndSaved() = runTest {
        val store = InMemoryTokenStore(SpotifyTokens("old", "refresh", Instant.parse("2026-11-22T00:00:30Z")))

        assertEquals("access-refreshed-1", session(store).current())
        assertEquals("access-refreshed-1", store.stored!!.accessToken)
    }

    @Test
    fun aRejectedTokenIsRefreshedOnce() = runTest {
        val store = InMemoryTokenStore(SpotifyTokens("rejected", "refresh", Instant.parse("2026-11-22T01:00:00Z")))
        val session = session(store)

        val first = session.refreshedAfterRejecting("rejected")
        val second = session.refreshedAfterRejecting("rejected")

        assertEquals(first, second)
        assertEquals(1, accounts.refreshCount)
    }

    @Test
    fun completingSignInStoresTheTokens() = runTest {
        val store = InMemoryTokenStore()
        val session = session(store)

        session.completeSignIn("the-code", "the-verifier")

        assertEquals(Triple("client-1", "the-code", "the-verifier"), accounts.exchangedWith)
        assertEquals(true, session.isSignedIn())
    }

    @Test
    fun signingOutWaitsForARefreshThatIsAlreadyRunning() = runTest {
        val store = InMemoryTokenStore(SpotifyTokens("rejected", "refresh", Instant.parse("2026-11-22T01:00:00Z")))
        val session = session(store)
        accounts.refreshGate = CompletableDeferred()

        val refresh = async { session.refreshedAfterRejecting("rejected") }
        runCurrent()
        val signOut = async { session.signOut() }
        runCurrent()
        accounts.refreshGate!!.complete(Unit)
        listOf(refresh, signOut).awaitAll()

        assertNull(store.stored)
    }

    @Test
    fun aRefreshThatStartedBeforeSignInCannotOverwriteTheNewTokens() = runTest {
        val store = InMemoryTokenStore(SpotifyTokens("rejected", "refresh", Instant.parse("2026-11-22T01:00:00Z")))
        val session = session(store)
        accounts.refreshGate = CompletableDeferred()

        val refresh = async { session.refreshedAfterRejecting("rejected") }
        runCurrent()
        val signIn = async { session.completeSignIn("the-code", "the-verifier") }
        runCurrent()
        accounts.refreshGate!!.complete(Unit)
        listOf(refresh, signIn).awaitAll()

        assertEquals("access-from-code", store.stored!!.accessToken)
    }
}
/** Reversible stand-in for the Keystore cipher, which needs a device. */
private class ReverseCipher : SecretCipher {
    var corrupted = false

    override fun encrypt(plainText: String) = plainText.reversed()

    override fun decrypt(encodedText: String): String {
        if (corrupted) throw GeneralSecurityException("key lost")
        return encodedText.reversed()
    }
}

class DataStoreTokenStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO)
    private val cipher = ReverseCipher()

    @After
    fun tearDown() = scope.cancel()

    private fun store(): DataStoreTokenStore {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "store.preferences_pb") }
        return DataStoreTokenStore(dataStore, cipher)
    }

    @Test
    fun tokensRoundTripAndAreNotStoredInPlainText() = runTest {
        val store = store()
        val tokens = SpotifyTokens("secret-access", "secret-refresh", Instant.parse("2026-11-22T01:00:00Z"))

        store.saveTokens(tokens)

        assertEquals(tokens, store.tokens())
        val fileText = folder.root.listFiles()!!.joinToString { it.readText(Charsets.ISO_8859_1) }
        assertEquals(false, fileText.contains("secret-access"))
    }

    @Test
    fun pendingAuthorizationRoundTrips() = runTest {
        val store = store()

        store.savePendingAuthorization(PendingAuthorization("verifier", "state"))

        assertEquals(PendingAuthorization("verifier", "state"), store.pendingAuthorization())
    }

    @Test
    fun clearRemovesEverything() = runTest {
        val store = store()
        store.saveTokens(SpotifyTokens("a", "r", Instant.parse("2026-11-22T01:00:00Z")))
        store.savePendingAuthorization(PendingAuthorization("verifier", "state"))

        store.clear()

        assertNull(store.tokens())
        assertNull(store.pendingAuthorization())
    }

    @Test
    fun aLostKeyCountsAsSignedOut() = runTest {
        val store = store()
        store.saveTokens(SpotifyTokens("a", "r", Instant.parse("2026-11-22T01:00:00Z")))
        cipher.corrupted = true

        assertNull(store.tokens())
    }
}
