package io.github.labushuya.emberlight.core.media.store

import android.provider.MediaStore
import io.github.labushuya.emberlight.core.media.model.MediaType

/**
 * Spaltennamen für MediaStore (siehe ADR 0007).
 *
 * Die Namen `DATE_TAKEN`, `BUCKET_ID`, `BUCKET_DISPLAY_NAME`, `ORIENTATION`, `DURATION`, `IS_PENDING`,
 * `RELATIVE_PATH` und `VOLUME_NAME` sind in `MediaStore.MediaColumns` erst ab API 29 deklariert. Vor API 29 stehen
 * dieselben Namen in `ImageColumns` und `VideoColumns`. Eigene Strings mit identischen Werten vermeiden
 * NewApi-Warnungen bei minSdk 26. Die übrigen Namen kommen direkt aus MediaStore und sind ab API 26 verfügbar.
 */
internal object MediaColumnNames {
    val ID: String = MediaStore.MediaColumns._ID
    val DISPLAY_NAME: String = MediaStore.MediaColumns.DISPLAY_NAME
    val MIME_TYPE: String = MediaStore.MediaColumns.MIME_TYPE
    val SIZE: String = MediaStore.MediaColumns.SIZE
    val WIDTH: String = MediaStore.MediaColumns.WIDTH
    val HEIGHT: String = MediaStore.MediaColumns.HEIGHT
    val DATE_ADDED: String = MediaStore.MediaColumns.DATE_ADDED
    val DATE_MODIFIED: String = MediaStore.MediaColumns.DATE_MODIFIED

    const val DATE_TAKEN = "datetaken"
    const val BUCKET_ID = "bucket_id"
    const val BUCKET_DISPLAY_NAME = "bucket_display_name"
    const val ORIENTATION = "orientation"
    const val DURATION = "duration"
    const val RELATIVE_PATH = "relative_path"
    const val VOLUME_NAME = "volume_name"
    const val IS_PENDING = "is_pending"
}

/** Projektion und Selektion je Collection und SDK-Level. Reine Funktionen, damit sie ohne Gerät testbar sind. */
internal object MediaStoreQuery {
    /** Ab API 29 gibt es `IS_PENDING`. Einträge, die gerade geschrieben werden, werden ausgeblendet. */
    private const val API_SCOPED_COLUMNS = 29

    /**
     * Spalten für Bilder und Videos. Optionale Spalten gibt es nur ab bestimmten API-Levels (Video-Orientierung,
     * relative Pfade, Volume). Der Cursor liest sie tolerant, siehe Gateway.
     */
    fun projection(
        type: MediaType,
        sdkInt: Int,
    ): Array<String> =
        buildList {
            add(MediaColumnNames.ID)
            add(MediaColumnNames.DISPLAY_NAME)
            add(MediaColumnNames.MIME_TYPE)
            add(MediaColumnNames.SIZE)
            add(MediaColumnNames.WIDTH)
            add(MediaColumnNames.HEIGHT)
            add(MediaColumnNames.DATE_TAKEN)
            add(MediaColumnNames.DATE_MODIFIED)
            add(MediaColumnNames.DATE_ADDED)
            add(MediaColumnNames.BUCKET_ID)
            add(MediaColumnNames.BUCKET_DISPLAY_NAME)
            when (type) {
                MediaType.IMAGE -> {
                    add(MediaColumnNames.ORIENTATION)
                }

                MediaType.VIDEO -> {
                    add(MediaColumnNames.DURATION)
                    if (sdkInt >= API_SCOPED_COLUMNS) add(MediaColumnNames.ORIENTATION)
                }
            }
            if (sdkInt >= API_SCOPED_COLUMNS) {
                add(MediaColumnNames.RELATIVE_PATH)
                add(MediaColumnNames.VOLUME_NAME)
            }
        }.toTypedArray()

    /**
     * Selektion: ab API 29 ohne Einträge, die noch geschrieben werden (`IS_PENDING = 1`). Der Papierkorb ist ab
     * API 30 standardmäßig ausgeblendet. Ohne `QUERY_ARG_MATCH_TRASHED` liefert MediaStore ihn nicht, deshalb setzt
     * die App dafür keine eigene Bedingung.
     */
    fun selection(sdkInt: Int): String? =
        if (sdkInt >=
            API_SCOPED_COLUMNS
        ) {
            "${MediaColumnNames.IS_PENDING} = 0"
        } else {
            null
        }
}
