package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.labushuya.emberlight.feature.gallery.R
import io.github.labushuya.emberlight.feature.gallery.timeline.formatDuration

/**
 * Steuerung der Videowiedergabe: Abspielen oder Pause, Zeitleiste zum Ziehen, aktuelle Zeit und Gesamtdauer, Stumm-Schalter.
 * Alle Knöpfe sind beschriftet. Die Zeitleiste ist nur aktiv, wenn der Player das Video geladen hat ([attached]).
 */
@Composable
internal fun VideoControls(
    video: VideoPlaybackUi,
    playRequested: Boolean,
    attached: Boolean,
    muted: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = video.durationMs.coerceAtLeast(1L)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: (video.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val position = formatDuration(video.positionMs)
    val total = formatDuration(video.durationMs)
    val seekLabel = stringResource(R.string.feature_gallery_viewer_seek)
    val seekState = stringResource(R.string.feature_gallery_viewer_position, position, total)

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Slider(
            value = fraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let { onSeek((it * duration).toLong()) }
                dragFraction = null
            },
            enabled = attached && video.durationMs > 0L,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = seekLabel
                        stateDescription = seekState
                    },
            colors =
                SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = INACTIVE_TRACK_ALPHA),
                ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onPlayPause) {
                Icon(
                    painter = painterResource(if (playRequested) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                    contentDescription =
                        stringResource(
                            if (playRequested) {
                                R.string.feature_gallery_viewer_pause
                            } else {
                                R.string.feature_gallery_viewer_play
                            },
                        ),
                    tint = Color.White,
                )
            }
            Text(
                text = "$position / $total",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleMute) {
                Icon(
                    painter = painterResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
                    contentDescription =
                        stringResource(
                            if (muted) R.string.feature_gallery_viewer_unmute else R.string.feature_gallery_viewer_mute,
                        ),
                    tint = Color.White,
                )
            }
        }
    }
}

private const val INACTIVE_TRACK_ALPHA = 0.3f
