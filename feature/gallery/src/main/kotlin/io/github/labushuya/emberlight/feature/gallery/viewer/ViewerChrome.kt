package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.labushuya.emberlight.feature.gallery.R

/**
 * Obere Leiste des Viewers: Zurück-Pfeil, Datum und Uhrzeit des Elements, bei Videos zusätzlich die Dauer. Transparent über
 * einem dezenten Verlauf. Die Texte sind weiß, weil der Viewer unabhängig vom Theme auf Schwarz liegt.
 */
@Composable
internal fun ViewerTopBar(
    title: String,
    durationText: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(CHROME_SCRIM, Color.Transparent)))
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.feature_gallery_back),
                tint = Color.White,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (durationText != null) {
                Text(
                    text = durationText,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = SECONDARY_ALPHA),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Untere Fläche für die Videosteuerung: derselbe Verlauf, nach oben ausgerichtet. */
@Composable
internal fun ViewerBottomScrim(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, CHROME_SCRIM)))
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                ).padding(top = 24.dp, bottom = 4.dp),
    ) {
        content()
    }
}

private val CHROME_SCRIM = Color(0xB3000000)
private const val SECONDARY_ALPHA = 0.8f
