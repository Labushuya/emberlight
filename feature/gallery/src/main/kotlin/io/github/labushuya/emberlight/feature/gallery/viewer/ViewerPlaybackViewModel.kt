package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.lifecycle.ViewModel
import androidx.media3.common.Player
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Videowiedergabe des Vollbild-Overlays. Der Halter gehört dem Ziel des Rasters (Galerie oder Album) und überlebt damit Drehen
 * und Falten. Es gibt genau einen Player. Er entsteht erst beim ersten Abspielen und gibt sich mit dem Halter frei (ADR 0010).
 *
 * Die Liste der Elemente gehört dem Raster. Der Halter kennt nur Medien-IDs und URIs, die die Oberfläche übergibt.
 */
@HiltViewModel
internal class ViewerPlaybackViewModel
    @Inject
    constructor(
        private val playerFactory: VideoPlayerFactory,
    ) : ViewModel() {
        private val playbackState = MutableStateFlow(VideoPlaybackState())

        /** Stand der Videowiedergabe. Die Oberfläche richtet danach den Player und die Knöpfe aus. */
        val playback: StateFlow<VideoPlaybackState> = playbackState.asStateFlow()

        private var playerInstance: Player? = null

        private val mutedState = MutableStateFlow(false)

        private val unplayableState = MutableStateFlow(false)

        /** Ein Video lässt sich nicht abspielen (Demo-Medium). Die Oberfläche zeigt dazu eine kurze Meldung. */
        val unplayable: StateFlow<Boolean> = unplayableState.asStateFlow()

        /** Stumm-Schalter. Gilt für den Player, auch wenn er erst beim Abspielen entsteht. */
        val muted: StateFlow<Boolean> = mutedState.asStateFlow()

        /** Der eine Player des Halters. Nur gültig, wenn [playback] eine Seite angehängt hat. */
        val player: Player?
            get() = playerInstance

        fun onToggleMute() {
            mutedState.value = !mutedState.value
            playerInstance?.volume = volumeFor(mutedState.value)
        }

        /** Die Seite mit [id] ist eingerastet. Löst die Wiedergabe von anderen Seiten. */
        fun onPageSettled(id: Long) = dispatch(VideoPlaybackEvent.PageSettled(id))

        /** Abspiel-Knopf der Video-Seite [id] mit dem Video [uri]. Bei Bildern wirkungslos. */
        fun onPlayPressed(
            id: Long,
            uri: String,
        ) {
            // Nur Inhalte aus dem MediaStore (content://) lassen sich abspielen. Demo-Medien kommen nicht von dort (ADR 0011):
            // Der Halter meldet das, statt still nichts zu tun.
            if (!uri.startsWith(CONTENT_SCHEME)) {
                unplayableState.value = true
                return
            }
            dispatch(VideoPlaybackEvent.PlayPressed(id = id, uri = uri))
        }

        /** Die Meldung „nicht abspielbar“ ist angezeigt und soll wieder verschwinden. */
        fun onUnplayableShown() {
            unplayableState.value = false
        }

        fun onPausePressed() = dispatch(VideoPlaybackEvent.PausePressed)

        /** Das Overlay schließt oder die Activity geht in den Hintergrund. Die Wiedergabe pausiert, das Video bleibt geladen. */
        fun onStopped() = dispatch(VideoPlaybackEvent.Stopped)

        /** Ziehen an der Zeitleiste. Wirkt nur, wenn ein Video geladen ist. */
        fun onSeek(positionMs: Long) {
            playerInstance?.seekTo(positionMs.coerceAtLeast(0L))
        }

        fun onPlaybackEnded() = dispatch(VideoPlaybackEvent.Ended)

        fun onPlaybackFailed() = dispatch(VideoPlaybackEvent.Failed)

        override fun onCleared() {
            playerInstance?.release()
            playerInstance = null
        }

        private fun dispatch(event: VideoPlaybackEvent) {
            val (next, commands) = VideoPlayback.reduce(playbackState.value, event)
            // Erst die Befehle an den Player, dann der Zustand: Eine Oberfläche, die den Zustand liest, findet den Player vor.
            commands.forEach(::execute)
            playbackState.value = next
        }

        private fun execute(command: VideoPlaybackCommand) {
            when (command) {
                is VideoPlaybackCommand.Load -> {
                    player().apply {
                        setMediaItem(
                            androidx.media3.common.MediaItem
                                .fromUri(command.uri),
                        )
                        prepare()
                    }
                }

                VideoPlaybackCommand.Play -> {
                    player().apply {
                        if (this.playbackState == Player.STATE_ENDED) seekTo(0L)
                        play()
                    }
                }

                VideoPlaybackCommand.Pause -> {
                    playerInstance?.pause()
                }

                VideoPlaybackCommand.Release -> {
                    playerInstance?.apply {
                        stop()
                        clearMediaItems()
                    }
                }
            }
        }

        private fun player(): Player =
            playerInstance ?: playerFactory.create().also {
                it.volume = volumeFor(mutedState.value)
                playerInstance = it
            }

        private fun volumeFor(muted: Boolean): Float = if (muted) 0f else 1f

        private companion object {
            const val CONTENT_SCHEME = "content://"
        }
    }
