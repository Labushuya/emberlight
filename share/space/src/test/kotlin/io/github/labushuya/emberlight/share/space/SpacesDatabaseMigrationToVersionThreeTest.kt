package io.github.labushuya.emberlight.share.space

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Migration 2 → 3 (Paket 3.2): Die Tabelle `space` kommt hinzu, Medien und Teilnehmer bleiben unverändert. Das Schema
 * der Version 2 wird von Hand angelegt, so wie es nach 3.1b auf dem Gerät liegt (Migration 1 → 2 und ihre Spalten).
 */
@RunWith(RobolectricTestRunner::class)
class SpacesDatabaseMigrationToVersionThreeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var database: SpacesDatabase? = null

    @After
    fun close() {
        database?.close()
    }

    @Test
    fun versionTwoDataSurvivesAndTheSpaceTableIsAdded() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val spacesDir = folder.newFolder("spaces")
            createVersionTwoDatabase(File(spacesDir, SpacesDatabase.FILE_NAME))

            val opened = SpacesDatabase.open(context, spacesDir)
            database = opened

            val media = opened.media().firstPage(SPACE_ID, 10)
            assertEquals(listOf("vorhanden"), media.map { it.id })
            assertTrue(media.single().locationKept)
            assertTrue(tableExists(opened, "space"))
        }

    @Test
    fun theMigratedDatabaseStoresSpacesWithAllFields() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val spacesDir = folder.newFolder("spaces")
            createVersionTwoDatabase(File(spacesDir, SpacesDatabase.FILE_NAME))
            val opened = SpacesDatabase.open(context, spacesDir)
            database = opened

            opened.spaces().upsert(
                SpaceEntity(
                    id = SPACE_ID,
                    name = "Geburtstag",
                    colorIndex = 4,
                    coverSha256 = "b".repeat(64),
                    lifetimePreset = null,
                    lifetimeUntil = 1_800_000_000_000L,
                    retentionDays = 0,
                    storageLimitBytes = 2L * 1024 * 1024 * 1024,
                    status = "DRAFT",
                    createdAt = 1L,
                    updatedAt = 2L,
                ),
            )

            val stored = opened.spaces().find(SPACE_ID)
            assertNotNull(stored)
            assertEquals("Geburtstag", stored?.name)
            assertEquals(null, stored?.lifetimePreset)
            assertEquals(1_800_000_000_000L, stored?.lifetimeUntil)
            assertEquals(0, stored?.retentionDays)
        }

    private fun tableExists(
        db: SpacesDatabase,
        name: String,
    ): Boolean =
        db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$name'")
            .use { it.moveToFirst() }

    /** Schema der Version 2: Teilnehmer, Medien mit den Spalten aus 3.1b und ihre Indizes. */
    private fun createVersionTwoDatabase(file: File) {
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
                "INSERT INTO `media` (`id`, `space_id`, `sha256`, `mime`, `size_bytes`, `original_name`, " +
                    "`uploader_id`, `created_at`, `state`, `location_kept`, `processing_version`) " +
                    "VALUES ('vorhanden', '$SPACE_ID', '${"a".repeat(64)}', 'image/jpeg', 10, 'a.jpg', " +
                    "'teilnehmer', 1000, 'READY', 1, 1)",
            )
            db.version = 2
        }
    }

    private companion object {
        const val SPACE_ID = "AAAAAAAAAAAAAAAAAAAAAA"
    }
}
