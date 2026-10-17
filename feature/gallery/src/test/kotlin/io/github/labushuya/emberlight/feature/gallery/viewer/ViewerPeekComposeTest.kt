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
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.feature.gallery.timeline.TestThumbnails
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Kurzer Blick über die System-Rückgeste im echten Overlay (ADR 0010, Kurzer Blick). Start ohne Fortschritt führt zur Blick-Stufe
 * mit hervorgehobener Kachel. Abbruch federt ins Vollbild zurück, Vollenden schließt. Ein Fortschritt folgt dem Finger.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
@OptIn(ExperimentalTestApi::class)
class ViewerPeekComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val viewerItems = TimelineFixtures.sampleItems().map { it.toViewerItem() }
    private val openId = 8L
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
    fun holdingWithoutProgressShowsTheStageAndHighlightsThenCancelReturns() {
        showOpenViewer()

        composeRule.runOnIdle { dispatcher().dispatchOnBackStarted(event(0f)) }
        composeRule.waitUntil(WAIT_MS) { overlay.peek > FULL && overlay.highlight > FULL }
        composeRule.runOnIdle {
            assertEquals(OverlayPhase.PEEKING, overlay.phase)
            assertEquals(PEEK_STAGE, overlay.progress, EPS)
            assertTrue(overlay.heroTile != null)
        }

        composeRule.runOnIdle { dispatcher().dispatchOnBackCancelled() }
        composeRule.waitUntil(WAIT_MS) {
            overlay.phase == OverlayPhase.OPEN && overlay.progress > FULL &&
                overlay.highlight < EPS
        }

        assertEquals(openId, overlay.shownId)
        assertEquals(0f, overlay.peek, EPS)
    }

    @Test
    fun holdingThenReleasingClosesTheViewer() {
        showOpenViewer()

        composeRule.runOnIdle { dispatcher().dispatchOnBackStarted(event(0f)) }
        composeRule.waitUntil(WAIT_MS) { overlay.peek > FULL }
        composeRule.runOnIdle { dispatcher().onBackPressed() }

        composeRule.waitUntil(WAIT_MS) { overlay.shownId == null && overlay.phase == OverlayPhase.CLOSED }
        assertNull(overlay.shownId)
    }

    @Test
    fun progressDuringTheHoldFollowsTheFingerInsteadOfTheStage() {
        showOpenViewer()

        composeRule.runOnIdle {
            dispatcher().dispatchOnBackStarted(event(0f))
            dispatcher().dispatchOnBackProgressed(event(0.4f))
        }
        composeRule.waitUntil(WAIT_MS) { overlay.phase == OverlayPhase.BACK }

        composeRule.runOnIdle {
            assertEquals(0.6f, overlay.progress, EPS)
            assertEquals(0f, overlay.peek, EPS)
        }
        composeRule.runOnIdle { dispatcher().dispatchOnBackCancelled() }
        composeRule.waitUntil(WAIT_MS) { overlay.phase == OverlayPhase.OPEN }
    }

    private fun showOpenViewer() {
        composeRule.setContent { PeekHost(viewerItems, openId) }
        composeRule.waitUntil(WAIT_MS) { ::overlay.isInitialized && overlay.phase == OverlayPhase.OPEN }
    }

    private fun dispatcher() = composeRule.activity.onBackPressedDispatcher

    private fun event(progress: Float) = BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT, 0L)

    @Composable
    private fun PeekHost(
        items: List<ViewerItem>,
        openId: Long,
    ) {
        EmberlightTheme {
            val scope = rememberCoroutineScope()
            overlay =
                remember(scope) {
                    ViewerOverlayState(scope, mutableStateOf<Long?>(null), { id ->
                        if (id ==
                            openId
                        ) {
                            TILE
                        } else {
                            null
                        }
                    })
                }
            LaunchedEffect(Unit) { overlay.open(openId, tile = TILE) }
            ViewerOverlay(
                overlay = overlay,
                items = items,
                playback = remember { ViewerPlaybackViewModel(NoPlayers) },
                imageSource = solidImageSources,
                zone = ZoneId.of("Europe/Berlin"),
            )
        }
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val EPS = 0.01f
        const val FULL = 0.99f
        val TILE =
            androidx.compose.ui.geometry
                .Rect(120f, 300f, 240f, 420f)
    }
}
