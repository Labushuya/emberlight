package io.github.labushuya.emberlight.feature.space.export

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.labushuya.emberlight.feature.space.R
import io.github.labushuya.emberlight.share.export.MediaStoreExportSink
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.export.ExportFailureReason
import io.github.labushuya.emberlight.share.space.export.ExportOutcome
import io.github.labushuya.emberlight.share.space.export.SpaceExportGate
import io.github.labushuya.emberlight.share.space.export.SpaceExporter
import java.io.File

/**
 * Übernimmt die ausgewählten Medien im Hintergrund (Paket 3.7, ADR 0022). Der Auftrag läuft als Vordergrund-Auftrag mit
 * Benachrichtigung weiter, wenn die App verlassen wird. Die Benachrichtigung zeigt den Stand, Abbrechen beendet den
 * Auftrag und verwirft die gerade laufende Kopie.
 *
 * Die Löschung des Space wartet auf den Schlüssel der Übernahme und bricht danach ab (ADR 0022). Die Auswahldatei wird
 * am Ende entfernt, auch bei Fehlern.
 */
class SpaceExportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val spaceId =
            inputData.getString(SpaceExportQueue.IN_SPACE_ID) ?: return androidx.work.ListenableWorker.Result
                .failure()
        val selectionPath = inputData.getString(SpaceExportQueue.IN_SELECTION_PATH)
        val selection = selectionPath?.let { readOwnSelection(it) }
        try {
            if (selection == null) {
                return androidx.work.ListenableWorker.Result
                    .failure()
            }
            setForeground(foregroundInfo(0, selection.size))
            val exporter =
                SpaceExporter(
                    database = SpacesDatabase.shared(applicationContext),
                    spacesDir = SpacesDatabase.spacesDirectory(applicationContext),
                    sink = MediaStoreExportSink(applicationContext),
                    gate = SpaceExportGate.shared,
                )
            // Fortschritt je Puffer wäre zu oft. Gespeichert und gemeldet wird je fertiger Datei und je 1 MiB.
            var reportedFiles = -1
            var reportedBytes = 0L
            val outcome =
                exporter.export(SpaceId(spaceId), selection) { progress ->
                    val fileChanged = progress.doneFiles != reportedFiles
                    if (!fileChanged && progress.doneBytes - reportedBytes < REPORT_STEP_BYTES) return@export
                    reportedFiles = progress.doneFiles
                    reportedBytes = progress.doneBytes
                    setProgressAsync(
                        workDataOf(
                            SpaceExportQueue.PROGRESS_DONE_FILES to progress.doneFiles,
                            SpaceExportQueue.PROGRESS_TOTAL_FILES to progress.totalFiles,
                            SpaceExportQueue.PROGRESS_DONE_BYTES to progress.doneBytes,
                            SpaceExportQueue.PROGRESS_TOTAL_BYTES to progress.totalBytes,
                        ),
                    )
                    if (fileChanged) {
                        setForegroundAsync(foregroundInfo(progress.doneFiles, progress.totalFiles))
                    }
                }
            return androidx.work.ListenableWorker.Result
                .success(outputOf(outcome))
        } finally {
            selectionPath?.let { deleteOwnSelection(it) }
        }
    }

    private fun outputOf(outcome: ExportOutcome) =
        when (outcome) {
            is ExportOutcome.Finished -> {
                val summary = outcome.summary
                val reasons = summary.failed.groupingBy { it.reason }.eachCount()
                workDataOf(
                    SpaceExportQueue.OUT_OUTCOME to SpaceExportQueue.OUTCOME_FINISHED,
                    SpaceExportQueue.OUT_EXPORTED to summary.exported,
                    SpaceExportQueue.OUT_SKIPPED to summary.skippedAlreadyExported,
                    SpaceExportQueue.OUT_FAILED to summary.failed.size,
                    SpaceExportQueue.OUT_FAILED_HASH to (reasons[ExportFailureReason.HASH_MISMATCH] ?: 0),
                    SpaceExportQueue.OUT_FAILED_WRITE to (reasons[ExportFailureReason.WRITE_FAILED] ?: 0),
                    SpaceExportQueue.OUT_FAILED_MISSING to (reasons[ExportFailureReason.MISSING_SOURCE] ?: 0),
                    SpaceExportQueue.OUT_FAILED_UNKNOWN to (reasons[ExportFailureReason.UNKNOWN] ?: 0),
                    SpaceExportQueue.OUT_STOPPED_BY_PURGE to summary.stoppedByPurge,
                )
            }

            is ExportOutcome.NotEnoughSpace -> {
                workDataOf(
                    SpaceExportQueue.OUT_OUTCOME to SpaceExportQueue.OUTCOME_NO_SPACE,
                    SpaceExportQueue.OUT_REQUIRED to outcome.requiredBytes,
                    SpaceExportQueue.OUT_AVAILABLE to outcome.availableBytes,
                )
            }

            ExportOutcome.NotExportable -> {
                workDataOf(SpaceExportQueue.OUT_OUTCOME to SpaceExportQueue.OUTCOME_NOT_EXPORTABLE)
            }
        }

    /** Liest nur Auswahldateien aus dem eigenen Verzeichnis. Ein fremder Pfad liefert `null`. */
    private fun readOwnSelection(path: String): Set<String>? {
        val file = File(path).canonicalFile
        val directory = SpaceExportQueue.selectionDirectory(applicationContext).canonicalFile
        if (file.parentFile != directory) return null
        return SpaceExportQueue.readSelection(file.path)
    }

    private fun deleteOwnSelection(path: String) {
        val file = File(path).canonicalFile
        if (file.parentFile == SpaceExportQueue.selectionDirectory(applicationContext).canonicalFile) {
            file.delete()
        }
    }

    private fun foregroundInfo(
        done: Int,
        total: Int,
    ): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.space_export_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification =
            NotificationCompat
                .Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(applicationContext.getString(R.string.space_export_channel))
                .setContentText(applicationContext.getString(R.string.space_export_notification_text, done, total))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(
                    0,
                    applicationContext.getString(R.string.space_export_cancel),
                    androidx.work.WorkManager
                        .getInstance(applicationContext)
                        .createCancelPendingIntent(id),
                ).build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "space_export"
        const val NOTIFICATION_ID = 4_713
        private const val REPORT_STEP_BYTES = 1L shl 20
    }
}
