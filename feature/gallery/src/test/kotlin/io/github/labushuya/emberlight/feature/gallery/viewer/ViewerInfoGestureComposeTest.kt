package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.activity.BackEventCompat
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
 * Geste des Infobereichs nach unten (ADR 0010, Infobereich). Zwei Befunde vom Gerät:
 *
 * - (a) Der Infobereich ist offen und der Inhalt oben: Wischen nach unten auf dem Bereich schließt ihn nicht. Der scrollbare
 *   Inhalt verbraucht die Geste.
 * - (b) Der Infobereich ist offen: Wischen nach unten auf dem Bild schließt ihn, aber der Viewer bleibt mit verkleinertem,
 *   versetztem Bild stehen.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
@OptIn(ExperimentalTestApi::class)
class ViewerInfoGestureComposeTest {
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
    fun swipingDownOnTheSheetWithContentAtTopClosesIt() {
        showOpenViewer(photoId)
        openInfo()

        // Start auf dem Infobereich (untere 60 %), Inhalt steht oben.
        composeRule.onRoot().performTouchInput { swipeDown(startY = height * SHEET_START, endY = height * SHEET_END) }
        composeRule.waitUntil(WAIT_MS) { !overlay.infoOpen && overlay.infoFraction == 0f }
        composeRule.waitForIdle()

        assertEquals(photoId, overlay.shownId)
        assertEquals(OverlayPhase.OPEN, overlay.phase)
        assertEquals(0f, overlay.dragY, 0f)
        assertEquals(1f, overlay.progress, 0f)
    }

