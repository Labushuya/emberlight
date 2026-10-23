package io.github.labushuya.emberlight.share.space.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportSelectionTest {
    private val a = ExportCandidate("a", sizeBytes = 10, locationKept = false, alreadyExported = false)
    private val b = ExportCandidate("b", sizeBytes = 20, locationKept = true, alreadyExported = false)
    private val c = ExportCandidate("c", sizeBytes = 30, locationKept = false, alreadyExported = true)

    @Test
    fun theDefaultSelectionIsEmpty() {
        val selection = ExportSelection(listOf(a, b, c))

        assertEquals(0, selection.count)
        assertEquals(0L, selection.totalBytes)
        assertTrue(selection.chosen.isEmpty())
    }

    @Test
    fun allSelectsEveryMediumThatIsNotExportedYet() {
        val selection = ExportSelection(listOf(a, b, c)).selectAll()

        assertEquals(2, selection.count)
        assertEquals(30L, selection.totalBytes)
        assertEquals(1, selection.withLocationCount)
        assertEquals(listOf("a", "b"), selection.chosen.map { it.mediaId })
    }

    @Test
    fun noneClearsTheSelection() {
        val selection = ExportSelection(listOf(a, b, c)).selectAll().selectNone()

        assertEquals(0, selection.count)
    }

    @Test
    fun toggleFlipsAMediumAndIgnoresExportedAndUnknownIds() {
        val start = ExportSelection(listOf(a, b, c))

        val on = start.toggle("a")
        assertEquals(1, on.count)
        assertEquals(0, on.toggle("a").count)
        assertEquals(0, start.toggle("c").count)
        assertEquals(0, start.toggle("unbekannt").count)
    }

    @Test
    fun exportedMediaAreNeverSelectable() {
        val selection = ExportSelection(listOf(a, b, c))

        assertEquals(listOf("a", "b"), selection.selectable.map { it.mediaId })
        assertFalse(selection.selectAll().chosen.any { it.alreadyExported })
    }

    @Test
    fun aRefreshKeepsSelectionsThatAreStillValid() {
        val selected = ExportSelection(listOf(a, b, c)).selectAll()

        val refreshed = selected.refreshed(listOf(a, b.copy(alreadyExported = true), c))

        assertEquals(listOf("a"), refreshed.chosen.map { it.mediaId })
        assertEquals(1, refreshed.count)
    }

    @Test
    fun aRemovedMediumDropsOutOfTheSelection() {
        val selected = ExportSelection(listOf(a, b)).selectAll()

        val refreshed = selected.refreshed(listOf(a))

        assertEquals(listOf("a"), refreshed.chosen.map { it.mediaId })
    }

    @Test
    fun theRequiredSpaceIsTheTotalPlusTheReserve() {
        assertEquals(12L, ExportSpacePolicy.requiredBytes(10L, 2L))
        assertEquals(Long.MAX_VALUE, ExportSpacePolicy.requiredBytes(Long.MAX_VALUE, 1L))
        assertTrue(ExportSpacePolicy.fits(availableBytes = 12L, totalBytes = 10L, reserveBytes = 2L))
        assertFalse(ExportSpacePolicy.fits(availableBytes = 11L, totalBytes = 10L, reserveBytes = 2L))
        assertThrows(IllegalArgumentException::class.java) { ExportSpacePolicy.requiredBytes(-1L, 0L) }
    }
}
