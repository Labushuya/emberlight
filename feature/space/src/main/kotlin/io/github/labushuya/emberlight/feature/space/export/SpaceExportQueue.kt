package io.github.labushuya.emberlight.feature.space.export

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID

/**
 * Einreihen und Beobachten der Übernahme in die Galerie (Paket 3.7, ADR 0022). Ein Auftrag je Space. Die Auswahl liegt
 * in einer Datei im App-Speicher, nicht in den Eingabedaten, weil WorkManager deren Größe begrenzt (10 KB).
 */
object SpaceExportQueue {
    /** Ausgabe des Auftrags: Ergebnis der Übernahme. Zählwerte, keine Dateinamen. */
    const val OUT_OUTCOME = "outcome"
    const val OUT_EXPORTED = "exported"
    const val OUT_SKIPPED = "skipped"
    const val OUT_FAILED = "failed"
    const val OUT_FAILED_HASH = "failedHash"
    const val OUT_FAILED_WRITE = "failedWrite"
    const val OUT_FAILED_MISSING = "failedMissing"
    const val OUT_FAILED_UNKNOWN = "failedUnknown"
    const val OUT_STOPPED_BY_PURGE = "stoppedByPurge"
    const val OUT_REQUIRED = "required"
    const val OUT_AVAILABLE = "available"
    const val OUTCOME_FINISHED = "finished"
    const val OUTCOME_NO_SPACE = "noSpace"
    const val OUTCOME_NOT_EXPORTABLE = "notExportable"

    /** Fortschritt des laufenden Auftrags. */
    const val PROGRESS_DONE_FILES = "doneFiles"
    const val PROGRESS_TOTAL_FILES = "totalFiles"
    const val PROGRESS_DONE_BYTES = "doneBytes"
    const val PROGRESS_TOTAL_BYTES = "totalBytes"

    /** Eingabe des Auftrags. */
    const val IN_SPACE_ID = "spaceId"
    const val IN_SELECTION_PATH = "selectionPath"

    private const val SELECTION_DIRECTORY = "space-export"

    /** Markierung aller Aufträge der Übernahme, etwa für Abfragen. */
    const val TAG = "space-export"

    fun uniqueWorkName(spaceId: String): String = "space-export-$spaceId"

    /**
     * Schreibt die Auswahl und reiht den Auftrag ein. Läuft für den Space schon ein Auftrag, bleibt er bestehen
     * ([ExistingWorkPolicy.KEEP]). Die neue Auswahldatei wird dann sofort wieder entfernt.
     */
    fun enqueue(
        context: Context,
        spaceId: String,
        mediaIds: Collection<String>,
    ): UUID {
        require(mediaIds.isNotEmpty()) { "Ohne Auswahl wird nichts eingereiht." }
        val file = writeSelection(context, spaceId, mediaIds)
        val request =
            OneTimeWorkRequestBuilder<SpaceExportWorker>()
                .setInputData(
                    workDataOf(
                        IN_SPACE_ID to spaceId,
                        IN_SELECTION_PATH to file.absolutePath,
                    ),
                ).addTag(TAG)
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueWorkName(spaceId), ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    /** Beobachtet den Auftrag des Space. Liefert `null`, wenn keiner läuft oder lief. */
    fun observe(
        context: Context,
        spaceId: String,
    ): Flow<WorkInfo?> =
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWorkFlow(uniqueWorkName(spaceId))
            .map { infos -> infos.lastOrNull() }

    fun cancel(
        context: Context,
        spaceId: String,
    ) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(spaceId))
    }

    /** Die Auswahldatei ist eine Zeile je Kennung. Ein Pfad außerhalb des Verzeichnisses wird nicht verwendet. */
    fun selectionDirectory(context: Context): File = File(context.noBackupFilesDir, SELECTION_DIRECTORY)

    fun readSelection(path: String): Set<String>? {
        val file = File(path)
        if (!file.isFile) return null
        return file
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun writeSelection(
        context: Context,
        spaceId: String,
        mediaIds: Collection<String>,
    ): File {
        val dir = selectionDirectory(context).apply { mkdirs() }
        val file = File(dir, "$spaceId-${UUID.randomUUID()}.txt")
        file.writeText(mediaIds.joinToString("\n"))
        return file
    }
}
