package io.github.labushuya.emberlight.core.media.index

import io.github.labushuya.emberlight.core.media.model.Album
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaLibrary
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.model.TimestampSource
import io.github.labushuya.emberlight.core.media.permission.MediaAccess

/** Effektiver Zeitstempel mit Quelle. */
internal data class ResolvedTimestamp(
    val millis: Long,
    val source: TimestampSource,
)

/**
 * Reine Logik des Index ohne Android-Abhängigkeit, damit sie auf dem JVM getestet und gemessen werden kann
 * (siehe ADR 0007).
 *
 * Einträge sind absteigend nach effektivem Zeitstempel sortiert. Bei Gleichstand entscheidet der Typ (Bild vor
 * Video), danach die ID aufsteigend. Diese Ordnung ist total, das Ergebnis ist also deterministisch.
 */
internal object MediaIndex {
    /** Reihenfolge der Zeitleiste: neuester Eintrag zuerst, dann Typ, dann ID. */
    val TIMELINE_ORDER: Comparator<MediaItem> =
        compareByDescending<MediaItem> { it.timestamp }
            .thenBy { it.type }
            .thenBy { it.id }

    /**
     * Effektiver Zeitstempel: `DATE_TAKEN` in Millisekunden, falls größer als 0. Sonst `DATE_MODIFIED` in Sekunden
     * mal 1000, falls größer als 0. Sonst `DATE_ADDED` mal 1000. Fehlende Werte werden als 0 übergeben.
     */
    fun resolveTimestamp(
        dateTakenMs: Long,
        dateModifiedSec: Long,
        dateAddedSec: Long,
    ): ResolvedTimestamp =
        when {
            dateTakenMs > 0 -> ResolvedTimestamp(dateTakenMs, TimestampSource.DATE_TAKEN)
            dateModifiedSec > 0 -> ResolvedTimestamp(dateModifiedSec * MILLIS_PER_SECOND, TimestampSource.DATE_MODIFIED)
            else -> ResolvedTimestamp(dateAddedSec * MILLIS_PER_SECOND, TimestampSource.DATE_ADDED)
        }

    /**
     * Baut den Index aus den Ergebnissen beider Collections. Sortiert jede Liste (n log n), führt sie dann in O(n)
     * zusammen und bildet die Alben in einem weiteren Durchlauf.
     */
    fun build(
        images: List<MediaItem>,
        videos: List<MediaItem>,
        access: MediaAccess,
        missingTypes: Set<MediaType> = emptySet(),
    ): MediaLibrary {
        val items = mergeSorted(images.sortedWith(TIMELINE_ORDER), videos.sortedWith(TIMELINE_ORDER))
        return MediaLibrary(items = items, albums = albums(items), mediaAccess = access, missingTypes = missingTypes)
    }

    /** Führt zwei nach [TIMELINE_ORDER] sortierte Listen in O(n) zusammen. Die Eingaben bleiben unverändert. */
    fun mergeSorted(
        first: List<MediaItem>,
        second: List<MediaItem>,
    ): List<MediaItem> {
        val merged = ArrayList<MediaItem>(first.size + second.size)
        var i = 0
        var j = 0
        while (i < first.size && j < second.size) {
            if (TIMELINE_ORDER.compare(first[i], second[j]) <= 0) {
                merged.add(first[i++])
            } else {
                merged.add(second[j++])
            }
        }
        while (i < first.size) merged.add(first[i++])
        while (j < second.size) merged.add(second[j++])
        return merged
    }

    /**
     * Gruppiert nach `bucketId`. `null` bildet das Sammelalbum für Einträge ohne Ordner. [items] muss nach
     * [TIMELINE_ORDER] sortiert sein, dann ist das erste Element jeder Gruppe das Cover. Die Alben sind absteigend
     * nach neuestem Element sortiert, bei Gleichstand aufsteigend nach `bucketId` mit `null` zuletzt.
     */
    fun albums(items: List<MediaItem>): List<Album> {
        val groups = LinkedHashMap<Long?, AlbumAccumulator>()
        for (item in items) {
            groups.getOrPut(item.bucketId) { AlbumAccumulator(cover = item) }.count++
        }
        return groups
            .map { (bucketId, acc) ->
                Album(
                    bucketId = bucketId,
                    name = acc.cover.bucketName.orEmpty(),
                    cover = acc.cover,
                    itemCount = acc.count,
                    latestTimestamp = acc.cover.timestamp,
                )
            }.sortedWith(
                compareByDescending<Album> { it.latestTimestamp }
                    .thenBy(nullsLast()) { it.bucketId },
            )
    }

    private class AlbumAccumulator(
        val cover: MediaItem,
        var count: Int = 0,
    )

    private const val MILLIS_PER_SECOND = 1000L
}
