package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.thumbnail.MediaThumbnail
import io.github.labushuya.emberlight.feature.gallery.R

/**
 * Eine Video-Seite. Ohne angehängten Player zeigt sie das Standbild (Vorschaubild des Systems) mit einem Abspiel-Knopf.
 * Ist die Seite angehängt, zeigt sie den einen Player (ADR 0010). Kein Autoplay: Die Wiedergabe beginnt nur über [onPlay].
 *
 * [label] ist die Beschreibung für Screenreader. [onTap] meldet ein einfaches Antippen, das die Leisten umschaltet.
 * Scheitert das Standbild, zeigt die Seite die Fehlermeldung.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun ViewerVideoPage(
    item: ViewerItem,
    label: String,
    attached: Boolean,
    player: Player?,
    video: VideoPlaybackUi,
    onPlay: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stillFailed = remember(item.id) { mutableStateOf(false) }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .semantics { contentDescription = label }
                .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        when {
            attached && player != null -> {
                val ratio = video.aspectRatio
                PlayerSurface(
                    player = player,
                    modifier = if (ratio != null) Modifier.aspectRatio(ratio) else Modifier.fillMaxSize(),
                )
            }

            stillFailed.value -> {
                ViewerLoadError(modifier = Modifier.fillMaxSize())
            }

            else -> {
                VideoStill(item = item, onError = { stillFailed.value = true })
                FilledIconButton(onClick = onPlay, modifier = Modifier.size(PLAY_BUTTON_SIZE)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_play_arrow),
                        contentDescription = stringResource(R.string.feature_gallery_viewer_play),
                        modifier = Modifier.size(PLAY_ICON_SIZE),
                    )
                }
            }
        }
    }
}

/** Standbild eines Videos über den Vorschau-Pfad des Rasters, mit demselben Cache-Schlüssel. */
@Composable
private fun VideoStill(
    item: ViewerItem,
    onError: () -> Unit,
) {
    val context = LocalPlatformContext.current
    val request =
        remember(context, item.uri, item.previewKey) {
            ImageRequest
                .Builder(context)
                .data(MediaThumbnail(uri = item.uri, type = MediaType.VIDEO))
                .memoryCacheKey(item.previewKey)
                .crossfade(false)
                .build()
        }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        onError = { _ -> onError() },
        modifier = Modifier.fillMaxSize(),
    )
}

private val PLAY_BUTTON_SIZE = 72.dp
private val PLAY_ICON_SIZE = 40.dp
