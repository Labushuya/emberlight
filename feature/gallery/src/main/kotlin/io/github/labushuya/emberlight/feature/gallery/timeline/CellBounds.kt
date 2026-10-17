@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect

/**
 * Grenzen der sichtbaren Kacheln in Root-Koordinaten, je Medien-ID, und das Sichtfenster des Rasters. Die Hero-Animation des
 * Overlays liest sie beim Öffnen und Schließen (ADR 0010). Bewusst ohne State: Die Kacheln schreiben ihre Lage beim Layout,
 * nie löst das eine Neuzusammensetzung aus.
 *
 * Jeder Eintrag trägt einen Zählerstand ([stamp]), der beim Schreiben wächst. So erkennt der Aufrufer, ob eine Kachel nach
 * einer Scroll-Anforderung tatsächlich neu gelegt wurde, und liest keine veralteten Grenzen (Feinschliff ADR 0010).
 */
internal class CellBounds {
    private class Entry(
        val rect: Rect,
        val stamp: Long,
    )

    private val entries = HashMap<Long, Entry>()
    private var counter = 0L

    /** Sichtfenster des Rasters in Root-Koordinaten (ohne Kopfzeile, ohne Leiste). `null`, solange es nicht gemessen ist. */
    var viewport: Rect? = null
        private set

    /** Zählerstand des letzten Schreibens. Vergleichen, ob seither neu gelegt wurde. */
    val stamp: Long get() = counter

    fun put(
        id: Long,
        rect: Rect,
    ) {
        counter += 1
        entries[id] = Entry(rect, counter)
    }

    fun remove(id: Long) {
        entries.remove(id)
    }

    fun setViewport(rect: Rect?) {
        viewport = rect
    }

    /** Grenzen der Kachel [id], `null` wenn sie nicht im Raster liegt (nicht komponiert oder außerhalb des Bildschirms). */
    fun rectOf(id: Long): Rect? = entries[id]?.rect

    /** Zählerstand, zu dem die Grenzen der Kachel [id] geschrieben wurden. `0`, wenn es keinen Eintrag gibt. */
    fun stampOf(id: Long): Long = entries[id]?.stamp ?: 0L
}

/**
 * Liegt die Kachel [tile] vollständig im sichtbaren Bereich? Sichtbar heißt: nicht unter der Kopfzeile ([viewport] oben), nicht
 * unter der Leiste ([bottomLimit], Oberkante der Leiste in Root-Koordinaten, `null` ohne Leiste) und ganz im Raster.
 */
internal fun isTileFullyVisible(
    tile: Rect,
    viewport: Rect,
    bottomLimit: Float?,
): Boolean {
    val bottom = bottomLimit?.let { minOf(it, viewport.bottom) } ?: viewport.bottom
    return tile.left >= viewport.left && tile.right <= viewport.right && tile.top >= viewport.top &&
        tile.bottom <= bottom
}

/** Die Kachel-Grenzen des umgebenden Rasters. Ohne Raster (Tests, Vorschauen) ist es eine leere, wegwerfbare Tabelle. */
internal val LocalCellBounds = staticCompositionLocalOf { CellBounds() }
