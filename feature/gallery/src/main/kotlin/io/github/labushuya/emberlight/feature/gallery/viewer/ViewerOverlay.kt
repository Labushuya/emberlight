package io.github.labushuya.emberlight.feature.gallery.viewer

import android.content.Context
import android.view.HapticFeedbackConstants
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil3.imageLoader
import io.github.labushuya.emberlight.feature.gallery.R
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFormats
import io.github.labushuya.emberlight.feature.gallery.timeline.formatDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.ZoneId

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
    imageSource: ViewerImageSource? = null,
    initialChromeVisible: Boolean = true,
    zone: ZoneId = ZoneId.systemDefault(),
    exif: ExifLookup = ExifLookup.None,
) {
    val id = overlay.shownId ?: return
    val context = LocalContext.current
    val contributions = LocalViewerImageSources.current
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

    // Vorschau in Bildschirmgröße (ADR 0010, Korrektur 4): Sie lädt beim Öffnen, überblendet das Vorschaubild im Hero und ist
    // der Platzhalter der Seiten. Der Store gehört dem Overlay und gibt beim Schließen alles frei.
    val scope = rememberCoroutineScope()
    val screens = remember(scope, context) { ScreenPreviews(scope, context.imageLoader, context) }
    DisposableEffect(screens) { onDispose { screens.release() } }
    val screenShown = screens.bitmapOf(item.uri) != null
    LaunchedEffect(index, items, screenShown) { screens.focus(items, index) }
    val hasHero = heroBitmap != null || screenShown

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

    // Die System-Rückgeste steuert dieselbe Ansicht wie das Ziehen. Ein Flow, der normal endet, ist ein Vollenden und schließt
    // immer, unabhängig vom Fortschritt. Nur `CancellationException` ist ein Abbruch und federt zurück (ADR 0010).
    // Beginn ohne Fortschritt tritt der Kurze Blick ein, mit Fortschritt folgt das Bild dem Finger (ADR 0010). Ist der
    // Infobereich offen, schließt die Rückgeste zuerst ihn.
    PredictiveBackHandler { progress ->
        latestOverlay.backStarted()
        var sawProgress = false
        try {
            progress.collect { event ->
                if (!sawProgress) ViewerTrace.mark("Rückgeste Start", latestOverlay.shownId)
                sawProgress = true
                latestOverlay.backProgressed(event.progress)
            }
            if (!sawProgress) ViewerTrace.mark("Rückgeste ohne Fortschritt", latestOverlay.shownId)
            ViewerTrace.mark("Rückgeste vollendet", latestOverlay.shownId)
            latestOverlay.backCommitted()
        } catch (e: CancellationException) {
            ViewerTrace.mark("Rückgeste abgebrochen", latestOverlay.shownId)
            latestOverlay.backCancelled()
        }
    }

    val phase = overlay.phase
    val title = titles.title(item.timestamp)
    val label = mediaLabel(item, dates)
    val durationText = item.takeIf { it.type == ViewerMediaType.VIDEO }?.durationMs?.let(::formatDuration)
    // Während des Ziehens und des Kurzen Blicks sind die Leisten aus. Der offene Infobereich blendet sie nie aus.
    val infoOpen = overlay.infoOpen
    val chromeShown =
        (chromeVisible || infoOpen) &&
            phase != OverlayPhase.DRAGGING && phase != OverlayPhase.CLOSING && phase != OverlayPhase.PEEKING
    // Die Haptik beim Kurzen Blick läuft über die Ansicht, ohne neue Berechtigung (ADR 0010).
    val view = LocalView.current
    SideEffect { overlay.onPeekStarted = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) } }
    val showInfoLabel = stringResource(R.string.feature_gallery_info_show)
    val hideInfoLabel = stringResource(R.string.feature_gallery_info_hide)
    val infoActionLabel = if (infoOpen) hideInfoLabel else showInfoLabel
    val highlightColor = MaterialTheme.colorScheme.primary
    val attachedHere = playbackState.attachedId == itemId
    val video =
        rememberVideoPlaybackUi(
            player = if (playbackState.attachedId != null) playback.player else null,
            fallbackDurationMs = item.durationMs,
            onPlaybackEnd = playback::onPlaybackEnded,
            onPlaybackError = playback::onPlaybackFailed,
        )
    val settled = overlay.phase.pagerShown

    ImmersiveSystemBars(immersive = !chromeVisible && !infoOpen)

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .testTag(VIEWER_OVERLAY_TAG)
                .semantics {
                    // Bedienungshilfen können den Infobereich öffnen und schließen, ohne zu wischen (ADR 0010).
                    customActions =
                        listOf(
                            CustomAccessibilityAction(infoActionLabel) {
                                overlay.toggleInfo()
                                true
                            },
                        )
                }.onGloballyPositioned { coords ->
                    overlay.originInRoot = coords.positionInRoot()
                    overlay.heightPx = coords.size.height.toFloat()
                    overlay.fit = fitInside(coords.size.width.toFloat(), coords.size.height.toFloat(), aspect)
                }.drawBehind {
                    drawRect(
                        Color.Black,
                        alpha =
                            backdropAlpha(
                                overlay.phase,
                                overlay.progress,
                                overlay.dragY,
                                overlay.heightPx,
                                overlay.peek,
                            ),
                    )
                },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { pagerTransform(overlay, hasHero) },
            key = { page -> items[page].id },
            beyondViewportPageCount = 1,
            pageSpacing = PAGE_SPACING,
        ) { page ->
            val pageItem = items[page]
            val pageLabel = mediaLabel(pageItem, dates)
            when (pageItem.type) {
                ViewerMediaType.IMAGE -> {
                    val pageSource: ViewerImageSource = imageSource ?: contributions.imageSourceFor(pageItem)
                    ViewerImagePage(
                        item = pageItem,
                        contentDescription = pageLabel,
                        imageSource = pageSource,
                        overlay = overlay,
                        settled = settled,
                        onTap = { if (overlay.infoOpen) overlay.closeInfo() else chromeVisible = !chromeVisible },
                        screen = screens.bitmapOf(pageItem.uri),
                        exif = exif,
                        zone = zone,
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
                        onTap = { if (overlay.infoOpen) overlay.closeInfo() else chromeVisible = !chromeVisible },
                        screen = screens.bitmapOf(pageItem.uri),
                        exif = exif,
                        zone = zone,
                    )
                }
            }
        }

        HeroLayer(overlay = overlay, bitmap = heroBitmap, screens = screens, item = item)

        // Die Kachel des Elements im Raster wird während des Blicks und der Rückgeste hervorgehoben. Die Hervorhebung liegt über
        // dem Bild, sonst verdeckt das Bild sie (ADR 0010, Kurzer Blick). Nur diese Ebene zeichnet, kein Zellen-Recompose.
        PeekHighlight(overlay = overlay, color = highlightColor)

        AnimatedVisibility(
            visible = chromeShown,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ViewerTopBar(title = title, durationText = durationText, onBack = overlay::close)
        }

        AnimatedVisibility(
            // Bei offenem Infobereich weicht die Steuerung, sonst läge sie über dem Bereich (ADR 0010, Infobereich).
            visible = chromeShown && item.type == ViewerMediaType.VIDEO && !infoOpen,
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
    peek: Float = 0f,
): Float {
    if (phase == OverlayPhase.OPEN) return 1f
    val base = progress.coerceIn(0f, 1f) * (1f - dismissProgress(dragY, heightPx))
    // Der Kurze Blick senkt die Deckkraft, damit das Raster darunter deutlich sichtbar ist (ADR 0010).
    return base + (PEEK_BACKDROP_ALPHA - base) * peek.coerceIn(0f, 1f)
}

