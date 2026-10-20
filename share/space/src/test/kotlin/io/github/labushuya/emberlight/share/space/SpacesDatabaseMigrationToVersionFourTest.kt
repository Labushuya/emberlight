package io.github.labushuya.emberlight.share.space

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.run.PauseEvent
import io.github.labushuya.emberlight.share.space.run.RunClock
import io.github.labushuya.emberlight.share.space.run.SpaceRunRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Migration 3 → 4 (Paket 3.3): Lebenszyklus-Spalten an `space` und die Tabelle `pause_event`. Medien, Teilnehmer und
 * Entwürfe aus Version 3 bleiben unberührt. Das Schema der Version 3 wird von Hand angelegt, wie es nach 3.2 auf dem
 * Gerät liegt. Room prüft das Schema nach der Migration selbst (fehlt eine Spalte, schlägt das Öffnen fehl).
 */
@RunWith(RobolectricTestRunner::class)
class SpacesDatabaseMigrationToVersionFourTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var database: SpacesDatabase? = null

    @After
    fun close() {
        database?.close()
    }

    @Test
    fun versionThreeDraftsSurviveAndGetEmptyLifecycleColumns() =
        runBlocking {
            val spacesDir = folder.newFolder("spaces")
            createVersionThreeDatabase(File(spacesDir, SpacesDatabase.FILE_NAME))

            val opened = open(spacesDir)

            val draft = opened.spaces().find(SPACE_ID)
            assertNotNull(draft)
            assertEquals("Entwurf", draft?.name)
            assertEquals("DRAFT", draft?.status)
            assertNull(draft?.startedAt)
            assertNull(draft?.expiresAt)
            assertNull(draft?.endedAt)
            assertEquals(1, opened.media().firstPage(SPACE_ID, 10).size)
        }

    @Test
    fun pauseEventsCanBeStoredAfterTheMigration() =
        runBlocking {
            val spacesDir = folder.newFolder("spaces")
            createVersionThreeDatabase(File(spacesDir, SpacesDatabase.FILE_NAME))
            val opened = open(spacesDir)
            SpaceRunRepository(opened, FixedClock).recordPause(SpaceId(SPACE_ID), PauseEvent(1_000L, 90_000L, 5_000L))

            val stored = opened.pauses().observeOf(SPACE_ID).first()
            assertEquals(1_000L, stored.single().startedAt)
            assertEquals(90_000L, stored.single().durationMs)
            assertEquals(false, stored.single().acknowledged)
            assertTrue(tableExists(opened, "pause_event"))
        }

    private fun open(spacesDir: File): SpacesDatabase =
        SpacesDatabase.open(ApplicationProvider.getApplicationContext<Context>(), spacesDir).also {
            database =
                it
        }

    private fun tableExists(
        db: SpacesDatabase,
        name: String,
    ): Boolean =
        db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$name'")
            .use { it.moveToFirst() }

    /** Schema der Version 3: Teilnehmer, Medien, Tabelle `space` ohne Lebenszyklus-Spalten, plus ein Entwurf. */
    private fun createVersionThreeDatabase(file: File) {
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `participant` (`id` TEXT NOT NULL, `display_name` TEXT NOT NULL, " +
                    "`role` TEXT NOT NULL, `token_hash` TEXT NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `media` (`id` TEXT NOT NULL, `space_id` TEXT NOT NULL, " +
                    "`sha256` TEXT NOT NULL, `mime` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, " +
                    "`original_name` TEXT NOT NULL, `uploader_id` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                    "`state` TEXT NOT NULL, `location_kept` INTEGER NOT NULL DEFAULT 0, " +
                    "`processing_version` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_media_space_id_sha256` ON `media` (`space_id`, `sha256`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_media_space_id_created_at_id` ON `media` " +
                    "(`space_id`, `created_at`, `id`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `space` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`color_index` INTEGER NOT NULL, `cover_sha256` TEXT, `lifetime_preset` TEXT, " +
                    "`lifetime_until` INTEGER, `retention_days` INTEGER NOT NULL, " +
                    "`storage_limit_bytes` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "INSERT INTO `space` (`id`, `name`, `color_index`, `cover_sha256`, `lifetime_preset`, " +
                    "`lifetime_until`, `retention_days`, `storage_limit_bytes`, `status`, `created_at`, " +
                    "`updated_at`) " +
                    "VALUES ('$SPACE_ID', 'Entwurf', 1, NULL, 'ONE_DAY', NULL, 7, 4294967296, 'DRAFT', 1000, 2000)",
            )
            db.execSQL(
                "INSERT INTO `media` (`id`, `space_id`, `sha256`, `mime`, `size_bytes`, `original_name`, " +
                    "`uploader_id`, `created_at`, `state`, `location_kept`, `processing_version`) " +
                    "VALUES ('vorhanden', '$SPACE_ID', '${"a".repeat(64)}', 'image/jpeg', 10, 'a.jpg', " +
                    "'teilnehmer', 1000, 'READY', 0, 1)",
            )
            db.version = 3
        }
    }

    private object FixedClock : RunClock {
        override fun wallMillis(): Long = 1_000L

        override fun elapsedMillis(): Long = 1_000L

        override fun uptimeMillis(): Long = 1_000L
    }

    private companion object {
        const val SPACE_ID = "AAAAAAAAAAAAAAAAAAAAAA"
    }
}
