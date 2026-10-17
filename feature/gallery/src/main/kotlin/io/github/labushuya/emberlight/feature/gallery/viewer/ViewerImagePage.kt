package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.labushuya.emberlight.feature.gallery.R
import kotlinx.coroutines.flow.first
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.ZoomableImage
import me.saket.telephoto.zoomable.ZoomableImageSource
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

/**
 * Bildquelle einer Seite. Erhält das Element, den Schalter „Bild darf geladen werden“ und einen Callback bei Fehlern. Die
 * Galerie setzt [ContentUriImageSource] ein, Tests eine feste Quelle.
 */
typealias ViewerImageSource = @Composable (
    item: ViewerItem,
    settled: State<Boolean>,
    onFailed: () -> Unit,
) -> ZoomableImageSource

/**
 * Standardquelle der Galerie: das Original über die Content-URI (ADR 0010). Das hochauflösende Bild wartet, bis das Overlay
 * offen steht. Bis dahin zeigt die Seite das Vorschaubild aus dem Speicher-Cache.
 */
internal val ContentUriImageSources: ViewerImageSource = { item, settled, onFailed ->
    remember(item.uri, item.previewKey, settled) {
        ContentUriImageSource(item.uri, item.previewKey, onFailed, settled)
    }
}

/**
 * Eine Bildseite des Overlays mit Pinch-Zoom, Doppeltipp-Zoom und Verschieben (Telephoto). Ist das Bild gezoomt, wischt der
 * Pager erst am Bildrand weiter. Ungezoomt wischt die Seite nach unten zum Schließen ([overlay]).
 *
 * [settled] ist `true`, sobald das Overlay offen steht. Bis dahin zeigt die Seite das Vorschaubild, das Bild selbst bleibt
 * unsichtbar. [onTap] meldet ein einfaches Antippen, das die Leisten umschaltet. [state] gehört dem Aufrufer, damit Tests den
 * Zoomzustand lesen können.
 */
@Composable
internal fun ViewerImagePage(
    item: ViewerItem,
    contentDescription: String,
    imageSource: ViewerImageSource,
    overlay: ViewerOverlayState,
    settled: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    state: ZoomableImageState = rememberZoomableImageState(rememberZoomableState()),
) {
    val failed = remember(item.id) { mutableStateOf(false) }
    val isSettled = settled
    val settledState = rememberUpdatedState(settled)
    // Der Zoomzustand dieser Seite gehört dem Overlay, damit es ein gezoomtes Bild vor dem Schließen zurücksetzt.
    val currentOverlay by rememberUpdatedState(overlay)
    DisposableEffect(item.id, state) {
        currentOverlay.registerZoom(item.id, state.zoomableState)
        onDispose { currentOverlay.unregisterZoom(item.id) }
    }
    // Bereit, sobald Telephoto das Bild gezeichnet hat. Erst dann darf das Vorschaubild der Animation gehen. Der Platzhalter
    // zählt nicht: Telephoto meldet ihn auch dann als gezeichnet, wenn es gar keinen gibt (leerer Platzhalter). Eine leere Seite
    // gälte sonst als bereit, und die Animation endete auf einem leeren Frame.
    LaunchedEffect(item.id, state) {
        snapshotFlow { state.isImageDisplayed }.first { it }
        currentOverlay.pageShown(item.id)
    }
    LaunchedEffect(item.id, state) {
        snapshotFlow { state.isImageDisplayedInFullQuality }.first { it }
        ViewerTrace.mark("Seite volle Qualität", item.id)
    }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .swipeDownToDismiss(
                    overlay = overlay,
                    canStart = { canStartDismiss(state.zoomableState.zoomFraction) },
                ),
    ) {
        if (failed.value) {
            ViewerLoadError(modifier = Modifier.fillMaxSize())
        } else {
            if (!isSettled) {
                // Scheitert nur die Vorschau, bleibt das Original die Quelle. Schlägt auch das fehl, meldet die Quelle den Fehler.
                ViewerPreview(item = item, onError = {})
            }
            ZoomableImage(
                image = imageSource(item, settledState) { failed.value = true },
                contentDescription = contentDescription,
                gestures = EnabledZoomGestures.ZoomAndPan,
                modifier = Modifier.fillMaxSize(),
                state = state,
                // Immer voll deckend. Der Platzhalter zeichnet schon während der Animation, damit die Seite ihre Bereitschaft meldet.
                alpha = 1f,
                onClick = { _ -> onTap() },
            )
        }
    }
}

/** Ruhige Meldung, wenn sich ein Element nicht anzeigen lässt, etwa weil die Datei inzwischen gelöscht ist. */
@Composable
internal fun ViewerLoadError(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_error_outline),
            // Der Text darunter beschreibt den Zustand, das Symbol ist dekorativ.
            contentDescription = null,
            tint = Color.White.copy(alpha = ERROR_ICON_ALPHA),
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.feature_gallery_viewer_error_title),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.feature_gallery_viewer_error_text),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = ERROR_TEXT_ALPHA),
            textAlign = TextAlign.Center,
        )
    }
}

private const val ERROR_ICON_ALPHA = 0.7f
private const val ERROR_TEXT_ALPHA = 0.8f