/** Deckkraft des schwarzen Grundes auf der Blick-Stufe: Das Raster darunter bleibt deutlich sichtbar. */
internal const val PEEK_BACKDROP_ALPHA = 0.35f

/** Strichstärke der Hervorhebung der Kachel und ihres Leuchtens. */
private val HIGHLIGHT_BORDER = 2.dp
private val HIGHLIGHT_GLOW = 6.dp
private val HIGHLIGHT_CORNER = 6.dp
private const val HIGHLIGHT_GLOW_ALPHA = 0.35f

/**
 * Hervorhebung der Kachel während des Kurzen Blicks und der Rückgeste (ADR 0010). Die Ebene liegt über dem Bild, weil das Bild
 * auf der Blick-Stufe die Kachel sonst verdeckt. Gelesen werden nur Zustandswerte in der Zeichenphase. So zeichnet nur diese
 * Ebene neu, keine Kachel des Rasters wird neu zusammengesetzt.
 */
@Composable
private fun PeekHighlight(
    overlay: ViewerOverlayState,
    color: Color,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val alpha = overlay.highlight
        if (alpha <= 0f) return@Canvas
        val tile = overlay.heroTile?.translate(-overlay.originInRoot) ?: return@Canvas
        val glow = HIGHLIGHT_GLOW.toPx()
        val corner = CornerRadius(HIGHLIGHT_CORNER.toPx())
        drawRoundRect(
            color = color.copy(alpha = alpha * HIGHLIGHT_GLOW_ALPHA),
            topLeft = Offset(tile.left - glow, tile.top - glow),
            size = Size(tile.width + 2 * glow, tile.height + 2 * glow),
            cornerRadius = corner,
            style = Stroke(width = glow),
        )
        drawRoundRect(
            color = color.copy(alpha = alpha),
            topLeft = tile.topLeft,
            size = tile.size,
            cornerRadius = corner,
            style = Stroke(width = HIGHLIGHT_BORDER.toPx()),
        )
    }
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

/**
 * Fit-Rechteck des Bildes im Overlay: dieselbe Berechnung wie für das Vorschaubild der Hero-Animation ([fitRect]), auf ganze
 * Pixel gerundet ([snapRect]). Ohne Verhältnis: der ganze Bereich.
 */
