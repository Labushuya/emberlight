package io.github.labushuya.emberlight.feature.space

import androidx.compose.ui.graphics.toArgb
import io.github.labushuya.emberlight.core.design.theme.SpaceAccents
import io.github.labushuya.emberlight.share.space.management.SpaceAccentPalette
import org.junit.Assert.assertEquals
import org.junit.Test

/** Die Hex-Werte für die Gäste-Seite müssen die Akzentfarben der Oberfläche sein, Farbe für Farbe (ADR 0018). */
class SpaceAccentPaletteTest {
    @Test
    fun theGuestPaletteMatchesTheHostInterfaceColorByColor() {
        val fromInterface = SpaceAccents.colors.map { color -> "#%06X".format(color.toArgb() and 0xFFFFFF) }

        assertEquals(fromInterface, SpaceAccentPalette.HEX)
    }

    @Test
    fun anUnknownIndexFallsBackToTheFirstColorLikeTheInterface() {
        assertEquals(SpaceAccentPalette.HEX.first(), SpaceAccentPalette.hexAt(-1))
        assertEquals(SpaceAccentPalette.HEX.first(), SpaceAccentPalette.hexAt(99))
    }
}
