package io.github.labushuya.emberlight.feature.gallery.viewer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.request.CachePolicy
import coil3.size.Dimension
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.thumbnail.MediaThumbnail
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Optionen der Anfrage für die Vorschau in Bildschirmgröße (ADR 0008, ADR 0010 Korrektur 4). Der Disk-Cache muss aus sein,
 * sonst landet eine Kopie privater Medien im App-Cache.
 */
@RunWith(AndroidJUnit4::class)
class ScreenPreviewRequestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val item = viewerItem(id = 7L, uri = "content://media/external/images/media/7")

    @Test
    fun theRequestNeverWritesToTheDiskCache() {
        val request = screenPreviewRequest(context, item)

        assertEquals(CachePolicy.DISABLED, request.diskCachePolicy)
    }

    @Test
    fun theRequestAsksForTheMediaThumbnailOfTheItem() {
        val request = screenPreviewRequest(context, item)

        assertEquals(MediaThumbnail(uri = item.uri, type = MediaType.IMAGE), request.data)
    }

    @Test
    fun theRequestUsesItsOwnStableKeyInTheMemoryCache() {
        val request = screenPreviewRequest(context, item)
        val gridKey = MediaThumbnail(uri = item.uri, type = MediaType.IMAGE).memoryCacheKey

        assertEquals(screenPreviewKey(item.uri), request.memoryCacheKey)
        assertNotEquals(gridKey, request.memoryCacheKey)
    }

    @Test
    fun theRequestSamplesToTheScreenEdge() {
        val request = screenPreviewRequest(context, item)

        val size = runBlocking { request.sizeResolver.size() }
        assertEquals(Dimension.Pixels(SCREEN_PREVIEW_EDGE_PX), size.width)
        assertEquals(Dimension.Pixels(SCREEN_PREVIEW_EDGE_PX), size.height)
    }

    @Test
    fun theRequestCarriesTheScreenPreviewExtra() {
        val request = screenPreviewRequest(context, item)

        assertTrue(request.extras.get(ScreenPreviewExtra) == true)
    }

    private fun viewerItem(
        id: Long,
        uri: String,
    ): ViewerItem =
        ViewerItem(
            id = id,
            uri = uri,
            type = ViewerMediaType.IMAGE,
            timestamp = 0L,
            width = 4032,
            height = 3024,
            durationMs = null,
            previewKey = null,
        )
}
