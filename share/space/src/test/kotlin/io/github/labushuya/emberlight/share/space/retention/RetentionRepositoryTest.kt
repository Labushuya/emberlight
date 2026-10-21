package io.github.labushuya.emberlight.share.space.retention

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.management.SpaceCoverStore
import kotlinx.coroutines.runBlocking
import org.junit.After
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

/** Friständerung, Verlängern um einen Tag und Erinnerungsmerkmal in der Datenbank (Paket 3.6b, ADR 0021). */
@RunWith(RobolectricTestRunner::class)
class RetentionRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var spacesDir: File
    private lateinit var database: SpacesDatabase
    private lateinit var repository: RetentionRepository

    private val day = RetentionRules.DAY_MILLIS
    private val endedAt = 1_000_000_000_000L

    @Before
    fun setUp() {
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(ApplicationProvider.getApplicationContext<Context>(), spacesDir)
        val covers = SpaceCoverStore(spacesDir)
        val purge = SpacePurge(database, covers) {}
        repository = RetentionRepository(database, purge)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun changingTheDeadlineStoresItCountedFromNow() {
        insertEnded(ID, retentionDays = 1)
        val now = endedAt + 2 * day

        val result = runBlocking { repository.changeUntil(SpaceId(ID), days = 7, now = now) }

        assertEquals(RetentionChange.Changed(now + 7 * day), result)
        assertEquals(now + 7 * day, runBlocking { database.spaces().find(ID) }?.retentionUntil)
    }

    @Test
    fun changingToSoforStoresTheNowAsDeadline() {
        insertEnded(ID, retentionDays = 7)

        val result = runBlocking { repository.changeUntil(SpaceId(ID), days = 0, now = endedAt + day) }

        assertEquals(RetentionChange.Changed(endedAt + day), result)
    }

    @Test
    fun aChangeClearsTheSentReminderSoItIsPlannedAgain() {
        insertEnded(ID, retentionDays = 7)
        runBlocking { repository.markReminded(SpaceId(ID), endedAt + 6 * day) }

        runBlocking { repository.changeUntil(SpaceId(ID), days = 3, now = endedAt + day) }

        assertNull(runBlocking { database.spaces().find(ID) }?.retentionRemindedFor)
    }

    @Test
    fun daysOutsideTheRangeAreRefusedWithoutWriting() {
        insertEnded(ID, retentionDays = 1)

        assertEquals(RetentionChange.InvalidDays, runBlocking { repository.changeUntil(SpaceId(ID), days = 91, now = endedAt) })
        assertEquals(RetentionChange.InvalidDays, runBlocking { repository.changeUntil(SpaceId(ID), days = -1, now = endedAt) })
        assertNull(runBlocking { database.spaces().find(ID) }?.retentionUntil)
    }

    @Test
    fun onlyAnEndedSpaceCanChangeItsDeadline() {
        insertSpace(ID, status = "ACTIVE")

        assertEquals(
            RetentionChange.NotChangeable,
            runBlocking { repository.changeUntil(SpaceId(ID), days = 3, now = endedAt) },
        )
        assertEquals(RetentionChange.NotFound, runBlocking { repository.changeUntil(SpaceId(OTHER_ID), days = 3, now = endedAt) })
    }

    @Test
    fun aSpaceBeingDeletedCannotChangeItsDeadline() {
        insertEnded(ID, retentionDays = 1, purging = true)

        assertEquals(
            RetentionChange.NotChangeable,
            runBlocking { repository.changeUntil(SpaceId(ID), days = 3, now = endedAt) },
        )
    }

    @Test
    fun extendingByOneDayAddsOneDayToTheCurrentDeadline() {
        insertEnded(ID, retentionDays = 1)

        val result = runBlocking { repository.extendByOneDay(SpaceId(ID), now = endedAt + 2 * 3_600_000L) }

        // Die Frist (Ende plus ein Tag) liegt noch in der Zukunft, also gilt sie plus einen Tag.
        assertEquals(RetentionChange.Changed(endedAt + 2 * day), result)
        assertTrue(runBlocking { database.spaces().find(ID) }?.retentionUntil == endedAt + 2 * day)
    }

    @Test
    fun thePlanReportsADueReminderForAnEndedSpace() {
        insertEnded(ID, retentionDays = 1)

        val now = endedAt + 13 * 3_600_000L
        val plan = runBlocking { repository.plan(now) }

        assertEquals(listOf(SpaceId(ID)), plan.due.map { it.spaceId })
    }

    @Test
    fun aMarkedReminderIsNoLongerDue() {
        insertEnded(ID, retentionDays = 1)
        val plannedAt = endedAt + 12 * 3_600_000L
        runBlocking { repository.markReminded(SpaceId(ID), plannedAt) }

        val plan = runBlocking { repository.plan(endedAt + 13 * 3_600_000L) }

        assertTrue(plan.due.isEmpty())
    }

    @Test
    fun purgeAndResumeAreDelegated() {
        insertEnded(ID, retentionDays = 1)

        assertEquals(0, runBlocking { repository.resumePurges() })
        assertEquals(io.github.labushuya.emberlight.share.space.retention.PurgeResult.Purged, runBlocking { repository.purge(SpaceId(ID)) })
        assertNull(runBlocking { database.spaces().find(ID) })
    }

    private fun insertEnded(
        id: String,
        retentionDays: Int,
        purging: Boolean = false,
    ) = insertSpace(id, status = "ENDED", retentionDays = retentionDays, purging = purging)

    private fun insertSpace(
        id: String,
        status: String,
        retentionDays: Int = 1,
        purging: Boolean = false,
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
                    retentionDays = retentionDays,
                    storageLimitBytes = 4L * 1024 * 1024 * 1024,
                    status = status,
                    createdAt = 1_000L,
                    updatedAt = 2_000L,
                    endedAt = if (status == "ENDED") endedAt else null,
                    purging = purging,
                ),
            )
        }
    }

    private companion object {
        const val ID = "AAAAAAAAAAAAAAAAAAAAAA"
        const val OTHER_ID = "BBBBBBBBBBBBBBBBBBBBBB"
    }
}
