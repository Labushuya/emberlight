package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.labushuya.emberlight.feature.gallery.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Abbildung zwischen Position auf der Leiste und Index im Raster. Reine Funktionen, dadurch einzeln prüfbar. */
internal object FastScrollMapping {
    /** Nach dieser Ruhezeit blendet die Leiste aus, solange nicht gescrollt oder gezogen wird. */
    const val HIDE_DELAY_MS = 1_500L

    /** Unter diesem Inhalt (in Bildschirmhöhen) gibt es keine Leiste. */
    const val MIN_SCREENS = 3f

    /** Anteil 0 bis 1 für einen Index. Der erste Eintrag liegt oben (0), der letzte unten (1). */
    fun fractionFor(
        index: Int,
        total: Int,
    ): Float {
        if (total <= 1) return 0f
        return (index.toFloat() / (total - 1)).coerceIn(0f, 1f)
    }

    /** Index für einen Anteil. Die Ränder liegen auf dem ersten und letzten Element. */
    fun indexFor(
        fraction: Float,
        total: Int,
    ): Int {
        if (total <= 1) return 0
        val index = (fraction.coerceIn(0f, 1f) * (total - 1)).roundToInt()
        return index.coerceIn(0, total - 1)
    }

    /**
     * Ob die Leiste erscheint. Der Inhalt wird aus Zellenzahl, Spalten und Zellhöhe geschätzt. Überschriften fehlen in der
     * Schätzung, sie sind klein gegenüber dem Inhalt.
     */
    fun isUseful(
        totalItems: Int,
        columns: Int,
        cellPx: Float,
        viewportPx: Float,
    ): Boolean {
        if (viewportPx <= 0f || totalItems <= 0 || columns < 1 || cellPx <= 0f) return false
        val rows = ceil(totalItems.toDouble() / columns).toFloat()
        return rows * cellPx > MIN_SCREENS * viewportPx
    }
}

private val TOUCH_WIDTH = 48.dp
private val THUMB_WIDTH = 4.dp
private val THUMB_HEIGHT = 48.dp
private val BUBBLE_GAP = 8.dp

/**
 * Schnell-Scrollleiste am Ende-Rand. Erscheint beim Scrollen, bleibt sichtbar, solange gezogen wird, und blendet danach aus.
 * Beim Ziehen zeigt eine Blase den Monat (Tagesstufen) oder das Jahr (Monatsstufen) an der Position.
 *
 * Der Griff und die Blase werden über Lambda-Modifikatoren positioniert und lesen den Scrollzustand dort. Der Körper der
 * Funktion liest nur abgeleitete Zustände, daher läuft beim Scrollen keine Recomposition des Rasters.
 */
