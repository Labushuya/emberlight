package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.feature.gallery.R
import io.github.labushuya.emberlight.core.design.R as DesignR

/**
 * Erklärung vor der Systemanfrage. [canRequest] `true` zeigt „Zugriff erlauben“. `false` führt in die Einstellungen,
 * weil der Dialog nicht mehr erscheint.
 */
@Composable
internal fun PermissionPrompt(
    canRequest: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleRes =
        if (canRequest) {
            R.string.feature_gallery_permission_title
        } else {
            R.string.feature_gallery_permission_settings_title
        }
    val messageRes =
        if (canRequest) {
            R.string.feature_gallery_permission_text
        } else {
            R.string.feature_gallery_permission_settings_text
        }
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(DesignR.drawable.ic_photo_library_filled),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(messageRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.feature_gallery_permission_privacy),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        if (canRequest) {
            Button(onClick = onRequest) {
                Text(stringResource(R.string.feature_gallery_permission_allow))
            }
        } else {
            Button(onClick = onOpenSettings) {
                Text(stringResource(R.string.feature_gallery_permission_open_settings))
            }
        }
    }
}

/**
 * Hinweis über der Zeitleiste: nur ein Teil der Medien sichtbar (ausgewählte Fotos) oder ein Medientyp fehlt.
 * Die Aktionen bitten um mehr Zugriff, über den Systemdialog oder die Einstellungen.
 */
@Composable
internal fun AccessBanner(
    access: MediaAccess,
    missingTypes: Set<MediaType>,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (access == MediaAccess.PARTIAL) {
        AccessBannerSurface(
            message = stringResource(R.string.feature_gallery_partial_text),
            modifier = modifier,
        ) {
            TextButton(onClick = onRequest) {
                Text(stringResource(R.string.feature_gallery_partial_more))
            }
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.feature_gallery_partial_all))
            }
        }
    } else {
        val message =
            if (MediaType.VIDEO in missingTypes) {
                stringResource(R.string.feature_gallery_missing_videos)
            } else {
                stringResource(R.string.feature_gallery_missing_images)
            }
        AccessBannerSurface(message = message, modifier = modifier) {
            TextButton(onClick = onRequest) {
                Text(stringResource(R.string.feature_gallery_permission_allow))
            }
        }
    }
}

/** `true`, wenn ein Hinweis-Banner über dem Raster stehen muss: nur ein Teil der Medien oder ein Typ fehlt. */
internal fun needsAccessBanner(
    access: MediaAccess,
    missingTypes: Set<MediaType>,
): Boolean = access == MediaAccess.PARTIAL || missingTypes.isNotEmpty()

/**
 * Banner als erste Zeile eines Rasters, über die volle Breite. Galerie und Album setzen es gleich, damit der Hinweis in
 * beiden Reitern erscheint.
 */
internal fun LazyGridScope.accessBannerItem(
    key: String,
    contentType: String,
    access: MediaAccess,
    missingTypes: Set<MediaType>,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = contentType) {
        AccessBanner(
            access = access,
            missingTypes = missingTypes,
            onRequest = onRequest,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
private fun AccessBannerSurface(
    message: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                actions()
            }
        }
    }
}

/** Leerzustand: Zugriff ist da, aber die Galerie hat keine Einträge. */
@Composable
internal fun EmptyMessage(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(DesignR.drawable.ic_photo_library_outlined),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.feature_gallery_empty_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.feature_gallery_empty_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
