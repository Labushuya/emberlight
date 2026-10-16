package io.github.labushuya.emberlight.feature.gallery.timeline

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
            bucketId = 1L,
            bucketName = "Camera",
            relativePath = "DCIM/Camera/",
            volumeName = "external_primary",
        )

    /** Acht Einträge, absteigend sortiert: heute, gestern, Oktober, Juli (mit Stunden-Video) und Juni 2025. */
    fun sampleItems(): List<MediaItem> =
        listOf(
            item(9, millis("2026-10-16T09:30"), MediaType.VIDEO, durationMs = 42_000L),
            item(8, millis("2026-10-16T08:15")),
            item(7, millis("2026-10-15T19:40")),
            item(6, millis("2026-10-15T10:02")),
            item(5, millis("2026-10-12T17:55")),
            item(4, millis("2026-07-02T21:10"), MediaType.VIDEO, durationMs = 3_723_000L),
            item(3, millis("2025-06-03T11:00")),
            item(2, millis("2025-06-03T10:30")),
        )

    fun library(
        items: List<MediaItem> = sampleItems(),
        access: MediaAccess = MediaAccess.FULL,
        missingTypes: Set<MediaType> = emptySet(),
    ): MediaLibrary =
        MediaLibrary(items = items, albums = emptyList(), mediaAccess = access, missingTypes = missingTypes)
}
