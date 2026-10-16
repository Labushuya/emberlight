package io.github.labushuya.emberlight.feature.gallery.viewer

/** Gewähltes Element im Viewer: Position in der Liste und Medien-ID. */
internal data class ViewerPosition(
    val index: Int,
    val id: Long,
)

/**
 * Bestimmt die Position nach einer Änderung der Liste oder der gewünschten ID. Liegt das Element noch in der Liste, bleibt es
 * gewählt. Ist es verschwunden, etwa gelöscht, springt die Auswahl an dieselbe Stelle: auf das Element, das jetzt bei
 * [previousIndex] steht, sonst auf das letzte. Die Liste darf nicht leer sein.
 */
internal fun resolveViewerPosition(
    items: List<ViewerItem>,
    requestedId: Long,
    previousIndex: Int,
): ViewerPosition {
    require(items.isNotEmpty()) { "Ohne Elemente gibt es keine Position." }
    val found = items.indexOfFirst { it.id == requestedId }
    if (found >= 0) return ViewerPosition(index = found, id = requestedId)
    val index = previousIndex.coerceIn(0, items.lastIndex)
    return ViewerPosition(index = index, id = items[index].id)
}
