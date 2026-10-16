package io.github.labushuya.emberlight.feature.gallery.timeline

/**
 * Position im Raster für das Element [mediaId], etwa das zuletzt im Viewer angesehene. Die Position zählt Überschriften und
 * Banner mit, sie ist der Index des Rasters. `null`, wenn das Element nicht (mehr) im Raster steht.
 */
internal fun gridScrollTarget(
    sections: List<TimelineSection>,
    index: TimelineIndex,
    mediaId: Long,
): Int? {
    val uri =
        sections
            .asSequence()
            .flatMap { it.items }
            .firstOrNull { it.id == mediaId }
            ?.uri ?: return null
    return index.indexOfMedia(uri)
}

/**
 * Liegt die Position [target] im Bereich, den das Raster von der gespeicherten ersten Position [firstVisible] aus zeigt? Die
 * Schätzung nutzt die Spalten und die Zeilen des Bildschirms, die schon vor dem Layout feststehen. Eine Überschrift zählt als
 * Position, daher bleibt die Schätzung eine Näherung. Sie entscheidet nur, ob vor dem ersten Layout gescrollt wird.
 */
internal fun inVisibleWindow(
    firstVisible: Int,
    target: Int,
    columns: Int,
    rows: Int,
): Boolean {
    if (columns <= 0 || rows <= 0) return false
    return target >= firstVisible && target < firstVisible + columns * rows
}
