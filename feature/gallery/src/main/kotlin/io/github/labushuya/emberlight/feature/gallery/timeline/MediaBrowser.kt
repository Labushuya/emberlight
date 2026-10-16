package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import io.github.labushuya.emberlight.core.design.motion.LocalNavigationChrome
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.feature.gallery.viewer.OverlayPhase
import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerItem
import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerOverlay
import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerOverlayState
import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerPlaybackViewModel
import io.github.labushuya.emberlight.feature.gallery.viewer.rememberViewerOverlayState
import io.github.labushuya.emberlight.feature.gallery.viewer.toViewerItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Was das Raster dem Gastgeber über den Viewer mitteilt. [open] öffnet das Vollbild eines Elements aus dem Raster. Die Kachel
 * wird vorher vollständig sichtbar gemacht, damit die Hero-Animation an der echten Kachel beginnt. [gridAlpha] ist die
 * Deckkraft des Rasters: Solange der Viewer ruhig und vollständig deckend steht, zeichnet das Raster nicht (ADR 0010).
 */
@Stable
internal class MediaBrowserState(
    private val overlay: ViewerOverlayState,
    private val scope: CoroutineScope,
    private val reveal: suspend (Long) -> Unit,
) {
    fun open(item: MediaItem) {
        scope.launch {
            reveal(item.id)
            overlay.open(item.id)
        }
    }

    val gridAlpha: Float get() = if (overlay.phase == OverlayPhase.OPEN) 0f else 1f
}

/**
 * Raster und Vollbild-Viewer als ein Ziel (ADR 0010). [grid] zeichnet das Raster und bekommt den [MediaBrowserState] und einen
 * Modifier mit der Deckkraft. Das Overlay liegt darüber und nutzt dieselbe Liste wie das Raster: [content] ist der Zustand des
 * Rasters, daraus entstehen Reihenfolge und Live-Updates. Ist nichts mehr zu zeigen, schließt das Overlay ohne Animation.
 *
 * Wechselt der Pager die Seite, scrollt das Raster ohne Animation so, dass die Kachel des gewählten Elements vollständig zu
 * sehen ist. Damit hat der Rückweg immer ein Ziel.
 */
@Composable
// Der Wiedergabe-Halter gehört dem Ziel und wird nur bis zum Overlay weitergereicht (ADR 0010).
@Suppress("ktlint:compose:vm-forwarding-check")
internal fun MediaBrowser(
    content: MediaGridState.Content?,
    gridState: LazyGridState,
    playback: ViewerPlaybackViewModel,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    grid: @Composable (browser: MediaBrowserState, gridModifier: Modifier) -> Unit,
) {
    val cells = remember { CellBounds() }
    val overlay = rememberViewerOverlayState(cellRect = cells::rectOf)
    val scope = rememberCoroutineScope()
    val reveal: suspend (Long) -> Unit = remember(content, gridState) { { id -> revealInGrid(content, gridState, id) } }
    val browser = remember(overlay, scope, reveal) { MediaBrowserState(overlay, scope, reveal) }
    val items = remember(content?.sections) { content?.sections?.flatMap { it.items }.orEmpty() }
    val viewerItems: List<ViewerItem> = remember(items) { items.map { it.toViewerItem() } }
    val shownId = overlay.shownId
    val chrome = LocalNavigationChrome.current
    val viewerOpen = shownId != null
    // Solange der Viewer offen ist, blendet die App die Navigation aus (ADR 0010). Schließen gibt sie wieder frei.
    DisposableEffect(viewerOpen, chrome) {
        chrome?.hidden = viewerOpen
        onDispose { chrome?.hidden = false }
    }

    // Nichts mehr zu zeigen (etwa Berechtigung entzogen): das Overlay schließt ohne Animation.
    LaunchedEffect(viewerItems.isEmpty()) {
        if (viewerItems.isEmpty() && overlay.shownId != null) overlay.forceClose()
    }
    // Das Raster folgt dem Viewer: Die Kachel des gewählten Elements soll vollständig sichtbar sein.
    LaunchedEffect(shownId) {
        val id = shownId ?: return@LaunchedEffect
        revealInGrid(content, gridState, id)
    }

    Box(modifier = modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalCellBounds provides cells) {
            grid(browser, Modifier.fillMaxSize().graphicsLayer { alpha = browser.gridAlpha })
        }
        if (shownId != null && viewerItems.isNotEmpty()) {
            ViewerOverlay(overlay = overlay, items = viewerItems, playback = playback, zone = zone)
        }
    }
}

/**
 * Scrollt das Raster ohne Animation, bis die Kachel des Elements [id] vollständig sichtbar ist. Ohne Raster oder ohne Element
 * geschieht nichts. Liegt die Kachel schon vollständig im Bild, bleibt die Position unverändert.
 */
private suspend fun revealInGrid(
    content: MediaGridState.Content?,
    gridState: LazyGridState,
    id: Long,
) {
    val grid = content ?: return
    val banner = if (needsAccessBanner(grid.access, grid.missingTypes)) 1 else 0
    val index = TimelineIndex(grid.sections, banner, grid.today)
    val target = gridScrollTarget(grid.sections, index, id) ?: return
    if (!isFullyVisible(gridState, target)) gridState.scrollToItem(target)
}

/** Liegt die Kachel der Position [target] vollständig im sichtbaren Bereich des Rasters? */
private fun isFullyVisible(
    gridState: LazyGridState,
    target: Int,
): Boolean {
    val layout = gridState.layoutInfo
    val info = layout.visibleItemsInfo.firstOrNull { it.index == target } ?: return false
    return info.offset.y >= layout.viewportStartOffset && info.offset.y + info.size.height <= layout.viewportEndOffset
}
