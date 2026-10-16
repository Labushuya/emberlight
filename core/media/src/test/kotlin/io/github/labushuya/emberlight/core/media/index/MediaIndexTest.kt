package io.github.labushuya.emberlight.core.media.index

import io.github.labushuya.emberlight.core.media.model.Album
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.model.TimestampSource
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaIndexTest {
    @Test
    fun takenDateWinsWhenPositive() {
        val resolved = MediaIndex.resolveTimestamp(dateTakenMs = 5_000L, dateModifiedSec = 9L, dateAddedSec = 8L)
        assertEquals(5_000L, resolved.millis)
        assertEquals(TimestampSource.DATE_TAKEN, resolved.source)
    }

    @Test
    fun zeroOrNegativeTakenFallsBackToModifiedInSeconds() {
        val zero = MediaIndex.resolveTimestamp(dateTakenMs = 0L, dateModifiedSec = 9L, dateAddedSec = 8L)
        assertEquals(9_000L, zero.millis)
        assertEquals(TimestampSource.DATE_MODIFIED, zero.source)

        val negative = MediaIndex.resolveTimestamp(dateTakenMs = -1L, dateModifiedSec = 9L, dateAddedSec = 8L)
        assertEquals(9_000L, negative.millis)
        assertEquals(TimestampSource.DATE_MODIFIED, negative.source)
    }

    @Test
    fun zeroModifiedFallsBackToAddedInSeconds() {
        val resolved = MediaIndex.resolveTimestamp(dateTakenMs = 0L, dateModifiedSec = 0L, dateAddedSec = 8L)
        assertEquals(8_000L, resolved.millis)
        assertEquals(TimestampSource.DATE_ADDED, resolved.source)
    }

    @Test
    fun allMissingEndsAtAddedWithZero() {
        val resolved = MediaIndex.resolveTimestamp(0L, 0L, 0L)
        assertEquals(0L, resolved.millis)
        assertEquals(TimestampSource.DATE_ADDED, resolved.source)
    }

    @Test
    fun buildSortsNewestFirstAcrossBothTypes() {
        val images = listOf(image(1, 1_000), image(2, 3_000))
        val videos = listOf(video(3, 2_000), video(4, 4_000))
        val library = MediaIndex.build(images, videos, MediaAccess.FULL)
        assertEquals(listOf(4L, 2L, 3L, 1L), library.items.map { it.id })
        assertEquals(MediaAccess.FULL, library.mediaAccess)
    }

    @Test
    fun equalTimestampsTieBreakByTypeThenId() {
        // Gleicher Zeitstempel: Bild vor Video, innerhalb eines Typs die kleinere ID zuerst.
        val images = listOf(image(5, 1_000), image(2, 1_000))
        val videos = listOf(video(1, 1_000))
        val library = MediaIndex.build(images, videos, MediaAccess.FULL)
        assertEquals(
            listOf(MediaType.IMAGE to 2L, MediaType.IMAGE to 5L, MediaType.VIDEO to 1L),
            library.items.map { it.type to it.id },
        )
    }

    @Test
    fun mergeKeepsOrderAndDoesNotMutateInputs() {
        val first = listOf(image(1, 9_000), image(2, 5_000), image(3, 1_000))
        val second = listOf(video(10, 8_000), video(11, 5_000), video(12, 0))
        val firstCopy = first.toList()
        val secondCopy = second.toList()

        val merged = MediaIndex.mergeSorted(first, second)

        assertEquals(listOf(1L, 10L, 2L, 11L, 3L, 12L), merged.map { it.id })
        assertEquals(firstCopy, first)
        assertEquals(secondCopy, second)
    }

    @Test
    fun mergeHandlesEmptyLists() {
        val items = listOf(image(1, 1_000))
        assertEquals(items, MediaIndex.mergeSorted(items, emptyList()))
        assertEquals(items, MediaIndex.mergeSorted(emptyList(), items))
        assertEquals(emptyList<MediaItem>(), MediaIndex.mergeSorted(emptyList(), emptyList()))
    }

    @Test
    fun albumsGroupByBucketWithCoverAndCount() {
        val items =
            MediaIndex.mergeSorted(
                listOf(
                    image(1, 9_000, bucketId = 10, bucketName = "Camera"),
                    image(2, 2_000, bucketId = 20, bucketName = "Screenshots"),
                    image(3, 1_000, bucketId = 10, bucketName = "Camera"),
                ),
                listOf(video(4, 5_000, bucketId = 10, bucketName = "Camera")),
            )

        val albums = MediaIndex.albums(items)

        assertEquals(listOf(10L, 20L), albums.map { it.bucketId })
        val camera = albums[0]
        assertEquals("Camera", camera.name)
        assertEquals(3, camera.itemCount)
        assertEquals(1L, camera.cover.id)
        assertEquals(9_000L, camera.latestTimestamp)
        assertEquals(1, albums[1].itemCount)
        assertEquals(2L, albums[1].cover.id)
    }

    @Test
    fun itemsWithoutBucketFormUncategorizedAlbum() {
        val items =
            MediaIndex.mergeSorted(
                listOf(image(1, 3_000), image(2, 1_000, bucketId = 7, bucketName = "Dog")),
                listOf(video(3, 2_000)),
            )

        val albums = MediaIndex.albums(items)

        val uncategorized = albums.single { it.bucketId == null }
        assertEquals("", uncategorized.name)
        assertEquals(2, uncategorized.itemCount)
        assertEquals(1L, uncategorized.cover.id)
    }

    @Test
    fun albumsAreSortedNewestFirstWithNullBucketLast() {
        val items =
            MediaIndex.mergeSorted(
                listOf(
                    image(1, 5_000, bucketId = null),
                    image(2, 5_000, bucketId = 30, bucketName = "B"),
                    image(3, 4_000, bucketId = 10, bucketName = "A"),
                ),
                emptyList(),
            )

        val albums = MediaIndex.albums(items)

        // Gleicher neuester Zeitstempel: bucketId aufsteigend, null zuletzt. Dann das ältere Album.
        assertEquals(listOf(30L, null, 10L), albums.map { it.bucketId })
    }

    @Test
    fun emptyInputGivesEmptyLibrary() {
        val library = MediaIndex.build(emptyList(), emptyList(), MediaAccess.NONE)
        assertEquals(emptyList<MediaItem>(), library.items)
        assertEquals(emptyList<Album>(), library.albums)
        assertEquals(MediaAccess.NONE, library.mediaAccess)
    }

    private fun image(
        id: Long,
        timestamp: Long,
        bucketId: Long? = null,
        bucketName: String? = null,
    ) = item(id, MediaType.IMAGE, timestamp, bucketId, bucketName)

    private fun video(
        id: Long,
        timestamp: Long,
        bucketId: Long? = null,
        bucketName: String? = null,
    ) = item(id, MediaType.VIDEO, timestamp, bucketId, bucketName)

    private fun item(
        id: Long,
        type: MediaType,
        timestamp: Long,
        bucketId: Long?,
        bucketName: String?,
    ) = MediaItem(
        id = id,
        uri = "content://media/external/${type.name.lowercase()}s/media/$id",
        type = type,
        displayName = "file$id",
        mimeType = null,
        timestamp = timestamp,
        timestampSource = TimestampSource.DATE_TAKEN,
        width = null,
        height = null,
        orientation = null,
        durationMs = null,
        sizeBytes = null,
        bucketId = bucketId,
        bucketName = bucketName,
        relativePath = null,
        volumeName = null,
    )
}
