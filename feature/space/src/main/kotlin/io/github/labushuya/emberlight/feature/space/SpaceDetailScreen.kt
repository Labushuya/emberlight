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
import io.github.labushuya.emberlight.share.space.retention.RetentionRules
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
    onTemplate: (String) -> Unit = {},
    onExport: (String) -> Unit = {},
    viewModel: SpaceDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val purgeFailed by viewModel.failure.collectAsStateWithLifecycle()
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
            onChangeRetention = { days -> viewModel.changeRetention(days) },
            onPurge = { viewModel.purge(onDone = onBack) },
            onTemplate = onTemplate,
            onExport = onExport,
            onRemove = { viewModel.removeFromList(onDone = onBack) },
            notificationsAllowed = viewModel.notificationsAllowed,
            purgeFailed = purgeFailed,
            confirmPurgeOnOpen = viewModel.confirmPurgeRequested,
            onStart = onStart,
            onSetup = onSetup,
            onOpenRunning = onOpenRunning,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * Detail mit Zustand: Auswahl, Laden, nicht mehr vorhanden. Die Liste auf großen Displays und die Route nutzen dasselbe.
 * Für beendete Spaces kommen Frist ändern, Jetzt löschen und das Medienraster der Aufbewahrung dazu (Paket 3.6b).
 */
@Composable
internal fun SpaceDetailPane(
    state: SpaceDetailUiState,
    coverFile: (SpaceRecord?) -> File?,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
    onStart: (String) -> Unit,
    onSetup: (String) -> Unit,
    onOpenRunning: (String) -> Unit,
    modifier: Modifier = Modifier,
    onChangeRetention: (Int) -> Unit = {},
    onPurge: () -> Unit = {},
    notificationsAllowed: Boolean = true,
    purgeFailed: Boolean = false,
    confirmPurgeOnOpen: Boolean = false,
    onTemplate: (String) -> Unit = {},
    onExport: (String) -> Unit = {},
    onRemove: () -> Unit = {},
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
                onTemplate = { onTemplate(record.id.value) },
                onExport = { onExport(record.id.value) },
                onRemove = onRemove,
                onChangeRetention = onChangeRetention,
                onPurge = onPurge,
                notificationsAllowed = notificationsAllowed,
                purgeFailed = purgeFailed,
                confirmPurgeOnOpen = confirmPurgeOnOpen,
                media = { RetentionMediaSection(record.id) },
                modifier = modifier,
            )
        }
    }
}

/**
 * Zusammenfassung eines Space. Bearbeiten gibt es nur im Entwurf. Beendete Spaces zeigen die Aufbewahrung mit dem Löschdatum,
 * „Frist ändern“ und „Jetzt löschen“ (nur nach Bestätigung). Das Löschen laufender Spaces ist nicht möglich.
 */
@Composable
internal fun SpaceDetailContent(
    record: SpaceRecord,
    coverFile: File?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onStart: () -> Unit = {},
    onSetup: () -> Unit = {},
    onOpenRunning: () -> Unit = {},
    onChangeRetention: (Int) -> Unit = {},
    onPurge: () -> Unit = {},
    notificationsAllowed: Boolean = true,
    purgeFailed: Boolean = false,
    confirmPurgeOnOpen: Boolean = false,
    onTemplate: () -> Unit = {},
    onExport: () -> Unit = {},
    onRemove: () -> Unit = {},
    media: @Composable () -> Unit = {},
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var changingRetention by rememberSaveable { mutableStateOf(false) }
    var confirmPurge by rememberSaveable { mutableStateOf(confirmPurgeOnOpen) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val settings = record.settings
    val editable = record.status == SpaceStatus.DRAFT
    val ended = record.status == SpaceStatus.ENDED
    val untilMillis =
        record.endedAt?.let {
            RetentionRules.effectiveUntil(it, settings.retentionDays, record.retentionUntil)
        }
    val sofort = settings.retentionDays == 0 && record.retentionUntil == null
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
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.space_export_entry))
                    }
                }

                SpaceStatus.ENDED -> {
                    Text(
                        text =
                            stringResource(
                                R.string.space_detail_ended_at,
                                formatDate(record.endedAt ?: record.updatedAt),
                            ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text =
                            when {
                                record.purging -> {
                                    stringResource(R.string.space_retention_purging)
                                }

                                sofort -> {
                                    stringResource(R.string.space_retention_until_sofort)
                                }

                                untilMillis != null -> {
                                    stringResource(R.string.space_retention_until, formatDate(untilMillis))
                                }

                                else -> {
                                    ""
                                }
                            },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (!notificationsAllowed) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.space_retention_notifications_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!record.purging) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.space_export_entry))
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { changingRetention = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.space_retention_change))
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onTemplate, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.space_template_action))
                        }
                    }
                    media()
                }

                SpaceStatus.DELETED -> {
                    record.deletedAt?.let {
                        Text(
                            text = stringResource(R.string.space_deleted_at, formatDate(it)),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.space_deleted_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onTemplate, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.space_template_action))
                    }
                }
            }
            if (!editable) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onEdit, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.space_detail_edit))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.space_detail_not_editable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (ended && !record.purging) {
                OutlinedButton(
                    onClick = { confirmPurge = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.space_retention_purge))
                }
                if (purgeFailed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.space_retention_purge_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else if (record.status == SpaceStatus.DRAFT) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.space_detail_delete))
                }
            } else if (record.status == SpaceStatus.DELETED) {
                OutlinedButton(
                    onClick = { confirmRemove = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.space_list_remove))
                }
            }
        }

        if (changingRetention) {
            val currentDays =
                record.retentionUntil?.let { until ->
                    val remaining = (until - System.currentTimeMillis()).coerceAtLeast(0L)
                    (remaining / RetentionRules.DAY_MILLIS).toInt().coerceIn(0, RetentionRules.MAX_DAYS)
                } ?: settings.retentionDays
            RetentionChangeDialog(
                currentDays = currentDays,
                onConfirm = { days ->
                    changingRetention = false
                    onChangeRetention(days)
                },
                onDismiss = { changingRetention = false },
            )
        }

        if (confirmPurge) {
            RetentionPurgeDialog(
                name = settings.name,
                onConfirm = {
                    confirmPurge = false
                    onPurge()
                },
                onDismiss = { confirmPurge = false },
            )
        }

        if (confirmRemove) {
            AlertDialog(
                onDismissRequest = { confirmRemove = false },
                title = { Text(stringResource(R.string.space_list_remove_title)) },
                text = { Text(stringResource(R.string.space_list_remove_text, settings.name)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmRemove = false
                            onRemove()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(stringResource(R.string.space_list_remove_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmRemove = false }) {
                        Text(stringResource(R.string.space_cancel))
                    }
                },
            )
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
