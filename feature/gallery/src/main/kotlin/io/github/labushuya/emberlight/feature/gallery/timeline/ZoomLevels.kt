package io.github.labushuya.emberlight.feature.gallery.timeline

import kotlin.math.floor

/**
 * Zoomstufen der Zeitleiste. Eine Stufe ist eine Ziel-Kachelbreite in dp, keine Spaltenzahl. Die Spaltenzahl folgt aus
 * der verfügbaren Breite, damit auf breiten Fenstern automatisch mehr Spalten entstehen (ADR 0008).
 *
 * Stufe 0 hat die größten Kacheln, Stufe 4 die kleinsten. Die Gruppierung hängt an der Stufe, nicht an der Spaltenzahl:
 * bis [LAST_DAY_LEVEL] Tagesgruppen, darüber Monatsgruppen. So zeigt ein Tablet auf Standardstufe Tage in vielen
 * Spalten statt Monate.
 */
internal object ZoomLevels {
    /**
     * Ziel-Kachelbreite je Stufe in dp. Die tatsächliche Kachel ist wegen des Rests etwas breiter.
     *
     * Die Werte sind so gewählt, dass die Spaltenzahl auf einem Handy (353 bis 412 dp) bei jeder Stufe aufsteigt, ohne zwei
     * gleiche Werte. Stufe 0 hat mindestens zwei Spalten. Auf dem Standard (Stufe 2) gibt es 4 Spalten (ADR 0008).
     * Bei 353 dp: 2, 3, 4, 5, 6 Spalten. Bei 412 dp: 2, 3, 4, 6, 7 Spalten.
     */
    val TILE_WIDTHS_DP: List<Float> = listOf(170f, 110f, 84f, 64f, 54f)

    const val MIN_LEVEL = 0
    const val MAX_LEVEL = 4
    const val DEFAULT_LEVEL = 2

    /** Letzte Stufe mit Tagesgruppen. Ab der nächsten Stufe gruppiert die Zeitleiste nach Monaten. */
    const val LAST_DAY_LEVEL = 2

    /** Abstand zwischen Zellen und an den Rändern des Rasters. */
    const val GRID_GAP_DP = 2f

    fun clamp(level: Int): Int = level.coerceIn(MIN_LEVEL, MAX_LEVEL)

    /** Spalten = max(1, floor(Breite / Zielbreite)). Ungültige Breiten ergeben eine Spalte. */
    fun columnsFor(
        level: Int,
        widthDp: Float,
    ): Int {
        if (!widthDp.isFinite() || widthDp <= 0f) return 1
        val target = TILE_WIDTHS_DP[clamp(level)]
        return maxOf(1, floor(widthDp / target).toInt())
    }

    fun groupingFor(level: Int): GroupingMode {
        val stage = clamp(level)
        return if (stage <= LAST_DAY_LEVEL) GroupingMode.DAY else GroupingMode.MONTH
    }

    /**
     * Breite einer quadratischen Kachel in derselben Einheit wie [widthPx] und [gapPx]. Der Rand links und rechts
     * zählt wie ein Zwischenraum, daher `n + 1` Abstände.
     */
    fun tileWidth(
        widthPx: Float,
        gapPx: Float,
        columns: Int,
    ): Float = (widthPx - gapPx * (columns + 1)) / columns
}
