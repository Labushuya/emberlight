package io.github.labushuya.emberlight.feature.gallery.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastScrollMappingTest {
    @Test
    fun firstAndLastItemSitAtTheEnds() {
        assertEquals(0f, FastScrollMapping.fractionFor(0, 10), 0.0001f)
        assertEquals(1f, FastScrollMapping.fractionFor(9, 10), 0.0001f)
        assertEquals(5f / 9f, FastScrollMapping.fractionFor(5, 10), 0.0001f)
    }

    @Test
    fun fractionToIndexHitsTheEnds() {
        assertEquals(0, FastScrollMapping.indexFor(0f, 50_000))
        assertEquals(49_999, FastScrollMapping.indexFor(1f, 50_000))
        assertEquals(25_000, FastScrollMapping.indexFor(0.5f, 50_001))
    }

    @Test
    fun outOfRangeFractionsAreClamped() {
        assertEquals(0, FastScrollMapping.indexFor(-0.4f, 100))
        assertEquals(99, FastScrollMapping.indexFor(1.7f, 100))
        assertEquals(0f, FastScrollMapping.fractionFor(-3, 100), 0.0001f)
        assertEquals(1f, FastScrollMapping.fractionFor(500, 100), 0.0001f)
    }

    @Test
    fun roundTripKeepsEveryIndexInPlace() {
        val total = 1_234
        for (index in listOf(0, 1, 17, 600, 1_232, 1_233)) {
            val fraction = FastScrollMapping.fractionFor(index, total)
            assertEquals(index, FastScrollMapping.indexFor(fraction, total))
        }
    }

    @Test
    fun emptyOrSingleItemListsStayAtTheStart() {
        assertEquals(0, FastScrollMapping.indexFor(0.9f, 1))
        assertEquals(0, FastScrollMapping.indexFor(0.9f, 0))
        assertEquals(0f, FastScrollMapping.fractionFor(0, 1), 0.0001f)
    }

    @Test
    fun scrollbarAppearsOnlyBeyondThreeScreens() {
        // 800 px Höhe, 100 px Zellen, 4 Spalten: drei Bildschirme sind 2.400 px, das sind 24 Zeilen zu je 4 Zellen.
        assertFalse(FastScrollMapping.isUseful(totalItems = 8, columns = 4, cellPx = 100f, viewportPx = 800f))
        assertFalse(FastScrollMapping.isUseful(totalItems = 96, columns = 4, cellPx = 100f, viewportPx = 800f))
        assertTrue(FastScrollMapping.isUseful(totalItems = 100, columns = 4, cellPx = 100f, viewportPx = 800f))
    }

    @Test
    fun scrollbarNeedsAViewportAndSaneValues() {
        assertFalse(FastScrollMapping.isUseful(totalItems = 50_000, columns = 4, cellPx = 100f, viewportPx = 0f))
        assertFalse(FastScrollMapping.isUseful(totalItems = 0, columns = 4, cellPx = 100f, viewportPx = 800f))
        assertFalse(FastScrollMapping.isUseful(totalItems = 50_000, columns = 0, cellPx = 100f, viewportPx = 800f))
        assertFalse(FastScrollMapping.isUseful(totalItems = 50_000, columns = 4, cellPx = 0f, viewportPx = 800f))
    }
}
