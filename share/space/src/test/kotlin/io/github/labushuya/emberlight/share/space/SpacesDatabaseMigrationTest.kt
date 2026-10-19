package io.github.labushuya.emberlight.share.space

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Migration 1 → 2 (Paket 3.1b): Bestehende Medien bleiben lesbar, bekommen `location_kept = 0` und
 * `processing_version = 0` (ADR 0015). Die Datenbank wird mit dem Version-1-Schema von Hand angelegt.
 */
@RunWith(RobolectricTestRunner::class)
class SpacesDatabaseMigrationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var database: SpacesDatabase? = null

    @After
    fun close() {
        database?.close()
    }

    @Test
    fun migrationFromVersionOneKeepsMediaAndAddsSafeDefaults() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val spacesDir = folder.newFolder("spaces")
        createVersionOneDatabase(File(spacesDir, SpacesDatabase.FILE_NAME))

        val opened = SpacesDatabase.open(context, spacesDir)
        database = opened

        val rows = runBlocking { opened.media().firstPage(SPACE, 10) }
        assertEquals(1, rows.size)
        assertEquals("vorhanden", rows.single().id)
        assertFalse(rows.single().locationKept)

        val cursor =
            opened.openHelper.readableDatabase.query(
                "SELECT location_kept, processing_version FROM media WHERE id = 'vorhanden'",
            )
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
            assertEquals(0, it.getInt(1))
        }
    }

    /** Schema wie Room es für Version 1 anlegt, mit einem Medium. */
    private fun createVersionOneDatabase(file: File) {
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
                    "`state` TEXT NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_media_space_id_sha256` ON `media` (`space_id`, `sha256`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_media_space_id_created_at_id` ON `media` " +
                    "(`space_id`, `created_at`, `id`)",
            )
            db.execSQL(
                "INSERT INTO media (id, space_id, sha256, mime, size_bytes, original_name, uploader_id, created_at, " +
                    "state) VALUES ('vorhanden', '$SPACE', '${"a".repeat(64)}', 'image/jpeg', 64, 'alt.jpg', " +
                    "'teilnehmer', 1000, 'READY')",
            )
            db.version = 1
        }
    }

    private companion object {
        const val SPACE = "space-migration"
    }
}
