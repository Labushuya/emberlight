package io.github.labushuya.emberlight.feature.space.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
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
 * Hintergrundauftrag der Übernahme (Paket 3.7, ADR 0022): Eingaben prüfen, nur die eigene Auswahldatei verwenden und sie
 * danach entfernen, unbekannte Spaces melden „nicht übernehmbar“.
 */
@RunWith(RobolectricTestRunner::class)
class SpaceExportWorkerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @Test
    fun withoutASpaceIdTheRunFails() {
        val result = run(workDataOf(SpaceExportQueue.IN_SELECTION_PATH to "x"))

        assertTrue(result is ListenableWorker.Result.Failure)
    }

    @Test
    fun aSelectionOutsideTheOwnDirectoryIsNotReadAndNotDeleted() {
        val outside = folder.newFile("fremd.txt").apply { writeText("AAAAAAAAAAAAAAAAAAAAAA\nMedium") }

        val result =
            run(
                workDataOf(
                    SpaceExportQueue.IN_SPACE_ID to ID,
                    SpaceExportQueue.IN_SELECTION_PATH to outside.path,
                ),
            )

        assertTrue(result is ListenableWorker.Result.Failure)
        assertTrue("Fremde Dateien bleiben unberührt", outside.exists())
    }

    @Test
    fun aMissingSpaceReportsNotExportableAndTheOwnSelectionIsRemoved() {
        val own = ownSelection("Medium-1")

        val result = run(workDataOf(SpaceExportQueue.IN_SPACE_ID to ID, SpaceExportQueue.IN_SELECTION_PATH to own.path))

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(
            SpaceExportQueue.OUTCOME_NOT_EXPORTABLE,
            (result as ListenableWorker.Result.Success).outputData.getString(SpaceExportQueue.OUT_OUTCOME),
        )
        assertFalse("Die eigene Auswahl wird nach dem Lauf entfernt", own.exists())
    }

    @Test
    fun aSelectionThatIsGoneGivesAFailureInsteadOfAnEmptyCopy() {
        val result =
            run(
                workDataOf(
                    SpaceExportQueue.IN_SPACE_ID to ID,
                    SpaceExportQueue.IN_SELECTION_PATH to "gibt-es-nicht",
                ),
            )

        assertTrue(result is ListenableWorker.Result.Failure)
    }

    /** Eine Auswahldatei im Verzeichnis, das die Übernahme selbst benutzt. */
    private fun ownSelection(vararg ids: String): File {
        val dir = SpaceExportQueue.selectionDirectory(context).apply { mkdirs() }
        return File(dir, "test-${System.nanoTime()}.txt").apply { writeText(ids.joinToString("\n")) }
    }

    private fun run(input: androidx.work.Data): ListenableWorker.Result =
        runBlocking {
            TestListenableWorkerBuilder<SpaceExportWorker>(context)
                .setInputData(input)
                .build()
                .startWork()
                .get()
        }

    private companion object {
        const val ID = "BBBBBBBBBBBBBBBBBBBBBB"
    }
}
