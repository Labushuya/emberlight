package io.github.labushuya.emberlight.feature.gallery.album

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.labushuya.emberlight.core.media.model.Album
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.feature.gallery.R
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaGridState
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaThumbnailImage
import io.github.labushuya.emberlight.feature.gallery.timeline.SCRIM
import io.github.labushuya.emberlight.feature.gallery.timeline.accessBannerItem
import io.github.labushuya.emberlight.feature.gallery.timeline.needsAccessBanner
import java.text.NumberFormat

/** Mindestbreite einer Albumkarte. Auf dem Handy passen zwei Spalten, auf Faltgeräten und Tablets mehr. */
internal val ALBUM_MIN_WIDTH: Dp = 160.dp
private val ALBUM_GAP: Dp = 12.dp
private val ALBUM_PADDING: Dp = 16.dp

private const val TYPE_ALBUM = "album"
private const val TYPE_BANNER = "banner"
private const val KEY_BANNER = "access-banner"
private const val KEY_OTHER = "album-other"

/** Testmarken für Karte und Video-Kennzeichnung. Die Compose-Tests prüfen darüber Reihenfolge und Video-Cover. */
internal const val ALBUM_CARD_TAG = "album-card"
internal const val ALBUM_VIDEO_BADGE_TAG = "album-video-badge"

/**
 * Alben-Übersicht: ein Raster quadratischer Karten, neueste zuerst, „Sonstige“ zuletzt. Der Hinweis-Banner steht oben wie in
 * der Zeitleiste. Antippen meldet [onAlbumClick] mit der Ordner-ID (`null` für „Sonstige“).
 */
@Composable
internal fun AlbumGrid(
    albums: AlbumList,
    grid: MediaGridState.Content,
    padding: PaddingValues,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onAlbumClick: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val hasBanner = needsAccessBanner(grid.access, grid.missingTypes)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = ALBUM_MIN_WIDTH),
        state = gridState,
        // Das Raster beginnt unter der Kopfzeile, nicht dahinter: Der Rand des Scaffolds liegt im Modifier (siehe MediaGrid).
        modifier = modifier.fillMaxSize().padding(padding),
        contentPadding =
            PaddingValues(
                start = ALBUM_PADDING,
                end = ALBUM_PADDING,
                top = ALBUM_PADDING,
                bottom = ALBUM_PADDING,
            ),
        horizontalArrangement = Arrangement.spacedBy(ALBUM_GAP),
        verticalArrangement = Arrangement.spacedBy(ALBUM_GAP),
    ) {
        if (hasBanner) {
            accessBannerItem(
                key = KEY_BANNER,
                contentType = TYPE_BANNER,
                access = grid.access,
                missingTypes = grid.missingTypes,
                onRequest = onRequest,
                onOpenSettings = onOpenSettings,
            )
        }
        items(
            items = albums.items,
            key = { album -> album.bucketId ?: KEY_OTHER },
            contentType = { TYPE_ALBUM },
        ) { album ->
            AlbumCard(album = album, onClick = { onAlbumClick(album.bucketId) })
        }
    }
}

/**
 * Eine Albumkarte: quadratisches Titelbild (das neueste Element) mit abgerundeten Ecken, darunter Name und Anzahl. Ist das
 * Titelbild ein Video, markiert ein Symbol das. Die ganze Karte ist ein Button mit einer Beschreibung, etwa „Kamera, 1.234
 * Elemente“.
 */
@Composable
internal fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = albumTitle(album)
    val count = itemCountText(album.itemCount)
    val label = stringResource(R.string.feature_gallery_album_label, title, count)
    Column(
        modifier =
            modifier
                .testTag(ALBUM_CARD_TAG)
                .semantics(mergeDescendants = true) { contentDescription = label }
                .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.medium),
        ) {
            MediaThumbnailImage(uri = album.cover.uri, type = album.cover.type, modifier = Modifier.fillMaxSize())
            if (album.cover.type == MediaType.VIDEO) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .size(22.dp)
                            .testTag(ALBUM_VIDEO_BADGE_TAG)
                            .background(SCRIM, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_play_arrow),
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = count,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Anzahl mit Plural und Tausendertrennzeichen der Sprache, etwa „1.234 Elemente“ oder „1,234 items“. */
@Composable
private fun itemCountText(count: Int): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    return pluralStringResource(R.plurals.feature_gallery_album_items, count, formatter.format(count))
}
