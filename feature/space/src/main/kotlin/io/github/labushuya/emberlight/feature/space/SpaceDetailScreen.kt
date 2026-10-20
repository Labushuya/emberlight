package io.github.labushuya.emberlight.feature.space

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import java.io.File

/** Route des Detail auf dem Handy. Auf großen Displays zeigt die Liste dasselbe Detail neben sich ([SpaceDetailPane]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpaceDetailScreen(
    modifier: Modifier = Modifier,
    onEdit: (String) -> Unit = {},
    onBack: () -> Unit = {},
    onStart: (String) -> Unit = {},
    onSetup: (String) -> Unit = {},
    onOpenRunning: (String) -> Unit = {},
    viewModel: SpaceDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.space_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        SpaceDetailPane(
            state = state,
            coverFile = viewModel::coverFile,
            onEdit = onEdit,
            onDelete = { viewModel.delete(onDone = onBack) },
            onStart = onStart,
            onSetup = onSetup,
            onOpenRunning = onOpenRunning,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * Detail mit Zustand: Auswahl, Laden, nicht mehr vorhanden. Die Liste auf großen Displays und die Route nutzen dasselbe.
 */
@Composable
internal fun SpaceDetailPane(
    state: SpaceDetailUiState,
    coverFile: (SpaceRecord?) -> File?,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
    onStart: (String) -> Unit = {},
    onSetup: (String) -> Unit = {},
    onOpenRunning: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val record = state.record
    when {
        state.selected == null && record == null -> {
            CenteredMessage(stringResource(R.string.space_detail_select), modifier)
        }

        record == null && state.loading -> {
            Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        }

        record == null -> {
            CenteredMessage(stringResource(R.string.space_detail_missing), modifier)
        }

        else -> {
            SpaceDetailContent(
                record = record,
                coverFile = coverFile(record),
                onEdit = { onEdit(record.id.value) },
                onDelete = onDelete,
                onStart = { onStart(record.id.value) },
                onSetup = { onSetup(record.id.value) },
                onOpenRunning = { onOpenRunning(record.id.value) },
                modifier = modifier,
            )
        }
    }
}

/** Zusammenfassung eines Space. Bearbeiten gibt es nur im Entwurf, Löschen immer, mit Bestätigung. */
@Composable
internal fun SpaceDetailContent(
    record: SpaceRecord,
    coverFile: File?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onStart: () -> Unit = {},
    onSetup: () -> Unit = {},
    onOpenRunning: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val settings = record.settings
    val editable = record.status == SpaceStatus.DRAFT
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            CoverImage(
                file = coverFile,
                colorIndex = settings.colorIndex,
                contentDescription = stringResource(R.string.space_card_cover_description, settings.name),
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = settings.name,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = statusLabel(record.status),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.space_detail_summary),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            DetailRow(stringResource(R.string.space_detail_color), stringResource(colorNameRes(settings.colorIndex)))
            DetailRow(stringResource(R.string.space_detail_lifetime), lifetimeText(settings.lifetime))
            DetailRow(stringResource(R.string.space_detail_retention), retentionText(settings.retentionDays))
            DetailRow(stringResource(R.string.space_detail_limit), limitText(settings.storageLimitBytes))
            Spacer(Modifier.height(24.dp))
            when (record.status) {
                SpaceStatus.DRAFT -> {
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.space_detail_start))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onSetup, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.space_detail_setup))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.space_detail_edit))
                    }
                }
                SpaceStatus.ACTIVE -> {
                    Button(onClick = onOpenRunning, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.space_detail_open_running))
                    }
                }
                SpaceStatus.ENDED -> {
                    Text(
                        text = stringResource(R.string.space_detail_ended_at, formatDate(record.endedAt ?: record.updatedAt)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text =
                            stringResource(
                                R.string.space_detail_retention_info,
                                retentionText(settings.retentionDays),
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SpaceStatus.DELETED -> Unit
            }
            if (!editable) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.space_detail_not_editable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (record.status != SpaceStatus.ACTIVE) OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.space_detail_delete))
            }
        }

        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(stringResource(R.string.space_delete_title)) },
                text = { Text(stringResource(R.string.space_delete_text, settings.name)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmDelete = false
                            onDelete()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(stringResource(R.string.space_delete_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) {
                        Text(stringResource(R.string.space_cancel))
                    }
                },
            )
        }
    }
}

/** Eine Zeile der Zusammenfassung: Bezeichnung und Wert, als eine Aussage vorgelesen. */
@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.6f),
        )
    }
}

@Composable
private fun CenteredMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
