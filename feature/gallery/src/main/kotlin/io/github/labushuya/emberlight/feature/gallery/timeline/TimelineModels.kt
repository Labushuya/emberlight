package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.runtime.Immutable
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import java.time.LocalDate
import java.time.YearMonth

/** Spaltenzahl der Standardstufe. Die Zeitleiste hat in Paket 3a nur diese Stufe (siehe ADR 0008). */
internal const val DEFAULT_COLUMNS = 4

/**
 * Gruppierung der Zeitleiste. Tagesgruppen bis [MAX_DAY_COLUMNS] Spalten, Monatsgruppen ab der nächsten Stufe.
 * Die Monatsgruppen sind in 3a vorbereitet und getestet. Die UI nutzt sie erst mit der Spaltenstufe ab 5.
 */
internal enum class GroupingMode {
    DAY,
    MONTH,
    ;

    companion object {
        const val MAX_DAY_COLUMNS = 4

        fun forColumns(columns: Int): GroupingMode = if (columns <= MAX_DAY_COLUMNS) DAY else MONTH
    }
}

/** Überschrift einer Gruppe. Der Text entsteht erst in der UI, weil Sprache und Ressourcen dort liegen. */
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
 * Eine Gruppe der Zeitleiste. [key] ist eindeutig und stabil über Aktualisierungen hinweg, z. B. `day:2026-10-16`.
 * [items] behält die Reihenfolge des Index, neueste zuerst.
 */
@Immutable
internal data class TimelineSection(
    val key: String,
    val header: TimelineHeader,
    val items: List<MediaItem>,
)

/** Zustand der Zeitleiste für die UI. */
internal sealed interface TimelineUiState {
    /** Erste Abfrage läuft. */
    data object Loading : TimelineUiState

    /**
     * Kein Zugriff. [canRequest] `true`: der Dialog kann erneut erscheinen. `false`: nur noch die Systemeinstellungen
     * helfen, weil der Nutzer dauerhaft verweigert hat.
     */
    @Immutable
    data class PermissionRequired(
        val canRequest: Boolean,
    ) : TimelineUiState

    /** Zugriff vorhanden, aber keine Einträge (bei [MediaAccess.PARTIAL] keine Auswahl). */
    @Immutable
    data class Empty(
        val access: MediaAccess,
        val missingTypes: Set<MediaType>,
    ) : TimelineUiState

    @Immutable
    data class Content(
        val sections: List<TimelineSection>,
        val access: MediaAccess,
        val missingTypes: Set<MediaType>,
    ) : TimelineUiState
}
