package io.github.labushuya.emberlight.feature.gallery.viewer

import android.content.Context
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import io.github.labushuya.emberlight.feature.gallery.R
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFormats
import io.github.labushuya.emberlight.feature.gallery.timeline.formatDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.ZoneId
import kotlin.math.roundToInt

/** Abstand zwischen zwei Seiten. */
private val PAGE_SPACING = 16.dp

/** Markierung des Overlays für Tests (Compose-Tests zählen offene Viewer über sie). */
internal const val VIEWER_OVERLAY_TAG = "viewer-overlay"

// Dauer der Meldung „nicht abspielbar“ und ihr Abstand zum Rand.
private const val UNPLAYABLE_MS = 3_000L
private val UNPLAYABLE_BOTTOM = 96.dp
private val UNPLAYABLE_CORNER = 8.dp
private val UNPLAYABLE_BACKGROUND = Color(0xCC000000)

/**
 * Vollbild-Overlay über dem Raster (ADR 0010). Der Pager zeigt die Elemente in Rasterreihenfolge, die Hero-Animation zeichnet
 * das Bild beim Öffnen und Schließen selbst. Der Hintergrund ist schwarz und blendet mit dem Fortschritt aus, sodass das
 * Raster beim Schließen und Wegwischen durchscheint.
 *
 * [items] ist dieselbe geordnete Liste wie im Raster des Gastgebers. [overlay] trägt Phase und Zustand. Das gewählte Element
 * ist [ViewerOverlayState.shownId]; wechselt der Pager die Seite, meldet er es zurück, und das Raster folgt (siehe
 * MediaBrowser).
 */
