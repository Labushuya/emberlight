package io.github.labushuya.emberlight.feature.space

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import io.github.labushuya.emberlight.share.space.retention.RetentionRules
import java.io.File
import io.github.labushuya.emberlight.core.design.R as DesignR

/** Ab dieser Breite stehen Liste und Detail nebeneinander (wie die Navigationsleiste der App, ADR 0002). */
private val WIDE_LAYOUT_MIN_WIDTH = 600.dp

/** Testmarke für die Liste. Die Oberflächentests suchen darüber die Karten. */
internal const val SPACES_LIST_TAG = "spaces-list"

/**
 * Spaces-Liste mit Zustand. Auf dem Handy öffnet Antippen das Detail als eigenes Ziel. Ab 600 dp Breite steht das
 * Detail neben der Liste. Der gewählte Space bleibt über Drehungen erhalten.
 */
@Composable
internal fun SpacesScreen(
    onStart: (String) -> Unit,
    onSetup: (String) -> Unit,
    onOpenRunning: (String) -> Unit,
    modifier: Modifier = Modifier,
    onCreate: () -> Unit = {},
    onOpen: (String) -> Unit = {},
    onEdit: (String) -> Unit = {},
    onTemplate: (String) -> Unit = {},
    viewModel: SpacesListViewModel = hiltViewModel(),
    detailViewModel: SpaceDetailViewModel = hiltViewModel(),
) {
    val spaces by viewModel.spaces.collectAsStateWithLifecycle()
    val detail by detailViewModel.state.collectAsStateWithLifecycle()
    val purgeFailed by detailViewModel.failure.collectAsStateWithLifecycle()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_LAYOUT_MIN_WIDTH
        var selectedId by rememberSaveable { mutableStateOf<String?>(null) }

        LaunchedEffect(wide, spaces) {
            // Auf großen Displays ist immer ein Space gewählt, solange es einen gibt.
            if (wide && spaces.none { it.id.value == selectedId }) selectedId = spaces.firstOrNull()?.id?.value
        }
        LaunchedEffect(selectedId, wide) {
            if (wide) detailViewModel.select(selectedId)
        }

        if (wide) {
            Row(modifier = Modifier.fillMaxSize()) {
                SpacesListContent(
                    spaces = spaces,
                    selectedId = selectedId,
                    coverFile = viewModel::coverFile,
                    onSelect = { selectedId = it.id.value },
                    onCreate = onCreate,
                    modifier = Modifier.weight(0.4f).fillMaxHeight(),
                )
                VerticalDivider(modifier = Modifier.fillMaxHeight())
                Column(modifier = Modifier.weight(0.6f).fillMaxHeight()) {
                    SpaceDetailPane(
                        state = detail,
                        coverFile = detailViewModel::coverFile,
                        onEdit = onEdit,
                        onDelete = { detailViewModel.delete(onDone = {}) },
                        onChangeRetention = { days -> detailViewModel.changeRetention(days) },
                        onPurge = { detailViewModel.purge(onDone = {}) },
                        onTemplate = onTemplate,
                        onRemove = { detailViewModel.removeFromList(onDone = {}) },
                        notificationsAllowed = detailViewModel.notificationsAllowed,
                        purgeFailed = purgeFailed,
                        confirmPurgeOnOpen = detailViewModel.confirmPurgeRequested,
                        onStart = onStart,
                        onSetup = onSetup,
                        onOpenRunning = onOpenRunning,
                    )
                }
            }
        } else {
            SpacesListContent(
                spaces = spaces,
                selectedId = null,
                coverFile = viewModel::coverFile,
                onSelect = { onOpen(it.id.value) },
                onCreate = onCreate,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Liste der Spaces ohne Zustand, damit Goldens und Tests sie direkt aufrufen können. Ohne Einträge erklärt der
 * Leerzustand, was ein Space ist, und bietet das Anlegen an.
 */
@Composable
internal fun SpacesListContent(
    spaces: List<SpaceRecord>,
    selectedId: String?,
    coverFile: (SpaceRecord) -> File?,
    onSelect: (SpaceRecord) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            if (spaces.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onCreate,
                    icon = { Icon(painter = painterResource(R.drawable.ic_add), contentDescription = null) },
                    text = { Text(stringResource(R.string.space_create)) },
                )
            }
        },
    ) { padding ->
        if (spaces.isEmpty()) {
            SpacesEmptyState(onCreate = onCreate, modifier = Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).testTag(SPACES_LIST_TAG),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(spaces, key = { it.id.value }) { record ->
                    SpaceCard(
                        record = record,
                        coverFile = coverFile(record),
                        selected = record.id.value == selectedId,
                        onClick = { onSelect(record) },
                    )
                }
            }
        }
    }
}

/** Leerzustand: Erklärung und Knopf zum Anlegen. */
@Composable
private fun SpacesEmptyState(
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(DesignR.drawable.ic_groups_filled),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.space_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.space_empty_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCreate) {
            Text(stringResource(R.string.space_create))
        }
    }
}

/** Karte eines Space: Titelbild oder Farbe, Name, Status, Laufzeit und Speicherlimit. Antippen wählt den Space. */
@Composable
private fun SpaceCard(
    record: SpaceRecord,
    coverFile: File?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val name = record.settings.name
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
            ),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(
                file = coverFile,
                colorIndex = record.settings.colorIndex,
                contentDescription = stringResource(R.string.space_card_cover_description, name),
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = statusLabel(record.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.space_card_lifetime, lifetimeText(record.settings.lifetime)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.space_card_limit, limitText(record.settings.storageLimitBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Gelöschte Spaces bleiben in der Liste mit dem Löschdatum (Paket 3.6c).
                if (record.status == SpaceStatus.DELETED) {
                    record.deletedAt?.let {
                        Text(
                            text = stringResource(R.string.space_deleted_at, formatDate(it)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Beendete Spaces zeigen das Löschdatum, sobald die Aufbewahrung läuft (Paket 3.6b).
                if (record.status == SpaceStatus.ENDED && !record.purging) {
                    val until =
                        record.endedAt?.let {
                            RetentionRules.effectiveUntil(it, record.settings.retentionDays, record.retentionUntil)
                        }
                    if (until != null) {
                        Text(
                            text = stringResource(R.string.space_retention_list_until, formatDate(until)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
