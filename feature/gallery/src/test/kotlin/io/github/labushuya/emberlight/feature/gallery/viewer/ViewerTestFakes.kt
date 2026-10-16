package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.test.junit4.ComposeTestRule
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import kotlinx.coroutines.flow.Flow
import me.saket.telephoto.zoomable.ZoomableImageSource
import org.robolectric.shadows.ShadowLooper
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Viewer mit festen Werten für Goldens: Zeitzone Berlin, keine Rückrufe, nur die Leisten sind wählbar. */
@Composable
internal fun ViewerGoldenContent(
    items: List<ViewerItem>,
    chromeVisible: Boolean,
) {
    EmberlightTheme {
        ViewerContent(
            items = items,
            index = 0,
            playback = VideoPlaybackState(),
            muted = false,
            player = null,
            chromeVisible = chromeVisible,
            onChromeToggle = {},
            onBack = {},
            onPageAtRest = {},
            onPlay = {},
            onPause = {},
            onSeek = {},
            onToggleMute = {},
            onVideoEnd = {},
            onVideoError = {},
            imageSource = solidImageSources,
            zone = ZoneId.of("Europe/Berlin"),
        )
    }
}

/** Einfarbiges Bild mit festem Seitenverhältnis. Telephoto braucht eine Größe, um zu zoomen. */
internal fun solidPainter(
    color: Color,
    width: Float = 400f,
    height: Float = 800f,
): Painter =
    object : Painter() {
        override val intrinsicSize: Size = Size(width, height)

        override fun DrawScope.onDraw() {
            drawRect(color)
        }
    }

/** Bildquelle mit einem festen Painter, ohne Dateizugriff. Für Tests und Goldens. */
internal class SolidImageSource(
    private val painter: Painter,
) : ZoomableImageSource {
    @Composable
    override fun resolve(canvasSize: Flow<Size>): ZoomableImageSource.ResolveResult =
        ZoomableImageSource.ResolveResult(delegate = ZoomableImageSource.PainterDelegate(painter))
}

/** Bildquelle für alle Seiten der Tests: jede Seite bekommt eine warme Farbe aus einer festen Palette. */
internal val solidImageSources: ViewerImageSource = { item, _ ->
    val color = warmPalette[(item.id % warmPalette.size).toInt()]
    remember(item.id) { SolidImageSource(solidPainter(color)) }
}

private val warmPalette =
    listOf(
        Color(0xFF8A4B2A),
        Color(0xFFB5652F),
        Color(0xFFD9893F),
        Color(0xFF6E3B24),
        Color(0xFFC97A36),
        Color(0xFF4A2A1C),
    )

/** Foto mit festem Zeitpunkt in der Zone Berlin. */
internal fun photo(
    id: Long,
    localDateTime: String,
): ViewerItem =
    ViewerItem(
        id = id,
        uri = "content://media/external/images/media/$id",
        type = ViewerMediaType.IMAGE,
        timestamp = TimelineFixtures.millis(localDateTime),
        width = 4032,
        height = 3024,
        durationMs = null,
        previewKey = null,
    )

/** Video mit festem Zeitpunkt und Dauer. */
internal fun video(
    id: Long,
    localDateTime: String,
    durationMs: Long,
): ViewerItem =
    ViewerItem(
        id = id,
        uri = "content://media/external/video/media/$id",
        type = ViewerMediaType.VIDEO,
        timestamp = TimelineFixtures.millis(localDateTime),
        width = 1920,
        height = 1080,
        durationMs = durationMs,
        previewKey = null,
    )

/**
 * Lässt die Uhr einige Frames laufen, damit Layout, Zeichnung und das Einblenden des Bildes fertig sind, bevor ein Golden
 * entsteht. Die Uhr läuft dabei von Hand, danach wieder automatisch.
 */
internal fun ComposeTestRule.settleFrames() {
    mainClock.autoAdvance = false
    repeat(SETTLE_FRAMES) { mainClock.advanceTimeByFrame() }
    mainClock.autoAdvance = true
    waitForIdle()
    // Der Android-Looper zeichnet die Ansicht erst in seinem eigenen Frame. Ohne diesen Lauf kann die Aufnahme leer sein.
    ShadowLooper.idleMainLooper(FRAME_MS, TimeUnit.MILLISECONDS)
}

private const val SETTLE_FRAMES = 60
private const val FRAME_MS = 100L

/**
 * Leert den Haupt-Looper vor jedem Golden. Robolectric teilt den Looper zwischen den Tests eines Laufs. Geplante Aufgaben
 * eines früheren Tests (etwa das Ausblenden der Schnell-Scrollleiste) laufen sonst in den nächsten Test hinein.
 */
internal fun flushMainLooper() {
    ShadowLooper.idleMainLooper(FLUSH_SECONDS, TimeUnit.SECONDS)
}

private const val FLUSH_SECONDS = 5L