    @Test
    fun swipingDownOnTheImageWhileTheSheetIsOpenClosesOnlyTheSheet() {
        showOpenViewer(photoId)
        openInfo()

        composeRule.onRoot().performTouchInput { swipeDown(startY = height * IMAGE_START, endY = height * IMAGE_END) }
        composeRule.waitUntil(WAIT_MS) { !overlay.infoOpen && overlay.infoFraction == 0f }
        assertSettledNormally(photoId)

        // Die Phase darf nicht hängen: auch nach einer Weile steht das Bild in Normalposition.
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(HOLD_MS)
        assertSettledNormally(photoId)
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun aSlowSwipeDownOnTheImageWhileTheSheetIsOpenClosesOnlyTheSheet() {
        showOpenViewer(photoId)
        openInfo()

        composeRule.onRoot().performTouchInput {
            swipeDown(startY = height * IMAGE_START, endY = height * IMAGE_END, durationMillis = SLOW_SWIPE_MS)
        }
        composeRule.waitUntil(WAIT_MS) { !overlay.infoOpen && overlay.infoFraction == 0f }
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(HOLD_MS)
        assertSettledNormally(photoId)
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun aSecondSwipeDownAfterTheSheetClosedDismissesTheViewer() {
        showOpenViewer(photoId)
        openInfo()
        composeRule.onRoot().performTouchInput { swipeDown(startY = height * IMAGE_START, endY = height * IMAGE_END) }
        composeRule.waitUntil(WAIT_MS) { !overlay.infoOpen && overlay.infoFraction == 0f }
        composeRule.waitForIdle()

        composeRule.onRoot().performTouchInput { swipeDown(startY = height * IMAGE_START, endY = height * DISMISS_END) }
        composeRule.waitUntil(WAIT_MS) { overlay.shownId == null && overlay.phase == OverlayPhase.CLOSED }
        assertFalse(overlay.infoOpen)
    }

    /** Ruhezustand nach dem Schließen des Bereichs: Bild in Normalposition, nichts hängt, der Viewer bleibt offen. */
    private fun assertSettledNormally(id: Long) {
        composeRule.waitForIdle()
        assertEquals(id, overlay.shownId)
        assertEquals(OverlayPhase.OPEN, overlay.phase)
        assertFalse(overlay.infoOpen)
        assertEquals(0f, overlay.infoFraction, 0f)
        assertEquals(0f, overlay.dragY, 0f)
        assertEquals(1f, overlay.progress, 0f)
        assertTrue(overlay.heroAlpha == 0f)
    }

    /**
     * Übergang ohne Endzustand: Die Rückgeste wird abgebrochen (Blick-Stufe, 0,8), und ein Ziehen nach unten kommt, bevor die
     * Feder zurückgelaufen ist. Das Ziehen bricht die Feder ab, der Fortschritt bliebe bei 0,8 stehen: Der Viewer hängt
     * mit verkleinertem, versetztem Bild. Danach muss das Bild in Normalposition stehen.
     */
    @Test
    fun aDragDuringTheCancelSettleEndsWithTheImageInNormalPosition() {
        showOpenViewer(photoId)
        composeRule.mainClock.autoAdvance = false
        dispatchBackStarted()
        advanceUntil { overlay.phase == OverlayPhase.PEEKING && overlay.progress < PEEK_STAGE_REACHED }

        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        composeRule.onRoot().performTouchInput {
            swipeDown(startY = height * IMAGE_START, endY = height * SMALL_END, durationMillis = FAST_SWIPE_MS)
        }

        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(photoId, overlay.shownId)
        assertEquals(OverlayPhase.OPEN, overlay.phase)
        assertEquals(1f, overlay.progress, 0f)
        assertEquals(0f, overlay.peek, 0f)
        assertEquals(0f, overlay.dragY, 0f)
    }

    /**
     * Gestenpriorität während des Einfahrens: Der Infobereich schließt gerade (Feder läuft, der Stand ist noch über 0). Ein neues
     * Ziehen nach unten auf dem Bild gehört dem Bereich, nicht dem Wegwischen. Erst wenn der Bereich zu ist, darf es wegwischen.
     */
    @Test
    fun aNewDragDownWhileTheSheetIsStillClosingDoesNotDismissTheViewer() {
        showOpenViewer(photoId)
        openInfo()
        composeRule.mainClock.autoAdvance = false

        composeRule.onRoot().performTouchInput {
            swipeDown(startY = height * IMAGE_START, endY = height * PARTIAL_END, durationMillis = FAST_SWIPE_MS)
        }
        assertFalse(overlay.infoOpen)
        assertTrue(overlay.infoFraction > 0f)
        composeRule.onRoot().performTouchInput {
            swipeDown(startY = height * IMAGE_START, endY = height * DISMISS_END, durationMillis = FAST_SWIPE_MS)
        }

        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(photoId, overlay.shownId)
        assertEquals(OverlayPhase.OPEN, overlay.phase)
        assertEquals(0f, overlay.dragY, 0f)
        assertEquals(1f, overlay.progress, 0f)
    }

    /** Lässt die Uhr Bild für Bild laufen, bis [done] gilt (höchstens [MAX_ADVANCE_FRAMES] Bilder). */
    private fun advanceUntil(done: () -> Boolean) {
        repeat(MAX_ADVANCE_FRAMES) {
            if (done()) return
            composeRule.mainClock.advanceTimeByFrame()
        }
        assertTrue("Zustand nicht erreicht", done())
    }

    private fun dispatchBackStarted() {
        composeRule.runOnIdle {
            val event = BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT, 0L)
            composeRule.activity.onBackPressedDispatcher.dispatchOnBackStarted(event)
        }
    }

    private fun openInfo() {
        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.waitUntil(WAIT_MS) { overlay.infoOpen }
        composeRule.waitUntil(WAIT_MS) { overlay.infoFraction > 0.99f }
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
        const val HOLD_MS = 4_000L
        const val SLOW_SWIPE_MS = 900L
        const val FAST_SWIPE_MS = 120L
        const val PARTIAL_END = 0.55f
        const val PEEK_STAGE_REACHED = 0.81f
        const val MAX_ADVANCE_FRAMES = 400

        // Anteile der Bildschirmhöhe: Das Bild liegt oben (bis 40 %), der Infobereich unten (ab 40 %).
        const val SHEET_START = 0.55f
        const val SHEET_END = 0.95f
        const val IMAGE_START = 0.2f
        const val IMAGE_END = 0.9f
        const val DISMISS_END = 0.97f
        const val SMALL_END = 0.3f
    }
}
