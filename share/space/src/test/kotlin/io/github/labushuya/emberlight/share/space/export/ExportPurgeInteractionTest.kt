package io.github.labushuya.emberlight.share.space.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.MediaEntity
import io.github.labushuya.emberlight.share.space.MediaFixtures
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceStorage
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.management.SpaceCoverStore
import io.github.labushuya.emberlight.share.space.retention.PurgeResult
import io.github.labushuya.emberlight.share.space.retention.SpacePurge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * Übernahme und Löschung im selben Prozess (Paket 3.7, ADR 0022): Die Löschung wartet auf die laufende Kopie, bricht
 * die Übernahme nach der Datei ab, und die fertigen Kopien in der Galerie bleiben unberührt.
 */
@RunWith(RobolectricTestRunner::class)
class ExportPurgeInteractionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var spacesDir: File
    private lateinit var database: SpacesDatabase
    private lateinit var sink: FakeExportSink
    private lateinit var gate: SpaceExportGate
    private lateinit var covers: SpaceCoverStore

    @Before
    fun setUp() {
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(ApplicationProvider.getApplicationContext<Context>(), spacesDir)
        sink = FakeExportSink()
        gate = SpaceExportGate()
        covers = SpaceCoverStore(spacesDir)
        insertEndedSpace()
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
            reserveBytes = 0L,
            nowMillis = { 5_000L },
            captureTimeOf = { _, _ -> null },
        )

    private fun purger(): SpacePurge = SpacePurge(database, covers, nowMillis = { 9_000L }, exportGate = gate) { }

    @Test
    fun aPurgeWaitsForTheRunningCopyAndTheFinishedCopyStaysInTheGallery() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            sink.beforePublish = {
                entered.complete(Unit)
                release.await()
            }
            val export = async(Dispatchers.Default) { exporter().export(SpaceId(ID), setOf(MEDIUM_A, MEDIUM_B)) }
            entered.await()

            val purge = async(Dispatchers.Default) { purger().purge(SpaceId(ID)) }

            // Solange die Kopie läuft, wartet die Löschung. Der Space ist schon als „wird gelöscht“ markiert.
            assertNull(withTimeoutOrNull(300) { purge.await() })
            assertTrue(database.spaces().find(ID)?.purging == true)
            release.complete(Unit)

            val outcome = export.await()
            assertEquals(PurgeResult.Purged, purge.await())
            val summary = (outcome as ExportOutcome.Finished).summary
            assertEquals(1, summary.exported)
            assertTrue(summary.stoppedByPurge)
            // Die fertige Kopie liegt in der Galerie, sie wurde nicht verworfen und nicht angefasst.
            assertEquals(1, sink.published.size)
            assertTrue(sink.discarded.isEmpty())
            assertArrayEquals(MediaFixtures.jpeg(64), sink.contents[sink.published.single().uri])
            // Der Space ist gelöscht, die Vermerke gehen mit ihm.
            assertEquals("DELETED", database.spaces().find(ID)?.status)
            assertTrue(database.exports().exportedIds(ID).isEmpty())
            assertNull(database.exports().folderOf(ID))
        }

    @Test
    fun aPurgeAfterTheCopyFinishedKeepsTheCopiesInTheGallery() =
        runBlocking {
            val outcome = exporter().export(SpaceId(ID), setOf(MEDIUM_A))
            assertEquals(1, (outcome as ExportOutcome.Finished).summary.exported)

            assertEquals(PurgeResult.Purged, purger().purge(SpaceId(ID)))

            assertEquals(1, sink.published.size)
            assertTrue(sink.discarded.isEmpty())
            assertTrue(sink.contents.containsKey(sink.published.single().uri))
        }

    @Test
    fun anExportOfAnEndedSpaceIsRefusedOnceThePurgeHasStarted() =
        runBlocking {
            purger().purge(SpaceId(ID))

            assertEquals(ExportOutcome.NotExportable, exporter().export(SpaceId(ID), setOf(MEDIUM_A)))
            assertTrue(sink.created.isEmpty())
        }

    private fun insertEndedSpace() {
        runBlocking {
            database.spaces().upsert(
                SpaceEntity(
                    id = ID,
                    name = "Urlaub",
                    colorIndex = 1,
                    coverSha256 = null,
                    lifetimePreset = "ONE_DAY",
                    lifetimeUntil = null,
                    retentionDays = 1,
                    storageLimitBytes = 4L * 1024 * 1024 * 1024,
                    status = "ENDED",
                    createdAt = 1_000L,
                    updatedAt = 2_000L,
                    endedAt = 2_000L,
                ),
            )
        }
        addMedium(MEDIUM_A, MediaFixtures.jpeg(64), "a.jpg")
        addMedium(MEDIUM_B, MediaFixtures.png(), "b.png")
    }

    private fun addMedium(
        id: String,
        bytes: ByteArray,
        name: String,
    ) {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        SpaceStorage(spacesDir, SpaceId(ID)).contentPath(sha).toFile().writeBytes(bytes)
        runBlocking {
            database.media().insert(
                MediaEntity(
                    id = id,
                    spaceId = ID,
                    sha256 = sha,
                    mime = if (name.endsWith("png")) "image/png" else "image/jpeg",
                    sizeBytes = bytes.size.toLong(),
                    originalName = name,
                    uploaderId = "gast",
                    createdAt = 1_500L,
                    state = "READY",
                ),
            )
        }
    }

    private companion object {
        const val ID = "EEEEEEEEEEEEEEEEEEEEEE"
        const val MEDIUM_A = "medium-a"
        const val MEDIUM_B = "medium-b"
    }
}
