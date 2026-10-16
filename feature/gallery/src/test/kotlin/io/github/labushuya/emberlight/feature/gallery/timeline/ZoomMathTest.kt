package io.github.labushuya.emberlight.feature.gallery.timeline

import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.TODAY
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.ZONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomMathTest {
    @Test
    fun twoPercentOverTheThresholdEnlargesOneStage() {
        assertEquals(1, PinchMath.levelStep(1.31f, 2))
        assertEquals(3, PinchMath.levelStep(0.76f, 2))
    }

    @Test
    fun belowTheThresholdNothingChanges() {
        assertNull(PinchMath.levelStep(1.29f, 2))
        assertNull(PinchMath.levelStep(0.78f, 2))
        assertNull(PinchMath.levelStep(1f, 2))
    }

    @Test
    fun edgeStagesDoNotSwitchFurther() {
        assertNull(PinchMath.levelStep(2f, ZoomLevels.MIN_LEVEL))
        assertNull(PinchMath.levelStep(0.5f, ZoomLevels.MAX_LEVEL))
    }

    @Test
    fun edgeResistanceDampsTheMovement() {
        assertEquals(1f, PinchMath.resisted(1f), 0.0001f)
        assertEquals(1.25f, PinchMath.resisted(2f), 0.0001f)
        assertEquals(0.875f, PinchMath.resisted(0.5f), 0.0001f)
    }

    @Test
    fun pickReturnsTheMediaCellContainingThePoint() {
        val cells = listOf(header(top = 0f), media("a", left = 0f, top = 40f), media("b", left = 102f, top = 40f))

        assertEquals("b", ZoomAnchor.pick(cells, x = 150f, y = 60f)?.key)
    }

    @Test
    fun pickIgnoresHeadersEvenIfThePointIsInside() {
        val cells = listOf(header(top = 0f), media("a", left = 0f, top = 40f))

        assertEquals("a", ZoomAnchor.pick(cells, x = 50f, y = 10f)?.key)
    }

    @Test
    fun pickFallsBackToTheNearestMediaCell() {
        val cells = listOf(media("near", left = 0f, top = 0f), media("far", left = 400f, top = 400f))

        assertEquals("near", ZoomAnchor.pick(cells, x = 110f, y = 50f)?.key)
    }

    @Test
    fun pickIsNullWithoutVisibleMedia() {
        assertNull(ZoomAnchor.pick(listOf(header(top = 0f)), x = 10f, y = 10f))
    }

    @Test
    fun fractionWithinTheCellIsClamped() {
        val cell = media("a", left = 0f, top = 100f, size = 50f)

        assertEquals(0.5f, ZoomAnchor.fractionWithin(y = 125f, cell = cell), 0.0001f)
        assertEquals(0f, ZoomAnchor.fractionWithin(y = 50f, cell = cell), 0.0001f)
        assertEquals(1f, ZoomAnchor.fractionWithin(y = 400f, cell = cell), 0.0001f)
    }

    @Test
    fun targetTopKeepsThePointUnderThePivot() {
        // Der Punkt in der Mitte der Zelle liegt beim Wechsel auf 100 dp Kacheln genau unter der Fingermitte bei 300.
        assertEquals(250f, ZoomAnchor.targetTop(pivotY = 300f, fraction = 0.5f, tileNew = 100f), 0.0001f)
    }

    @Test
    fun scrollOffsetPlacesTheCellTopAtTheTarget() {
        assertEquals(-242, ZoomAnchor.scrollOffset(targetTop = 250f, paddingTop = 8f))
    }

    @Test
    fun anchorSurvivesADayToMonthChange() {
        val items = TimelineFixtures.sampleItems()
        val anchorUri = items[4].uri // 12. Oktober, Tagesgruppe
        val days = TimelineGrouping.group(items, GroupingMode.DAY, TODAY, ZONE)
        val months = TimelineGrouping.group(items, GroupingMode.MONTH, TODAY, ZONE)

        val dayIndex = TimelineIndex(days, leadingItems = 0, today = TODAY)
        val monthIndex = TimelineIndex(months, leadingItems = 0, today = TODAY)

        val position = monthIndex.indexOfMedia(anchorUri)!!
        val monthSection = monthIndex.sectionAt(position)!!
        assertEquals(TimelineHeader.Month(java.time.YearMonth.of(2026, 10)), monthSection.header)
        assertTrue(monthSection.items.any { it.uri == anchorUri })
        assertTrue(dayIndex.indexOfMedia(anchorUri)!! != position)
    }

    private fun header(top: Float) =
        GridCell(key = "day:x", isMedia = false, left = 0f, top = top, width = 400f, height = 40f)

    private fun media(
        key: String,
        left: Float,
        top: Float,
        size: Float = 100f,
    ) = GridCell(key = key, isMedia = true, left = left, top = top, width = size, height = size)
}
