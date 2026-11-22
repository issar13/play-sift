package app.playsift.spotify

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

private class RecordingTokens : AccessTokens {
    val rejected = mutableListOf<String>()

    override suspend fun current() = "token-1"

    override suspend fun refreshedAfterRejecting(rejectedToken: String): String {
        rejected += rejectedToken
        return "token-2"
    }
}

/** Talks to a local MockWebServer only: no real network. */
class SpotifyApiTest {
    private val server = MockWebServer()
    private val tokens = RecordingTokens()
    private val pauses = mutableListOf<Long>()
    private lateinit var api: OkHttpSpotifyApi

    @Before
    fun setUp() {
        server.start()
        api = OkHttpSpotifyApi(OkHttpClient(), tokens, server.url("/v1/"), pause = { pauses += it })
    }

    @After
    fun tearDown() = server.close()

    private fun reply(code: Int, body: String = "{}", retryAfter: String? = null) =
        MockResponse.Builder().code(code).body(body).apply { retryAfter?.let { addHeader("Retry-After", it) } }.build()

    @Test
    fun searchSendsTheQueryAndReadsTheResults() = runTest {
        server.enqueue(reply(200, """{"tracks":{"items":[{"uri":"spotify:track:1","name":"Blue Monday","duration_ms":450000,"artists":[{"name":"New Order"}],"extra":1}]}}"""))

        val results = api.searchTracks("Blue \"Monday\"", "New Order")

        val request = server.takeRequest()
        assertEquals("Bearer token-1", request.headers["Authorization"])
        assertEquals("track:\"Blue Monday\" artist:\"New Order\"", request.url.queryParameter("q"))
        assertEquals("track", request.url.queryParameter("type"))
        assertEquals(listOf(SpotifyTrack("spotify:track:1", "Blue Monday", listOf("New Order"), 450_000)), results)
    }

    @Test
    fun optionSearchSendsTheQueryAsGivenAndReadsTheAlbum() = runTest {
        server.enqueue(reply(200, """{"tracks":{"items":[{"uri":"spotify:track:1","name":"Halo","duration_ms":261000,"artists":[{"name":"Beyonce"}],"album":{"name":"I Am"}}]}}"""))

        val results = api.searchOptions("Halo Beyonce")

        val request = server.takeRequest()
        assertEquals("Halo Beyonce", request.url.queryParameter("q"))
        assertEquals("8", request.url.queryParameter("limit"))
        assertEquals(listOf(SpotifyTrack("spotify:track:1", "Halo", listOf("Beyonce"), 261_000, album = "I Am")), results)
        assertEquals("1", results.single().id)
    }

