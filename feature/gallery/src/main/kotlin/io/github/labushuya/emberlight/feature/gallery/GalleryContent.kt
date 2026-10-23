package io.github.labushuya.emberlight.feature.gallery

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import io.github.labushuya.emberlight.feature.gallery.album.AlbumGrid
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaBrowser
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaBrowserState
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaGrid
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaGridState
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaStateBody
import io.github.labushuya.emberlight.feature.gallery.viewer.ExifLookup
import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerPlaybackViewModel
import java.time.ZoneId

/** Reiter der Galerie. Wechsel nur per Antippen, nicht per Wischen (ADR 0009). */
internal enum class GalleryTab(
    @param:StringRes val labelRes: Int,
) {
    PHOTOS(R.string.feature_gallery_tab_photos),
    ALBUMS(R.string.feature_gallery_tab_albums),
}

/**
 * Zustandslose Galerie: Top-App-Bar, Reiterleiste und der Inhalt des gewählten Reiters. Die Scroll-Zustände kommen von außen,
 * damit jeder Reiter seine Position behält. Die Aktionen gehen nach außen.
 *
 * Der Vollbild-Viewer liegt über dem Fotos-Raster (ADR 0010). Er nutzt die Liste des Rasters, daher bekommt er den Zustand
 * des Fotos-Reiters.
 *
 * Die Top-App-Bar klappt beim Scrollen ein. Die Reiterleiste bleibt stehen: Der Wechsel zwischen Fotos und Alben soll
 * jederzeit möglich sein, auch wenn der Inhalt weit unten ist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("ktlint:compose:vm-forwarding-check")
internal fun GalleryContent(
    state: GalleryUiState,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onAlbumClick: (Long?) -> Unit,
    onZoomLevelChange: (Int) -> Unit,
    playback: ViewerPlaybackViewModel,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    exif: ExifLookup = ExifLookup.None,
    photosGridState: LazyGridState = rememberLazyGridState(),
    albumsGridState: LazyGridState = rememberLazyGridState(),
) {
    var tab by rememberSaveable { mutableStateOf(GalleryTab.PHOTOS) }
    val photos = (state.grid as? MediaGridState.Content)?.takeIf { tab == GalleryTab.PHOTOS }
    MediaBrowser(
        content = photos,
        gridState = photosGridState,
        playback = playback,
        zone = zone,
        exif = exif,
        modifier = modifier.fillMaxSize(),
    ) { browser, gridModifier ->
        GalleryScaffold(
            state = state,
            tab = tab,
            onTabChange = { tab = it },
            onRequestPermission = onRequestPermission,
            onOpenSettings = onOpenSettings,
            onAlbumClick = onAlbumClick,
            onZoomLevelChange = onZoomLevelChange,
            onAbout = onAbout,
            browser = browser,
            photosGridState = photosGridState,
            albumsGridState = albumsGridState,
            modifier = gridModifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryScaffold(
    state: GalleryUiState,
    tab: GalleryTab,
    onTabChange: (GalleryTab) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onAlbumClick: (Long?) -> Unit,
    onZoomLevelChange: (Int) -> Unit,
    onAbout: () -> Unit,
    browser: MediaBrowserState,
    photosGridState: LazyGridState,
    albumsGridState: LazyGridState,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.feature_gallery_title)) },
                    actions = { GalleryOverflowMenu(onAbout = onAbout) },
                    scrollBehavior = scrollBehavior,
                )
                SecondaryTabRow(selectedTabIndex = tab.ordinal) {
                    GalleryTab.entries.forEach { entry ->
                        Tab(
                            selected = tab == entry,
                            onClick = { onTabChange(entry) },
                            text = { Text(text = stringResource(entry.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        when (tab) {
            GalleryTab.PHOTOS -> {
                MediaStateBody(
                    state = state.grid,
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
                        onMediaClick = { item -> browser.open(item) },
                        onZoomLevelChange = onZoomLevelChange,
                        gridState = photosGridState,
                    )
                }
            }

            GalleryTab.ALBUMS -> {
                MediaStateBody(
                    state = state.grid,
                    padding = padding,
                    onRequest = onRequestPermission,
                    onOpenSettings = onOpenSettings,
                    gone = {},
                ) { content ->
                    AlbumGrid(
                        albums = state.albums,
                        grid = content,
                        padding = padding,
                        onRequest = onRequestPermission,
                        onOpenSettings = onOpenSettings,
                        onAlbumClick = onAlbumClick,
                        gridState = albumsGridState,
                    )
                }
            }
        }
    }
}
