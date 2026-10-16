package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.feature.gallery.R
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.TimeZone
import kotlin.math.abs

/**
 * Kopfbereich der Galerie (Befund B): Die Schnell-Scrollleiste beginnt unter der Kopfzeile, die erste Überschrift sitzt
 * direkt darunter, und am Listenanfang lässt sich nicht weiter nach oben scrollen (ADR 0008).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w412dp-h915dp")
class GridTopEdgeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val items = TimelineFixtures.longLibrary()
    private var hostZone: TimeZone? = null

    @Before
    fun setUp() {
        hostZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        TestThumbnails.install(items)
    }

    @After
    fun tearDown() {
        TestThumbnails.reset()
        TimeZone.setDefault(hostZone)
    }

    @Test
    fun theFastScrollGripStartsBelowTheHeader() {
        GalleryHarness(items).show(composeRule)
        composeRule.waitForIdle()

        val grip = composeRule.onNodeWithTag(FAST_SCROLL_GRIP_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Der Griff liegt unter der Kopfzeile: ${grip.top} < ${tabRowBottom()}",
            grip.top >= tabRowBottom() - TOLERANCE,
        )
    }

    @Test
    fun theFirstHeadingSitsDirectlyBelowTheHeader() {
        GalleryHarness(items).show(composeRule)
        composeRule.waitForIdle()

        val heading = firstHeadingTop()
        val gap = heading - tabRowBottom()
        assertTrue("Zwischen Kopfzeile und erster Überschrift liegt leerer Raum: $gap px", abs(gap) <= TOLERANCE)
    }

    @Test
    fun scrollingUpAtTheStartDoesNotMoveTheList() {
        GalleryHarness(items).show(composeRule)
        composeRule.waitForIdle()
        val before = firstHeadingTop()

        repeat(5) {
            composeRule.onAllNodes(anyMediaCell()).onFirst().performTouchInput {
                swipeDown(startY = height * 0.3f, endY = height * 0.9f, durationMillis = 100)
            }
        }
        composeRule.waitForIdle()

        assertTrue(
            "Am Listenanfang verschiebt sich der Inhalt nach oben: $before -> ${firstHeadingTop()}",
            abs(firstHeadingTop() - before) <= TOLERANCE,
        )
    }

    @Test
    fun theContentScrollsOnlyBelowTheHeaderWhileCollapsing() {
        GalleryHarness(items).show(composeRule)
        composeRule.waitForIdle()
        repeat(6) {
            composeRule.onAllNodes(anyMediaCell()).onFirst().performTouchInput {
                swipeUp(startY = height * 0.7f, endY = height * 0.3f, durationMillis = 100)
            }
        }
        composeRule.waitForIdle()

        val top = tabRowBottom()
        val cells = composeRule.onAllNodes(anyMediaCell()).fetchSemanticsNodes()
        val hidden = cells.filter { it.boundsInRoot.bottom <= top + TOLERANCE }
        val partlyUnder = cells.filter { it.boundsInRoot.top < top - TOLERANCE && it.boundsInRoot.bottom > top }
        assertTrue(
            "Eine Zelle liegt unter der Kopfzeile: ${partlyUnder.map { it.boundsInRoot.top }}",
            partlyUnder.isEmpty(),
        )
        assertTrue("Der Test braucht sichtbare Zellen.", cells.size > hidden.size)
    }

    private fun tabRowBottom(): Float =
        composeRule
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .onFirst()
            .fetchSemanticsNode()
            .boundsInRoot.bottom

    private fun firstHeadingTop(): Float {
        val today =
            ApplicationProvider.getApplicationContext<android.content.Context>().getString(
                R.string.feature_gallery_header_today,
            )
        return composeRule
            .onNode(hasText(today) and isHeading())
            .fetchSemanticsNode()
            .boundsInRoot.top
    }

    private fun anyMediaCell() =
        SemanticsMatcher("media cell") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Photo from") } == true
        }

    private companion object {
        /** Eine Pixel-Toleranz für Rundung bei Dichte 2,625. Ein Dp entspricht etwa 2,6 Pixeln. */
        const val TOLERANCE = 3f
    }
}
