package app.playsift

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import app.playsift.history.HistorySnapshotWorker
import app.playsift.spotify.SpotifyViewModel
import app.playsift.spotify.spotifyViewModelFactory
import app.playsift.ui.LibraryViewModel
import app.playsift.ui.PlaySiftApp
import app.playsift.ui.Screen
import app.playsift.ui.theme.PlaySiftTheme

class MainActivity : ComponentActivity() {
    private val viewModel: LibraryViewModel by viewModels { LibraryViewModel.factory(application) }
    private val spotifyViewModel: SpotifyViewModel by viewModels { spotifyViewModelFactory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HistorySnapshotWorker.schedule(applicationContext)
        // A recreated activity gets the launch intent again; the sign-in callback must only be handled once.
        if (savedInstanceState == null) handleSpotifyCallback(intent)
        setContent {
            PlaySiftTheme {
                PlaySiftApp(viewModel, spotifyViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSpotifyCallback(intent)
    }

    /** Spotify sends the browser back to `playsift://spotify-callback?code=...&state=...`. */
    private fun handleSpotifyCallback(callbackIntent: Intent) {
        val callbackUri = callbackIntent.data?.takeIf { it.scheme == "playsift" && it.host == "spotify-callback" } ?: return
        spotifyViewModel.onAuthCallback(
            code = callbackUri.getQueryParameter("code"),
            state = callbackUri.getQueryParameter("state"),
            error = callbackUri.getQueryParameter("error"),
        )
        viewModel.showScreen(Screen.Spotify)
    }
}
