package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Vollbildiger Viewer mit Zustand. Verbindet das ViewModel mit Zurück, Lebenszyklus und den Systemleisten.
 *
 * [onBack] führt ins Raster zurück und meldet die zuletzt angesehene Medien-ID (`null`, wenn es keine mehr gibt, etwa bei
 * entzogener Berechtigung). Die Leisten sind per Antippen umschaltbar. Ausgeblendet heißt immersiv: Auch die Systemleisten
 * sind dann aus.
 */
@Composable
internal fun ViewerScreen(
    onBack: (Long?) -> Unit,
    viewModel: ViewerViewModel = hiltViewModel(),
) {
    val currentOnBack by rememberUpdatedState(onBack)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val muted by viewModel.muted.collectAsStateWithLifecycle()
    // Überlebt Drehen und Falten. Die Leisten zeigen sich beim Öffnen.
    var chromeVisible by rememberSaveable { mutableStateOf(true) }

    val ready = state as? ViewerUiState.Ready
    val currentId = ready?.let { it.items[it.index].id }

    ImmersiveSystemBars(immersive = !chromeVisible)
    // Im Hintergrund pausiert die Wiedergabe. Ein Video startet nie von selbst wieder.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStopped() }
    // Mit Inhalt übernimmt ViewerContent die Rückgeste (Predictive Back, ADR 0010). Ohne Inhalt bleibt dieser Weg.
    BackHandler(enabled = state !is ViewerUiState.Ready) { onBack(currentId) }
    LaunchedEffect(state) {
        // Nichts mehr zu zeigen: zurück ins Raster, das den Grund anzeigt (Berechtigung oder leere Liste).
        if (state == ViewerUiState.Closed) currentOnBack(null)
    }

    when (val current = state) {
        ViewerUiState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        ViewerUiState.Closed -> {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        }

        is ViewerUiState.Ready -> {
            ViewerContent(
                items = current.items,
                index = current.index,
                playback = playback,
                muted = muted,
                player = viewModel.player,
                zone = viewModel.zone,
                chromeVisible = chromeVisible,
                onChromeToggle = { chromeVisible = !chromeVisible },
                onBack = { onBack(currentId) },
                onPageAtRest = viewModel::onPageSettled,
                onPlay = viewModel::onPlayPressed,
                onPause = viewModel::onPausePressed,
                onSeek = viewModel::onSeek,
                onToggleMute = viewModel::onToggleMute,
                onVideoEnd = viewModel::onPlaybackEnded,
                onVideoError = viewModel::onPlaybackFailed,
            )
        }
    }
}
