package io.github.labushuya.emberlight.feature.gallery.timeline

import kotlin.math.roundToInt

/** Sichtbare Zelle im Raster, in Koordinaten des Rasters (wie `LazyGridItemInfo`). */
internal data class GridCell(
    val key: Any,
    val isMedia: Boolean,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/**
 * Schwellen und Dämpfung der Pinch-Geste. Das Verhältnis ist der Fingerabstand seit der letzten Basis. Die Richtung
 * ist so gewählt, dass Auseinanderziehen die Ansicht vergrößert, also zu größeren Kacheln und einer kleineren Stufe führt.
 */
internal object PinchMath {
    /** Ab diesem Verhältnis eine Stufe größere Kacheln (Stufe sinkt). */
    const val ENLARGE_RATIO = 1.3f

    /** Ab diesem Verhältnis eine Stufe kleinere Kacheln (Stufe steigt). */
    const val SHRINK_RATIO = 0.77f

    /** Anteil der Bewegung, der am Rand ankommt. Der Rest wird als Widerstand gedämpft. */
    const val EDGE_RESISTANCE = 0.25f

    /**
     * Neue Stufe, wenn [ratio] eine Schwelle überschreitet, sonst `null`. Am Rand der Stufen gibt es keinen Wechsel.
     */
    fun levelStep(
        ratio: Float,
        level: Int,
    ): Int? =
        when {
            ratio >= ENLARGE_RATIO && level > ZoomLevels.MIN_LEVEL -> level - 1
            ratio <= SHRINK_RATIO && level < ZoomLevels.MAX_LEVEL -> level + 1
            else -> null
        }

    /** Skalierung am Rand: Die Abweichung von 1 wird stark gedämpft, damit die Geste fest wirkt, aber nicht springt. */
    fun resisted(scale: Float): Float = 1f + (scale - 1f) * EDGE_RESISTANCE
}

/**
 * Anker-Verfahren für den Stufenwechsel. Das Element unter dem Fingermittelpunkt bleibt vertikal an derselben Stelle.
 *
 * Vor dem Wechsel liegt der Punkt `pivot` in der Zelle. Die Position im Verhältnis zur Kachelgröße, `fraction`, bleibt
 * über den Wechsel gleich. Die neue Kachel ist `tileNew` groß, daher steht die Zelle danach bei `pivotY - fraction * tileNew`.
 */
internal object ZoomAnchor {
    /**
     * Medien-Zelle, die den Punkt ([x], [y]) enthält. Liegt der Punkt in keiner, die nächstliegende Medien-Zelle.
     * `null`, wenn keine Medien sichtbar sind.
     */
    fun pick(
        cells: List<GridCell>,
        x: Float,
        y: Float,
    ): GridCell? {
        var best: GridCell? = null
        var bestDistance = Float.MAX_VALUE
        for (cell in cells) {
            if (!cell.isMedia) continue
            val dx = distanceOutside(x, cell.left, cell.left + cell.width)
            val dy = distanceOutside(y, cell.top, cell.top + cell.height)
            val distance = dx * dx + dy * dy
            if (distance == 0f) return cell
            if (distance < bestDistance) {
                bestDistance = distance
                best = cell
            }
        }
        return best
    }

    /** Vertikaler Anteil des Punktes innerhalb der Zelle, zwischen 0 und 1. */
    fun fractionWithin(
        y: Float,
        cell: GridCell,
    ): Float {
        if (cell.height <= 0f) return 0f
        return ((y - cell.top) / cell.height).coerceIn(0f, 1f)
    }

    /** Ziel-Oberkante der Zelle nach dem Wechsel, im Koordinatensystem des Rasters. */
    fun targetTop(
        pivotY: Float,
        fraction: Float,
        tileNew: Float,
    ): Float = pivotY - fraction * tileNew

    /**
     * Scroll-Offset für `requestScrollToItem`, damit die Zelle an [targetTop] steht. Die Oberkante der Zelle liegt bei
     * `paddingTop - scrollOffset`, daher ist der Offset die Differenz zur Auflagekante des Rand-Abstands.
     */
    fun scrollOffset(
        targetTop: Float,
        paddingTop: Float,
    ): Int = (paddingTop - targetTop).roundToInt()

    private fun distanceOutside(
        value: Float,
        low: Float,
        high: Float,
    ): Float =
        when {
            value < low -> low - value
            value > high -> value - high
            else -> 0f
        }
}

/** Geplanter Stufenwechsel. Der Anker wird erst gesetzt, wenn das neue Raster in der Komposition steht. */
internal class PendingZoom(
    val level: Int,
    /** URI des Ankers. `null`, wenn kein Medium sichtbar war. Dann bleibt die Position, wie sie ist. */
    val anchorUri: String?,
    val anchorFraction: Float,
    val pivotX: Float,
    val pivotY: Float,
    /** Skalierung, die das neue Raster sofort zeigt, damit die Größe der Kacheln gleich bleibt. */
    val scale: Float,
)
