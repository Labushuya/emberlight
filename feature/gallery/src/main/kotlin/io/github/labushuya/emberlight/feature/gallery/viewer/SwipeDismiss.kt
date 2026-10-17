package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Feste Schwellen der vertikalen Gesten in dp (ADR 0010, Gesten-Schwellen in dp). Sie hängen nicht an der Höhe des Geräts, sondern
 * gelten gleich auf jedem Gerät. Umgerechnet in Pixel wird über [GesturePx.of].
 */
internal object ViewerGestureDp {
    /** Infobereich: Weg in dp, ab dem er beim Loslassen einrastet. Gleich dem Positionsschwellwert von Material 3 (56 dp). */
    const val INFO_OPEN_DP = 56f

    /** Infobereich: Geschwindigkeit in dp/s für den Schnellschluss. Darunter gilt der Weg allein. */
    const val INFO_FLING_DP_PER_SECOND = 200f

    /** Infobereich: Mindestweg in dp, damit ein Schnellschluss zählt. Schützt vor Zucken. */
    const val INFO_FLING_MIN_DP = 12f

    /** Wegwischen: Weg in dp, ab dem ein Loslassen den Viewer schließt. */
    const val DISMISS_DP = 96f

    /** Wegwischen: Geschwindigkeit in dp/s für den Schnellschluss. */
    const val DISMISS_FLING_DP_PER_SECOND = 400f

    /** Wegwischen: Mindestweg in dp für den Schnellschluss. Verhindert Fehlschließen bei kleinen Zuckern. */
    const val DISMISS_FLING_MIN_DP = 24f
}

/** Die Schwellen aus [ViewerGestureDp] in Pixel, für die Dichte des Geräts. Ohne Bezug zur Höhe des Bildschirms. */
internal class GesturePx(
    val infoOpenPx: Float,
    val infoFlingPerSecond: Float,
    val infoFlingMinPx: Float,
    val dismissPx: Float,
    val dismissFlingPerSecond: Float,
    val dismissFlingMinPx: Float,
) {
    companion object {
        /** Rechnet die dp-Schwellen für die Dichte [density] in Pixel um. */
        fun of(density: Density): GesturePx =
            with(density) {
                GesturePx(
                    infoOpenPx = ViewerGestureDp.INFO_OPEN_DP.dp.toPx(),
                    infoFlingPerSecond = ViewerGestureDp.INFO_FLING_DP_PER_SECOND.dp.toPx(),
                    infoFlingMinPx = ViewerGestureDp.INFO_FLING_MIN_DP.dp.toPx(),
                    dismissPx = ViewerGestureDp.DISMISS_DP.dp.toPx(),
                    dismissFlingPerSecond = ViewerGestureDp.DISMISS_FLING_DP_PER_SECOND.dp.toPx(),
                    dismissFlingMinPx = ViewerGestureDp.DISMISS_FLING_MIN_DP.dp.toPx(),
                )
            }
    }
}

/** Verhältnis der Achsen: Eine Bewegung gilt als vertikal, wenn sie mehr als so viel mal stärker vertikal als horizontal geht. */
internal const val AXIS_RATIO = 1.5f

/** Größte Verkleinerung beim Wegwischen (10 %). Rein optisch, die Schwelle hängt nicht daran. */
internal const val SHRINK_MAX = 0.1f

/** Zoomstufe, unterhalb derer das Bild als ungezoomt gilt (Telephoto liefert ungefähr 0). */
internal const val ZOOM_EPSILON = 0.001f

/** Anteil der Höhe, den der Infobereich offen einnimmt (ca. 60 %). Das Bild bleibt oben sichtbar (ADR 0010, Infobereich). */
internal const val INFO_PANEL_FRACTION = 0.6f

/** Entscheidung über eine Geste, sobald sie den Schwellwert der Bedienung überschreitet. */
internal enum class DragIntent {
    /** Noch zu kurz, um zu entscheiden. */
    PENDING,

    /** Überwiegend nach unten: Wegwischen, oder Schließen des Infobereichs, wenn er offen ist. */
    DISMISS,

    /** Überwiegend nach oben: der Infobereich öffnet sich (ADR 0010, Infobereich). */
    INFO,

    /** Horizontal oder unentschieden: das Wischen bleibt beim Pager. */
    PASS,
}

/**
 * Achsenerkennung. Entscheidet erst, wenn die Gesamtbewegung den [slopPx] überschreitet. Überwiegend vertikal nach unten
 * (`dy > 0` und `|dy| > AXIS_RATIO * |dx|`) ist Wegwischen, überwiegend vertikal nach oben ist der Infobereich. Horizontale
 * Bewegungen gehen an den Pager.
 */
