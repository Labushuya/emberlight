package io.github.labushuya.emberlight.feature.gallery.timeline

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomLevelsTest {
    @Test
    fun narrowestPhoneOuterDisplayGivesTwoToSixColumnsAndIncreases() {
        // Außendisplay des Magic V2: 353 dp. Stufe 0 hat mindestens zwei Spalten, jede Stufe eine mehr.
        assertEquals(listOf(2, 3, 4, 5, 6), (0..4).map { ZoomLevels.columnsFor(it, 353f) })
    }

    @Test
    fun phoneWidthGivesTwoToSevenColumns() {
        // Handy: 412 dp ergeben 2, 3, 4, 6, 7 Spalten über alle Stufen. Der Standard liegt bei 4.
        assertEquals(listOf(2, 3, 4, 6, 7), (0..4).map { ZoomLevels.columnsFor(it, 412f) })
        assertEquals(4, ZoomLevels.columnsFor(ZoomLevels.DEFAULT_LEVEL, 412f))
    }

    @Test
    fun foldableInnerDisplayGivesThreeToTwelveColumns() {
        assertEquals(listOf(3, 6, 8, 10, 12), (0..4).map { ZoomLevels.columnsFor(it, 673f) })
    }

    @Test
    fun largePhoneGivesFourToTwelveColumns() {
        assertEquals(listOf(4, 6, 8, 10, 12), (0..4).map { ZoomLevels.columnsFor(it, 700f) })
    }

    @Test
    fun tabletWidthGivesFiveToSixteenColumns() {
        assertEquals(listOf(5, 8, 10, 14, 16), (0..4).map { ZoomLevels.columnsFor(it, 900f) })
    }

    @Test
    fun everyStageHasItsOwnColumnCountOnPhonesAndFolds() {
        // Keine zwei Stufen mit gleicher Spaltenzahl, sonst bewirkt ein Stufenwechsel nichts Sichtbares.
        listOf(353f, 412f, 673f, 700f, 900f).forEach { width ->
            val columns = (0..4).map { ZoomLevels.columnsFor(it, width) }
            assertEquals("Spalten bei $width dp: $columns", columns.distinct().size, columns.size)
            assertEquals("Spalten bei $width dp steigen nicht: $columns", columns.sorted(), columns)
        }
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