@Composable
internal fun FastScrollBar(
    gridState: LazyGridState,
    index: TimelineIndex,
    mode: GroupingMode,
    formats: TimelineFormats,
    columns: Int,
    cellPx: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.feature_gallery_fast_scroll)
    val touchWidthPx = with(density) { TOUCH_WIDTH.roundToPx() }
    val thumbWidthPx = with(density) { THUMB_WIDTH.roundToPx() }
    val thumbHeightPx = with(density) { THUMB_HEIGHT.roundToPx() }

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var trackHeightPx by remember { mutableIntStateOf(0) }
    var bubbleHeightPx by remember { mutableIntStateOf(0) }
    var railVisible by remember { mutableStateOf(false) }
    val railAlpha by animateFloatAsState(if (railVisible || dragging) 1f else 0f, label = "fastScrollAlpha")

    val eligible by remember(gridState, columns, cellPx) {
        derivedStateOf {
            val layout = gridState.layoutInfo
            FastScrollMapping.isUseful(
                totalItems = layout.totalItemsCount,
                columns = columns,
                cellPx = cellPx,
                viewportPx = layout.viewportSize.height.toFloat(),
            )
        }
    }
    val bubble by remember(gridState, index, mode, formats) {
        derivedStateOf {
            if (!dragging) {
                ""
            } else {
                val target = FastScrollMapping.indexFor(dragFraction, gridState.layoutInfo.totalItemsCount)
                index.dateAt(target)?.let { formats.scrollLabel(mode, it) } ?: ""
            }
        }
    }

    // Sichtbarkeit: beim Scrollen und Ziehen an, danach nach kurzer Ruhe aus.
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress || dragging }
            .collectLatest { active ->
                if (active) {
                    railVisible = true
                } else {
                    delay(FastScrollMapping.HIDE_DELAY_MS)
                    railVisible = false
                }
            }
    }
    // Während des Ziehens springt das Raster zum Ziel. `conflate` hält nur den neuesten Wert, damit nie zwei Sprünge
    // gleichzeitig laufen. Das drosselt auch bei 50.000 Einträgen.
    LaunchedEffect(gridState) {
        snapshotFlow {
            if (dragging) FastScrollMapping.indexFor(dragFraction, gridState.layoutInfo.totalItemsCount) else null
        }.filterNotNull()
            .distinctUntilChanged()
            .conflate()
            .collect { target -> gridState.scrollToItem(target) }
    }
    // Leichtes Tick, wenn die Blase den Monat oder das Jahr wechselt. Nicht beim Beginn des Ziehens.
    LaunchedEffect(index, mode, formats) {
        var previous = ""
        snapshotFlow { bubble }.collect { text ->
            if (text.isEmpty()) {
                previous = ""
            } else {
                if (previous.isNotEmpty() && text != previous) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
                previous = text
            }
        }
    }

    if (!eligible) return

    // Anteil des Griffs: beim Ziehen die Fingerposition, sonst die aktuelle Scrollposition.
    val thumbFraction: () -> Float = {
        if (dragging) {
            dragFraction
        } else {
            FastScrollMapping.fractionFor(gridState.firstVisibleItemIndex, gridState.layoutInfo.totalItemsCount)
        }
    }
    val thumbTop: () -> Int = {
        (thumbFraction() * (trackHeightPx - thumbHeightPx).coerceAtLeast(0)).roundToInt()
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)),
    ) {
        // Berührungsfläche am Ende-Rand, volle Höhe. Der Griff darin ist schmal.
        Box(
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(TOUCH_WIDTH)
                    .onSizeChanged { trackHeightPx = it.height }
                    .semantics {
                        contentDescription = label
                        progressBarRangeInfo =
                            ProgressBarRangeInfo(
                                current = thumbFraction(),
                                range = 0f..1f,
                            )
                        setProgress { target ->
                            val to = FastScrollMapping.indexFor(target, gridState.layoutInfo.totalItemsCount)
                            scope.launch { gridState.scrollToItem(to) }
                            true
                        }
                    }.pointerInput(gridState) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val height = size.height.toFloat().coerceAtLeast(1f)
                            dragging = true
                            try {
                                dragFraction = (down.position.y / height).coerceIn(0f, 1f)
                                drag(down.id) { change ->
                                    dragFraction = (change.position.y / height).coerceIn(0f, 1f)
                                    change.consume()
                                }
                            } finally {
                                dragging = false
                            }
                        }
                    },
        ) {
            Box(
                modifier =
                    Modifier
                        .offset {
                            IntOffset(x = (touchWidthPx - thumbWidthPx) / 2, y = thumbTop())
                        }.size(THUMB_WIDTH, THUMB_HEIGHT)
                        .graphicsLayer { alpha = railAlpha }
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }

        // Blase neben dem Griff, zur Innenseite hin. Mit `end`-Abstand bleibt sie in RTL-Layouts auf der richtigen Seite.
        val text = bubble
        if (text.isNotEmpty()) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset {
                            IntOffset(x = 0, y = thumbTop() + thumbHeightPx / 2 - bubbleHeightPx / 2)
                        }.padding(end = TOUCH_WIDTH + BUBBLE_GAP)
                        .onSizeChanged { bubbleHeightPx = it.height }
                        .graphicsLayer { alpha = railAlpha }
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
