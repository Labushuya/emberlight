package io.github.labushuya.emberlight.feature.space.retention

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.management.SpaceCoverStore
import io.github.labushuya.emberlight.share.space.retention.PurgeResult
import io.github.labushuya.emberlight.share.space.retention.RemoveResult
import io.github.labushuya.emberlight.share.space.retention.RetentionChange
import io.github.labushuya.emberlight.share.space.retention.RetentionRepository
import io.github.labushuya.emberlight.share.space.retention.RetentionRules
import io.github.labushuya.emberlight.share.space.retention.SpacePurge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Erinnerungen zur Aufbewahrung (Paket 3.6b, ADR 0021) mit festen Zeitwerten: fällige Erinnerung, Nachholen nach einem
 * Neustart ohne Doppelte, fehlende Berechtigung, Wecker zum nächsten Zeitpunkt, Aktionen ohne direktes Löschen.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33])
class RetentionRemindersTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val day = RetentionRules.DAY_MILLIS
    private val hour = RetentionRules.HOUR_MILLIS
    private lateinit var database: SpacesDatabase
    private lateinit var repository: RetentionRepository
    private lateinit var reminders: RetentionReminders
    private lateinit var spacesDir: File

    /** Ende vor 13 Stunden, eine Frist von einem Tag: die Erinnerung liegt bei Ende plus 12 Stunden und ist fällig. */
    private val endedAt = 1_000_000_000_000L
    private val now = endedAt + 13 * hour

    @Before
    fun setUp() {
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(context, spacesDir)
        repository = RetentionRepository(database, SpacePurge(database, SpaceCoverStore(spacesDir)) {})
        reminders = RetentionReminders(context, repository, RetentionNotifier(context))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun aDueReminderIsShownOnceAndNotRepeatedAfterARestart() {
        grantNotifications()
        insertEnded(ID, retentionDays = 1)

        runBlocking { reminders.checkNow(now) }
        assertEquals(1, shownNotifications())
        assertEquals(endedAt + 12 * hour, runBlocking { database.spaces().find(ID) }?.retentionRemindedFor)

        // Eine erneute Prüfung: die Erinnerung ist schon gesendet, es kommt keine zweite.
        runBlocking { reminders.checkNow(now) }
        assertEquals(1, shownNotifications())
    }

    @Test
    fun withoutPermissionTheReminderStaysOpenForTheNextCheck() {
        insertEnded(ID, retentionDays = 1)

        runBlocking { reminders.checkNow(now) }

        assertNull(runBlocking { database.spaces().find(ID) }?.retentionRemindedFor)
        assertEquals(0, shownNotifications())
    }

    // Robolectric markiert den Shadow des Weckers als veraltet. Er ist die einzige Sicht auf den geplanten Zeitpunkt.
    @Suppress("DEPRECATION")
    @Test
    fun theNextWakeUpIsSetToTheNextPlannedReminder() {
        insertEnded(ID, retentionDays = 7)

        runBlocking { reminders.checkNow(endedAt + day) }

        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java)).nextScheduledAlarm
        assertEquals(endedAt + 6 * day, alarm?.triggerAtTime)
    }

    @Test
    fun extendingByOneDayKeepsTheSpaceAndPlansTheReminderAgain() {
        insertEnded(ID, retentionDays = 1)

        runBlocking { reminders.extendByOneDay(SpaceId.parse(ID)!!, now) }

        val row = runBlocking { database.spaces().find(ID) }
        assertEquals(endedAt + day + day, row?.retentionUntil)
        assertNull(row?.retentionRemindedFor)
    }

    @Test
    fun changingTheDeadlineStoresItFromNow() {
        insertEnded(ID, retentionDays = 1)

        val result = runBlocking { reminders.changeRetention(SpaceId.parse(ID)!!, days = 3, now = now) }

        assertEquals(RetentionChange.Changed(now + 3 * day), result)
        assertEquals(now + 3 * day, runBlocking { database.spaces().find(ID) }?.retentionUntil)
    }

    @Test
    fun purgingRemovesTheSpaceAndItsOpenReminder() {
        insertEnded(ID, retentionDays = 1)

        val result = runBlocking { reminders.purge(SpaceId.parse(ID)!!) }

        assertEquals(PurgeResult.Purged, result)
        assertEquals("DELETED", runBlocking { database.spaces().find(ID) }?.status)
    }

    @Test
    fun aSpaceWhoseKeepingHasRunOutIsDeletedAutomaticallyAtTheCheck() {
        grantNotifications()
        insertEnded(ID, retentionDays = 1)

        // Die Frist ist um. Die Löschung kommt vor der Erinnerung, für den gelöschten Space geht keine mehr.
        runBlocking { reminders.checkNow(endedAt + day) }

        assertEquals("DELETED", runBlocking { database.spaces().find(ID) }?.status)
        assertEquals(0, shownNotifications())
    }

    @Test
    fun theStartupDeletesWhatExpiredWhileTheAppWasNotRunning() {
        insertEnded(ID, retentionDays = 1)

        runBlocking { reminders.startupCatchUp() }

        assertEquals("DELETED", runBlocking { database.spaces().find(ID) }?.status)
    }

    @Test
    fun onlyDeletedSpacesCanBeRemovedFromTheList() {
        insertEnded(ID, retentionDays = 1)
        assertEquals(RemoveResult.NotDeleted, runBlocking { reminders.removeFromList(SpaceId.parse(ID)!!) })

        runBlocking { reminders.purge(SpaceId.parse(ID)!!) }

        assertEquals(RemoveResult.Removed, runBlocking { reminders.removeFromList(SpaceId.parse(ID)!!) })
        assertNull(runBlocking { database.spaces().find(ID) })
    }

    @Suppress("DEPRECATION")
    @Test
    fun noFurtherReminderMeansNoAlarm() {
        insertEnded(ID, retentionDays = 1)
        runBlocking { reminders.checkNow(endedAt + 20 * day) }

        // Der Space ist nach der Frist gelöscht. Es gibt keinen weiteren Termin, also keinen Wecker.
        assertNull(shadowOf(context.getSystemService(AlarmManager::class.java)).nextScheduledAlarm)
    }

    private fun shownNotifications(): Int =
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).allNotifications.size

    private fun grantNotifications() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun insertEnded(
        id: String,
        retentionDays: Int,
    ) {
        runBlocking {
            database.spaces().upsert(
                SpaceEntity(
                    id = id,
                    name = "Sommerfest",
                    colorIndex = 1,
                    coverSha256 = null,
                    lifetimePreset = "ONE_DAY",
                    lifetimeUntil = null,
                    retentionDays = retentionDays,
                    storageLimitBytes = 4L * 1024 * 1024 * 1024,
                    status = "ENDED",
                    createdAt = 1_000L,
                    updatedAt = 2_000L,
                    endedAt = endedAt,
                ),
            )
        }
    }

    private companion object {
        const val ID = "AAAAAAAAAAAAAAAAAAAAAA"
    }
}
