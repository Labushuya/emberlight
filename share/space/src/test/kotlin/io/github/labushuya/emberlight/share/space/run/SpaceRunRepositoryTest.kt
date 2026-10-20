package io.github.labushuya.emberlight.share.space.run

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
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
import java.io.File

/** Übergänge des Betriebs in der echten Datenbank (Paket 3.3): Start, Ende, Abbruch, Pausen und Abschluss ohne Dienst. */
@RunWith(RobolectricTestRunner::class)
class SpaceRunRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: SpacesDatabase
    private lateinit var clock: MutableClock
    private lateinit var runs: SpaceRunRepository
    private val gib = 1024L * 1024L * 1024L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = SpacesDatabase.open(context, folder.newFolder("spaces"))
        clock = MutableClock(START)
        runs = SpaceRunRepository(database, clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun aDraftStartsAndGetsStartAndExpiry() =
        runBlocking {
            draft(FIRST)

            val result = runs.start(FIRST, freeBytes = 100 * gib)

            val started = (result as StartResult.Started).record
            assertEquals(SpaceStatus.ACTIVE, started.status)
            assertEquals(START, started.startedAt)
            assertEquals(START + 24 * HOUR, started.expiresAt)
            assertEquals(started.id, runs.findActive()?.id)
        }

    @Test
    fun onlyOneSpaceIsActiveAtATime() =
        runBlocking {
            draft(FIRST)
            draft(SECOND)
            runs.start(FIRST, freeBytes = 100 * gib)

            val second = runs.start(SECOND, freeBytes = 100 * gib)

            assertEquals(StartResult.Refused(StartProblem.ANOTHER_ACTIVE), second)
            assertEquals(SpaceStatus.DRAFT, database.spaces().find(SECOND.value)?.toStatus())
        }

    @Test
    fun aStartNeedsTheLimitToFitTheFreeSpace() =
        runBlocking {
            draft(FIRST, limit = 8 * gib)

            val result = runs.start(FIRST, freeBytes = 9 * gib)

            assertEquals(StartResult.Refused(StartProblem.STORAGE_ABOVE_FREE), result)
            assertNull(runs.findActive())
        }

    @Test
    fun aFixedEndInThePastIsRefusedAndNothingChanges() =
        runBlocking {
            draft(FIRST, until = START - 1)

            assertEquals(StartResult.Refused(StartProblem.END_IN_PAST), runs.start(FIRST, freeBytes = 100 * gib))
            assertEquals(SpaceStatus.DRAFT, database.spaces().find(FIRST.value)?.toStatus())
        }

    @Test
    fun unknownAndEndedSpacesAreRefused() =
        runBlocking {
            assertEquals(StartResult.Refused(StartProblem.NOT_FOUND), runs.start(FIRST, freeBytes = 100 * gib))
            draft(FIRST)
            runs.start(FIRST, freeBytes = 100 * gib)
            runs.end(FIRST, START + 1)

            assertEquals(StartResult.Refused(StartProblem.NOT_A_DRAFT), runs.start(FIRST, freeBytes = 100 * gib))
        }

    @Test
    fun endingSetsEndedAtOnceAndOnlyForAnActiveSpace() =
        runBlocking {
            draft(FIRST)
            runs.start(FIRST, freeBytes = 100 * gib)

            assertTrue(runs.end(FIRST, START + 5_000))
            assertFalse(runs.end(FIRST, START + 6_000))

            val row = database.spaces().find(FIRST.value)
            assertEquals("ENDED", row?.status)
            assertEquals(START + 5_000, row?.endedAt)
            assertNull(runs.findActive())
        }

    @Test
    fun aFailedStartReturnsTheSpaceToDraft() =
        runBlocking {
            draft(FIRST)
            runs.start(FIRST, freeBytes = 100 * gib)

            assertTrue(runs.abortStart(FIRST))

            val row = database.spaces().find(FIRST.value)
            assertEquals("DRAFT", row?.status)
            assertNull(row?.startedAt)
            assertNull(row?.expiresAt)
        }

    @Test
    fun pausesAreListedNewestFirstAndAcknowledgedAsAGroup() =
        runBlocking {
            draft(FIRST)
            runs.start(FIRST, freeBytes = 100 * gib)
            runs.recordPause(FIRST, PauseEvent(START + 1_000, 600_000L, 500_000L))
            runs.recordPause(FIRST, PauseEvent(START + 900_000, 120_000L, 0L))

            val before = runs.observePauses(FIRST).first()
            assertEquals(listOf(START + 900_000, START + 1_000), before.map { it.startedAtMillis })
            assertFalse(
                database
                    .pauses()
                    .observeOf(FIRST.value)
                    .first()
                    .any { it.acknowledged },
            )

            assertEquals(2, runs.acknowledgePauses(FIRST))
            assertTrue(
                database
                    .pauses()
                    .observeOf(FIRST.value)
                    .first()
                    .all { it.acknowledged },
            )
        }

    @Test
    fun anExpiredActiveSpaceIsClosedAtItsEndAndNotAtTheRecognitionTime() =
        runBlocking {
            draft(FIRST, lifetimeMillis = HOUR)
            runs.start(FIRST, freeBytes = 100 * gib)

            // Der Prozess war zwei Stunden weg. Das Ende war nach einer Stunde.
            clock.wall = START + 2 * HOUR
            val closed = runs.closeWithoutService()

            assertEquals(1, closed.size)
            assertEquals(START + HOUR, closed.single().endedAt)
            assertEquals(SpaceStatus.ENDED, closed.single().status)
            assertNull(runs.findActive())
        }

    @Test
    fun aSpaceWithTimeLeftIsClosedAtTheMomentItIsRecognised() =
        runBlocking {
            draft(FIRST)
            runs.start(FIRST, freeBytes = 100 * gib)
            clock.wall = START + 60_000

            val closed = runs.closeWithoutService()

            assertEquals(START + 60_000, closed.single().endedAt)
        }

    private suspend fun draft(
        id: SpaceId,
        limit: Long = gib,
        until: Long? = null,
        lifetimeMillis: Long? = null,
    ) {
        // Eine Dauer wird über den Preset-Namen abgebildet. Ein Test mit eigener Dauer ist über ein festes Ende möglich.
        database.spaces().upsert(
            SpaceEntity(
                id = id.value,
                name = "Test ${id.value.take(4)}",
                colorIndex = 0,
                coverSha256 = null,
                lifetimePreset = if (until == null && lifetimeMillis == null) "ONE_DAY" else null,
                lifetimeUntil = until ?: lifetimeMillis?.let { START + it },
                retentionDays = 7,
                storageLimitBytes = limit,
                status = SpaceStatus.DRAFT.name,
                createdAt = START,
                updatedAt = START,
            ),
        )
    }

    private fun io.github.labushuya.emberlight.share.space.SpaceEntity.toStatus(): SpaceStatus =
        SpaceStatus.valueOf(status)

    private class MutableClock(
        var wall: Long,
    ) : RunClock {
        override fun wallMillis(): Long = wall

        override fun elapsedMillis(): Long = wall

        override fun uptimeMillis(): Long = wall
    }

    private companion object {
        const val START = 1_800_000_000_000L
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        val FIRST = SpaceId("AAAAAAAAAAAAAAAAAAAAAA")
        val SECOND = SpaceId("BBBBBBBBBBBBBBBBBBBBBB")
    }
}
