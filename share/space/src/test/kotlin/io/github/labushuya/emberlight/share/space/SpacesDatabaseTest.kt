package io.github.labushuya.emberlight.share.space

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Prüft den Speicherort der Room-Datenbank (ADR 0014, Persistenz, offene Frage zu absoluten Pfaden). Die Datei liegt
 * tatsächlich unter `noBackupFilesDir/spaces/` und nicht unter `databases/`.
 */
@RunWith(RobolectricTestRunner::class)
class SpacesDatabaseTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: SpacesDatabase

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val spacesDir = File(context.noBackupFilesDir, "spaces")
        database = SpacesDatabase.open(context, spacesDir)
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun databaseFileLivesUnderNoBackupFilesDirAndNotInDatabasesFolder() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Eine Abfrage erzwingt das Anlegen der Datei.
        runBlocking { database.media().usedBytes("probe") }

        val expected = File(File(context.noBackupFilesDir, "spaces"), SpacesDatabase.FILE_NAME)
        assertTrue("Datenbank fehlt unter ${expected.path}", expected.isFile)
        assertFalse(context.getDatabasePath(SpacesDatabase.FILE_NAME).isFile)
        assertTrue(expected.absolutePath.startsWith(context.noBackupFilesDir.absolutePath))
        assertEquals(0L, runBlocking { database.media().usedBytes("probe") })
    }
}
