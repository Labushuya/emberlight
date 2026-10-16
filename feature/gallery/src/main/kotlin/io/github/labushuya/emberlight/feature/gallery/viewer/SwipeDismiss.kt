package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import io.github.labushuya.emberlight.core.design.motion.EmberlightMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
 * Zustand des Wegwischens und des Rückwegs. Der Viewer zeigt ihn über `graphicsLayer` und `drawBehind` (Draw-Phase), damit
 * jedes Frame ohne Neuzusammensetzung des Rasters auskommt.
 *
 * - [offsetY]: Verschiebung nach unten in Pixeln.
 * - [scale]: Verkleinerung, abhängig vom Anteil des Wegziehens.
 * - [backdropAlpha]: Der schwarze Grund wird proportional transparent und gibt das Raster frei.
 * - [dragging]: Leisten sind während des Ziehens ausgeblendet.
 *
 * Schließen: Die Verschiebung, die Verkleinerung und der Grund werden in derselben Dauer wie der Shared-Element-Rückweg
 * zurückgeführt. Der Rückweg startet dadurch aus der gezogenen Position und endet an der Kachel, ohne Sprung in die Mitte.
 */
@Stable
internal class DismissState(
    private val scope: CoroutineScope,
) {
    private var translation by mutableFloatStateOf(0f)
    private var shrink by mutableFloatStateOf(0f)

    /** 0 = schwarz, 1 = transparent. */
    private var fade by mutableFloatStateOf(0f)
    private var running: Job? = null

    /** Höhe des Viewers in Pixeln. Die Oberfläche setzt sie beim Layout. */
    var heightPx: Float = 1f

    var dragging by mutableStateOf(false)
        private set

    val offsetY: Float get() = translation
    val scale: Float get() = 1f - SHRINK_MAX * shrink
    val backdropAlpha: Float get() = 1f - fade

    /** Ein Ziehen beginnt. Eine laufende Rückbewegung endet. */
    fun beginDrag() {
        running?.cancel()
        dragging = true
    }

    /** Folgt dem Finger. Nach oben geht es nicht über die Ausgangslage hinaus. */
    fun dragBy(dy: Float) {
        translation = (translation + dy).coerceAtLeast(0f)
        val progress = dismissProgress(translation, heightPx)
        shrink = progress
        fade = progress
    }

    /** Loslassen: schließen über der Schwelle oder über die Geschwindigkeit, sonst federt der Viewer zurück. */
    fun endDrag(
        velocityYPerSecond: Float,
        flingPerSecond: Float,
        onClose: () -> Unit,
    ) {
        dragging = false
        if (shouldDismiss(translation, velocityYPerSecond, heightPx, flingPerSecond)) {
            close(onClose)
        } else {
            settle()
        }
    }

    /** Der System-Rückweg (Vorschau) läuft: Der Viewer folgt dem Fortschritt wie beim Ziehen. */
    fun backProgressed(progress: Float) {
        running?.cancel()
        dragging = true
        shrink = progress.coerceIn(0f, 1f)
        fade = progress.coerceIn(0f, 1f)
    }

    /** Die Rückgeste wurde abgebrochen. Der Viewer bleibt ohne Artefakte, er federt in die Ausgangslage zurück. */
    fun backCancelled() {
        dragging = false
        settle()
    }

    /** Die Rückgeste ist vollendet: Der Viewer schließt. */
    fun backCommitted(onClose: () -> Unit) {
        dragging = false
        close(onClose)
    }

    /** Schließt den Viewer: [onClose] führt zurück, die Rückbewegung läuft in derselben Dauer wie der Shared-Element-Übergang. */
    fun close(onClose: () -> Unit) {
        onClose()
        animateTo(
            target = 0f,
            scaleTarget = 0f,
            fadeTarget = 1f,
            spec = tween(EmberlightMotion.SHARED_MS, easing = EmberlightMotion.Emphasized),
        )
    }

    /** Federt in die Ausgangslage zurück. */
    fun settle() {
        animateTo(target = 0f, scaleTarget = 0f, fadeTarget = 0f, spec = FEDER)
    }

    private fun animateTo(
        target: Float,
        scaleTarget: Float,
        fadeTarget: Float,
        spec: FiniteAnimationSpec<Float>,
    ) {
        running?.cancel()
        running =
            scope.launch {
                coroutineScope {
                    launch { animate(translation, target, animationSpec = spec) { value, _ -> translation = value } }
                    launch { animate(shrink, scaleTarget, animationSpec = spec) { value, _ -> shrink = value } }
                    launch { animate(fade, fadeTarget, animationSpec = spec) { value, _ -> fade = value } }
                }
            }
    }

    private companion object {
        val FEDER: FiniteAnimationSpec<Float> =
            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
    }
}

/** Zustand des Wegwischens für den Viewer, an die Coroutine-Umgebung der Komposition gebunden. */
@Composable
internal fun rememberDismissState(): DismissState {
    val scope = rememberCoroutineScope()
    return remember(scope) { DismissState(scope) }
}

/**
 * Wegwischen nach unten für eine Seite. Greift nur bei überwiegend vertikaler Bewegung nach unten und nur, wenn [canStart]
 * `true` liefert. Erkennt die Achse erst, wenn die Bewegung den Schwellwert überschreitet. Horizontale Wischbewegungen,
 * Antippen und Zoom bleiben bei ihren Gesten: Ein Kind, das die Bewegung verbraucht, beendet das Wegwischen.
 */
@Composable
internal fun Modifier.swipeDownToDismiss(
    dismiss: DismissState,
    canStart: () -> Boolean,
    onClose: () -> Unit,
): Modifier {
    val currentCanStart by rememberUpdatedState(canStart)
    val currentOnClose by rememberUpdatedState(onClose)
    return pointerInput(dismiss) {
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
                                dismiss.beginDrag()
                                dismiss.dragBy(totalY)
                                tracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                            }
                        }
                    }

                    DragIntent.DISMISS -> {
                        if (change.isConsumed) break
                        dismiss.dragBy(delta.y)
                        tracker.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                    }

                    // Die Bewegung gehört dem Pager oder dem Zoom: nichts zu tun, bis der Finger losgelassen wird.
                    DragIntent.PASS -> {}
                }
            }
            if (intent == DragIntent.DISMISS) {
                dismiss.endDrag(
                    velocityYPerSecond = tracker.calculateVelocity().y,
                    flingPerSecond = fling,
                    onClose = currentOnClose,
                )
            }
        }
    }
}
