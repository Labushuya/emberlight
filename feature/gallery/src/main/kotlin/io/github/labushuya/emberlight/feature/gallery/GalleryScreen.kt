package io.github.labushuya.emberlight.feature.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.labushuya.emberlight.feature.gallery.permission.rememberAppSettingsOpener
import io.github.labushuya.emberlight.feature.gallery.permission.rememberMediaPermissionRequest
import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerDetailsViewModel
import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerPlaybackViewModel

/**
 * Galerie mit Zustand. Verbindet das ViewModel mit den Systemdialogen und den Einstellungen. [onAlbumClick] meldet ein
 * Antippen auf ein Album, [onAbout] das Öffnen von „Über & Lizenzen“ aus dem Überlaufmenü. Der Viewer öffnet sich über dem
 * Raster, seine Wiedergabe gehört zu diesem Ziel.
 */
@Composable
// Der Wiedergabe-Halter gehört dem Ziel und wird nur bis zum Overlay weitergereicht (ADR 0010).
@Suppress("ktlint:compose:vm-forwarding-check")
internal fun GalleryScreen(
    modifier: Modifier = Modifier,
    onAlbumClick: (Long?) -> Unit = {},
    onAbout: () -> Unit = {},
    viewModel: GalleryViewModel = hiltViewModel(),
    playback: ViewerPlaybackViewModel = hiltViewModel(),
    details: ViewerDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val requestPermission =
        rememberMediaPermissionRequest(
            onResult = viewModel::onPermissionResult,
            onResume = viewModel::onResumed,
        )
    GalleryContent(
        state = state,
        onRequestPermission = requestPermission,
        onOpenSettings = rememberAppSettingsOpener(),
        onAlbumClick = onAlbumClick,
        onAbout = onAbout,
        onZoomLevelChange = viewModel::onZoomLevelChanged,
        playback = playback,
        exif = details,
        zone = viewModel.zone,
        modifier = modifier,
    )
}
