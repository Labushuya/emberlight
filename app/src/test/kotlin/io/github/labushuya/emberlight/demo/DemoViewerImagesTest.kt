package io.github.labushuya.emberlight.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.BitmapImage
import coil3.fetch.ImageFetchResult
import io.github.labushuya.emberlight.feature.gallery.viewer.SCREEN_PREVIEW_EDGE_PX
import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerItem
import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerMediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Demo-Vollbild und Vorschaubild (ADR 0011): gleiches Motiv, passende Seitenverhältnisse, nur synthetische URIs. */
@RunWith(AndroidJUnit4::class)
class DemoViewerImagesTest {
    @Test
    fun theContributionServesSyntheticEntriesOnly() {
        val contribution = DemoViewerImageContribution()
        val synthetic = viewerItem(uri = SyntheticLibrary.itemAt(7).uri)
        val foreign = viewerItem(uri = "content://media/external/images/media/42")

        assertNotNull(contribution.sourceFor(synthetic))
        assertNull(contribution.sourceFor(foreign))
    }

    @Test
    fun theFullImageKeepsTheAspectOfTheEntry() {
        val landscape = renderDemoBitmap(1, 0L, width = 4000, height = 3000, longEdge = FULL_LONG_EDGE)
        val portrait = renderDemoBitmap(2, 0L, width = 3000, height = 4000, longEdge = FULL_LONG_EDGE)

        assertEquals(FULL_LONG_EDGE, landscape.width)
        assertEquals(960, landscape.height)
        assertEquals(960, portrait.width)
        assertEquals(FULL_LONG_EDGE, portrait.height)
    }

    @Test
    fun anEntryWithoutSizeIsSquare() {
        val bitmap = renderDemoBitmap(3, 0L, width = null, height = null, longEdge = THUMBNAIL_LONG_EDGE)

        assertEquals(THUMBNAIL_LONG_EDGE, bitmap.width)
        assertEquals(THUMBNAIL_LONG_EDGE, bitmap.height)
    }

    @Test
    fun thumbnailAndFullImageShareTheAspect() {
        val item = SyntheticLibrary.itemAt(12)
        val thumbnail = renderDemoBitmap(item.id, item.timestamp, item.width, item.height, THUMBNAIL_LONG_EDGE)
        val full = renderDemoBitmap(item.id, item.timestamp, item.width, item.height, FULL_LONG_EDGE)

        assertEquals(thumbnail.width.toDouble() / thumbnail.height, full.width.toDouble() / full.height, 0.01)
    }

    @Test
    fun theScreenPreviewFetcherRendersTheEntryAtScreenSize() {
        val item = SyntheticLibrary.itemAt(5)
        val result = runBlocking { DemoThumbnailFetcher(item, SCREEN_PREVIEW_EDGE_PX).fetch() } as ImageFetchResult
        val bitmap = (result.image as BitmapImage).bitmap

        assertEquals(SCREEN_PREVIEW_EDGE_PX, maxOf(bitmap.width, bitmap.height))
    }

    private fun viewerItem(uri: String) =
        ViewerItem(
            id = 1L,
            uri = uri,
            type = ViewerMediaType.IMAGE,
            timestamp = 0L,
            width = 4000,
            height = 3000,
            durationMs = null,
            previewKey = null,
        )
}
