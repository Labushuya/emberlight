package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.feature.gallery.R
import java.time.ZoneId

/** Abstand zwischen Zellen und an den Rändern des Rasters. */
internal val GRID_GAP: Dp = 2.dp

private const val TYPE_HEADER = "header"
private const val TYPE_MEDIA = "media"
private const val TYPE_BANNER = "banner"
private const val KEY_BANNER = "access-banner"

/**
 * Zeitleiste ohne eigene Zustandslogik: zeichnet [state] und meldet Aktionen nach außen. Dadurch sind alle Zustände
 * mit festen Daten testbar und in Goldens festhaltbar. Der Zustand kommt aus [GalleryScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryContent(
    state: TimelineUiState,
    columns: Int,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val locale = LocalConfiguration.current.locales[0]
    val formats = remember(locale) { TimelineFormats(locale, ZoneId.systemDefault()) }
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.feature_gallery_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        when (state) {
            TimelineUiState.Loading -> {
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }

            is TimelineUiState.PermissionRequired -> {
                PermissionPrompt(
                    canRequest = state.canRequest,
                    onRequest = onRequestPermission,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.padding(padding),
                )
            }

            is TimelineUiState.Empty -> {
                EmptyTimeline(
                    access = state.access,
                    missingTypes = state.missingTypes,
                    onRequest = onRequestPermission,
                    onOpenSettings = onOpenSettings,
                    padding = padding,
                )
            }

            is TimelineUiState.Content -> {
                TimelineGrid(
                    state = state,
                    columns = columns,
                    formats = formats,
                    onRequest = onRequestPermission,
                    onOpenSettings = onOpenSettings,
                    onMediaClick = onMediaClick,
                    padding = padding,
                )
            }
        }
    }
}

@Composable
private fun TimelineGrid(
    state: TimelineUiState.Content,
    columns: Int,
    formats: TimelineFormats,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    padding: PaddingValues,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = GRID_GAP,
                end = GRID_GAP,
                top = padding.calculateTopPadding() + GRID_GAP,
                bottom = padding.calculateBottomPadding() + GRID_GAP,
            ),
        horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
        verticalArrangement = Arrangement.spacedBy(GRID_GAP),
    ) {
        if (state.access == MediaAccess.PARTIAL || state.missingTypes.isNotEmpty()) {
            item(key = KEY_BANNER, span = { GridItemSpan(maxLineSpan) }, contentType = TYPE_BANNER) {
                AccessBanner(
                    access = state.access,
                    missingTypes = state.missingTypes,
                    onRequest = onRequest,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
        state.sections.forEach { section ->
            item(key = section.key, span = { GridItemSpan(maxLineSpan) }, contentType = TYPE_HEADER) {
                SectionHeader(header = section.header, formats = formats)
            }
            items(
                count = section.items.size,
                key = { index -> section.items[index].uri },
                contentType = { TYPE_MEDIA },
            ) { index ->
                val item = section.items[index]
                MediaCell(
                    item = item,
                    label = mediaLabel(item, formats),
                    onClick = { onMediaClick(item) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(
    header: TimelineHeader,
    formats: TimelineFormats,
) {
    val text =
        when (header) {
            TimelineHeader.Today -> stringResource(R.string.feature_gallery_header_today)
            TimelineHeader.Yesterday -> stringResource(R.string.feature_gallery_header_yesterday)
            is TimelineHeader.Day -> formats.dayHeader(header.date, header.withYear)
            is TimelineHeader.Month -> formats.monthHeader(header.yearMonth)
        }
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 20.dp, bottom = 8.dp)
                .semantics { heading() },
    )
}

@Composable
private fun mediaLabel(
    item: MediaItem,
    formats: TimelineFormats,
): String {
    val date = formats.longDate(item.timestamp)
    return if (item.type == MediaType.VIDEO) {
        stringResource(R.string.feature_gallery_cell_video, formatDuration(item.durationMs ?: 0L), date)
    } else {
        stringResource(R.string.feature_gallery_cell_photo, date)
    }
}

@Composable
private fun EmptyTimeline(
    access: MediaAccess,
    missingTypes: Set<MediaType>,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    padding: PaddingValues,
) {
    Column(modifier = Modifier.padding(padding).fillMaxSize()) {
        if (access == MediaAccess.PARTIAL || missingTypes.isNotEmpty()) {
            AccessBanner(
                access = access,
                missingTypes = missingTypes,
                onRequest = onRequest,
                onOpenSettings = onOpenSettings,
            )
        }
        EmptyMessage(modifier = Modifier.weight(1f))
    }
}
