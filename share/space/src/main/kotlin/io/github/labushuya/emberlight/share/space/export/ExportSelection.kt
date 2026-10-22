package io.github.labushuya.emberlight.share.space.export

/** Ein Medium, das in die Galerie übernommen werden kann (Paket 3.7). Nur gespeicherte, fertige Medien. */
data class ExportCandidate(
    val mediaId: String,
    val sizeBytes: Long,
    val locationKept: Boolean,
    /** Videos statt Bilder. Nur für die Anzeige und die Kennzeichnung der Kachel. */
    val isVideo: Boolean = false,
    /** Schon übernommen: wird gekennzeichnet und lässt sich nicht erneut auswählen (keine Duplikate). */
    val alreadyExported: Boolean,
)

/**
 * Auswahl der Medien zur Übernahme (Paket 3.7, Nutzerentscheidung 1). Vorauswahl: nichts. „Alle“ wählt alle Medien aus,
 * die noch nicht übernommen sind. Bereits übernommene und unbekannte Kennungen lassen sich nicht auswählen.
 *
 * Reine Logik, ohne Android. Das ViewModel hält eine Instanz und ersetzt sie bei jeder Änderung.
 */
data class ExportSelection(
    val candidates: List<ExportCandidate>,
    val selected: Set<String> = emptySet(),
) {
    private val byId: Map<String, ExportCandidate> = candidates.associateBy { it.mediaId }

    /** Kennungen, die sich auswählen lassen. */
    val selectable: List<ExportCandidate> = candidates.filter { !it.alreadyExported }

    /** Ausgewählte Medien, in der Reihenfolge der Kandidaten. */
    val chosen: List<ExportCandidate> = candidates.filter { it.mediaId in selected && !it.alreadyExported }

    val count: Int get() = chosen.size

    val totalBytes: Long get() = chosen.sumOf { it.sizeBytes }

    /** Ausgewählt, aber mit „Standort enthalten“. Die Oberfläche kennzeichnet sie deutlich. */
    val withLocationCount: Int get() = chosen.count { it.locationKept }

    /** Alle, die noch nicht übernommen sind. */
    fun selectAll(): ExportSelection = copy(selected = selectable.map { it.mediaId }.toSet())

    fun selectNone(): ExportSelection = copy(selected = emptySet())

    /** Schaltet ein Medium um. Bereits übernommene oder unbekannte Kennungen bleiben unberührt. */
    fun toggle(mediaId: String): ExportSelection {
        val candidate = byId[mediaId] ?: return this
        if (candidate.alreadyExported) return this
        val next = if (mediaId in selected) selected - mediaId else selected + mediaId
        return copy(selected = next)
    }

    /** Übernimmt eine neue Liste (z. B. nach einer Übernahme oder einem Neuladen), ohne die Auswahl zu verlieren, wo möglich. */
    fun refreshed(newCandidates: List<ExportCandidate>): ExportSelection {
        val fresh = newCandidates.associateBy { it.mediaId }
        val kept = selected.filter { id -> fresh[id]?.alreadyExported == false }.toSet()
        return copy(candidates = newCandidates, selected = kept)
    }
}

/** Platzprüfung vor der Übernahme (Paket 3.7). */
object ExportSpacePolicy {
    /**
     * Benötigter Platz auf dem Ziel: die Summe der Dateien plus der Mindest-Restspeicher des Geräts (2 GiB, ADR 0013).
     * Die Reserve bleibt nach dem Schreiben frei, damit das System und andere Apps nicht ausgebremst werden. Der
     * Summenüberlauf wird abgefangen.
     */
    fun requiredBytes(
        totalBytes: Long,
        reserveBytes: Long,
    ): Long {
        require(totalBytes >= 0 && reserveBytes >= 0) { "Größen dürfen nicht negativ sein." }
        return if (totalBytes > Long.MAX_VALUE - reserveBytes) Long.MAX_VALUE else totalBytes + reserveBytes
    }

    fun fits(
        availableBytes: Long,
        totalBytes: Long,
        reserveBytes: Long,
    ): Boolean = availableBytes >= requiredBytes(totalBytes, reserveBytes)
}
