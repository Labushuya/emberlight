package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.MediaRepository
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.feature.gallery.timeline.ScopedItems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import androidx.media3.common.MediaItem as PlayerMediaItem

/** Zustand des Viewers. [Closed] heißt: nichts zu zeigen, etwa weil die Berechtigung entzogen wurde. Der Viewer geht zurück. */
internal sealed interface ViewerUiState {
    data object Loading : ViewerUiState

    /** [items] in der Reihenfolge des Rasters, [index] ist das gewählte Element. */
    @Immutable
    data class Ready(
        val items: List<ViewerItem>,
        val index: Int,
    ) : ViewerUiState

    data object Closed : ViewerUiState
}

/**
 * Viewer: die Einträge des Rasters im selben Geltungsbereich und derselben Reihenfolge, der gewählte Eintrag und die
 * Videowiedergabe. Die Liste kommt aus [MediaRepository] über [ScopedItems], wie im Raster. Die gewählte Seite bleibt über
 * [SavedStateHandle] beim Drehen erhalten.
 *
 * Es gibt genau einen Player. Er wird erst gebraucht, wenn der Nutzer ein Video abspielt, und gibt sich mit dem ViewModel
 * frei (ADR 0010).
 */
@HiltViewModel
internal class ViewerViewModel
    @Inject
    constructor(
        repository: MediaRepository,
        private val playerFactory: VideoPlayerFactory,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val route: ViewerRoute = savedStateHandle.toRoute()
        private val scopedItems = ScopedItems(route.scope())
        private val currentId: StateFlow<Long> = savedStateHandle.getStateFlow(KEY_CURRENT_ID, route.mediaId)

        /** Liste und Zugriff aus dem Index. Wird nur neu gebildet, wenn sich der Index ändert. */
        private val sources: Flow<Source> =
            repository.library.map { library -> sourceOf(library) }.distinctUntilChanged()

        /** Liste des letzten Zustands, für Seitenwechsel und Wiedergabe. */
        private var current: List<ViewerItem> = emptyList()

        /** Letzte bekannte Position, damit ein verschwundenes Element an derselben Stelle ersetzt wird. */
        private var lastIndex: Int = 0

        val uiState: StateFlow<ViewerUiState> =
            combine(sources, currentId) { source, requested -> uiStateOf(source, requested) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ViewerUiState.Loading)

        private val playbackState = MutableStateFlow(VideoPlaybackState())

        /** Stand der Videowiedergabe. Die Oberfläche richtet danach den Player und die Knöpfe aus. */
        val playback: StateFlow<VideoPlaybackState> = playbackState.asStateFlow()

        private var playerInstance: Player? = null

        private val mutedState = MutableStateFlow(false)

        /** Stumm-Schalter. Gilt für den Player, auch wenn er erst beim Abspielen entsteht. */
        val muted: StateFlow<Boolean> = mutedState.asStateFlow()

        /** Der eine Player des Viewers. Nur gültig, wenn [playback] eine Seite angehängt hat. */
        val player: Player?
            get() = playerInstance

        fun onToggleMute() {
            mutedState.value = !mutedState.value
            playerInstance?.volume = volumeFor(mutedState.value)
        }

        /** Die Seite mit [id] ist eingerastet. Merkt sich die Position und löst die Wiedergabe von anderen Seiten. */
        fun onPageSettled(id: Long) {
            savedStateHandle[KEY_CURRENT_ID] = id
            current.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { lastIndex = it }
            dispatch(VideoPlaybackEvent.PageSettled(id))
        }

        /** Abspiel-Knopf der Video-Seite [id]. Bei Bildern wirkungslos. */
        fun onPlayPressed(id: Long) {
            val item = current.firstOrNull { it.id == id } ?: return
            if (item.type != ViewerMediaType.VIDEO) return
            dispatch(VideoPlaybackEvent.PlayPressed(id = id, uri = item.uri))
        }

        fun onPausePressed() = dispatch(VideoPlaybackEvent.PausePressed)

        /** Die Activity ist in den Hintergrund gegangen. Die Wiedergabe pausiert, das Video bleibt geladen. */
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
                        setMediaItem(PlayerMediaItem.fromUri(command.uri))
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

        private fun uiStateOf(
            source: Source,
            requested: Long,
        ): ViewerUiState =
            when (source) {
                Source.Loading -> {
                    ViewerUiState.Loading
                }

                Source.Closed -> {
                    ViewerUiState.Closed
                }

                is Source.Items -> {
                    current = source.items
                    val position = resolveViewerPosition(source.items, requested, lastIndex)
                    lastIndex = position.index
                    ViewerUiState.Ready(items = source.items, index = position.index)
                }
            }

        private fun sourceOf(library: MediaLibraryState): Source =
            when (library) {
                MediaLibraryState.Loading -> {
                    Source.Loading
                }

                is MediaLibraryState.Ready -> {
                    val index = library.library
                    when {
                        index.mediaAccess == MediaAccess.NONE -> {
                            Source.Closed
                        }

                        else -> {
                            val items = scopedItems.of(index.items).map { it.toViewerItem() }
                            if (items.isEmpty()) Source.Closed else Source.Items(items)
                        }
                    }
                }
            }

        /** Eingabe der Ansicht aus dem Index, unabhängig von der gewählten Seite. */
        private sealed interface Source {
            data object Loading : Source

            data object Closed : Source

            data class Items(
                val items: List<ViewerItem>,
            ) : Source
        }

        private companion object {
            const val KEY_CURRENT_ID = "viewer.currentId"
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
