package io.github.labushuya.emberlight.feature.gallery.album

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.labushuya.emberlight.core.media.model.Album
import io.github.labushuya.emberlight.feature.gallery.R

/**
 * Anzeigename eines Albums. „Sonstige“ für Einträge ohne Ordner, „Album ohne Namen“ für einen Ordner ohne Namen. Ist das
 * Album nicht (mehr) vorhanden, etwa in der Album-Ansicht nach dem Löschen aller Einträge, steht ein allgemeiner Titel.
 */
@Composable
internal fun albumTitle(album: Album?): String =
    when {
        album == null -> stringResource(R.string.feature_gallery_album_fallback_title)
        album.bucketId == null -> stringResource(R.string.feature_gallery_album_other)
        album.name.isBlank() -> stringResource(R.string.feature_gallery_album_unnamed)
        else -> album.name
    }