    @Test
    fun optionSearchRefreshesTheTokenAfterA401() = runTest {
        server.enqueue(reply(401))
        server.enqueue(reply(200, """{"tracks":{"items":[]}}"""))

        assertEquals(emptyList<SpotifyTrack>(), api.searchOptions("Halo"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aTooManyRequestsAnswerWaitsForRetryAfterThenSucceeds() = runTest {
        server.enqueue(reply(429, retryAfter = "3"))
        server.enqueue(reply(200, """{"tracks":{"items":[]}}"""))

        val results = api.searchTracks("A", "B")

        assertEquals(emptyList<SpotifyTrack>(), results)
        assertEquals(listOf(3_000L, 250L), pauses)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun repeatedRateLimitingEventuallyFails() = runTest {
        repeat(11) { server.enqueue(reply(429, retryAfter = "1")) }

        val failure = failureOf<SpotifyHttpException> { api.searchTracks("A", "B") }

        assertEquals(429, failure.status)
        assertEquals(10, pauses.count { it == 1_000L })
    }

    @Test
    fun aFiveMinuteRetryAfterIsWaitedOutAndTheWorkerIsToldTheSeconds() = runTest {
        server.enqueue(reply(429, retryAfter = "300"))
        server.enqueue(reply(200, """{"tracks":{"items":[]}}"""))
        val heard = mutableListOf<Long>()

        withContext(RateLimitWaitHook { seconds -> heard += seconds }) { api.searchTracks("A", "B") }

        assertEquals(listOf(300L), heard)
        assertEquals(listOf(300_000L, 250L), pauses)
    }

    @Test
    fun aRetryAfterPastTenMinutesIsNotWaitedOutAndIsOnTheException() = runTest {
        server.enqueue(reply(429, retryAfter = "601"))

        val failure = failureOf<SpotifyHttpException> { api.searchTracks("A", "B") }

        assertEquals(601L, failure.retryAfterSeconds)
        assertEquals(emptyList<Long>(), pauses)
    }

    @Test
    fun optionSearchKeepsTheShortCap() = runTest {
        server.enqueue(reply(429, retryAfter = "300"))

        val failure = failureOf<SpotifyHttpException> { api.searchOptions("Halo") }

        assertEquals(300L, failure.retryAfterSeconds)
        assertEquals(emptyList<Long>(), pauses)
    }

    @Test
    fun aShortPauseSeparatesConsecutiveRequests() = runTest {
        server.enqueue(reply(200, """{"tracks":{"items":[]}}"""))
        server.enqueue(reply(200, """{"tracks":{"items":[]}}"""))

        api.searchTracks("A", "B")
        assertEquals(emptyList<Long>(), pauses)
        api.searchTracks("C", "D")

        assertEquals(listOf(250L), pauses)
    }

    @Test
    fun anUnauthorizedAnswerRefreshesTheTokenOnceAndRetries() = runTest {
        server.enqueue(reply(401))
        server.enqueue(reply(200, """{"id":"user-1","display_name":"Issa"}"""))

        val name = api.currentUserName()

        assertEquals("Issa", name)
        assertEquals(listOf("token-1"), tokens.rejected)
        server.takeRequest()
        assertEquals("Bearer token-2", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun theUserIdIsAskedForOncePerAccessToken() = runTest {
        server.enqueue(reply(200, """{"id":"user-1","display_name":"Issa"}"""))

        assertEquals("user-1", api.currentUserId())
        assertEquals("user-1", api.currentUserId())

        assertEquals(1, server.requestCount)
    }

    @Test
    fun aSecondUnauthorizedAnswerIsAnError() = runTest {
        server.enqueue(reply(401))
        server.enqueue(reply(401))

        val failure = failureOf<SpotifyHttpException> { api.currentUserName() }

        assertEquals(401, failure.status)
        assertEquals(1, tokens.rejected.size)
    }

    @Test
    fun createPlaylistPostsAPrivatePlaylist() = runTest {
        server.enqueue(reply(201, """{"id":"abc","external_urls":{"spotify":"https://open.spotify.com/playlist/abc"}}"""))

        val playlist = api.createPlaylist("Play Sift top 100")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/me/playlists", request.url.encodedPath)
        assertEquals(true, request.body!!.utf8().contains("\"public\":false"))
        assertEquals(SpotifyPlaylist("abc", "https://open.spotify.com/playlist/abc"), playlist)
    }

    @Test
    fun replaceUsesPutAndAddUsesPostOnTheItemsPath() = runTest {
        server.enqueue(reply(200))
        server.enqueue(reply(201))

        api.replaceItems("abc", listOf("spotify:track:1"))
        api.addItems("abc", listOf("spotify:track:2"))

        val replace = server.takeRequest()
        val add = server.takeRequest()
        assertEquals("PUT", replace.method)
        assertEquals("POST", add.method)
        assertEquals("/v1/playlists/abc/items", replace.url.encodedPath)
        assertEquals("""{"uris":["spotify:track:1"]}""", replace.body!!.utf8())
        assertEquals("""{"uris":["spotify:track:2"]}""", add.body!!.utf8())
    }

    @Test
    fun otherErrorsCarryTheStatus() = runTest {
        server.enqueue(reply(404))

        val failure = failureOf<SpotifyHttpException> { api.replaceItems("gone", emptyList()) }

        assertEquals(404, failure.status)
    }
}

class SpotifyAccountsTest {
    private val server = MockWebServer()
    private val clock = Clock.fixed(Instant.parse("2026-11-22T00:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun accounts() = OkHttpSpotifyAccounts(OkHttpClient(), clock, server.url("/"))

    @Test
    fun exchangeSendsTheCodeAndTheVerifier() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"access_token":"a","refresh_token":"r","expires_in":3600,"token_type":"Bearer"}""").build())

        val tokens = accounts().exchangeCode("client-1", "the-code", "the-verifier")

        val body = server.takeRequest().body!!.utf8()
        assertEquals(true, body.contains("grant_type=authorization_code"))
        assertEquals(true, body.contains("code=the-code"))
        assertEquals(true, body.contains("code_verifier=the-verifier"))
        assertEquals(true, body.contains("client_id=client-1"))
        assertEquals(SpotifyTokens("a", "r", Instant.parse("2026-11-22T01:00:00Z")), tokens)
    }

    @Test
    fun refreshKeepsTheOldRefreshTokenWhenNoneIsSent() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"access_token":"b","expires_in":3600}""").build())

        val tokens = accounts().refresh("client-1", "old-refresh")

        assertEquals("old-refresh", tokens.refreshToken)
        assertEquals("b", tokens.accessToken)
    }

    @Test
    fun aRejectedExchangeIsAnAuthError() = runTest {
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":"invalid_grant"}""").build())

        failureOf<SpotifyAuthException> { accounts().exchangeCode("c", "x", "y") }
    }

    @Test
    fun authorizationUrlCarriesTheChallengeScopesAndRedirect() {
        val url = authorizationUrl("client-1", "challenge-1", "state-1").toHttpUrl()

        assertEquals("accounts.spotify.com", url.host)
        assertEquals("/authorize", url.encodedPath)
        assertEquals("client-1", url.queryParameter("client_id"))
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("challenge-1", url.queryParameter("code_challenge"))
        assertEquals("state-1", url.queryParameter("state"))
        assertEquals("playsift://spotify-callback", url.queryParameter("redirect_uri"))
        assertEquals(SPOTIFY_SCOPES, url.queryParameter("scope"))
    }
}
