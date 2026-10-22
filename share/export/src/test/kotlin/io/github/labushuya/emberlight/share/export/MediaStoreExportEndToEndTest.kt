package io.github.labushuya.emberlight.share.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.MediaEntity
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceStorage
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.export.ExportOutcome
import io.github.labushuya.emberlight.share.space.export.ExportSink
import io.github.labushuya.emberlight.share.space.export.SpaceExportGate
import io.github.labushuya.emberlight.share.space.export.SpaceExporter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * Ganze Strecke der Übernahme (Paket 3.7, ADR 0022): Space-Datei, Kopie in den Medienspeicher, Hash-Prüfung nach dem
 * Zurücklesen, Vermerk, zweite Übernahme ohne Duplikat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MediaStoreExportEndToEndTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var spacesDir: File
    private lateinit var database: SpacesDatabase
    private lateinit var provider: FakeMediaStoreProvider
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(context, spacesDir)
        provider = FakeMediaStoreProvider(folder.newFolder("store"))
        provider.register(context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun aCopyIsWrittenVerifiedAndRecordedAndASecondRunAddsNothing() {
        val bytes = "Bild aus dem Space".toByteArray()
        val sha = sha256Of(bytes)
        SpaceStorage(spacesDir, SpaceId(ID)).contentPath(sha).toFile().writeBytes(bytes)
        runBlocking {
            database.spaces().upsert(
                SpaceEntity(
                    id = ID,
                    name = "Urlaub: Berlin",
                    colorIndex = 1,
                    coverSha256 = null,
                    lifetimePreset = "ONE_DAY",
                    lifetimeUntil = null,
                    retentionDays = 1,
                    storageLimitBytes = 1L shl 30,
                    status = "ENDED",
                    createdAt = 1_000L,
                    updatedAt = 2_000L,
                    endedAt = 2_000L,
                ),
            )
            database.media().insert(
                MediaEntity(
                    id = MEDIUM,
                    spaceId = ID,
                    sha256 = sha,
                    mime = "image/jpeg",
                    sizeBytes = bytes.size.toLong(),
                    originalName = "foto.jpg",
                    uploaderId = "gast",
                    createdAt = 1_500L,
                    state = "READY",
                ),
            )
        }
        val exporter =
            SpaceExporter(
                database = database,
                spacesDir = spacesDir,
                // Der Platz wird vorgegeben. Unter Robolectric meldet StatFs keinen echten Wert.
                sink =
                    object : ExportSink by MediaStoreExportSink(context) {
                        override fun availableBytes(): Long = Long.MAX_VALUE
                    },
                gate = SpaceExportGate(),
                reserveBytes = 0L,
                captureTimeOf = { _, _ -> null },
            )

        val first = runBlocking { exporter.export(SpaceId(ID), setOf(MEDIUM)) } as ExportOutcome.Finished
        val second = runBlocking { exporter.export(SpaceId(ID), setOf(MEDIUM)) } as ExportOutcome.Finished

        assertEquals(1, first.summary.exported)
        assertTrue(first.summary.failed.isEmpty())
        assertEquals(0, second.summary.exported)
        assertEquals(1, second.summary.skippedAlreadyExported)
        val copy = provider.rows.values.single()
        assertEquals(0, copy["is_pending"])
        assertEquals("Pictures/Emberlight/Urlaub_ Berlin/", copy["relative_path"])
        assertEquals("foto.jpg", copy["_display_name"])
        assertArrayEquals(bytes, provider.contentOf((copy["_id"] as Long)))
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val ID = "AAAAAAAAAAAAAAAAAAAAAA"
        const val MEDIUM = "medium-e2e"
    }
}
