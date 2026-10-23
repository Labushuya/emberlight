package io.github.labushuya.emberlight.share.export

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.export.ExportFileMeta
import io.github.labushuya.emberlight.share.space.export.ExportHandle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Medienspeicher-Schreiber der Übernahme (Paket 3.7, ADR 0022), gegen einen Speicher im Test: Ausstehend bis zur
 * Freigabe, Bilder und Videos in ihren Tabellen, Verwerfen, Ordner- und Dateiprüfung.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MediaStoreExportSinkTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var provider: FakeMediaStoreProvider
    private lateinit var sink: MediaStoreExportSink
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = FakeMediaStoreProvider(folder.newFolder("store"))
        provider.register(context)
        sink = MediaStoreExportSink(context)
        // Die Liste der Kopien eines Laufs ist prozessweit. Jeder Fall beginnt mit einer leeren Liste.
        synchronized(MediaStoreExportSink.LIVE_COPIES) { MediaStoreExportSink.LIVE_COPIES.clear() }
    }

    @Test
    fun aNewCopyIsPendingUntilItIsPublishedAndThenVisibleInItsFolder() {
        val handle = runBlocking { sink.create(meta("bild.jpg", "image/jpeg", capture = 1_700_000_000_000L)) }
        val id = idOf(handle)

        val pending = provider.row(id)
        assertEquals(1, pending?.get(MediaColumns.IS_PENDING))
        assertEquals("Pictures/Emberlight/Urlaub/", pending?.get(MediaColumns.RELATIVE_PATH))
        assertEquals("bild.jpg", pending?.get(MediaColumns.DISPLAY_NAME))
        assertEquals(1_700_000_000_000L, pending?.get(MediaColumns.DATE_TAKEN))
        assertEquals("image/jpeg", pending?.get(MediaColumns.MIME_TYPE))
        assertTrue(runBlocking { sink.folderExists("Urlaub") })

        runBlocking {
            sink.openOutput(handle).use { it.write(BYTES) }
            assertArrayEquals(BYTES, sink.openInput(handle).use { it.readBytes() })
            sink.publish(handle)
        }

        assertEquals(0, provider.row(id)?.get(MediaColumns.IS_PENDING))
        assertArrayEquals(BYTES, provider.contentOf(id))
        assertTrue(runBlocking { sink.fileExists("Urlaub", "BILD.jpg") })
        assertFalse(runBlocking { sink.fileExists("Urlaub", "anders.jpg") })
    }

    @Test
    fun aVideoGoesIntoTheVideoTableUnderPictures() {
        val handle = runBlocking { sink.create(meta("clip.mp4", "video/mp4", capture = null)) }

        assertTrue(Uri.parse(handle.uri).toString().contains("/video/"))
        assertEquals("Pictures/Emberlight/Urlaub/", provider.row(idOf(handle))?.get(MediaColumns.RELATIVE_PATH))
        assertNull(provider.row(idOf(handle))?.get(MediaColumns.DATE_TAKEN))
    }

    @Test
    fun discardRemovesThePendingEntryAndItsFile() {
        val handle = runBlocking { sink.create(meta("weg.jpg", "image/jpeg", capture = null)) }
        val id = idOf(handle)

        runBlocking { sink.discard(handle) }
        runBlocking { sink.discard(handle) }

        assertNull(provider.row(id))
        assertFalse(File(folder.root, "store/$id").exists())
        assertFalse(runBlocking { sink.fileExists("Urlaub", "weg.jpg") })
    }

    @Test
    fun aMediaTypeOutsideTheSpaceIsRefusedBeforeAnyWrite() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { sink.create(meta("akte.pdf", "application/pdf", capture = null)) }
        }
        assertTrue(provider.rows.isEmpty())
    }

    @Test
    fun theFreeSpaceIsNeverNegative() {
        // Unter Robolectric liefert StatFs keinen echten Wert. Der echte Platz wird am Gerät geprüft.
        assertTrue(sink.availableBytes() >= 0L)
    }

    @Test
    fun aMissingProviderRowFailsThePublish() {
        val handle = runBlocking { sink.create(meta("fehlt.jpg", "image/jpeg", capture = null)) }
        provider.rows.clear()

        val thrown =
            runCatching {
                runBlocking { sink.publish(handle) }
            }.exceptionOrNull()

        assertTrue(thrown is java.io.IOException)
    }

    @Test
    fun aCopyLeftPendingByAnEarlierRunIsRemovedAndNothingElse() {
        val live = runBlocking { sink.create(meta("lebt.jpg", "image/jpeg", capture = null)) }
        val leftover = insertPending(owner = null, path = "Pictures/Emberlight/Urlaub/")
        val foreign = insertPending(owner = "de.andere.app", path = "Pictures/Emberlight/Urlaub/")
        val outside = insertPending(owner = null, path = "DCIM/")

        val removed = runBlocking { sink.removeAbandonedCopies() }

        assertEquals(1, removed)
        assertNull(provider.row(leftover))
        assertNotNull(provider.row(foreign))
        assertNotNull(provider.row(outside))
        assertNotNull(provider.row(idOf(live)))
    }

    @Test
    fun aPublishedCopyIsNotAbandoned() {
        val handle = runBlocking { sink.create(meta("fertig.jpg", "image/jpeg", capture = null)) }
        runBlocking { sink.publish(handle) }

        assertEquals(0, runBlocking { sink.removeAbandonedCopies() })
        assertNotNull(provider.row(idOf(handle)))
    }

    /** Ein ausstehender Eintrag, wie ihn ein abgestürzter Lauf hinterlässt: nicht in diesem Prozess angelegt. */
    private fun insertPending(
        owner: String?,
        path: String,
    ): Long {
        val values =
            ContentValues().apply {
                put(MediaColumns.DISPLAY_NAME, "alt.jpg")
                put(MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaColumns.RELATIVE_PATH, path)
                put(MediaColumns.IS_PENDING, 1)
                owner?.let { put(MediaColumns.OWNER_PACKAGE_NAME, it) }
            }
        val uri =
            context.contentResolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values,
            )
                ?: error("Der Testeintrag konnte nicht angelegt werden.")
        return ContentUris.parseId(uri)
    }

    private fun meta(
        name: String,
        mime: String,
        capture: Long?,
    ): ExportFileMeta =
        ExportFileMeta(
            folderName = "Urlaub",
            fileName = name,
            mime = mime,
            captureTimeMillis = capture,
            sizeBytes = BYTES.size.toLong(),
        )

    private fun idOf(handle: ExportHandle): Long = Uri.parse(handle.uri).lastPathSegment!!.toLong()

    private companion object {
        val BYTES = "Testinhalt der Kopie".toByteArray()
    }
}
