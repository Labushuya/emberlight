package io.github.labushuya.emberlight.feature.gallery

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaGrid
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaGridState
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaStateBody

/**
 * Zustandslose Galerie: Top-App-Bar und der Inhalt der Zeitleiste. Dadurch sind alle Zustände mit festen Daten
 * testbar und in Goldens festhaltbar. Die Aktionen gehen nach außen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryContent(
    state: MediaGridState,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    onZoomLevelChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.feature_gallery_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        MediaStateBody(
            state = state,
            padding = padding,
            onRequest = onRequestPermission,
            onOpenSettings = onOpenSettings,
            gone = {},
        ) { content ->
            MediaGrid(
                state = content,
                padding = padding,
                onRequest = onRequestPermission,
                onOpenSettings = onOpenSettings,
                onMediaClick = onMediaClick,
                onZoomLevelChange = onZoomLevelChange,
            )
        }
    }
}
