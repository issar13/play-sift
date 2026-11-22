package app.playsift.spotify

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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

fun spotifyViewModelFactory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val clock = Clock.systemUTC()
        val httpClient = OkHttpClient()
        val clientId = SpotifyClientId(application.settingsDataStore, BuildConfig.SPOTIFY_CLIENT_ID)
        val tokenStore = DataStoreTokenStore(application.settingsDataStore, KeystoreSecretCipher())
        val session = SpotifySession(tokenStore, OkHttpSpotifyAccounts(httpClient, clock), clientId::effective, clock)
        val api = OkHttpSpotifyApi(httpClient, session)
        val dao = PlayHistoryDatabase.open(application).spotifyDao()
        val library = PlayerLibrary(application.contentResolver)
        val history = roomPlayHistory(application)
        return SpotifyViewModel(
            session = session,
            tokenStore = tokenStore,
            clientId = clientId,
            api = api,
            matcher = SongMatcher(api, dao, clock),
            publisher = PlaylistPublisher(api, dao),
            fileSizes = MediaStoreFileSizes(application.contentResolver),
            loadMergedTracks = {
                val playerTracks = library.tracks(LibraryList.AllTracks, Period.ZERO, clock.instant())
                mergeHistory(playerTracks, history.record(playerTracks))
            },
            builtInClientId = BuildConfig.SPOTIFY_CLIENT_ID,
        ) as T
    }
}
