package io.github.labushuya.emberlight.feature.gallery.album

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.labushuya.emberlight.feature.gallery.R
import io.github.labushuya.emberlight.feature.gallery.permission.rememberAppSettingsOpener
import io.github.labushuya.emberlight.feature.gallery.permission.rememberMediaPermissionRequest
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaBrowser
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaBrowserState
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaGrid
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaGridState
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaStateBody
import io.github.labushuya.emberlight.feature.gallery.viewer.ExifLookup
import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerDetailsViewModel
import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerPlaybackViewModel
import java.time.ZoneId

/**
 * Album-Ansicht mit Zustand. Verbindet das ViewModel mit den Systemdialogen und den Einstellungen. [onBack] führt zurück in
 * die Alben-Übersicht. Der Viewer öffnet sich als Overlay über dem Album-Raster.
 */
@Composable
// Der Wiedergabe-Halter gehört dem Ziel und wird nur bis zum Overlay weitergereicht (ADR 0010).
@Suppress("ktlint:compose:vm-forwarding-check")
internal fun AlbumScreen(
    onBack: () -> Unit,
    viewModel: AlbumDetailViewModel = hiltViewModel(),
    playback: ViewerPlaybackViewModel = hiltViewModel(),
    details: ViewerDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val requestPermission =
        rememberMediaPermissionRequest(
            onResult = viewModel::onPermissionResult,
            onResume = viewModel::onResumed,
        )
    AlbumContent(
        state = state,
        onBack = onBack,
        onRequestPermission = requestPermission,
        onOpenSettings = rememberAppSettingsOpener(),
        onZoomLevelChange = viewModel::onZoomLevelChanged,
        playback = playback,
        exif = details,
        zone = viewModel.zone,
    )
}

/**
 * Zustandslose Album-Ansicht: Top-App-Bar mit Zurück-Pfeil und Albumname, darunter das gemeinsame Raster der Galerie. Der
 * Vollbild-Viewer liegt darüber (ADR 0010). Ist das Album verschwunden, steht eine kurze Meldung mit Zurück-Knopf.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("ktlint:compose:vm-forwarding-check")
internal fun AlbumContent(
    state: AlbumDetailUiState,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onZoomLevelChange: (Int) -> Unit,
    playback: ViewerPlaybackViewModel,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    exif: ExifLookup = ExifLookup.None,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val content = state.grid as? MediaGridState.Content
    MediaBrowser(
        content = content,
        gridState = gridState,
        playback = playback,
        zone = zone,
        exif = exif,
        modifier = modifier.fillMaxSize(),
    ) { browser, gridModifier ->
        AlbumScaffold(
            state = state,
            onBack = onBack,
            onRequestPermission = onRequestPermission,
            onOpenSettings = onOpenSettings,
            onZoomLevelChange = onZoomLevelChange,
            browser = browser,
            gridState = gridState,
            modifier = gridModifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumScaffold(
    state: AlbumDetailUiState,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onZoomLevelChange: (Int) -> Unit,
    browser: MediaBrowserState,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val title = albumTitle(state.album)
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.feature_gallery_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        MediaStateBody(
            state = state.grid,
            padding = padding,
            onRequest = onRequestPermission,
            onOpenSettings = onOpenSettings,
            gone = { gonePadding -> AlbumGone(padding = gonePadding, onBack = onBack) },
        ) { content ->
            MediaGrid(
                state = content,
                padding = padding,
                onRequest = onRequestPermission,
                onOpenSettings = onOpenSettings,
                onMediaClick = { item -> browser.open(item) },
                onZoomLevelChange = onZoomLevelChange,
                gridState = gridState,
            )
        }
    }
}

/** Das Album ist nicht mehr vorhanden, etwa weil alle Einträge gelöscht wurden. Der Zurück-Knopf führt in die Übersicht. */
@Composable
private fun AlbumGone(
    padding: PaddingValues,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.feature_gallery_album_gone),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) {
            Text(stringResource(R.string.feature_gallery_back))
        }
    }
}
