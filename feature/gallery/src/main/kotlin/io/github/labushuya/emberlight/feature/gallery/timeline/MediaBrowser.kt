package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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

/** Höchstens so viele Frames wartet das Raster auf sein neues Layout, bevor die Grenzen gelesen werden (ADR 0010). */
internal const val MAX_LAYOUT_FRAMES = 2

/**
 * Was das Raster dem Gastgeber über den Viewer mitteilt. [open] öffnet das Vollbild eines Elements aus dem Raster. Die Kachel
 * wird vorher vollständig sichtbar gemacht, damit die Hero-Animation an der echten Kachel beginnt. [gridAlpha] ist die
 * Deckkraft des Rasters: Solange der Viewer ruhig und vollständig deckend steht, zeichnet das Raster nicht (ADR 0010).
 */
@Stable
internal class MediaBrowserState(
    private val overlay: ViewerOverlayState,
    private val scope: CoroutineScope,
    private val reveal: suspend (Long) -> Rect?,
) {
    fun open(item: MediaItem) {
        scope.launch {
            // Erst die Kachel vollständig sichtbar machen, dann ihre aktuellen Grenzen an das Overlay geben.
            val tile = reveal(item.id)
            overlay.open(item.id, tile)
        }
    }

    val gridAlpha: Float get() = if (overlay.phase == OverlayPhase.OPEN) 0f else 1f
}

/**
 * Raster und Vollbild-Viewer als ein Ziel (ADR 0010). [grid] zeichnet das Raster und bekommt den [MediaBrowserState] und einen
 * Modifier mit der Deckkraft. Das Overlay liegt darüber und nutzt dieselbe Liste wie das Raster: [content] ist der Zustand des
 * Rasters, daraus entstehen Reihenfolge und Live-Updates. Ist nichts mehr zu zeigen, schließt das Overlay ohne Animation.
 *
 * Wechselt der Pager die Seite, scrollt das Raster ohne Animation so, dass die Kachel des gewählten Elements vollständig unter
 * der Kopfzeile und über der Leiste liegt. Vor dem Schließen geschieht dasselbe, und die Grenzen werden erst nach dem Layout
 * gelesen. Damit hat der Rückweg ein Ziel.
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
    val chrome = LocalNavigationChrome.current
    val overlay = rememberViewerOverlayState(cellRect = cells::rectOf)
    val scope = rememberCoroutineScope()
    val bottomLimit = { chrome?.bottomBarTopPx }
    val reveal: suspend (
        Long,
    ) -> Rect? = remember(content, gridState) { { id -> revealTile(content, gridState, cells, bottomLimit, id) } }
    // Vor dem Schließen wird dieselbe Sicherung wie beim Öffnen ausgeführt. Der Halter zeigt immer auf den aktuellen Stand.
    SideEffect { overlay.beforeClose = reveal }
    val browser = remember(overlay, scope, reveal) { MediaBrowserState(overlay, scope, reveal) }
    val items = remember(content?.sections) { content?.sections?.flatMap { it.items }.orEmpty() }
    val viewerItems: List<ViewerItem> = remember(items) { items.map { it.toViewerItem() } }
    val shownId = overlay.shownId
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
        revealTile(content, gridState, cells, bottomLimit, id)
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
 * Bringt die Kachel des Elements [id] vollständig unter die Kopfzeile und über die Leiste und liefert ihre aktuellen Grenzen.
 * Liegt sie schon so, geschieht nichts. Sonst fordert das Raster ohne Animation an und wartet höchstens [MAX_LAYOUT_FRAMES]
 * Frames auf das neue Layout. Erst danach gelten die Grenzen als aktuell (Zählerstand seit der Anforderung).
 *
 * `null`, wenn die Kachel danach nicht vollständig sichtbar ist, etwa weil sie nicht im Raster liegt. Der Übergang blendet dann
 * zur Mitte aus.
 */
internal suspend fun revealTile(
    content: MediaGridState.Content?,
    gridState: LazyGridState,
    cells: CellBounds,
    bottomLimit: () -> Float?,
    id: Long,
): Rect? {
    fun visible(rect: Rect?): Boolean {
        val viewport = cells.viewport ?: return false
        return rect != null && isTileFullyVisible(rect, viewport, bottomLimit())
    }
    val target = targetIndex(content, id) ?: return cells.rectOf(id).takeIf { visible(it) }
    if (visible(cells.rectOf(id))) return cells.rectOf(id)
    val before = cells.stamp
    gridState.requestScrollToItem(target)
    repeat(MAX_LAYOUT_FRAMES) {
        withFrameNanos { }
        if (cells.stampOf(id) > before && visible(cells.rectOf(id))) return cells.rectOf(id)
    }
    return cells.rectOf(id).takeIf { visible(it) }
}

/** Position der Kachel [id] im Raster, `null` ohne Raster oder ohne Element. */
private fun targetIndex(
    content: MediaGridState.Content?,
    id: Long,
): Int? {
    val grid = content ?: return null
    val banner = if (needsAccessBanner(grid.access, grid.missingTypes)) 1 else 0
    val index = TimelineIndex(grid.sections, banner, grid.today)
    return gridScrollTarget(grid.sections, index, id)
}
