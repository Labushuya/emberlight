package io.github.labushuya.emberlight.share.space

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Migrationskette 8 → 10: Hotfix 8 → 9 (Fehlergründe der Vorschau an `media`) und Paket 3.6c, 9 → 10 (Zeitpunkt der
 * Löschung an `space`). Die Spalten sind neutral (NULL), bestehende Spaces und Medien bleiben unverändert. Das Schema der
 * Version 8 wird von Hand angelegt, wie es nach 3.6b auf dem Gerät liegt.
 */
@RunWith(RobolectricTestRunner::class)
class SpacesDatabaseMigrationToVersionTenTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var database: SpacesDatabase? = null

    @After
    fun close() {
        database?.close()
    }

    @Test
    fun theDeletionTimeColumnIsAddedWithNullAndTheSpaceSurvives() {
        val spacesDir = folder.newFolder("spaces")
        createVersionEightDatabase(File(spacesDir, SpacesDatabase.FILE_NAME))

        val opened = open(spacesDir)

        val columns = columnsOf(opened, "space")
        listOf("deleted_at", "retention_until", "purging").forEach {
            assertTrue("Spalte $it fehlt", columns.contains(it))
        }
        val space = runBlocking { opened.spaces().find(ENDED_ID) }
        assertEquals("ENDED", space?.status)
        assertEquals(ENDED_AT, space?.endedAt)
        assertEquals(1, space?.retentionDays)
        assertNull(space?.retentionUntil)
        assertNull(space?.retentionRemindedFor)
        assertFalse(space?.purging ?: true)
        assertNull(space?.deletedAt)
        val mediaColumns = columnsOf(opened, "media")
        assertTrue("Spalte preview_reason fehlt", mediaColumns.contains("preview_reason"))
        assertTrue("Spalte view_reason fehlt", mediaColumns.contains("view_reason"))
    }

    @Test
    fun theExistingMediaSurvivesTheMigration() {
        val spacesDir = folder.newFolder("spaces")
        createVersionEightDatabase(File(spacesDir, SpacesDatabase.FILE_NAME))

        val opened = open(spacesDir)

        assertEquals(1, runBlocking { opened.media().allOf(ENDED_ID).size })
    }

    private fun open(spacesDir: File): SpacesDatabase =
        SpacesDatabase.open(ApplicationProvider.getApplicationContext<Context>(), spacesDir).also {
            database = it
        }

    private fun columnsOf(
        db: SpacesDatabase,
        table: String,
    ): List<String> =
        db.openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }.toList()
        }

    /** Schema der Version 8 (nach 3.6b) mit einem beendeten Space und einem Medium darin. */
    private fun createVersionEightDatabase(file: File) {
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `participant` (`id` TEXT NOT NULL, `display_name` TEXT NOT NULL, " +
                    "`role` TEXT NOT NULL, `token_hash` TEXT NOT NULL, `space_id` TEXT NOT NULL DEFAULT '', " +
                    "`name_key` TEXT NOT NULL DEFAULT '', `created_at` INTEGER NOT NULL DEFAULT 0, " +
                    "`revoked_at` INTEGER, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_participant_space_id_name_key` " +
                    "ON `participant` (`space_id`, `name_key`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `media` (`id` TEXT NOT NULL, `space_id` TEXT NOT NULL, " +
                    "`sha256` TEXT NOT NULL, `mime` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, " +
                    "`original_name` TEXT NOT NULL, `uploader_id` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                    "`state` TEXT NOT NULL, `location_kept` INTEGER NOT NULL DEFAULT 0, " +
                    "`processing_version` INTEGER NOT NULL DEFAULT 0, `preview_state` TEXT, `view_state` TEXT, " +
                    "`video_codec` TEXT, `preview_bytes` INTEGER NOT NULL DEFAULT 0, " +
                    "`view_bytes` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
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
                    "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `started_at` INTEGER, " +
                    "`expires_at` INTEGER, `ended_at` INTEGER, `invite_hash` TEXT, `invite_created_at` INTEGER, " +
                    "`invite_rotated_at` INTEGER, `port` INTEGER, `invite_ciphertext` BLOB, `invite_iv` BLOB, " +
                    "`retention_until` INTEGER, `retention_reminded_for` INTEGER, " +
                    "`purging` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `pause_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`space_id` TEXT NOT NULL, `started_at` INTEGER NOT NULL, " +
                    "`duration_ms` INTEGER NOT NULL, `deep_sleep_ms` INTEGER NOT NULL, " +
                    "`acknowledged` INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_pause_event_space_id_started_at` ON `pause_event` (`space_id`, `started_at`)",
            )
            db.execSQL(
                "INSERT INTO `space` (`id`, `name`, `color_index`, `lifetime_preset`, `retention_days`, " +
                    "`storage_limit_bytes`, `status`, `created_at`, `updated_at`, `ended_at`) " +
                    "VALUES ('$ENDED_ID', 'Beendet', 1, 'ONE_DAY', 1, 4294967296, 'ENDED', 1000, $ENDED_AT, $ENDED_AT)",
            )
            db.execSQL(
                "INSERT INTO `media` (`id`, `space_id`, `sha256`, `mime`, `size_bytes`, `original_name`, " +
                    "`uploader_id`, `created_at`, `state`) " +
                    "VALUES ('$MEDIUM_ID', '$ENDED_ID', '$SHA256', 'image/jpeg', 12345, 'bild.jpg', " +
                    "'$UPLOADER_ID', 1500, 'READY')",
            )
            db.version = 8
        }
    }

    private companion object {
        const val ENDED_ID = "AAAAAAAAAAAAAAAAAAAAAA"
        const val ENDED_AT = 5_000_000L
        const val MEDIUM_ID = "BBBBBBBBBBBBBBBBBBBBBB"
        const val UPLOADER_ID = "CCCCCCCCCCCCCCCCCCCCCC"
        const val SHA256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
