package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import io.github.labushuya.emberlight.feature.gallery.R
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFormats
import io.github.labushuya.emberlight.feature.gallery.timeline.formatDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.ZoneId

/** Abstand zwischen zwei Seiten. */
private val PAGE_SPACING = 16.dp

/**
 * Der Viewer ohne Zustand des ViewModels: schwarzer Grund (unabhängig vom Theme), der Pager über die Elemente in
 * Rasterreihenfolge und die Leisten. Alles, was der Nutzer tut, geht nach außen. Die Leisten und der Stand der Wiedergabe
 * kommen von außen, damit Tests den Zustand direkt setzen können.
 *
 * [index] ist das gewählte Element. Der Pager folgt ihm, etwa nach dem Verschwinden eines Elements. Umgekehrt meldet der
 * Pager jede eingerastete Seite über [onPageAtRest].
 *
 * Wegwischen nach unten und die Vorschau der System-Rückgeste (Predictive Back) steuern dieselbe Ansicht: Der Pager
 * verschiebt und verkleinert sich, der Grund wird transparent (ADR 0010). Schließen führt über [onBack], der Rückweg zur
 * Kachel startet aus der aktuellen Position.
 */
@Composable
internal fun ViewerContent(
    items: List<ViewerItem>,
    index: Int,
    playback: VideoPlaybackState,
    muted: Boolean,
    player: Player?,
    chromeVisible: Boolean,
    onChromeToggle: () -> Unit,
    onBack: () -> Unit,
    onPageAtRest: (Long) -> Unit,
    onPlay: (Long) -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
    onVideoEnd: () -> Unit,
    onVideoError: () -> Unit,
    modifier: Modifier = Modifier,
    imageSource: ViewerImageSource = ContentUriImageSources,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val locale = LocalConfiguration.current.locales[0]
    val titles = remember(locale, zone) { ViewerFormats(locale, zone) }
    val dates = remember(locale, zone) { TimelineFormats(locale, zone) }
    val pagerState = rememberPagerState(initialPage = index, pageCount = { items.size })
    val currentItem = items.getOrNull(pagerState.currentPage)
    val attachedHere = currentItem != null && playback.attachedId == currentItem.id
    val dismiss = rememberDismissState()
    // Schließen läuft über den Zustand: Der Rückweg startet aus der gezogenen Position, nicht aus der Mitte.
    val leave: () -> Unit = { dismiss.close(onBack) }

    // Die System-Rückgeste steuert die Vorschau: Der Fortschritt zeigt sich wie beim Ziehen, Abbrechen federt zurück,
    // Vollenden schließt (ADR 0010).
    PredictiveBackHandler { progress ->
        try {
            progress.collect { event -> dismiss.backProgressed(event.progress) }
            dismiss.backCommitted(onBack)
        } catch (e: CancellationException) {
            dismiss.backCancelled()
        }
    }

    // Der eine Player hängt an der angehängten Seite. Die Anzeige liest seinen Zustand, auch wenn die Seite gerade nicht
    // die aktuelle ist, etwa während eines Wischens.
    val video =
        rememberVideoPlaybackUi(
            player = if (playback.attachedId != null) player else null,
            fallbackDurationMs = currentItem?.durationMs,
            onPlaybackEnd = onVideoEnd,
            onPlaybackError = onVideoError,
        )

    val latestItems by rememberUpdatedState(items)
    val latestOnPageSettled by rememberUpdatedState(onPageAtRest)

    // Der Pager folgt dem Zustand, etwa nachdem ein Element gelöscht wurde und der Nachbar an dessen Stelle rückt.
    LaunchedEffect(index) {
        if (index < pagerState.pageCount && index != pagerState.currentPage) pagerState.scrollToPage(index)
    }
    // Eingerastete Seiten melden: Der Zustand merkt sich die Seite und löst die Wiedergabe von anderen Seiten.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page -> latestItems.getOrNull(page)?.let { latestOnPageSettled(it.id) } }
    }

    val title = currentItem?.let { titles.title(it.timestamp) }
    val durationText =
        currentItem
            ?.takeIf { it.type == ViewerMediaType.VIDEO }
            ?.durationMs
            ?.let(::formatDuration)
    val controlsVideo = if (attachedHere) video else VideoPlaybackUi.idle(currentItem?.durationMs)
    // Während des Ziehens sind die Leisten aus. Nur die Seite und der Grund bewegen sich.
    val chromeShown = chromeVisible && !dismiss.dragging

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged { size -> dismiss.heightPx = size.height.toFloat() }
                // Der Grund ist schwarz, unabhängig vom Theme. Während des Wegwischens wird er transparent. Gelesen wird in der
                // Zeichenphase, damit jedes Frame ohne Neuzusammensetzung auskommt.
                .drawBehind { drawRect(Color.Black, alpha = dismiss.backdropAlpha) },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = dismiss.offsetY
                        scaleX = dismiss.scale
                        scaleY = dismiss.scale
                    },
            key = { page -> items[page].id },
            beyondViewportPageCount = 1,
            pageSpacing = PAGE_SPACING,
        ) { page ->
            val item = items[page]
            val label = mediaLabel(item, dates)
            when (item.type) {
                ViewerMediaType.IMAGE -> {
                    ViewerImagePage(
                        item = item,
                        contentDescription = label,
                        imageSource = imageSource,
                        onTap = onChromeToggle,
                        dismiss = dismiss,
                        onDismiss = leave,
                    )
                }

                ViewerMediaType.VIDEO -> {
                    val attached = playback.attachedId == item.id
                    ViewerVideoPage(
                        item = item,
                        label = label,
                        attached = attached,
                        player = if (attached) player else null,
                        video = if (attached) video else VideoPlaybackUi.idle(item.durationMs),
                        onPlay = { onPlay(item.id) },
                        onTap = onChromeToggle,
                        dismiss = dismiss,
                        onDismiss = leave,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = chromeShown && title != null,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ViewerTopBar(title = title.orEmpty(), durationText = durationText, onBack = leave)
        }

        AnimatedVisibility(
            visible = chromeShown && currentItem?.type == ViewerMediaType.VIDEO,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ViewerBottomScrim {
                VideoControls(
                    video = controlsVideo,
                    playRequested = attachedHere && playback.playRequested,
                    attached = attachedHere,
                    muted = muted,
                    onPlayPause = {
                        when {
                            attachedHere && playback.playRequested -> onPause()
                            currentItem != null -> onPlay(currentItem.id)
                        }
                    },
                    onSeek = onSeek,
                    onToggleMute = onToggleMute,
                )
            }
        }
    }
}

/** Beschreibung eines Elements für Screenreader, wie in der Zeitleiste: „Foto vom …“ bzw. „Video, 0:42, vom …“. */
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
