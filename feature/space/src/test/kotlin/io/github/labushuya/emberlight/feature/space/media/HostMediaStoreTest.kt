package io.github.labushuya.emberlight.feature.space.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.feature.space.run.FakeInviteSecretCipher
import io.github.labushuya.emberlight.share.space.MediaEntity
import io.github.labushuya.emberlight.share.space.MediaId
import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceVaults
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.preview.PreviewState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Dateien der Medienansicht (Paket 3.5c): Vorschau und Detail kommen aus dem Space-Speicher, und eine Vorschau ist erst
 * sichtbar, wenn sie bereit ist. HEIC zeigt die 1600-px-Ansicht, sonst das Original.
 */
@RunWith(RobolectricTestRunner::class)
class HostMediaStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val space = SpaceId.generate()
    private val cipher = FakeInviteSecretCipher()
    private lateinit var database: SpacesDatabase
    private lateinit var spacesDir: File
    private lateinit var vaults: SpaceVaults
    private lateinit var store: HostMediaStore

    @Before
    fun setUp() {
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(context, spacesDir)
        vaults = SpaceVaults.create(database, spacesDir)
        store = HostMediaStore(vaults, cipher)
        runBlocking {
            database.spaces().upsert(
                SpaceEntity(
                    id = space.value,
                    name = "Test",
                    colorIndex = 1,
                    coverSha256 = null,
                    lifetimePreset = null,
                    lifetimeUntil = null,
                    retentionDays = 7,
                    storageLimitBytes = 1_000_000_000L,
                    status = "ACTIVE",
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        vaults.close()
        database.close()
    }

    /** Legt ein bereites Medium an und schreibt seine Datei in den Space-Speicher. */
    private fun medium(
        hash: Char,
        mime: String,
        preview: String?,
        view: String? = null,
    ): MediaId {
        val id = MediaId.generate()
        val sha = hash.toString().repeat(64)
        runBlocking {
            database.media().insert(
                MediaEntity(
                    id = id.value,
                    spaceId = space.value,
                    sha256 = sha,
                    mime = mime,
                    sizeBytes = 4L,
                    originalName = "datei",
                    uploaderId = ParticipantId.generate().value,
                    createdAt = 1L,
                    state = "READY",
                    previewState = preview,
                    viewState = view,
                ),
            )
        }
        // Der Ordner des Space entsteht beim Öffnen der Vault. Der Test legt ihn selbst an, bevor er die Datei schreibt.
        File(spacesDir, space.value).mkdirs()
        File(spacesDir, "${space.value}/$sha").writeBytes(byteArrayOf(1, 2, 3, 4))
        return id
    }

    @Test
    fun aReadyPreviewIsNotShownBeforeItIsRendered() {
        val id = medium('a', "image/jpeg", preview = PreviewState.PENDING)

        assertNull(runBlocking { store.previewFile(space, id) })
    }

    @Test
    fun aMediumWithoutAnyRowHasNoFiles() {
        val unknown = MediaId.generate()

        assertNull(runBlocking { store.previewFile(space, unknown) })
        assertNull(runBlocking { store.detailFile(space, unknown, heic = false) })
    }

    @Test
    fun theDetailOfAnOrdinaryImageIsItsOriginal() {
        val id = medium('b', "image/jpeg", preview = PreviewState.READY)

        val file = runBlocking { store.detailFile(space, id, heic = false) }

        assertEquals(File(spacesDir, "${space.value}/${"b".repeat(64)}").canonicalPath, file?.canonicalPath)
    }

    @Test
    fun aHeicDetailWaitsForItsJpegView() {
        val id = medium('c', "image/heic", preview = PreviewState.READY, view = PreviewState.PENDING)

        assertNull(runBlocking { store.detailFile(space, id, heic = true) })
    }
}
