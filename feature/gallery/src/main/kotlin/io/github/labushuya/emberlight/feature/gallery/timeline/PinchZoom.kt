package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Sichtbarer Zustand des Rasters während einer Geste: Skalierung um einen Ankerpunkt und Verschiebung durch die Fingermitte.
 *
 * Die Abbildung ist: Bildschirmpunkt = Fingermitte + scale * (Rasterpunkt - Ankerpunkt). Der Rasterpunkt am Ankerpunkt
 * bleibt also unter der Fingermitte, auch wenn die Finger wandern.
 *
 * Die Werte sind [mutableFloatStateOf]-Zustände, die nur in der Layer-Lambda gelesen werden. Jede Änderung zeichnet die
 * Ebene neu, löst aber keine Recomposition aus.
 */
@Stable
internal class PinchZoomState {
    var scale by mutableFloatStateOf(1f)
    var pivotX by mutableFloatStateOf(0f)
    var pivotY by mutableFloatStateOf(0f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)

    /**
     * Geplanter Stufenwechsel, bis das neue Raster steht. Kein State: Nur die Geste und die Komposition lesen es,
     * und die Komposition liest es ohnehin bei jedem Wechsel.
     */
    internal var pending: PendingZoom? = null

    private var settling: Job? = null

    /** Bricht eine laufende Rückführung ab, bevor eine neue Geste oder ein Wechsel die Werte übernimmt. */
    internal fun stopSettling() {
        settling?.cancel()
        settling = null
    }

    /** Führt die Skalierung kurz und ohne Überschwingen auf 1 zurück. Der Ankerpunkt bleibt, wo er ist. */
    internal fun settle(scope: CoroutineScope) {
        stopSettling()
        pending = null
        val fromScale = scale
        val fromPanX = panX
        val fromPanY = panY
        settling =
            scope.launch {
                val spec = spring<Float>(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)
                coroutineScope {
                    launch { Animatable(fromScale).animateTo(1f, spec) { scale = value } }
                    launch { Animatable(fromPanX).animateTo(0f, spec) { panX = value } }
                    launch { Animatable(fromPanY).animateTo(0f, spec) { panY = value } }
                }
                scale = 1f
                panX = 0f
                panY = 0f
            }
    }

    /**
     * Übernimmt den Wechsel, sobald das neue Raster für [level] steht. Scrollt den Anker an seine Position und setzt die
     * Skalierung. Läuft in der Komposition, damit kein Zwischenbild mit altem Raster und neuer Transformation entsteht.
     */
    internal fun resolve(
        level: Int,
        tileNewPx: Float,
        paddingTopPx: Float,
        index: TimelineIndex,
        gridState: LazyGridState,
    ) {
        val plan = pending ?: return
        if (plan.level != level) return
        pending = null
        plan.anchorUri?.let { uri ->
            index.indexOfMedia(uri)?.let { target ->
                val top = ZoomAnchor.targetTop(plan.pivotY, plan.anchorFraction, tileNewPx)
                gridState.requestScrollToItem(target, ZoomAnchor.scrollOffset(top, paddingTopPx))
            }
        }
        scale = plan.scale
        pivotX = plan.pivotX
        pivotY = plan.pivotY
        panX = 0f
        panY = 0f
    }
}

/**
 * Plant einen Stufenwechsel. Der Anker ist die Medien-Zelle unter dem Punkt ([anchorX], [anchorY]) im aktuellen Raster.
 * Die neue Ansicht bekommt den Ankerpunkt ([pivotX], [pivotY]) und die Skalierung [visualScale] mal dem Größenverhältnis
 * der Kacheln. So bleibt die Kachelgröße über den Wechsel gleich. `null` bedeutet keine Geste: dann gibt es keine
 * Transformation (Skalierung 1), nur den Anker.
 */
internal fun planLevelChange(
    gridState: LazyGridState,
    anchorX: Float,
    anchorY: Float,
    pivotX: Float,
    pivotY: Float,
    fromLevel: Int,
    toLevel: Int,
    widthPx: Float,
    gapPx: Float,
    densityValue: Float,
    visualScale: Float?,
): PendingZoom {
    val widthDp = widthPx / densityValue
    val tileOld = ZoomLevels.tileWidth(widthPx, gapPx, ZoomLevels.columnsFor(fromLevel, widthDp))
    val tileNew = ZoomLevels.tileWidth(widthPx, gapPx, ZoomLevels.columnsFor(toLevel, widthDp))
    val cells =
        gridState.layoutInfo.visibleItemsInfo.map { info ->
            GridCell(
                key = info.key,
                isMedia = info.contentType == CONTENT_TYPE_MEDIA,
                left = info.offset.x.toFloat(),
                top = info.offset.y.toFloat(),
                width = info.size.width.toFloat(),
                height = info.size.height.toFloat(),
            )
        }
    val anchor = ZoomAnchor.pick(cells, anchorX, anchorY)
    val tileRatio = if (tileNew > 0f) tileOld / tileNew else 1f
    return PendingZoom(
        level = toLevel,
        anchorUri = anchor?.key as? String,
        anchorFraction = anchor?.let { ZoomAnchor.fractionWithin(anchorY, it) } ?: 0f,
        pivotX = pivotX,
        pivotY = pivotY,
        scale = if (visualScale == null) 1f else visualScale * tileRatio,
    )
}

