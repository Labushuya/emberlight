package io.github.labushuya.emberlight.feature.gallery.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerPositionTest {
    private val items = listOf(viewerItem(1), viewerItem(2), viewerItem(3))

    @Test
    fun keepsTheRequestedElementWhenItIsStillThere() {
        assertEquals(
            ViewerPosition(index = 1, id = 2),
            resolveViewerPosition(items, requestedId = 2, previousIndex = 0),
        )
    }

    @Test
    fun deletedElementMovesToTheNeighbourAtTheSamePlace() {
        // Element 2 ist verschwunden, das Element danach rückt an seine Stelle (Index 1).
        val rest = listOf(viewerItem(1), viewerItem(3))

        assertEquals(ViewerPosition(index = 1, id = 3), resolveViewerPosition(rest, requestedId = 2, previousIndex = 1))
    }

    @Test
    fun deletedLastElementClampsToTheNewLast() {
        val shorter = listOf(viewerItem(1), viewerItem(2))

        assertEquals(
            ViewerPosition(index = 1, id = 2),
            resolveViewerPosition(shorter, requestedId = 3, previousIndex = 2),
        )
    }

    @Test
    fun unknownStartIdFallsBackToThePreviousIndex() {
        assertEquals(
            ViewerPosition(index = 0, id = 1),
            resolveViewerPosition(items, requestedId = 99, previousIndex = 0),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyListHasNoPosition() {
        resolveViewerPosition(emptyList(), requestedId = 1, previousIndex = 0)
    }
}

/** Bild ohne Vorschau, für die Positionslogik nur die ID zählt. */
internal fun viewerItem(
    id: Long,
    type: ViewerMediaType = ViewerMediaType.IMAGE,
): ViewerItem =
    ViewerItem(
        id = id,
        uri = "content://media/external/images/media/$id",
        type = type,
        timestamp = id,
        width = null,
        height = null,
        durationMs = null,
        previewKey = null,
    )
