package io.github.labushuya.emberlight.feature.gallery

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.permission.requiredPermissions
import io.github.labushuya.emberlight.feature.gallery.permission.anyPermissionRationale
import io.github.labushuya.emberlight.feature.gallery.permission.appSettingsIntent
import io.github.labushuya.emberlight.feature.gallery.permission.findActivity
import io.github.labushuya.emberlight.feature.gallery.timeline.DEFAULT_COLUMNS
import io.github.labushuya.emberlight.feature.gallery.timeline.GalleryContent
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineViewModel

/**
 * Zeitleiste der Galerie. Verbindet den Zustand des ViewModels mit den Systemdialogen und den Einstellungen.
 * [onMediaClick] meldet einen Antippen nach außen. Der Viewer (Paket 5) ist noch nicht verdrahtet.
 */
@Composable
internal fun GalleryScreen(
    modifier: Modifier = Modifier,
    onMediaClick: (MediaItem) -> Unit = {},
    viewModel: TimelineViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val permissions = remember { requiredPermissions(Build.VERSION.SDK_INT) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Die Antwort selbst liest der Index neu. Hier zählt nur, ob ein erneuter Dialog noch möglich ist.
            viewModel.onPermissionResult(showRationale = activity.anyPermissionRationale(permissions))
        }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onResumed(showRationale = activity.anyPermissionRationale(permissions))
    }
    LaunchedEffect(DEFAULT_COLUMNS) {
        viewModel.onColumnsChanged(DEFAULT_COLUMNS)
    }

    GalleryContent(
        state = state,
        columns = DEFAULT_COLUMNS,
        onRequestPermission = { permissionLauncher.launch(permissions.toTypedArray()) },
        onOpenSettings = { context.startActivity(appSettingsIntent(context)) },
        onMediaClick = onMediaClick,
        modifier = modifier,
    )
}
