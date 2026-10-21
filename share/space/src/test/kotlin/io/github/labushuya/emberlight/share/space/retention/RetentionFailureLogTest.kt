package io.github.labushuya.emberlight.share.space.retention

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.SpaceEntity
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.management.SpaceCoverStore
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
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * Protokoll fehlgeschlagener automatischer Löschungen (Paket 3.6d, ADR 0021). Unter `EmberlightRetention` steht die
 * Space-Kennung, die Art des Fehlers und der Versuch, aber weder Fehlermeldung noch Namen oder Pfade.
 */
@RunWith(RobolectricTestRunner::class)
class RetentionFailureLogTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var spacesDir: File
    private lateinit var database: SpacesDatabase

    @Before
    fun setUp() {
        spacesDir = folder.newFolder("spaces")
        database = SpacesDatabase.open(ApplicationProvider.getApplicationContext<Context>(), spacesDir)
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun aFailingPurgeIsLoggedPerAttemptWithoutItsMessage() {
        insertEnded()
        val failing =
            SpacePurge(database, SpaceCoverStore(spacesDir)) {
                throw IllegalStateException("/storage/emulated/0/Pictures/Urlaub/Geheim.jpg")
            }
        val repository = RetentionRepository(database, failing)

        runBlocking { repository.purgeDue(now = ENDED_AT) }
        runBlocking { repository.purgeDue(now = ENDED_AT) }

        val logs = ShadowLog.getLogs().filter { it.tag == RetentionLog.TAG }
        assertEquals(2, logs.size)
        assertTrue(logs.all { it.type == Log.WARN })
        assertTrue(logs[0].msg, logs[0].msg.contains("space=$ID") && logs[0].msg.contains("Versuch 1"))
        assertTrue(logs[1].msg, logs[1].msg.contains("Versuch 2"))
        assertTrue(logs.all { it.msg.contains("IllegalStateException") })
        assertTrue("Die Fehlermeldung darf nicht protokolliert werden", logs.none { it.msg.contains("Geheim") })
    }

    @Test
    fun aSuccessfulPurgeAfterFailuresStartsCountingAgain() {
        insertEnded()
        var fail = true
        val flaky =
            SpacePurge(database, SpaceCoverStore(spacesDir)) {
                if (fail) throw IllegalStateException("erster Versuch")
            }
        val repository = RetentionRepository(database, flaky)
        runBlocking { repository.purgeDue(now = ENDED_AT) }
        fail = false
        runBlocking { repository.purgeDue(now = ENDED_AT) }

        assertEquals("DELETED", runBlocking { database.spaces().find(ID) }?.status)
        // Ein neuer Fehler nach dem Erfolg beginnt wieder bei Versuch 1. Der Space ist dann schon gelöscht,
        // daher prüft der Test nur den Zähler über einen zweiten, neu angelegten Space.
        insertEndedWithId(OTHER_ID)
        fail = true
        runBlocking { repository.purgeDue(now = ENDED_AT) }

        val messages = ShadowLog.getLogs().filter { it.tag == RetentionLog.TAG }.map { it.msg }
        assertEquals(2, messages.size)
        assertTrue(messages.last().contains("space=$OTHER_ID") && messages.last().contains("Versuch 1"))
    }

    private fun insertEnded() = insertEndedWithId(ID)

    private fun insertEndedWithId(id: String) {
        runBlocking {
            database.spaces().upsert(
                SpaceEntity(
                    id = id,
                    name = "Test",
                    colorIndex = 1,
                    coverSha256 = null,
                    lifetimePreset = "ONE_DAY",
                    lifetimeUntil = null,
                    retentionDays = 0,
                    storageLimitBytes = 4L * 1024 * 1024 * 1024,
                    status = "ENDED",
                    createdAt = 1_000L,
                    updatedAt = 2_000L,
                    endedAt = ENDED_AT,
                ),
            )
        }
    }

    private companion object {
        const val ID = "AAAAAAAAAAAAAAAAAAAAAA"
        const val OTHER_ID = "BBBBBBBBBBBBBBBBBBBBBB"
        const val ENDED_AT = 2_000L
    }
}