/**
 * Zweifinger-Geste über dem Raster. Erst mit zwei Fingern wird ausgewertet und das Ereignis konsumiert. Ein Finger bleibt
 * unberührt, daher scrollt und fliegt das Raster wie bisher.
 *
 * Die Auswertung läuft in der Initial-Phase, also vor dem Raster selbst. Nur so kann die Geste die Scroll-Bewegung
 * verhindern, die sonst mitläuft.
 *
 * @param level Aktuelle Stufe. Wird bei jedem Ereignis gelesen.
 * @param onLevelChange Meldung eines Stufenwechsels. Das ViewModel übernimmt ihn, danach folgt die Anker-Übernahme.
 */
internal fun Modifier.pinchZoom(
    zoom: PinchZoomState,
    gridState: LazyGridState,
    level: State<Int>,
    onLevelChange: (Int) -> Unit,
    scope: CoroutineScope,
): Modifier =
    pointerInput(zoom, gridState, onLevelChange, scope) {
        val gapPx = ZoomLevels.GRID_GAP_DP.dp.toPx()
        val minSpanPx = MIN_SPAN_DP.dp.toPx()
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var session: PinchSession? = null
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    val a = pressed[0].position
                    val b = pressed[1].position
                    val span = (b - a).getDistance()
                    val centroid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                    if (session == null) {
                        zoom.stopSettling()
                        zoom.pending = null
                        if (zoom.scale == 1f && zoom.panX == 0f && zoom.panY == 0f) {
                            zoom.pivotX = centroid.x
                            zoom.pivotY = centroid.y
                        }
                        session = PinchSession(base = span, compensation = zoom.scale)
                    }
                    event.changes.forEach { it.consume() }
                    if (span >= minSpanPx) {
                        session.update(
                            zoom = zoom,
                            span = span,
                            centroid = centroid,
                            gridState = gridState,
                            level = level.value,
                            gapPx = gapPx,
                            widthPx = size.width.toFloat(),
                            densityValue = density,
                            onLevelChange = onLevelChange,
                        )
                    }
                } else if (session != null) {
                    // Ein Finger ist weg: die Geste endet. Der verbleibende Finger scrollt wieder normal.
                    break
                }
                if (event.changes.none { it.pressed }) break
            }
            if (session != null) zoom.settle(scope)
        }
    }

private const val MIN_SPAN_DP = 16f

/**
 * Zustand einer laufenden Geste. [base] ist der Fingerabstand, auf den sich das Verhältnis bezieht. [compensation] ist die
 * Skalierung, mit der das Raster nach einem Stufenwechsel beginnt, damit die Kacheln nicht springen.
 */
private class PinchSession(
    var base: Float,
    var compensation: Float,
) {
    fun update(
        zoom: PinchZoomState,
        span: Float,
        centroid: Offset,
        gridState: LazyGridState,
        level: Int,
        gapPx: Float,
        widthPx: Float,
        densityValue: Float,
        onLevelChange: (Int) -> Unit,
    ) {
        // Während ein Wechsel auf das neue Raster wartet, bleibt die Ansicht stehen. Die Basis wandert mit.
        if (zoom.pending != null) {
            base = span
            return
        }
        val ratio = span / base
        val step = PinchMath.levelStep(ratio, level)
        if (step != null) {
            val plan =
                planLevelChange(
                    gridState = gridState,
                    anchorX = zoom.pivotX,
                    anchorY = zoom.pivotY,
                    pivotX = centroid.x,
                    pivotY = centroid.y,
                    fromLevel = level,
                    toLevel = step,
                    widthPx = widthPx,
                    gapPx = gapPx,
                    densityValue = densityValue,
                    visualScale = zoom.scale,
                )
            zoom.pending = plan
            // Neue Basis: Der Wechsel zählt als Schwelle, danach beginnt die Zählung von vorn.
            base = span
            compensation = plan.scale
            onLevelChange(step)
            return
        }
        val atEdge = (level == ZoomLevels.MIN_LEVEL && ratio > 1f) || (level == ZoomLevels.MAX_LEVEL && ratio < 1f)
        zoom.scale =
            if (atEdge) {
                compensation * PinchMath.resisted(ratio.coerceIn(EDGE_RATIO_MIN, EDGE_RATIO_MAX))
            } else {
                compensation * ratio
            }
        zoom.panX = centroid.x - zoom.pivotX
        zoom.panY = centroid.y - zoom.pivotY
    }

    private companion object {
        const val EDGE_RATIO_MIN = 0.5f
        const val EDGE_RATIO_MAX = 2f
    }
}
