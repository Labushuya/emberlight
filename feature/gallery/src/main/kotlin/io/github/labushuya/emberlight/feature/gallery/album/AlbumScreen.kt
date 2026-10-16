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
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.feature.gallery.R
import io.github.labushuya.emberlight.feature.gallery.permission.rememberAppSettingsOpener
import io.github.labushuya.emberlight.feature.gallery.permission.rememberMediaPermissionRequest
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaGrid
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaStateBody
import io.github.labushuya.emberlight.feature.gallery.viewer.LastViewedMediaId

/**
 * Album-Ansicht mit Zustand. Verbindet das ViewModel mit den Systemdialogen und den Einstellungen. [onBack] führt zurück in
 * die Alben-Übersicht. [onMediaClick] meldet ein Antippen auf ein Medium.
 */
@Composable
internal fun AlbumScreen(
    onBack: () -> Unit,
    onMediaClick: (MediaItem) -> Unit = {},
    lastViewed: LastViewedMediaId = LastViewedMediaId.None,
    viewModel: AlbumDetailViewModel = hiltViewModel(),
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
        onMediaClick = onMediaClick,
        onZoomLevelChange = viewModel::onZoomLevelChanged,
        lastViewed = lastViewed,
    )
}

/**
 * Zustandslose Album-Ansicht: Top-App-Bar mit Zurück-Pfeil und Albumname, darunter das gemeinsame Raster der Galerie.
 * Ist das Album verschwunden, steht eine kurze Meldung mit Zurück-Knopf.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumContent(
    state: AlbumDetailUiState,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    onZoomLevelChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    lastViewed: LastViewedMediaId = LastViewedMediaId.None,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val title = albumTitle(state.album)
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
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
                onMediaClick = onMediaClick,
                onZoomLevelChange = onZoomLevelChange,
                gridState = gridState,
                lastViewed = lastViewed,
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
