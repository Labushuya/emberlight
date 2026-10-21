package io.github.labushuya.emberlight.feature.space

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import io.github.labushuya.emberlight.feature.space.form.RetentionOption
import io.github.labushuya.emberlight.share.space.retention.RetentionRules

/**
 * Dialog „Frist ändern“ während der Aufbewahrung (Paket 3.6b). Die Auswahl entspricht dem Formular. Die Dauer in Tagen
 * wird mit [onConfirm] zurückgegeben, die Umrechnung in ein Datum geschieht in der Domäne (ab jetzt).
 */
@Composable
internal fun RetentionChangeDialog(
    currentDays: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial =
        when (currentDays) {
            0 -> RetentionOption.IMMEDIATE
            1 -> RetentionOption.ONE_DAY
            7 -> RetentionOption.ONE_WEEK
            else -> RetentionOption.CUSTOM
        }
    var option by rememberSaveable { mutableStateOf(initial) }
    var custom by rememberSaveable { mutableStateOf(if (initial == RetentionOption.CUSTOM) currentDays.toString() else "") }
    val customDays = custom.toIntOrNull()
    val chosenDays =
        when (option) {
            RetentionOption.CUSTOM -> customDays?.takeIf { RetentionRules.isValidDays(it) && it > 0 }
            else -> option.days
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.space_retention_change_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.space_retention_change_text))
                Spacer(Modifier.height(12.dp))
                RetentionOption.entries.forEach { entry ->
                    val label =
                        when (entry) {
                            RetentionOption.IMMEDIATE -> stringResource(R.string.space_retention_immediate)
                            RetentionOption.ONE_DAY -> stringResource(R.string.space_retention_one_day)
                            RetentionOption.ONE_WEEK -> stringResource(R.string.space_retention_one_week)
                            RetentionOption.CUSTOM -> stringResource(R.string.space_retention_custom)
                        }
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = option == entry,
                                    onClick = { option = entry },
                                    role = Role.RadioButton,
                                ).padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = option == entry, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (option == RetentionOption.CUSTOM) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { value -> custom = value.filter { it.isDigit() }.take(2) },
                        label = { Text(stringResource(R.string.space_retention_custom_label)) },
                        isError = custom.isNotEmpty() && chosenDays == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { chosenDays?.let(onConfirm) },
                enabled = chosenDays != null,
            ) {
                Text(stringResource(R.string.space_retention_change_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.space_cancel))
            }
        },
    )
}

/** Bestätigung „Jetzt löschen“ (Paket 3.6b, Designentscheidung (a)). Löschen geschieht nur nach dem Bestätigen. */
@Composable
internal fun RetentionPurgeDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.space_retention_purge_title)) },
        text = { Text(stringResource(R.string.space_retention_purge_text, name)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.space_retention_purge_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.space_cancel))
            }
        },
    )
}
