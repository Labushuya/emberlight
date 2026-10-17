package io.github.labushuya.emberlight.demo

import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerItem
import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetische EXIF-Angaben des Demo-Modus (ADR 0011). Nur der Demo-Source-Set enthält sie, sie sind deterministisch. */
class DemoViewerDetailsTest {
    private val contribution = DemoViewerDetailsContribution()

    @Test
    fun theSameDemoPhotoAlwaysGetsTheSameDetails() {
        assertEquals(demoExif(3), demoExif(3))
    }

    @Test
    fun demoPhotosGetPlausibleCameraValues() {
        val exif = demoExif(5)

        assertEquals("Demo-Kamera", exif.make)
        assertNotNull(exif.model)
        assertNotNull(exif.fNumber)
        assertNotNull(exif.exposureSeconds)
        assertNotNull(exif.iso)
        assertNotNull(exif.focalLengthMm)
        assertNotNull(exif.flashFired)
    }

    @Test
    fun aSyntheticPhotoGetsDemoDetails() {
        val exif = contribution.exifFor(item(uri = SyntheticLibrary.URI_PREFIX + "4", type = ViewerMediaType.IMAGE))

        assertEquals("Demo-Kamera", exif?.make)
    }

    @Test
    fun videosAndOtherItemsGetNoDemoDetails() {
        assertNull(contribution.exifFor(item(uri = SyntheticLibrary.URI_PREFIX + "4", type = ViewerMediaType.VIDEO)))
        assertNull(
            contribution.exifFor(item(uri = "content://media/external/images/media/4", type = ViewerMediaType.IMAGE)),
        )
    }

    private fun item(
        uri: String,
        type: ViewerMediaType,
    ) = ViewerItem(
        id = 4L,
        uri = uri,
        type = type,
        timestamp = 0L,
        width = 4032,
        height = 3024,
        durationMs = null,
        previewKey = null,
    )
}
