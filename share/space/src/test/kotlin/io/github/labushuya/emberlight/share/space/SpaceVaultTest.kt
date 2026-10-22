package io.github.labushuya.emberlight.share.space

import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.participants.FakeInviteSecretCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
import java.io.File

/** Upload, Dedupe, Grenzen, Löschen, Liste und Bereinigung der Anwendungslogik (ADR 0013, ADR 0014). */
@RunWith(RobolectricTestRunner::class)
class SpaceVaultTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: SpacesDatabase
    private lateinit var spacesDir: File
    private var clock = 1_000L
    private var freeBytes = Long.MAX_VALUE / 4
    private val host = Principal(ParticipantId.generate(), Role.HOST)
    private val guest = Principal(ParticipantId.generate(), Role.GUEST)
    private val otherGuest = Principal(ParticipantId.generate(), Role.GUEST)
    private val space = SpaceId.generate()

    private val small =
        SpaceLimits(
            imageMaxBytes = 1_000,
            videoMaxBytes = 5_000,
            storageLimitBytes = 1_000_000,
            minDeviceReserveBytes = 0,
        )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(context, spacesDir)
        val vault = vault()
        runBlocking {
            vault.registerParticipant(host.participantId, "Gastgeberin", Role.HOST, "0".repeat(64))
            vault.registerParticipant(guest.participantId, "Gast", Role.GUEST, "0".repeat(64))
            vault.registerParticipant(otherGuest.participantId, "Anderer Gast", Role.GUEST, "0".repeat(64))
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun vault(
        limits: SpaceLimits = small,
        probe: StorageProbe = StorageProbe { freeBytes },
    ): SpaceVault =
        SpaceVault(database, SpaceStorage(spacesDir, space), limits, probe, FakeInviteSecretCipher()) { clock++ }

    /** Dateien im Space-Ordner. Fehlt der Ordner, bricht der Test ab, damit "leer" nicht ungeprüft bestehen kann. */
    private fun storedFiles(): List<String> =
        checkNotNull(File(spacesDir, space.value).listFiles()) { "Space-Ordner fehlt." }.map { it.name }

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun ingest(
        vault: SpaceVault,
        principal: Principal,
        bytes: ByteArray,
        name: String = "bild.jpg",
        declared: Long? = bytes.size.toLong(),
    ): IngestResult = runBlocking { vault.ingest(principal, name, declared, BytesSource(bytes)) }

    private fun createdRecord(result: IngestResult): MediaRecord {
        assertTrue("Erwartet Created, war $result", result is IngestResult.Created)
        return (result as IngestResult.Created).record
    }

    @Test
    fun uploadStoresDetectedTypeAndSanitizedNameUnderContentHash() {
        val vault = vault()

        val record = createdRecord(ingest(vault, guest, MediaFixtures.jpeg(100), name = "../../evil.jpg"))

        assertEquals("image/jpeg", record.mime)
        assertEquals("evil.jpg", record.originalName)
        assertEquals(100L, record.sizeBytes)
        assertEquals(listOf(record.sha256), storedFiles())
    }

    @Test
    fun identicalContentIsDeduplicatedAndUploaderStaysTheFirst() {
        val vault = vault()
        val first = createdRecord(ingest(vault, guest, MediaFixtures.jpeg(100)))

        val second = ingest(vault, host, MediaFixtures.jpeg(100))

        assertTrue(second is IngestResult.Duplicate)
        val duplicate = (second as IngestResult.Duplicate).record
        assertEquals(first.id, duplicate.id)
        assertEquals(guest.participantId, duplicate.uploaderId)
        assertEquals(1, storedFiles().size)
    }

    @Test
    fun differentContentIsStoredSeparately() {
        val vault = vault()

        val first = createdRecord(ingest(vault, guest, MediaFixtures.jpeg(100)))
        val second = createdRecord(ingest(vault, guest, MediaFixtures.jpeg(101)))

        assertTrue(first.id != second.id)
        assertEquals(2, storedFiles().size)
    }

    @Test
    fun unsupportedContentIsRejectedAndLeavesNoFile() {
        val vault = vault()

        val result = ingest(vault, guest, MediaFixtures.ascii("<html><script>x</script></html>"))

        assertEquals(IngestResult.Rejected(RejectReason.UnsupportedType), result)
        assertTrue(storedFiles().isEmpty())
    }

    /** Ein großer Körper ohne erlaubte Signatur wird nach dem Kopf abgelehnt. Mehr als [ContentInspector.HEAD_BYTES] liest der Vault nicht. */
    @Test
    fun bodyWithoutAllowedSignatureIsRejectedAfterTheHeadOnly() {
        val vault = vault()
        val source = CountingSource(BytesSource(ByteArray(2_000_000) { 0x55 }, chunk = 64 * 1024))

        val result = runBlocking { vault.ingest(guest, "rauschen.bin", null, source) }

        assertEquals(IngestResult.Rejected(RejectReason.UnsupportedType), result)
        assertTrue("gelesen: ${source.bytesRead}", source.bytesRead <= ContentInspector.HEAD_BYTES)
        assertTrue(storedFiles().isEmpty())
    }

    /**
     * Ein Anhängsel hinter IEND wird abgeschnitten (ADR 0015, Anhänge). Das PNG-Gerüst bleibt, der Rest (hier HTML)
     * verschwindet. Die Datei gilt als bereinigt, also ohne Standort-Markierung und ohne Einwilligung.
     */
    @Test
    fun polyglotPngWithAppendedHtmlIsStoredWithoutTheTail() {
        val vault = vault()
        val clean = MediaFixtures.png()

        val record = createdRecord(ingest(vault, guest, MediaFixtures.png(MediaFixtures.ascii("<html></html>"))))

        assertEquals("image/png", record.mime)
        assertEquals(false, record.locationKept)
        assertEquals(clean.size.toLong(), record.sizeBytes)
        assertArrayEquals(clean, File(spacesDir, "${space.value}/${record.sha256}").readBytes())
        assertEquals(listOf(record.sha256), storedFiles())
        assertEquals(sha256Hex(clean), record.sha256)
    }

    /**
     * Mit Einwilligung wird die Datei unverändert gespeichert, also samt Anhang hinter IEND (ADR 0015, Nachtrag 3.6d).
     * `locationKept` markiert die Einwilligung, nicht den Nachweis eines Standorts.
     */
    @Test
    fun polyglotPngWithConsentIsStoredUnchanged() {
        val vault = vault()
        val bytes = MediaFixtures.png(MediaFixtures.ascii("<html></html>"))

        val record = createdRecord(runBlocking { vault.ingest(guest, "polyglot.png", null, BytesSource(bytes), true) })

        assertEquals("image/png", record.mime)
        assertEquals(true, record.locationKept)
        assertEquals(bytes.size.toLong(), record.sizeBytes)
        assertEquals(sha256Hex(bytes), record.sha256)
        assertArrayEquals(bytes, File(spacesDir, "${space.value}/${record.sha256}").readBytes())
    }

    /**
     * Ein Anhang nach der letzten Box ist kein Strukturfehler mehr (vorher 415). Das Video wird angenommen und ohne den
     * Anhang gespeichert, also mit der Länge bis zum Ende der Box-Kette (ADR 0015, Anhänge).
     */
    @Test
    fun mp4WithAttachmentAfterTheLastBoxIsStoredWithoutIt() {
        val vault = vault()
        val mp4 = MediaFixtures.mp4()
        val withTail = mp4 + ByteArray(201) { (it * 7 + 3).toByte() }

        val record = createdRecord(ingest(vault, guest, withTail, name = "film.mp4"))

        assertEquals("video/mp4", record.mime)
        assertEquals(mp4.size.toLong(), record.sizeBytes)
        assertEquals(sha256Hex(mp4), record.sha256)
        assertArrayEquals(mp4, File(spacesDir, "${space.value}/${record.sha256}").readBytes())
    }

    @Test
    fun brokenBoxStructureIsRejectedAndLeavesNoFile() {
        val vault = vault()
        val broken = MediaFixtures.ftyp("isom") + MediaFixtures.rawBox(9_999, "mdat", ByteArray(16))

        val result = ingest(vault, guest, broken, name = "film.mp4")

        assertEquals(IngestResult.Rejected(RejectReason.UnsupportedType), result)
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun declaredLengthAboveTheImageLimitIsRejectedBeforeReading() {
        val vault = vault()

        val result = ingest(vault, guest, MediaFixtures.jpeg(100), declared = 5_000)

        assertEquals(IngestResult.Rejected(RejectReason.TooLarge(1_000)), result)
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun chunkedBodyWithoutDeclaredLengthIsCutAtTheLimit() {
        val vault = vault()

        val result = ingest(vault, guest, MediaFixtures.jpeg(5_000), declared = null)

        assertEquals(IngestResult.Rejected(RejectReason.TooLarge(1_000)), result)
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun smallDeclaredLengthWithLargerBodyIsCutAtTheLimitNotAtTheDeclaredValue() {
        val vault = vault()

        val result = ingest(vault, guest, MediaFixtures.jpeg(5_000), declared = 10)

        assertEquals(IngestResult.Rejected(RejectReason.TooLarge(1_000)), result)
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun videoLimitIsSeparateFromTheImageLimit() {
        val vault = vault()
        val video = MediaFixtures.mp4() + ByteArray(3_000)

        val record = createdRecord(ingest(vault, guest, video, name = "clip.mp4"))

        assertEquals("video/mp4", record.mime)
    }

    @Test
    fun abortedBodyRemovesThePartialFileAndPropagatesTheAbort() {
        val vault = vault()
        val source = BrokenSource(BytesSource(MediaFixtures.jpeg(900), chunk = 100), failAfter = 300)

        assertThrows(CancellationException::class.java) {
            runBlocking { vault.ingest(guest, "bild.jpg", null, source) }
        }
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun spaceLimitRejectsWithStorageFullAndWarnsBeforeIt() {
        val limits = small.copy(storageLimitBytes = 250)
        val vault = vault(limits = limits)

        val first = ingest(vault, guest, MediaFixtures.jpeg(200))
        val second = ingest(vault, guest, MediaFixtures.jpeg(100))

        assertTrue(first is IngestResult.Created)
        assertEquals(StorageWarning.STORAGE_80, (first as IngestResult.Created).warning)
        assertEquals(IngestResult.Rejected(RejectReason.StorageFull), second)
        assertEquals(1, storedFiles().size)
    }

    @Test
    fun deviceReserveRejectsWithDeviceStorageLow() {
        val limits = small.copy(minDeviceReserveBytes = 2_000)
        val vault = vault(limits = limits, probe = StorageProbe { 2_500 })

        val tooBig = ingest(vault, guest, MediaFixtures.jpeg(900))
        val fits = ingest(vault, guest, MediaFixtures.jpeg(400))

        assertEquals(IngestResult.Rejected(RejectReason.DeviceStorageLow), tooBig)
        assertTrue(fits is IngestResult.Created)
    }

    @Test
    fun uploaderMayDeleteOwnMedia() {
        val vault = vault()
        val record = createdRecord(ingest(vault, guest, MediaFixtures.jpeg(100)))

        assertEquals(DeleteOutcome.Deleted, runBlocking { vault.delete(guest, record.id) })
        assertNull(runBlocking { vault.find(record.id) })
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun otherGuestMayNotDeleteForeignMedia() {
        val vault = vault()
        val record = createdRecord(ingest(vault, guest, MediaFixtures.jpeg(100)))

        assertEquals(DeleteOutcome.Forbidden, runBlocking { vault.delete(otherGuest, record.id) })
        assertNotNull(runBlocking { vault.find(record.id) })
    }

    @Test
    fun hostMayDeleteAnyMedia() {
        val vault = vault()
        val record = createdRecord(ingest(vault, guest, MediaFixtures.jpeg(100)))

        assertEquals(DeleteOutcome.Deleted, runBlocking { vault.delete(host, record.id) })
    }

    @Test
    fun unknownMediaIdIsNotFound() {
        val vault = vault()

        assertEquals(DeleteOutcome.NotFound, runBlocking { vault.delete(host, MediaId.generate()) })
    }

    @Test
    fun listIsNewestFirstAndPagesWithCursor() {
        val vault = vault()
        val oldest = createdRecord(ingest(vault, guest, MediaFixtures.jpeg(100)))
        val middle = createdRecord(ingest(vault, host, MediaFixtures.jpeg(101)))
        val newest = createdRecord(ingest(vault, otherGuest, MediaFixtures.jpeg(102)))

        val firstPage = runBlocking { vault.list(null, limit = 2) }
        val secondPage = runBlocking { vault.list(firstPage.nextCursor, limit = 2) }

        assertEquals(listOf(newest.id, middle.id), firstPage.items.map { it.id })
        assertNotNull(firstPage.nextCursor)
        assertEquals(listOf(oldest.id), secondPage.items.map { it.id })
        assertNull(secondPage.nextCursor)
        assertEquals("Anderer Gast", firstPage.items.first().uploaderName)
    }

    @Test
    fun usageSumsStoredBytesAndReportsWarning() {
        val vault = vault(limits = small.copy(storageLimitBytes = 250))
        createdRecord(ingest(vault, guest, MediaFixtures.jpeg(200)))

        val usage = runBlocking { vault.usage() }

        assertEquals(200L, usage.usedBytes)
        assertTrue(usage.warning)
    }

    @Test
    fun recoverRemovesDeletingEntriesAndOrphanFiles() {
        val vault = vault()
        val kept = createdRecord(ingest(vault, guest, MediaFixtures.jpeg(100)))
        val doomed = createdRecord(ingest(vault, guest, MediaFixtures.jpeg(101)))
        // Absturz mitten im Löschen: Eintrag steht auf DELETING, die Datei ist noch da.
        runBlocking { database.media().markDeleting(doomed.id.value) }
        File(spacesDir, space.value).resolve("tmp-verwaist.part").writeBytes(byteArrayOf(1))

        runBlocking { vault.recover() }

        assertNull(runBlocking { vault.find(doomed.id) })
        assertNotNull(runBlocking { vault.find(kept.id) })
        assertEquals(listOf(kept.sha256), storedFiles())
    }
}
