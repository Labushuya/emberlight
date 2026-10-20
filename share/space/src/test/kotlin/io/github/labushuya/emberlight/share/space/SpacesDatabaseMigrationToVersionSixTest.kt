package io.github.labushuya.emberlight.share.space

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.participants.FakeInviteSecretCipher
import io.github.labushuya.emberlight.share.space.participants.Secrets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Migration 5 → 6 (Paket 3.4, ADR 0018): verschlüsseltes Einladungsgeheimnis als Ciphertext und IV am Space. Bestehende
 * Spaces mit Einladungs-Hash bekommen `NULL`. Beim ersten Start danach entsteht eine neue Einladung, der Gastgeber wird
 * darauf hingewiesen. Das Schema der Version 5 wird von Hand angelegt, wie es nach 3.4 (ohne Geheimnis) auf dem Gerät liegt.
 */
@RunWith(RobolectricTestRunner::class)
class SpacesDatabaseMigrationToVersionSixTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var database: SpacesDatabase? = null

    @After
    fun close() {
        database?.close()
    }

    @Test
    fun theSecretColumnsAreAddedAndTheExistingSpaceKeepsItsHashAndPort() {
        val spacesDir = folder.newFolder("spaces")
        val legacyHash = Secrets.hashHex(Secrets.generate())
        createVersionFiveDatabase(File(spacesDir, SpacesDatabase.FILE_NAME), legacyHash)

        val opened = open(spacesDir)

        val columns = columnsOf(opened, "space")
        assertTrue(columns.contains("invite_ciphertext"))
        assertTrue(columns.contains("invite_iv"))
        val space = runBlocking { opened.spaces().find(SPACE_ID) }
        assertEquals(legacyHash, space?.inviteHash)
        assertEquals(8080, space?.port)
        assertNull(space?.inviteCiphertext)
        assertNull(space?.inviteIv)
    }

    @Test
    fun theFirstStartAfterTheMigrationRenewsTheLegacyInviteOnceAndThenKeepsIt() {
        val spacesDir = folder.newFolder("spaces")
        createVersionFiveDatabase(File(spacesDir, SpacesDatabase.FILE_NAME), Secrets.hashHex(Secrets.generate()))
        val opened = open(spacesDir)
        val cipher = FakeInviteSecretCipher()

        val first = runBlocking { vaultOf(opened, spacesDir, cipher).ensureInvite() }
        val second = runBlocking { vaultOf(opened, spacesDir, cipher).ensureInvite() }

        assertTrue(first.renewed)
        assertFalse(second.renewed)
        assertEquals(first.secret, second.secret)
        assertNotNull(runBlocking { opened.spaces().find(SPACE_ID) }?.inviteCiphertext)
    }

    private fun vaultOf(
        db: SpacesDatabase,
        spacesDir: File,
        cipher: FakeInviteSecretCipher,
    ): SpaceVault =
        SpaceVault.open(
            db,
            spacesDir,
            SpaceId(SPACE_ID),
            SpaceLimits(),
            StorageProbe { Long.MAX_VALUE / 4 },
            cipher,
        )

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

    /** Schema der Version 5 (nach 3.4, vor dem Geheimnis): Spalten der Migrationen 4 → 5, ohne Verschlüsselung. */
    private fun createVersionFiveDatabase(
        file: File,
        inviteHash: String,
    ) {
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
                    "`expires_at` INTEGER, `ended_at` INTEGER, `invite_hash` TEXT, `invite_created_at` INTEGER, " +
                    "`invite_rotated_at` INTEGER, `port` INTEGER, PRIMARY KEY(`id`))",
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
                    "`updated_at`, `started_at`, `expires_at`, `ended_at`, `invite_hash`, `port`) " +
                    "VALUES ('$SPACE_ID', 'Laufend', 1, NULL, 'ONE_DAY', NULL, 7, 4294967296, 'ACTIVE', 1000, 2000, " +
                    "1500, 9000, NULL, '$inviteHash', 8080)",
            )
            db.version = 5
        }
    }

    private companion object {
        const val SPACE_ID = "AAAAAAAAAAAAAAAAAAAAAA"
    }
}
