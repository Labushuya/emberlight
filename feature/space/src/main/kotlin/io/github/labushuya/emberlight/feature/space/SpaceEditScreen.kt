package io.github.labushuya.emberlight.feature.space

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.labushuya.emberlight.core.design.theme.SpaceAccents
import io.github.labushuya.emberlight.feature.space.form.LifetimeOption
import io.github.labushuya.emberlight.feature.space.form.RetentionOption
import io.github.labushuya.emberlight.feature.space.form.SpaceField
import io.github.labushuya.emberlight.feature.space.form.SpaceForm
import io.github.labushuya.emberlight.feature.space.form.StorageOption
import io.github.labushuya.emberlight.feature.space.form.field
import io.github.labushuya.emberlight.share.space.management.SettingsError
import java.io.File

/** Testmarke des Formulars. */
internal const val SPACE_EDIT_TAG = "space-edit"

/**
 * Formular mit Zustand. Verbindet das ViewModel mit der System-Fotoauswahl. Gespeichert oder verworfen wird das Ziel
 * über [SpaceEditEvent.Finished], dann geht die Oberfläche zurück.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpaceEditScreen(
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
    viewModel: SpaceEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pickCover =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let(viewModel::importCover)
        }
    val currentOnDone by rememberUpdatedState(onDone)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { currentOnDone() }
    }
    // Die Systemgeste zurück verhält sich wie der Schließen-Knopf: mit Änderungen erst die Rückfrage.
    BackHandler { viewModel.requestClose() }
    SpaceEditContent(
        state = state,
        onChange = viewModel::onFormChange,
        onLifetime = viewModel::onLifetimeSelected,
        onPickCover = {
            pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onRemoveCover = viewModel::removeCover,
        onSuggestName = viewModel::onSuggestName,
        onConfirmName = viewModel::confirmNameSuggestion,
        onDismissName = viewModel::dismissNameSuggestion,
        onSave = viewModel::save,
        onClose = viewModel::requestClose,
        onConfirmDiscard = viewModel::confirmDiscard,
        onDismissDiscard = viewModel::dismissDiscard,
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * Formular ohne Zustand. Die Abschnitte stehen in der Reihenfolge des Konzepts. Fehler erscheinen am Feld, sobald der
 * Nutzer es berührt hat oder gespeichert wird. „Speichern“ ist erst mit gültigen Eingaben aktiv.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpaceEditContent(
    state: SpaceEditUiState,
    onChange: (SpaceForm) -> Unit,
    onLifetime: (LifetimeOption) -> Unit,
    onPickCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onDismissDiscard: () -> Unit,
    modifier: Modifier = Modifier,
    onSuggestName: () -> Unit = {},
    onConfirmName: () -> Unit = {},
    onDismissName: () -> Unit = {},
) {
    val form = state.form
    val visible = state.visibleErrors()
    val storageMax = state.storageMaxBytes

    fun errorFor(field: SpaceField): SettingsError? = visible.firstOrNull { it.field() == field }

    Scaffold(
        modifier = modifier.testTag(SPACE_EDIT_TAG),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isNew) R.string.space_form_title_new else R.string.space_form_title_edit,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.space_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (state.loaded && state.editable) {
                Surface(tonalElevation = 3.dp) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        if (state.saveFailed) {
                            Text(
                                text = stringResource(R.string.space_save_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Button(onClick = onSave, enabled = state.canSave, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.space_save))
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (!state.loaded) return@Scaffold
        if (!state.editable) {
            Column(modifier = Modifier.padding(padding).padding(24.dp)) {
                Text(
                    text = stringResource(R.string.space_not_editable),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            if (state.templateNotes.isNotEmpty()) {
                SupportText(stringResource(R.string.space_template_hint))
                if (TemplateNote.LIFETIME_RESET in state.templateNotes) {
                    SupportText(stringResource(R.string.space_template_lifetime_reset))
                }
                if (TemplateNote.LIMIT_RESET in state.templateNotes) {
                    SupportText(stringResource(R.string.space_template_limit_reset))
                }
                Spacer(Modifier.height(16.dp))
            }
            FormSection(stringResource(R.string.space_section_name)) {
                val nameError = errorFor(SpaceField.NAME)
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { onChange(form.copy(name = it)) },
                    label = { Text(stringResource(R.string.space_name_label)) },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = {
                        Text(
                            nameError?.let { stringResource(it.messageRes()) }
                                ?: stringResource(R.string.space_name_support),
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = onSuggestName) {
                            Icon(
                                painter = painterResource(R.drawable.ic_dice),
                                contentDescription = stringResource(R.string.space_name_suggest),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            FormSection(stringResource(R.string.space_section_cover)) {
                CoverField(
                    file = state.coverFile,
                    colorIndex = form.colorIndex,
                    hasCover = form.coverSha256 != null,
                    problem = state.coverProblem,
                    onPick = onPickCover,
                    onRemove = onRemoveCover,
                )
            }

            FormSection(stringResource(R.string.space_section_color)) {
                ColorChoice(
                    selected = form.colorIndex,
                    onSelect = { onChange(form.copy(colorIndex = it)) },
                )
                errorFor(SpaceField.COLOR)?.let { ErrorText(stringResource(it.messageRes())) }
            }

            FormSection(stringResource(R.string.space_section_lifetime)) {
                ChoiceChips(
                    options = LifetimeOption.entries.map { it to stringResource(it.labelRes()) },
                    selected = form.lifetime,
                    onSelect = onLifetime,
                )
                if (form.lifetime == LifetimeOption.UNTIL) {
                    val lifetimeError = errorFor(SpaceField.LIFETIME)
                    UntilPickers(
                        date = form.untilDate,
                        time = form.untilTime,
                        onDate = { onChange(form.copy(untilDate = it)) },
                        onTime = { onChange(form.copy(untilTime = it)) },
                    )
                    if (lifetimeError != null) {
                        ErrorText(stringResource(lifetimeError.messageRes()))
                    } else {
                        SupportText(stringResource(R.string.space_until_support))
                    }
                }
            }

            FormSection(stringResource(R.string.space_section_retention)) {
                ChoiceChips(
                    options = RetentionOption.entries.map { option -> option to stringResource(option.labelRes()) },
                    selected = form.retention,
                    onSelect = { onChange(form.copy(retention = it)) },
                )
                SupportText(stringResource(R.string.space_retention_explainer))
                if (form.retention == RetentionOption.CUSTOM) {
                    val retentionError = errorFor(SpaceField.RETENTION)?.let { stringResource(it.messageRes()) }
                    OutlinedTextField(
                        value = form.retentionCustom,
                        onValueChange = { onChange(form.copy(retentionCustom = it)) },
                        label = { Text(stringResource(R.string.space_retention_custom_label)) },
                        singleLine = true,
                        isError = retentionError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { retentionError?.let { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            FormSection(stringResource(R.string.space_section_storage)) {
                // Harte Grenze (Entscheidung 2): Voreinstellungen über der Obergrenze sind ausgegraut, eigene Werte sind ein Fehler.
                val storageError = errorFor(SpaceField.STORAGE)?.let { settingsErrorText(it, storageMax) }
                if (!state.canCreateAnySpace) {
                    ErrorText(stringResource(R.string.space_storage_none_possible))
                } else {
                    SupportText(stringResource(R.string.space_storage_available, formatGib(storageMax)))
                }
                ChoiceChips(
                    options =
                        StorageOption.entries.map { option ->
                            option to
                                (
                                    option.gib?.let { stringResource(R.string.space_storage_gib, it.toString()) }
                                        ?: stringResource(R.string.space_storage_custom)
                                )
                        },
                    selected = form.storage,
                    enabled = { option -> SpaceForm.isStorageAllowed(option, storageMax) },
                    onSelect = { onChange(form.copy(storage = it)) },
                )
                if (StorageOption.entries.any { !SpaceForm.isStorageAllowed(it, storageMax) }) {
                    SupportText(stringResource(R.string.space_storage_not_enough, formatGib(storageMax)))
                }
                if (form.storage == StorageOption.CUSTOM) {
                    OutlinedTextField(
                        value = form.storageCustom,
                        onValueChange = { onChange(form.copy(storageCustom = it)) },
                        label = { Text(stringResource(R.string.space_storage_custom_label)) },
                        singleLine = true,
                        isError = storageError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                storageError?.let { ErrorText(it) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (state.showDiscard) {
        AlertDialog(
            onDismissRequest = onDismissDiscard,
            title = { Text(stringResource(R.string.space_discard_title)) },
            text = { Text(stringResource(R.string.space_discard_text)) },
            confirmButton = {
                TextButton(onClick = onConfirmDiscard) {
                    Text(stringResource(R.string.space_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDiscard) {
                    Text(stringResource(R.string.space_discard_dismiss))
                }
            },
        )
    }

    // Der Würfel ersetzt einen eigenen Namen erst nach Bestätigung (Entscheidung 1).
    state.pendingName?.let { suggestion ->
        AlertDialog(
            onDismissRequest = onDismissName,
            title = { Text(stringResource(R.string.space_name_replace_title)) },
            text = { Text(stringResource(R.string.space_name_replace_text, suggestion)) },
            confirmButton = {
                TextButton(onClick = onConfirmName) {
                    Text(stringResource(R.string.space_name_replace_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissName) {
                    Text(stringResource(R.string.space_name_replace_dismiss))
                }
            },
        )
    }
}

/** Titelbild-Feld: Vorschau, Wählen oder Ändern, Entfernen, und die Meldung, wenn ein Bild nicht geht. */
@Composable
private fun CoverField(
    file: File?,
    colorIndex: Int,
    hasCover: Boolean,
    problem: CoverProblem?,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CoverImage(
            file = file,
            colorIndex = colorIndex,
            contentDescription = if (file != null) stringResource(R.string.space_section_cover) else null,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.size(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!hasCover) {
                SupportText(stringResource(R.string.space_cover_none))
            }
            OutlinedButton(onClick = onPick) {
                Text(stringResource(if (hasCover) R.string.space_cover_change else R.string.space_cover_pick))
            }
            if (hasCover) {
                TextButton(onClick = onRemove) {
                    Text(stringResource(R.string.space_cover_remove))
                }
            }
        }
    }
    problem?.let { ErrorText(stringResource(it.messageRes())) }
}

