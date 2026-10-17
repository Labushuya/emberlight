package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.feature.gallery.timeline.TestThumbnails
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

/**
 * Vertikale Gesten mit festen Schwellen in dp (ADR 0010, Gesten-Schwellen in dp). Kurze Flicks wirken, langsame kurze Züge federn
 * zurück, und horizontales Blättern bleibt unverändert. Die Strecken sind dp, in Pixel umgerechnet mit der Dichte der Test-Activity.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
@OptIn(ExperimentalTestApi::class)
class ViewerVerticalGestureComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val viewerItems = TimelineFixtures.sampleItems().map { it.toViewerItem() }
    private val photoId = 8L
    private val nextPhotoId = 7L
    private lateinit var overlay: ViewerOverlayState

    private val density: Float by lazy { composeRule.activity.resources.displayMetrics.density }

    @Before
    fun setUp() {
        TestThumbnails.install(TimelineFixtures.sampleItems())
    }

    @After
    fun tearDown() {
        TestThumbnails.reset()
    }

    @Test
    fun aQuickFlickUpOpensTheInfoSheet() {
        showOpenViewer(photoId)
        val d = density

        // 40 dp in 60 ms: unter der Weg-Schwelle von 56 dp, aber schnell genug für den Schnellschluss.
        composeRule.onRoot().performTouchInput {
            swipeUp(startY = height / 2f + 20f * d, endY = height / 2f - 20f * d, durationMillis = QUICK_MS)
        }
        composeRule.waitUntil(WAIT_MS) { overlay.infoOpen && overlay.infoFraction > 0.99f }
        assertEquals(photoId, overlay.shownId)
    }

    @Test
    fun aQuickFlickDownOnTheSheetClosesIt() {
        showOpenViewer(photoId)
        openInfo()
        val d = density

        composeRule.onRoot().performTouchInput {
            swipeDown(startY = height * SHEET_Y, endY = height * SHEET_Y + 40f * d, durationMillis = QUICK_MS)
        }
        composeRule.waitUntil(WAIT_MS) { !overlay.infoOpen && overlay.infoFraction == 0f }
        assertSettledNormally(photoId)
    }

    @Test
    fun aSlowShortPullUpSpringsBackWithTheSheetClosed() {
        showOpenViewer(photoId)
        val d = density

        // 20 dp in 600 ms: langsam und kurz, der Bereich bleibt zu.
        composeRule.onRoot().performTouchInput {
            swipeUp(startY = height / 2f + 10f * d, endY = height / 2f - 10f * d, durationMillis = SLOW_SHORT_MS)
        }
        composeRule.waitForIdle()
        assertFalse(overlay.infoOpen)
        assertEquals(0f, overlay.infoFraction, 0f)
        assertSettledNormally(photoId)
    }

    @Test
    fun aQuickFlickDownOnTheImageDismissesTheViewer() {
        showOpenViewer(photoId)
        val d = density

        // 120 dp in 100 ms: über der Schwelle von 96 dp und schnell.
        composeRule.onRoot().performTouchInput {
            swipeDown(startY = height * IMAGE_Y, endY = height * IMAGE_Y + 120f * d, durationMillis = DISMISS_FLICK_MS)
        }
        composeRule.waitUntil(WAIT_MS) { overlay.shownId == null && overlay.phase == OverlayPhase.CLOSED }
    }

    @Test
    fun aSlowShortPullDownOnTheImageSpringsBack() {
        showOpenViewer(photoId)
        val d = density

        // 30 dp langsam: unter der Schwelle, der Viewer bleibt offen und federt in die Ausgangslage zurück.
        composeRule.onRoot().performTouchInput {
            swipeDown(startY = height * IMAGE_Y, endY = height * IMAGE_Y + 30f * d, durationMillis = SLOW_SHORT_MS)
        }
        composeRule.waitForIdle()
        assertEquals(photoId, overlay.shownId)
        assertEquals(OverlayPhase.OPEN, overlay.phase)
        assertEquals(0f, overlay.dragY, 0f)
        assertEquals(1f, overlay.progress, 0f)
    }

    @Test
    fun aHorizontalSwipeChangesThePageAndLeavesTheSheetClosed() {
        showOpenViewer(photoId)

        composeRule.onRoot().performTouchInput { swipeLeft() }
        composeRule.waitUntil(WAIT_MS) { overlay.shownId == nextPhotoId }
        composeRule.waitForIdle()
        assertFalse(overlay.infoOpen)
        assertEquals(0f, overlay.dragY, 0f)
        assertEquals(OverlayPhase.OPEN, overlay.phase)
    }

    /** Der Infobereich ist offen und ruht. Die Strecke beginnt im Bereich, der Inhalt steht oben. */
    private fun openInfo() {
        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.waitUntil(WAIT_MS) { overlay.infoOpen }
        composeRule.waitUntil(WAIT_MS) { overlay.infoFraction > 0.99f }
    }

    /** Ruhezustand: das Bild in Normalposition, der Infobereich zu, der Viewer offen. */
    private fun assertSettledNormally(id: Long) {
        composeRule.waitForIdle()
        assertEquals(id, overlay.shownId)
        assertEquals(OverlayPhase.OPEN, overlay.phase)
        assertFalse(overlay.infoOpen)
        assertEquals(0f, overlay.infoFraction, 0f)
        assertEquals(0f, overlay.dragY, 0f)
        assertEquals(1f, overlay.progress, 0f)
    }

    private fun showOpenViewer(openId: Long) {
        composeRule.setContent { ViewerGestureHost(viewerItems, openId) }
        composeRule.waitUntil(WAIT_MS) { ::overlay.isInitialized && overlay.phase == OverlayPhase.OPEN }
    }

    @Composable
    private fun ViewerGestureHost(
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
        const val QUICK_MS = 60L
        const val SLOW_SHORT_MS = 600L
        const val DISMISS_FLICK_MS = 100L

        // Anteile der Bildschirmhöhe, innerhalb der erlaubten Zonen (nicht oben 12 %, nicht unten 5 %).
        const val IMAGE_Y = 0.25f
        const val SHEET_Y = 0.8f
    }
}
