package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.feature.gallery.R
import io.github.labushuya.emberlight.feature.gallery.viewer.LastViewedMediaId
import java.time.ZoneId

/** Abstand zwischen Zellen und an den Rändern des Rasters. */
internal val GRID_GAP: Dp = ZoomLevels.GRID_GAP_DP.dp

private const val TYPE_HEADER = "header"
private const val TYPE_BANNER = "banner"
private const val KEY_BANNER = "access-banner"

/**
 * Das gemeinsame Raster von Galerie und Album: Überschriften, Medien, Zoom mit Anker, Schnell-Scrollleiste und Hinweis-
 * Banner. Zeichnet den Zustand [state] und meldet Aktionen nach außen. Die Spaltenzahl folgt aus der Breite und der
 * Zoomstufe des Zustands. Eine Stufenänderung (Pinch oder Bedienungshilfe) geht über [onZoomLevelChange].
 *
 * [gridState] gehört dem Aufrufer, damit die Scrollposition den Reiter- oder Ansichtswechsel übersteht.
 */
@Composable
internal fun MediaGrid(
    state: MediaGridState.Content,
    padding: PaddingValues,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    onZoomLevelChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    lastViewed: LastViewedMediaId = LastViewedMediaId.None,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val density = LocalDensity.current
    val zoom = remember { PinchZoomState() }
    val scope = rememberCoroutineScope()
    val currentLevel = rememberUpdatedState(state.zoomLevel)
    val locale = LocalConfiguration.current.locales[0]
    val formats = remember(locale) { TimelineFormats(locale, ZoneId.systemDefault()) }
    val hasBanner = needsAccessBanner(state.access, state.missingTypes)
    val index =
        remember(state.sections, hasBanner) { TimelineIndex(state.sections, if (hasBanner) 1 else 0, state.today) }

    // Das zuletzt im Viewer angesehene Element. Liegt es außerhalb des sichtbaren Bereichs, scrollt das Raster dorthin.
    // Die Meldung wird danach gelöscht, damit ein späteres Wiederkommen nicht erneut springt.
    val lastViewedId = lastViewed.mediaId
    LaunchedEffect(lastViewedId, index) {
        if (lastViewedId != null) {
            val target = gridScrollTarget(state.sections, index, lastViewedId)
            if (target != null && gridState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
                gridState.scrollToItem(target)
            }
            lastViewed.onHandled()
        }
    }
    val mode = ZoomLevels.groupingFor(state.zoomLevel)
    val gapPx = with(density) { GRID_GAP.toPx() }
    val paddingTopPx = with(density) { (padding.calculateTopPadding() + GRID_GAP).toPx() }
    val enlarge = stringResource(R.string.feature_gallery_zoom_in)
    val shrink = stringResource(R.string.feature_gallery_zoom_out)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val columns = ZoomLevels.columnsFor(state.zoomLevel, maxWidth.value)
        val tilePx = ZoomLevels.tileWidth(widthPx, gapPx, columns)
        val cellPx = tilePx + gapPx

        // Nach dem Stufenwechsel steht das neue Raster in der Komposition. Dann wird der Anker gesetzt, im selben Frame.
        SideEffect {
            zoom.resolve(
                level = state.zoomLevel,
                tileNewPx = tilePx,
                paddingTopPx = paddingTopPx,
                index = index,
                gridState = gridState,
            )
        }

        val actions =
            remember(state.zoomLevel, widthPx, gapPx, enlarge, shrink, onZoomLevelChange) {
                listOf(
                    CustomAccessibilityAction(enlarge) {
                        stepZoomWithAnchor(
                            zoom = zoom,
                            gridState = gridState,
                            from = state.zoomLevel,
                            delta = -1,
                            widthPx = widthPx,
                            gapPx = gapPx,
                            densityValue = density.density,
                            onZoomLevelChange = onZoomLevelChange,
                        )
                    },
                    CustomAccessibilityAction(shrink) {
                        stepZoomWithAnchor(
                            zoom = zoom,
                            gridState = gridState,
                            from = state.zoomLevel,
                            delta = 1,
                            widthPx = widthPx,
                            gapPx = gapPx,
                            densityValue = density.density,
                            onZoomLevelChange = onZoomLevelChange,
                        )
                    },
                )
            }
        val columnsDescription = pluralStringResource(R.plurals.feature_gallery_zoom_columns, columns, columns)

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .pinchZoom(
                        zoom = zoom,
                        gridState = gridState,
                        level = currentLevel,
                        onLevelChange = onZoomLevelChange,
                        scope = scope,
                    ).graphicsLayer {
                        // Nur diese Ebene wird skaliert. Der Ankerpunkt liegt unter der Fingermitte.
                        val s = zoom.scale
                        scaleX = s
                        scaleY = s
                        val w = size.width
                        val h = size.height
                        transformOrigin =
                            TransformOrigin(
                                if (w > 0f) zoom.pivotX / w else 0.5f,
                                if (h > 0f) zoom.pivotY / h else 0.5f,
                            )
                        translationX = zoom.panX
                        translationY = zoom.panY
                        clip = true
                    }.semantics {
                        customActions = actions
                        stateDescription = columnsDescription
                    },
            contentPadding =
                PaddingValues(
                    start = GRID_GAP,
                    end = GRID_GAP,
                    top = padding.calculateTopPadding() + GRID_GAP,
                    bottom = padding.calculateBottomPadding() + GRID_GAP,
                ),
            horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
            verticalArrangement = Arrangement.spacedBy(GRID_GAP),
        ) {
            if (hasBanner) {
                accessBannerItem(
                    key = KEY_BANNER,
                    contentType = TYPE_BANNER,
                    access = state.access,
                    missingTypes = state.missingTypes,
                    onRequest = onRequest,
                    onOpenSettings = onOpenSettings,
                )
            }
            state.sections.forEach { section ->
                item(key = section.key, span = { GridItemSpan(maxLineSpan) }, contentType = TYPE_HEADER) {
                    SectionHeader(header = section.header, formats = formats)
                }
                items(
                    count = section.items.size,
                    key = { position -> section.items[position].uri },
                    contentType = { CONTENT_TYPE_MEDIA },
                ) { position ->
                    val item = section.items[position]
                    MediaCell(
                        item = item,
                        label = mediaLabel(item, formats),
                        onClick = { onMediaClick(item) },
                    )
                }
            }
        }

        FastScrollBar(
            gridState = gridState,
            index = index,
            mode = mode,
            formats = formats,
            columns = columns,
            cellPx = cellPx,
        )
    }
}

