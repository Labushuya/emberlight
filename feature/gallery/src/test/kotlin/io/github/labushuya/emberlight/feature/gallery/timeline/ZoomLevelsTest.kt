package io.github.labushuya.emberlight.feature.gallery.timeline

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomLevelsTest {
    @Test
    fun phoneWidthGivesTwoToSevenColumns() {
        // Handy quer und hoch: 412 dp ergeben 2, 3, 4, 5, 7 Spalten über alle Stufen.
        assertEquals(listOf(2, 3, 4, 5, 7), (0..4).map { ZoomLevels.columnsFor(it, 412f) })
    }

    @Test
    fun foldableInnerDisplayGivesThreeToTwelveColumns() {
        assertEquals(listOf(3, 5, 7, 9, 12), (0..4).map { ZoomLevels.columnsFor(it, 673f) })
    }

    @Test
    fun tabletWidthGivesFiveToSeventeenColumns() {
        assertEquals(listOf(5, 7, 10, 12, 17), (0..4).map { ZoomLevels.columnsFor(it, 900f) })
    }

    @Test
    fun invalidWidthsFallBackToOneColumn() {
        assertEquals(1, ZoomLevels.columnsFor(2, 0f))
        assertEquals(1, ZoomLevels.columnsFor(2, -50f))
        assertEquals(1, ZoomLevels.columnsFor(2, 10f))
        assertEquals(1, ZoomLevels.columnsFor(2, Float.NaN))
        assertEquals(1, ZoomLevels.columnsFor(2, Float.POSITIVE_INFINITY))
    }

    @Test
    fun outOfRangeLevelsUseTheNearestStage() {
        assertEquals(ZoomLevels.columnsFor(0, 412f), ZoomLevels.columnsFor(-3, 412f))
        assertEquals(ZoomLevels.columnsFor(4, 412f), ZoomLevels.columnsFor(9, 412f))
    }

    @Test
    fun daysUpToLevelTwoAndMonthsFromLevelThree() {
        assertEquals(GroupingMode.DAY, ZoomLevels.groupingFor(0))
        assertEquals(GroupingMode.DAY, ZoomLevels.groupingFor(ZoomLevels.LAST_DAY_LEVEL))
        assertEquals(GroupingMode.MONTH, ZoomLevels.groupingFor(ZoomLevels.LAST_DAY_LEVEL + 1))
        assertEquals(GroupingMode.MONTH, ZoomLevels.groupingFor(4))
    }

    @Test
    fun groupingFollowsTheStageNotTheColumnCount() {
        // Tablet auf Standardstufe: viele Spalten, trotzdem Tagesgruppen.
        val columns = ZoomLevels.columnsFor(ZoomLevels.DEFAULT_LEVEL, 900f)

        assertEquals(10, columns)
        assertEquals(GroupingMode.DAY, ZoomLevels.groupingFor(ZoomLevels.DEFAULT_LEVEL))
    }

    @Test
    fun clampKeepsLevelsInRange() {
        assertEquals(0, ZoomLevels.clamp(-1))
        assertEquals(2, ZoomLevels.clamp(2))
        assertEquals(4, ZoomLevels.clamp(5))
    }

    @Test
    fun tileWidthCountsOuterMarginsAsGaps() {
        // 412 dp minus (4 + 1) Abstände von 2 dp, geteilt durch 4 Spalten.
        assertEquals(100.5f, ZoomLevels.tileWidth(412f, 2f, 4), 0.001f)
    }
}
