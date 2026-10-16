package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.labushuya.emberlight.core.design.motion.EmberlightMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomableState

/**
 * Zustand des Vollbild-Overlays (ADR 0010). Besitzt die Phase, den Fortschritt der Hero-Animation, den Zieh-Versatz und das
 * gewählte Element. Alles, was die Zeichnung liest, sind Zustandswerte, die in der Zeichenphase gelesen werden: Ein Frame
 * setzt die Oberfläche nicht neu zusammen.
 *
 * [shown] trägt die ID des gezeigten Elements, `null` heißt geschlossen. Die Galerie hält es über `rememberSaveable`, damit es
 * Drehen und Falten übersteht. [cellRect] liefert die Grenzen der sichtbaren Kachel in Root-Koordinaten (ohne Beobachtung).
 */
@Stable
internal class ViewerOverlayState(
    private val scope: CoroutineScope,
    private val shown: MutableState<Long?>,
    private val cellRect: (Long) -> Rect?,
) {
    /** Die gezeigte Medien-ID, `null` wenn geschlossen. */
    val shownId: Long? get() = shown.value

    var phase: OverlayPhase by mutableStateOf(if (shown.value != null) OverlayPhase.OPEN else OverlayPhase.CLOSED)
        private set

    /** Fortschritt der Hero-Animation: 0 = Kachel, 1 = Vollbild. Bei der Rückgeste steuert er den Schrumpf zur Kachel. */
    var progress: Float by mutableFloatStateOf(if (shown.value != null) 1f else 0f)
        private set

    /** Zieh-Versatz nach unten in Pixeln, nie negativ. */
    var dragY: Float by mutableFloatStateOf(0f)
        private set

    /** Höhe des Overlays in Pixeln und Ursprung in Root-Koordinaten. Die Oberfläche setzt sie beim Layout. */
    var heightPx: Float by mutableFloatStateOf(1f)

    /** Rechteck des Fit-Bildes in Overlay-Koordinaten. Die Oberfläche setzt es, sobald das Seitenverhältnis bekannt ist. */
    var fit: Rect by mutableStateOf(Rect.Zero)

    /** Ursprung des Overlays in Root-Koordinaten. */
    var originInRoot: Offset = Offset.Zero

    /** Ziel der Hero-Animation in Root-Koordinaten. `null`: ohne Kachel, die Animation blendet zur Mitte aus. */
    var heroTile: Rect? by mutableStateOf(null)
        private set

    /** Die Zoomzustände der Bildseiten, damit ein gezoomtes Bild vor dem Schließen auf ungezoomt zurückfällt. */
    private val zoomStates = HashMap<Long, ZoomableState>()

    private var job: Job? = null

    /** Registriert den Zoomzustand einer Bildseite, [onDispose] meldet die Abmeldung. */
    fun registerZoom(
        id: Long,
        zoom: ZoomableState,
    ) {
        zoomStates[id] = zoom
    }

    fun unregisterZoom(id: Long) {
        zoomStates.remove(id)
    }

    /** Öffnet das Element [id]. Die Hero-Animation startet an dessen Kachel. */
    fun open(id: Long) {
        if (phase != OverlayPhase.CLOSED) return
        shown.value = id
        heroTile = cellRect(id)
        phase = nextPhase(phase, OverlayEvent.Open)
        job?.cancel()
        job =
            scope.launch {
                progress = 0f
                dragY = 0f
                animate(0f, 1f, animationSpec = heroSpec()) { value, _ -> progress = value }
                phase = nextPhase(phase, OverlayEvent.OpenFinished)
            }
    }

    /** Der Nutzer beginnt zu ziehen. Eine laufende Feder endet, das Bild bleibt an seiner Stelle. */
    fun dragStarted() {
        if (phase != OverlayPhase.OPEN && phase != OverlayPhase.SETTLING) return
        job?.cancel()
        phase = nextPhase(phase, OverlayEvent.DragStarted)
    }

    /** Folgt dem Finger um [delta] Pixel. Nach oben geht es nicht über die Ausgangslage hinaus. */
    fun dragBy(delta: Float) {
        if (phase != OverlayPhase.DRAGGING) return
        dragY = (dragY + delta).coerceAtLeast(0f)
    }

    /** Loslassen: schließen über der Schwelle oder über die Geschwindigkeit, sonst federt das Bild zurück. */
    fun dragEnded(
        velocityYPerSecond: Float,
        flingPerSecond: Float,
    ) {
        if (phase != OverlayPhase.DRAGGING) return
        val dismiss = shouldDismiss(dragY, velocityYPerSecond, heightPx, flingPerSecond)
        phase = nextPhase(phase, OverlayEvent.DragEnded(dismiss = dismiss))
        if (dismiss) {
            closeAnimated()
        } else {
            settleAnimated()
        }
    }

    /** Die System-Rückgeste meldet [fraction] (0 bis 1). Das Bild schrumpft in Richtung Kachel. */
    fun backProgressed(fraction: Float) {
        val next = nextPhase(phase, OverlayEvent.BackProgressed)
        if (next == phase && next != OverlayPhase.BACK) return
        job?.cancel()
        phase = next
        progress = 1f - fraction.coerceIn(0f, 1f)
        dragY = 0f
    }

    /** Die Rückgeste wurde abgebrochen: Das Bild federt zurück zum Vollbild. */
    fun backCancelled() {
        if (phase != OverlayPhase.BACK) return
        phase = nextPhase(phase, OverlayEvent.BackCancelled)
        job?.cancel()
        job =
            scope.launch {
                animate(progress, 1f, animationSpec = SETTLE_SPRING) { value, _ -> progress = value }
                phase = nextPhase(phase, OverlayEvent.SettleFinished)
            }
    }

    /** Die Rückgeste wurde vollendet: der Viewer schließt zur Kachel. */
    fun backCommitted() {
        // Ohne Fortschritt (das System meldet nur das Vollenden, etwa bei der Zurück-Taste) schließt der Viewer trotzdem.
        if (phase == OverlayPhase.BACK) {
            phase = nextPhase(phase, OverlayEvent.BackCommitted)
            closeAnimated()
        } else {
            close()
        }
    }

    /** Wählt das gezeigte Element, etwa nach einem Seitenwechsel. Ohne Wirkung, wenn geschlossen. */
    fun select(id: Long) {
        if (shown.value != null) shown.value = id
    }

    /** Schließt ohne Animation, etwa wenn es nichts mehr zu zeigen gibt. */
    fun forceClose() {
        job?.cancel()
        shown.value = null
        progress = 0f
        dragY = 0f
        phase = OverlayPhase.CLOSED
    }

    /** Schließen über Zurück-Taste oder Schließen-Knopf. */
    fun close() {
        val next = nextPhase(phase, OverlayEvent.CloseRequested)
        if (next == phase) return
        phase = next
        closeAnimated()
    }

    private fun settleAnimated() {
        job?.cancel()
        job =
            scope.launch {
                animate(dragY, 0f, animationSpec = SETTLE_SPRING) { value, _ -> dragY = value }
                phase = nextPhase(phase, OverlayEvent.SettleFinished)
            }
    }

    /**
     * Schließt: Ein gezoomtes Bild fällt zuerst ohne Animation auf ungezoomt zurück. Dann läuft die Hero-Animation vom aktuellen
     * Stand (Zieh-Versatz, Schrumpf, Fortschritt) zurück zur Kachel des aktuellen Elements.
     */
    private fun closeAnimated() {
        job?.cancel()
        job =
            scope.launch {
                val id = shown.value
                heroTile = id?.let(cellRect)
                id?.let { zoomStates[it] }?.let { zoom ->
                    if ((zoom.zoomFraction ?: 0f) > ZOOM_EPSILON) zoom.resetZoom(snap())
                }
                coroutineScope {
                    launch { animate(progress, 0f, animationSpec = heroSpec()) { value, _ -> progress = value } }
                    launch { animate(dragY, 0f, animationSpec = heroSpec()) { value, _ -> dragY = value } }
                }
                shown.value = null
                phase = nextPhase(phase, OverlayEvent.CloseFinished)
            }
    }

    private fun heroSpec(): FiniteAnimationSpec<Float> = EmberlightMotion.heroSpec()

    private companion object {
        val SETTLE_SPRING: FiniteAnimationSpec<Float> =
            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
    }
}

/**
 * Overlay-Zustand der Galerie oder des Albums. Die gewählte ID bleibt über `rememberSaveable` erhalten. Nach einem Prozess-Tod
 * ist der Viewer offen, aber ohne Animation (ADR 0010).
 */
@Composable
internal fun rememberViewerOverlayState(cellRect: (Long) -> Rect?): ViewerOverlayState {
    val scope = rememberCoroutineScope()
    val shown = rememberSaveable { mutableStateOf<Long?>(null) }
    return remember(scope, cellRect) { ViewerOverlayState(scope, shown, cellRect) }
}
