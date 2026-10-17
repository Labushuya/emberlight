package io.github.labushuya.emberlight.demo

import io.github.labushuya.emberlight.core.media.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die synthetische Demo-Bibliothek (ADR 0011): Determinismus, Verteilung, Videoanteil, Alben und Zeitraum. */
class SyntheticLibraryTest {
    private val items by lazy { SyntheticLibrary.items() }

    @Test
    fun theSameIndexGivesTheSameEntryEveryTime() {
        assertEquals(SyntheticLibrary.itemAt(4_321), SyntheticLibrary.itemAt(4_321))
        assertEquals(SyntheticLibrary.items().take(50), SyntheticLibrary.items().take(50))
    }

    @Test
    fun theLibraryHasTheConfiguredSize() {
        assertEquals(SyntheticLibrary.COUNT, items.size)
        assertEquals(SyntheticLibrary.COUNT, items.map { it.id }.toSet().size)
    }

    @Test
    fun entriesAreSortedNewestFirstLikeTheIndex() {
        val timestamps = items.map { it.timestamp }
        assertTrue("Die Zeitstempel müssen streng absteigen.", timestamps.zipWithNext().all { (a, b) -> a > b })
    }

    @Test
    fun theLibrarySpansAboutThreeYears() {
        val spanDays = (items.first().timestamp - items.last().timestamp) / DAY_MS
        assertTrue("Spanne ist $spanDays Tage, erwartet etwa 1095.", spanDays in 1090L..1100L)
    }

    @Test
    fun aboutOneInTenEntriesIsAVideo() {
        val videos = items.count { it.type == MediaType.VIDEO }
        val share = videos.toDouble() / items.size
        assertTrue("Videoanteil ist $share, erwartet etwa 0,10.", share in 0.085..0.115)
        items.filter { it.type == MediaType.VIDEO }.forEach { video ->
            val duration = requireNotNull(video.durationMs)
            assertTrue("Videodauer $duration ms liegt außerhalb des Bereichs.", duration in 5_000L until 305_000L)
        }
        items.filter { it.type == MediaType.IMAGE }.forEach { assertNull(it.durationMs) }
    }

    @Test
    fun twelveAlbumsShareTheEntries() {
        val albums = SyntheticLibrary.albums(items)
        assertEquals(SyntheticLibrary.ALBUM_COUNT, albums.size)
        assertEquals(items.size, albums.sumOf { it.itemCount })
        albums.forEach { album ->
            assertTrue("Album ${album.name} ist leer.", album.itemCount > 0)
            // Ein Album hat etwa ein Zwölftel der Einträge, nicht mehr als das Doppelte.
            assertTrue("Album ${album.name} hat ${album.itemCount} Einträge.", album.itemCount in 1_200..2_300)
        }
        assertEquals(albums.sortedByDescending { it.latestTimestamp }, albums)
    }

    @Test
    fun allFourShapesOccur() {
        val shapes = items.map { (it.width ?: 0) to (it.height ?: 0) }.toSet()
        assertTrue("Hochformat fehlt.", shapes.any { (w, h) -> h > w && h / w.toDouble() < 1.5 })
        assertTrue("Querformat fehlt.", shapes.any { (w, h) -> w > h && w / h.toDouble() < 1.5 })
        assertTrue("Quadrat fehlt.", shapes.any { (w, h) -> w == h })
        assertTrue("Panorama fehlt.", shapes.any { (w, h) -> w / h.toDouble() >= 2.5 })
    }

    @Test
    fun uriRoundTripsToTheIndex() {
        val item = SyntheticLibrary.itemAt(777)
        assertEquals(777, SyntheticLibrary.indexOf(item.uri))
        assertNull(SyntheticLibrary.indexOf("content://media/external/images/media/5"))
        assertNull(SyntheticLibrary.indexOf("emberlight-demo://media/0"))
        assertNull(SyntheticLibrary.indexOf("emberlight-demo://media/${SyntheticLibrary.COUNT + 1}"))
    }

    @Test
    fun indicesOutsideTheLibraryAreRejected() {
        val rejected = runCatching { SyntheticLibrary.itemAt(SyntheticLibrary.COUNT) }
        assertTrue(rejected.isFailure)
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
