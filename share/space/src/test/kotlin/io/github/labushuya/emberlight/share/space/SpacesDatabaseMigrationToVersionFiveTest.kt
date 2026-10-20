package io.github.labushuya.emberlight.share.space

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.participants.JoinOutcome
import io.github.labushuya.emberlight.share.space.participants.Secrets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Migration 4 → 5 (Paket 3.4, ADR 0018): Teilnehmer mit Space-Bezug und Namensschlüssel, Einladung und Port am Space.
 * Bestehende Teilnehmer und Medien bleiben erhalten. Das Schema der Version 4 wird von Hand angelegt, wie es nach 3.3
 * auf dem Gerät liegt. Room prüft das Schema nach der Migration (fehlt eine Spalte oder weicht ein Default ab, schlägt
 * das Öffnen fehl).
 */
@RunWith(RobolectricTestRunner::class)
class SpacesDatabaseMigrationToVersionFiveTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var database: SpacesDatabase? = null

    @After
    fun close() {
        database?.close()
    }

    @Test
    fun legacyParticipantsAndMediaSurviveWithPlaceholderKeys() =
        runBlocking {
            val spacesDir = folder.newFolder("spaces")
            createVersionFourDatabase(File(spacesDir, SpacesDatabase.FILE_NAME))

            val opened = open(spacesDir)

            val legacy = opened.participants().findById("teilnehmer")
            assertEquals("Alt", legacy?.displayName)
            assertEquals("", legacy?.spaceId)
            assertEquals("#legacy:teilnehmer", legacy?.nameKey)
            assertNull(legacy?.revokedAt)
            assertEquals(1, opened.media().firstPage(SPACE_ID, 10).size)
        }

    @Test
    fun spaceGetsInviteAndPortColumnsEmptyAndTheJoinWorksAfterwards() =
        runBlocking {
            val spacesDir = folder.newFolder("spaces")
            createVersionFourDatabase(File(spacesDir, SpacesDatabase.FILE_NAME))
            val opened = open(spacesDir)

            val space = opened.spaces().find(SPACE_ID)
            assertNull(space?.inviteHash)
            assertNull(space?.port)
            assertNull(space?.inviteCreatedAt)

            val vault =
                SpaceVault.open(
                    opened,
                    spacesDir,
                    SpaceId(SPACE_ID),
                    SpaceLimits(),
                    StorageProbe {
                        Long.MAX_VALUE /
                            4
                    },
                )
            val secret = vault.ensureInvite(null)
            val outcome = vault.join(secret, "Neu")
            assertTrue("Erwartet Joined, war $outcome", outcome is JoinOutcome.Joined)
            assertTrue(Secrets.isWellFormed(secret))
            // Der Altbestand hat keinen Space-Bezug (Platzhalter), gezählt wird nur der neue Gast.
            assertEquals(1, opened.participants().countActive(SPACE_ID))
        }

    @Test
    fun uniqueNameIndexExistsAfterTheMigration() {
        val spacesDir = folder.newFolder("spaces")
        createVersionFourDatabase(File(spacesDir, SpacesDatabase.FILE_NAME))
        val opened = open(spacesDir)
        assertTrue(indexExists(opened, "index_participant_space_id_name_key"))
    }

    private fun open(spacesDir: File): SpacesDatabase =
        SpacesDatabase.open(ApplicationProvider.getApplicationContext<Context>(), spacesDir).also {
            database = it
        }

    private fun indexExists(
        db: SpacesDatabase,
        name: String,
    ): Boolean =
        db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = '$name'")
            .use { it.moveToFirst() }

    /** Schema der Version 4 (nach 3.3): Teilnehmer ohne Space-Bezug, Space mit Lebenszyklus, Pausen. */
    private fun createVersionFourDatabase(file: File) {
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
                    "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `started_at` INTEGER, " +
                    "`expires_at` INTEGER, `ended_at` INTEGER, PRIMARY KEY(`id`))",
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
                "INSERT INTO `space` (`id`, `name`, `color_index`, `cover_sha256`, `lifetime_preset`, " +
                    "`lifetime_until`, `retention_days`, `storage_limit_bytes`, `status`, `created_at`, " +
                    "`updated_at`, `started_at`, `expires_at`, `ended_at`) " +
                    "VALUES ('$SPACE_ID', 'Laufend', 1, NULL, 'ONE_DAY', NULL, 7, 4294967296, 'ACTIVE', 1000, 2000, " +
                    "1500, 9000, NULL)",
            )
            db.execSQL(
                "INSERT INTO `participant` (`id`, `display_name`, `role`, `token_hash`) " +
                    "VALUES ('teilnehmer', 'Alt', 'GUEST', '${"0".repeat(64)}')",
            )
            db.execSQL(
                "INSERT INTO `media` (`id`, `space_id`, `sha256`, `mime`, `size_bytes`, `original_name`, " +
                    "`uploader_id`, `created_at`, `state`, `location_kept`, `processing_version`) " +
                    "VALUES ('vorhanden', '$SPACE_ID', '${"a".repeat(64)}', 'image/jpeg', 10, 'a.jpg', " +
                    "'teilnehmer', 1000, 'READY', 0, 1)",
            )
            db.version = 4
        }
    }

    private companion object {
        const val SPACE_ID = "AAAAAAAAAAAAAAAAAAAAAA"
    }
}
