package app.playsift.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.playsift.R
import app.playsift.player.PlayerTrack
import app.playsift.spotify.SpotifyViewModel
import app.playsift.files.trashRequest
import app.playsift.player.AccessState
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppTab(val label: String, val iconResource: Int) {
    Home("Home", R.drawable.ic_tab_home),
    Library("Library", R.drawable.ic_tab_library),
    Spotify("Spotify", R.drawable.ic_tab_spotify),
    Settings("Settings", R.drawable.ic_tab_settings),
}

private fun Screen.tab() = when (this) {
    Screen.Home -> AppTab.Home
    Screen.MostPlayed, Screen.NeverPlayed, Screen.Delete, Screen.Large -> AppTab.Library
    Screen.Spotify -> AppTab.Spotify
    Screen.Settings -> AppTab.Settings
}

private const val SPOTIFY_PACKAGE_NAME = "com.spotify.music"

private val mediaPermission =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaySiftApp(viewModel: LibraryViewModel, spotifyViewModel: SpotifyViewModel) {
    val state by viewModel.state.collectAsState()
    val spotifyState by spotifyViewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val trashFlow by viewModel.trashFlow.collectAsState()

    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onBatchResult(resultOk = result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(trashFlow) {
        viewModel.takeBatchToLaunch()?.let { batch ->
            try {
                trashLauncher.launch(IntentSenderRequest.Builder(trashRequest(context.contentResolver, batch).intentSender).build())
            } catch (_: Exception) {
                viewModel.onBatchResult(resultOk = false)
            }
        }
    }

    val importHistoryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { backupUri ->
        backupUri?.let {
            viewModel.importHistory {
                withContext(Dispatchers.IO) { context.contentResolver.openInputStream(backupUri)!!.bufferedReader().use { it.readText() } }
            }
        }
    }

    val pickHistoryFile = { importHistoryLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }

    val message by viewModel.message.collectAsState()
    LaunchedEffect(message) {
        message?.let { shownMessage ->
            snackbarHostState.showSnackbar(shownMessage.text)
            viewModel.messageShown()
        }
    }

    var isFileAccessBlocked by rememberSaveable { mutableStateOf(false) }
    val openAppSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
    }
    val isPreparingTrash by viewModel.isPreparingTrash.collectAsState()
    var selectionAtPermissionRequest by rememberSaveable { mutableStateOf(longArrayOf()) }
    var trashCandidates by remember { mutableStateOf<List<PlayerTrack>?>(null) }
    val prepareTrash = { selectedIds: Set<Long> ->
        scope.launch { viewModel.prepareTrash(selectedIds, trashCandidates ?: state.tracks) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        viewModel.setFileAccess(isGranted)
        isFileAccessBlocked = !isGranted && !ActivityCompat.shouldShowRequestPermissionRationale(context.findActivity(), mediaPermission)
        if (isGranted) {
            prepareTrash(selectionAtPermissionRequest.toSet())
        } else {
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = "Play Sift needs access to your music files to move them to the trash.",
                    actionLabel = if (isFileAccessBlocked) "Open settings" else null,
                )
                if (result == SnackbarResult.ActionPerformed) openAppSettings()
            }
        }
    }

    val moveToTrash: (Set<Long>, List<PlayerTrack>?) -> Unit = { selectionNow, candidates ->
        trashCandidates = candidates
        val hasPermission =
            ContextCompat.checkSelfPermission(context, mediaPermission) == PackageManager.PERMISSION_GRANTED
        when {
            hasPermission -> prepareTrash(selectionNow)
            isFileAccessBlocked -> scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = "Play Sift needs access to your music files to move them to the trash.",
                    actionLabel = "Open settings",
                )
                if (result == SnackbarResult.ActionPerformed) openAppSettings()
            }
            else -> {
                selectionAtPermissionRequest = selectionNow.toLongArray()
                permissionLauncher.launch(mediaPermission)
            }
        }
    }
    val moveSelectionToTrash: () -> Unit = { moveToTrash(state.selectedIds, null) }
    var libraryChip by rememberSaveable { mutableStateOf(Screen.MostPlayed) }
    val openLibraryChip: (Screen) -> Unit = { chipScreen ->
        libraryChip = chipScreen
        viewModel.showScreen(chipScreen)
    }
    val openTab: (AppTab) -> Unit = { tab ->
        when (tab) {
            AppTab.Home -> viewModel.showScreen(Screen.Home)
            AppTab.Library -> viewModel.showScreen(libraryChip)
            AppTab.Spotify -> viewModel.showScreen(Screen.Spotify)
            AppTab.Settings -> viewModel.showScreen(Screen.Settings)
        }
    }
    val sendSelectionToSpotify: () -> Unit = {
        spotifyViewModel.useTickedSongs(state.tracks.filter { it.id in state.selectedIds })
        viewModel.showScreen(Screen.Spotify)
    }
    val openPlaylist: (String) -> Unit = { url ->
        val spotifyIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(SPOTIFY_PACKAGE_NAME)
        try {
            context.startActivity(spotifyIntent)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
    val openSignInPage: (String) -> Unit = { url -> CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url)) }
    val spotifyActions = SpotifyActions(
        onSignIn = { spotifyViewModel.signIn(openSignInPage) },
        onSignOut = spotifyViewModel::signOut,
        onClientIdChange = spotifyViewModel::setClientIdOverride,
        onChooseSource = spotifyViewModel::chooseSource,
        onTopCountChange = spotifyViewModel::setTopCount,
        onPlaylistNameChange = spotifyViewModel::setPlaylistName,
        onSend = spotifyViewModel::send,
        onConfirmUnsure = spotifyViewModel::confirmUnsure,
        onSkipUnsure = spotifyViewModel::skipUnsure,
        onOpenPlaylist = openPlaylist,
        onToggleOffered = spotifyViewModel::toggleOffered,
        onSelectAllOffered = spotifyViewModel::selectAllOffered,
        onMoveToTrash = { selectedSongs -> moveToTrash(selectedSongs.map { it.track.id }.toSet(), selectedSongs.map { it.track }) },
        onBackToSetup = spotifyViewModel::backToSetup,
    )

    val fileAccessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        viewModel.setFileAccess(isGranted)
        isFileAccessBlocked = !isGranted && !ActivityCompat.shouldShowRequestPermissionRationale(context.findActivity(), mediaPermission)
    }
    var hasAskedForFileAccess by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, mediaPermission) == PackageManager.PERMISSION_GRANTED) {
            viewModel.setFileAccess(true)
        } else if (!hasAskedForFileAccess) {
            hasAskedForFileAccess = true
            fileAccessLauncher.launch(mediaPermission)
        }
    }

    BackHandler(enabled = state.screen != Screen.Home) { viewModel.showScreen(Screen.Home) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onResume()
        val hasFileAccess = ContextCompat.checkSelfPermission(context, mediaPermission) == PackageManager.PERMISSION_GRANTED
        viewModel.setFileAccess(hasFileAccess)
    }

    (trashFlow as? TrashFlow.Done)?.let { done ->
        TrashDoneScreen(
            movedCount = done.movedCount,
            attemptedCount = done.attemptedCount,
            onOpenPlayer = viewModel::openPlayer,
            onFinished = viewModel::finishTrash,
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Play Sift") },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(painter = painterResource(R.drawable.ic_refresh), contentDescription = "Refresh")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = state.screen.tab() == tab,
                        onClick = { openTab(tab) },
                        icon = { Icon(painter = painterResource(tab.iconResource), contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            when {
                state.screen == Screen.Spotify -> SpotifyScreen(spotifyState, isPreparingTrash, spotifyActions)
                state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.loadFailed -> LoadFailedScreen(onTryAgain = viewModel::refresh)
                state.access != AccessState.Ready -> AccessScreen(
                    access = state.access,
                    onRequestAccess = viewModel::requestPermission,
                    onCheckAgain = viewModel::refresh,
                )
                state.screen == Screen.Home -> HomeScreen(
                    state = state,
                    onOpenDelete = { openLibraryChip(Screen.Delete) },
                    onOpenLarge = { openLibraryChip(Screen.Large) },
                    onAllowFileAccess = {
                        if (isFileAccessBlocked) {
                            openAppSettings()
                        } else {
                            fileAccessLauncher.launch(mediaPermission)
                        }
                    },
                    onImportHistory = pickHistoryFile,
                    onDismissImportOffer = viewModel::dismissImportOffer,
                )
                state.screen == Screen.Settings -> SettingsScreen(
                    selected = state.cutoff,
                    onSelect = viewModel::setCutoff,
                    onExportHistory = viewModel::exportHistory,
                    onImportHistory = pickHistoryFile,
                    spotifyState = spotifyState,
                    spotifyActions = spotifyActions,
                )
                else -> Column(modifier = Modifier.fillMaxSize()) {
                    LibraryChips(selected = state.screen, counts = state.libraryCounts, onSelect = openLibraryChip)
                    if (state.screen == Screen.Large) {
                        LargeSongsScreen(
                            state = state,
                            onToggleSelected = viewModel::toggleSelected,
                            onSelectAll = viewModel::selectAll,
                            isPreparingTrash = isPreparingTrash,
                            onMoveToTrash = moveSelectionToTrash,
                            onSendToSpotify = sendSelectionToSpotify,
                        )
                    } else {
                        TrackListScreen(
                            state = state,
                            today = LocalDate.now(ZoneOffset.UTC),
                            onToggleSelected = viewModel::toggleSelected,
                            onSelectAll = viewModel::selectAll,
                            isPreparingTrash = isPreparingTrash,
                            onMoveToTrash = moveSelectionToTrash,
                            onSendToSpotify = sendSelectionToSpotify,
                        )
                    }
                }
            }
        }
    }

    (trashFlow as? TrashFlow.Confirming)?.let { confirming ->
        TrashConfirmDialog(
            plan = confirming.plan,
            onConfirm = viewModel::confirmTrash,
            onDismiss = viewModel::cancelTrash,
        )
    }
}

private tailrec fun Context.findActivity(): Activity =
    this as? Activity ?: (this as ContextWrapper).baseContext.findActivity()

@Composable
private fun LoadFailedScreen(onTryAgain: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Couldn't read your music player's library.")
        Button(onClick = onTryAgain) { Text("Try again") }
    }
}
