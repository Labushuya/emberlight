package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot

/** Anteil der Höhe, ab dem ein Loslassen den Viewer schließt (ca. 15 %). */
internal const val DISMISS_FRACTION = 0.15f

/** Mindestanteil der Höhe für den Schnellschluss über die Geschwindigkeit. Verhindert Fehlschließen bei kleinen Zuckern. */
internal const val FLING_MIN_FRACTION = 0.05f

/** Geschwindigkeit nach unten (dp je Sekunde), ab der ein Wisch schließt, auch unter der Schwelle. */
internal const val FLING_DP_PER_SECOND = 600f

/** Verhältnis der Achsen: Eine Bewegung gilt als vertikal, wenn sie mehr als so viel mal stärker nach unten geht. */
internal const val AXIS_RATIO = 1.5f

/** Größte Verkleinerung beim Wegwischen (10 %). */
internal const val SHRINK_MAX = 0.1f

/** Zoomstufe, unterhalb derer das Bild als ungezoomt gilt (Telephoto liefert ungefähr 0). */
internal const val ZOOM_EPSILON = 0.001f

/** Entscheidung über eine Geste, sobald sie den Schwellwert der Bedienung überschreitet. */
internal enum class DragIntent {
    /** Noch zu kurz, um zu entscheiden. */
    PENDING,

    /** Überwiegend nach unten: der Viewer folgt dem Finger. */
    DISMISS,

    /** Horizontal oder nach oben: das Wischen bleibt beim Pager oder der Zoom-Geste. */
    PASS,
}

/**
 * Achsenerkennung. Entscheidet erst, wenn die Gesamtbewegung den [slopPx] überschreitet. Überwiegend vertikal nach unten
 * (`dy > 0` und `|dy| > AXIS_RATIO * |dx|`) startet das Wegwischen. Alles andere geht an den Pager.
 */
internal fun dragIntent(
    totalX: Float,
    totalY: Float,
    slopPx: Float,
): DragIntent {
    if (hypot(totalX, totalY) < slopPx) return DragIntent.PENDING
    return if (totalY > 0f && abs(totalY) > AXIS_RATIO * abs(totalX)) DragIntent.DISMISS else DragIntent.PASS
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

/** Wegwischen beginnt nur, wenn das Bild nicht gezoomt ist. Gezoomt gehört die Geste dem Bild (Verschieben). */
internal fun canStartDismiss(zoomFraction: Float?): Boolean = (zoomFraction ?: 0f) <= ZOOM_EPSILON

/**
 * Wegwischen nach unten für eine Seite. Greift nur bei überwiegend vertikaler Bewegung nach unten und nur, wenn [canStart]
 * `true` liefert. Erkennt die Achse erst, wenn die Bewegung den Schwellwert überschreitet. Horizontale Wischbewegungen,
 * Antippen und Zoom bleiben bei ihren Gesten: Ein Kind, das die Bewegung verbraucht, beendet das Wegwischen.
 */
@Composable
internal fun Modifier.swipeDownToDismiss(
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
                            if (intent == DragIntent.DISMISS) {
                                overlay.dragStarted()
                                overlay.dragBy(totalY)
                                tracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                            }
                        }
                    }

                    DragIntent.DISMISS -> {
                        if (change.isConsumed) break
                        overlay.dragBy(delta.y)
                        tracker.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                    }

                    // Die Bewegung gehört dem Pager oder dem Zoom: nichts zu tun, bis der Finger losgelassen wird.
                    DragIntent.PASS -> {}
                }
            }
            if (intent == DragIntent.DISMISS) {
                overlay.dragEnded(velocityYPerSecond = tracker.calculateVelocity().y, flingPerSecond = fling)
            }
        }
    }
}