internal fun dragIntent(
    totalX: Float,
    totalY: Float,
    slopPx: Float,
): DragIntent {
    if (hypot(totalX, totalY) < slopPx) return DragIntent.PENDING
    if (abs(totalY) <= AXIS_RATIO * abs(totalX)) return DragIntent.PASS
    return if (totalY > 0f) DragIntent.DISMISS else DragIntent.INFO
}

/** Anteil des Wegziehens an der Höhe, zwischen 0 und 1. Nur Bewegungen nach unten zählen. */
internal fun dismissProgress(
    offsetPx: Float,
    heightPx: Float,
): Float = if (heightPx <= 0f) 0f else (offsetPx / heightPx).coerceIn(0f, 1f)

/** Skalierung beim Wegziehen: 1 ohne Geste, bis zu `1 - SHRINK_MAX` am Ende der Strecke. */
internal fun dragScale(
    offsetPx: Float,
    heightPx: Float,
): Float = 1f - SHRINK_MAX * dismissProgress(offsetPx, heightPx)

/**
 * Schließen beim Loslassen? Ja, wenn der Weg nach unten [GesturePx.dismissPx] erreicht. Ja auch bei einer schnellen Bewegung nach
 * unten, sofern sie mindestens [GesturePx.dismissFlingMinPx] zurückgelegt hat. Sonst federt der Viewer zurück. Die Höhe des
 * Bildschirms spielt keine Rolle: Dieselbe Strecke in dp schließt auf jedem Gerät.
 */
internal fun shouldDismiss(
    offsetPx: Float,
    velocityYPerSecond: Float,
    thresholds: GesturePx,
): Boolean {
    if (offsetPx <= 0f) return false
    if (offsetPx >= thresholds.dismissPx) return true
    return offsetPx >= thresholds.dismissFlingMinPx && velocityYPerSecond >= thresholds.dismissFlingPerSecond
}

/**
 * Infobereich offen oder zu nach dem Loslassen. [startOpen] ist der Stand beim Beginn der Geste, [travelUpPx] der Weg des
 * Fingers seit dem Beginn nach oben (nach unten negativ). Ein schneller Zug mit Mindestweg entscheidet die Richtung. Sonst
 * entscheidet der Weg: ab [GesturePx.infoOpenPx] nach oben öffnet, ab gleichem Weg nach unten schließt. Darunter federt er
 * in den Ausgangsstand zurück.
 */
internal fun infoSheetOpens(
    startOpen: Boolean,
    travelUpPx: Float,
    velocityYPerSecond: Float,
    thresholds: GesturePx,
): Boolean =
    when {
        velocityYPerSecond <= -thresholds.infoFlingPerSecond && travelUpPx >= thresholds.infoFlingMinPx -> true
        velocityYPerSecond >= thresholds.infoFlingPerSecond && travelUpPx <= -thresholds.infoFlingMinPx -> false
        travelUpPx >= thresholds.infoOpenPx -> true
        travelUpPx <= -thresholds.infoOpenPx -> false
        else -> startOpen
    }

/** Wegwischen und Infobereich beginnen nur, wenn das Bild nicht gezoomt ist. Gezoomt gehört die Geste dem Bild (Verschieben). */
internal fun canStartDismiss(zoomFraction: Float?): Boolean = (zoomFraction ?: 0f) <= ZOOM_EPSILON

/**
 * Vertikale Wischgesten einer Seite (ADR 0010, Kurzer Blick und Infobereich).
 *
 * - Infobereich geschlossen, ungezoomt: nach unten wegwischen, nach oben den Infobereich öffnen.
 * - Infobereich offen: nach unten schließt er, nach oben geschieht nichts.
 * - Gezoomt oder horizontal: die Geste gehört dem Bild bzw. dem Pager.
 *
 * Die Achse wird erst erkannt, wenn die Bewegung den Schwellwert überschreitet. Ein Kind, das die Bewegung verbraucht, beendet
 * die Geste, damit Antippen, Doppeltipp und Telephoto unberührt bleiben.
 */
