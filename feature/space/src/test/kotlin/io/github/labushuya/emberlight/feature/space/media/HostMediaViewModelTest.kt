package io.github.labushuya.emberlight.feature.space.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.feature.space.run.FakeInviteSecretCipher
import io.github.labushuya.emberlight.feature.space.run.SpaceHostActions
import io.github.labushuya.emberlight.share.space.MediaEntity
import io.github.labushuya.emberlight.share.space.MediaId
import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Role
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceVaults
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.participants.Secrets
import io.github.labushuya.emberlight.share.space.run.RunClock
import io.github.labushuya.emberlight.share.space.run.SpaceRunRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
 * Medienansicht des Gastgebers (Paket 3.5c): Filter „Alle“ und „Meine“, Uploader und entfernte Teilnehmer, Typ Video,
 * Standort-Vermerk, Aktualisierung aus dem Index und das Entfernen durch den Gastgeber.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HostMediaViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val space = SpaceId.parse(io.github.labushuya.emberlight.feature.space.SpaceFixtures.ID_HOCHZEIT)!!
    private val cipher = FakeInviteSecretCipher()
    private val host = ParticipantId.generate()
    private val guest = ParticipantId.generate()
    private lateinit var database: SpacesDatabase
    private lateinit var spacesDir: File
    private lateinit var vaults: SpaceVaults
    private lateinit var model: HostMediaViewModel
    private lateinit var collector: kotlinx.coroutines.Job
    private var latest = HostMediaUiState()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(context, spacesDir)
        vaults = SpaceVaults.create(database, spacesDir)
        val clock =
            object : RunClock {
                override fun wallMillis(): Long = NOW

                override fun elapsedMillis(): Long = NOW

                override fun uptimeMillis(): Long = NOW
            }
        val actions = SpaceHostActions(vaults, cipher)
        model = HostMediaViewModel(SpaceRunRepository(database, clock), actions, HostMediaStore(vaults, cipher))
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
            val vault = vaults.vaultOf(space, cipher)!!
            vault.registerParticipant(host, "Gastgeber", Role.HOST, Secrets.hashHex("h"))
            vault.registerParticipant(guest, "Gast", Role.GUEST, Secrets.hashHex("g"))
        }
        collector = CoroutineScope(Dispatchers.Unconfined).launch { model.state.collect { latest = it } }
    }

    @After
    fun tearDown() {
        collector.cancel()
        vaults.close()
        database.close()
        Dispatchers.resetMain()
    }

    private fun insert(
        id: MediaId,
        uploader: ParticipantId,
        mime: String = "image/jpeg",
        locationKept: Boolean = false,
        hash: Char = 'a',
    ) {
        runBlocking {
            database.media().insert(
                MediaEntity(
                    id = id.value,
                    spaceId = space.value,
                    sha256 = hash.toString().repeat(64),
                    mime = mime,
                    sizeBytes = 2_048L,
                    originalName = "datei.jpg",
                    uploaderId = uploader.value,
                    createdAt = NOW - 60_000L,
                    state = "READY",
                    locationKept = locationKept,
                ),
            )
        }
    }

    /** Wartet, bis die Ansicht den gewünschten Stand zeigt. Der Index meldet Änderungen asynchron. */
    private fun awaitState(check: (HostMediaUiState) -> Boolean) {
        runBlocking {
            withTimeout(WAIT_MS) {
                while (!check(latest)) delay(POLL_MS)
            }
        }
    }

    @Test
    fun aShownSpaceListsAllMediaWithTheirUploaders() {
        val ownMedia = MediaId.generate()
        val guestMedia = MediaId.generate()
        insert(ownMedia, host, hash = 'a')
        insert(guestMedia, guest, hash = 'b')

        model.show(space)

        awaitState { it.loaded && it.items.size == 2 }
        assertEquals(setOf(ownMedia.value, guestMedia.value), latest.items.map { it.id }.toSet())
        assertEquals("Gast", latest.items.single { it.id == guestMedia.value }.uploaderName)
    }

    @Test
    fun theMineFilterShowsOnlyTheHostsOwnUploads() {
        val ownMedia = MediaId.generate()
        insert(ownMedia, host, hash = 'a')
        insert(MediaId.generate(), guest, hash = 'b')
        model.show(space)
        awaitState { it.items.size == 2 }

        model.setFilter(MediaFilter.MINE)

        assertEquals(listOf(ownMedia.value), latest.visible.map { it.id })
        model.setFilter(MediaFilter.ALL)
        assertEquals(2, latest.visible.size)
    }

    @Test
    fun aNewUploadAppearsWithoutReloadingTheScreen() {
        model.show(space)
        awaitState { it.loaded }
        assertTrue(latest.items.isEmpty())

        val later = MediaId.generate()
        insert(later, guest, hash = 'c')

        awaitState { it.items.any { item -> item.id == later.value } }
    }

    @Test
    fun aRemovedUploaderIsMarkedAndTheMediaStays() {
        val media = MediaId.generate()
        insert(media, guest, hash = 'd')
        runBlocking { vaults.vaultOf(space, cipher)!!.removeParticipant(guest, deleteUploads = false) }
        model.show(space)

        awaitState { it.items.any { item -> item.id == media.value } }
        val item = latest.items.single { it.id == media.value }
        assertTrue(item.uploaderRemoved)
        assertEquals("Gast", item.uploaderName)
    }

    @Test
    fun videosAreFlaggedAndLocationKeptIsShown() {
        val video = MediaId.generate()
        insert(video, host, mime = "video/mp4", locationKept = true, hash = 'e')
        model.show(space)

        awaitState { it.items.any { item -> item.id == video.value } }
        val item = latest.items.single()
        assertTrue(item.isVideo)
        assertFalse(item.isHeic)
        assertTrue(item.locationKept)
    }

    @Test
    fun aMediumWithoutAReadyPreviewHasNoFile() {
        val media = MediaId.generate()
        insert(media, guest, hash = 'f')
        model.show(space)

        awaitState { it.items.any { item -> item.id == media.value } }
        assertNull(latest.items.single().previewFile)
    }

    @Test
    fun theHostRemovesAMediumFromTheList() {
        val first = MediaId.generate()
        val second = MediaId.generate()
        insert(first, guest, hash = '1')
        insert(second, guest, hash = '2')
        model.show(space)
        awaitState { it.items.size == 2 }

        model.delete(first.value)

        awaitState { it.items.map { item -> item.id } == listOf(second.value) }
        // Der Index blendet das Medium schon im Zustand DELETING aus. Die Zeile verschwindet, wenn die Datei weg ist.
        runBlocking {
            withTimeout(WAIT_MS) {
                while (database.media().findById(first.value) != null) delay(POLL_MS)
            }
        }
        assertNull(runBlocking { database.media().findById(first.value) })
    }

    @Test
    fun withoutAShownSpaceTheListIsEmpty() {
        assertFalse(latest.loaded)
        assertTrue(latest.items.isEmpty())
    }

    private companion object {
        const val NOW = 1_000_000_000L
        const val WAIT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
