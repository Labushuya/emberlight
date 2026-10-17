package io.github.labushuya.emberlight.feature.gallery.timeline

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.center
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.TODAY
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.ZONE
import io.github.labushuya.emberlight.feature.gallery.viewer.VIEWER_OVERLAY_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone

/**
 * Schnell-Scrollleiste (Befund C): Nur ein Ziehen am Griff bewegt die Liste. Antippen geht an die Kachel darunter, die
 * Zone außerhalb des Griffs ist für die Leiste inaktiv, und eine ausgeblendete Leiste fängt nichts ab.
 *
 * Die Zeit läuft von Hand. Die Leiste blendet 1,5 s nach der letzten Bewegung aus. Ein Wischen hinterlässt einen Fling, der
 * einen Tipp verschluckt. Deshalb macht ein kurzer Griffzug die Leiste sichtbar, und vor jeder Prüfung ist die Zeit fortgeschritten.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w412dp-h915dp")
class FastScrollGripTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val items = denseLibrary()
    private var hostZone: TimeZone? = null

    @Before
    fun setUp() {
        hostZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        TestThumbnails.install(items)
        composeRule.mainClock.autoAdvance = false
    }

    @After
    fun tearDown() {
        TestThumbnails.reset()
        TimeZone.setDefault(hostZone)
    }

    @Test
    fun tappingTheRightEdgeTileWithTheRailHiddenOpensItAndDoesNotJump() {
        show()
        scrollToTheMiddle()
        val tile = rightEdgeTileIndex()

        tapCell(tile, Offset(cellWidth(tile) - 6f, cellHeight(tile) / 2f))

        assertEquals("Die Kachel ganz rechts öffnet sich nicht.", 1, openedViewers())
        val after = cell(tile).fetchSemanticsNode().boundsInRoot
        assertEquals("Die Kachel ist nach dem Antippen vollständig sichtbar.", after.width, after.height, 0.5f)
    }

    @Test
    fun tappingTheRightEdgeTileWithTheRailVisibleOpensItAndDoesNotJump() {
        show()
        scrollToTheMiddle()
        showRailByGrip()
        val tile = rightEdgeTileIndex()

        tapCell(tile, Offset(cellWidth(tile) - 6f, cellHeight(tile) / 2f))

        assertEquals(
            "Die Kachel am rechten Rand öffnet sich nicht, solange die Leiste sichtbar ist.",
            1,
            openedViewers(),
        )
        val after = cell(tile).fetchSemanticsNode().boundsInRoot
        assertEquals("Die Kachel ist nach dem Antippen vollständig sichtbar.", after.width, after.height, 0.5f)
    }

    @Test
    fun tappingTheGripOpensTheTileUnderItAndDoesNotJump() {
        show()
        scrollToTheMiddle()
        showRailByGrip()
        assertTrue("Der Griff muss über einer Kachel am rechten Rand liegen.", moveGripOverARightEdgeTile())
        val grip = composeRule.onNodeWithTag(FAST_SCROLL_GRIP_TAG)
        val before = grip.fetchSemanticsNode().boundsInRoot

        grip.performTouchInput { click(center) }
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.waitForIdle()

        assertEquals("Die Kachel unter dem Griff öffnet sich nicht.", 1, openedViewers())
        assertEquals("Die Liste springt beim Antippen des Griffs.", before, grip.fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun draggingTheGripScrollsTheList() {
        show()
        scrollToTheMiddle()
        showRailBySwipe()
        val grip = composeRule.onNodeWithTag(FAST_SCROLL_GRIP_TAG)
        val before = grip.fetchSemanticsNode().boundsInRoot

        // Gehaltenes Ziehen: Die Uhr läuft, damit die Leiste während des Ziehens neu gezeichnet wird. Das Ziehen hält sie offen.
        composeRule.mainClock.autoAdvance = true
        grip.performTouchInput {
            down(center)
            moveBy(Offset(0f, 1_000f), delayMillis = 16)
        }
        composeRule.waitForIdle()

        // Der Finger liegt noch auf dem Griff: Blase und Griff zeigen die Position des Ziehens.
        assertTrue("Die Blase mit dem Monat des Endes fehlt.", bubbleNodes().fetchSemanticsNodes().isNotEmpty())
        assertTrue("Der Griff ist nicht mitgewandert.", before != grip.fetchSemanticsNode().boundsInRoot)
        grip.performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.waitForIdle()
    }

    @Test
    fun draggingOnTheRailOutsideTheGripDoesNotUseTheRail() {
        show()
        scrollToTheMiddle()
        showRailByGrip()
        val grip = composeRule.onNodeWithTag(FAST_SCROLL_GRIP_TAG).fetchSemanticsNode().boundsInRoot
        val rootHeight =
            composeRule
                .onRoot()
                .fetchSemanticsNode()
                .boundsInRoot.height

        // Die Zone rechts, aber weit unten. Der Griff steht weiter oben.
        composeRule.onRoot().performTouchInput {
            down(Offset(grip.right - 4f, rootHeight * 0.9f))
            moveBy(Offset(0f, -40f), delayMillis = 16)
            up()
        }
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.waitForIdle()

        assertEquals("Ohne Griff erscheint keine Blase.", 0, bubbleNodes().fetchSemanticsNodes().size)
    }

    @Test
    fun aHiddenRailDoesNotCatchTouchesAtTheEdge() {
        show()
        scrollToTheMiddle()
        val rootHeight =
            composeRule
                .onRoot()
                .fetchSemanticsNode()
                .boundsInRoot.height
        val tile = rightEdgeTileIndex()
        val before = cell(tile).fetchSemanticsNode().boundsInRoot

        // Ausgeblendet: der Griff hat keinen Hit-Test. Eine Geste am Rand scrollt wie überall sonst, ohne Blase.
        composeRule.onRoot().performTouchInput {
            down(Offset(width - 8f, rootHeight * 0.5f))
            moveBy(Offset(0f, -40f), delayMillis = 16)
            up()
        }
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.waitForIdle()

        assertEquals("Eine ausgeblendete Leiste zeigt eine Blase.", 0, bubbleNodes().fetchSemanticsNodes().size)
        assertTrue("Die Geste am Rand bewegt die Liste nicht.", before != cell(tile).fetchSemanticsNode().boundsInRoot)
    }

    private fun openedViewers(): Int = composeRule.onAllNodesWithTag(VIEWER_OVERLAY_TAG).fetchSemanticsNodes().size

    private fun show() {
        GalleryHarness(items).show(composeRule)
        composeRule.waitForIdle()
    }

    /** Wischer nach oben, danach Zeit bis zur Ruhe: der Fling ist vorbei, die Leiste ausgeblendet. */
    private fun scrollToTheMiddle() {
        repeat(MIDDLE_SWIPES) {
            composeRule.onRoot().performTouchInput {
                swipeUp(startY = height * 0.8f, endY = height * 0.2f, durationMillis = 200)
            }
        }
        composeRule.mainClock.advanceTimeBy(HIDE_AFTER_MS)
        composeRule.waitForIdle()
    }

    /** Ein kurzer Wisch macht die Leiste sichtbar. Die Zeit danach lässt den Fling auslaufen, die Leiste bleibt offen. */
    private fun showRailBySwipe() {
        composeRule.onRoot().performTouchInput {
            swipeUp(startY = height * 0.7f, endY = height * 0.6f, durationMillis = 150)
        }
        composeRule.mainClock.advanceTimeBy(SHOWN_MS)
        composeRule.waitForIdle()
    }

    /** Ein kurzer Griffzug macht die Leiste sichtbar. Danach bleibt sie für die Ruhezeit offen. */
    private fun showRailByGrip() {
        dragGripBy(GRIP_NUDGE_PX)
    }

    private fun dragGripBy(dy: Float) {
        composeRule.onNodeWithTag(FAST_SCROLL_GRIP_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, dy), delayMillis = 16)
            up()
        }
        composeRule.mainClock.advanceTimeBy(SHOWN_MS)
        composeRule.waitForIdle()
    }

    /** Verschiebt den Griff in Schritten, bis seine Mitte über einer vollständig sichtbaren Randkachel liegt. */
    private fun moveGripOverARightEdgeTile(): Boolean {
        repeat(MAX_OVERLAP_STEPS) {
            if (rightEdgeTileUnderGrip()) return true
            dragGripBy(SMALL_STEP_PX)
        }
        return rightEdgeTileUnderGrip()
    }

    private fun rightEdgeTileUnderGrip(): Boolean {
        val grip = composeRule.onNodeWithTag(FAST_SCROLL_GRIP_TAG).fetchSemanticsNode().boundsInRoot
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        return composeRule
            .onAllNodes(anyMediaCell())
            .fetchSemanticsNodes()
            .any { node ->
                fullyVisible(node.boundsInRoot, root) &&
                    node.boundsInRoot.right > root.right - RIGHT_EDGE_BAND_PX &&
                    node.boundsInRoot.contains(grip.center)
            }
    }

    /** Index der Kachel ganz rechts, die am weitesten oben im sichtbaren Bereich steht. */
    private fun rightEdgeTileIndex(): Int {
        val nodes = composeRule.onAllNodes(anyMediaCell()).fetchSemanticsNodes()
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val visible = nodes.withIndex().filter { (_, node) -> fullyVisible(node.boundsInRoot, root) }
        val rightmost = visible.maxOf { it.value.boundsInRoot.right }
        return visible.filter { it.value.boundsInRoot.right == rightmost }.minBy { it.value.boundsInRoot.top }.index
    }

    private fun tapCell(
        index: Int,
        at: Offset,
    ) {
        cell(index).performTouchInput { click(at) }
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.waitForIdle()
    }

    private fun cellWidth(index: Int): Float = cell(index).fetchSemanticsNode().boundsInRoot.width

    private fun cellHeight(index: Int): Float = cell(index).fetchSemanticsNode().boundsInRoot.height

    private fun fullyVisible(
        cell: Rect,
        root: Rect,
    ): Boolean = cell.top >= root.top && cell.bottom <= root.bottom && cell.width > 0f

    private fun cell(index: Int) = composeRule.onAllNodes(anyMediaCell())[index]

    private fun anyMediaCell() =
        SemanticsMatcher("media cell") { node ->
            node.config
                .getOrNull(
                    SemanticsProperties.ContentDescription,
                )?.any { it.startsWith("Photo from") } == true &&
                generateSequence(node) { it.parent }.none {
                    it.config.getOrNull(SemanticsProperties.TestTag) ==
                        VIEWER_OVERLAY_TAG
                }
        }

    /** Blase mit dem Monat des ältesten Eintrags, also dem Ende der Leiste. */
    private fun bubbleNodes() = composeRule.onAllNodes(hasText(oldestMonthText()))

    private fun oldestMonthText(): String = monthLabel(oldestDate())

    private fun oldestDate(): LocalDate = Instant.ofEpochMilli(items.last().timestamp).atZone(ZONE).toLocalDate()

    /** Erwarteter Blasentext in der Sprache der Tests, über dieselben Formatierer wie die App. */
    private fun monthLabel(date: LocalDate): String =
        TimelineFormats(Locale.ENGLISH, ZONE).scrollLabel(GroupingMode.DAY, date)

    private companion object {
        const val MIDDLE_SWIPES = 6
        const val MAX_OVERLAP_STEPS = 60
        const val GRIP_NUDGE_PX = 40f
        const val SMALL_STEP_PX = 37f

        /** Lang genug, dass die Einblendung fertig ist, und kürzer als die Ruhezeit, nach der die Leiste ausblendet. */
        const val SHOWN_MS = 800L

        /** Länger als die Ruhezeit, nach der die Leiste ausblendet. */
        const val HIDE_AFTER_MS = 2_000L

        /** Kacheln, die bis auf diesen Abstand am rechten Rand liegen, gelten als Randkachel. */
        const val RIGHT_EDGE_BAND_PX = 80f
    }
}

/**
 * Viele Einträge je Tag: Vier Einträge pro Tag ergeben vier Kacheln in einer Zeile, damit es eine Kachel ganz rechts gibt.
 * Neueste zuerst, wie der Index.
 */
internal fun denseLibrary(count: Int = 400): List<MediaItem> =
    (0 until count).map { i ->
        val day = TODAY.minusDays((i / 4).toLong())
        val minutes = (3 - i % 4) * 10L
        val millis =
            day
                .atTime(9, 0)
                .plusMinutes(minutes)
                .atZone(ZONE)
                .toInstant()
                .toEpochMilli()
        TimelineFixtures.item(id = 3_000L + i, millis = millis)
    }
