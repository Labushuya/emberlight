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
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot

/** Anteil der Höhe, ab dem ein Loslassen den Viewer schließt (ca. 15 %). */
internal const val DISMISS_FRACTION = 0.15f

/** Mindestanteil der Höhe für den Schnellschluss über die Geschwindigkeit. Verhindert Fehlschließen bei kleinen Zuckern. */
internal const val FLING_MIN_FRACTION = 0.05f

/** Geschwindigkeit (dp je Sekunde), ab der eine Wischbewegung als schnell gilt. */
internal const val FLING_DP_PER_SECOND = 600f

/** Verhältnis der Achsen: Eine Bewegung gilt als vertikal, wenn sie mehr als so viel mal stärker vertikal als horizontal geht. */
internal const val AXIS_RATIO = 1.5f

/** Größte Verkleinerung beim Wegwischen (10 %). */
internal const val SHRINK_MAX = 0.1f

/** Zoomstufe, unterhalb derer das Bild als ungezoomt gilt (Telephoto liefert ungefähr 0). */
internal const val ZOOM_EPSILON = 0.001f

/** Anteil der Höhe, den der Infobereich offen einnimmt (ca. 60 %). Das Bild bleibt oben sichtbar (ADR 0010, Infobereich). */
internal const val INFO_PANEL_FRACTION = 0.6f

/** Ab diesem Anteil rastet der Infobereich beim Loslassen offen ein, sonst zu. */
internal const val INFO_SNAP_FRACTION = 0.5f

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
 * Schließen beim Loslassen? Ja, wenn die Bewegung nach unten die Schwelle (ca. 15 % der Höhe) erreicht. Ja auch bei einer
 * schnellen Bewegung nach unten, sofern sie mindestens [FLING_MIN_FRACTION] der Höhe zurückgelegt hat. Sonst federt der
 * Viewer zurück.
 */
internal fun shouldDismiss(
    offsetPx: Float,
    velocityYPerSecond: Float,
    heightPx: Float,
    flingPerSecond: Float,
): Boolean {
    if (offsetPx <= 0f || heightPx <= 0f) return false
    if (offsetPx >= heightPx * DISMISS_FRACTION) return true
    return offsetPx >= heightPx * FLING_MIN_FRACTION && velocityYPerSecond >= flingPerSecond
}

/**
 * Infobereich offen oder zu nach dem Loslassen. Eine schnelle Bewegung entscheidet die Richtung: nach oben öffnet, nach unten
 * schließt. Sonst zählt der Stand, ab [INFO_SNAP_FRACTION] rastet er offen ein.
 */
internal fun infoSheetOpens(
    fraction: Float,
    velocityYPerSecond: Float,
    flingPerSecond: Float,
): Boolean =
    when {
        velocityYPerSecond <= -flingPerSecond -> true
        velocityYPerSecond >= flingPerSecond -> false
        else -> fraction >= INFO_SNAP_FRACTION
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
        val fling = FLING_DP_PER_SECOND.dp.toPx()
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
                overlay.infoDragEnded(velocity, fling)
            } else if (intent == DragIntent.DISMISS) {
                overlay.dragEnded(velocityYPerSecond = velocity, flingPerSecond = fling)
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
        val fling = FLING_DP_PER_SECOND.dp.toPx()
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
            if (taken) overlay.infoDragEnded(tracker.calculateVelocity().y, fling)
        }
    }
}
