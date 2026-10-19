package io.github.labushuya.emberlight.feature.space

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.labushuya.emberlight.share.space.management.SpaceRules
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Datum und Uhrzeit des festen Endes über die Auswahl von Material 3 statt Textfeldern (Paket 3.2, Entscheidung 4). Das
 * Datum lässt sich nur für heute bis 30 Tage voraus wählen. Die Regel für die Uhrzeit prüft [SpaceRules] beim Speichern.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UntilPickers(
    date: LocalDate?,
    time: LocalTime?,
    onDate: (LocalDate) -> Unit,
    onTime: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PickerButton(
            label = stringResource(R.string.space_until_date),
            value = date?.let { formatLocalDate(it) } ?: stringResource(R.string.space_until_pick_date),
            onClick = { showDate = true },
            modifier = Modifier.weight(1f),
        )
        PickerButton(
            label = stringResource(R.string.space_until_time),
            value = time?.let { formatLocalTime(it) } ?: stringResource(R.string.space_until_pick_time),
            onClick = { showTime = true },
            modifier = Modifier.weight(1f),
        )
    }
    if (showDate) {
        DateChoiceDialog(
            initial = date,
            onPick = {
                showDate = false
                onDate(it)
            },
            onDismiss = { showDate = false },
        )
    }
    if (showTime) {
        TimeChoiceDialog(
            initial = time,
            onPick = {
                showTime = false
                onTime(it)
            },
            onDismiss = { showTime = false },
        )
    }
}

@Composable
private fun PickerButton(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text(text = value)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateChoiceDialog(
    initial: LocalDate?,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val today = remember(zone) { LocalDate.now(zone) }
    val last = today.plusDays(SpaceRules.UNTIL_MAX_DAYS)
    // Der Picker arbeitet in UTC-Mitternacht des gewählten Tages. Die Grenzen gelten für den Kalendertag.
    val selectable =
        remember(today, last) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val day = utcMillisToDate(utcTimeMillis)
                    return !day.isBefore(today) && !day.isAfter(last)
                }
            }
        }
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis = (initial ?: today).let { dateToUtcMillis(it) },
            selectableDates = selectable,
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onPick(utcMillisToDate(it)) } ?: onDismiss() },
                enabled = state.selectedDateMillis != null,
            ) {
                Text(stringResource(R.string.space_picker_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.space_cancel))
            }
        },
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeChoiceDialog(
    initial: LocalTime?,
    onPick: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val start = initial ?: LocalTime.NOON
    val state =
        rememberTimePickerState(
            initialHour = start.hour,
            initialMinute = start.minute,
            is24Hour = DateFormat.is24HourFormat(LocalContext.current),
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) {
                Text(stringResource(R.string.space_picker_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.space_cancel))
            }
        },
        text = { TimePicker(state = state) },
    )
}

/** Kalendertag zu einem Zeitpunkt des Pickers (UTC-Mitternacht). */
private fun utcMillisToDate(utcMillis: Long): LocalDate =
    Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

/** UTC-Mitternacht eines Kalendertags, so wie der Picker ihn erwartet. */
private fun dateToUtcMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
