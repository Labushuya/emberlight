package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import io.github.labushuya.emberlight.feature.gallery.R

/**
 * Eine Video-Seite. Ohne angehängten Player zeigt sie das Standbild (Vorschaubild des Systems) mit einem Abspiel-Knopf.
 * Ist die Seite angehängt, zeigt sie den einen Player (ADR 0010). Kein Autoplay: Die Wiedergabe beginnt nur über [onPlay].
 *
 * Das Standbild ist das gemeinsame Element des Übergangs. Der Player erscheint erst nach dem Übergang, die Oberfläche
 * zeigt bis dahin das Standbild. Wischen nach unten schließt den Viewer ([dismiss]), ohne Zoom.
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
    dismiss: DismissState = rememberDismissState(),
    onDismiss: () -> Unit = {},
) {
    val stillFailed = remember(item.id) { mutableStateOf(false) }
    val settled by rememberPageSettled()
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .semantics { contentDescription = label }
                .swipeDownToDismiss(dismiss = dismiss, canStart = { true }, onClose = onDismiss)
                .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        when {
            attached && player != null && settled -> {
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
                ViewerPreview(item = item, onError = { stillFailed.value = true })
                if (!attached) {
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
}

private val PLAY_BUTTON_SIZE = 72.dp
private val PLAY_ICON_SIZE = 40.dp
