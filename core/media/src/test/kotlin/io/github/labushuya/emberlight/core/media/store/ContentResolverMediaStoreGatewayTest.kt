package io.github.labushuya.emberlight.core.media.store

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import app.cash.turbine.test
import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.log.MediaLogger
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.model.TimestampSource
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.core.media.permission.MediaPermissionChecker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver

/**
 * Android-Gateway gegen einen Fake-ContentProvider für die echte MediaStore-Authority. Der Provider liefert
 * MatrixCursor-Zeilen für die echten Collection-URIs. So laufen Projektion, Selektion, Cursor-Mapping und
 * Beobachtung durch den echten ContentResolver.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ContentResolverMediaStoreGatewayTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val provider = FakeMediaProvider()
    private val gateway = ContentResolverMediaStoreGateway(context)
    private val logged = mutableListOf<String>()

    @Before
    fun registerFakeProvider() {
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
    }

    @Test
    fun mapsAllFieldsOfAnImageRow() {
        provider.images += imageRow()

        val items = gateway.query(MediaType.IMAGE)

        assertEquals(
            listOf(
                MediaItem(
                    id = 42L,
                    uri = "content://media/external/images/media/42",
                    type = MediaType.IMAGE,
                    displayName = "IMG_1.jpg",
                    mimeType = "image/jpeg",
                    timestamp = 1_700_000_000_000L,
                    timestampSource = TimestampSource.DATE_TAKEN,
                    width = 4032,
                    height = 3024,
                    orientation = 90,
                    durationMs = null,
                    sizeBytes = 1_234L,
                    bucketId = 555L,
                    bucketName = "Camera",
                    relativePath = "DCIM/Camera/",
                    volumeName = "external_primary",
                ),
            ),
            items,
        )
    }

    @Test
    fun mapsVideoRowWithDurationAndModifiedFallback() {
        provider.videos +=
            mapOf(
                ID to 7L,
                DISPLAY_NAME to "clip.mp4",
                MIME_TYPE to "video/mp4",
                SIZE to 9_000L,
                WIDTH to 0,
                HEIGHT to 0,
                DATE_TAKEN to 0L,
                DATE_MODIFIED to 1_700_000_100L,
                DATE_ADDED to 1_700_000_200L,
                DURATION to 12_345L,
                ORIENTATION to 0,
            )

        val item = gateway.query(MediaType.VIDEO).single()

        assertEquals(MediaType.VIDEO, item.type)
        assertEquals("content://media/external/video/media/7", item.uri)
        assertEquals(1_700_000_100_000L, item.timestamp)
        assertEquals(TimestampSource.DATE_MODIFIED, item.timestampSource)
        assertEquals(12_345L, item.durationMs)
        // Breite und Höhe 0 gelten als unbekannt.
        assertEquals(null, item.width)
        assertEquals(null, item.height)
        assertEquals(0, item.orientation)
        assertEquals(null, item.bucketId)
    }

    @Test
    fun fallsBackToDateAddedWhenOtherDatesAreMissing() {
        provider.images += mapOf(ID to 3L, DATE_ADDED to 1_700_000_200L)

        val item = gateway.query(MediaType.IMAGE).single()

        assertEquals(1_700_000_200_000L, item.timestamp)
        assertEquals(TimestampSource.DATE_ADDED, item.timestampSource)
        assertEquals(null, item.bucketId)
        assertEquals(null, item.orientation)
    }

    @Test
    fun queriesExternalVolumeAndExcludePendingItems() {
        provider.images += imageRow()
        provider.videos += mapOf(ID to 1L)

        gateway.query(MediaType.IMAGE)
        gateway.query(MediaType.VIDEO)

        val imageCall = provider.queries[0]
        assertEquals("content://media/external/images/media", imageCall.uri.toString())
        assertEquals("is_pending = 0", imageCall.selection)
        assertTrue(imageCall.projection.contains(DATE_TAKEN))

        val videoCall = provider.queries[1]
        assertEquals("content://media/external/video/media", videoCall.uri.toString())
        assertEquals("is_pending = 0", videoCall.selection)
        assertTrue(videoCall.projection.contains(DURATION))
    }

    @Test
    fun securityExceptionPropagatesFromQuery() {
        provider.error = SecurityException("denied")

        assertThrows(SecurityException::class.java) { gateway.query(MediaType.IMAGE) }
    }

    @Test
    fun changesEmitForBothCollections() =
        runTest {
            gateway.changes().test {
                context.contentResolver.notifyChange(imagesUri(), null)
                awaitItem()
                context.contentResolver.notifyChange(videoUri(), null)
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun repositoryReportsNoneAndEmptyWhenProviderDeniesAccess() =
        runTest {
            provider.error = SecurityException("denied")
            val repo =
                MediaStoreMediaRepository(
                    gateway = gateway,
                    permissions = MediaPermissionChecker { true },
                    logger = MediaLogger { message, _ -> logged += message },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            advanceUntilIdle()

            val library = (repo.library.value as MediaLibraryState.Ready).library
            assertEquals(MediaAccess.NONE, library.mediaAccess)
            assertEquals(emptyList<MediaItem>(), library.items)
            // Ein Entzug ist kein unerwarteter Fehler, also darf nichts protokolliert sein.
            assertEquals(emptyList<String>(), logged)
        }

    @Test
    fun repositoryPicksUpNewRowsAfterRefresh() =
        runTest {
            val repo =
                MediaStoreMediaRepository(
                    gateway = gateway,
                    permissions = MediaPermissionChecker { true },
                    logger = MediaLogger { message, _ -> logged += message },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )
            advanceUntilIdle()
            assertEquals(
                0,
                (repo.library.value as MediaLibraryState.Ready).library.items.size,
            )

            provider.images += imageRow()
            repo.refresh()
            advanceUntilIdle()

            val library = (repo.library.value as MediaLibraryState.Ready).library
            assertEquals(listOf(42L), library.items.map { it.id })
            assertEquals(listOf(555L), library.albums.map { it.bucketId })
            assertEquals(MediaAccess.FULL, library.mediaAccess)
            assertEquals(emptyList<String>(), logged)
        }

    private fun imagesUri(): Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private fun videoUri(): Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private fun imageRow(): Map<String, Any?> =
        mapOf(
            ID to 42L,
            DISPLAY_NAME to "IMG_1.jpg",
            MIME_TYPE to "image/jpeg",
            SIZE to 1_234L,
            WIDTH to 4032,
            HEIGHT to 3024,
            DATE_TAKEN to 1_700_000_000_000L,
            DATE_MODIFIED to 1_700_000_100L,
            DATE_ADDED to 1_700_000_200L,
            BUCKET_ID to 555L,
            BUCKET_DISPLAY_NAME to "Camera",
            ORIENTATION to 90,
            RELATIVE_PATH to "DCIM/Camera/",
            VOLUME_NAME to "external_primary",
        )

    /** Liefert Zeilen für die echten Collection-URIs. Die Projektion steuert die Spalten. */
    private class FakeMediaProvider : ContentProvider() {
        val images = mutableListOf<Map<String, Any?>>()
        val videos = mutableListOf<Map<String, Any?>>()
        val queries = mutableListOf<QueryCall>()
        var error: RuntimeException? = null

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val columns = projection?.toList().orEmpty()
            queries += QueryCall(uri, columns, selection)
            error?.let { throw it }
            val rows = if (uri.pathSegments.contains("images")) images else videos
            val cursor = MatrixCursor(columns.toTypedArray())
            rows.forEach { row -> cursor.addRow(columns.map { row[it] }.toTypedArray()) }
            return cursor
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = throw UnsupportedOperationException()

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = throw UnsupportedOperationException()

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = throw UnsupportedOperationException()
    }

    private data class QueryCall(
        val uri: Uri,
        val projection: List<String>,
        val selection: String?,
    )

    private companion object {
        val ID = MediaStore.MediaColumns._ID
        val DISPLAY_NAME = MediaStore.MediaColumns.DISPLAY_NAME
        val MIME_TYPE = MediaStore.MediaColumns.MIME_TYPE
        val SIZE = MediaStore.MediaColumns.SIZE
        val WIDTH = MediaStore.MediaColumns.WIDTH
        val HEIGHT = MediaStore.MediaColumns.HEIGHT
        val DATE_ADDED = MediaStore.MediaColumns.DATE_ADDED
        val DATE_MODIFIED = MediaStore.MediaColumns.DATE_MODIFIED
        const val DATE_TAKEN = "datetaken"
        const val BUCKET_ID = "bucket_id"
        const val BUCKET_DISPLAY_NAME = "bucket_display_name"
        const val ORIENTATION = "orientation"
        const val DURATION = "duration"
        const val RELATIVE_PATH = "relative_path"
        const val VOLUME_NAME = "volume_name"
    }
}
