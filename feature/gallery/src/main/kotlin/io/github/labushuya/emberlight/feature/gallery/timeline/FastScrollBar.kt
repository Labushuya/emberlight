package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
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
import kotlin.math.abs
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
     * Ob die Leiste erscheint. Der Inhalt wird aus Zellenzahl, Spalten und Zellhöhe geschätzt. Überschriften
     * fehlen in der
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

/** Testmarke des Griffs der Schnell-Scrollleiste. */
internal const val FAST_SCROLL_GRIP_TAG = "fast-scroll-grip"

/** Breite der Trefferfläche des Griffs am Ende-Rand. Nur so breit wie nötig, damit Kacheln daneben erreichbar bleiben. */
internal val FAST_SCROLL_GRIP_WIDTH = 32.dp

/** Höhe des Griffs und seiner Trefferfläche. */
internal val FAST_SCROLL_GRIP_HEIGHT = 48.dp
private val THUMB_WIDTH = 4.dp
private val BUBBLE_GAP = 8.dp

/**
 * Zustand der Schnell-Scrollleiste. Das Raster hält ihn, damit der Griff (Anzeige) und die Geste ([fastScrollGrip]) dieselben
 * Werte lesen. [active] ist `true`, solange die Leiste sichtbar ist oder gezogen wird.
 */
@Stable
internal class FastScrollState(
    val gridState: LazyGridState,
) {
    var railVisible by mutableStateOf(false)
    var dragging by mutableStateOf(false)
    var dragFraction by mutableFloatStateOf(0f)
    var trackHeightPx by mutableIntStateOf(0)

    /** Spalten und Zellhöhe des Rasters, vom Raster bei jeder Komposition gesetzt. Die Geste liest sie beim Ereignis. */
    var columns: Int = 1
    var cellPx: Float = 0f

    val active: Boolean get() = railVisible || dragging

    /** Anteil des Griffs: beim Ziehen die Fingerposition, sonst die aktuelle Scrollposition. */
    fun thumbFraction(): Float =
        if (dragging) {
            dragFraction
        } else {
            FastScrollMapping.fractionFor(gridState.firstVisibleItemIndex, gridState.layoutInfo.totalItemsCount)
        }

    /** Oberkante des Griffs in Pixeln, im Koordinatensystem der Leiste. */
    fun thumbTopPx(thumbHeightPx: Int): Int =
        (thumbFraction() * (trackHeightPx - thumbHeightPx).coerceAtLeast(0)).roundToInt()
}

/**
 * Geste des Griffs. Liegt sie auf dem Griff und überschreitet das Ziehen den Touch-Slop, übernimmt die Leiste sie und
 * verschiebt die Liste. Sonst bleibt alles unverändert: Ein Antippen und Ziehen außerhalb des Griffs gehen an die Kacheln
 * bzw. das Raster.
 *
 * Die Geste hängt an einem gemeinsamen Vorfahren von Raster und Leiste und wertet in der Initial-Phase aus. Der Griff selbst
 * hat keinen Eingabe-Knoten, sonst würde er die Kachel darunter verdecken: Compose verteilt eine Berührung nur an das oberste
 * Geschwister mit Eingabe. Ist die Leiste ausgeblendet, endet die Geste sofort, ohne Treffer.
 *
 * Spalten und Zellhöhe liest die Geste aus [FastScrollState], das Raster schreibt sie bei jeder Komposition hinein.
 */
internal fun Modifier.fastScrollGrip(
    state: FastScrollState,
    gripWidthPx: Int,
    gripHeightPx: Int,
): Modifier =
    pointerInput(state, gripWidthPx, gripHeightPx) {
        val touchSlop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
            val eligible =
                FastScrollMapping.isUseful(
                    totalItems = state.gridState.layoutInfo.totalItemsCount,
                    columns = state.columns,
                    cellPx = state.cellPx,
                    viewportPx =
                        state.gridState.layoutInfo.viewportSize.height
                            .toFloat(),
                )
            if (state.active && eligible) {
                val top = state.thumbTopPx(gripHeightPx).toFloat()
                val onGrip =
                    down.position.x >= size.width - gripWidthPx &&
                        down.position.y >= top &&
                        down.position.y <= top + gripHeightPx
                if (onGrip) {
                    dragOnGrip(state, down, top, size.height.toFloat(), gripHeightPx, touchSlop)
                }
            }
        }
    }

/**
 * Zieht den Griff mit dem Finger, nachdem der Touch-Slop überschritten ist. Der Griff folgt dem Finger 1:1 ab der
 * Stelle, an der
 * er gepackt wurde. Ohne Überschreitung wird nichts verbraucht.
 */
private suspend fun AwaitPointerEventScope.dragOnGrip(
    state: FastScrollState,
    down: PointerInputChange,
    startTop: Float,
    trackHeight: Float,
    gripHeightPx: Int,
    touchSlop: Float,
) {
    val range = (trackHeight - gripHeightPx).coerceAtLeast(1f)
    var dragStarted = false
    try {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val travel = change.position.y - down.position.y
            if (!dragStarted) {
                if (abs(travel) <= touchSlop) continue
                dragStarted = true
                state.dragging = true
            }
            state.dragFraction = ((startTop + travel) / range).coerceIn(0f, 1f)
            change.consume()
        }
    } finally {
        if (dragStarted) state.dragging = false
    }
}

