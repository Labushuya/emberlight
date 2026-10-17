package io.github.labushuya.emberlight.feature.gallery.viewer

/**
 * Zustand der Videowiedergabe im Viewer. Es gibt genau einen Player. [attachedId] ist die Seite, an der er hängt (und
 * deren Video geladen ist), `null` heißt: keine Seite spielt, der Player ist gelöst. [playRequested] heißt: die Wiedergabe
 * ist gewünscht.
 *
 * Nie automatisch: Ein Video startet nur, wenn der Nutzer den Abspiel-Knopf drückt.
 */
internal data class VideoPlaybackState(
    val attachedId: Long? = null,
    val playRequested: Boolean = false,
)

/** Ereignisse, die den Zustand ändern. */
internal sealed interface VideoPlaybackEvent {
    /** Die Seite mit [id] ist eingerastet (Wischen beendet). */
    data class PageSettled(
        val id: Long,
    ) : VideoPlaybackEvent

    /** Der Abspiel-Knopf der Seite [id] wurde gedrückt. [uri] ist das Video dieser Seite. */
    data class PlayPressed(
        val id: Long,
        val uri: String,
    ) : VideoPlaybackEvent

    /** Pause-Knopf. */
    data object PausePressed : VideoPlaybackEvent

    /** Die Activity geht in den Hintergrund (ON_STOP). */
    data object Stopped : VideoPlaybackEvent

    /** Das Video ist zu Ende gelaufen. */
    data object Ended : VideoPlaybackEvent

    /** Der Player meldet einen Fehler. Die Seite fällt auf das Standbild zurück. */
    data object Failed : VideoPlaybackEvent
}

/** Aufträge an den Player, die aus einem Übergang folgen. */
internal sealed interface VideoPlaybackCommand {
    /** Ein neues Video laden und vorbereiten, ersetzt das bisherige. */
    data class Load(
        val uri: String,
    ) : VideoPlaybackCommand

    /** Wiedergabe starten. Am Ende des Videos beginnt sie von vorn. */
    data object Play : VideoPlaybackCommand

    /** Wiedergabe anhalten. */
    data object Pause : VideoPlaybackCommand

    /** Den Player vom Video lösen und das Medium entladen, Speicher und Decoder freigeben. */
    data object Release : VideoPlaybackCommand
}

/** Reine Zustandsmaschine der Videowiedergabe. Ohne Player, ohne Android, damit sie vollständig testbar ist. */
internal object VideoPlayback {
    fun reduce(
        state: VideoPlaybackState,
        event: VideoPlaybackEvent,
    ): Pair<VideoPlaybackState, List<VideoPlaybackCommand>> =
        when (event) {
            is VideoPlaybackEvent.PageSettled -> pageSettled(state, event.id)
            is VideoPlaybackEvent.PlayPressed -> playPressed(state, event)
            VideoPlaybackEvent.PausePressed, VideoPlaybackEvent.Stopped, VideoPlaybackEvent.Ended -> pause(state)
            VideoPlaybackEvent.Failed -> release(state)
        }

    /** Seitenwechsel: Die Wiedergabe endet und der Player wird gelöst, sobald die Seite nicht mehr die angehängte ist. */
    private fun pageSettled(
        state: VideoPlaybackState,
        id: Long,
    ): Pair<VideoPlaybackState, List<VideoPlaybackCommand>> {
        val attached = state.attachedId ?: return state to emptyList()
        return if (attached == id) state to emptyList() else release(state)
    }

    private fun playPressed(
        state: VideoPlaybackState,
        event: VideoPlaybackEvent.PlayPressed,
    ): Pair<VideoPlaybackState, List<VideoPlaybackCommand>> =
        when {
            state.attachedId != event.id -> {
                VideoPlaybackState(attachedId = event.id, playRequested = true) to
                    listOf(VideoPlaybackCommand.Load(event.uri), VideoPlaybackCommand.Play)
            }

            state.playRequested -> {
                state to emptyList()
            }

            else -> {
                state.copy(playRequested = true) to listOf(VideoPlaybackCommand.Play)
            }
        }

    /** Anhalten, aber das Video bleibt geladen. Eine spätere Wiedergabe setzt an derselben Stelle fort. */
    private fun pause(state: VideoPlaybackState): Pair<VideoPlaybackState, List<VideoPlaybackCommand>> =
        if (state.playRequested) {
            state.copy(playRequested = false) to listOf(VideoPlaybackCommand.Pause)
        } else {
            state to emptyList()
        }

    private fun release(state: VideoPlaybackState): Pair<VideoPlaybackState, List<VideoPlaybackCommand>> =
        if (state.attachedId == null) {
            state to emptyList()
        } else {
            VideoPlaybackState() to listOf(VideoPlaybackCommand.Release)
        }
}