/**
 * Schritt über die Bedienungshilfen. Wirkt wie der Pinch, ohne Geste: Der Anker ist die Zelle in der Mitte des Rasters, die
 * Ansicht bleibt ohne Transformation. Gibt `false` zurück, wenn die Grenze der Stufen erreicht ist.
 */
private fun stepZoomWithAnchor(
    zoom: PinchZoomState,
    gridState: LazyGridState,
    from: Int,
    delta: Int,
    widthPx: Float,
    gapPx: Float,
    densityValue: Float,
    onZoomLevelChange: (Int) -> Unit,
): Boolean {
    val target = from + delta
    if (target < ZoomLevels.MIN_LEVEL || target > ZoomLevels.MAX_LEVEL) return false
    val viewport = gridState.layoutInfo.viewportSize
    val centerX = viewport.width / 2f
    val centerY = viewport.height / 2f
    zoom.stopSettling()
    zoom.scale = 1f
    zoom.panX = 0f
    zoom.panY = 0f
    zoom.pending =
        planLevelChange(
            gridState = gridState,
            anchorX = centerX,
            anchorY = centerY,
            pivotX = centerX,
            pivotY = centerY,
            fromLevel = from,
            toLevel = target,
            widthPx = widthPx,
            gapPx = gapPx,
            densityValue = densityValue,
            visualScale = null,
        )
    onZoomLevelChange(target)
    return true
}

@Composable
private fun SectionHeader(
    header: TimelineHeader,
    formats: TimelineFormats,
) {
    val text =
        when (header) {
            TimelineHeader.Today -> stringResource(R.string.feature_gallery_header_today)
            TimelineHeader.Yesterday -> stringResource(R.string.feature_gallery_header_yesterday)
            is TimelineHeader.Day -> formats.dayHeader(header.date, header.withYear)
            is TimelineHeader.Month -> formats.monthHeader(header.yearMonth)
        }
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 20.dp, bottom = 8.dp)
                .semantics { heading() },
    )
}

@Composable
private fun mediaLabel(
    item: MediaItem,
    formats: TimelineFormats,
): String {
    val date = formats.longDate(item.timestamp)
    return if (item.type == MediaType.VIDEO) {
        stringResource(R.string.feature_gallery_cell_video, formatDuration(item.durationMs ?: 0L), date)
    } else {
        stringResource(R.string.feature_gallery_cell_photo, date)
    }
}
