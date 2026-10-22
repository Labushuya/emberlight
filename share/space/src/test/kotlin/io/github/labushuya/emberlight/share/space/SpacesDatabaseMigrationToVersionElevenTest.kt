package io.github.labushuya.emberlight.share.space

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.export.ExportedMediaEntity
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
 * Migration 10 → 11 (Paket 3.7, ADR 0022): zwei neue Tabellen für Zielordner und Vermerke. Der Test nimmt ein frisches
 * Schema der Version 11, entfernt die beiden Tabellen und setzt die Version auf 10 zurück. Beim Öffnen läuft dann die
 * Migration. Room prüft dabei das Schema gegen die Entitäten. Ein abweichendes SQL würde beim Öffnen scheitern.
 */
@RunWith(RobolectricTestRunner::class)
class SpacesDatabaseMigrationToVersionElevenTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var database: SpacesDatabase? = null

    @After
    fun close() {
        database?.close()
    }

    @Test
    fun theExportTablesAreAddedAndEmptyAfterTheMigration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val spacesDir = folder.newFolder("spaces")
        SpacesDatabase.open(context, spacesDir).also { fresh ->
            // Erzwingt das Anlegen des Schemas der Version 11.
            fresh.openHelper.writableDatabase
            fresh.close()
        }
        val file = File(spacesDir, SpacesDatabase.FILE_NAME)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DROP TABLE IF EXISTS `space_export`")
            raw.execSQL("DROP TABLE IF EXISTS `export_media`")
            raw.version = 10
        }

        val opened = SpacesDatabase.open(context, spacesDir).also { database = it }

        assertNull(runBlocking { opened.exports().folderOf(ID) })
        assertTrue(runBlocking { opened.exports().exportedIds(ID) }.isEmpty())
        assertEquals(
            listOf("media_id", "space_id", "target_name", "target_uri", "exported_at"),
            columnsOf(opened, "export_media"),
        )
        assertEquals(listOf("space_id", "folder_name", "created_at"), columnsOf(opened, "space_export"))
    }

    @Test
    fun aRecordedCopyIsStillThereAfterReopening() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val spacesDir = folder.newFolder("spaces")
        SpacesDatabase.open(context, spacesDir).also { fresh ->
            runBlocking {
                fresh.exports().insertExported(
                    ExportedMediaEntity(MEDIUM, ID, "bild.jpg", "content://media/1", 42L),
                )
            }
            fresh.close()
        }

        val reopened = SpacesDatabase.open(context, spacesDir).also { database = it }

        assertEquals(listOf(MEDIUM), runBlocking { reopened.exports().exportedIds(ID) })
    }

    private fun columnsOf(
        db: SpacesDatabase,
        table: String,
    ): List<String> =
        db.openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }.toList()
        }

    private companion object {
        const val ID = "space-migration-11"
        const val MEDIUM = "medium-migration-11"
    }
}
