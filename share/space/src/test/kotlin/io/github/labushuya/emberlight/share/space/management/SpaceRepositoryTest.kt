package io.github.labushuya.emberlight.share.space.management

import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.MediaEntity
import io.github.labushuya.emberlight.share.space.MediaFixtures
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import kotlinx.coroutines.flow.first
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
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Anlegen, Bearbeiten, Löschen und Titelbild-Wechsel über [SpaceRepository] (Paket 3.2). Room läuft echt, unter
 * Robolectric, mit einer Datei im Test-Ordner.
 */
@RunWith(RobolectricTestRunner::class)
class SpaceRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var spacesDir: File
    private lateinit var database: SpacesDatabase
    private lateinit var covers: SpaceCoverStore
    private lateinit var repository: SpaceRepository
    private var now = NOW

    @Before
    fun setUp() {
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(ApplicationProvider.getApplicationContext(), spacesDir)
        covers = SpaceCoverStore(spacesDir)
        repository = SpaceRepository(database, covers) { now }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun settings(name: String = "Hochzeit") = SpaceDefaults.settings().copy(name = name)

    private fun spaceDirectory(id: SpaceId): File = File(spacesDir, id.value)

    @Test
    fun createStoresTheDraftWithTrimmedNameAndTimestamps() =
        runBlocking {
            val id = SpaceId.generate()

            val result = repository.create(id, settings("  Hochzeit  "), null)

            val record = (result as SaveResult.Saved).record
            assertEquals("Hochzeit", record.settings.name)
            assertEquals(SpaceStatus.DRAFT, record.status)
            assertEquals(NOW, record.createdAt)
            assertEquals(NOW, record.updatedAt)
            assertEquals(record, repository.find(id))
        }

    @Test
    fun invalidSettingsAreReportedAndNothingIsWritten() =
        runBlocking {
            val id = SpaceId.generate()

            val result = repository.create(id, settings(name = " "), null)

            assertEquals(SaveResult.Invalid(setOf(SettingsError.NAME_EMPTY)), result)
            assertNull(repository.find(id))
        }

    @Test
    fun lifetimeUntilIsStoredAsEndAndReadBack() =
        runBlocking {
            val id = SpaceId.generate()
            val end = NOW + 3_600_000L
            val untilSettings = settings().copy(lifetime = Lifetime.Until(end))

            repository.create(id, untilSettings, null)

            assertEquals(Lifetime.Until(end), repository.find(id)?.settings?.lifetime)
        }

    @Test
    fun updateChangesTheDraftAndKeepsCreationTime() =
        runBlocking {
            val id = SpaceId.generate()
            repository.create(id, settings("Alt"), null)
            now = NOW + 1000

            val result = repository.update(id, settings("Neu").copy(colorIndex = 3), null)

            val record = (result as SaveResult.Saved).record
            assertEquals("Neu", record.settings.name)
            assertEquals(3, record.settings.colorIndex)
            assertEquals(NOW, record.createdAt)
            assertEquals(NOW + 1000, record.updatedAt)
        }

    @Test
    fun updateOfAnActiveSpaceIsRefusedAndLeavesItUnchanged() =
        runBlocking {
            val id = SpaceId.generate()
            database.spaces().upsert(
                SpaceEntity(
                    id = id.value,
                    name = "Aktiv",
                    colorIndex = 0,
                    coverSha256 = null,
                    lifetimePreset = "ONE_DAY",
                    lifetimeUntil = null,
                    retentionDays = 7,
                    storageLimitBytes = SpaceDefaults.STORAGE_LIMIT_BYTES,
                    status = SpaceStatus.ACTIVE.name,
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )

            val result = repository.update(id, settings("Geändert"), null)

            assertEquals(SaveResult.NotEditable, result)
            assertEquals("Aktiv", repository.find(id)?.settings?.name)
        }

    @Test
    fun createWithAnExistingIdIsRefused() =
        runBlocking {
            val id = SpaceId.generate()
            repository.create(id, settings(), null)

            assertEquals(SaveResult.NotEditable, repository.create(id, settings("Zweiter"), null))
        }

    @Test
    fun updateOfAnUnknownSpaceReportsNotFound() =
        runBlocking {
            assertEquals(SaveResult.NotFound, repository.update(SpaceId.generate(), settings(), null))
        }

    @Test
    fun deleteRemovesRowsMediaAndTheWholeStorage() =
        runBlocking {
            val id = SpaceId.generate()
            repository.create(id, settings(), null)
            database.media().insert(
                MediaEntity(
                    id = "medium-1",
                    spaceId = id.value,
                    sha256 = "0".repeat(64),
                    mime = "image/jpeg",
                    sizeBytes = 3,
                    originalName = "a.jpg",
                    uploaderId = "teilnehmer-1",
                    createdAt = NOW,
                    state = "READY",
                ),
            )
            File(spaceDirectory(id), "0".repeat(64)).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }

            assertTrue(repository.delete(id))

            assertNull(repository.find(id))
            assertNull(database.media().findById("medium-1"))
            assertFalse(spaceDirectory(id).exists())
        }

    @Test
    fun deleteOfAnUnknownSpaceReturnsFalse() =
        runBlocking {
            assertFalse(repository.delete(SpaceId.generate()))
        }

    @Test
    fun coverIsReplacedAndTheOldFileIsRemoved() =
        runBlocking {
            val id = SpaceId.generate()
            repository.create(id, settings(), null)
            val first = (
                repository.storeCover(
                    id,
                    ByteArrayInputStream(MediaFixtures.jpeg(size = 120)),
                ) as CoverResult.Stored
            )
            repository.update(id, settings(), first.sha256)
            assertTrue(covers.exists(id, first.sha256))

            val second = (
                repository.storeCover(
                    id,
                    ByteArrayInputStream(MediaFixtures.jpeg(size = 160)),
                ) as CoverResult.Stored
            )
            val result = repository.update(id, settings(), second.sha256)

            assertEquals(second.sha256, (result as SaveResult.Saved).record.coverSha256)
            assertFalse(covers.exists(id, first.sha256))
            assertTrue(covers.exists(id, second.sha256))
        }

    @Test
    fun removingTheCoverDeletesTheFile() =
        runBlocking {
            val id = SpaceId.generate()
            repository.create(id, settings(), null)
            val stored =
                repository.storeCover(
                    id,
                    ByteArrayInputStream(MediaFixtures.jpeg(size = 120)),
                ) as CoverResult.Stored
            repository.update(id, settings(), stored.sha256)

            val result = repository.update(id, settings(), null)

            assertNull((result as SaveResult.Saved).record.coverSha256)
            assertFalse(covers.exists(id, stored.sha256))
        }

    @Test
    fun aCoverThatIsNotInStorageIsRefused() =
        runBlocking {
            val id = SpaceId.generate()

            val result = repository.create(id, settings(), "a".repeat(64))

            assertEquals(SaveResult.Invalid(setOf(SettingsError.COVER_MISSING)), result)
        }

    @Test
    fun discardingAnUnsavedDraftRemovesItsStorage() =
        runBlocking {
            val id = SpaceId.generate()
            repository.storeCover(id, ByteArrayInputStream(MediaFixtures.jpeg(size = 120)))
            assertTrue(spaceDirectory(id).isDirectory)

            repository.discardUnsaved(id)

            assertFalse(spaceDirectory(id).exists())
        }

    @Test
    fun discardingNeverRemovesASavedSpace() =
        runBlocking {
            val id = SpaceId.generate()
            repository.create(id, settings(), null)

            repository.discardUnsaved(id)

            assertTrue(repository.find(id) != null)
        }

    @Test
    fun observeAllEmitsTheSpacesNewestFirst() =
        runBlocking {
            val older = SpaceId.generate()
            repository.create(older, settings("Alt"), null)
            now = NOW + 5000
            val newer = SpaceId.generate()
            repository.create(newer, settings("Neu"), null)

            val names = repository.observeAll().first().map { it.settings.name }

            assertEquals(listOf("Neu", "Alt"), names)
        }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
