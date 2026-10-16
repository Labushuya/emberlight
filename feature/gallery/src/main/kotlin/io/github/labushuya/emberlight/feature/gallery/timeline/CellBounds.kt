@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect

/**
 * Grenzen der sichtbaren Kacheln in Root-Koordinaten, je Medien-ID. Die Hero-Animation des Overlays liest sie beim Öffnen und
 * Schließen (ADR 0010). Bewusst ohne State: Die Kacheln schreiben ihre Lage beim Layout, nie löst das eine Neuzusammensetzung
 * aus.
 */
internal class CellBounds {
    private val rects = HashMap<Long, Rect>()

    fun put(
        id: Long,
        rect: Rect,
    ) {
        rects[id] = rect
    }

    fun remove(id: Long) {
        rects.remove(id)
    }

    /** Grenzen der Kachel [id], `null` wenn sie nicht im Raster liegt (nicht komponiert oder außerhalb des Bildschirms). */
    fun rectOf(id: Long): Rect? = rects[id]
}

/** Die Kachel-Grenzen des umgebenden Rasters. Ohne Raster (Tests, Vorschauen) ist es eine leere, wegwerfbare Tabelle. */
internal val LocalCellBounds = staticCompositionLocalOf { CellBounds() }
