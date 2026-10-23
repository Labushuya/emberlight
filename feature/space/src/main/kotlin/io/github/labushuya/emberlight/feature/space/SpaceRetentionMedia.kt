package io.github.labushuya.emberlight.feature.space

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.labushuya.emberlight.feature.space.media.HostMediaViewModel
import io.github.labushuya.emberlight.feature.space.run.HostMediaDetail
import io.github.labushuya.emberlight.feature.space.run.HostMediaSection
import io.github.labushuya.emberlight.share.space.SpaceId

/**
 * Medienraster der Aufbewahrung (Paket 3.6b): dieselbe Ansicht wie im laufenden Space (3.5c), aber nur lesend. Entfernen und
 * Übernehmen gibt es hier nicht. Eine Kachel öffnet die Detailansicht ohne Entfernen-Knopf, wenn die Datei lokal vorliegt.
 * Fehlt sie, ist die Kachel weder klickbar noch eine Schaltfläche.
 */
@Composable
internal fun RetentionMediaSection(
    spaceId: SpaceId,
    modifier: Modifier = Modifier,
    viewModel: HostMediaViewModel = hiltViewModel(),
) {
    LaunchedEffect(spaceId) { viewModel.show(spaceId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var openMediaId by rememberSaveable { mutableStateOf<String?>(null) }
    openMediaId?.let { mediaId ->
        state.items.firstOrNull { it.id == mediaId }?.let { item ->
            HostMediaDetail(
                item = item,
                loadDetail = { viewModel.detailOf(mediaId) },
                editable = false,
                onDelete = {},
                onDismiss = { openMediaId = null },
            )
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.space_running_media_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.space_retention_media_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        HostMediaSection(
            state = state,
            onFilter = viewModel::setFilter,
            onOpen = { openMediaId = it.id },
            isOpenable = { it.detailReady },
        )
    }
}
