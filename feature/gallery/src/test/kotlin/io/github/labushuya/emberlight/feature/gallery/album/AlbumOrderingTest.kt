package io.github.labushuya.emberlight.feature.gallery.album

import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.model.Album
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumOrderingTest {
    @Test
    fun otherAlbumMovesLastEvenWhenItIsTheNewest() {
        val other = album(bucketId = null, latest = 300L)
        val camera = album(bucketId = 1L, latest = 200L)
        val downloads = album(bucketId = 3L, latest = 100L)

        val result = albumsForDisplay(listOf(other, camera, downloads))

        assertEquals(listOf(camera.bucketId, downloads.bucketId, other.bucketId), result.map { it.bucketId })
    }

    @Test
    fun namedAlbumsKeepTheRepositoryOrder() {
        val first = album(bucketId = 7L, latest = 500L)
        val second = album(bucketId = 2L, latest = 400L)
        val third = album(bucketId = 5L, latest = 100L)

        val result = albumsForDisplay(listOf(first, second, third))

        assertEquals(listOf(7L, 2L, 5L), result.map { it.bucketId })
    }

    @Test
    fun emptyListStaysEmpty() {
        assertEquals(emptyList<Album>(), albumsForDisplay(emptyList()))
    }

    @Test
    fun albumsOfLoadingIsEmpty() {
        assertEquals(emptyList<Album>(), albumsOf(MediaLibraryState.Loading).items)
    }

    @Test
    fun albumsOfReadyLibraryUsesTheDisplayOrder() {
        val items = TimelineFixtures.sampleItems()
        val library = TimelineFixtures.library(items = items)

        val result = albumsOf(MediaLibraryState.Ready(library)).items

        assertEquals(listOf(1L, 2L, 3L, null), result.map { it.bucketId })
        assertEquals(4, result.first { it.bucketId == 1L }.itemCount)
    }

    private fun album(
        bucketId: Long?,
        latest: Long,
    ): Album =
        Album(
            bucketId = bucketId,
            name = "",
            cover = TimelineFixtures.item(id = latest, millis = latest),
            itemCount = 1,
            latestTimestamp = latest,
        )
}