@Composable
internal fun Modifier.verticalSwipe(
    overlay: ViewerOverlayState,
    canStart: () -> Boolean,
): Modifier {
    val currentCanStart by rememberUpdatedState(canStart)
    val viewConfiguration = LocalViewConfiguration.current
    return pointerInput(overlay) {
        val slop = viewConfiguration.touchSlop
        val thresholds = GesturePx.of(this)
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!currentCanStart()) return@awaitEachGesture
            val tracker = VelocityTracker()
            var totalX = 0f
            var totalY = 0f
            var intent = DragIntent.PENDING
            // Bewegt die Geste den Infobereich (statt das Wegwischen)? Gilt ab der Entscheidung.
            var onSheet = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.positionChange()
                when (intent) {
                    DragIntent.PENDING -> {
                        if (change.isConsumed) {
                            intent = DragIntent.PASS
                        } else {
                            totalX += delta.x
                            totalY += delta.y
                            intent = dragIntent(totalX, totalY, slop)
                            when (intent) {
                                // Solange der Infobereich offen ist oder einfährt, gehört das Ziehen ihm (ADR 0010).
                                DragIntent.DISMISS -> {
                                    onSheet = overlay.infoVisible
                                    if (onSheet) overlay.infoDragStarted() else overlay.dragStarted()
                                }

                                // Nach oben öffnet der Infobereich. Ist er schon offen, bleibt die Geste beim Pager.
                                DragIntent.INFO -> {
                                    if (overlay.infoOpen) {
                                        intent = DragIntent.PASS
                                    } else {
                                        onSheet = true
                                        overlay.infoDragStarted()
                                    }
                                }

                                DragIntent.PENDING, DragIntent.PASS -> {}
                            }
                            if (intent == DragIntent.DISMISS || intent == DragIntent.INFO) {
                                if (onSheet) overlay.infoDragBy(totalY) else overlay.dragBy(totalY)
                                tracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                            }
                        }
                    }

                    DragIntent.DISMISS, DragIntent.INFO -> {
                        if (change.isConsumed) break
                        if (onSheet) overlay.infoDragBy(delta.y) else overlay.dragBy(delta.y)
                        tracker.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                    }

                    // Die Bewegung gehört dem Pager oder dem Zoom: nichts zu tun, bis der Finger losgelassen wird.
                    DragIntent.PASS -> {}
                }
            }
            val velocity = tracker.calculateVelocity().y
            if (onSheet) {
                overlay.infoDragEnded(velocity, thresholds)
            } else if (intent == DragIntent.DISMISS) {
                overlay.dragEnded(velocity, thresholds)
            }
        }
    }
}

/**
 * Ziehen nach unten am Infobereich selbst (ADR 0010, Infobereich): Steht der Inhalt oben, zieht die Bewegung den Bereich mit und
 * schließt ihn bei Schwelle oder Geschwindigkeit, sonst federt er zurück. Ist der Inhalt gescrollt, verbraucht er die Bewegung
 * zuerst, und der Bereich bleibt stehen.
 *
 * Der Bereich übernimmt eine Bewegung erst, wenn der Inhalt sie nicht verbraucht hat. Die übernommenen Ereignisse verbraucht er,
 * damit die Seite darunter sie nicht zusätzlich als Wegwischen wertet. Übernimmt der Bereich nicht, bleibt die Geste bei der
 * Seite (Bild, Pager).
 */
@Composable
internal fun Modifier.sheetDragToClose(
    overlay: ViewerOverlayState,
    scroll: ScrollState,
): Modifier {
    val viewConfiguration = LocalViewConfiguration.current
    return pointerInput(overlay) {
        val slop = viewConfiguration.touchSlop
        val thresholds = GesturePx.of(this)
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (overlay.phase != OverlayPhase.OPEN || !overlay.infoVisible) return@awaitEachGesture
            val tracker = VelocityTracker()
            var totalX = 0f
            var totalY = 0f
            var taken = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                // Der Inhalt verbraucht die Bewegung auch am Anschlag. Deshalb zählt die rohe Bewegung. Gescrollt, gehört die Geste
                // dem Inhalt. Steht er oben, gehört eine Bewegung nach unten dem Bereich.
                val delta = change.positionChangeIgnoreConsumed()
                if (scroll.value > 0f) break
                if (!taken) {
                    totalX += delta.x
                    totalY += delta.y
                    when (dragIntent(totalX, totalY, slop)) {
                        DragIntent.PENDING -> {
                            continue
                        }

                        DragIntent.DISMISS -> {
                            taken = true
                            overlay.infoDragStarted()
                            overlay.infoDragBy(totalY)
                        }

                        // Nach oben oder horizontal: nicht der Bereich, die Seite entscheidet.
                        DragIntent.INFO, DragIntent.PASS -> {
                            break
                        }
                    }
                } else {
                    overlay.infoDragBy(delta.y)
                }
                tracker.addPosition(change.uptimeMillis, change.position)
                change.consume()
            }
            if (taken) overlay.infoDragEnded(tracker.calculateVelocity().y, thresholds)
        }
    }
}
