package io.github.labushuya.emberlight.feature.gallery.viewer

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.imageLoader
import coil3.memory.MemoryCache
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import me.saket.telephoto.zoomable.ZoomableImageSource
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

/**
 * Das Overlay mit der Vorschau in Bildschirmgröße (ADR 0010, Korrektur 4). Der Hero übernimmt sie, und die Seite bekommt sie als
 * Platzhalter.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
class ViewerScreenPreviewComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val viewerItems = TimelineFixtures.sampleItems().map { it.toViewerItem() }
    private val photo = viewerItems.first { it.type == ViewerMediaType.IMAGE }
    private var fakes = ScreenPreviewFakes()
    private val marks = mutableListOf<String>()
    private val pageScreens = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        installLoader(synchronous = false)
        ViewerTrace.output = { line -> marks += line.substringBefore(" id=") }
    }

    /** Setzt den Singleton-Loader auf die Fakes dieses Tests. Ohne [synchronous] läuft er auf Hintergrund-Threads. */
    private fun installLoader(synchronous: Boolean) {
        SingletonImageLoader.reset()
        fakes = ScreenPreviewFakes(synchronous)
        SingletonImageLoader.setSafe { ctx -> fakes.loader(ctx) }
    }

    @After
    fun tearDown() {
        ViewerTrace.output = { _ -> }
        SingletonImageLoader.reset()
    }

    /**
     * Der Hero bleibt stehen, bis die Seite ihr Bild meldet. Diese Seite zeigt nie etwas, der Hero läuft also bis zum Rückfall.
     * Die Vorschau liegt schon im Speicher-Cache, damit ihre Ankunft nicht von der Ladezeit abhängt. Der Loader läuft dabei auf
     * dem Haupt-Dispatcher des Tests. Mit einem Hintergrund-Thread hängt die Ankunft an der realen Zeit: Ist sie unter Last
     * langsam, rückt der Test-Scheduler die virtuelle Zeit schon bis zum Rückfall des Hero (1 Sekunde) vor, und die Vorschau
     * kommt zu spät.
     */
    @Test
    fun theHeroTakesTheScreenPreviewOnceItIsInTheCache() {
        installLoader(synchronous = true)
        cacheScreenPreview(photo)

        composeRule.setContent { OpenedViewer(viewerItems, photo.id, noPageImageSources) }

        composeRule.waitUntil(timeoutMillis = WAIT_MS) { marks.contains("im Hero übernommen") }
        assertTrue(
            "Die Vorschau ist bereit, bevor der Hero sie nimmt: $marks",
            marks.indexOf("Bildschirm-Vorschau bereit") < marks.indexOf("im Hero übernommen"),
        )
        assertTrue(
            "Die Anforderung steht vor der Bereitschaft: $marks",
            marks.indexOf("Bildschirm-Vorschau angefordert") < marks.indexOf("Bildschirm-Vorschau bereit"),
        )
    }

    @Test
    fun thePagePlaceholderSwitchesToTheScreenPreviewOnceItIsThere() {
        val gate = CompletableDeferred<Unit>().also { fakes.gate = it }
        composeRule.setContent { OpenedViewer(viewerItems, photo.id, recordingSource) }
        composeRule.runOnIdle { gate.complete(Unit) }

        composeRule.waitUntil(timeoutMillis = WAIT_MS) { pageScreens.contains(true) }
        assertTrue(
            "Vor der Ankunft bleibt der Platzhalter ohne Vorschau in Bildschirmgröße.",
            pageScreens.first() == false,
        )
    }

    /** Legt eine Vorschau in Bildschirmgröße in den Speicher-Cache des Singleton-Laders. */
    private fun cacheScreenPreview(item: ViewerItem) {
        val bitmap = Bitmap.createBitmap(BITMAP_EDGE, BITMAP_EDGE, Bitmap.Config.ARGB_8888)
        context.imageLoader.memoryCache?.set(
            MemoryCache.Key(screenPreviewKey(item.uri)),
            MemoryCache.Value(bitmap.asImage(), emptyMap()),
        )
    }

    /** Öffnet das Overlay ohne Kachel, wie ein Tipp aufs Raster. */
    @Composable
    private fun OpenedViewer(
        items: List<ViewerItem>,
        openId: Long,
        source: ViewerImageSource,
    ) {
        EmberlightTheme {
            val scope = rememberCoroutineScope()
            val overlay = remember(scope) { ViewerOverlayState(scope, mutableStateOf<Long?>(null), { null }) }
            LaunchedEffect(Unit) { overlay.open(openId, tile = null) }
            ViewerOverlay(
                overlay = overlay,
                items = items,
                playback = remember { ViewerPlaybackViewModel(NoPlayers) },
                imageSource = source,
                zone = ZoneId.of("Europe/Berlin"),
            )
        }
    }

    private val recordingSource: ViewerImageSource = { item, _, screen, _ ->
        pageScreens += screen != null
        remember(item.id) { SolidImageSource(solidPainter(Color(PAGE_COLOR))) }
    }

    private val noPageImageSources: ViewerImageSource = { _, _, _, _ -> remember { NoPageImageSource } }

    private companion object {
        const val WAIT_MS = 5_000L
        const val PAGE_COLOR = 0xFF8A4B2A
        const val BITMAP_EDGE = 8
    }
}

/** Bildquelle ohne Bild: die Seite zeigt nichts, der Hero läuft bis zum Rückfall. */
private object NoPageImageSource : ZoomableImageSource {
    @Composable
    override fun resolve(canvasSize: Flow<Size>): ZoomableImageSource.ResolveResult =
        ZoomableImageSource.ResolveResult(delegate = null)
}
