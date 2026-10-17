package io.github.labushuya.emberlight.feature.gallery.timeline

import io.github.labushuya.emberlight.core.media.model.Album
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaLibrary
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.model.TimestampSource
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Feste Daten für Tests. Heute ist der 16. Oktober 2026 in Berlin. Die Zeitpunkte liegen so, dass sie in Berlin und
 * in UTC am selben Tag liegen, damit Zellbeschreibungen unabhängig von der Zeitzone des Testrechners sind.
 */
internal object TimelineFixtures {
    val ZONE: ZoneId = ZoneId.of("Europe/Berlin")
    val TODAY: LocalDate = LocalDate.of(2026, 10, 16)

    /** Ordner der Beispieldaten. Die Alben-Tests erwarten genau diese Namen. */
    const val CAMERA_BUCKET = 1L
    const val SCREENSHOT_BUCKET = 2L
    const val DOWNLOAD_BUCKET = 3L

    fun clock(): Clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZONE)

    /** Epochen-Millisekunden für einen lokalen Zeitpunkt in [ZONE], Format `2026-10-16T09:30`. */
    fun millis(localDateTime: String): Long =
        LocalDateTime
            .parse(localDateTime)
            .atZone(ZONE)
            .toInstant()
            .toEpochMilli()

    fun item(
        id: Long,
        millis: Long,
        type: MediaType = MediaType.IMAGE,
        durationMs: Long? = null,
        bucketId: Long? = CAMERA_BUCKET,
        bucketName: String? = "Camera",
    ): MediaItem =
        MediaItem(
            id = id,
            uri = "content://media/external/${type.name.lowercase()}/media/$id",
            type = type,
            displayName = "file$id",
            mimeType = null,
            timestamp = millis,
            timestampSource = TimestampSource.DATE_TAKEN,
            width = 4032,
            height = 3024,
            orientation = 0,
            durationMs = durationMs,
            sizeBytes = 1_000_000L,
            bucketId = bucketId,
            bucketName = bucketName,
            relativePath = "DCIM/Camera/",
            volumeName = "external_primary",
        )

    /**
     * Acht Einträge, absteigend sortiert: heute, gestern, Oktober, Juli (mit Stunden-Video) und Juni 2025. Die Ordner:
     * Kamera (4), Screenshots (1), Downloads (2) und ein Eintrag ohne Ordner aus dem Juli. Dieser liegt zeitlich vor den
     * Downloads und steht im Album-Raster trotzdem am Ende.
     */
    fun sampleItems(): List<MediaItem> =
        listOf(
            item(9, millis("2026-10-16T09:30"), MediaType.VIDEO, durationMs = 42_000L),
            item(8, millis("2026-10-16T08:15")),
            item(7, millis("2026-10-15T19:40"), bucketId = SCREENSHOT_BUCKET, bucketName = "Screenshots"),
            item(6, millis("2026-10-15T10:02")),
            item(5, millis("2026-10-12T17:55")),
            item(
                4,
                millis("2026-07-02T21:10"),
                MediaType.VIDEO,
                durationMs = 3_723_000L,
                bucketId = null,
                bucketName = null,
            ),
            item(3, millis("2025-06-03T11:00"), bucketId = DOWNLOAD_BUCKET, bucketName = "Downloads"),
            item(2, millis("2025-06-03T10:30"), bucketId = DOWNLOAD_BUCKET, bucketName = "Downloads"),
        )

    /** Mehrere hundert Einträge, je zwei Tage auseinander, rückwärts ab heute: genug für eine Schnell-Scrollleiste. */
    fun longLibrary(count: Int = 400): List<MediaItem> =
        (0 until count).map { i ->
            val millis =
                TODAY
                    .minusDays(i * 2L)
                    .atTime(10, 0)
                    .atZone(ZONE)
                    .toInstant()
                    .toEpochMilli()
            item(id = 1_000L + i, millis = millis)
        }

    /**
     * Alben wie der Index sie bildet: je Ordner ein Album, das neueste Element als Cover, die Anzahl und der jüngste Zeitpunkt.
     * Sortiert wie der Index, neueste zuerst, bei Gleichstand der Ordner ohne Namen zuletzt. Die Abbildung steht hier im
     * Test, weil `MediaIndex` in `core:media` intern ist.
     */
    fun albumsOf(items: List<MediaItem>): List<Album> {
        val byBucket = LinkedHashMap<Long?, MutableList<MediaItem>>()
        items.sortedByDescending { it.timestamp }.forEach { byBucket.getOrPut(it.bucketId) { mutableListOf() }.add(it) }
        return byBucket
            .map { (bucketId, list) ->
                Album(
                    bucketId = bucketId,
                    name = list.first().bucketName.orEmpty(),
                    cover = list.first(),
                    itemCount = list.size,
                    latestTimestamp = list.first().timestamp,
                )
            }.sortedWith(
                compareByDescending<Album> { it.latestTimestamp }
                    .thenBy(nullsLast()) { it.bucketId },
            )
    }

    fun library(
        items: List<MediaItem> = sampleItems(),
        access: MediaAccess = MediaAccess.FULL,
        missingTypes: Set<MediaType> = emptySet(),
    ): MediaLibrary =
        MediaLibrary(items = items, albums = albumsOf(items), mediaAccess = access, missingTypes = missingTypes)
}
