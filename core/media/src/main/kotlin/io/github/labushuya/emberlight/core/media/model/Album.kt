package io.github.labushuya.emberlight.core.media.model

import androidx.compose.runtime.Immutable

/**
 * Album aus einem MediaStore-Ordner (BUCKET_ID).
 *
 * [bucketId] `null` markiert das Sammelalbum „Sonstige“ für Einträge ohne Ordner. Der Anzeigename kommt dann aus
 * `strings.xml` der UI, [name] ist dort leer. Bei Ordnern ohne bekannten Namen ist [name] ebenfalls leer.
 * [cover] ist das neueste Element des Albums.
 */
@Immutable
data class Album(
    val bucketId: Long?,
    val name: String,
    val cover: MediaItem,
    val itemCount: Int,
    val latestTimestamp: Long,
)
