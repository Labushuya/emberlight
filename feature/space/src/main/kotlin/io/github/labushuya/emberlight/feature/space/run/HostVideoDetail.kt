package io.github.labushuya.emberlight.feature.space.run

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import io.github.labushuya.emberlight.feature.space.R
import java.io.File

/**
 * Zustand der Videowiedergabe in der Gastgeber-Detailansicht (Hotfix 2). Die Oberfläche richtet sich danach aus. Reiner
 * Zustand ohne Player, damit die Logik ohne Gerät testbar ist.
 */
internal data class HostVideoState(
    /** Die Fläche des Players ist sichtbar. Sie erscheint erst nach dem Abspiel-Knopf, nie schon beim Öffnen. */
    val surface: Boolean = false,
    val playing: Boolean = false,
    val ended: Boolean = false,
    val failed: Boolean = false,
)

/** Ereignisse der Wiedergabe: Knöpfe, Rückmeldungen des Players und der Lebenszyklus. */
internal sealed interface HostVideoEvent {
    data object PlayPressed : HostVideoEvent

    data object PausePressed : HostVideoEvent

    data class PlayingChanged(
        val playing: Boolean,
    ) : HostVideoEvent

    data object Ended : HostVideoEvent

    data object Failed : HostVideoEvent

    /** Die Ansicht verliert den Vordergrund: Die Wiedergabe pausiert. */
    data object Stopped : HostVideoEvent
}

/** Reduzierer der Wiedergabe. Gibt nur den nächsten Zustand zurück. Der Aufrufer führt die Befehle am Player aus. */
internal object HostVideoPlayback {
    fun reduce(
        state: HostVideoState,
        event: HostVideoEvent,
    ): HostVideoState =
        when (event) {
            HostVideoEvent.PlayPressed -> {
                state.copy(surface = true)
            }

            HostVideoEvent.PausePressed, HostVideoEvent.Stopped -> {
                state.copy(playing = false)
            }

            is HostVideoEvent.PlayingChanged -> {
                state.copy(playing = event.playing, ended = if (event.playing) false else state.ended)
            }

            HostVideoEvent.Ended -> {
                state.copy(playing = false, ended = true)
            }

            HostVideoEvent.Failed -> {
                state.copy(playing = false, failed = true, surface = false)
            }
        }
}

/**
 * Video aus dem App-Speicher über `file://` (Hotfix 2). Der Player entsteht mit der Ansicht, bekommt die Audio-Attribute für
 * Medien, pausiert beim Abziehen des Kopfhörers und beim Verlassen des Vordergrunds ([LifecycleEventEffect]) und wird beim
 * Schließen freigegeben. Kein Autoplay: Die Wiedergabe beginnt nur über den Abspiel-Knopf.
 *
 * Die Fläche ist eine [SURFACE_TYPE_TEXTURE_VIEW]. Die Ansicht liegt in einem `Dialog`. Dort zeichnet eine SurfaceView in
 * einem eigenen Fenster auf manchen Geräten schwarz oder verspätet. Eine TextureView bleibt im Fenster des Dialogs. Sie
 * kostet eine Kopie der Bilder, bei Video in dieser Größe ist das tragbar. Eine Vollbild-Route wäre die Alternative, ist
 * aber ein größerer Umbau der Navigation.
 *
 * Fehler erscheinen als Text und werden mit Code protokolliert (Tag, Fehlercode, ohne Pfad).
 */
@OptIn(UnstableApi::class)
@Composable
internal fun HostVideoDetail(file: File) {
    val context = LocalContext.current
    var state by remember(file) { mutableStateOf(HostVideoState()) }
    val player =
        remember(file) {
            ExoPlayer
                .Builder(context)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    // Die Fokusanfrage übernimmt der Player: Ein Anruf oder eine andere App pausiert das Video.
                    true,
                ).setHandleAudioBecomingNoisy(true)
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    prepare()
                }
        }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    state = HostVideoPlayback.reduce(state, HostVideoEvent.PlayingChanged(isPlaying))
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState ==
                        Player.STATE_ENDED
                    ) {
                        state = HostVideoPlayback.reduce(state, HostVideoEvent.Ended)
                    }
                }

                override fun onRenderedFirstFrame() {
                    Log.i(TAG, "Video: erstes Bild gerendert")
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Nur der Fehlercode, nie der Pfad oder die Meldung des Players.
                    Log.w(TAG, "Video nicht abspielbar: ${error.errorCodeName} (${error.errorCode})")
                    state = HostVideoPlayback.reduce(state, HostVideoEvent.Failed)
                }
            }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        player.pause()
        state = HostVideoPlayback.reduce(state, HostVideoEvent.Stopped)
    }
    VideoDetailContent(
        state = state,
        surface = {
            PlayerSurface(
                player = player,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            )
        },
        onPlay = {
            val wasEnded = state.ended
            state = HostVideoPlayback.reduce(state, HostVideoEvent.PlayPressed)
            if (wasEnded) player.seekTo(0L)
            player.play()
        },
        onPause = {
            player.pause()
            state = HostVideoPlayback.reduce(state, HostVideoEvent.PausePressed)
        },
    )
}

/**
 * Anzeige der Videowiedergabe ohne Player: Fläche, Knöpfe und Fehlertext. Eigene Funktion, damit die Goldens die Steuerung
 * ohne Gerät zeigen. [surface] ist die Fläche des Players.
 */
@Composable
internal fun VideoDetailContent(
    state: HostVideoState,
    surface: @Composable () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            state.failed -> {
                Text(
                    text = stringResource(R.string.space_media_video_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            else -> {
                if (state.surface) surface()
                if (state.playing) {
                    Button(onClick = onPause) { Text(stringResource(R.string.space_media_video_pause)) }
                } else {
                    val label =
                        if (state.ended) {
                            R.string.space_media_video_replay
                        } else {
                            R.string.space_media_video_play
                        }
                    Button(onClick = onPlay) { Text(stringResource(label)) }
                }
            }
        }
    }
}

private const val TAG = "EmberlightSpaceVideo"
