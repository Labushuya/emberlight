package io.github.labushuya.emberlight.feature.space.run

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.labushuya.emberlight.feature.space.R
import io.github.labushuya.emberlight.share.space.participants.ParticipantInfo
import io.github.labushuya.emberlight.share.space.Role as ParticipantRole

/**
 * Einladung des laufenden Space: QR-Code, Link als Text, Teilen und Kopieren, Erneuern (ADR 0018). Solange der Block steht,
 * bleibt der Bildschirm an und hell.
 */
@Composable
internal fun InviteSection(
    link: String,
    addressChanged: Boolean,
    onRotate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    KeepScreenBrightWhileShown()
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.space_invite_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (addressChanged) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.space_invite_address_changed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            InviteQrImage(content = link, modifier = Modifier.fillMaxWidth(QR_WIDTH_FRACTION))
        }
        Spacer(Modifier.height(12.dp))
        SelectionContainer {
            Text(
                text = link,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = stringResource(R.string.space_invite_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { shareLink(context, link) },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.space_invite_share)) }
            OutlinedButton(
                onClick = { copyLink(context, link) },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.space_invite_copy)) }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRotate, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.space_invite_rotate))
        }
    }
}

/** Teilnehmerliste mit Gastgeber-Kennzeichen. Entfernen nur bei Gästen (ADR 0018). */
@Composable
internal fun ParticipantsSection(
    participants: List<ParticipantInfo>,
    onRemove: (ParticipantInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.space_participants_title, participants.size),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        participants.forEachIndexed { index, participant ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = participant.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (participant.role == ParticipantRole.HOST) {
                        Text(
                            text = stringResource(R.string.space_participant_host),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (participant.role == ParticipantRole.GUEST) {
                    TextButton(onClick = { onRemove(participant) }) {
                        Text(stringResource(R.string.space_participant_remove))
                    }
                }
            }
        }
    }
}

/**
 * Entfernen eines Gastes. Das Häkchen löscht auch seine Medien. Ohne Häkchen bleiben sie und zeigen „entfernt“ (ADR 0018).
 */
@Composable
internal fun RemoveParticipantDialog(
    participant: ParticipantInfo,
    onConfirm: (deleteUploads: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var deleteUploads by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.space_participant_remove_title, participant.displayName)) },
        text = {
            RemoveParticipantText(
                name = participant.displayName,
                deleteUploads = deleteUploads,
                onDeleteUploadsChange = { deleteUploads = it },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(deleteUploads) }) {
                Text(stringResource(R.string.space_participant_remove_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.space_cancel)) }
        },
    )
}

/** Inhalt des Entfernen-Dialogs: Erklärung und das Häkchen für die Uploads. Die Goldens zeigen ihn ohne Dialogfenster. */
@Composable
internal fun RemoveParticipantText(
    name: String,
    deleteUploads: Boolean,
    onDeleteUploadsChange: (Boolean) -> Unit,
) {
    Column {
        Text(stringResource(R.string.space_participant_remove_text))
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = deleteUploads, role = Role.Checkbox, onValueChange = onDeleteUploadsChange),
        ) {
            Checkbox(checked = deleteUploads, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.space_participant_remove_uploads, name))
        }
    }
}

/** Inhalt des Erneuern-Dialogs. Die Goldens zeigen ihn ohne Dialogfenster. */
@Composable
internal fun RotateInviteText() {
    Text(stringResource(R.string.space_invite_rotate_text))
}

/** Erneuern der Einladung. Alte Codes werden ungültig, Beigetretene bleiben (ADR 0018). */
@Composable
internal fun RotateInviteDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.space_invite_rotate_title)) },
        text = { RotateInviteText() },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.space_invite_rotate_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.space_cancel)) }
        },
    )
}

private const val QR_WIDTH_FRACTION = 0.8f

private fun shareLink(
    context: Context,
    link: String,
) {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
        }
    context.startActivity(Intent.createChooser(send, null))
}

private fun copyLink(
    context: Context,
    link: String,
) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.space_invite_title), link))
    Toast.makeText(context, R.string.space_invite_copied, Toast.LENGTH_SHORT).show()
}
