package io.github.labushuya.emberlight.feature.space.export

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.labushuya.emberlight.feature.space.R

/**
 * Auswahl der Medien zur Übernahme in die Galerie (Paket 3.7, ADR 0022). Raster mit Häkchen, „Alle“ und „Nichts“, Zähler und
 * Größe, Knopf „Übernehmen“, Fortschritt und Ergebnis. Bereits übernommene Medien sind gekennzeichnet und nicht wählbar.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SpaceExportScreen(
    onBack: () -> Unit = {},
    viewModel: SpaceExportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SpaceExportContent(
        state = state,
        onBack = onBack,
        onAll = viewModel::selectAll,
        onNone = viewModel::selectNone,
        onToggle = viewModel::toggle,
        onStart = viewModel::start,
        onCancel = viewModel::cancel,
    )
}

/** Zustandslose Oberfläche der Auswahl. Der Einstieg ist [SpaceExportScreen]; Goldens und Tests nutzen diesen Teil direkt. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SpaceExportContent(
    state: SpaceExportUiState,
    onBack: () -> Unit,
    onAll: () -> Unit,
    onNone: () -> Unit,
    onToggle: (String) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.space_export_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = stringResource(R.string.space_export_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ControlsRow(
                selectedCount = state.selectedCount,
                selectedBytes = state.selectedBytes,
                withLocation = state.withLocationCount,
                onAll = onAll,
                onNone = onNone,
            )
            HorizontalDivider()
            when {
                state.loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }

                state.items.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.space_export_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp),
                    )
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 136.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.items, key = { it.mediaId }) { item ->
                            MediaTile(item = item, onToggle = { onToggle(item.mediaId) })
                        }
                    }
                }
            }
            PhaseArea(
                phase = state.phase,
                canStart = state.canStart,
                onStart = onStart,
                onCancel = onCancel,
            )
        }
    }
}

@Composable
private fun ControlsRow(
    selectedCount: Int,
    selectedBytes: Long,
    withLocation: Int,
    onAll: () -> Unit,
    onNone: () -> Unit,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAll, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.space_export_all))
            }
            OutlinedButton(onClick = onNone, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.space_export_none))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text =
                stringResource(
                    R.string.space_export_summary,
                    selectedCount,
                    Formatter.formatShortFileSize(context, selectedBytes),
                ),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (withLocation > 0) {
            Text(
                text = stringResource(R.string.space_export_location),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MediaTile(
    item: ExportItemUi,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val kind = stringResource(if (item.isVideo) R.string.space_export_kind_video else R.string.space_export_kind_image)
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !item.exported, role = Role.Checkbox, onClick = onToggle),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = item.selected,
                    onCheckedChange = null,
                    enabled = !item.exported,
                )
                Spacer(Modifier.width(4.dp))
                Text(text = kind, style = MaterialTheme.typography.labelLarge)
            }
            Text(
                text = Formatter.formatShortFileSize(context, item.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.locationKept) {
                Text(
                    text = stringResource(R.string.space_export_location),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (item.exported) {
                Text(
                    text = stringResource(R.string.space_export_already),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun PhaseArea(
    phase: ExportPhase,
    canStart: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        when (phase) {
            ExportPhase.Idle -> {}

            is ExportPhase.Running -> {
                val fraction =
                    if (phase.totalBytes > 0) (phase.doneBytes.toFloat() / phase.totalBytes).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text(
                    text =
                        stringResource(
                            R.string.space_export_progress,
                            phase.doneFiles,
                            phase.totalFiles,
                            Formatter.formatShortFileSize(context, phase.doneBytes),
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.space_export_cancel))
                }
            }

            is ExportPhase.NoSpace -> {
                Text(
                    text =
                        stringResource(
                            R.string.space_export_no_space,
                            Formatter.formatShortFileSize(context, phase.requiredBytes),
                            Formatter.formatShortFileSize(context, phase.availableBytes),
                        ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is ExportPhase.Finished -> {
                val result = phase.result
                Text(
                    text =
                        if (result.stoppedByPurge) {
                            stringResource(R.string.space_export_done_purged, result.exported)
                        } else {
                            stringResource(R.string.space_export_done, result.exported, result.skipped, result.failed)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (result.failedHash > 0) {
                    Text(
                        text = stringResource(R.string.space_export_failed_hash),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            ExportPhase.Cancelled -> {
                Text(stringResource(R.string.space_export_cancelled), style = MaterialTheme.typography.bodyMedium)
            }

            ExportPhase.Failed -> {
                Text(
                    stringResource(R.string.space_export_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            ExportPhase.NotExportable -> {
                Text(
                    stringResource(R.string.space_export_not_exportable),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (phase !is ExportPhase.Running && phase != ExportPhase.NotExportable) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onStart, enabled = canStart, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.space_export_start))
            }
        }
    }
}
