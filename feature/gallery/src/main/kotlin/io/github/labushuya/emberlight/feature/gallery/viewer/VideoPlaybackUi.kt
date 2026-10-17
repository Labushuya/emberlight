package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.delay

/** Anzeigewerte der Videowiedergabe. Ohne Player (noch nicht geladen) gelten Standardwerte aus den Metadaten. */
@Stable
internal data class VideoPlaybackUi(
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val aspectRatio: Float?,
) {
    companion object {
        /** Zustand vor dem Laden: Position null, Dauer aus den Metadaten, unbekanntes Seitenverhältnis. */
        fun idle(durationMs: Long?): VideoPlaybackUi =
            VideoPlaybackUi(isPlaying = false, positionMs = 0L, durationMs = durationMs ?: 0L, aspectRatio = null)

        fun of(player: Player): VideoPlaybackUi {
            val size = player.videoSize
            val ratio =
                if (size.width > 0 && size.height > 0) {
                    size.width * size.pixelWidthHeightRatio / size.height
                } else {
                    null
                }
            val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
            return VideoPlaybackUi(
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = duration,
                aspectRatio = ratio,
            )
        }
    }
}

/**
 * Liest den Zustand des Players für die Anzeige. Ohne Player (`null`) gilt [VideoPlaybackUi.idle]. Die Position wird während
 * der Wiedergabe alle 250 ms nachgelesen. Ende und Fehler gehen über [onPlaybackEnd] und [onPlaybackError] an den Viewer.
 */
@Composable
internal fun rememberVideoPlaybackUi(
    player: Player?,
    fallbackDurationMs: Long?,
    onPlaybackEnd: () -> Unit,
    onPlaybackError: () -> Unit,
): VideoPlaybackUi {
    val currentOnEnded by rememberUpdatedState(onPlaybackEnd)
    val currentOnFailed by rememberUpdatedState(onPlaybackError)
    var snapshot by remember(player) {
        mutableStateOf(player?.let(VideoPlaybackUi::of) ?: VideoPlaybackUi.idle(fallbackDurationMs))
    }
    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose {}
        val listener =
            object : Player.Listener {
                override fun onEvents(
                    player: Player,
                    events: Player.Events,
                ) {
                    snapshot = VideoPlaybackUi.of(player)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) currentOnEnded()
                }

                override fun onPlayerError(error: PlaybackException) {
                    currentOnFailed()
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val playing = snapshot.isPlaying
    LaunchedEffect(player, playing) {
        while (player != null && player.isPlaying) {
            snapshot = VideoPlaybackUi.of(player)
            delay(POSITION_INTERVAL_MS)
        }
    }
    // Ohne Player gelten immer die Werte der aktuellen Metadaten, nicht ein Stand von einem früheren Player.
    return if (player == null) VideoPlaybackUi.idle(fallbackDurationMs) else snapshot
}

private const val POSITION_INTERVAL_MS = 250L
