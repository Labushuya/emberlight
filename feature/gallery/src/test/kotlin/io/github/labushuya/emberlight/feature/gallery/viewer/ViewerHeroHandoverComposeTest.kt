package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.feature.gallery.timeline.TestThumbnails
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomableImageSource
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

/**
 * Übergabe Hero zur Seite mit der echten Telephoto-Bereitschaft (ADR 0010, Feinschliff). Die Marken aus [ViewerTrace] zeigen,
 * was das Vorschaubild beendet: die Meldung der Seite oder der Rückfall.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
@OptIn(ExperimentalTestApi::class)
class ViewerHeroHandoverComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val viewerItems = TimelineFixtures.sampleItems().map { it.toViewerItem() }
    private val photo = viewerItems.first { it.type == ViewerMediaType.IMAGE }
    private val marks = mutableListOf<String>()

    @Before
    fun collectTraceMarks() {
        TestThumbnails.install(TimelineFixtures.sampleItems())
        ViewerTrace.output = { line -> marks += line.substringBefore(" id=") }
    }

    @After
    fun restoreTrace() {
        ViewerTrace.output = { _ -> }
        TestThumbnails.reset()
    }

    @Test
    fun aPageThatShowsItsImageRetiresTheHeroBeforeTheFallback() {
        composeRule.setContent { HandoverContent(viewerItems, photo.id, solidImageSources) }

        composeRule.waitUntil(timeoutMillis = WAIT_MS) { marks.contains("Hero entfernt") }

        assertTrue("Die Seite meldet ihr Bild.", marks.contains("Seite Bild angezeigt"))
        assertFalse("Der Rückfall darf nicht greifen.", marks.contains("Hero-Rückfall ohne Bild"))
        assertTrue(marks.indexOf("Seite Bild angezeigt") < marks.indexOf("Hero entfernt"))
    }

    @Test
    fun aPageWithoutAnImageLetsTheHeroFallBack() {
        composeRule.setContent { HandoverContent(viewerItems, photo.id, silentImageSources) }

        composeRule.waitUntil(timeoutMillis = FALLBACK_WAIT_MS) { marks.contains("Hero entfernt") }

        assertTrue("Ohne Bild greift der Rückfall: $marks", marks.contains("Hero-Rückfall ohne Bild"))
        assertFalse(marks.contains("Seite Bild angezeigt"))
    }

    /** Öffnet das Element [openId] mit Animation, wie ein Tipp aufs Raster. */
    @Composable
    private fun HandoverContent(
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

    @Test
    fun closingFromAZoomKeepsTheZoomAndTakesTheVisibleRectangleAsStart() {
        lateinit var overlay: ViewerOverlayState
        lateinit var zoom: ZoomableImageState
        lateinit var scope: CoroutineScope
        composeRule.setContent {
            EmberlightTheme {
                scope = rememberCoroutineScope()
                overlay = remember(scope) { ViewerOverlayState(scope, mutableStateOf(photo.id), { null }) }
                zoom = rememberZoomableImageState(rememberZoomableState())
                ViewerImagePage(
                    item = photo,
                    contentDescription = photo.uri,
                    imageSource = solidImageSources,
                    overlay = overlay,
                    settled = true,
                    onTap = {},
                    state = zoom,
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = WAIT_MS) { zoom.isImageDisplayed }
        composeRule.runOnIdle { scope.launch { zoom.zoomableState.zoomTo(ZOOM_TO) } }
        composeRule.waitUntil(timeoutMillis = WAIT_MS) { (zoom.zoomableState.zoomFraction ?: 0f) > ZOOM_SEEN }

        // Der Zustand gilt direkt nach dem Schließen, bevor die Animation abläuft. Danach ist das Overlay zu.
        composeRule.runOnIdle {
            overlay.close()
            assertNotNull("Der Rückweg startet am sichtbaren Inhalts-Rechteck.", overlay.zoomFrom)
            assertTrue("Der Zoom bleibt beim Schließen erhalten.", (zoom.zoomableState.zoomFraction ?: 0f) > ZOOM_SEEN)
        }
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val FALLBACK_WAIT_MS = 8_000L
        const val ZOOM_TO = 2f
        const val ZOOM_SEEN = 0.2f
    }
}

/** Bildquelle ohne Bild: die Seite zeigt nichts, weder Original noch Platzhalter. */
private object SilentImageSource : ZoomableImageSource {
    @Composable
    override fun resolve(canvasSize: Flow<Size>): ZoomableImageSource.ResolveResult =
        ZoomableImageSource.ResolveResult(delegate = null)
}

private val silentImageSources: ViewerImageSource = { _, _, _, _ -> remember { SilentImageSource } }
