package io.github.labushuya.emberlight.feature.gallery.timeline

import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.TODAY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Das Scroll-Ziel für das zuletzt im Viewer angesehene Element (Rückkehr aus dem Viewer). */
class GridScrollTargetTest {
    // Zwei Abschnitte: Kopf 0, Medien 1 und 2, Kopf 3, Medium 4. Mit Banner rutscht alles um eins.
    private val sections =
        listOf(
            TimelineSection(
                key = "day:a",
                header = TimelineHeader.Today,
                items = listOf(TimelineFixtures.item(1, millis = 1L), TimelineFixtures.item(2, millis = 2L)),
            ),
            TimelineSection(
                key = "day:b",
                header = TimelineHeader.Yesterday,
                items = listOf(TimelineFixtures.item(3, millis = 3L)),
            ),
        )

    @Test
    fun targetCountsHeadersInTheGrid() {
        val index = TimelineIndex(sections, leadingItems = 0, today = TODAY)

        assertEquals(4, gridScrollTarget(sections, index, mediaId = 3))
        assertEquals(1, gridScrollTarget(sections, index, mediaId = 1))
    }

    @Test
    fun targetIncludesTheAccessBannerAsLeadingItem() {
        val index = TimelineIndex(sections, leadingItems = 1, today = TODAY)

        assertEquals(5, gridScrollTarget(sections, index, mediaId = 3))
    }

    @Test
    fun elementNoLongerInTheGridHasNoTarget() {
        val index = TimelineIndex(sections, leadingItems = 0, today = TODAY)

        assertNull(gridScrollTarget(sections, index, mediaId = 99))
    }
}
