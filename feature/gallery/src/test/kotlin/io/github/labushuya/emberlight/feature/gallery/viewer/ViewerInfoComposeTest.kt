package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.core.media.exif.PhotoExif
import io.github.labushuya.emberlight.feature.gallery.timeline.TestThumbnails
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

/**
 * Infobereich im Viewer mit echten Wischgesten (ADR 0010, Kurzer Blick und Infobereich): Öffnen, Schließen, Zurück zuerst, das
 * Wechseln mit dem Pager und das Verbot im Zoom.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
@OptIn(ExperimentalTestApi::class)
class ViewerInfoComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val viewerItems = TimelineFixtures.sampleItems().map { it.toViewerItem() }
    private val photoId = 8L
    private val nextPhotoId = 7L
    private lateinit var overlay: ViewerOverlayState

    @Before
    fun setUp() {
        TestThumbnails.install(TimelineFixtures.sampleItems())
    }

    @After
    fun tearDown() {
        TestThumbnails.reset()
    }

    @Test
    fun swipingUpOpensTheInfoWithTheItemsTexts() {
        showOpenViewer(photoId)

        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.waitUntil(WAIT_MS) { overlay.infoOpen }

        assertTrue(composeRule.onAllNodesWithText("Details").fetchSemanticsNodes().isNotEmpty())
        composeRule.onNodeWithText("file$photoId").assertIsDisplayed()
    }

    @Test
    fun swipingDownClosesTheInfoAndKeepsTheViewerOpen() {
        showOpenViewer(photoId)
        openInfo()

        composeRule.onRoot().performTouchInput { swipeDown() }
        composeRule.waitUntil(WAIT_MS) { !overlay.infoOpen && overlay.infoFraction == 0f }

        assertFalse(overlay.infoOpen)
        assertTrue(overlay.shownId == photoId && overlay.phase == OverlayPhase.OPEN)
    }

    @Test
    fun backClosesTheInfoFirstAndTheViewerSecond() {
        showOpenViewer(photoId)
        openInfo()

        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitUntil(WAIT_MS) { !overlay.infoOpen }
        assertTrue(overlay.shownId == photoId)

        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitUntil(WAIT_MS) { overlay.shownId == null && overlay.phase == OverlayPhase.CLOSED }
        assertNull(overlay.shownId)
    }

    @Test
    fun swipingToTheNextItemKeepsTheInfoOpenAndShowsItsDetails() {
        showOpenViewer(photoId)
        openInfo()

        composeRule.onRoot().performTouchInput { swipeLeft() }
        composeRule.waitUntil(WAIT_MS) { overlay.shownId == nextPhotoId && overlay.phase == OverlayPhase.OPEN }

        assertTrue(overlay.infoOpen)
        composeRule.onNodeWithText("file$nextPhotoId").assertIsDisplayed()
    }

    @Test
    fun aZoomedImageDoesNotOpenTheInfo() {
        lateinit var zoom: ZoomableImageState
        lateinit var scope: CoroutineScope
        composeRule.setContent {
            EmberlightTheme {
                scope = rememberCoroutineScope()
                overlay = remember(scope) { ViewerOverlayState(scope, mutableStateOf(photoId), { null }) }
                zoom = rememberZoomableImageState(rememberZoomableState())
                Box(modifier = Modifier.fillMaxSize()) {
                    ViewerImagePage(
                        item = viewerItems.first { it.id == photoId },
                        contentDescription = "photo",
                        imageSource = solidImageSources,
                        overlay = overlay,
                        settled = true,
                        onTap = {},
                        state = zoom,
                    )
                }
            }
        }
        composeRule.waitUntil(WAIT_MS) { zoom.isImageDisplayed }
        composeRule.runOnIdle { scope.launch { zoom.zoomableState.zoomTo(ZOOM_TO) } }
        composeRule.waitUntil(WAIT_MS) { (zoom.zoomableState.zoomFraction ?: 0f) > ZOOM_SEEN }

        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        // Gezoomt gehört die Geste dem Bild: kein Infobereich, kein Wegwischen.
        assertFalse(overlay.infoOpen)
        assertTrue(overlay.infoFraction == 0f)
    }

    private fun openInfo() {
        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.waitUntil(WAIT_MS) { overlay.infoOpen }
        composeRule.waitUntil(WAIT_MS) { overlay.infoFraction > 0.99f }
    }

    private fun showOpenViewer(openId: Long) {
        composeRule.setContent { ViewerInfoHost(viewerItems, openId) }
        composeRule.waitUntil(WAIT_MS) { ::overlay.isInitialized && overlay.phase == OverlayPhase.OPEN }
    }

    @Composable
    private fun ViewerInfoHost(
        items: List<ViewerItem>,
        openId: Long,
    ) {
        EmberlightTheme {
            val scope = rememberCoroutineScope()
            overlay = remember(scope) { ViewerOverlayState(scope, mutableStateOf<Long?>(null), { null }) }
            LaunchedEffect(Unit) { overlay.open(openId, tile = null) }
            ViewerOverlay(
                overlay = overlay,
                items = items,
                playback = remember { ViewerPlaybackViewModel(NoPlayers) },
                imageSource = solidImageSources,
                zone = ZoneId.of("Europe/Berlin"),
                exif = FakeExif,
            )
        }
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val ZOOM_TO = 2f
        const val ZOOM_SEEN = 0.2f
    }
}

/** Feste EXIF-Angaben für Fotos, ohne Datei. */
internal object FakeExif : ExifLookup {
    override suspend fun exifOf(item: ViewerItem): PhotoExif? =
        if (item.type == ViewerMediaType.IMAGE) {
            PhotoExif(
                make = "Emberlight",
                model = "TestCam 1",
                lensModel = "Test 50mm f/1.8",
                fNumber = 1.8,
                exposureSeconds = 1.0 / 250.0,
                iso = 200,
                focalLengthMm = 50.0,
                flashFired = false,
            )
        } else {
            null
        }
}
