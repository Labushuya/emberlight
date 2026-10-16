package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.runtime.Immutable
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import java.time.LocalDate
import java.time.YearMonth

/** Typ der Zelle im Raster. Dient auch dem Anker: Nur Medien-Zellen sind Anker, Überschriften nicht. */
internal const val CONTENT_TYPE_MEDIA = "media"

/** Gruppierung der Zeitleiste. Die Stufe bestimmt sie, siehe [ZoomLevels.groupingFor]. */
internal enum class GroupingMode {
    DAY,
    MONTH,
}

/** Überschrift einer Gruppe. Der Text entsteht erst in der UI, weil Sprache und Ressourcen dort liegen. */
@Immutable
internal sealed interface TimelineHeader {
    data object Today : TimelineHeader

    data object Yesterday : TimelineHeader

    /** Einzelner Tag. [withYear] ist `false` im laufenden Kalenderjahr. */
    @Immutable
    data class Day(
        val date: LocalDate,
        val withYear: Boolean,
    ) : TimelineHeader

    @Immutable
    data class Month(
        val yearMonth: YearMonth,
    ) : TimelineHeader
}

/**
 * Ein Datum, das für eine Überschrift steht. Heute und Gestern brauchen den heutigen Tag, deshalb kommt er als
 * Parameter. Für die Schnell-Scrollleiste gilt: Der Monat der Überschrift bestimmt die Anzeige.
 */
internal fun TimelineHeader.representativeDate(today: LocalDate): LocalDate =
    when (this) {
        TimelineHeader.Today -> today
        TimelineHeader.Yesterday -> today.minusDays(1)
        is TimelineHeader.Day -> date
        is TimelineHeader.Month -> yearMonth.atDay(1)
    }

/**
 * Eine Gruppe der Zeitleiste. [key] ist eindeutig und stabil über Aktualisierungen hinweg, z. B. `day:2026-10-16`.
 * [items] behält die Reihenfolge des Index, neueste zuerst.
 */
@Immutable
internal data class TimelineSection(
    val key: String,
    val header: TimelineHeader,
    val items: List<MediaItem>,
)

/**
 * Zustand eines Rasters für die UI, gemeinsam für Galerie und Album. Laden, Berechtigung und Leerzustand gelten für
 * alle Ansichten gleich. [Gone] meldet ein Album, das nicht mehr im Index steht (ADR 0009).
 */
@Immutable
internal sealed interface MediaGridState {
    /** Erste Abfrage läuft. */
    data object Loading : MediaGridState

    /**
     * Kein Zugriff. [canRequest] `true`: der Dialog kann erneut erscheinen. `false`: nur noch die Systemeinstellungen
     * helfen, weil der Nutzer dauerhaft verweigert hat.
     */
    @Immutable
    data class PermissionRequired(
        val canRequest: Boolean,
    ) : MediaGridState

    /** Zugriff vorhanden, aber keine Einträge im Geltungsbereich (bei [MediaAccess.PARTIAL] keine Auswahl). */
    @Immutable
    data class Empty(
        val access: MediaAccess,
        val missingTypes: Set<MediaType>,
    ) : MediaGridState

    /**
     * Das Album hat keine Einträge mehr, etwa weil alle gelöscht wurden. Entsteht nur im Geltungsbereich
     * [MediaScope.Bucket]. Die Galerie meldet stattdessen [Empty].
     */
    @Immutable
    data class Gone(
        val access: MediaAccess,
        val missingTypes: Set<MediaType>,
    ) : MediaGridState

    /**
     * Gruppiertes Raster für [zoomLevel]. [today] ist der Tag, für den die Gruppen gebildet wurden. Die UI braucht
     * ihn für die Schnell-Scrollleiste, damit Heute und Gestern dasselbe Datum meinen wie die Überschriften.
     */
    @Immutable
    data class Content(
        val sections: List<TimelineSection>,
        val access: MediaAccess,
        val missingTypes: Set<MediaType>,
        val zoomLevel: Int,
        val today: LocalDate,
    ) : MediaGridState
}
