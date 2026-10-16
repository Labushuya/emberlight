package io.github.labushuya.emberlight.feature.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.feature.gallery.permission.rememberAppSettingsOpener
import io.github.labushuya.emberlight.feature.gallery.permission.rememberMediaPermissionRequest

/**
 * Galerie mit Zustand. Verbindet das ViewModel mit den Systemdialogen und den Einstellungen. [onMediaClick] meldet ein
 * Antippen nach außen. Der Viewer (Paket 5) ist noch nicht verdrahtet.
 */
@Composable
internal fun GalleryScreen(
    modifier: Modifier = Modifier,
    onMediaClick: (MediaItem) -> Unit = {},
    viewModel: GalleryViewModel = hiltViewModel(),
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
        onMediaClick = onMediaClick,
        onZoomLevelChange = viewModel::onZoomLevelChanged,
        modifier = modifier,
    )
}
