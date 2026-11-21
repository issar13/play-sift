package app.playsift.ui

import android.Manifest
import android.app.Activity
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
import app.playsift.files.trashRequest
import app.playsift.player.AccessState
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.launch

private val tabs = listOf(
    "Home" to Screen.Home,
    "Most played" to Screen.MostPlayed,
    "Never played" to Screen.NeverPlayed,
    "Delete" to Screen.Delete,
    "Settings" to Screen.Settings,
)

private val mediaPermission =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaySiftApp(viewModel: LibraryViewModel) {
    val state by viewModel.state.collectAsState()
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

    val isPreparingTrash by viewModel.isPreparingTrash.collectAsState()
    var selectionAtPermissionRequest by rememberSaveable { mutableStateOf(longArrayOf()) }
    val prepareTrash = { selectedIds: Set<Long> -> scope.launch { viewModel.prepareTrash(selectedIds) } }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        viewModel.setFileAccess(isGranted)
        if (isGranted) {
            prepareTrash(selectionAtPermissionRequest.toSet())
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Play Sift needs access to your music files to move them to the trash.")
            }
        }
    }

    val moveSelectionToTrash: () -> Unit = {
        val selectionNow = state.selectedIds
        val hasPermission =
            ContextCompat.checkSelfPermission(context, mediaPermission) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            prepareTrash(selectionNow)
        } else {
            selectionAtPermissionRequest = selectionNow.toLongArray()
            permissionLauncher.launch(mediaPermission)
        }
    }

    var isFileAccessBlocked by rememberSaveable { mutableStateOf(false) }
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

    BackHandler(enabled = state.screen == Screen.Large) { viewModel.showScreen(Screen.Home) }

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
                tabs.forEach { (label, screen) ->
                    NavigationBarItem(
                        selected = state.screen == screen || (screen == Screen.Home && state.screen == Screen.Large),
                        onClick = { viewModel.showScreen(screen) },
                        icon = {},
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            when {
                state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.loadFailed -> LoadFailedScreen(onTryAgain = viewModel::refresh)
                state.access != AccessState.Ready -> AccessScreen(
                    access = state.access,
                    onRequestAccess = viewModel::requestPermission,
                    onCheckAgain = viewModel::refresh,
                )
                state.screen == Screen.Home -> HomeScreen(
                    state = state,
                    onOpenDelete = { viewModel.showScreen(Screen.Delete) },
                    onOpenLarge = { viewModel.showScreen(Screen.Large) },
                    onAllowFileAccess = {
                        if (isFileAccessBlocked) {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                            )
                        } else {
                            fileAccessLauncher.launch(mediaPermission)
                        }
                    },
                )
                state.screen == Screen.Settings -> SettingsScreen(selected = state.cutoff, onSelect = viewModel::setCutoff)
                state.screen == Screen.Large -> LargeSongsScreen(
                    state = state,
                    onBack = { viewModel.showScreen(Screen.Home) },
                    onToggleSelected = viewModel::toggleSelected,
                    onSelectAll = viewModel::selectAll,
                    isPreparingTrash = isPreparingTrash,
                    onMoveToTrash = moveSelectionToTrash,
                )
                else -> TrackListScreen(
                    state = state,
                    today = LocalDate.now(ZoneOffset.UTC),
                    onToggleSelected = viewModel::toggleSelected,
                    onSelectAll = viewModel::selectAll,
                    isPreparingTrash = isPreparingTrash,
                    onMoveToTrash = moveSelectionToTrash,
                )
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
