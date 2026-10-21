package io.github.labushuya.emberlight.share.space.preview

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.BytesSource
import io.github.labushuya.emberlight.share.space.IngestResult
import io.github.labushuya.emberlight.share.space.MediaEntity
import io.github.labushuya.emberlight.share.space.MediaId
import io.github.labushuya.emberlight.share.space.MediaFixtures
import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Principal
import io.github.labushuya.emberlight.share.space.Role
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceLimits
import io.github.labushuya.emberlight.share.space.SpaceStorage
import io.github.labushuya.emberlight.share.space.SpaceVault
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.StorageProbe
import io.github.labushuya.emberlight.share.space.participants.FakeInviteSecretCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Vorschauen im Vault (Paket 3.5a-2, ADR 0019): Einreihung ohne Warten, Zustände, Speicherzählung, Löschen,
 * Wiederaufnahme nach einem Neustart und Reihenfolge des Worker. Der Renderer ist ein Fake, die Zeit fest.
 */
@RunWith(RobolectricTestRunner::class)
class SpaceVaultPreviewTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: SpacesDatabase
    private lateinit var spacesDir: File
    private val renderer = FakePreviewRenderer()
    private val guest = Principal(ParticipantId.generate(), Role.GUEST)
    private val space = SpaceId.generate()
    private val limits =
        SpaceLimits(
            imageMaxBytes = 1_000,
            videoMaxBytes = 5_000,
            storageLimitBytes = 1_000_000,
            minDeviceReserveBytes = 0,
        )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(context, spacesDir)
        runBlocking { vault().registerParticipant(guest.participantId, "Gast", Role.GUEST, "0".repeat(64)) }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun vault(
        limits: SpaceLimits = this.limits,
        renderer: FakePreviewRenderer = this.renderer,
    ): SpaceVault =
        SpaceVault.open(
            database,
            spacesDir,
            space,
            limits,
            StorageProbe { Long.MAX_VALUE / 4 },
            FakeInviteSecretCipher(),
            renderer,
        )

    private fun upload(
        vault: SpaceVault,
        bytes: ByteArray,
        name: String = "bild.jpg",
        keepLocation: Boolean = false,
    ): MediaId =
        runBlocking {
            val result = vault.ingest(guest, name, bytes.size.toLong(), BytesSource(bytes), keepLocation)
            check(result is IngestResult.Created) { "Erwartet Created, war $result" }
            result.record.id
        }

    private fun entryOf(
        vault: SpaceVault,
        id: MediaId,
    ) = runBlocking { checkNotNull(vault.entry(id)) }

    private fun shaOf(
        vault: SpaceVault,
        id: MediaId,
    ): String = runBlocking { checkNotNull(vault.find(id)).sha256 }

    private fun previewFile(
        sha256: String,
        longEdge: Int,
    ): File = File(spacesDir, "${space.value}/${SpaceStorage.PREVIEW_DIR}/${SpaceStorage.previewFileName(sha256, longEdge)}")

    @Test
    fun uploadQueuesThePreviewWithoutRenderingIt() {
        val vault = vault()

        val id = upload(vault, MediaFixtures.jpeg(100))

        assertEquals("PENDING", entryOf(vault, id).previewState)
        assertTrue(renderer.calls.isEmpty())
        assertEquals(PreviewLookup.Unavailable, runBlocking { vault.previewOf(id) })
        assertEquals(PreviewLookup.NotFound, runBlocking { vault.previewOf(MediaId.generate()) })
    }

    @Test
    fun aRenderedPreviewIsReadyServedAndCountedAgainstTheStorage() {
        val vault = vault()
        val id = upload(vault, MediaFixtures.jpeg(100))

        runBlocking { vault.renderPreviews(id) }

        val lookup = runBlocking { vault.previewOf(id) }
        assertTrue(lookup is PreviewLookup.Ready)
        assertEquals("image-320", (lookup as PreviewLookup.Ready).path.toFile().readText())
        assertEquals("READY", entryOf(vault, id).previewState)
        // Original (100 Bytes) plus Vorschau ("image-320", 9 Bytes) zählen gegen das Speicherlimit.
        assertEquals(109L, runBlocking { vault.usage() }.usedBytes)
    }

    @Test
    fun aFailingRendererLeavesTheMediumFailedWithoutAFile() {
        val failing = FakePreviewRenderer(ok = false)
        val vault = vault(renderer = failing)
        val id = upload(vault, MediaFixtures.jpeg(100))

        runBlocking { vault.renderPreviews(id) }

        assertEquals("FAILED", entryOf(vault, id).previewState)
        assertEquals(PreviewLookup.Unavailable, runBlocking { vault.previewOf(id) })
        assertFalse(previewFile(shaOf(vault, id), PreviewRules.PREVIEW_LONG_EDGE).exists())
        assertEquals(100L, runBlocking { vault.usage() }.usedBytes)
    }

    @Test
    fun aPreviewThatWouldExceedTheSpaceLimitIsFailedInsteadOfStored() {
        val tight = limits.copy(storageLimitBytes = 105)
        val vault = vault(limits = tight)
        val id = upload(vault, MediaFixtures.jpeg(100))

        runBlocking { vault.renderPreviews(id) }

        assertEquals("FAILED", entryOf(vault, id).previewState)
        assertEquals(100L, runBlocking { vault.usage() }.usedBytes)
    }

    @Test
    fun aHeicMediumGetsAViewAndTheViewIsServedAsTheOneThousandSixHundredPixelImage() {
        val vault = vault()
        val id = upload(vault, MediaFixtures.heic(), name = "foto.heic", keepLocation = true)
        assertEquals("PENDING", entryOf(vault, id).viewState)

        runBlocking { vault.renderPreviews(id) }

        val view = runBlocking { vault.viewOf(id) } as PreviewLookup.Ready
        assertEquals("image-1600", view.path.toFile().readText())
        assertEquals("READY", entryOf(vault, id).viewState)
        assertTrue(runBlocking { vault.previewOf(id) } is PreviewLookup.Ready)
        assertEquals(listOf(320, 1600), renderer.calls.filter { it.kind == "image" }.map { it.longEdge })
    }

    @Test
    fun aJpegHasNoViewAndTheViewLookupIsUnavailable() {
        val vault = vault()
        val id = upload(vault, MediaFixtures.jpeg(100))

        runBlocking { vault.renderPreviews(id) }

        assertNull(entryOf(vault, id).viewState)
        assertEquals(PreviewLookup.Unavailable, runBlocking { vault.viewOf(id) })
    }

    @Test
    fun aVideoGetsItsCodecAtUploadAndItsPosterFrameFromTheWorker() {
        val vault = vault()
        val id = upload(vault, MediaFixtures.mp4(), name = "clip.mp4")

        assertEquals("avc1", entryOf(vault, id).videoCodec)
        assertEquals(listOf("codec"), renderer.calls.map { it.kind })

        runBlocking { vault.renderPreviews(id) }

        assertEquals("video-320", (runBlocking { vault.previewOf(id) } as PreviewLookup.Ready).path.toFile().readText())
        assertEquals(listOf("codec", "video"), renderer.calls.map { it.kind })
    }

    @Test
    fun anUnrecognizedCodecIsStoredAsEmptyAndNotRetried() {
        val silent = FakePreviewRenderer(codec = null)
        val vault = vault(renderer = silent)
        val id = upload(vault, MediaFixtures.mp4(), name = "clip.mp4")

        runBlocking { vault.renderPreviews(id) }
        runBlocking { vault.renderPreviews(id) }

        assertEquals("", entryOf(vault, id).videoCodec)
        assertEquals(1, silent.calls.count { it.kind == "codec" })
    }

    @Test
    fun deletingTheMediumRemovesItsPreviewFilesAndItsBytes() {
        val vault = vault()
        val id = upload(vault, MediaFixtures.jpeg(100))
        runBlocking { vault.renderPreviews(id) }
        val sha = shaOf(vault, id)
        assertTrue(previewFile(sha, PreviewRules.PREVIEW_LONG_EDGE).exists())

        runBlocking { vault.delete(guest, id) }

        assertFalse(previewFile(sha, PreviewRules.PREVIEW_LONG_EDGE).exists())
        assertEquals(0L, runBlocking { vault.usage() }.usedBytes)
    }

    @Test
    fun uploadsAreRenderedInTheOrderTheyArrived() {
        val vault = vault()
        val ids = listOf(100, 101, 102).map { upload(vault, MediaFixtures.jpeg(it)) }
        val expected = ids.map { shaOf(vault, it) }
        renderer.expect(ids.size)
        val workers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            vault.startPreviews(workers)

            assertTrue(renderer.awaitCalls())
            assertEquals(expected, renderer.calls.map { it.source })
        } finally {
            runBlocking { workers.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() }
            vault.stopPreviews()
        }
    }

    @Test
    fun aRestartRemovesOrphanPreviewsAndRequeuesTheOpenMedia() {
        val first = vault()
        val rendered = upload(first, MediaFixtures.jpeg(100))
        runBlocking { first.renderPreviews(rendered) }
        val open = upload(first, MediaFixtures.jpeg(101))
        val orphan = previewFile("f".repeat(64), PreviewRules.PREVIEW_LONG_EDGE).apply {
            parentFile?.mkdirs()
            writeText("verwaist")
        }
        val restarted = vault()

        runBlocking { restarted.recover() }

        assertFalse(orphan.exists())
        assertEquals("READY", entryOf(restarted, rendered).previewState)
        assertEquals("PENDING", entryOf(restarted, open).previewState)
        renderer.calls.clear()
        renderer.expect(1)
        val workers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            restarted.startPreviews(workers)

            assertTrue(renderer.awaitCalls())
            assertEquals(listOf(shaOf(first, open)), renderer.calls.map { it.source })
        } finally {
            runBlocking { workers.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() }
            restarted.stopPreviews()
        }
    }

    @Test
    fun aReadyPreviewWhoseFileIsGoneGoesBackToPendingOnRestart() {
        val first = vault()
        val id = upload(first, MediaFixtures.jpeg(100))
        runBlocking { first.renderPreviews(id) }
        SpaceStorage(spacesDir, space).deletePreviews(shaOf(first, id))
        val restarted = vault()

        runBlocking { restarted.recover() }

        assertEquals("PENDING", entryOf(restarted, id).previewState)
        assertEquals(100L, runBlocking { restarted.usage() }.usedBytes)
    }

    @Test
    fun aMediumFromBeforeThePreviewsGetsPendingAndIsQueuedOnRestart() {
        val vault = vault()
        val sha = "a".repeat(64)
        val legacyId = MediaId.generate()
        runBlocking {
            database.media().insert(
                MediaEntity(
                    id = legacyId.value,
                    spaceId = space.value,
                    sha256 = sha,
                    mime = "image/jpeg",
                    sizeBytes = 10,
                    originalName = "alt.jpg",
                    uploaderId = guest.participantId.value,
                    createdAt = 5,
                    state = "READY",
                ),
            )
        }
        assertNull(entryOf(vault, legacyId).previewState)
        renderer.calls.clear()
        renderer.expect(1)
        val workers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking { vault.recover() }
            assertEquals("PENDING", entryOf(vault, legacyId).previewState)
            vault.startPreviews(workers)

            assertTrue(renderer.awaitCalls())
            assertEquals(listOf(sha), renderer.calls.map { it.source })
        } finally {
            runBlocking { workers.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() }
            vault.stopPreviews()
        }
    }
}
