package app.playsift

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import app.playsift.history.HistorySnapshotWorker
import app.playsift.ui.LibraryViewModel
import app.playsift.ui.PlaySiftApp
import app.playsift.ui.theme.PlaySiftTheme

class MainActivity : ComponentActivity() {
    private val viewModel: LibraryViewModel by viewModels { LibraryViewModel.factory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HistorySnapshotWorker.schedule(applicationContext)
        setContent {
            PlaySiftTheme {
                PlaySiftApp(viewModel)
            }
        }
    }
}
