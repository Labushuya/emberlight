package io.github.labushuya.emberlight.feature.gallery.timeline

import io.github.labushuya.emberlight.core.media.model.MediaItem
import java.time.LocalDate
import java.time.ZoneId

/** Bildet die sortierten Einträge auf Gruppen ab. Austauschbar, damit der Cache in Tests zählbar ist. */
internal fun interface TimelineGrouper {
    fun group(
        items: List<MediaItem>,
        mode: GroupingMode,
        today: LocalDate,
        zone: ZoneId,
    ): List<TimelineSection>

    companion object {
        /** Ohne Cache: rechnet jedes Mal neu. */
        val Direct: TimelineGrouper =
            TimelineGrouper {
                items,
                mode,
                today,
                zone,
                ->
                TimelineGrouping.group(items, mode, today, zone)
            }
    }
}

/**
 * Hält je Gruppierungsart das Ergebnis, damit das Umschalten der Zoomstufe nicht jedes Mal 50.000 Einträge neu gruppiert.
 * Der Cache hängt an der Liste (Identität) und am Tag. Ändert sich eins davon, wird er verworfen. Nur Berechnung bei Bedarf:
 * eine Art wird erst gebildet, wenn sie angefragt wird.
 */
internal class TimelineGroupingCache(
    private val grouper: TimelineGrouper = TimelineGrouper.Direct,
) : TimelineGrouper {
    private var source: List<MediaItem>? = null
    private var sourceToday: LocalDate? = null
    private var sourceZone: ZoneId? = null
    private val cached = HashMap<GroupingMode, List<TimelineSection>>()

    /** Mehrere Berechnungen können parallel auf dem Default-Dispatcher laufen, daher synchronisiert. */
    @Synchronized
    override fun group(
        items: List<MediaItem>,
        mode: GroupingMode,
        today: LocalDate,
        zone: ZoneId,
    ): List<TimelineSection> {
        if (items !== source || today != sourceToday || zone != sourceZone) {
            source = items
            sourceToday = today
            sourceZone = zone
            cached.clear()
        }
        return cached.getOrPut(mode) { grouper.group(items, mode, today, zone) }
    }
}
