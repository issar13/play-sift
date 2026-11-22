package app.playsift.spotify

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.work.WorkManager
import app.playsift.BuildConfig
import app.playsift.files.MediaStoreFileSizes
import app.playsift.history.PlayHistoryDatabase
import app.playsift.history.mergeHistory
import app.playsift.history.roomPlayHistory
import app.playsift.player.LibraryList
import app.playsift.player.PlayerLibrary
import app.playsift.settings.settingsDataStore
import java.time.Clock
import java.time.Period
import okhttp3.OkHttpClient

/** The Spotify pieces, shared by the screen's view model and the background worker so both use one token session. */
class SpotifyComponents private constructor(private val context: Context) {
    private val clock = Clock.systemUTC()
    private val httpClient = OkHttpClient()
    val clientId = SpotifyClientId(context.settingsDataStore, BuildConfig.SPOTIFY_CLIENT_ID)
    val tokenStore = DataStoreTokenStore(context.settingsDataStore, KeystoreSecretCipher())
    val session = SpotifySession(tokenStore, OkHttpSpotifyAccounts(httpClient, clock), clientId::effective, clock)
    val api: SpotifyApi = OkHttpSpotifyApi(httpClient, session)
    private val dao = PlayHistoryDatabase.open(context).spotifyDao()
    val matcher = SongMatcher(api, dao, clock)
    val publisher = PlaylistPublisher(api, dao)
    val sendStore: SendStore = DataStoreSendStore(context.settingsDataStore)

    val sendServices: SendServices by lazy {
        val library = PlayerLibrary(context.contentResolver)
        val history = roomPlayHistory(context)
        val runner = SendRunner(matcher, publisher) {
            val playerTracks = library.tracks(LibraryList.AllTracks, Period.ZERO, clock.instant())
            mergeHistory(playerTracks, history.record(playerTracks))
        }
        SendServices(runner, sendStore, SendNotifications(context))
    }

    companion object {
        @Volatile
        private var instance: SpotifyComponents? = null

        fun of(context: Context): SpotifyComponents =
            instance ?: synchronized(this) { instance ?: SpotifyComponents(context.applicationContext).also { instance = it } }
    }
}

fun spotifyViewModelFactory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val components = SpotifyComponents.of(application)
        return SpotifyViewModel(
            session = components.session,
            tokenStore = components.tokenStore,
            clientId = components.clientId,
            api = components.api,
            matcher = components.matcher,
            publisher = components.publisher,
            fileSizes = MediaStoreFileSizes(application.contentResolver),
            store = components.sendStore,
            scheduler = WorkManagerSendScheduler(WorkManager.getInstance(application), components.sendStore),
            builtInClientId = BuildConfig.SPOTIFY_CLIENT_ID,
        ) as T
    }
}