private fun fitInside(
    width: Float,
    height: Float,
    aspect: Float?,
): Rect {
    val bounds = Rect(Offset.Zero, Size(width, height))
    return if (aspect == null) bounds else snapRect(fitRect(bounds, aspect))
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
            // Der Rückweg aus dem Zoom zeichnet das vorhandene Vorschaubild. Die Seite bleibt darunter unsichtbar.
            overlay.zoomFrom != null -> 0f

            // Im Schließen zeichnet das Vorschaubild die Ebene. Die Seite bleibt unsichtbar.
            phase == OverlayPhase.CLOSING && hasHero -> 0f

            // Liegt das Vorschaubild darüber, liegt die Seite mit gleicher Geometrie darunter. So meldet sie, wann ihr Bild steht.
            hasHero && overlay.heroAlpha > 0f -> 1f

            phase.pagerShown -> 1f

            else -> k
        }
}

/**
 * Zeichnet die Bilder der Hero-Animation über dem Pager: das Raster-Vorschaubild und, sobald sie da ist, die Vorschau in
 * Bildschirmgröße. Die Bildschirm-Vorschau überblendet das Vorschaubild in [ScreenPreviews.mix] (ADR 0010, Korrektur 4).
 * Ohne Bild, Kachel oder Maß bleibt die Ebene leer. Gelesen werden nur Zustandswerte in der Zeichenphase.
 */
@Composable
private fun HeroLayer(
    overlay: ViewerOverlayState,
    bitmap: ImageBitmap?,
    screens: ScreenPreviews,
    item: ViewerItem,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val screen = screens.bitmapOf(item.uri)
        if (bitmap == null && screen == null) return@Canvas
        if (overlay.phase == OverlayPhase.CLOSED || overlay.heroAlpha <= 0f) return@Canvas
        val fit = overlay.fit
        if (fit.width <= 0f || fit.height <= 0f) return@Canvas
        val localTile = overlay.heroTile?.translate(-overlay.originInRoot)
        val tile = localTile ?: fallbackTile(fit)
        // Ohne Kachel blendet die Animation nur ein, daher folgt die Deckkraft dem Fortschritt. Das Ausblenden nach der
        // Bereitschaft der Seite steuert die Deckkraft des Vorschaubildes.
        val fade = if (localTile == null) overlay.progress.coerceIn(0f, 1f) else 1f
        val base = fade * overlay.heroAlpha
        val mix = screens.mix.coerceIn(0f, 1f)
        if (bitmap != null) {
            drawHeroImage(overlay, bitmap, tile, fit, if (screen == null) base else base * (1f - mix))
        }
        if (screen != null) {
            drawHeroImage(overlay, screen, tile, fit, base * mix)
            screens.markHeroUse(item.id, item.uri)
        }
    }
}

/** Zeichnet ein Bild der Hero-Animation mit [alpha] von der Kachel zum Vollbild, siehe [heroFrameOf]. */
private fun DrawScope.drawHeroImage(
    overlay: ViewerOverlayState,
    image: ImageBitmap,
    tile: Rect,
    fit: Rect,
    alpha: Float,
) {
    if (alpha <= 0f) return
    val tileAspect = if (tile.height > 0f) tile.width / tile.height else 1f
    val crop = cropSourceRect(image.width, image.height, tileAspect)
    val full = Rect(0f, 0f, image.width.toFloat(), image.height.toFloat())
    val frame = heroFrameOf(overlay, tile, fit, crop, full)
    // Ganze Pixel an allen Kanten, wie das Fit-Rechteck der Seite (siehe fitInside).
    val dst =
        snapRect(
            transformRect(
                rect = frame.dst,
                pivot = Offset(size.width / 2f, size.height / 2f),
                scale = dragScale(overlay.dragY, overlay.heightPx),
                dy = overlay.dragY,
            ),
        )
    val src = snapRect(frame.src)
    drawImage(
        image = image,
        srcOffset = IntOffset(src.left.toInt(), src.top.toInt()),
        srcSize = IntSize(src.width.toInt().coerceAtLeast(1), src.height.toInt().coerceAtLeast(1)),
        dstOffset = IntOffset(dst.left.toInt(), dst.top.toInt()),
        dstSize = IntSize(dst.width.toInt().coerceAtLeast(1), dst.height.toInt().coerceAtLeast(1)),
        alpha = alpha,
    )
}

/**
 * Rahmen der Hero-Animation zum Stand des Overlays. Startet der Viewer aus dem Zoom, beginnt er am sichtbaren Inhalts-Rechteck
 * ([ViewerOverlayState.zoomFrom]) und läuft zur Kachel. Sonst gilt der übliche Verlauf von der Kachel zum Vollbild.
 */
private fun heroFrameOf(
    overlay: ViewerOverlayState,
    tile: Rect,
    fit: Rect,
    crop: Rect,
    full: Rect,
): HeroFrame {
    val zoomFrom = overlay.zoomFrom ?: return heroFrame(overlay.progress, tile, fit, crop, full)
    return lerpFrame(1f - overlay.progress, HeroFrame(dst = zoomFrom, src = full), HeroFrame(dst = tile, src = crop))
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
