package io.github.labushuya.emberlight.feature.space.run

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.labushuya.emberlight.feature.space.R
import io.github.labushuya.emberlight.feature.space.UntilPickers
import io.github.labushuya.emberlight.share.space.run.ExtendProblem
import java.text.DateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date

/** Wahl im Verlängern-Dialog (Paket 3.6a). Die Endzeit rechnet der Aufrufer aus dem aktuellen Ende. */
sealed interface ExtendChoice {
    /** Eine Stunde zum bisherigen Ende. */
    data object PlusHour : ExtendChoice

    /** Ein Tag zum bisherigen Ende. */
    data object PlusDay : ExtendChoice

    /** Ein festes neues Ende in UTC-Millisekunden. */
    data class Until(
        val endsAtMillis: Long,
    ) : ExtendChoice
}

/**
 * Verlängern eines laufenden Space (Paket 3.6a): „+1 Std.“, „+1 Tag“ oder „Eigenes Ende“ über die Auswahl von Datum und Uhrzeit.
 * [problem] zeigt den Grund, wenn die letzte Wahl nicht galt.
 */
@Composable
internal fun ExtendDialog(
    currentEndsAtMillis: Long,
    problem: ExtendProblem?,
    onChoose: (ExtendChoice) -> Unit,
    onDismiss: () -> Unit,
    customStart: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.space_extend_title)) },
        text = {
            ExtendDialogContent(
                currentEndsAtMillis = currentEndsAtMillis,
                problem = problem,
                onChoose = onChoose,
                customStart = customStart,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.space_cancel)) }
        },
    )
}

/** Inhalt des Verlängern-Dialogs ohne Fensterrahmen. Die Goldens rufen diesen Teil auf. */
@Composable
internal fun ExtendDialogContent(
    currentEndsAtMillis: Long,
    problem: ExtendProblem?,
    onChoose: (ExtendChoice) -> Unit,
    customStart: Boolean = false,
) {
    var custom by rememberSaveable { mutableStateOf(customStart) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.space_extend_current, formatMillis(currentEndsAtMillis)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (custom) {
            CustomEndPicker(onChoose = onChoose)
        } else {
            OutlinedButton(
                onClick = { onChoose(ExtendChoice.PlusHour) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.space_extend_hour)) }
            OutlinedButton(
                onClick = { onChoose(ExtendChoice.PlusDay) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.space_extend_day)) }
            OutlinedButton(
                onClick = { custom = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.space_extend_custom)) }
        }
        problem?.let {
            Text(
                text = stringResource(extendProblemText(it)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Datum und Uhrzeit des neuen Endes. Erst mit beiden Angaben lässt sich bestätigen. */
@Composable
private fun CustomEndPicker(onChoose: (ExtendChoice) -> Unit) {
    // Datum und Uhrzeit bleiben nur im laufenden Dialog. Eine Drehung setzt die Wahl zurück, das ist für einen Dialog vertretbar.
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var time by remember { mutableStateOf<LocalTime?>(null) }
    UntilPickers(
        date = date,
        time = time,
        onDate = { date = it },
        onTime = { time = it },
    )
    val chosenDate = date
    val chosenTime = time
    Button(
        onClick = {
            if (chosenDate != null && chosenTime != null) {
                val millis =
                    LocalDateTime
                        .of(chosenDate, chosenTime)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                onChoose(ExtendChoice.Until(millis))
            }
        },
        enabled = chosenDate != null && chosenTime != null,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.space_extend_confirm)) }
}

/** Text zum Grund, warum die Verlängerung nicht gilt. */
internal fun extendProblemText(problem: ExtendProblem): Int =
    when (problem) {
        ExtendProblem.NOT_ACTIVE -> R.string.space_extend_error_not_active
        ExtendProblem.NOT_IN_FUTURE -> R.string.space_extend_error_future
        ExtendProblem.NOT_LATER -> R.string.space_extend_error_later
        ExtendProblem.TOO_LONG -> R.string.space_extend_error_too_long
    }

private fun formatMillis(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
