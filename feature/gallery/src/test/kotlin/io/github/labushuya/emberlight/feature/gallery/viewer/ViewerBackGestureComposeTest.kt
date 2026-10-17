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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

/**
 * Systemrückgeste des Viewers (ADR 0010, Korrektur 4). Die Ereignisse laufen über den echten [androidx.activity.OnBackPressedDispatcher]
 * der Activity, wie vom System gemeldet. Regel: Ein normal endender Rückweg schließt immer, unabhängig vom Fortschritt. Nur ein
 * Abbruch federt zurück.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
@OptIn(ExperimentalTestApi::class)
class ViewerBackGestureComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val viewerItems = TimelineFixtures.sampleItems().map { it.toViewerItem() }
    private val first = viewerItems.first()
    private lateinit var overlay: ViewerOverlayState

    @Before
    fun setUp() {
        TestThumbnails.install(TimelineFixtures.sampleItems())
    }

    @After
    fun tearDown() {
        TestThumbnails.reset()
    }

    /** (a) started, Fortschritt 0, invoked: schließt. */
    @Test
    fun releasingAtZeroProgressClosesTheViewer() {
        showOpenViewer()

        backStarted(progress = 0f)
        backInvoked()

        awaitClosed()
    }

    /** (a2) started ohne jeden Fortschritt, invoked: schließt. Die Form eines schnellen Tastendrucks. */
    @Test
    fun releasingWithoutAnyProgressClosesTheViewer() {
        showOpenViewer()

        composeRule.runOnIdle { dispatcher().dispatchOnBackStarted(event(0f)) }
        backInvoked()

        awaitClosed()
    }

    /** (b) started, Fortschritt 0,6, invoked: schließt. */
    @Test
    fun committingAfterMostOfTheProgressClosesTheViewer() {
        showOpenViewer()

        backStarted(progress = 0.6f)
        backInvoked()

        awaitClosed()
    }

    /** (c) started, Fortschritt 0,4, cancelled: federt zurück, der Viewer bleibt offen. */
    @Test
    fun cancellingFederatesBackAndKeepsTheViewerOpen() {
        showOpenViewer()

        backStarted(progress = 0.4f)
        composeRule.runOnIdle { dispatcher().dispatchOnBackCancelled() }

        composeRule.waitUntil(timeoutMillis = WAIT_MS) { overlay.phase == OverlayPhase.OPEN }
        assertEquals(first.id, overlay.shownId)
        assertEquals(1f, overlay.progress, 0.001f)
    }

    /** (d) einfacher Zurück-Druck ohne started: schließt. */
    @Test
    fun aPlainBackPressWithoutProgressClosesTheViewer() {
        showOpenViewer()

        composeRule.runOnIdle { dispatcher().onBackPressed() }

        awaitClosed()
    }

    private fun showOpenViewer() {
        composeRule.setContent { ViewerBackHost(viewerItems, first.id) }
        composeRule.waitUntil(timeoutMillis = WAIT_MS) { ::overlay.isInitialized && overlay.phase == OverlayPhase.OPEN }
    }

    private fun backStarted(progress: Float) {
        composeRule.runOnIdle {
            dispatcher().dispatchOnBackStarted(event(0f))
            dispatcher().dispatchOnBackProgressed(event(progress))
        }
        composeRule.waitForIdle()
    }

    private fun backInvoked() {
        composeRule.runOnIdle { dispatcher().onBackPressed() }
    }

    private fun awaitClosed() {
        composeRule.waitUntil(
            timeoutMillis = WAIT_MS,
        ) { overlay.shownId == null && overlay.phase == OverlayPhase.CLOSED }
        assertNull(overlay.shownId)
    }

    private fun dispatcher() = composeRule.activity.onBackPressedDispatcher

    private fun event(progress: Float) = BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT, 0L)

    @Composable
    private fun ViewerBackHost(
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
            )
        }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
