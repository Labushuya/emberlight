package io.github.labushuya.emberlight.feature.gallery.viewer

import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Abbildung der MediaStore-Angaben auf die Anzeigedaten des Infobereichs (ADR 0007, ADR 0010). */
class MediaDetailsMappingTest {
    @Test
    fun carriesNameSizeTypeAlbumAndFolder() {
        val details = mediaItem(relativePath = "DCIM/Camera/").toViewerItem().details

        assertEquals("IMG_2041.jpg", details.displayName)
        assertEquals("image/jpeg", details.mimeType)
        assertEquals(3_482_000L, details.sizeBytes)
        assertEquals("Camera", details.albumName)
        assertEquals("DCIM/Camera", details.folder)
    }

    @Test
    fun aDateFromTheCameraIsNotMarkedAsEstimated() {
        assertFalse(mediaItem(source = TimestampSource.DATE_TAKEN).toViewerItem().details.dateEstimated)
    }

    @Test
    fun aDateFromTheFileIsMarkedAsEstimated() {
        assertTrue(mediaItem(source = TimestampSource.DATE_MODIFIED).toViewerItem().details.dateEstimated)
        assertTrue(mediaItem(source = TimestampSource.DATE_ADDED).toViewerItem().details.dateEstimated)
    }

    @Test
    fun missingValuesStayMissingInsteadOfBecomingText() {
        val details =
            mediaItem(displayName = null, mimeType = null, sizeBytes = null, bucketName = null, relativePath = "")
                .toViewerItem()
                .details

        assertNull(details.displayName)
        assertNull(details.mimeType)
        assertNull(details.sizeBytes)
        assertNull(details.albumName)
        assertNull(details.folder)
    }

    private fun mediaItem(
        displayName: String? = "IMG_2041.jpg",
        mimeType: String? = "image/jpeg",
        sizeBytes: Long? = 3_482_000L,
        bucketName: String? = "Camera",
        relativePath: String? = "DCIM/Camera/",
        source: TimestampSource = TimestampSource.DATE_TAKEN,
    ): MediaItem =
        MediaItem(
            id = 1L,
            uri = "content://media/external/images/media/1",
            type = MediaType.IMAGE,
            displayName = displayName,
            mimeType = mimeType,
            timestamp = 1_000L,
            timestampSource = source,
            width = 4032,
            height = 3024,
            orientation = 0,
            durationMs = null,
            sizeBytes = sizeBytes,
            bucketId = 7L,
            bucketName = bucketName,
            relativePath = relativePath,
            volumeName = "external_primary",
        )
}