/**
 * Schnell-Scrollleiste am Ende-Rand. Erscheint beim Scrollen, bleibt sichtbar, solange gezogen wird, und blendet
 * danach aus.
 * Beim Ziehen zeigt eine Blase den Monat (Tagesstufen) oder das Jahr (Monatsstufen) an der Position.
 *
 * Gezeichnet werden der Griff und die Blase. Die Geste liegt in [fastScrollGrip] am Raster, der Griff selbst nimmt keine
 * Berührung an. Die Bedienungshilfen wirken über die Semantik des Griffs, unabhängig davon, ob die Leiste gerade sichtbar ist.
 *
 * Der Griff und die Blase werden über Lambda-Modifikatoren positioniert und lesen den Scrollzustand dort. Der
 * Körper der
 * Funktion liest nur abgeleitete Zustände, daher läuft beim Scrollen keine Recomposition des Rasters.
 */
@Composable
internal fun FastScrollBar(
    state: FastScrollState,
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
    val gridState = state.gridState
    val gripHeightPx = with(density) { FAST_SCROLL_GRIP_HEIGHT.roundToPx() }

    var bubbleHeightPx by remember { mutableIntStateOf(0) }
    val railAlpha by animateFloatAsState(if (state.active) 1f else 0f, label = "fastScrollAlpha")

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
    // Der Blasentext liest nur das Ziehen und den Index, nicht die Layout-Information des Rasters. Ein abgeleiteter Zustand
    // meldete die Änderung hier nicht zuverlässig an die Komposition der Leiste.
    val bubble: () -> String = {
        if (!state.dragging) {
            ""
        } else {
            val target = FastScrollMapping.indexFor(state.dragFraction, index.totalItems)
            index.dateAt(target)?.let { formats.scrollLabel(mode, it) } ?: ""
        }
    }

    // Sichtbarkeit: beim Scrollen und Ziehen an, danach nach kurzer Ruhe aus.
    LaunchedEffect(state) {
        snapshotFlow { gridState.isScrollInProgress || state.dragging }
            .collectLatest { active ->
                if (active) {
                    state.railVisible = true
                } else {
                    delay(FastScrollMapping.HIDE_DELAY_MS)
                    state.railVisible = false
                }
            }
    }
    // Während des Ziehens springt das Raster zum Ziel. `conflate` hält nur den neuesten Wert, damit nie zwei Sprünge
    // gleichzeitig laufen. Das drosselt auch bei 50.000 Einträgen.
    LaunchedEffect(state) {
        snapshotFlow {
            if (state.dragging) {
                FastScrollMapping.indexFor(
                    state.dragFraction,
                    gridState.layoutInfo.totalItemsCount,
                )
            } else {
                null
            }
        }.filterNotNull()
            .distinctUntilChanged()
            .conflate()
            .collect { target -> gridState.scrollToItem(target) }
    }
    // Leichtes Tick, wenn die Blase den Monat oder das Jahr wechselt. Nicht beim Beginn des Ziehens.
    LaunchedEffect(index, mode, formats) {
        var previous = ""
        snapshotFlow { bubble() }.collect { text ->
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

    Box(
        modifier =
            modifier
                .fillMaxSize()
                // Der Rand oben und unten liegt im Padding des Rasters (Kopfzeile, Navigation). Hier nur der Ende-Rand.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End))
                .onSizeChanged { state.trackHeightPx = it.height },
    ) {
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(x = 0, y = state.thumbTopPx(gripHeightPx)) }
                    .size(FAST_SCROLL_GRIP_WIDTH, FAST_SCROLL_GRIP_HEIGHT)
                    .testTag(FAST_SCROLL_GRIP_TAG)
                    .semantics {
                        contentDescription = label
                        progressBarRangeInfo =
                            ProgressBarRangeInfo(
                                current = state.thumbFraction(),
                                range = 0f..1f,
                            )
                        setProgress { target ->
                            val to = FastScrollMapping.indexFor(target, gridState.layoutInfo.totalItemsCount)
                            scope.launch { gridState.scrollToItem(to) }
                            true
                        }
                    },
        ) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(THUMB_WIDTH, FAST_SCROLL_GRIP_HEIGHT)
                        .graphicsLayer { alpha = railAlpha }
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }

        // Blase neben dem Griff, zur Innenseite hin. Mit `end`-Abstand bleibt sie in RTL-Layouts auf der richtigen Seite.
        val text = bubble()
        if (text.isNotEmpty()) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset {
                            IntOffset(x = 0, y = state.thumbTopPx(gripHeightPx) + gripHeightPx / 2 - bubbleHeightPx / 2)
                        }.padding(end = FAST_SCROLL_GRIP_WIDTH + BUBBLE_GAP)
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
