package io.github.labushuya.emberlight.demo

import io.github.labushuya.emberlight.core.media.model.Album
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.model.TimestampSource
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Synthetische Bibliothek für Gerätetests ohne private Fotos (ADR 0011). Jeder Eintrag ist eine reine Funktion seines Index:
 * dieselbe Bibliothek entsteht bei jedem Start, ohne Dateien auf dem Gerät.
 *
 * Der Index 0 ist der neueste Eintrag. Die Zeitstempel fallen streng monoton, daher ist die Liste bereits absteigend sortiert,
 * wie der MediaStore-Index. Drei Jahre liegen zwischen dem neuesten und dem ältesten Eintrag. Etwa jeder zehnte Eintrag ist ein
 * Video. Zwölf Ordner (Alben) teilen die Einträge, vier Seitenverhältnisse kommen vor.
 */
internal object SyntheticLibrary {
    const val COUNT = 20_000
    const val ALBUM_COUNT = 12

    /** Schema der synthetischen URIs. Nur der Demo-Thumbnail-Fetcher kennt sie, andere Pfade gibt es nicht. */
    const val URI_PREFIX = "emberlight-demo://media/"

    private const val FIRST_BUCKET_ID = 101L
    private const val SPAN_MS = 3L * 365L * 86_400_000L
    private const val STEP_MS = SPAN_MS / COUNT

    /** Festes Bezugsdatum, nicht die Uhr: die Bibliothek bleibt zwischen den Läufen gleich. */
    private val NEWEST_MS: Long =
        LocalDateTime
            .of(
                2026,
                10,
                16,
                12,
                0,
            ).atZone(ZoneId.of("Europe/Berlin"))
            .toInstant()
            .toEpochMilli()

    /** Ein Eintrag zum Index [index] (0 bis [COUNT] - 1). */
    fun itemAt(index: Int): MediaItem {
        require(index in 0 until COUNT) { "Index außerhalb der Bibliothek: $index" }
        val i = index.toLong()
        val type = if (Math.floorMod(mix(i, SALT_TYPE), 10L) == 0L) MediaType.VIDEO else MediaType.IMAGE
        val bucket = FIRST_BUCKET_ID + Math.floorMod(mix(i, SALT_BUCKET), ALBUM_COUNT.toLong())
        val (width, height) = shapeOf(i)
        val timestamp = NEWEST_MS - i * STEP_MS - Math.floorMod(mix(i, SALT_JITTER), STEP_MS / 2)
        val id = i + 1
        val extension = if (type == MediaType.VIDEO) "mp4" else "jpg"
        return MediaItem(
            id = id,
            uri = URI_PREFIX + id,
            type = type,
            displayName = "DEMO_%06d.%s".format(id, extension),
            mimeType = if (type == MediaType.VIDEO) "video/mp4" else "image/jpeg",
            timestamp = timestamp,
            timestampSource = TimestampSource.DATE_TAKEN,
            width = width,
            height = height,
            orientation = 0,
            durationMs = if (type == MediaType.VIDEO) 5_000L + Math.floorMod(mix(i, SALT_DURATION), 300_000L) else null,
            sizeBytes = 1_000_000L + Math.floorMod(mix(i, SALT_SIZE), 5_000_000L),
            bucketId = bucket,
            bucketName = "Album %02d".format(bucket - FIRST_BUCKET_ID + 1),
            relativePath = "Pictures/Demo/",
            volumeName = "demo",
        )
    }

    /** Alle Einträge, neueste zuerst. */
    fun items(): List<MediaItem> = (0 until COUNT).map(::itemAt)

    /**
     * Alben wie der Index sie bildet: je Ordner ein Album, das neueste Element als Cover, die Anzahl und der jüngste Zeitpunkt.
     * Sortiert wie der Index, neueste zuerst.
     */
    fun albums(items: List<MediaItem>): List<Album> {
        val byBucket = LinkedHashMap<Long?, MutableList<MediaItem>>()
        items.forEach { byBucket.getOrPut(it.bucketId) { mutableListOf() }.add(it) }
        return byBucket
            .map { (bucketId, list) ->
                Album(
                    bucketId = bucketId,
                    name = list.first().bucketName.orEmpty(),
                    cover = list.first(),
                    itemCount = list.size,
                    latestTimestamp = list.first().timestamp,
                )
            }.sortedByDescending { it.latestTimestamp }
    }

    /** Index aus einer synthetischen URI, `null` für fremde URIs. */
    fun indexOf(uri: String): Int? {
        if (!uri.startsWith(URI_PREFIX)) return null
        val id = uri.removePrefix(URI_PREFIX).toLongOrNull() ?: return null
        return (id - 1).takeIf { it in 0 until COUNT }?.toInt()
    }

    /** Seitenverhältnis je Eintrag: hoch, quer, quadratisch und Panorama. */
    private fun shapeOf(i: Long): Pair<Int, Int> =
        when (Math.floorMod(mix(i, SALT_SHAPE), 4L)) {
            0L -> 3024 to 4032
            1L -> 4032 to 3024
            2L -> 3024 to 3024
            else -> 6000 to 2000
        }

    /** Splitmix64: deterministische, gut verteilte Werte aus dem Index und einem Salz. */
    internal fun mix(
        index: Long,
        salt: Long,
    ): Long {
        var z = index * -0x61c8864680b583ebL + salt
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    private const val SALT_TYPE = 1L
    private const val SALT_BUCKET = 2L
    private const val SALT_JITTER = 3L
    private const val SALT_DURATION = 4L
    private const val SALT_SIZE = 5L
    private const val SALT_SHAPE = 6L
}
