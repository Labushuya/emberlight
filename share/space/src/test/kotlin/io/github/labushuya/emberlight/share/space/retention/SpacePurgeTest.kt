package io.github.labushuya.emberlight.share.space.retention

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.MediaEntity
import io.github.labushuya.emberlight.share.space.ParticipantEntity
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.management.SpaceCoverStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Lösch-Funktion für beendete Spaces (Paket 3.6b, ADR 0021): vollständige Entfernung, Nicht-Löschen laufender Spaces,
 * Abbruch mitten im Vorgang und Fortsetzen beim nächsten Start.
 */
@RunWith(RobolectricTestRunner::class)
class SpacePurgeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var spacesDir: File
    private lateinit var database: SpacesDatabase
    private lateinit var covers: SpaceCoverStore
    private val forgotten = mutableListOf<SpaceId>()
    private var failForget = false

    @Before
    fun setUp() {
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(ApplicationProvider.getApplicationContext<Context>(), spacesDir)
        covers = SpaceCoverStore(spacesDir)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun purger(): SpacePurge =
        SpacePurge(database, covers) { id ->
            if (failForget) throw IllegalStateException("Abbruch im Test")
            forgotten += id
        }

    @Test
    fun purgingAnEndedSpaceRemovesMediaParticipantsEntryAndStorage() {
        insertSpace(ID, "ENDED", purging = false)
        insertMedium(ID)
        insertParticipant(ID)
        File(spacesDir, ID).apply { mkdirs() }.resolve("abc").writeText("datei")

        val result = runBlocking { purger().purge(SpaceId(ID)) }

        assertEquals(PurgeResult.Purged, result)
        assertEquals("DELETED", runBlocking { database.spaces().find(ID) }?.status)
        assertTrue(runBlocking { database.media().allOf(ID) }.isEmpty())
        assertTrue(runBlocking { database.participants().activeOf(ID) }.isEmpty())
        assertFalse(File(spacesDir, ID).resolve("abc").exists())
        assertEquals(listOf(SpaceId(ID)), forgotten)
    }

    @Test
    fun anActiveSpaceIsNeverPurged() {
        insertSpace(ID, "ACTIVE", purging = false)
        File(spacesDir, ID).apply { mkdirs() }.resolve("abc").writeText("datei")

        val result = runBlocking { purger().purge(SpaceId(ID)) }

        assertEquals(PurgeResult.NotEnded, result)
        assertTrue(runBlocking { database.spaces().find(ID) } != null)
        assertTrue(File(spacesDir, ID).exists())
        assertTrue(forgotten.isEmpty())
    }

    @Test
    fun aMissingSpaceCountsAsPurged() {
        assertEquals(PurgeResult.Purged, runBlocking { purger().purge(SpaceId(ID)) })
    }

    @Test
    fun anAbortedPurgeLeavesTheMarkerAndTheNextRunFinishesIt() {
        insertSpace(ID, "ENDED", purging = false)
        insertMedium(ID)
        File(spacesDir, ID).apply { mkdirs() }.resolve("abc").writeText("datei")

        failForget = true
        try {
            runBlocking { purger().purge(SpaceId(ID)) }
            fail("Der Abbruch muss den Vorgang unterbrechen")
        } catch (expected: IllegalStateException) {
            // Erwartet: Die Löschung ist mitten im Vorgang abgebrochen.
        }
        // Der Space bleibt mit Merkmal stehen, nichts ist entfernt worden.
        assertEquals(true, runBlocking { database.spaces().find(ID) }?.purging)
        assertTrue(runBlocking { database.media().allOf(ID) }.isNotEmpty())

        failForget = false
        val resumed = runBlocking { purger().resumeAll() }

        assertEquals(1, resumed)
        assertEquals("DELETED", runBlocking { database.spaces().find(ID) }?.status)
        assertFalse(File(spacesDir, ID).resolve("abc").exists())
    }

    @Test
    fun aPurgeThatRunsTwiceDoesNotFail() {
        insertSpace(ID, "ENDED", purging = false)

        runBlocking {
            purger().purge(SpaceId(ID))
            assertEquals(PurgeResult.Purged, purger().purge(SpaceId(ID)))
        }
    }

    @Test
    fun resumeAllOnlyTouchesSpacesWithTheMarker() {
        insertSpace(ID, "ENDED", purging = true)
        insertSpace(OTHER_ID, "ENDED", purging = false)

        val resumed = runBlocking { purger().resumeAll() }

        assertEquals(1, resumed)
        assertEquals("DELETED", runBlocking { database.spaces().find(ID) }?.status)
        assertTrue(runBlocking { database.spaces().find(OTHER_ID) } != null)
    }

    private fun insertSpace(
        id: String,
        status: String,
        purging: Boolean,
    ) {
        runBlocking {
            database.spaces().upsert(
                SpaceEntity(
                    id = id,
                    name = "Test",
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

    private fun insertMedium(spaceId: String) {
        runBlocking {
            database.media().insert(
                MediaEntity(
                    id = MEDIUM_ID,
                    spaceId = spaceId,
                    sha256 = SHA256,
                    mime = "image/jpeg",
                    sizeBytes = 12L,
                    originalName = "bild.jpg",
                    uploaderId = UPLOADER_ID,
                    createdAt = 1_500L,
                    state = "READY",
                ),
            )
        }
    }

    private fun insertParticipant(spaceId: String) {
        runBlocking {
            database.participants().insert(
                ParticipantEntity(
                    id = UPLOADER_ID,
                    spaceId = spaceId,
                    displayName = "Gast",
                    nameKey = "gast",
                    role = "GUEST",
                    tokenHash = TOKEN_HASH,
                    createdAt = 1_000L,
                ),
            )
        }
    }

    @Test
    fun thePurgedSpaceKeepsItsCoverAndGetsItsDeletionTime() {
        insertSpace(ID, "ENDED", purging = false)
        File(spacesDir, ID).apply { mkdirs() }.resolve("cover-$SHA256").writeText("bild")
        File(spacesDir, ID).resolve("abc").writeText("datei")
        val timed = SpacePurge(database, covers, nowMillis = { 77_000L }) { forgotten += it }

        runBlocking { timed.purge(SpaceId(ID)) }

        val row = runBlocking { database.spaces().find(ID) }
        assertEquals("DELETED", row?.status)
        assertEquals(77_000L, row?.deletedAt)
        assertFalse(row?.purging ?: true)
        assertTrue(File(spacesDir, ID).resolve("cover-$SHA256").exists())
    }

    @Test
    fun removingADeletedSpaceDropsTheEntryAndItsCover() {
        insertSpace(ID, "ENDED", purging = false)
        File(spacesDir, ID).apply { mkdirs() }.resolve("cover-$SHA256").writeText("bild")
        runBlocking { purger().purge(SpaceId(ID)) }

        assertEquals(RemoveResult.Removed, runBlocking { purger().remove(SpaceId(ID)) })
        assertNull(runBlocking { database.spaces().find(ID) })
        assertFalse(File(spacesDir, ID).exists())
    }

    @Test
    fun removingAnEndedSpaceFromTheListIsRefused() {
        insertSpace(ID, "ENDED", purging = false)

        assertEquals(RemoveResult.NotDeleted, runBlocking { purger().remove(SpaceId(ID)) })
        assertEquals("ENDED", runBlocking { database.spaces().find(ID) }?.status)
    }

    @Test
    fun removingAMissingSpaceCountsAsRemoved() {
        assertEquals(RemoveResult.Removed, runBlocking { purger().remove(SpaceId(ID)) })
    }

    private companion object {
        const val ID = "AAAAAAAAAAAAAAAAAAAAAA"
        const val OTHER_ID = "BBBBBBBBBBBBBBBBBBBBBB"
        const val MEDIUM_ID = "CCCCCCCCCCCCCCCCCCCCCC"
        const val UPLOADER_ID = "DDDDDDDDDDDDDDDDDDDDDD"
        const val SHA256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val TOKEN_HASH = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
