package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.runtime.Stable
import java.time.LocalDate

/**
 * Abbildung der Zeitleiste auf die flache Reihenfolge der Raster-Zellen: optionale Kopfzeilen (Banner), dann je Abschnitt
 * eine Überschrift und ihre Medien. Die Position im Raster ist der Index für `requestScrollToItem`.
 *
 * Die Zuordnung Medium → Position entsteht erst bei Bedarf (Anker, Ziehen), weil sie bei 50.000 Einträgen merklich kostet.
 * Die Klasse ist unveränderlich, daher als stabil markiert. Die Komposition darf sie überspringen.
 *
 * @param leadingItems Anzahl Zellen vor dem ersten Abschnitt, etwa 1 für das Zugriffs-Banner.
 * @param today Tag, für den die Überschriften gebildet wurden. Heute und Gestern brauchen ihn für das Datum der Blase.
 */
@Stable
internal class TimelineIndex(
    private val sections: List<TimelineSection>,
    leadingItems: Int,
    private val today: LocalDate,
) {
    private val sectionStarts = IntArray(sections.size)

    /** Gesamtzahl der Raster-Zellen einschließlich Überschriften und Banner. */
    val totalItems: Int

    private val mediaPositions: Map<String, Int> by lazy(LazyThreadSafetyMode.NONE) {
        val positions = HashMap<String, Int>(totalItems)
        sections.forEachIndexed { sectionIndex, section ->
            val first = sectionStarts[sectionIndex] + 1
            section.items.forEachIndexed { offset, item -> positions[item.uri] = first + offset }
        }
        positions
    }

    init {
        var position = leadingItems
        sections.forEachIndexed { sectionIndex, section ->
            sectionStarts[sectionIndex] = position
            position += 1 + section.items.size
        }
        totalItems = position
    }

    /** Abschnitt, zu dem die Zelle an [index] gehört. `null` für das Banner und außerhalb des Rasters. */
    fun sectionAt(index: Int): TimelineSection? {
        if (sections.isEmpty() || index < sectionStarts[0] || index >= totalItems) return null
        var low = 0
        var high = sections.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (sectionStarts[mid] <= index) low = mid else high = mid - 1
        }
        return sections[low]
    }

    /** Datum des Abschnitts an [index], für die Schnell-Scrollblase. `null` wie bei [sectionAt]. */
    fun dateAt(index: Int): LocalDate? = sectionAt(index)?.header?.representativeDate(today)

    /** Position eines Mediums im Raster, über seine URI. */
    fun indexOfMedia(uri: String): Int? = mediaPositions[uri]
}
