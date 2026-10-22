package io.github.labushuya.emberlight.share.space.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.MediaEntity
import io.github.labushuya.emberlight.share.space.MediaFixtures
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceStorage
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.security.MessageDigest

/**
 * Übernahme in die Galerie (Paket 3.7, ADR 0022): Kopie mit Hash-Prüfung, Duplikate, Platz, Fehler je Datei, Abbruch,
 * Namen und Zustände des Space. Die Kopien liegen im Fake-Speicher, die Space-Dateien auf der Platte.
 */
@RunWith(RobolectricTestRunner::class)
class SpaceExporterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var spacesDir: File
    private lateinit var database: SpacesDatabase
    private lateinit var sink: FakeExportSink
    private lateinit var gate: SpaceExportGate
    private val reserve = 1_000L

    @Before
    fun setUp() {
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(ApplicationProvider.getApplicationContext<Context>(), spacesDir)
        sink = FakeExportSink()
        gate = SpaceExportGate()
        insertSpace(ID, "ENDED", name = "Urlaub")
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun exporter(): SpaceExporter =
        SpaceExporter(
            database = database,
            spacesDir = spacesDir,
            sink = sink,
            gate = gate,
            reserveBytes = reserve,
            nowMillis = { 5_000L },
            captureTimeOf = { _, _ -> null },
        )

    @Test
    fun copiesTheSelectedMediaVerifiesTheHashAndRecordsThem() {
        val jpeg = MediaFixtures.jpeg(64)
        val png = MediaFixtures.png()
        addMedium(MEDIUM_A, jpeg, "urlaub.jpg", "image/jpeg")
        addMedium(MEDIUM_B, png, "karte.png", "image/png")
        val progress = mutableListOf<ExportProgress>()

        val outcome = runBlocking { exporter().export(SpaceId(ID), setOf(MEDIUM_A, MEDIUM_B)) { progress += it } }

        val summary = (outcome as ExportOutcome.Finished).summary
        assertEquals(2, summary.exported)
        assertEquals(0, summary.skippedAlreadyExported)
        assertTrue(summary.failed.isEmpty())
        assertFalse(summary.stoppedByPurge)
        assertEquals((jpeg.size + png.size).toLong(), summary.bytesCopied)
        assertEquals(2, sink.published.size)
        assertEquals(0, sink.pendingCount())
        assertEquals(setOf("Urlaub"), sink.created.map { it.folderName }.toSet())
        assertEquals("urlaub.jpg", sink.created[0].fileName)
        assertEquals("image/jpeg", sink.created[0].mime)
        assertNull(sink.created[0].captureTimeMillis)
        assertArrayEquals(jpeg, sink.contents[sink.published[0].uri])
        assertEquals(2, runBlocking { database.exports().exportedIds(ID) }.size)
        assertEquals("Urlaub", runBlocking { database.exports().folderOf(ID) }?.folderName)
        assertEquals(2, progress.last().doneFiles)
        assertEquals((jpeg.size + png.size).toLong(), progress.last().doneBytes)
    }

    @Test
    fun aMediumThatWasExportedBeforeIsSkippedWithoutANewCopy() {
        addMedium(MEDIUM_A, MediaFixtures.jpeg(), "a.jpg", "image/jpeg")
        runBlocking { exporter().export(SpaceId(ID), setOf(MEDIUM_A)) }
        sink.created.clear()

        val outcome = runBlocking { exporter().export(SpaceId(ID), setOf(MEDIUM_A)) }

        val summary = (outcome as ExportOutcome.Finished).summary
        assertEquals(0, summary.exported)
        assertEquals(1, summary.skippedAlreadyExported)
        assertTrue(sink.created.isEmpty())
        assertEquals(1, sink.published.size)
    }

    @Test
    fun notEnoughSpaceWritesNothingAndSaysHowMuchIsMissing() {
        val jpeg = MediaFixtures.jpeg(64)
        addMedium(MEDIUM_A, jpeg, "a.jpg", "image/jpeg")
        sink.available = jpeg.size + reserve - 1

        val outcome = runBlocking { exporter().export(SpaceId(ID), setOf(MEDIUM_A)) }

        assertEquals(ExportOutcome.NotEnoughSpace(jpeg.size + reserve, jpeg.size + reserve - 1), outcome)
        assertTrue(sink.created.isEmpty())
        assertTrue(runBlocking { database.exports().exportedIds(ID) }.isEmpty())
    }

    @Test
    fun aCopyWhoseHashDoesNotMatchIsDiscardedAndReportedAsAnError() {
        addMedium(MEDIUM_A, MediaFixtures.jpeg(), "a.jpg", "image/jpeg")
        sink.corruptFor += "a.jpg"

        val outcome = runBlocking { exporter().export(SpaceId(ID), setOf(MEDIUM_A)) }

        val summary = (outcome as ExportOutcome.Finished).summary
        assertEquals(0, summary.exported)
        assertEquals(listOf(ExportFailure(MEDIUM_A, ExportFailureReason.HASH_MISMATCH)), summary.failed)
        assertEquals(1, sink.discarded.size)
        assertTrue(sink.published.isEmpty())
        assertTrue(runBlocking { database.exports().exportedIds(ID) }.isEmpty())
    }

    @Test
    fun aFailingFileDoesNotStopTheOthers() {
        addMedium(MEDIUM_A, MediaFixtures.jpeg(), "kaputt.jpg", "image/jpeg")
        addMedium(MEDIUM_B, MediaFixtures.png(), "gut.png", "image/png")
        sink.failCreateFor += "kaputt.jpg"

        val outcome = runBlocking { exporter().export(SpaceId(ID), setOf(MEDIUM_A, MEDIUM_B)) }

        val summary = (outcome as ExportOutcome.Finished).summary
        assertEquals(1, summary.exported)
        assertEquals(listOf(ExportFailure(MEDIUM_A, ExportFailureReason.WRITE_FAILED)), summary.failed)
        assertEquals("gut.png", sink.published.single().fileName)
    }

    @Test
    fun aMissingSpaceFileIsReportedNotCopied() {
        insertMediumRow(MEDIUM_A, sha = "a".repeat(64), name = "fehlt.jpg")

        val outcome = runBlocking { exporter().export(SpaceId(ID), setOf(MEDIUM_A)) }

        val summary = (outcome as ExportOutcome.Finished).summary
        assertEquals(listOf(ExportFailure(MEDIUM_A, ExportFailureReason.MISSING_SOURCE)), summary.failed)
        assertTrue(sink.created.isEmpty())
    }

    @Test
    fun aSecondSpaceWithTheSameNameGetsItsOwnFolder() {
        sink.folders += "Urlaub"
        addMedium(MEDIUM_A, MediaFixtures.jpeg(), "a.jpg", "image/jpeg")

        runBlocking { exporter().export(SpaceId(ID), setOf(MEDIUM_A)) }

        assertEquals("Urlaub (2)", sink.created.single().folderName)
        assertEquals("Urlaub (2)", runBlocking { database.exports().folderOf(ID) }?.folderName)
    }

    @Test
    fun aTakenFileNameGetsANumberAndTheOriginalIsKept() {
        sink.existing += "urlaub/bild.jpg"
        addMedium(MEDIUM_A, MediaFixtures.jpeg(), "bild.jpg", "image/jpeg")

        runBlocking { exporter().export(SpaceId(ID), setOf(MEDIUM_A)) }

        assertEquals("bild (2).jpg", sink.created.single().fileName)
    }

    @Test
    fun aSpaceThatIsNotEndedOrIsGoingAwayIsRefusedWithoutAnyWrite() {
        addMedium(MEDIUM_A, MediaFixtures.jpeg(), "a.jpg", "image/jpeg")
        insertSpace(OTHER, "DELETED", name = "Alt")
        insertSpace(PURGING, "ENDED", name = "Wird gelöscht", purging = true)

        assertEquals(ExportOutcome.NotExportable, runBlocking { exporter().export(SpaceId(OTHER), setOf(MEDIUM_A)) })
        assertEquals(ExportOutcome.NotExportable, runBlocking { exporter().export(SpaceId(PURGING), setOf(MEDIUM_A)) })
        assertEquals(
            ExportOutcome.NotExportable,
            runBlocking { exporter().export(SpaceId("DDDDDDDDDDDDDDDDDDDDDD"), setOf(MEDIUM_A)) },
        )
        assertTrue(sink.created.isEmpty())
    }

    @Test
    fun aCancelledCopyIsDiscardedAndTheCancellationIsPassedOn() {
        addMedium(MEDIUM_A, MediaFixtures.jpeg(), "a.jpg", "image/jpeg")
        sink.beforePublish = { throw CancellationException("abgebrochen (Test)") }

        assertThrows(CancellationException::class.java) {
            runBlocking { exporter().export(SpaceId(ID), setOf(MEDIUM_A)) }
        }
        assertEquals(1, sink.discarded.size)
        assertTrue(sink.published.isEmpty())
        assertTrue(runBlocking { database.exports().exportedIds(ID) }.isEmpty())
    }

    @Test
    fun aPurgeMarkerSetDuringTheRunStopsItBeforeTheNextFile() {
        addMedium(MEDIUM_A, MediaFixtures.jpeg(), "a.jpg", "image/jpeg")
        addMedium(MEDIUM_B, MediaFixtures.png(), "b.png", "image/png")
        sink.beforePublish = { database.spaces().markPurging(ID) }

        val outcome = runBlocking { exporter().export(SpaceId(ID), setOf(MEDIUM_A, MEDIUM_B)) }

        val summary = (outcome as ExportOutcome.Finished).summary
        assertEquals(1, summary.exported)
        assertTrue(summary.stoppedByPurge)
        assertEquals(1, sink.created.size)
    }

    private fun addMedium(
        id: String,
        bytes: ByteArray,
        name: String,
        mime: String,
    ) {
        val sha = sha256Of(bytes)
        SpaceStorage(spacesDir, SpaceId(ID)).contentPath(sha).toFile().writeBytes(bytes)
        insertMediumRow(id, sha, name, mime, bytes.size.toLong())
    }

    private fun insertMediumRow(
        id: String,
        sha: String,
        name: String,
        mime: String = "image/jpeg",
        size: Long = 64L,
    ) {
        runBlocking {
            database.media().insert(
                MediaEntity(
                    id = id,
                    spaceId = ID,
                    sha256 = sha,
                    mime = mime,
                    sizeBytes = size,
                    originalName = name,
                    uploaderId = "gast",
                    createdAt = 1_500L,
                    state = "READY",
                ),
            )
        }
    }

    private fun insertSpace(
        id: String,
        status: String,
        name: String,
        purging: Boolean = false,
    ) {
        runBlocking {
            database.spaces().upsert(
                SpaceEntity(
                    id = id,
                    name = name,
                    colorIndex = 1,
                    coverSha256 = null,
                    lifetimePreset = "ONE_DAY",
                    lifetimeUntil = null,
                    retentionDays = 1,
                    storageLimitBytes = 4L * 1024 * 1024 * 1024,
                    status = status,
                    createdAt = 1_000L,
                    updatedAt = 2_000L,
                    endedAt = if (status == "ENDED") 2_000L else null,
                    purging = purging,
                ),
            )
        }
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val ID = "AAAAAAAAAAAAAAAAAAAAAA"
        const val OTHER = "BBBBBBBBBBBBBBBBBBBBBB"
        const val PURGING = "CCCCCCCCCCCCCCCCCCCCCC"
        const val MEDIUM_A = "medium-a"
        const val MEDIUM_B = "medium-b"
    }
}
