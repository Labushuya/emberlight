package io.github.labushuya.emberlight.share.space.retention

import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.management.Lifetime
import io.github.labushuya.emberlight.share.space.management.LifetimePreset
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceSettings
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Planung der Erinnerungen über feste Zustände (Paket 3.6b): kurze Fristen, „sofort“, keine Doppelten, Löschung läuft. */
class RetentionReminderPlannerTest {
    private val day = RetentionRules.DAY_MILLIS
    private val hour = RetentionRules.HOUR_MILLIS
    private val ended = 1_000_000_000_000L
    private val expiresAt = ended - 30 * RetentionRules.MINUTE_MILLIS

    @Test
    fun anEndedSpaceWithAWeekPlansTheReminderOneDayBeforeTheDeadline() {
        val space = record(status = SpaceStatus.ENDED, retentionDays = 7, endedAt = ended)

        val reminder = RetentionReminderPlanner.reminderOf(space)

        assertEquals(RetentionReminderKind.AFTER_END, reminder?.kind)
        assertEquals(ended + 6 * day, reminder?.plannedAt)
        assertEquals(ended + 7 * day, reminder?.untilMillis)
    }

    @Test
    fun aChangedDeadlineMovesTheReminder() {
        val space =
            record(
                status = SpaceStatus.ENDED,
                retentionDays = 7,
                endedAt = ended,
                retentionUntil =
                    ended + 3 * day,
            )

        assertEquals(ended + 2 * day, RetentionReminderPlanner.reminderOf(space)?.plannedAt)
    }

    @Test
    fun aSoforSpaceRemindsWithTheWarningBeforeTheEnd() {
        val space = record(status = SpaceStatus.ACTIVE, retentionDays = 0, expiresAt = expiresAt)

        val reminder = RetentionReminderPlanner.reminderOf(space)

        assertEquals(RetentionReminderKind.SOFORT, reminder?.kind)
        assertEquals(expiresAt - 10 * RetentionRules.MINUTE_MILLIS, reminder?.plannedAt)
    }

    @Test
    fun anActiveSpaceWithARetentionPlansNothingYet() {
        assertNull(
            RetentionReminderPlanner.reminderOf(
                record(status = SpaceStatus.ACTIVE, retentionDays = 7, expiresAt = expiresAt),
            ),
        )
    }

    @Test
    fun draftsAndSpacesBeingDeletedGetNoReminder() {
        assertNull(RetentionReminderPlanner.reminderOf(record(status = SpaceStatus.DRAFT, retentionDays = 1)))
        assertNull(
            RetentionReminderPlanner.reminderOf(
                record(status = SpaceStatus.ENDED, retentionDays = 1, endedAt = ended, purging = true),
            ),
        )
    }

    @Test
    fun aDueReminderIsReturnedAndTheNextCheckIsTheEarliestLaterOne() {
        val later =
            record(id = "BBBBBBBBBBBBBBBBBBBBBB", status = SpaceStatus.ENDED, retentionDays = 7, endedAt = ended)
        val due =
            record(
                id = "AAAAAAAAAAAAAAAAAAAAAA",
                status = SpaceStatus.ENDED,
                retentionDays = 3,
                endedAt =
                    ended - 2 * day,
            )
        val now = ended + 2 * hour

        val plan = RetentionReminderPlanner.plan(listOf(later, due), now)

        // Die Erinnerung des Drei-Tage-Space liegt bei ended (Frist minus 24 h), also in der Vergangenheit: fällig.
        // Die nächste Prüfung ist die Löschung dieses Space bei ended + 1 Tag (Ende vor zwei Tagen, Frist drei Tage).
        assertEquals(listOf(due.id), plan.due.map { it.spaceId })
        assertEquals(ended + day, plan.nextCheckAt)
    }

    @Test
    fun aReminderAlreadySentForItsPlannedTimeIsNotDueAgain() {
        val planned = ended + 12 * hour
        val space =
            record(status = SpaceStatus.ENDED, retentionDays = 1, endedAt = ended, retentionRemindedFor = planned)

        val plan = RetentionReminderPlanner.plan(listOf(space), now = planned + hour)

        assertTrue(plan.due.isEmpty())
        assertEquals(ended + day, plan.nextCheckAt)
    }

    @Test
    fun aMissedReminderIsDueAtTheFirstCheckAfterARestart() {
        val space = record(status = SpaceStatus.ENDED, retentionDays = 7, endedAt = ended)

        val plan = RetentionReminderPlanner.plan(listOf(space), now = ended + 6 * day + hour)

        assertEquals(1, plan.due.size)
        assertTrue(plan.purgeDue.isEmpty())
    }

    @Test
    fun aSpaceWhoseKeepingHasRunOutIsPurgedAndNotReminded() {
        val expired = record(status = SpaceStatus.ENDED, retentionDays = 1, endedAt = ended)

        val plan = RetentionReminderPlanner.plan(listOf(expired), now = ended + day)

        assertEquals(listOf(expired.id), plan.purgeDue)
        assertTrue(plan.due.isEmpty())
    }

    @Test
    fun aSpaceThatIsStillKeptIsNotPurgedYetAndTheNextCheckIsItsEnd() {
        val kept = record(status = SpaceStatus.ENDED, retentionDays = 3, endedAt = ended)

        val plan = RetentionReminderPlanner.plan(listOf(kept), now = ended + day)

        assertTrue(plan.purgeDue.isEmpty())
        assertEquals(ended + 2 * day, plan.nextCheckAt)
    }

    @Test
    fun aSpaceBeingDeletedOrDeletedIsNeverPurgedAgain() {
        val running = record(status = SpaceStatus.ENDED, retentionDays = 1, endedAt = ended, purging = true)
        val deleted = record(status = SpaceStatus.DELETED, retentionDays = 1, endedAt = ended)

        val plan = RetentionReminderPlanner.plan(listOf(running, deleted), now = ended + 5 * day)

        assertTrue(plan.purgeDue.isEmpty())
        assertTrue(plan.due.isEmpty())
    }

    @Test
    fun immediateDeletionIsPurgedAtTheEnd() {
        val immediate = record(status = SpaceStatus.ENDED, retentionDays = 0, endedAt = ended)

        assertEquals(listOf(immediate.id), RetentionReminderPlanner.plan(listOf(immediate), now = ended).purgeDue)
    }

    private fun record(
        id: String = "AAAAAAAAAAAAAAAAAAAAAA",
        status: SpaceStatus,
        retentionDays: Int,
        endedAt: Long? = null,
        expiresAt: Long? = null,
        retentionUntil: Long? = null,
        retentionRemindedFor: Long? = null,
        purging: Boolean = false,
    ): SpaceRecord =
        SpaceRecord(
            id = SpaceId(id),
            settings =
                SpaceSettings(
                    name = "Test",
                    colorIndex = 1,
                    lifetime = Lifetime.Preset(LifetimePreset.ONE_DAY),
                    retentionDays = retentionDays,
                    storageLimitBytes = 4L * 1024 * 1024 * 1024,
                ),
            coverSha256 = null,
            status = status,
            createdAt = 0L,
            updatedAt = 0L,
            expiresAt = expiresAt,
            endedAt = endedAt,
            retentionUntil = retentionUntil,
            retentionRemindedFor = retentionRemindedFor,
            purging = purging,
        )
}
