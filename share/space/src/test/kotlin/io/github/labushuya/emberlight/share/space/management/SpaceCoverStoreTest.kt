package io.github.labushuya.emberlight.share.space.management

import io.github.labushuya.emberlight.share.space.ContentInspector
import io.github.labushuya.emberlight.share.space.MediaFixtures
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.location.LocationFixtures
import io.github.labushuya.emberlight.share.space.location.LocationScrubResult
import io.github.labushuya.emberlight.share.space.location.LocationScrubber
import io.github.labushuya.emberlight.share.space.location.NotRemovableReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/**
 * Titelbild-Pipeline (Paket 3.2): Kopie aus dem Stream, Prüfung auf Bild und Größe, Standort-Entfernung, Abweisung bei
 * nicht bereinigbarem Standort, Aufräumen beim Wechsel. Die Eingabe ist ein Stream, nie ein URI.
 */
class SpaceCoverStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var spacesDir: File
    private lateinit var store: SpaceCoverStore
    private val spaceId = SpaceId.generate()

    private fun setUp(maxBytes: Long = 50L * 1024 * 1024) {
        spacesDir = folder.newFolder("spaces")
        store = SpaceCoverStore(spacesDir, maxBytes)
    }

    private fun spaceDirectory(): File = File(spacesDir, spaceId.value)

    private fun filesInSpace(): List<String> = spaceDirectory().listFiles()?.map { it.name }?.sorted() ?: emptyList()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun store(bytes: ByteArray): CoverResult = runBlocking { store.store(spaceId, ByteArrayInputStream(bytes)) }

    @Test
    fun cleanImageIsStoredUnderItsHashWithoutChanges() {
        setUp()
        val original = MediaFixtures.jpeg(size = 200)

        val result = store(original)

        assertEquals(CoverResult.Stored(sha256Hex(original)), result)
        val stored = store.file(spaceId, sha256Hex(original))
        assertTrue(stored.isFile)
        assertTrue(original.contentEquals(stored.readBytes()))
        assertEquals(listOf("cover-${sha256Hex(original)}"), filesInSpace())
    }

    @Test
    fun locationIsRemovedFromTheCopy() {
        setUp()
        val withLocation = LocationFixtures.jpeg(LocationFixtures.app1Exif(LocationFixtures.exifTiff().bytes))

        val result = store(withLocation)

        val sha = (result as CoverResult.Stored).sha256
        assertTrue(sha != sha256Hex(withLocation))
        val stored = store.file(spaceId, sha)
        // Die Kopie ist danach standortfrei: ein zweiter Lauf der Bereinigung ändert nichts mehr.
        val format = ContentInspector.identify(stored.readBytes().copyOf(ContentInspector.HEAD_BYTES))
        checkNotNull(format) { "Die gespeicherte Kopie ist kein erkennbares Bild." }
        assertEquals(
            LocationScrubResult.Clean(changed = false, sha256 = sha),
            LocationScrubber.scrub(stored, format, sha),
        )
    }

    @Test
    fun videoIsNotAcceptedAsCover() {
        setUp()

        assertEquals(CoverResult.UnsupportedType, store(MediaFixtures.mp4()))
        assertTrue(filesInSpace().none { it.startsWith("tmp-") })
        assertTrue(filesInSpace().none { it.startsWith("cover-") })
    }

    @Test
    fun nonMediaContentIsRejected() {
        setUp()

        assertEquals(CoverResult.UnsupportedType, store("Das ist kein Bild".toByteArray()))
        assertEquals(CoverResult.UnsupportedType, store(ByteArray(0)))
        assertTrue(filesInSpace().none { it.startsWith("cover-") })
    }

    @Test
    fun imageAboveTheLimitIsRejectedAndLeavesNothingBehind() {
        setUp(maxBytes = 100)

        val result = store(MediaFixtures.jpeg(size = 300))

        assertEquals(CoverResult.TooLarge(100), result)
        assertTrue(filesInSpace().none { it.startsWith("tmp-") || it.startsWith("cover-") })
    }

    @Test
    fun locationThatCannotBeRemovedIsRejectedWithoutConsent() {
        setUp()
        val notRemovable =
            LocationFixtures.png(
                LocationFixtures.pngChunk(
                    "zTXt",
                    LocationFixtures.ztxtDeflated("Comment", "Ort +48.8566+002.3522/"),
                ),
            )

        val result = store(notRemovable)

        assertEquals(CoverResult.LocationNotRemovable(NotRemovableReason.COMPRESSED_TEXT), result)
        assertTrue(filesInSpace().none { it.startsWith("tmp-") || it.startsWith("cover-") })
    }

    @Test
    fun replacingTheCoverKeepsOnlyTheListedOne() {
        setUp()
        val first = store(MediaFixtures.jpeg(size = 120)) as CoverResult.Stored
        val second = store(MediaFixtures.jpeg(size = 160)) as CoverResult.Stored

        val removed = runBlocking { store.retainOnly(spaceId, setOf(second.sha256)) }

        assertEquals(1, removed)
        assertFalse(store.exists(spaceId, first.sha256))
        assertTrue(store.exists(spaceId, second.sha256))
    }

    @Test
    fun retainingKeepsMediaUntouched() {
        setUp()
        val media =
            File(spaceDirectory(), sha256Hex(ByteArray(3))).apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(3))
            }
        store(MediaFixtures.jpeg(size = 120))

        runBlocking { store.retainOnly(spaceId, emptySet()) }

        assertTrue(media.isFile)
        assertTrue(filesInSpace().none { it.startsWith("cover-") })
    }

    @Test
    fun deleteSpaceRemovesTheWholeDirectory() {
        setUp()
        store(MediaFixtures.jpeg(size = 120))
        assertTrue(spaceDirectory().isDirectory)

        runBlocking { store.deleteSpace(spaceId) }

        assertFalse(spaceDirectory().exists())
    }
}
