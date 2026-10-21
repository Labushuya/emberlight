package io.github.labushuya.emberlight.share.space.retention

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.BytesSource
import io.github.labushuya.emberlight.share.space.ChunkSource
import io.github.labushuya.emberlight.share.space.IngestResult
import io.github.labushuya.emberlight.share.space.MediaFixtures
import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Principal
import io.github.labushuya.emberlight.share.space.RejectReason
import io.github.labushuya.emberlight.share.space.Role
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceLimits
import io.github.labushuya.emberlight.share.space.SpaceVault
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.StorageProbe
import io.github.labushuya.emberlight.share.space.management.SpaceCoverStore
import io.github.labushuya.emberlight.share.space.participants.FakeInviteSecretCipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Löschen während eines Uploads (Paket 3.6c, ADR 0021): Ein Upload, der vor dem Löschen begann, schreibt danach keine Datei
 * und keinen Medieneintrag mehr. Die Lösch-Funktion wird wie in der Anwendung aufgerufen ([SpacePurge]), mit dem Retire der
 * Vault als Freigabe.
 */
@RunWith(RobolectricTestRunner::class)
class UploadAcrossPurgeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var spacesDir: File
    private lateinit var database: SpacesDatabase
    private val space = SpaceId.generate()
    private val guest = Principal(ParticipantId.generate(), Role.GUEST)
    private val limits =
        SpaceLimits(
            imageMaxBytes = 1_000,
            videoMaxBytes = 5_000,
            storageLimitBytes = 1_000_000,
            minDeviceReserveBytes = 0,
        )

    @Before
    fun setUp() {
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(ApplicationProvider.getApplicationContext<Context>(), spacesDir)
        // Ein beendeter Space mit Aufbewahrung „sofort“ (0 Tage), wie er nach dem Ende im Nachlauf steht.
        runBlocking {
            database.spaces().upsert(
                SpaceEntity(
                    id = space.value,
                    name = "Test",
                    colorIndex = 1,
                    coverSha256 = null,
                    lifetimePreset = "ONE_DAY",
                    lifetimeUntil = null,
                    retentionDays = 0,
                    storageLimitBytes = limits.storageLimitBytes,
                    status = "ENDED",
                    createdAt = 1_000L,
                    updatedAt = 2_000L,
                    endedAt = 2_000L,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun vault(): SpaceVault =
        SpaceVault.open(
            database,
            spacesDir,
            space,
            limits,
            StorageProbe { Long.MAX_VALUE / 4 },
            FakeInviteSecretCipher(),
        )

    /** Dateien unter dem Space-Verzeichnis, Namen relativ dazu. */
    private fun filesLeft(): List<String> =
        File(spacesDir, space.value)
            .walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(File(spacesDir, space.value)).path }
            .toList()

    @Test
    fun anUploadThatStartedBeforeTheDeletionWritesNothingAfterIt() {
        val vault = vault()
        val bytes = MediaFixtures.jpeg(100)
        val source = GatedSource(bytes)
        val purge = SpacePurge(database, SpaceCoverStore(spacesDir)) { vault.retire() }

        runBlocking {
            val upload = async(Dispatchers.Default) { vault.ingest(guest, "bild.jpg", bytes.size.toLong(), source) }
            // Der Upload steht mitten im Strom: der Kopf ist da, die Teildatei liegt im Space-Verzeichnis.
            source.paused.await()

            assertEquals(PurgeResult.Purged, purge.purge(space))
            source.release.complete(Unit)

            assertEquals(IngestResult.Rejected(RejectReason.SpaceDeleting), upload.await())
        }
        assertEquals(emptyList<String>(), filesLeft())
        assertTrue(runBlocking { database.media().allOf(space.value) }.isEmpty())
    }

    @Test
    fun aRetiredVaultTakesNoUploadEvenIfTheDeletionMarkIsNotSetYet() {
        val vault = vault()
        val bytes = MediaFixtures.jpeg(100)

        val result =
            runBlocking {
                vault.retire()
                vault.ingest(guest, "bild.jpg", bytes.size.toLong(), BytesSource(bytes))
            }

        assertEquals(IngestResult.Rejected(RejectReason.SpaceDeleting), result)
        assertEquals(emptyList<String>(), filesLeft())
    }

    /**
     * Quelle, die nach dem Kopf anhält. Der erste Aufruf liefert den Kopf, der zweite meldet [paused] und wartet auf
     * [release], danach kommt der Rest des Inhalts.
     */
    private class GatedSource(
        private val bytes: ByteArray,
    ) : ChunkSource {
        val paused = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private var position = 0
        private var reads = 0

        override suspend fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            reads++
            if (reads > 1) {
                paused.complete(Unit)
                release.await()
            }
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }
}
