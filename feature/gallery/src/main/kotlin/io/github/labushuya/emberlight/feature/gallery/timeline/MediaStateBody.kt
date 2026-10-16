package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.permission.MediaAccess

/**
 * Rahmen für alle Rasteransichten: Ladezustand, Berechtigung, Leerzustand, Album ohne Einträge und das Raster selbst.
 * Galerie und Album zeigen dieselben Zustände mit denselben Texten. Nur das Raster ([content]) und der Zustand
 * „Album fehlt“ ([gone]) unterscheiden sich.
 */
@Composable
internal fun MediaStateBody(
    state: MediaGridState,
    padding: PaddingValues,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    gone: @Composable (PaddingValues) -> Unit,
    content: @Composable (MediaGridState.Content) -> Unit,
) {
    when (state) {
        MediaGridState.Loading -> {
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }

        is MediaGridState.PermissionRequired -> {
            PermissionPrompt(
                canRequest = state.canRequest,
                onRequest = onRequest,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.padding(padding),
            )
        }

        is MediaGridState.Empty -> {
            EmptyTimeline(
                access = state.access,
                missingTypes = state.missingTypes,
                onRequest = onRequest,
                onOpenSettings = onOpenSettings,
                padding = padding,
            )
        }

        is MediaGridState.Gone -> {
            gone(padding)
        }

        is MediaGridState.Content -> {
            content(state)
        }
    }
}

@Composable
private fun EmptyTimeline(
    access: MediaAccess,
    missingTypes: Set<MediaType>,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    padding: PaddingValues,
) {
    Column(modifier = Modifier.padding(padding).fillMaxSize()) {
        if (needsAccessBanner(access, missingTypes)) {
            AccessBanner(
                access = access,
                missingTypes = missingTypes,
                onRequest = onRequest,
                onOpenSettings = onOpenSettings,
            )
        }
        EmptyMessage(modifier = Modifier.weight(1f))
    }
}
