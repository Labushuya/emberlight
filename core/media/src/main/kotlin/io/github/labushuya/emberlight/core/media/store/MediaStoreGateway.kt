package io.github.labushuya.emberlight.core.media.store

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.labushuya.emberlight.core.media.index.MediaIndex
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

/**
 * Naht zu MediaStore. Die Repository-Logik hängt nur an dieser Schnittstelle, damit Tests sie durch eine
 * Fassung ohne Gerät ersetzen können.
 */
internal interface MediaStoreGateway {
    /** SDK-Level, bestimmt Berechtigungs- und Abfrageregeln. */
    val sdkInt: Int

    /**
     * Liest alle sichtbaren Einträge eines Typs, unsortiert. Wirft [SecurityException], wenn die Berechtigung fehlt.
     */
    fun query(type: MediaType): List<MediaItem>

    /** MediaStore-Generation des Volumes (ab API 30). `null`, wenn sie nicht verfügbar ist. */
    fun generation(): Long?

    /** Signal bei jeder Änderung an einer der beiden Collections. */
    fun changes(): Flow<Unit>
}

/**
 * Android-Implementierung über `ContentResolver`. Alle API-abhängigen Aufrufe stehen hier, mit `Build.VERSION`-Prüfung.
 * Die Auswahl der Felder und die Logik stehen in [MediaStoreQuery] und [MediaIndex].
 */
internal class ContentResolverMediaStoreGateway
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : MediaStoreGateway {
        override val sdkInt: Int = Build.VERSION.SDK_INT

        override fun query(type: MediaType): List<MediaItem> {
            val collection = collectionUri(type)
            val cursor =
                context.contentResolver.query(
                    collection,
                    MediaStoreQuery.projection(type, sdkInt),
                    MediaStoreQuery.selection(sdkInt),
                    null,
                    null,
                ) ?: return emptyList()
            return cursor.use { c ->
                val columns = CursorColumns(c)
                buildList(c.count) {
                    while (c.moveToNext()) add(columns.read(c, type, collection))
                }
            }
        }

        override fun generation(): Long? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            return try {
                MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL)
            } catch (e: IllegalArgumentException) {
                // Unbekanntes Volume: „unbekannt“ statt Fehler. Die Abfrage läuft dann bei jeder Änderung.
                null
            } catch (e: SecurityException) {
                // Ohne Berechtigung meldet die Generation nichts. Die eigentliche Antwort kommt aus query().
                null
            }
        }

        override fun changes(): Flow<Unit> =
            callbackFlow {
                val observer =
                    object : android.database.ContentObserver(null) {
                        override fun onChange(
                            selfChange: Boolean,
                            uri: Uri?,
                        ) {
                            trySend(Unit)
                        }
                    }
                val resolver = context.contentResolver
                MediaType.entries.forEach { type ->
                    resolver.registerContentObserver(collectionUri(type), true, observer)
                }
                awaitClose { resolver.unregisterContentObserver(observer) }
            }

        /**
         * Collection-URI je Typ. Ab API 29 über `VOLUME_EXTERNAL`, also alle externen Volumes. Davor
         * `EXTERNAL_CONTENT_URI`.
         */
        private fun collectionUri(type: MediaType): Uri {
            val allVolumes = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            return when (type) {
                MediaType.IMAGE -> {
                    if (allVolumes) {
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    } else {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }
                }

                MediaType.VIDEO -> {
                    if (allVolumes) {
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    } else {
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    }
                }
            }
        }
    }

/**
 * Spaltenindizes einmal je Cursor auflösen. Pflichtspalten (`_id`) brechen bei Fehlen ab. Optionale Spalten liefern
 * `-1`, dann gilt der Wert als unbekannt.
 */
private class CursorColumns(
    cursor: Cursor,
) {
    private val id = cursor.getColumnIndexOrThrow(MediaColumnNames.ID)
    private val displayName = cursor.getColumnIndex(MediaColumnNames.DISPLAY_NAME)
    private val mimeType = cursor.getColumnIndex(MediaColumnNames.MIME_TYPE)
    private val size = cursor.getColumnIndex(MediaColumnNames.SIZE)
    private val width = cursor.getColumnIndex(MediaColumnNames.WIDTH)
    private val height = cursor.getColumnIndex(MediaColumnNames.HEIGHT)
    private val dateTaken = cursor.getColumnIndex(MediaColumnNames.DATE_TAKEN)
    private val dateModified = cursor.getColumnIndex(MediaColumnNames.DATE_MODIFIED)
    private val dateAdded = cursor.getColumnIndex(MediaColumnNames.DATE_ADDED)
    private val bucketId = cursor.getColumnIndex(MediaColumnNames.BUCKET_ID)
    private val bucketName = cursor.getColumnIndex(MediaColumnNames.BUCKET_DISPLAY_NAME)
    private val orientation = cursor.getColumnIndex(MediaColumnNames.ORIENTATION)
    private val duration = cursor.getColumnIndex(MediaColumnNames.DURATION)
    private val relativePath = cursor.getColumnIndex(MediaColumnNames.RELATIVE_PATH)
    private val volumeName = cursor.getColumnIndex(MediaColumnNames.VOLUME_NAME)

    fun read(
        cursor: Cursor,
        type: MediaType,
        collection: Uri,
    ): MediaItem {
        val itemId = cursor.getLong(id)
        val resolved =
            MediaIndex.resolveTimestamp(
                dateTakenMs = cursor.longOrNull(dateTaken) ?: 0L,
                dateModifiedSec = cursor.longOrNull(dateModified) ?: 0L,
                dateAddedSec = cursor.longOrNull(dateAdded) ?: 0L,
            )
        return MediaItem(
            id = itemId,
            uri = ContentUris.withAppendedId(collection, itemId).toString(),
            type = type,
            displayName = cursor.stringOrNull(displayName),
            mimeType = cursor.stringOrNull(mimeType),
            timestamp = resolved.millis,
            timestampSource = resolved.source,
            width = cursor.intOrNull(width)?.takeIf { it > 0 },
            height = cursor.intOrNull(height)?.takeIf { it > 0 },
            orientation = cursor.intOrNull(orientation),
            durationMs = if (type == MediaType.VIDEO) cursor.longOrNull(duration)?.takeIf { it > 0 } else null,
            sizeBytes = cursor.longOrNull(size),
            bucketId = cursor.longOrNull(bucketId),
            bucketName = cursor.stringOrNull(bucketName),
            relativePath = cursor.stringOrNull(relativePath),
            volumeName = cursor.stringOrNull(volumeName),
        )
    }
}

private fun Cursor.longOrNull(index: Int): Long? = if (index >= 0 && !isNull(index)) getLong(index) else null

private fun Cursor.intOrNull(index: Int): Int? = if (index >= 0 && !isNull(index)) getInt(index) else null

private fun Cursor.stringOrNull(index: Int): String? = if (index >= 0 && !isNull(index)) getString(index) else null