@Composable
internal fun ViewerOverlay(
    overlay: ViewerOverlayState,
    items: List<ViewerItem>,
    playback: ViewerPlaybackViewModel,
    modifier: Modifier = Modifier,
    imageSource: ViewerImageSource = ContentUriImageSources,
    initialChromeVisible: Boolean = true,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val id = overlay.shownId ?: return
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val titles = remember(locale, zone) { ViewerFormats(locale, zone) }
    val dates = remember(locale, zone) { TimelineFormats(locale, zone) }
    val lastIndex = remember { IntArray(1) }
    val index = resolveViewerPosition(items, id, lastIndex[0]).index
    lastIndex[0] = index
    val item = items[index]
    val itemId = item.id
    // Hero-Bitmap und Seitenverhältnis kommen aus dem Speicher-Cache, wie die Kachel selbst (ADR 0008).
    val heroBitmap = remember(itemId, item.previewKey) { cachedPreviewBitmap(context, item.previewKey) }
    val aspect = remember(itemId, item.previewKey, item.width, item.height) { heroAspect(context, item, heroBitmap) }

    val pagerState = rememberPagerState(initialPage = index, pageCount = { items.size })
    val latestOverlay by rememberUpdatedState(overlay)
    val latestPlayback by rememberUpdatedState(playback)
    val playbackState by playback.playback.collectAsStateWithLifecycle()
    val muted by playback.muted.collectAsStateWithLifecycle()
    val unplayable by playback.unplayable.collectAsStateWithLifecycle()
    LaunchedEffect(unplayable) {
        if (unplayable) {
            delay(UNPLAYABLE_MS)
            playback.onUnplayableShown()
        }
    }
    var chromeVisible by rememberSaveable { mutableStateOf(initialChromeVisible) }

    // Der Pager folgt dem gewählten Element, etwa nachdem es verschwunden ist und der Nachbar nachrückt.
    LaunchedEffect(index) {
        if (index < pagerState.pageCount && index != pagerState.currentPage) pagerState.scrollToPage(index)
    }
    // Eingerastete Seiten melden: Das Overlay merkt sich das Element, die Wiedergabe löst die anderen Seiten.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                items.getOrNull(page)?.let { settled ->
                    latestOverlay.select(settled.id)
                    latestPlayback.onPageSettled(settled.id)
                }
            }
    }
    // Schließen beginnt: Die Wiedergabe pausiert, ein Video startet nie von selbst wieder.
    LaunchedEffect(overlay.phase) {
        if (overlay.phase == OverlayPhase.CLOSING) latestPlayback.onStopped()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playback.onStopped() }

    // Die System-Rückgeste steuert dieselbe Ansicht wie das Ziehen: Abbrechen federt zurück, Vollenden schließt (ADR 0010).
    PredictiveBackHandler { progress ->
        try {
            progress.collect { event -> latestOverlay.backProgressed(event.progress) }
            latestOverlay.backCommitted()
        } catch (e: CancellationException) {
            latestOverlay.backCancelled()
        }
    }

    val phase = overlay.phase
    val title = titles.title(item.timestamp)
    val label = mediaLabel(item, dates)
    val durationText = item.takeIf { it.type == ViewerMediaType.VIDEO }?.durationMs?.let(::formatDuration)
    // Während des Ziehens sind die Leisten aus. Nur die Seite und der Grund bewegen sich.
    val chromeShown = chromeVisible && phase != OverlayPhase.DRAGGING && phase != OverlayPhase.CLOSING
    val attachedHere = playbackState.attachedId == itemId
    val video =
        rememberVideoPlaybackUi(
            player = if (playbackState.attachedId != null) playback.player else null,
            fallbackDurationMs = item.durationMs,
            onPlaybackEnd = playback::onPlaybackEnded,
            onPlaybackError = playback::onPlaybackFailed,
        )
    val settled = overlay.phase.pagerShown

    ImmersiveSystemBars(immersive = !chromeVisible)

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .testTag(VIEWER_OVERLAY_TAG)
                .onGloballyPositioned { coords ->
                    overlay.originInRoot = coords.positionInRoot()
                    overlay.heightPx = coords.size.height.toFloat()
                    overlay.fit = fitInside(coords.size.width.toFloat(), coords.size.height.toFloat(), aspect)
                }.drawBehind {
                    drawRect(
                        Color.Black,
                        alpha = backdropAlpha(overlay.phase, overlay.progress, overlay.dragY, overlay.heightPx),
                    )
                },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { pagerTransform(overlay, heroBitmap != null) },
            key = { page -> items[page].id },
            beyondViewportPageCount = 1,
            pageSpacing = PAGE_SPACING,
        ) { page ->
            val pageItem = items[page]
            val pageLabel = mediaLabel(pageItem, dates)
            when (pageItem.type) {
                ViewerMediaType.IMAGE -> {
                    ViewerImagePage(
                        item = pageItem,
                        contentDescription = pageLabel,
                        imageSource = imageSource,
                        overlay = overlay,
                        settled = settled,
                        onTap = { chromeVisible = !chromeVisible },
                    )
                }

                ViewerMediaType.VIDEO -> {
                    val attached = playbackState.attachedId == pageItem.id
                    ViewerVideoPage(
                        item = pageItem,
                        label = pageLabel,
                        attached = attached,
                        player = if (attached) playback.player else null,
                        video = if (attached) video else VideoPlaybackUi.idle(pageItem.durationMs),
                        overlay = overlay,
                        settled = settled,
                        onPlay = { playback.onPlayPressed(pageItem.id, pageItem.uri) },
                        onTap = { chromeVisible = !chromeVisible },
                    )
                }
            }
        }

        HeroLayer(overlay = overlay, bitmap = heroBitmap)

        AnimatedVisibility(
            visible = chromeShown,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ViewerTopBar(title = title, durationText = durationText, onBack = overlay::close)
        }

        AnimatedVisibility(
            visible = chromeShown && item.type == ViewerMediaType.VIDEO,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ViewerBottomScrim {
                VideoControls(
                    video = if (attachedHere) video else VideoPlaybackUi.idle(item.durationMs),
                    playRequested = attachedHere && playbackState.playRequested,
                    attached = attachedHere,
                    muted = muted,
                    onPlayPause = {
                        when {
                            attachedHere && playbackState.playRequested -> playback.onPausePressed()
                            else -> playback.onPlayPressed(item.id, item.uri)
                        }
                    },
                    onSeek = playback::onSeek,
                    onToggleMute = playback::onToggleMute,
                )
            }
        }

        // Eine kurze Meldung statt stiller Untätigkeit, etwa bei Demo-Medien, die sich nicht abspielen lassen.
        AnimatedVisibility(
            visible = unplayable,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = UNPLAYABLE_BOTTOM),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Text(
                text = stringResource(R.string.feature_gallery_viewer_demo_unplayable),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier =
                    Modifier
                        .background(UNPLAYABLE_BACKGROUND, RoundedCornerShape(UNPLAYABLE_CORNER))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * Hintergrund des Overlays: voll deckend im Ruhezustand, ausblendend beim Schließen und beim Wegwischen. Der Grund zeigt das
 * Raster darunter, sobald er transparent wird.
 */
internal fun backdropAlpha(
    phase: OverlayPhase,
    progress: Float,
    dragY: Float,
    heightPx: Float,
): Float {
    if (phase == OverlayPhase.OPEN) return 1f
    return progress.coerceIn(0f, 1f) * (1f - dismissProgress(dragY, heightPx))
}

/** Seitenverhältnis des Fit-Rechtecks: aus dem Vorschaubild, sonst aus dem MediaStore. `null`, wenn unbekannt. */
private fun heroAspect(
    context: Context,
    item: ViewerItem,
    bitmap: ImageBitmap?,
): Float? {
    if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) return bitmap.width.toFloat() / bitmap.height.toFloat()
    return previewAspect(context, item)
}

