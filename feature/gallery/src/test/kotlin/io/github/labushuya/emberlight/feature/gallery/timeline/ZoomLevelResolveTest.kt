package io.github.labushuya.emberlight.feature.gallery.timeline

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Welche Zoomstufe gilt: die Nutzerwahl hat Vorrang vor dem Gespeicherten, sonst die Standardstufe. Immer begrenzt.
 */
class ZoomLevelResolveTest {
    @Test
    fun aUserChoiceBeatsTheSavedValue() {
        assertEquals(3, resolveZoomLevel(chosen = 3, saved = 0))
    }

    @Test
    fun withoutAChoiceTheSavedValueApplies() {
        assertEquals(ZoomLevels.MAX_LEVEL, resolveZoomLevel(chosen = null, saved = ZoomLevels.MAX_LEVEL))
    }

    @Test
    fun withoutAnythingTheDefaultLevelApplies() {
        assertEquals(ZoomLevels.DEFAULT_LEVEL, resolveZoomLevel(chosen = null, saved = null))
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        assertEquals(ZoomLevels.MAX_LEVEL, resolveZoomLevel(chosen = 99, saved = null))
        assertEquals(ZoomLevels.MIN_LEVEL, resolveZoomLevel(chosen = null, saved = -4))
    }
}
