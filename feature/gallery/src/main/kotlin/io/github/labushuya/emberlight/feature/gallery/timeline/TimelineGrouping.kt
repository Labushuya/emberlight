package io.github.labushuya.emberlight.feature.gallery.timeline

import io.github.labushuya.emberlight.core.media.model.MediaItem
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Gruppiert die sortierte Zeitleiste in Tages- oder Monatsgruppen. Läuft in O(n), es gibt keine erneute Sortierung.
 *
 * [items] muss absteigend nach Zeitstempel sortiert sein, wie ihn der Index liefert. Dann liegt jede Gruppe in einem
 * zusammenhängenden Stück. Heute und Zone kommen als Parameter, damit die Gruppierung mit fester Uhr prüfbar ist.
 */
internal object TimelineGrouping {
    fun group(
        items: List<MediaItem>,
        mode: GroupingMode,
        today: LocalDate,
        zone: ZoneId,
    ): List<TimelineSection> {
        if (items.isEmpty()) return emptyList()
        val sections = ArrayList<TimelineSection>()
        var bucket: LocalDate? = null
        var current = ArrayList<MediaItem>()
        for (item in items) {
            val date = Instant.ofEpochMilli(item.timestamp).atZone(zone).toLocalDate()
            val start = if (mode == GroupingMode.DAY) date else date.withDayOfMonth(1)
            if (start != bucket) {
                bucket?.let { sections += section(it, mode, today, current) }
                bucket = start
                current = ArrayList()
            }
            current += item
        }
        bucket?.let { sections += section(it, mode, today, current) }
        return sections
    }

    /**
     * Überschrift für einen Tag: „Heute“, „Gestern“, sonst Wochentag und Datum. Die Jahreszahl steht nur außerhalb
     * des laufenden Kalenderjahres.
     */
    fun dayHeader(
        date: LocalDate,
        today: LocalDate,
    ): TimelineHeader =
        when (date) {
            today -> TimelineHeader.Today
            today.minusDays(1) -> TimelineHeader.Yesterday
            else -> TimelineHeader.Day(date = date, withYear = date.year != today.year)
        }

    private fun section(
        start: LocalDate,
        mode: GroupingMode,
        today: LocalDate,
        items: List<MediaItem>,
    ): TimelineSection =
        when (mode) {
            GroupingMode.DAY -> {
                TimelineSection(key = "day:$start", header = dayHeader(start, today), items = items)
            }

            GroupingMode.MONTH -> {
                val yearMonth = YearMonth.from(start)
                TimelineSection(key = "month:$yearMonth", header = TimelineHeader.Month(yearMonth), items = items)
            }
        }
}
