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