/** Größtes Rechteck mit [aspect] in einem Bereich der Größe [width] mal [height], zentriert. Ohne Verhältnis: der ganze Bereich. */
private fun fitInside(
    width: Float,
    height: Float,
    aspect: Float?,
): Rect {
    val bounds = Rect(Offset.Zero, Size(width, height))
    return if (aspect == null) bounds else fitRect(bounds, aspect)
}

/** Schrumpf-Transformation des Pagers: Der Pager wird so gelegt, dass das Fit-Bild zur Hero-Position passt. */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.pagerTransform(
    overlay: ViewerOverlayState,
    hasHero: Boolean,
) {
    val phase = overlay.phase
    val fit = overlay.fit
    val tile = overlay.heroTile?.translate(-overlay.originInRoot)
    val k = overlay.progress.coerceIn(0f, 1f)
    val fitCenter = fit.center
    val tileScale = if (tile != null && fit.width > 0f) tile.width / fit.width else FALLBACK_TILE_FACTOR
    val tileCenter = tile?.center ?: fitCenter
    val scale = lerpFloat(tileScale, 1f, k) * dragScale(overlay.dragY, overlay.heightPx)
    scaleX = scale
    scaleY = scale
    translationX = (1f - k) * (tileCenter.x - fitCenter.x)
    translationY = (1f - k) * (tileCenter.y - fitCenter.y) + overlay.dragY
    alpha =
        when {
            phase.pagerShown -> 1f

            // Während der Hero-Animation zeichnet das Bild die Ebene darüber, die Seite bleibt unsichtbar.
            phase.heroShown && hasHero -> 0f

            else -> k
        }
}

/** Zeichnet das Bild der Hero-Animation über dem Pager. Ohne Bitmap, Kachel oder Maß bleibt die Ebene leer. */
@Composable
private fun HeroLayer(
    overlay: ViewerOverlayState,
    bitmap: ImageBitmap?,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val image = bitmap ?: return@Canvas
        if (!overlay.phase.heroShown) return@Canvas
        val fit = overlay.fit
        if (fit.width <= 0f || fit.height <= 0f) return@Canvas
        val localTile = overlay.heroTile?.translate(-overlay.originInRoot)
        val tile = localTile ?: fallbackTile(fit)
        val tileAspect = if (tile.height > 0f) tile.width / tile.height else 1f
        val crop = cropSourceRect(image.width, image.height, tileAspect)
        val full = Rect(0f, 0f, image.width.toFloat(), image.height.toFloat())
        val frame = heroFrame(overlay.progress, tile, fit, crop, full)
        val dst =
            transformRect(
                rect = frame.dst,
                pivot = Offset(size.width / 2f, size.height / 2f),
                scale = dragScale(overlay.dragY, overlay.heightPx),
                dy = overlay.dragY,
            )
        // Ohne Kachel blendet die Animation nur ein, daher folgt die Deckkraft dem Fortschritt.
        val alpha = if (localTile == null) overlay.progress.coerceIn(0f, 1f) else 1f
        drawImage(
            image = image,
            srcOffset = IntOffset(frame.src.left.roundToInt(), frame.src.top.roundToInt()),
            srcSize =
                IntSize(
                    frame.src.width
                        .roundToInt()
                        .coerceAtLeast(1),
                    frame.src.height
                        .roundToInt()
                        .coerceAtLeast(1),
                ),
            dstOffset = IntOffset(dst.left.roundToInt(), dst.top.roundToInt()),
            dstSize = IntSize(dst.width.roundToInt().coerceAtLeast(1), dst.height.roundToInt().coerceAtLeast(1)),
            alpha = alpha,
        )
    }
}

/** Beschreibung eines Elements für Screenreader, wie im Raster: „Foto vom …“ bzw. „Video, 0:42, vom …“. */
@Composable
private fun mediaLabel(
    item: ViewerItem,
    dates: TimelineFormats,
): String {
    val date = dates.longDate(item.timestamp)
    return if (item.type == ViewerMediaType.VIDEO) {
        stringResource(R.string.feature_gallery_cell_video, formatDuration(item.durationMs ?: 0L), date)
    } else {
        stringResource(R.string.feature_gallery_cell_photo, date)
    }
}
