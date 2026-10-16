package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.ZoomableImage
import me.saket.telephoto.zoomable.ZoomableImageSource
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

/**
 * Bildquelle einer Seite. Erhält das Element und einen Callback, der bei Fehlern aufgerufen wird. Die Galerie setzt
 * [ContentUriImageSource] ein, Tests eine feste Quelle.
 */
internal typealias ViewerImageSource = @Composable (item: ViewerItem, onFailed: () -> Unit) -> ZoomableImageSource

/** Standardquelle der Galerie: das Original über die Content-URI (ADR 0010). */
internal val ContentUriImageSources: ViewerImageSource = { item, onFailed ->
    remember(item.uri, item.previewKey) { ContentUriImageSource(item.uri, item.previewKey, onFailed) }
}

/**
 * Eine Bildseite des Viewers mit Pinch-Zoom, Doppeltipp-Zoom und Verschieben (Telephoto). Ist das Bild gezoomt, wischt der
 * Pager erst am Bildrand weiter: Telephoto verbraucht Verschiebungen nur, solange sie im Bild liegen.
 *
 * [onTap] meldet ein einfaches Antippen, das die Leisten ein- oder ausblendet. [state] gehört dem Aufrufer, damit Tests den
 * Zoomzustand lesen können.
 */
@Composable
internal fun ViewerImagePage(
    item: ViewerItem,
    contentDescription: String,
    imageSource: ViewerImageSource,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    state: ZoomableImageState = rememberZoomableImageState(rememberZoomableState()),
) {
    val failed = remember(item.id) { mutableStateOf(false) }
    if (failed.value) {
        ViewerLoadError(modifier = modifier.fillMaxSize())
    } else {
        ZoomableImage(
            image = imageSource(item) { failed.value = true },
            contentDescription = contentDescription,
            gestures = EnabledZoomGestures.ZoomAndPan,
            modifier = modifier.fillMaxSize(),
            state = state,
            onClick = { _ -> onTap() },
        )
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
