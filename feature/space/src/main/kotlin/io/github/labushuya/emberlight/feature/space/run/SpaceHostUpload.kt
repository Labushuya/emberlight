package io.github.labushuya.emberlight.feature.space.run

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.labushuya.emberlight.feature.space.R
import io.github.labushuya.emberlight.feature.space.upload.HostUploadRules
import io.github.labushuya.emberlight.feature.space.upload.HostUploadUiState
import io.github.labushuya.emberlight.feature.space.upload.StopReason
import io.github.labushuya.emberlight.feature.space.upload.UploadItem
import io.github.labushuya.emberlight.feature.space.upload.UploadStatus
import io.github.labushuya.emberlight.share.space.RejectReason

/**
 * Fortschritt der Uploads aus der App (Paket 3.5c). Je Datei Name, Fortschritt und Ergebnis. Abbrechen stoppt die Reihe.
 * Ist die Größe unbekannt, bleibt der Balken unbestimmt. Die Zeile zeigt dann nur den Zustand.
 */
@Composable
internal fun HostUploadSection(
    state: HostUploadUiState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        state.items.forEach { item ->
            UploadRow(item)
            Spacer(Modifier.height(8.dp))
        }
        if (state.running) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.space_upload_cancel))
            }
        }
        if (state.storageWarning) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.space_upload_storage_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Eine Datei der Reihe: Name, Balken während des Hochladens und das Ergebnis. */
@Composable
private fun UploadRow(item: UploadItem) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = item.name.ifBlank { stringResource(R.string.space_upload_unnamed) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (item.status == UploadStatus.Uploading) {
            val progress = item.progress
            val fraction = progress?.let { HostUploadRules.fraction(it) }
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    text =
                        stringResource(
                            R.string.space_upload_progress,
                            Formatter.formatShortFileSize(context, progress.readBytes),
                            Formatter.formatShortFileSize(context, progress.totalBytes ?: 0L),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        Text(
            text = statusText(item.status),
            style = MaterialTheme.typography.bodySmall,
            color = statusColor(item.status),
        )
    }
}

/** Text zum Zustand einer Datei. Die Texte stehen in `strings.xml`. */
@Composable
private fun statusText(status: UploadStatus): String {
    val context = LocalContext.current
    return when (status) {
        UploadStatus.Waiting -> {
            stringResource(R.string.space_upload_waiting)
        }

        UploadStatus.Uploading -> {
            stringResource(R.string.space_upload_uploading)
        }

        is UploadStatus.Stored -> {
            stringResource(
                if (status.warning) R.string.space_upload_stored_warning else R.string.space_upload_stored,
            )
        }

        UploadStatus.Duplicate -> {
            stringResource(R.string.space_upload_duplicate)
        }

        UploadStatus.Unreadable -> {
            stringResource(R.string.space_upload_unreadable)
        }

        UploadStatus.Skipped -> {
            stringResource(R.string.space_upload_skipped)
        }

        is UploadStatus.Stopped -> {
            stringResource(stoppedRes(status.reason))
        }

        is UploadStatus.Rejected -> {
            rejectedText(
                status.reason,
                Formatter.formatShortFileSize(context, limitOf(status.reason)),
            )
        }
    }
}

private fun stoppedRes(reason: StopReason): Int =
    when (reason) {
        StopReason.CANCELLED -> R.string.space_upload_stopped_cancelled
        StopReason.STORAGE_FULL -> R.string.space_upload_stopped_storage
        StopReason.NOT_RUNNING -> R.string.space_upload_stopped_not_running
    }

@Composable
private fun rejectedText(
    reason: RejectReason,
    limit: String,
): String =
    when (reason) {
        RejectReason.UnsupportedType -> stringResource(R.string.space_upload_rejected_format)
        is RejectReason.TooLarge -> stringResource(R.string.space_upload_rejected_size, limit)
        RejectReason.StorageFull -> stringResource(R.string.space_upload_rejected_storage)
        RejectReason.DeviceStorageLow -> stringResource(R.string.space_upload_rejected_device)
        is RejectReason.LocationNotRemovable -> stringResource(R.string.space_upload_rejected_location)
    }

/** Größe für die Grenzmeldung. Nur die Größengrenze nennt eine Zahl. */
private fun limitOf(reason: RejectReason): Long = (reason as? RejectReason.TooLarge)?.limitBytes ?: 0L

@Composable
private fun statusColor(status: UploadStatus) =
    when (status) {
        is UploadStatus.Rejected, UploadStatus.Unreadable -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

/**
 * Standort-Rückfrage pro Datei (Paket 3.5c, Designentscheidung (e)). Der Gastgeber entscheidet je Datei, ob sie mit Standort
 * hochgeladen wird. Mit Nein bleibt die Datei außen, die Reihe läuft weiter.
 */
@Composable
internal fun LocationQuestionDialog(
    fileName: String,
    onKeep: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(fileName) },
        text = { LocationQuestionText() },
        confirmButton = {
            TextButton(onClick = onKeep) { Text(stringResource(R.string.space_upload_location_keep)) }
        },
        dismissButton = {
            TextButton(onClick = onSkip) { Text(stringResource(R.string.space_upload_location_skip)) }
        },
    )
}

/** Text der Standort-Rückfrage. Eigene Funktion, damit die Goldens dieselben Zeilen zeigen wie die App. */
@Composable
internal fun LocationQuestionText() {
    Text(stringResource(R.string.space_upload_location_text))
}

/** Zeile „Dateien hochladen“ mit dem Knopf für die Auswahl, nur im laufenden Space. */
@Composable
internal fun HostUploadAddRow(
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.space_upload_add))
        }
    }
}