private fun CoverProblem.messageRes(): Int =
    when (this) {
        CoverProblem.UNSUPPORTED -> R.string.space_cover_error_type
        CoverProblem.TOO_LARGE -> R.string.space_cover_error_too_large
        CoverProblem.LOCATION -> R.string.space_cover_error_location
        CoverProblem.GENERIC -> R.string.space_cover_error_generic
    }

/** Farbauswahl: acht Kreise in einer Gruppe. Jeder Kreis hat einen Namen für die Vorlesefunktion. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorChoice(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SpaceAccents.colors.forEachIndexed { index, color ->
            val isSelected = index == selected
            val name = stringResource(colorNameRes(index))
            val rim = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(width = if (isSelected) 3.dp else 1.dp, color = rim, shape = CircleShape)
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onSelect(index) },
                        ).semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Box(
                        modifier =
                            Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(SpaceAccents.onAccent),
                    )
                }
            }
        }
    }
}

/**
 * Auswahl aus festen Werten als Chips in einer Gruppe. [enabled] sperrt einzelne Werte, sie bleiben sichtbar und
 * ausgegraut (Speicherlimit über der Obergrenze).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceChips(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: (T) -> Boolean = { true },
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (option, label) ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label) },
                enabled = enabled(option),
            )
        }
    }
}

@Composable
private fun FormSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun SupportText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
